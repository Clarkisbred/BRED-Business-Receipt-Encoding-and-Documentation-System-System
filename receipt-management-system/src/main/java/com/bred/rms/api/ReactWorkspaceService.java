package com.bred.rms.api;

import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.ai.GroqDuckyService;
import com.bred.rms.config.AppContext;
import com.bred.rms.config.AppVersion;
import com.bred.rms.models.ActivityEntry;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.BugReport;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.Company;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.AttachmentRules;
import com.bred.rms.security.Module;
import com.bred.rms.services.ReceiptQuery;
import com.bred.rms.supabase.SupabaseException;
import com.bred.rms.ui.construction.ConstructionPanel;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.ui.map.NominatimGeocoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReactWorkspaceService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DUCKY_HISTORY_TURNS = 20;
    private static final int MAX_DUCKY_HISTORY_CHARS = 12_000;
    private static final int MAX_DUCKY_PROMPT_CHARS = 4_000;
    private final AppContext context;
    private final GroqDuckyService ducky;
    private final NominatimGeocoder geocoder = new NominatimGeocoder();

    ReactWorkspaceService(AppContext context) {
        this.context = context;
        this.ducky = context.supabaseRest == null
                ? new GroqDuckyService(context, new GroqCredentials(), ignored -> { }, ignored -> { })
                : new GroqDuckyService(context, new GroqCredentials(), context.supabaseRest,
                        ignored -> { }, ignored -> { });
    }

    LocalApiServer.ModulePageData load(Module module) {
        return load(module, null);
    }

    LocalApiServer.ModulePageData load(Module module, String table) {
        return switch (module) {
            case PROJECTS -> projects();
            case SUPPLIERS -> suppliers();
            case RECEIPTS -> receipts();
            case MESSAGES -> messages();
            case DUCKY -> ducky();
            case LIVE_MAP -> liveMap();
            case CONSTRUCTION -> construction(table);
            case REPORTS -> reports();
            case USERS -> users();
            case ACTIVITY_LOG -> activityLog();
            case COMPANIES -> companies();
            case HISTORY -> history();
            case SETTINGS -> settings();
            case DASHBOARD -> throw new IllegalArgumentException("The dashboard uses its dedicated data service.");
        };
    }

    LocalApiServer.ModulePageData apply(Module module, String action, String id, JsonNode values) {
        if ("send-password-reset".equals(action)) {
            if (module != Module.USERS) {
                throw new IllegalArgumentException("Password reset emails can only be sent from Users.");
            }
            context.users.sendPasswordReset(uuid(id, "Choose an account."));
            return load(module);
        }
        if ("delete".equals(action)) {
            UUID recordId = uuid(id, "Choose a record to delete.");
            switch (module) {
                case PROJECTS -> context.projects.delete(recordId);
                case SUPPLIERS -> context.suppliers.delete(recordId);
                case RECEIPTS -> context.receipts.delete(recordId);
                case CONSTRUCTION -> deleteConstruction(id, values);
                case COMPANIES -> context.companies.delete(recordId);
                case USERS -> context.users.delete(recordId);
                default -> throw new IllegalArgumentException("This workspace does not support deleting records.");
            }
            return load(module);
        }
        if (module == Module.CONSTRUCTION && isBillingAction(action)) {
            JsonNode input = values == null || !values.isObject() ? null : values;
            if (input == null) throw new IllegalArgumentException("The billing request must be an object.");
            return applyBillingAction(action, id, input);
        }
        if (module == Module.CONSTRUCTION && isRoyaltyAction(action)) {
            JsonNode input = values == null || !values.isObject() ? null : values;
            if (input == null) throw new IllegalArgumentException("The royalty request must be an object.");
            return applyRoyaltyAction(action, id, input);
        }
        if (!"save".equals(action) && !"send".equals(action) && !"ask".equals(action)
                && !"report".equals(action) && !"publish-release".equals(action)) {
            throw new IllegalArgumentException("Choose a supported workspace action.");
        }
        if (("report".equals(action) || "publish-release".equals(action)) && module != Module.SETTINGS) {
            throw new IllegalArgumentException("This action is only available from Settings.");
        }

        JsonNode input = values == null || !values.isObject() ? null : values;
        if (input == null) throw new IllegalArgumentException("The workspace record must be an object.");
        return switch (module) {
            case PROJECTS -> saveProject(id, input);
            case SUPPLIERS -> saveSupplier(id, input);
            case RECEIPTS -> saveReceipt(id, input);
            case MESSAGES -> sendMessage(input);
            case DUCKY -> askDucky(input);
            case LIVE_MAP -> saveMapMarker(id, input);
            case CONSTRUCTION -> saveConstruction(id, input);
            case USERS -> saveUser(id, input);
            case COMPANIES -> saveCompany(id, input);
            case SETTINGS -> switch (action) {
                case "report" -> submitBugReport(input);
                case "publish-release" -> publishRelease(input);
                default -> saveSettings(input);
            };
            default -> throw new IllegalArgumentException("This workspace does not support that action.");
        };
    }

    List<LocalApiServer.MapSearchResult> searchMap(String query) {
        String search = query == null ? "" : query.trim();
        if (search.isEmpty() || search.length() > 160) {
            throw new IllegalArgumentException("Enter a place or address to search.");
        }
        try {
            return geocoder.search(search).stream()
                    .map(result -> new LocalApiServer.MapSearchResult(result.displayName(),
                            Double.toString(result.latitude()), Double.toString(result.longitude()),
                            result.type()))
                    .toList();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Location search failed: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Location search was interrupted.", error);
        }
    }

    private LocalApiServer.ModulePageData projects() {
        List<Receipt> receipts = context.receipts.search(ReceiptQuery.all());
        List<Project> projects = context.projects.findAll();
        Map<UUID, BigDecimal> spend = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Receipt receipt : receipts) {
            if (receipt.projectId() == null) continue;
            spend.merge(receipt.projectId(), receipt.grandTotal(), BigDecimal::add);
            counts.merge(receipt.projectId(), 1, Integer::sum);
        }
        Map<UUID, BigDecimal> income = new LinkedHashMap<>();
        Map<UUID, Integer> billingCounts = new LinkedHashMap<>();
        Map<UUID, BigDecimal> revisedContract = new LinkedHashMap<>();
        projects.forEach(project -> {
            revisedContract.put(project.id(), project.revisedContractAmount().signum() > 0
                    ? project.revisedContractAmount() : project.contractAmount());
            income.put(project.id(), BigDecimal.ZERO);
        });
        if (context.supabaseRest != null) {
            try {
                for (JsonNode billing : context.supabaseRest.selectAll("project_billings",
                        "select=project_id,net_amount,status&status=neq.Cancelled")) {
                    UUID projectId = uuidOrNull(billing.path("project_id").asText());
                    if (projectId == null) continue;
                    BigDecimal amount = billing.path("net_amount").isNumber()
                            ? billing.path("net_amount").decimalValue() : BigDecimal.ZERO;
                    income.merge(projectId, amount, BigDecimal::add);
                    billingCounts.merge(projectId, 1, Integer::sum);
                }
                for (JsonNode variation : context.supabaseRest.selectAll("project_variation_orders",
                        "select=project_id,amount_effect&status=eq.Approved")) {
                    UUID projectId = uuidOrNull(variation.path("project_id").asText());
                    if (projectId == null) continue;
                    BigDecimal effect = variation.path("amount_effect").isNumber()
                            ? variation.path("amount_effect").decimalValue() : BigDecimal.ZERO;
                    Project project = projects.stream().filter(item -> item.id().equals(projectId)).findFirst().orElse(null);
                    if (project == null || project.revisedContractAmount().signum() == 0) {
                        revisedContract.merge(projectId, effect, BigDecimal::add);
                    }
                }
            } catch (SupabaseException ignored) {
                // The project panel still shows receipt spend and original contract values.
            }
        }
        BigDecimal totalIncome = income.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpend = spend.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfit = totalIncome.subtract(totalSpend);
        BigDecimal profitPercent = totalIncome.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalProfit.multiply(BigDecimal.valueOf(100)).divide(totalIncome, 2, java.math.RoundingMode.HALF_UP);
        List<LocalApiServer.ModuleMetricPoint> profitChart = projects.stream().map(project -> {
            BigDecimal projectIncome = income.getOrDefault(project.id(), BigDecimal.ZERO);
            BigDecimal projectProfit = projectIncome.subtract(spend.getOrDefault(project.id(), BigDecimal.ZERO));
            return new LocalApiServer.ModuleMetricPoint(project.name(), projectProfit);
        }).toList();
        List<LocalApiServer.ModuleRecord> rows = projects.stream().map(project -> {
            BigDecimal projectIncome = income.getOrDefault(project.id(), BigDecimal.ZERO);
            BigDecimal projectSpend = spend.getOrDefault(project.id(), BigDecimal.ZERO);
            BigDecimal projectProfit = projectIncome.subtract(projectSpend);
            BigDecimal revised = revisedContract.getOrDefault(project.id(), project.contractAmount());
            BigDecimal revisionEffect = revised.subtract(project.contractAmount());
            return record(
                project.id().toString(),
                "name", project.name(),
                "code", project.code(),
                "fundSource", project.fundSource(),
                "contractAmount", money(project.contractAmount()),
                "revisedContractAmount", money(revised),
                "revisionEffect", money(revisionEffect),
                "receiptCount", counts.getOrDefault(project.id(), 0),
                "billingCount", billingCounts.getOrDefault(project.id(), 0),
                "billings", money(projectIncome),
                "income", money(projectIncome),
                "spend", money(projectSpend),
                "profitLoss", money(projectProfit),
                "profitLossStatus", projectProfit.signum() >= 0 ? "Profit" : "Loss");
        }).toList();
        return page(Module.PROJECTS, "Projects", "Manage project records and review receipt activity.",
                List.of(metric("Total projects", projects.size()),
                        metric("Projects with receipts", counts.size()),
                        metric("Project income", pesoMoney(totalIncome),
                                "Approved and recorded billings"),
                        chartMetric("Profit / Loss", pesoMoney(totalProfit),
                                profitPercent + "% " + (totalProfit.signum() >= 0 ? "profit margin" : "loss margin"),
                                profitChart)),
                List.of(column("name", "Project"), column("code", "Code"),
                        column("fundSource", "Fund source"), column("receiptCount", "Receipts", true),
                        column("billingCount", "Billings", true),
                        column("income", "Project income", true),
                        column("spend", "Receipt spend", true),
                        column("profitLoss", "Profit / Loss", true),
                        column("revisedContractAmount", "Revised contract", true)),
                List.of(field("name", "Project name", "text", true),
                        field("code", "Project code", "text", true),
                        field("fundSource", "Fund source", "text", false),
                        field("contractAmount", "Contract amount", "number", false),
                        field("revisedContractAmount", "Revised contract amount", "number", false)),
                rows, true, true, currentRole().canManageCompany(), "New project", "Save project", "save");
    }

    private LocalApiServer.ModulePageData suppliers() {
        List<Supplier> suppliers = context.suppliers.findAll();
        List<LocalApiServer.ModuleRecord> rows = suppliers.stream().map(supplier -> record(
                supplier.id().toString(),
                "businessName", supplier.businessName(),
                "tin", supplier.tin(),
                "address", supplier.address(),
                "contactNumber", supplier.contactNumber(),
                "taxClassification", supplier.taxClassification()))
                .toList();
        return page(Module.SUPPLIERS, "Suppliers", "Maintain supplier details used by receipts and project records.",
                List.of(metric("Total suppliers", suppliers.size())),
                List.of(column("businessName", "Supplier"), column("tin", "TIN"),
                        column("taxClassification", "Tax classification"),
                        column("address", "Address"), column("contactNumber", "Contact")),
                List.of(field("businessName", "Business name", "text", true),
                        field("tin", "Tax identification number", "text", true),
                        field("address", "Address", "text", false),
                        field("contactNumber", "Contact number", "text", false),
                        field("taxClassification", "Tax classification", "select", true,
                                List.of(option("VAT Registered", "VAT_REGISTERED"),
                                        option("Non-VAT", "NON_VAT"),
                                        option("VAT-exempt", "VAT_EXEMPT"),
                                        option("Unclassified", "UNCLASSIFIED")),
                                "UNCLASSIFIED")),
                rows, true, true, currentRole().canManageCompany(), "New supplier", "Save supplier", "save");
    }

    private LocalApiServer.ModulePageData receipts() {
        List<Receipt> receipts = context.receipts.search(ReceiptQuery.all());
        Map<UUID, Supplier> suppliers = new LinkedHashMap<>();
        context.suppliers.findAll().forEach(supplier -> suppliers.put(supplier.id(), supplier));
        Map<UUID, Project> projects = new LinkedHashMap<>();
        context.projects.findAll().forEach(project -> projects.put(project.id(), project));
        List<LocalApiServer.ModuleOption> supplierOptions = suppliers.values().stream()
                .map(supplier -> new LocalApiServer.ModuleOption(supplier.businessName(),
                        supplier.id().toString(),
                        "TIN " + supplier.tin() + (supplier.address().isBlank() ? "" : " · " + supplier.address()),
                        supplier.taxClassification()))
                .toList();
        List<LocalApiServer.ModuleOption> projectOptions = projects.values().stream()
                .map(project -> option(project.name(), project.id().toString())).toList();
        List<LocalApiServer.ModuleRecord> rows = receipts.stream().map(receipt -> record(
                receipt.id().toString(),
                "receiptNo", receipt.receiptNo(),
                "date", receipt.date(),
                "type", receipt.type().label(),
                "supplierId", receipt.supplierId(),
                "supplier", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).businessName() : "Unavailable supplier",
                "supplierTaxClassification", receipt.supplierTaxClassification(),
                "supplierTin", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).tin() : "",
                "supplierAddress", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).address() : "",
                "project", receipt.projectId() == null ? "Unassigned"
                        : projects.containsKey(receipt.projectId()) ? projects.get(receipt.projectId()).name()
                        : "Unavailable project",
                "projectId", receipt.projectId(),
                "typeValue", receipt.type().dbValue(),
                "classification", receipt.classification(),
                "itemDescription", receipt.items().stream().map(ReceiptItem::description)
                        .filter(description -> description != null && !description.isBlank())
                        .collect(java.util.stream.Collectors.joining(", ")),
                "itemDetails", receipt.items().stream()
                        .map(item -> item.description() + " — " + item.quantity().stripTrailingZeros().toPlainString()
                                + " " + item.unit() + " × " + money(item.unitPrice())
                                + " = " + money(item.subtotal()))
                        .collect(java.util.stream.Collectors.joining("; ")),
                "itemCount", receipt.items().size(),
                "items", receipt.items().stream().map(item -> Map.of(
                        "description", item.description(),
                        "quantity", item.quantity().stripTrailingZeros().toPlainString(),
                        "unit", item.unit(),
                        "unitPrice", item.unitPrice().stripTrailingZeros().toPlainString())).toList(),
                "subtotal", money(receipt.subtotal()),
                "discount", money(receipt.discount()),
                "discountValue", receipt.discount().toPlainString(),
                "vat", money(receipt.vat()),
                "vatValue", receipt.vat().toPlainString(),
                "additionalCharges", money(receipt.additionalCharges()),
                "additionalChargesValue", receipt.additionalCharges().toPlainString(),
                "notes", receipt.notes(),
                "paymentMethod", receipt.paymentMethod(),
                "ewtRate", receipt.ewtRate().toPlainString(),
                "ewtAmount", money(receipt.ewtAmount()),
                "netTotal", money(receipt.netTotal()),
                "attachmentNames", receipt.attachments().stream().map(attachment -> attachment.fileName())
                        .collect(java.util.stream.Collectors.joining(", ")),
                "attachments", receipt.attachments().stream().map(attachment -> Map.of(
                        "fileName", attachment.fileName(), "storagePath", attachment.storagePath())).toList(),
                "total", money(receipt.grandTotal())))
                .toList();
        return page(Module.RECEIPTS, "Receipts", "Search encoded receipts and add a receipt record.",
                List.of(metric("Total receipts", receipts.size()),
                        metric("Net payables", money(receipts.stream().map(Receipt::netTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))),
                        metric("Accumulated EWT", money(receipts.stream().map(Receipt::ewtAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)))),
                List.of(column("receiptNo", "Receipt no."), column("date", "Date"),
                        column("type", "Document type"), column("supplier", "Supplier"),
                        column("supplierTaxClassification", "Supplier tax class"),
                        column("project", "Project"), column("classification", "Category"),
                        column("netTotal", "Net payable", true)),
                List.of(field("receiptNo", "Receipt number", "text", true),
                        field("date", "Receipt date", "date", true),
                        field("type", "Document type", "select", true, receiptTypeOptions()),
                        field("supplierId", "Supplier", "select", true, supplierOptions),
                        field("projectId", "Project", "select", false, projectOptions),
                        field("classification", "Category", "text", true),
                        field("paymentMethod", "Payment account", "select", true, List.of(
                                option("Cash", "Cash"), option("Card", "Card"), option("E-wallet", "E-wallet"))),
                        field("notes", "Personal notes", "textarea", false),
                        field("ewtRate", "EWT percentage", "number", false),
                        field("discount", "Discount", "number", false),
                        field("vat", "VAT", "number", false),
                        field("additionalCharges", "Additional charges", "number", false)),
                rows, true, true, currentRole().canManageCompany(), "New receipt", "Save receipt", "save");
    }

    private LocalApiServer.ModulePageData messages() {
        User user = currentUser();
        List<User> contacts = context.userRepository == null
                ? List.of() : context.userRepository.findMessagingContacts().stream()
                        .filter(contact -> contact.active() && !contact.id().equals(user.id())).toList();
        List<ChatMessage> messages = new ArrayList<>(context.messages.findFor(user.id()));
        if (user.companyId() != null) messages.addAll(context.messages.findCompanyChat(user.companyId()));
        messages.sort(java.util.Comparator.comparing(ChatMessage::sentAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).reversed());
        List<LocalApiServer.ModuleRecord> rows = messages.stream().map(message -> record(
                message.id().toString(),
                "sentAt", message.sentAt(),
                "senderId", message.senderId(),
                "recipientId", message.recipientId(),
                "companyId", message.companyId(),
                "broadcast", message.broadcast(),
                "urgent", message.urgent(),
                "sender", label(message.senderName(), "Deleted user"),
                "recipient", message.companyId() != null ? "Company chat"
                        : label(message.recipientName(), "Deleted user"),
                "type", message.companyId() != null
                        ? message.urgent() ? "Company · urgent" : "Company"
                        : message.broadcast() ? "Broadcast" : "Direct",
                "body", message.body()))
                .toList();
        List<LocalApiServer.ModuleField> fields = new ArrayList<>();
        fields.add(field("body", "Message", "textarea", true));
        if (!contacts.isEmpty()) {
            fields.add(field("recipientId", "Teammate", "select", false,
                    contacts.stream().map(contact -> option(contact.fullName(), contact.id().toString())).toList()));
        }
        if (user.companyId() != null) {
            fields.add(field("companyChat", "Send to company chat", "checkbox", false));
            fields.add(field("urgent", "Mark as urgent", "checkbox", false));
        }
        return page(Module.MESSAGES, "Custom Messages", "Read company chat and send a direct or company message.",
                List.of(metric("Messages", rows.size()), metric("Teammates", contacts.size())),
                List.of(column("sentAt", "Sent"), column("sender", "From"), column("recipient", "To"),
                        column("type", "Type"), column("body", "Message")),
                fields, rows, true, false, false, "New message", "Send message", "send");
    }

    private LocalApiServer.ModulePageData ducky() {
        return page(Module.DUCKY, "Ducky", "Ask BRED's AI assistant. Private BRED data is not shared without confirmation.",
                List.of(metric("AI tools", "BRED-authorized"), metric("Private data", "Protected")),
                List.of(column("answer", "Ducky's response")),
                List.of(field("question", "Ask Ducky", "textarea", true)),
                List.of(), true, false, false, "Ask Ducky", "Send question", "ask");
    }

    private LocalApiServer.ModulePageData liveMap() {
        List<JsonNode> markers = context.constructionData.rows("project_map_markers");
        Map<String, String> projectNames = new LinkedHashMap<>();
        context.projects.findAll().forEach(project -> projectNames.put(project.id().toString(), project.name()));
        List<LocalApiServer.ModuleRecord> rows = markers.stream().map(marker -> {
            String projectId = text(marker, "project_id");
            return record(text(marker, "id"),
                    "markerName", text(marker, "marker_name"),
                    "project", projectNames.getOrDefault(projectId, "Unassigned"),
                    "status", text(marker, "status"),
                    "latitude", text(marker, "latitude"),
                    "longitude", text(marker, "longitude"),
                    "markerType", text(marker, "marker_type"));
        }).toList();
        List<LocalApiServer.ModuleOption> projectOptions = context.projects.findAll().stream()
                .map(project -> option(project.name(), project.id().toString())).toList();
        return page(Module.LIVE_MAP, "Live Project Map", "Review verified project markers and record a mapped location.",
                List.of(metric("Verified locations", rows.size())),
                List.of(column("markerName", "Location"), column("project", "Project"),
                        column("status", "Status"), column("latitude", "Latitude", true),
                        column("longitude", "Longitude", true), column("markerType", "Type")),
                List.of(field("markerName", "Location name", "text", true),
                        field("projectId", "Project", "select", false, projectOptions),
                        field("latitude", "Latitude", "number", true),
                        field("longitude", "Longitude", "number", true),
                        field("status", "Status", "text", false)),
                rows, context.supabaseRest != null, false, false, "Add location", "Save location", "save");
    }

    private LocalApiServer.ModulePageData construction(String table) {
        List<ConstructionPanel.WorkspaceTab> availableTabs = ConstructionPanel.workspaceTabs();
        ConstructionPanel.WorkspaceTab tab = table == null || table.isBlank()
                ? availableTabs.get(0) : ConstructionPanel.workspaceTab(table);
        if (tab == null) throw new IllegalArgumentException("Choose a valid construction section.");
        if ("royalty_records".equals(tab.table())) return royaltiesPage();

        List<JsonNode> sourceRows = context.constructionData.rows(tab.table());
        Map<String, Map<String, Object>> billingDetails = "project_billings".equals(tab.table())
                && context.supabaseRest != null
                ? constructionBillingDetails() : Map.of();
        List<LocalApiServer.ModuleRecord> rows = sourceRows.stream()
                .map(source -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    tab.fields().forEach(field -> values.put(field.key(), jsonValue(source.get(field.key()))));
                    values.putAll(billingDetails.getOrDefault(text(source, "id"), Map.of()));
                    return new LocalApiServer.ModuleRecord(text(source, "id"), values);
                })
                .toList();
        var billingAnalytics = context.constructionData.billingAnalytics();
        var costAnalytics = context.constructionData.boqCostAnalytics();
        var summary = billingAnalytics.summary();
        var cost = costAnalytics.summary();
        Map<String, String> projectNames = new LinkedHashMap<>();
        context.projects.findAll().forEach(project -> projectNames.put(project.id().toString(), project.name()));
        Map<String, BigDecimal> grossByProject = new LinkedHashMap<>();
        Map<String, BigDecimal> collectedByProject = new LinkedHashMap<>();
        Map<String, BigDecimal> receivableByProject = new LinkedHashMap<>();
        billingAnalytics.byProject().forEach(project -> {
            grossByProject.put(project.projectId(), project.grossBillings());
            collectedByProject.put(project.projectId(), project.collected());
            receivableByProject.put(project.projectId(), project.receivable());
        });
        Map<String, BigDecimal> varianceByProject = new LinkedHashMap<>();
        costAnalytics.byProject().forEach(project ->
                varianceByProject.put(project.projectId(), project.variance()));
        var recordBreakdown = constructionRecordBreakdown(sourceRows, tab.title(), projectNames);
        List<LocalApiServer.ConstructionCategory> categories = constructionCategories();
        List<LocalApiServer.ModuleField> fields = tab.fields().stream()
                .map(field -> {
                    List<LocalApiServer.ModuleOption> options = "project_id".equals(field.key())
                            ? projectNames.entrySet().stream()
                                    .map(entry -> option(entry.getValue(), entry.getKey())).toList()
                            : constructionFieldOptions(field.key());
                    return new LocalApiServer.ModuleField(field.key(), field.label(),
                            options.isEmpty() ? constructionFieldType(field.type()) : "select",
                            field.required(), options, null);
                })
                .toList();
        List<LocalApiServer.ModuleColumn> columns = tab.fields().stream()
                .map(field -> column(field.key(), field.label(), "NUMBER".equals(field.type())))
                .toList();
        boolean canWrite = context.supabaseRest != null && currentRole().canManageCompany();
        boolean billingWorkflow = "project_billings".equals(tab.table());
        boolean canEdit = canWrite && (!isBillingTable(tab.table()) || billingWorkflow);
        boolean canDelete = canWrite && !isBillingTable(tab.table());
        List<Map<String, Object>> billingConfigurations = "project_billings".equals(tab.table())
                ? projectBillingRows("project_billing_configurations") : List.of();
        List<Map<String, Object>> billingRetentionReleases = "project_billings".equals(tab.table())
                ? projectBillingRows("project_billing_retention_releases") : List.of();
        List<Map<String, Object>> billingLegacyRetention = "project_billings".equals(tab.table())
                ? projectBillingRows("retention_ledger") : List.of();
        List<Map<String, Object>> billingMobilizationBalances = "project_billings".equals(tab.table())
                ? projectBillingRows("advance_payment_recoveries") : List.of();
        return new LocalApiServer.ModulePageData(Module.CONSTRUCTION.name(), tab.title(),
                "Manage " + tab.title().toLowerCase(java.util.Locale.ROOT) + " records.",
                List.of(chartMetric("Gross billings", money(summary.grossBillings()), "By project",
                                projectMetricPoints(grossByProject, projectNames)),
                        chartMetric("Collected", money(summary.collected()), "By project",
                                projectMetricPoints(collectedByProject, projectNames)),
                        chartMetric("Receivables", money(summary.receivable()), "By project",
                                projectMetricPoints(receivableByProject, projectNames)),
                        chartMetric("BOQ variance", money(cost.variance()), "By project",
                                projectMetricPoints(varianceByProject, projectNames)),
                        chartMetric(tab.title() + " records", String.valueOf(rows.size()),
                                recordBreakdown.note(), recordBreakdown.points())),
                columns, fields, rows, canWrite && (!isBillingTable(tab.table()) || billingWorkflow),
                canEdit, canDelete,
                "Add " + tab.title().toLowerCase(java.util.Locale.ROOT),
                "Save record", "save", categories, billingConfigurations, billingRetentionReleases,
                billingLegacyRetention, billingMobilizationBalances);
    }

    private LocalApiServer.ModulePageData royaltiesPage() {
        User user = currentUser();
        UUID companyId = user.companyId();
        boolean connected = context.supabaseRest != null;
        if (connected && companyId == null) {
            throw new IllegalStateException("Assign this account to a company before opening Royalties.");
        }
        boolean canWrite = connected && currentRole().canManageCompany();
        List<LocalApiServer.ModuleRecord> records = new ArrayList<>();
        List<Map<String, Object>> contracts = new ArrayList<>();
        List<Map<String, Object>> suppliers = new ArrayList<>();
        List<Map<String, Object>> returns = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> configuration = defaultRoyaltyConfiguration();
        if (connected) {
            try {
                String companyFilter = "&company_id=eq." + companyId;
                List<Map<String, Object>> savedRecords = royaltyRows(
                        "royalty_records", "select=*" + companyFilter
                                + "&order=billing_date.asc,created_at.asc");
                records.addAll(savedRecords.stream()
                        .map(row -> new LocalApiServer.ModuleRecord(mapText(row, "id"), row)).toList());
                List<Map<String, Object>> savedConfiguration = royaltyRows(
                        "royalty_company_settings", "select=*" + companyFilter);
                if (!savedConfiguration.isEmpty()) configuration = savedConfiguration.get(0);
                contracts.addAll(royaltyRows("project_contracts",
                        "select=id,project_id,client_name,contract_name,contract_amount"
                                + companyFilter + "&order=contract_name.asc"));
                returns.addAll(royaltyRows("royalty_returns",
                        "select=*" + companyFilter + "&order=return_date.asc,created_at.asc"));
                events.addAll(royaltyRows("royalty_record_events",
                        "select=*" + companyFilter + "&order=created_at.asc"));
                suppliers.addAll(royaltyRows("suppliers",
                        "select=id,business_name,tax_classification" + companyFilter
                                + "&order=business_name.asc"));
                List<Map<String, Object>> projects = royaltyRows("projects",
                        "select=id,name" + companyFilter);
                Map<String, String> projectNames = new LinkedHashMap<>();
                projects.forEach(project ->
                        projectNames.put(mapText(project, "id"), mapText(project, "name")));
                contracts.forEach(contract ->
                        contract.put("project_name", projectNames.getOrDefault(
                                mapText(contract, "project_id"), "Project not available")));
            } catch (SupabaseException error) {
                throw new IllegalStateException("Royalty records could not be loaded: " + error.getMessage(), error);
            }
        }

        BigDecimal totalBilling = royaltySum(records, "billing_amount");
        BigDecimal totalRoyalties = royaltySum(records, "royalty_amount");
        BigDecimal pendingReturn = records.stream()
                .filter(row -> "pending".equalsIgnoreCase(mapText(row.values(), "status")))
                .map(row -> amount(row.values(), "outstanding_return"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReturned = royaltySum(records, "returned_amount");
        return new LocalApiServer.ModulePageData(Module.CONSTRUCTION.name(), "Royalties",
                context.supabaseRest == null
                        ? "Connect to the Cloud Database to manage company-scoped royalty records."
                        : "Manage saved royalty records, withholding returns, and company-specific calculation settings.",
                List.of(metric("Gross billing", money(totalBilling)),
                        metric("Royalty accrued", money(totalRoyalties)),
                        metric("Pending returns", money(pendingReturn)),
                        metric("Amount returned", money(totalReturned)),
                        metric("Records", records.size())),
                List.of(column("client_name", "Client"), column("billing_date", "Billing date"),
                        column("billing_amount", "Billing amount", true),
                        column("royalty_amount", "Royalty", true),
                        column("withholding_amount", "Withholding", true),
                        column("status", "Return status")),
                List.of(), records, canWrite, canWrite, false, "New royalty record",
                "Save royalty record", "save", constructionCategories(),
                List.of(), List.of(), List.of(), List.of(), configuration, contracts, suppliers, returns, events);
    }

    private List<Map<String, Object>> royaltyRows(String table, String query) throws SupabaseException {
        return context.supabaseRest.selectAll(table, query).stream()
                .map(row -> JSON.convertValue(row, new TypeReference<Map<String, Object>>() { }))
                .toList();
    }

    private static BigDecimal royaltySum(List<LocalApiServer.ModuleRecord> rows, String key) {
        return rows.stream().map(row -> amount(row.values(), key))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal amount(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    private static String mapText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static Map<String, Object> defaultRoyaltyConfiguration() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("default_vat_rate", null);
        defaults.put("default_royalty_rate", new BigDecimal("3"));
        defaults.put("default_royalty_basis", "VAT_EXCLUSIVE");
        defaults.put("default_withholding_rate", new BigDecimal("2"));
        defaults.put("default_withholding_basis", "VAT_EXCLUSIVE");
        defaults.put("default_additional_withholding_rate", null);
        defaults.put("default_additional_withholding_basis", "VAT_EXCLUSIVE");
        defaults.put("default_ewt_rate", null);
        defaults.put("default_ewt_basis", "VAT_EXCLUSIVE");
        defaults.put("default_retention_rate", null);
        defaults.put("default_retention_basis", "VAT_EXCLUSIVE");
        defaults.put("deduction_confirmation_threshold", new BigDecimal("10000"));
        return defaults;
    }

    private static List<LocalApiServer.ConstructionCategory> constructionCategories() {
        return ConstructionPanel.workspaceCategories().stream()
                .map(category -> new LocalApiServer.ConstructionCategory(category.title(),
                        category.tabs().stream().map(item ->
                                new LocalApiServer.ConstructionTabInfo(item.title(), item.table())).toList()))
                .toList();
    }

    private static ConstructionRecordBreakdown constructionRecordBreakdown(
            List<JsonNode> rows, String title, Map<String, String> projectNames) {
        Map<String, BigDecimal> counts = new LinkedHashMap<>();
        boolean hasStatus = rows.stream().anyMatch(row -> row.has("status"));
        boolean hasProject = rows.stream().anyMatch(row -> row.has("project_id"));
        if (hasStatus) {
            for (JsonNode row : rows) {
                String status = text(row, "status");
                counts.merge(status.isBlank() ? "Unspecified" : status, BigDecimal.ONE, BigDecimal::add);
            }
            return new ConstructionRecordBreakdown("By status", metricPoints(counts, Map.of()));
        }
        if (hasProject) {
            for (JsonNode row : rows) {
                String projectId = text(row, "project_id");
                if (projectId.isBlank()) continue;
                String label = projectNames.getOrDefault(projectId, projectLabel(projectId));
                counts.merge(label, BigDecimal.ONE, BigDecimal::add);
            }
            return new ConstructionRecordBreakdown("By project", metricPoints(counts, Map.of()));
        }
        if (!rows.isEmpty()) counts.put(title, BigDecimal.valueOf(rows.size()));
        return new ConstructionRecordBreakdown("Records", metricPoints(counts, Map.of()));
    }

    private static List<LocalApiServer.ModuleMetricPoint> projectMetricPoints(
            Map<String, BigDecimal> values, Map<String, String> projectNames) {
        Map<String, BigDecimal> labeled = new LinkedHashMap<>();
        values.forEach((projectId, value) ->
                labeled.put(projectNames.getOrDefault(projectId, projectLabel(projectId)), value));
        return metricPoints(labeled, Map.of());
    }

    private static List<LocalApiServer.ModuleMetricPoint> metricPoints(
            Map<String, BigDecimal> values, Map<String, String> labels) {
        return values.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() != 0)
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal> entry) ->
                        entry.getValue().abs()).reversed())
                .limit(5)
                .map(entry -> new LocalApiServer.ModuleMetricPoint(
                        labels.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()))
                .toList();
    }

    private static String projectLabel(String projectId) {
        return "Project " + projectId.substring(0, Math.min(8, projectId.length()));
    }

    private record ConstructionRecordBreakdown(
            String note, List<LocalApiServer.ModuleMetricPoint> points) {}

    private LocalApiServer.ModulePageData saveConstruction(String id, JsonNode input) {
        String table = text(input, "table");
        ConstructionPanel.WorkspaceTab tab = ConstructionPanel.workspaceTab(table);
        if (tab == null) throw new IllegalArgumentException("Choose a valid construction section.");
        if (isBillingTable(table)) {
            throw new IllegalArgumentException("Billing records must be managed through the billing workflow.");
        }
        if (isRoyaltyTable(table)) {
            throw new IllegalArgumentException("Royalty records must be managed through the royalty workflow.");
        }
        requireConstructionWrite();
        UUID recordId = optionalUuid(id);
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConstructionPanel.WorkspaceField field : tab.fields()) {
            JsonNode value = input.get(field.key());
            String raw = value == null || value.isNull() ? "" : value.asText("");
            if ("null".equalsIgnoreCase(raw.trim())) raw = "";
            if (raw.isBlank()) {
                if (field.required()) {
                    throw new IllegalArgumentException(field.label() + " is required.");
                }
                continue;
            }
            values.put(field.key(), constructionFieldValue(field, value, raw));
        }
        context.constructionData.saveRow(tab.table(), recordId, values);
        return construction(tab.table());
    }

    private LocalApiServer.ModulePageData deleteConstruction(String id, JsonNode input) {
        String table = text(input, "table");
        ConstructionPanel.WorkspaceTab tab = ConstructionPanel.workspaceTab(table);
        if (tab == null) throw new IllegalArgumentException("Choose a valid construction section.");
        if (isBillingTable(table)) {
            throw new IllegalArgumentException("Billing records cannot be deleted. Use the authorized billing reversal workflow.");
        }
        if (isRoyaltyTable(table)) {
            throw new IllegalArgumentException("Royalty records are audit-preserved and cannot be deleted.");
        }
        requireConstructionWrite();
        context.constructionData.deleteRow(tab.table(), uuid(id, "Choose a record to delete."));
        return construction(tab.table());
    }

    private void requireConstructionWrite() {
        if (context.supabaseRest == null) {
            throw new IllegalStateException("Construction changes require a Cloud Database connection.");
        }
        if (!currentRole().canManageCompany()) {
            throw new SecurityException("Your BRED role cannot change construction records.");
        }
    }

    private LocalApiServer.ModulePageData applyBillingAction(String action, String id, JsonNode input) {
            if (!"project_billings".equals(text(input, "table"))) {
                throw new IllegalArgumentException("Choose the Billings section to manage billing transactions.");
            }
            requireConstructionWrite();
            if (context.supabaseRest == null) {
                throw new IllegalStateException("Billing transactions require a Cloud Database connection.");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            try {
                switch (action) {
                    case "create-billing" -> {
                        JsonNode result = context.supabaseRest.rpc("create_project_billing", Map.of("p_payload", input));
                        if (input.path("attachments").isArray() && !input.path("attachments").isEmpty()) {
                            uploadBillingDocuments(uuid(result.path("id").asText(), "The billing was saved without a usable record id."),
                                    input.path("attachments"));
                        }
                    }
                    case "amend-billing" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_payload", input);
                        args.put("p_reason", nullableText(input, "amendment_reason"));
                        context.supabaseRest.rpc("amend_project_billing", args);
                    }
                    case "save-billing-configuration" -> {
                        args.put("p_project_id", text(input, "project_id"));
                        args.put("p_contract_id", nullableText(input, "contract_id"));
                        args.put("p_settings", input.path("settings"));
                        context.supabaseRest.rpc("save_project_billing_configuration", args);
                    }
                    case "decide-billing-adjustment" -> {
                        args.put("p_adjustment_id", text(input, "adjustment_id"));
                        args.put("p_approve", input.path("approve").asBoolean(false));
                        args.put("p_note", nullableText(input, "note"));
                        context.supabaseRest.rpc("decide_project_billing_adjustment", args);
                    }
                    case "decide-billing" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_approve", input.path("approve").asBoolean(false));
                        args.put("p_note", nullableText(input, "note"));
                        context.supabaseRest.rpc("decide_project_billing", args);
                    }
                    case "reverse-billing" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_reason", text(input, "reason"));
                        context.supabaseRest.rpc("reverse_project_billing", args);
                    }
                    case "attach-billing-documents" ->
                            uploadBillingDocuments(uuid(id, "Choose a billing record before attaching documents."),
                                    input.path("attachments"));
                    case "record-billing-payment" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_amount", decimal(input, "amount"));
                        args.put("p_payment_date", nullableText(input, "payment_date"));
                        args.put("p_payment_method", nullableText(input, "payment_method"));
                        args.put("p_reference_number", nullableText(input, "reference_number"));
                        args.put("p_bank_reference", nullableText(input, "bank_reference"));
                        args.put("p_supporting_document_path", nullableText(input, "supporting_document_path"));
                        args.put("p_remarks", nullableText(input, "remarks"));
                        args.put("p_idempotency_key", text(input, "idempotency_key"));
                        context.supabaseRest.rpc("record_project_billing_payment", args);
                    }
                    case "refund-billing-payment" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_amount", decimal(input, "amount"));
                        args.put("p_refund_date", nullableText(input, "refund_date"));
                        args.put("p_reference_number", nullableText(input, "reference_number"));
                        args.put("p_reason", text(input, "reason"));
                        args.put("p_idempotency_key", text(input, "idempotency_key"));
                        context.supabaseRest.rpc("refund_project_billing_payment", args);
                    }
                    case "record-withholding-remittance" -> {
                        args.put("p_billing_id", id == null ? text(input, "billing_id") : id);
                        args.put("p_amount", decimal(input, "amount"));
                        args.put("p_remittance_date", nullableText(input, "remittance_date"));
                        args.put("p_payment_reference", nullableText(input, "payment_reference"));
                        args.put("p_proof_document_path", nullableText(input, "proof_document_path"));
                        args.put("p_idempotency_key", text(input, "idempotency_key"));
                        context.supabaseRest.rpc("record_project_billing_withholding_remittance", args);
                    }
                    case "release-billing-retention" -> {
                        args.put("p_project_id", text(input, "project_id"));
                        args.put("p_contract_id", nullableText(input, "contract_id"));
                        args.put("p_amount", decimal(input, "amount"));
                        args.put("p_release_date", nullableText(input, "release_date"));
                        args.put("p_reference_number", nullableText(input, "reference_number"));
                        args.put("p_document_path", nullableText(input, "document_path"));
                        args.put("p_notes", nullableText(input, "notes"));
                        args.put("p_idempotency_key", text(input, "idempotency_key"));
                        context.supabaseRest.rpc("release_project_billing_retention", args);
                    }
                    default -> throw new IllegalArgumentException("Choose a supported billing action.");
                }
            } catch (SupabaseException error) {
                throw new IllegalStateException("Billing transaction could not be completed: " + error.getMessage(), error);
            }
            return construction("project_billings");
        }

    private Map<String, Map<String, Object>> constructionBillingDetails() {
            Map<String, Map<String, Object>> details = new LinkedHashMap<>();
            addBillingRows(details, "project_billing_lines", "lines", "");
            addBillingRows(details, "project_billing_adjustments", "adjustments", "&order=created_at.desc");
            addBillingRows(details, "project_billing_collections", "payments", "&order=created_at.desc");
            addBillingRows(details, "project_billing_withholding_remittances", "withholding_remittances", "&order=remittance_date.desc");
            addBillingRows(details, "project_billing_attachments", "attachments", "&order=created_at.desc");
            addBillingRows(details, "project_billing_events", "activity", "&order=created_at.desc");
            return details;
        }

        private void uploadBillingDocuments(UUID billingId, JsonNode documentNodes) throws SupabaseException {
            if (documentNodes == null || !documentNodes.isArray() || documentNodes.isEmpty()) {
                throw new IllegalArgumentException("Choose at least one PNG, JPG, or PDF billing document.");
            }
            if (documentNodes.size() > 10) {
                throw new IllegalArgumentException("Attach no more than 10 billing documents at a time.");
            }
            long totalBytes = 0;
            List<String> uploadedPaths = new ArrayList<>();
            var metadata = JSON.createArrayNode();
            try {
                for (JsonNode node : documentNodes) {
                    String name = text(node, "name").trim();
                    String contentType = AttachmentRules.contentType(name);
                    if (name.isBlank() || name.length() > 255 || contentType == null) {
                        throw new IllegalArgumentException("Billing documents must be PNG, JPG, or PDF files.");
                    }
                    UUID requestKey = uuid(text(node, "idempotency_key"),
                            "A unique key is required for every billing document.");
                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(text(node, "data"));
                    } catch (IllegalArgumentException invalidBase64) {
                        throw new IllegalArgumentException("A billing document could not be read. Choose it again.");
                    }
                    try {
                        totalBytes += bytes.length;
                        if (bytes.length == 0 || totalBytes > AttachmentRules.MAX_BYTES) {
                            throw new IllegalArgumentException("Billing documents must total 10 MB or less.");
                        }
                        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
                        String path = billingId + "/" + requestKey + "-" + safeName;
                        context.supabaseRest.uploadObject("receipts", path, bytes, contentType, true);
                        uploadedPaths.add(path);
                        metadata.addObject()
                                .put("name", name)
                                .put("document_path", path)
                                .put("attachment_type", contentType)
                                .put("idempotency_key", requestKey.toString());
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
                context.supabaseRest.rpc("attach_project_billing_documents", Map.of(
                        "p_billing_id", billingId.toString(),
                        "p_documents", metadata));
            } catch (SupabaseException failure) {
                if (failure.status() >= 400 && failure.status() < 500) {
                    cleanupBillingUploads(uploadedPaths, failure);
                }
                throw failure;
            } catch (RuntimeException failure) {
                cleanupBillingUploads(uploadedPaths, failure);
                throw failure;
            }
        }

        private void cleanupBillingUploads(List<String> uploadedPaths, Throwable failure) {
            for (String path : uploadedPaths) {
                try {
                    context.supabaseRest.deleteObject("receipts", path);
                } catch (SupabaseException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }

    private void addBillingRows(
                Map<String, Map<String, Object>> details, String table, String key, String order) {
            try {
                for (JsonNode row : context.supabaseRest.selectAll(table, "select=*" + order)) {
                    String billingId = text(row, "billing_id");
                    if (billingId.isBlank()) continue;
                    Map<String, Object> item = JSON.convertValue(row, Map.class);
                    details.computeIfAbsent(billingId, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(key, ignored -> new ArrayList<>());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items =
                            (List<Map<String, Object>>) details.get(billingId).get(key);
                    items.add(item);
                }
            } catch (SupabaseException error) {
                throw new IllegalStateException("Billing history could not be loaded: " + error.getMessage(), error);
            }
        }

    private LocalApiServer.ModulePageData applyRoyaltyAction(String action, String id, JsonNode input) {
        if (!"royalty_records".equals(text(input, "table"))) {
            throw new IllegalArgumentException("Choose the Royalties section to manage royalty records.");
        }
        requireConstructionWrite();
        if (context.supabaseRest == null) {
            throw new IllegalStateException("Royalty records require a Cloud Database connection.");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            switch (action) {
                case "save-royalty" -> {
                    args.put("p_id", id == null || id.isBlank() ? null : uuid(id, "Choose a royalty record to edit."));
                    args.put("p_payload", input);
                    context.supabaseRest.rpc("save_royalty_record", args);
                }
                case "save-royalty-configuration" -> {
                    args.put("p_settings", input);
                    context.supabaseRest.rpc("save_royalty_company_settings", args);
                }
                case "record-royalty-return" -> {
                    args.put("p_record_id", uuid(text(input, "record_id"), "Choose a royalty record."));
                    args.put("p_payload", input);
                    context.supabaseRest.rpc("record_royalty_return", args);
                }
                default -> throw new IllegalArgumentException("Choose a supported royalty action.");
            }
        } catch (SupabaseException error) {
            throw new IllegalStateException("The royalty action could not be saved: " + error.getMessage(), error);
        }
        return construction("royalty_records");
    }

    private List<Map<String, Object>> projectBillingRows(String table) {
            if (context.supabaseRest == null) return List.of();
            try {
                return context.supabaseRest.selectAll(table, "select=*").stream()
                        .map(row -> JSON.convertValue(row, new TypeReference<Map<String, Object>>() { }))
                        .toList();
            } catch (SupabaseException error) {
                throw new IllegalStateException("Project billing balances could not be loaded: " + error.getMessage(), error);
            }
        }

    private static boolean isBillingAction(String action) {
        return Set.of("create-billing", "amend-billing", "save-billing-configuration",
                "decide-billing-adjustment", "decide-billing", "reverse-billing", "record-billing-payment",
                "refund-billing-payment", "record-withholding-remittance",
                "attach-billing-documents", "release-billing-retention").contains(action);
        }

    private static boolean isRoyaltyAction(String action) {
        return Set.of("save-royalty", "save-royalty-configuration", "record-royalty-return").contains(action);
    }

    private static boolean isBillingTable(String table) {
            return Set.of("project_billings", "project_billing_lines", "project_billing_deductions",
                    "project_billing_collections", "project_billing_attachments",
                    "project_billing_configurations", "project_billing_adjustments",
                    "project_billing_retention_releases", "project_billing_events").contains(table);
        }

    private static boolean isRoyaltyTable(String table) {
        return Set.of("royalty_records", "royalty_company_settings", "royalty_returns",
                "royalty_record_events").contains(table);
    }

    private static String nullableText(JsonNode values, String field) {
            String value = text(values, field);
            return value.isBlank() ? null : value;
        }

    private List<LocalApiServer.ModuleOption> constructionFieldOptions(String key) {
        if ("project_id".equals(key)) {
            return context.projects.findAll().stream()
                    .map(project -> option(project.name(), project.id().toString())).toList();
        }
        if ("contract_id".equals(key)) {
            return context.constructionData.rows("project_contracts").stream()
                    .map(row -> option(
                            first(row, "contract_name", "contract_number"),
                            text(row, "id"),
                            text(row, "project_id")))
                    .filter(option -> !option.value().isBlank())
                    .toList();
        }
        if ("supplier_id".equals(key) || "owner_supplier_id".equals(key)) {
            return context.suppliers.findAll().stream()
                    .map(supplier -> option(supplier.businessName(), supplier.id().toString())).toList();
        }
        return List.of();
    }

    private static String constructionFieldType(String type) {
        return switch (type) {
            case "NUMBER" -> "number";
            case "DATE" -> "date";
            case "BOOLEAN" -> "checkbox";
            case "JSON" -> "textarea";
            default -> "text";
        };
    }

    private static Object constructionFieldValue(ConstructionPanel.WorkspaceField field, JsonNode value, String raw) {
        try {
            return switch (field.type()) {
                case "NUMBER" -> new BigDecimal(raw);
                case "BOOLEAN" -> {
                    if (value != null && value.isBoolean()) yield value.booleanValue();
                    if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                        yield Boolean.parseBoolean(raw);
                    }
                    throw new IllegalArgumentException(field.label() + " must be true or false.");
                }
                case "JSON" -> JSON.readTree(raw);
                default -> raw;
            };
        } catch (NumberFormatException | JsonProcessingException invalid) {
            throw new IllegalArgumentException("Enter a valid " + field.label().toLowerCase(java.util.Locale.ROOT) + ".", invalid);
        }
    }

    private LocalApiServer.ModulePageData reports() {
        List<LocalApiServer.ModuleRecord> rows = new ArrayList<>();
        for (var total : context.reports.totalsByProject(null, null)) {
            rows.add(record("project-" + total.label(), "report", "By project",
                    "label", total.label(), "count", total.receiptCount(), "amount", money(total.amount())));
        }
        for (var total : context.reports.totalsBySupplier(null, null)) {
            rows.add(record("supplier-" + total.label(), "report", "By supplier",
                    "label", total.label(), "count", total.receiptCount(), "amount", money(total.amount())));
        }
        for (var total : context.reports.totalsByMonth(null, null)) {
            rows.add(record("month-" + total.label(), "report", "By month",
                    "label", total.label(), "count", total.receiptCount(), "amount", money(total.amount())));
        }
        List<Receipt> receipts = context.reports.receipts(null, null);
        BigDecimal total = receipts.stream().map(Receipt::grandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return page(Module.REPORTS, "Reports", "Review real receipt totals grouped by project and supplier.",
                List.of(metric("Receipts", receipts.size()), metric("Receipt spend", money(total))),
                List.of(column("report", "Report"), column("label", "Group"),
                        column("count", "Receipts", true), column("amount", "Total", true)),
                List.of(), rows, false, false, false, "", "", "save");
    }

    private LocalApiServer.ModulePageData users() {
        List<User> users = context.users.list();
        List<LocalApiServer.ModuleRecord> rows = users.stream().map(user -> record(
                user.id().toString(), "fullName", user.fullName(), "email", user.email(),
                "role", user.role().name(), "active", user.active(),
                "accountStatus", user.accountStatus().name(), "company", user.companyName()))
                .toList();
        List<LocalApiServer.ModuleOption> roles = new ArrayList<>(List.of(
                option("Admin", "ADMIN"), option("Encoder", "ENCODER")));
        if (currentRole() == Role.CREATOR) roles.add(0, option("Creator", "CREATOR"));
        return page(Module.USERS, "Users", "Manage BRED accounts with server-enforced company roles.",
                List.of(metric("Accounts", users.size()),
                        metric("Active accounts", users.stream().filter(User::active).count())),
                List.of(column("fullName", "Name"), column("email", "Email"), column("role", "Role"),
                        column("company", "Company"), column("accountStatus", "Account status"),
                        column("active", "Active")),
                List.of(field("fullName", "Full name", "text", true),
                        field("email", "Email address", "email", true),
                        field("role", "Role", "select", true, roles),
                        field("active", "Account active", "checkbox", false),
                        field("password", "Temporary password (new users)", "password", false)),
                rows, true, true, currentRole() == Role.CREATOR, "New user", "Save user", "save");
    }

    private LocalApiServer.ModulePageData activityLog() {
        List<ActivityEntry> entries = context.activityLog == null
                ? List.of() : context.activityLog.findAll();
        List<LocalApiServer.ModuleRecord> rows = entries.stream().map(entry -> record(
                entry.at() + "-" + entry.userId(),
                "at", entry.at(), "user", label(entry.userName(), "System"),
                "action", entry.action(), "detail", entry.detail()))
                .toList();
        return page(Module.ACTIVITY_LOG, "Activity Log", "Review recent account and data changes.",
                List.of(metric("Log entries", rows.size())),
                List.of(column("at", "When"), column("user", "User"),
                        column("action", "Action"), column("detail", "Details")),
                List.of(), rows, false, false, false, "", "", "save");
    }

    private LocalApiServer.ModulePageData companies() {
        if (!context.companies.isSupported()) {
            return page(Module.COMPANIES, "Companies",
                    "Company management is available when BRED is connected to Cloud Database.",
                    List.of(), List.of(column("name", "Company")), List.of(), List.of(),
                    false, false, false, "", "", "save");
        }
        List<Company> companies = context.companies.list();
        Map<UUID, BigDecimal> expenses = new LinkedHashMap<>();
        Map<UUID, Integer> admins = new LinkedHashMap<>();
        Map<UUID, Integer> encoders = new LinkedHashMap<>();
        Map<UUID, Integer> receiptCounts = new LinkedHashMap<>();
        companies.forEach(company -> expenses.put(company.id(), BigDecimal.ZERO));
        for (User user : context.users.list()) {
            if (!user.active() || user.companyId() == null || !expenses.containsKey(user.companyId())) continue;
            if (user.role() == Role.ADMIN) admins.merge(user.companyId(), 1, Integer::sum);
            else if (user.role() == Role.ENCODER) encoders.merge(user.companyId(), 1, Integer::sum);
        }
        for (Receipt receipt : context.receipts.search(ReceiptQuery.all())) {
            UUID companyId = receipt.companyId();
            if (companyId == null || !expenses.containsKey(companyId)) continue;
            expenses.merge(companyId, receipt.grandTotal(), BigDecimal::add);
            receiptCounts.merge(companyId, 1, Integer::sum);
        }
        List<LocalApiServer.ModuleRecord> rows = companies.stream().map(company -> {
            UUID id = company.id();
            return record(id.toString(),
                    "name", company.name(),
                    "expenses", money(expenses.getOrDefault(id, BigDecimal.ZERO)),
                    "admins", admins.getOrDefault(id, 0),
                    "encoders", encoders.getOrDefault(id, 0),
                    "totalReceipts", receiptCounts.getOrDefault(id, 0));
        }).toList();
        return page(Module.COMPANIES, "Companies", "Manage company records available to BRED accounts.",
                List.of(metric("Companies", companies.size())),
                List.of(column("name", "Company"), column("expenses", "Expenses", true),
                        column("admins", "Admins", true), column("encoders", "Encoders", true),
                        column("totalReceipts", "Receipts", true)),
                List.of(field("name", "Company name", "text", true)),
                rows, false, true, true, "", "Save company", "save");
    }

    private LocalApiServer.ModulePageData history() {
        List<ChatMessage> messages = context.messageService.findHistory();
        List<LocalApiServer.ModuleRecord> rows = messages.stream().map(message -> record(
                message.id().toString(),
                "sentAt", message.sentAt(),
                "sender", label(message.senderName(), "Deleted user"),
                "recipient", message.companyId() != null ? "Company chat"
                        : label(message.recipientName(), "Deleted user"),
                "type", message.companyId() != null
                        ? message.urgent() ? "Company · urgent" : "Company"
                        : message.broadcast() ? "Broadcast" : "Direct",
                "body", message.body()))
                .toList();
        return page(Module.HISTORY, "History", "Search the Creator's cross-company message history.",
                List.of(metric("Messages", rows.size())),
                List.of(column("sentAt", "When"), column("sender", "From"),
                        column("recipient", "To"), column("type", "Type"), column("body", "Message")),
                List.of(), rows, false, false, false, "", "", "save");
    }

    private LocalApiServer.ModulePageData settings() {
        User user = currentUser();
        ThemeManager.ReactTheme theme = ThemeManager.reactTheme();
        List<LocalApiServer.ModuleMetric> metrics = new ArrayList<>(List.of(
                metric("Account", user.fullName()), metric("Role", user.role().label()),
                metric("Company", label(user.companyName(), user.role() == Role.CREATOR ? "All companies" : "Not assigned")),
                metric("Email", user.email()), metric("Installed version", AppVersion.current()),
                latestReleaseMetric(), metric("Data storage", context.storageLabel),
                metric("Bug report submissions", context.bugReports == null ? "Unavailable" : "Available"),
                metric("Release publishing", context.appReleases == null ? "Unavailable" : "Available")));
        List<LocalApiServer.ModuleRecord> submittedReports = new ArrayList<>();
        if (user.role() == Role.CREATOR && context.bugReports != null) {
            for (BugReport report : context.bugReports.listForCreator()) {
                submittedReports.add(record(report.id().toString(), "title", report.title(), "company", report.companyName(),
                        "reporter", report.reporterName(), "email", report.reporterEmail(),
                        "description", report.description(), "createdAt", report.createdAt()));
            }
        }
        return page(Module.SETTINGS, "Settings", "Manage your BRED account, appearance, and support requests.",
                metrics,
                List.of(column("setting", "Setting"), column("value", "Current value")),
                List.of(field("appearance", "Theme preset", "select", true, appearanceOptions(),
                                theme.appearance()),
                        field("dark", "Dark appearance", "checkbox", false, List.of(), theme.dark()),
                        field("newPassword", "New password (optional)", "password", false),
                        field("confirmPassword", "Confirm new password", "password", false)),
                submittedReports, false, false, false, "", "Save preferences", "save");
    }

    private LocalApiServer.ModuleMetric latestReleaseMetric() {
        if (context.appReleases == null) {
            return new LocalApiServer.ModuleMetric("Latest release", "Unavailable", "Not connected to shared releases.");
        }
        try {
            return metric("Latest release", context.appReleases.current().version());
        } catch (RepositoryException error) {
            return new LocalApiServer.ModuleMetric("Latest release", "Unavailable", error.getMessage());
        }
    }

    private LocalApiServer.ModulePageData submitBugReport(JsonNode values) {
        if (context.bugReports == null) {
            throw new IllegalStateException("Bug reports are unavailable without a Cloud Database connection.");
        }
        context.bugReports.submit(text(values, "title"), text(values, "description"), null, null, null);
        return settings();
    }

    private LocalApiServer.ModulePageData publishRelease(JsonNode values) {
        if (context.appReleases == null) {
            throw new IllegalStateException("Publishing a release requires a Cloud Database connection.");
        }
        context.appReleases.publish(text(values, "version"));
        return settings();
    }

    private LocalApiServer.ModulePageData saveProject(String id, JsonNode values) {
        UUID projectId = optionalUuid(id);
        BigDecimal contractAmount = decimal(values, "contractAmount");
        BigDecimal revisedContractAmount = decimal(values, "revisedContractAmount");
        if (projectId != null) {
            Project existing = context.projects.findAll().stream()
                    .filter(project -> project.id().equals(projectId))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                if (text(values, "contractAmount").isBlank()) contractAmount = existing.contractAmount();
                if (text(values, "revisedContractAmount").isBlank()) revisedContractAmount = existing.revisedContractAmount();
            }
        } else if (text(values, "revisedContractAmount").isBlank()) {
            revisedContractAmount = contractAmount;
        }
        context.projects.save(new Project(projectId, text(values, "name"), text(values, "code"),
                text(values, "fundSource"),
                contractAmount,
                revisedContractAmount));
        return projects();
    }

    private LocalApiServer.ModulePageData saveSupplier(String id, JsonNode values) {
        UUID supplierId = optionalUuid(id);
        context.suppliers.save(new Supplier(supplierId, text(values, "businessName"),
                text(values, "tin"), text(values, "address"), text(values, "contactNumber"),
                text(values, "taxClassification")));
        return suppliers();
    }

    private LocalApiServer.ModulePageData saveReceipt(String id, JsonNode values) {
        Receipt previous = id == null || id.isBlank() ? null
                : context.receipts.find(uuid(id, "Choose a receipt to edit."))
                        .orElseThrow(() -> new IllegalArgumentException("That receipt is no longer available."));
        JsonNode itemNodes = values.path("items");
        if (!itemNodes.isArray()) throw new IllegalArgumentException("Add at least one item.");
        List<ReceiptItem> items = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            items.add(new ReceiptItem(text(item, "description"), new BigDecimal(text(item, "quantity")),
                    text(item, "unit").isBlank() ? "pc" : text(item, "unit"),
                    new BigDecimal(text(item, "unitPrice"))));
        }
        String notes = text(values, "notes");
        if (notes.length() > 2000) throw new IllegalArgumentException("Personal notes can be up to 2000 characters.");
        BigDecimal ewtRate = decimal(values, "ewtRate");
        if (ewtRate.signum() < 0 || ewtRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("EWT percentage must be from 0 to 100.");
        }
        String paymentMethod = text(values, "paymentMethod");
        if (!List.of("Cash", "Card", "E-wallet").contains(paymentMethod)) {
            throw new IllegalArgumentException("Choose Cash, Card, or E-wallet.");
        }
        String classification = text(values, "classification").trim();
        if (classification.isEmpty()) throw new IllegalArgumentException("Enter a category.");
        UUID receiptId = previous == null ? UUID.randomUUID() : previous.id();
        Receipt receipt = new Receipt(receiptId, text(values, "receiptNo"),
                LocalDate.parse(text(values, "date")),
                ReceiptType.fromDb(text(values, "type")),
                uuid(text(values, "supplierId"), "Choose a supplier."),
                optionalUuid(text(values, "projectId")),
                items,
                decimal(values, "discount"), decimal(values, "vat"),
                decimal(values, "additionalCharges"), List.of(), null,
                previous == null ? null : previous.createdAt(),
                previous == null ? null : previous.modifiedAt(),
                previous == null ? null : previous.companyId(),
                notes, paymentMethod, ewtRate, classification,
                text(values, "supplierTaxClassification"));
        List<Attachment> retained = retainedReceiptAttachments(previous, values.path("existingAttachmentPaths"));
        List<Attachment> uploaded = uploadReceiptAttachments(receiptId, values.path("attachments"));
        Receipt saved;
        try {
            List<Attachment> attachments = new ArrayList<>(retained);
            attachments.addAll(uploaded);
            saved = context.receipts.save(receipt.withAttachments(attachments));
        } catch (RuntimeException failure) {
            for (Attachment attachment : uploaded) {
                try {
                    context.attachments.remove(attachment);
                } catch (AttachmentException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        if (!saved.classification().equalsIgnoreCase(receipt.classification())) {
            throw new IllegalStateException("The receipt was saved, but its category was not stored. "
                    + "Apply database migration 039_receipt_classification.sql, then retry the category change.");
        }
        return receipts();
    }

    private static List<Attachment> retainedReceiptAttachments(Receipt previous, JsonNode requestedPaths) {
        if (requestedPaths.isMissingNode() || requestedPaths.isNull()) return List.of();
        if (!requestedPaths.isArray()) throw new IllegalArgumentException("Receipt attachments must be a list.");
        if (previous == null && !requestedPaths.isEmpty()) {
            throw new IllegalArgumentException("Attachments can only be retained when editing a receipt.");
        }
        Map<String, Attachment> available = new LinkedHashMap<>();
        if (previous != null) {
            previous.attachments().forEach(attachment -> available.put(attachment.storagePath(), attachment));
        }
        List<Attachment> retained = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode pathNode : requestedPaths) {
            String path = pathNode.asText("").trim();
            Attachment attachment = available.get(path);
            if (attachment == null || !seen.add(path)) {
                throw new IllegalArgumentException("An attachment is no longer available on this receipt.");
            }
            retained.add(attachment);
        }
        return List.copyOf(retained);
    }

    private List<Attachment> uploadReceiptAttachments(UUID receiptId, JsonNode attachmentNodes) {
        if (attachmentNodes.isMissingNode() || attachmentNodes.isNull()) return List.of();
        if (!attachmentNodes.isArray()) throw new IllegalArgumentException("Receipt attachments must be a list.");
        List<Attachment> uploaded = new ArrayList<>();
        long totalBytes = 0;
        try {
            for (JsonNode attachmentNode : attachmentNodes) {
                String name = text(attachmentNode, "name").trim();
                String encoded = text(attachmentNode, "data");
                if (name.isBlank() || AttachmentRules.contentType(name) == null) {
                    throw new IllegalArgumentException("Attachments must be png, jpg or pdf files.");
                }
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException invalidBase64) {
                    throw new IllegalArgumentException("An attachment could not be read. Please choose it again.");
                }
                try {
                    totalBytes += bytes.length;
                    if (bytes.length == 0) throw new IllegalArgumentException("An attachment is empty.");
                    if (bytes.length > AttachmentRules.MAX_BYTES || totalBytes > AttachmentRules.MAX_BYTES) {
                        throw new IllegalArgumentException("Receipt attachments must total 10 MB or less.");
                    }
                    String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
                    Path directory;
                    try {
                        directory = Files.createTempDirectory("bred-receipt-");
                    } catch (java.io.IOException ioFailure) {
                        throw new IllegalStateException("Could not prepare the receipt attachment.", ioFailure);
                    }
                    Path temporaryFile = directory.resolve(safeName);
                    RuntimeException operationFailure = null;
                    try {
                        Files.write(temporaryFile, bytes);
                        uploaded.add(context.attachments.upload(receiptId, temporaryFile));
                    } catch (AttachmentException uploadFailure) {
                        operationFailure = new IllegalArgumentException(uploadFailure.getMessage(), uploadFailure);
                        throw operationFailure;
                    } catch (java.io.IOException ioFailure) {
                        operationFailure = new IllegalStateException("Could not read the selected receipt attachment.", ioFailure);
                        throw operationFailure;
                    } catch (RuntimeException runtimeFailure) {
                        operationFailure = runtimeFailure;
                        throw runtimeFailure;
                    } finally {
                        try {
                            Files.deleteIfExists(temporaryFile);
                            Files.deleteIfExists(directory);
                        } catch (java.io.IOException cleanupFailure) {
                            if (operationFailure != null) operationFailure.addSuppressed(cleanupFailure);
                            else throw new IllegalStateException("Could not clean up the temporary receipt attachment.", cleanupFailure);
                        }
                    }
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (RuntimeException failure) {
            for (Attachment attachment : uploaded) {
                try {
                    context.attachments.remove(attachment);
                } catch (AttachmentException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return List.copyOf(uploaded);
    }

    private LocalApiServer.ModulePageData sendMessage(JsonNode values) {
        User user = currentUser();
        String body = text(values, "body").trim();
        if (body.isEmpty()) throw new IllegalArgumentException("Write a message before sending.");
        if (values.path("broadcast").asBoolean(false)) {
            if (user.role() != Role.CREATOR) {
                throw new SecurityException("Only the Creator can send broadcasts.");
            }
            JsonNode requestedRecipients = values.path("recipientIds");
            if (!requestedRecipients.isArray() || requestedRecipients.size() == 0) {
                throw new IllegalArgumentException("Choose at least one recipient.");
            }
            Set<UUID> availableRecipients = context.userRepository == null
                    ? Set.of() : context.userRepository.findMessagingContacts().stream()
                            .filter(User::active)
                            .filter(contact -> !contact.id().equals(user.id()))
                            .map(User::id)
                            .collect(java.util.stream.Collectors.toSet());
            List<UUID> recipientIds = new ArrayList<>();
            Set<UUID> selectedRecipients = new HashSet<>();
            for (JsonNode recipient : requestedRecipients) {
                if (!recipient.isTextual()) {
                    throw new IllegalArgumentException("Choose valid broadcast recipients.");
                }
                UUID recipientId = uuid(recipient.asText(), "Choose valid broadcast recipients.");
                if (!availableRecipients.contains(recipientId)) {
                    throw new SecurityException("A broadcast recipient is not available to your account.");
                }
                if (selectedRecipients.add(recipientId)) recipientIds.add(recipientId);
            }
            int sentCount = context.messageService.sendBroadcast(recipientIds, body);
            if (sentCount != recipientIds.size()) {
                throw new IllegalStateException("The broadcast was not confirmed for every selected recipient.");
            }
        } else if (values.path("companyChat").asBoolean(false)) {
            context.messages.sendCompanyChat(user.companyId(), body, values.path("urgent").asBoolean(false));
        } else {
            UUID recipientId = uuid(text(values, "recipientId"), "Choose a teammate.");
            boolean available = context.userRepository != null && context.userRepository.findMessagingContacts().stream()
                    .anyMatch(contact -> contact.active() && recipientId.equals(contact.id())
                            && !recipientId.equals(user.id()));
            if (!available) throw new SecurityException("That message recipient is not available to your account.");
            context.messages.send(recipientId, body);
        }
        return messages();
    }

    private LocalApiServer.ModulePageData askDucky(JsonNode values) {
        String question = text(values, "question");
        if (question.isBlank()) throw new IllegalArgumentException("Ask Ducky a question before sending.");
        if (question.length() > MAX_DUCKY_PROMPT_CHARS) {
            throw new IllegalArgumentException("Questions can be up to 4000 characters.");
        }
        List<String> priorTurns = duckyHistory(values.path("history"));
        try {
            GroqDuckyService.AgentResponse response = ducky.respond(question, priorTurns, List.of(),
                    (title, details) -> false, null);
            return page(Module.DUCKY, "Ducky", "Ask BRED's AI assistant. Private BRED data is not shared without confirmation.",
                    List.of(metric("AI tools", "BRED-authorized"), metric("Private data", "Protected")),
                    List.of(column("answer", "Ducky's response")),
                    List.of(field("question", "Ask a follow-up", "textarea", true)),
                    List.of(record(UUID.randomUUID().toString(), "answer", response.answer())),
                    true, false, false, "Ask Ducky", "Send question", "ask");
        } catch (com.bred.rms.ai.AIProviderException | com.bred.rms.importer.DocumentImportException error) {
            throw new IllegalStateException(error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ducky was interrupted before the response completed.", error);
        }
    }

    private static List<String> duckyHistory(JsonNode history) {
        if (history.isMissingNode() || history.isNull()) return List.of();
        if (!history.isArray()) throw new IllegalArgumentException("Chat history must be a list of messages.");
        if (history.size() > MAX_DUCKY_HISTORY_TURNS) {
            throw new IllegalArgumentException("Only the latest 20 chat messages can be sent to Ducky.");
        }
        List<String> turns = new ArrayList<>(history.size());
        int totalChars = 0;
        for (JsonNode turn : history) {
            if (!turn.isTextual() || turn.asText().isBlank()) {
                throw new IllegalArgumentException("Chat history contains an invalid message.");
            }
            String text = turn.asText();
            if (text.length() > MAX_DUCKY_PROMPT_CHARS) {
                throw new IllegalArgumentException("Chat history messages can be up to 4000 characters.");
            }
            totalChars += text.length();
            if (totalChars > MAX_DUCKY_HISTORY_CHARS) {
                throw new IllegalArgumentException("The latest chat history is too long to send.");
            }
            turns.add(text);
        }
        return List.copyOf(turns);
    }

    private LocalApiServer.ModulePageData saveMapMarker(String id, JsonNode values) {
        String markerName = text(values, "markerName").trim();
        if (markerName.isEmpty()) throw new IllegalArgumentException("Location name is required.");
        BigDecimal latitude = new BigDecimal(text(values, "latitude"));
        BigDecimal longitude = new BigDecimal(text(values, "longitude"));
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Enter valid latitude and longitude coordinates.");
        }
        String projectValue = text(values, "projectId");
        UUID projectId = optionalUuid(projectValue);
        if (projectId != null && context.projects.findAll().stream()
                .noneMatch(project -> projectId.equals(project.id()))) {
            throw new IllegalArgumentException("Choose a project available to your account.");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("marker_name", markerName);
        row.put("project_id", projectId == null ? null : projectId.toString());
        row.put("latitude", latitude);
        row.put("longitude", longitude);
        row.put("status", text(values, "status").isBlank() ? "Verified" : text(values, "status"));
        row.put("marker_type", "Site");
        context.constructionData.saveRow("project_map_markers", optionalUuid(id), row);
        return liveMap();
    }

    private LocalApiServer.ModulePageData saveUser(String id, JsonNode values) {
        UUID userId = optionalUuid(id);
        String fullName = text(values, "fullName");
        Role role = Role.valueOf(text(values, "role").toUpperCase(java.util.Locale.ROOT));
        if (userId == null) {
            char[] password = text(values, "password").toCharArray();
            try {
                context.users.create(fullName, text(values, "email"), role, password);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        } else {
            context.users.update(userId, fullName, role, values.path("active").asBoolean());
        }
        return users();
    }

    private LocalApiServer.ModulePageData saveCompany(String id, JsonNode values) {
        context.companies.update(uuid(id, "Choose a company."), text(values, "name"));
        return companies();
    }

    private LocalApiServer.ModulePageData saveSettings(JsonNode values) {
        String appearance = text(values, "appearance");
        if (!appearance.isBlank()) {
            ThemeManager.setAppearance(ThemeManager.Appearance.valueOf(appearance));
            ThemeManager.apply(values.path("dark").asBoolean());
        }
        String newPassword = text(values, "newPassword");
        String confirmPassword = text(values, "confirmPassword");
        if (!newPassword.isEmpty() || !confirmPassword.isEmpty()) {
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("The new passwords do not match.");
            }
            if (context.auth == null) throw new IllegalStateException("Password changes are unavailable in this mode.");
            try {
                context.auth.changePassword(newPassword);
            } catch (com.bred.rms.services.AuthException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        }
        return settings();
    }

    private Role currentRole() {
        return currentUser().role();
    }

    private User currentUser() {
        User user = context.currentUser.get();
        if (user == null || !user.active()) throw new SecurityException("Sign in to continue.");
        return user;
    }

    private static LocalApiServer.ModulePageData page(Module module, String title, String description,
            List<LocalApiServer.ModuleMetric> metrics, List<LocalApiServer.ModuleColumn> columns,
            List<LocalApiServer.ModuleField> fields, List<LocalApiServer.ModuleRecord> rows,
            boolean canCreate, boolean canEdit, boolean canDelete, String primaryActionLabel,
            String submitLabel, String createAction) {
        return new LocalApiServer.ModulePageData(module.name(), title, description, metrics, columns, fields, rows,
                canCreate, canEdit, canDelete, primaryActionLabel, submitLabel, createAction);
    }

    private static LocalApiServer.ModuleMetric metric(String label, Object value) {
        return new LocalApiServer.ModuleMetric(label, String.valueOf(value), "");
    }

    private static LocalApiServer.ModuleMetric metric(String label, Object value, String note) {
        return new LocalApiServer.ModuleMetric(label, String.valueOf(value), note);
    }

    private static LocalApiServer.ModuleMetric chartMetric(String label, Object value, String note,
                                                            List<LocalApiServer.ModuleMetricPoint> points) {
        return new LocalApiServer.ModuleMetric(label, String.valueOf(value), note, points);
    }

    private static LocalApiServer.ModuleColumn column(String key, String label) {
        return new LocalApiServer.ModuleColumn(key, label, false);
    }

    private static LocalApiServer.ModuleColumn column(String key, String label, boolean numeric) {
        return new LocalApiServer.ModuleColumn(key, label, numeric);
    }

    private static LocalApiServer.ModuleField field(String key, String label, String type, boolean required) {
        return new LocalApiServer.ModuleField(key, label, type, required, List.of(), null);
    }

    private static LocalApiServer.ModuleField field(String key, String label, String type, boolean required,
            List<LocalApiServer.ModuleOption> options) {
        return new LocalApiServer.ModuleField(key, label, type, required, options, null);
    }

    private static LocalApiServer.ModuleField field(String key, String label, String type, boolean required,
            List<LocalApiServer.ModuleOption> options, Object value) {
        return new LocalApiServer.ModuleField(key, label, type, required, options, value);
    }

    private static LocalApiServer.ModuleOption option(String label, String value) {
        return new LocalApiServer.ModuleOption(label, value);
    }

    private static LocalApiServer.ModuleOption option(String label, String value, String description) {
        return new LocalApiServer.ModuleOption(label, value, description);
    }

    private static LocalApiServer.ModuleRecord record(String id, Object... values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            fields.put(String.valueOf(values[index]), values[index + 1]);
        }
        return new LocalApiServer.ModuleRecord(id == null ? "" : id, fields);
    }

    private static List<LocalApiServer.ModuleOption> receiptTypeOptions() {
        return java.util.Arrays.stream(ReceiptType.values())
                .map(type -> option(type.label(), type.dbValue())).toList();
    }

    private static List<LocalApiServer.ModuleOption> appearanceOptions() {
        return java.util.Arrays.stream(ThemeManager.Appearance.values())
                .map(appearance -> option(appearance.toString(), appearance.name())).toList();
    }

    private static UUID optionalUuid(String id) {
        return id == null || id.isBlank() ? null : uuid(id, "The selected record is invalid.");
    }

    private static UUID uuid(String id, String error) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new IllegalArgumentException(error);
        }
    }

    private static String text(JsonNode values, String field) {
        JsonNode value = values == null ? null : values.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        if (value.isTextual()) {
            String text = value.asText();
            return "null".equalsIgnoreCase(text.trim()) ? "" : text;
        }
        return value.toString();
    }

    private static String first(JsonNode row, String... fields) {
        for (String field : fields) {
            String value = text(row, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static BigDecimal decimal(JsonNode values, String field) {
        String value = text(values, field).replace(",", "").trim();
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String pesoMoney(BigDecimal value) {
        java.text.DecimalFormat formatter = new java.text.DecimalFormat("₱#,##0.00");
        formatter.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return formatter.format(value == null ? BigDecimal.ZERO : value);
    }

    private static String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String tableLabel(String table) {
        return java.util.Arrays.stream(table.split("_"))
                .filter(part -> !part.isBlank())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
