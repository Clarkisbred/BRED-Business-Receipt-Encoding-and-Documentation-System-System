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
import com.bred.rms.ui.construction.ConstructionPanel;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.ui.map.NominatimGeocoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
        List<LocalApiServer.ModuleRecord> rows = projects.stream().map(project -> record(
                project.id().toString(),
                "name", project.name(),
                "code", project.code(),
                "fundSource", project.fundSource(),
                "receiptCount", counts.getOrDefault(project.id(), 0),
                "spend", money(spend.getOrDefault(project.id(), BigDecimal.ZERO))))
                .toList();
        return page(Module.PROJECTS, "Projects", "Manage project records and review receipt activity.",
                List.of(metric("Total projects", projects.size()), metric("Projects with receipts", counts.size())),
                List.of(column("name", "Project"), column("code", "Code"),
                        column("fundSource", "Fund source"), column("receiptCount", "Receipts", true),
                        column("spend", "Receipt spend", true)),
                List.of(field("name", "Project name", "text", true),
                        field("code", "Project code", "text", true),
                        field("fundSource", "Fund source", "text", false)),
                rows, true, true, currentRole().canManageCompany(), "New project", "Save project", "save");
    }

    private LocalApiServer.ModulePageData suppliers() {
        List<Supplier> suppliers = context.suppliers.findAll();
        List<LocalApiServer.ModuleRecord> rows = suppliers.stream().map(supplier -> record(
                supplier.id().toString(),
                "businessName", supplier.businessName(),
                "tin", supplier.tin(),
                "address", supplier.address(),
                "contactNumber", supplier.contactNumber()))
                .toList();
        return page(Module.SUPPLIERS, "Suppliers", "Maintain supplier details used by receipts and project records.",
                List.of(metric("Total suppliers", suppliers.size())),
                List.of(column("businessName", "Supplier"), column("tin", "TIN"),
                        column("address", "Address"), column("contactNumber", "Contact")),
                List.of(field("businessName", "Business name", "text", true),
                        field("tin", "Tax identification number", "text", true),
                        field("address", "Address", "text", false),
                        field("contactNumber", "Contact number", "text", false)),
                rows, true, true, currentRole().canManageCompany(), "New supplier", "Save supplier", "save");
    }

    private LocalApiServer.ModulePageData receipts() {
        List<Receipt> receipts = context.receipts.search(ReceiptQuery.all());
        Map<UUID, Supplier> suppliers = new LinkedHashMap<>();
        context.suppliers.findAll().forEach(supplier -> suppliers.put(supplier.id(), supplier));
        Map<UUID, Project> projects = new LinkedHashMap<>();
        context.projects.findAll().forEach(project -> projects.put(project.id(), project));
        List<LocalApiServer.ModuleOption> supplierOptions = suppliers.values().stream()
                .map(supplier -> option(supplier.businessName(), supplier.id().toString(),
                        "TIN " + supplier.tin() + (supplier.address().isBlank() ? "" : " · " + supplier.address())))
                .toList();
        List<LocalApiServer.ModuleOption> projectOptions = projects.values().stream()
                .map(project -> option(project.name(), project.id().toString())).toList();
        List<LocalApiServer.ModuleRecord> rows = receipts.stream().map(receipt -> record(
                receipt.id().toString(),
                "receiptNo", receipt.receiptNo(),
                "date", receipt.date(),
                "type", receipt.type().label(),
                "supplier", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).businessName() : "Unavailable supplier",
                "supplierTin", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).tin() : "",
                "supplierAddress", suppliers.containsKey(receipt.supplierId())
                        ? suppliers.get(receipt.supplierId()).address() : "",
                "project", receipt.projectId() == null ? "Unassigned"
                        : projects.containsKey(receipt.projectId()) ? projects.get(receipt.projectId()).name()
                        : "Unavailable project",
                "itemDescription", receipt.items().stream().map(ReceiptItem::description)
                        .filter(description -> description != null && !description.isBlank())
                        .collect(java.util.stream.Collectors.joining(", ")),
                "itemDetails", receipt.items().stream()
                        .map(item -> item.description() + " — " + item.quantity().stripTrailingZeros().toPlainString()
                                + " " + item.unit() + " × " + money(item.unitPrice())
                                + " = " + money(item.subtotal()))
                        .collect(java.util.stream.Collectors.joining("; ")),
                "itemCount", receipt.items().size(),
                "subtotal", money(receipt.subtotal()),
                "discount", money(receipt.discount()),
                "vat", money(receipt.vat()),
                "additionalCharges", money(receipt.additionalCharges()),
                "notes", receipt.notes(),
                "paymentMethod", receipt.paymentMethod(),
                "ewtRate", receipt.ewtRate().toPlainString(),
                "ewtAmount", money(receipt.ewtAmount()),
                "netTotal", money(receipt.netTotal()),
                "attachmentNames", receipt.attachments().stream().map(attachment -> attachment.fileName())
                        .collect(java.util.stream.Collectors.joining(", ")),
                "total", money(receipt.grandTotal())))
                .toList();
        return page(Module.RECEIPTS, "Receipts", "Search encoded receipts and add a receipt record.",
                List.of(metric("Total receipts", receipts.size()),
                        metric("Recorded spend", money(receipts.stream().map(Receipt::grandTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)))),
                List.of(column("receiptNo", "Receipt no."), column("date", "Date"),
                        column("type", "Document type"), column("supplier", "Supplier"),
                        column("project", "Project"), column("total", "Total", true)),
                List.of(field("receiptNo", "Receipt number", "text", true),
                        field("date", "Receipt date", "date", true),
                        field("type", "Document type", "select", true, receiptTypeOptions()),
                        field("supplierId", "Supplier", "select", true, supplierOptions),
                        field("projectId", "Project", "select", false, projectOptions),
                        field("paymentMethod", "Payment account", "select", true, List.of(
                                option("Cash", "Cash"), option("Card", "Card"), option("E-wallet", "E-wallet"))),
                        field("notes", "Personal notes", "textarea", false),
                        field("ewtRate", "EWT percentage", "number", false),
                        field("discount", "Discount", "number", false),
                        field("vat", "VAT", "number", false),
                        field("additionalCharges", "Additional charges", "number", false)),
                rows, true, false, currentRole().canManageCompany(), "New receipt", "Save receipt", "save");
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

        List<JsonNode> sourceRows = context.constructionData.rows(tab.table());
        List<LocalApiServer.ModuleRecord> rows = sourceRows.stream()
                .map(source -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    tab.fields().forEach(field -> values.put(field.key(), jsonValue(source.get(field.key()))));
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
        List<LocalApiServer.ConstructionCategory> categories = ConstructionPanel.workspaceCategories().stream()
                .map(category -> new LocalApiServer.ConstructionCategory(category.title(),
                        category.tabs().stream().map(item ->
                                new LocalApiServer.ConstructionTabInfo(item.title(), item.table())).toList()))
                .toList();
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
                columns, fields, rows, canWrite, canWrite, canWrite,
                "Add " + tab.title().toLowerCase(java.util.Locale.ROOT),
                "Save record", "save", categories);
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

    private List<LocalApiServer.ModuleOption> constructionFieldOptions(String key) {
        if ("project_id".equals(key)) {
            return context.projects.findAll().stream()
                    .map(project -> option(project.name(), project.id().toString())).toList();
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
        context.projects.save(new Project(projectId, text(values, "name"), text(values, "code"),
                text(values, "fundSource")));
        return projects();
    }

    private LocalApiServer.ModulePageData saveSupplier(String id, JsonNode values) {
        UUID supplierId = optionalUuid(id);
        context.suppliers.save(new Supplier(supplierId, text(values, "businessName"),
                text(values, "tin"), text(values, "address"), text(values, "contactNumber")));
        return suppliers();
    }

    private LocalApiServer.ModulePageData saveReceipt(String id, JsonNode values) {
        if (id != null && !id.isBlank()) {
            throw new IllegalArgumentException("Receipt editing is not supported in this form. Open the receipt directory in BRED to preserve all line items and attachments.");
        }
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
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = new Receipt(receiptId, text(values, "receiptNo"),
                LocalDate.parse(text(values, "date")),
                ReceiptType.fromDb(text(values, "type")),
                uuid(text(values, "supplierId"), "Choose a supplier."),
                optionalUuid(text(values, "projectId")),
                items,
                decimal(values, "discount"), decimal(values, "vat"),
                decimal(values, "additionalCharges"), List.of(), null, null, null, null,
                notes, paymentMethod, ewtRate);
        List<Attachment> uploaded = uploadReceiptAttachments(receiptId, values.path("attachments"));
        try {
            context.receipts.save(receipt.withAttachments(uploaded));
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
        return receipts();
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
        String value = text(values, field);
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
