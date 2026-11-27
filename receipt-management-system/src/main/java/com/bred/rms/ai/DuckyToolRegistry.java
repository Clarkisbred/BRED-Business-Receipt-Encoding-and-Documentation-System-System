package com.bred.rms.ai;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.security.AccessPolicy;
import com.bred.rms.security.Module;
import com.bred.rms.services.BugReportService;
import com.bred.rms.services.ReceiptQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Explicit, allow-listed BRED actions available to Ducky. */
public final class DuckyToolRegistry {
    public record ToolResult(String content, String localDisplay, boolean requiresDisclosure) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final java.util.Set<String> SAFE_ACTIONS = java.util.Set.of(
            "openReceiptEntry", "createReceiptFromChat", "submitBugReport");

    private final AppContext context;
    private final Consumer<Module> navigate;
    private final Consumer<UUID> openReceipt;
    private final Runnable openReceiptEntry;
    private final BugReportService bugReports;
    private final List<GroqChatClient.ToolDefinition> definitions;

    public DuckyToolRegistry(AppContext context, Consumer<Module> navigate, Consumer<UUID> openReceipt) {
        this(context, navigate, openReceipt, null);
    }

    public DuckyToolRegistry(AppContext context, Consumer<Module> navigate, Consumer<UUID> openReceipt,
                             Runnable openReceiptEntry) {
        this(context, navigate, openReceipt, openReceiptEntry,
                context == null ? null : context.bugReports);
    }

    DuckyToolRegistry(AppContext context, Consumer<Module> navigate, Consumer<UUID> openReceipt,
                      Runnable openReceiptEntry, BugReportService bugReports) {
        this.context = context;
        this.navigate = navigate;
        this.openReceipt = openReceipt;
        this.openReceiptEntry = openReceiptEntry;
        this.bugReports = bugReports;
        this.definitions = createDefinitions();
    }

    public List<GroqChatClient.ToolDefinition> definitions() {
        return definitions;
    }

    public List<GroqChatClient.ToolDefinition> definitions(DuckyPermission permission) {
        if (permission == null || !permission.available()) return List.of();
        return definitions.stream()
                .filter(definition -> permission.permitsTools() || SAFE_ACTIONS.contains(definition.name()))
                .toList();
    }

    static GroqChatClient.ToolDefinition clearMemoryDefinition() {
        return tool("clearDuckyMemory",
                "Permanently clear this signed-in user's private Ducky memory for the current company. "
                        + "Use only when the user explicitly asks to forget or clear saved memory.",
                fields());
    }

    static boolean isSafeAction(String name) {
        return SAFE_ACTIONS.contains(name) || "clearDuckyMemory".equals(name);
    }

    static boolean isSensitiveWriteAction(String name) {
        return "createReceiptFromChat".equals(name) || "submitBugReport".equals(name);
    }

    public ToolResult execute(String name, JsonNode arguments, DuckyPermission permission) {
        return execute(name, arguments, permission, null);
    }

    public ToolResult execute(String name, JsonNode arguments, DuckyPermission permission,
                              GroqDuckyService.DisclosureConfirmation confirmation) {
        if (permission == null || !permission.available()
                || (!permission.permitsTools() && !isSafeAction(name))) {
            throw new IllegalStateException("This BRED action is not available in the current permission mode.");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object.");
        }
        if (definitions.stream().noneMatch(definition -> definition.name().equals(name))) {
            throw new IllegalArgumentException("This BRED tool is not authorized or available.");
        }
        requireAuthorizedUser();
        return switch (name) {
            case "openReceiptEntry" -> openReceiptEntry(arguments);
            case "createReceiptFromChat" -> createReceiptFromChat(arguments, confirmation);
            case "submitBugReport" -> submitBugReport(arguments, confirmation);
            case "searchReceipts" -> searchReceipts(arguments);
            case "getReceiptDetails" -> getReceiptDetails(arguments);
            case "getRecentReceipts" -> getRecentReceipts(arguments);
            case "calculateReceiptTotals" -> calculateReceiptTotals(arguments);
            case "searchSuppliers" -> searchSuppliers(arguments);
            case "getSupplierDetails" -> getSupplierDetails(arguments);
            case "navigateToModule" -> navigateToModule(arguments);
            case "openReceipt" -> openReceipt(arguments);
            case "getCurrentUser" -> currentUser(arguments);
            case "getCurrentCompany" -> currentCompany(arguments);
            case "getApplicationVersion" -> applicationVersion(arguments);
            case "getConstructionAlerts" -> constructionAlerts(arguments);
            case "getConstructionReport" -> constructionReport(arguments);
            default -> throw new IllegalArgumentException("This BRED tool is not authorized or available.");
        };
    }

    private static final java.util.Set<String> CONSTRUCTION_REPORT_NAMES = java.util.Set.of(
            "Compliance expiry", "Failed tests", "Open safety", "Bid summary",
            "Subcontract balances", "Equipment costs", "Cash advances", "Tax calendar",
            "Progress and accomplishments", "Milestones", "Billing position",
            "Collections", "Overdue billings", "Project P&L", "Payables", "Cash flow",
            "Procurement", "Inventory stock", "Equipment usage", "Project risks",
            "Project map", "Quality NCR", "Punch items", "Closeout");

    private ToolResult constructionReport(JsonNode args) {
        only(args, "reportName");
        requireAuthorizedUser();
        if (context == null || context.constructionData == null) {
            throw new IllegalStateException("Construction data is unavailable.");
        }
        String reportName = text(args, "reportName", 1, 60);
        if (!CONSTRUCTION_REPORT_NAMES.contains(reportName)) {
            throw new IllegalArgumentException("That construction report is not authorized.");
        }
        java.util.List<com.bred.rms.services.ConstructionDataService.ConstructionReportRow> rows =
                context.constructionData.constructionReport(reportName);
        if (rows.isEmpty()) {
            return new ToolResult("No authorized " + reportName + " records are currently available.",
                    "", false);
        }
        StringBuilder details = new StringBuilder("Authorized construction report: ")
                .append(reportName).append(".\n");
        for (var row : rows.stream().limit(10).toList()) {
            details.append("- ").append(row.label())
                    .append("; status: ").append(row.status());
            if (row.amount().signum() != 0) {
                details.append("; amount: ").append(row.amount().toPlainString());
            }
            details.append('\n');
        }
        String content = details.toString();
        return new ToolResult(content, content, true);
    }

    private ToolResult constructionAlerts(JsonNode args) {
        only(args);
        requireAuthorizedUser();
        if (context == null || context.constructionData == null) {
            throw new IllegalStateException("Construction data is unavailable.");
        }
        var alerts = context.constructionData.expiryAlerts();
        int failedTests = context.constructionData.failedTestCount();
        int openSafety = context.constructionData.openSafetyCount();
        String details = "Construction alerts: " + alerts.size()
                + " compliance expiry alerts, " + failedTests
                + " failed tests, " + openSafety + " open safety records.";
        return new ToolResult(details, details, true);
    }

    private ToolResult searchReceipts(JsonNode args) {
        only(args, "query", "limit");
        String query = text(args, "query", 1, 100);
        int limit = limit(args);
        List<Receipt> found = authorizedReceipts(new ReceiptQuery(query, null, null, null, null))
                .stream().limit(limit).toList();
        return receiptResult("Receipt search", found);
    }

    private ToolResult getReceiptDetails(JsonNode args) {
        only(args, "receiptId");
        UUID id = uuid(args, "receiptId");
        Receipt receipt = authorizedReceipts().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That receipt is not available to your account."));
        String details = receiptDetails(receipt);
        return new ToolResult(details, details, true);
    }

    private ToolResult getRecentReceipts(JsonNode args) {
        only(args, "limit");
        int limit = limit(args);
        List<Receipt> found = authorizedReceipts(ReceiptQuery.all()).stream()
                .sorted(Comparator.comparing(Receipt::date).reversed())
                .limit(limit).toList();
        return receiptResult("Recent receipts", found);
    }

    private ToolResult calculateReceiptTotals(JsonNode args) {
        only(args, "dateFrom", "dateTo");
        LocalDate from = optionalDate(args, "dateFrom");
        LocalDate to = optionalDate(args, "dateTo");
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom.");
        }
        List<Receipt> found = authorizedReceipts(new ReceiptQuery(null, from, to, null, null));
        BigDecimal total = found.stream().map(Receipt::grandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String result = "Found " + found.size() + " authorized receipts; total is " + total.toPlainString()
                + (from == null && to == null ? "." : " for " + dateRange(from, to) + ".");
        return new ToolResult(result, result, !found.isEmpty());
    }

    private ToolResult searchSuppliers(JsonNode args) {
        only(args, "query", "limit");
        String query = text(args, "query", 1, 100).toLowerCase(Locale.ROOT);
        int limit = limit(args);
        List<Supplier> found = authorizedSuppliers().stream()
                .filter(supplier -> supplier.businessName() != null
                        && supplier.businessName().toLowerCase(Locale.ROOT).contains(query))
                .limit(limit).toList();
        return supplierResult("Supplier search", found);
    }

    private ToolResult getSupplierDetails(JsonNode args) {
        only(args, "supplierId");
        UUID id = uuid(args, "supplierId");
        Supplier supplier = authorizedSuppliers().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That supplier is not available to your account."));
        String details = "Supplier: " + safe(supplier.businessName()) + "; address: "
                + safe(supplier.address()) + "; contact: " + safe(supplier.contactNumber());
        return new ToolResult(details, details, true);
    }

    private ToolResult navigateToModule(JsonNode args) {
        only(args, "module");
        String name = text(args, "module", 1, 40);
        Module module;
        try {
            module = Module.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("That BRED section does not exist.");
        }
        if (!AccessPolicy.modulesFor(requireAuthorizedUser().role()).contains(module)) {
            throw new IllegalArgumentException("Your account cannot open that BRED section.");
        }
        invokeOnEventThread(() -> navigate.accept(module));
        return new ToolResult("Opened the " + module.title() + " section.", "", false);
    }

    private ToolResult openReceiptEntry(JsonNode args) {
        only(args);
        requireReceiptAccess();
        if (openReceiptEntry == null) {
            throw new IllegalStateException("The Add Receipt form is unavailable in this context.");
        }
        invokeOnEventThread(openReceiptEntry);
        return new ToolResult("Opened BRED's existing Add Receipt form. The user can review and save it there.",
                "", false);
    }

    private ToolResult createReceiptFromChat(JsonNode args, GroqDuckyService.DisclosureConfirmation confirmation) {
        only(args, "receiptNo", "date", "type", "supplierName", "projectName", "items",
                "discount", "vat", "additionalCharges");
        User user = requireReceiptAccess();
        if (user.companyId() == null) {
            throw new IllegalArgumentException("A company must be selected before creating a receipt.");
        }

        String receiptNo = text(args, "receiptNo", 1, 100);
        LocalDate date = requiredDate(args, "date");
        ReceiptType type = receiptType(args, "type");
        Supplier supplier = findUniqueSupplier(text(args, "supplierName", 1, 200));
        Project project = optionalProject(args);
        List<ReceiptItem> items = receiptItems(args);
        BigDecimal discount = moneyAmount(args, "discount");
        BigDecimal vat = moneyAmount(args, "vat");
        BigDecimal additionalCharges = moneyAmount(args, "additionalCharges");
        Instant createdAt = Instant.now();
        Receipt draft = new Receipt(UUID.randomUUID(), receiptNo, date, type, supplier.id(),
                project == null ? null : project.id(), items, discount, vat, additionalCharges, List.of(),
                user.id(), createdAt, createdAt, user.companyId());
        context.receipts.precheck(draft);

        String preview = receiptPreview(draft, supplier, project);
        if (confirmation == null || !confirmation.confirm("Create this receipt in BRED?", preview)) {
            return new ToolResult("The receipt was not created because confirmation was declined.", "", false);
        }
        Receipt saved = context.receipts.save(draft);
        if (openReceipt != null) invokeOnEventThread(() -> openReceipt.accept(saved.id()));
        return new ToolResult("Receipt " + safe(saved.receiptNo()) + " was saved in BRED.", "", false);
    }

    private ToolResult submitBugReport(JsonNode args, GroqDuckyService.DisclosureConfirmation confirmation) {
        only(args, "title", "description");
        requireAuthorizedUser();
        if (bugReports == null) {
            throw new IllegalStateException("Bug reporting is unavailable in this context.");
        }
        String title = text(args, "title", 1, 200);
        String description = text(args, "description", 1, 10_000);
        if (containsCredentialValue(title + "\n" + description)) {
            throw new IllegalArgumentException("Remove credentials, passwords, and access tokens from the report.");
        }
        String details = "This text-only bug report will be sent to Creator Clark Hilario through BRED's "
                + "existing bug-report service. Do not include passwords, API keys, access tokens, or other secrets.\n\n"
                + "Title: " + title + "\n\nDescription:\n" + description;
        if (confirmation == null || !confirmation.confirm("Send this bug report to the Creator?", details)) {
            return new ToolResult("The bug report was not sent because confirmation was declined.", "", false);
        }
        bugReports.submit(title, description, null, null, null);
        return new ToolResult("Your bug report was submitted to Creator Clark Hilario.", "", false);
    }

    private ToolResult openReceipt(JsonNode args) {
        only(args, "receiptId");
        UUID id = uuid(args, "receiptId");
        if (authorizedReceipts().stream().noneMatch(receipt -> receipt.id().equals(id))) {
            throw new IllegalArgumentException("That receipt is not available to your account.");
        }
        invokeOnEventThread(() -> openReceipt.accept(id));
        return new ToolResult("Opened the authorized receipt in BRED.", "", false);
    }

    private ToolResult currentUser(JsonNode args) {
        only(args);
        User user = requireAuthorizedUser();
        String detail = "Signed-in user: " + safe(user.fullName()) + "; role: " + user.role().name() + ".";
        return new ToolResult(detail, detail, true);
    }

    private ToolResult currentCompany(JsonNode args) {
        only(args);
        User user = requireAuthorizedUser();
        String detail = user.companyId() == null ? "No company is associated with this account."
                : "Current company: " + safe(user.companyName()) + ".";
        return new ToolResult(detail, detail, user.companyId() != null);
    }

    private ToolResult applicationVersion(JsonNode args) {
        only(args);
        return new ToolResult("BRED version " + com.bred.rms.config.AppVersion.current() + ".", "", false);
    }

    private List<Receipt> authorizedReceipts() {
        return authorizedReceipts(ReceiptQuery.all());
    }

    private List<Receipt> authorizedReceipts(ReceiptQuery query) {
        User user = requireAuthorizedUser();
        List<Receipt> receipts = context.receipts.search(query);
        if (user.role() == com.bred.rms.models.Role.CREATOR) return receipts;
        return receipts.stream().filter(receipt -> Objects.equals(receipt.companyId(), user.companyId())).toList();
    }

    private List<Supplier> authorizedSuppliers() {
        User user = requireAuthorizedUser();
        List<Supplier> suppliers = context.suppliers.findAll();
        if (user.role() == com.bred.rms.models.Role.CREATOR || context.supabaseRest != null) return suppliers;
        java.util.Set<UUID> supplierIds = authorizedReceipts().stream()
                .map(Receipt::supplierId).collect(Collectors.toSet());
        return suppliers.stream().filter(supplier -> supplierIds.contains(supplier.id())).toList();
    }

    private List<Project> authorizedProjects() {
        User user = requireAuthorizedUser();
        List<Project> projects = context.projects.findAll();
        if (user.role() == com.bred.rms.models.Role.CREATOR || context.supabaseRest != null) return projects;
        java.util.Set<UUID> projectIds = authorizedReceipts().stream()
                .map(Receipt::projectId).filter(Objects::nonNull).collect(Collectors.toSet());
        return projects.stream().filter(project -> projectIds.contains(project.id())).toList();
    }

    private User requireAuthorizedUser() {
        if (context == null || context.currentUser == null) {
            throw new IllegalStateException("BRED record tools are not available in this context.");
        }
        User user = context.currentUser.get();
        if (user == null || !user.active() || user.accountStatus() != AccountStatus.APPROVED
                || (user.companyId() == null && user.role() != com.bred.rms.models.Role.CREATOR)) {
            throw new SecurityException("Your signed-in account does not have approved BRED access.");
        }
        return user;
    }

    private User requireReceiptAccess() {
        User user = requireAuthorizedUser();
        if (!AccessPolicy.modulesFor(user.role()).contains(Module.RECEIPTS)) {
            throw new IllegalArgumentException("Your account cannot create or open receipts.");
        }
        return user;
    }

    private Supplier findUniqueSupplier(String name) {
        List<Supplier> matches = authorizedSuppliers().stream()
                .filter(supplier -> supplier.businessName() != null
                        && supplier.businessName().trim().equalsIgnoreCase(name.trim()))
                .toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Choose an existing supplier in your company.");
        if (matches.size() != 1) {
            throw new IllegalArgumentException("More than one supplier matches that name. Use the Add Receipt form.");
        }
        return matches.get(0);
    }

    private Project optionalProject(JsonNode args) {
        JsonNode value = args.get("projectName");
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) return null;
        String name = value.asText().trim();
        if (name.length() > 200) throw new IllegalArgumentException("projectName must be at most 200 characters.");
        List<Project> matches = authorizedProjects().stream()
                .filter(project -> project.name() != null && project.name().trim().equalsIgnoreCase(name))
                .toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Choose an existing project in your company.");
        if (matches.size() != 1) {
            throw new IllegalArgumentException("More than one project matches that name. Use the Add Receipt form.");
        }
        return matches.get(0);
    }

    private static LocalDate requiredDate(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(name + " must be an ISO date.");
        try {
            return LocalDate.parse(value.asText());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be an ISO date.");
        }
    }

    private static ReceiptType receiptType(JsonNode args, String name) {
        String value = text(args, name, 1, 40);
        try {
            return ReceiptType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("type must be one of the supported BRED receipt types.");
        }
    }

    private static List<ReceiptItem> receiptItems(JsonNode args) {
        JsonNode values = args.get("items");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 30) {
            throw new IllegalArgumentException("items must contain between 1 and 30 receipt lines.");
        }
        List<ReceiptItem> items = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) throw new IllegalArgumentException("Each receipt item must be an object.");
            only(value, "description", "quantity", "unitPrice");
            String description = text(value, "description", 1, 200);
            BigDecimal quantity = quantityAmount(value, "quantity");
            BigDecimal unitPrice = moneyAmount(value, "unitPrice");
            if (quantity.signum() <= 0 || unitPrice.signum() < 0) {
                throw new IllegalArgumentException("Receipt item quantity must be positive and price cannot be negative.");
            }
            items.add(new ReceiptItem(description, quantity, unitPrice));
        }
        return List.copyOf(items);
    }

    private static BigDecimal moneyAmount(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) return BigDecimal.ZERO.setScale(2);
        BigDecimal amount = requiredAmount(args, name);
        if (amount.scale() < -14 || amount.scale() > 20) {
            throw new IllegalArgumentException(name + " has too many decimal places.");
        }
        BigDecimal rounded = ReceiptMath.money(amount);
        if (rounded.precision() > 14) throw new IllegalArgumentException(name + " exceeds BRED's supported amount.");
        return rounded;
    }

    private static BigDecimal quantityAmount(JsonNode args, String name) {
        BigDecimal quantity = requiredAmount(args, name);
        if (quantity.scale() < -14 || quantity.scale() > 20) {
            throw new IllegalArgumentException(name + " has too many decimal places.");
        }
        BigDecimal rounded = quantity.setScale(3, RoundingMode.HALF_UP);
        if (rounded.precision() > 14) throw new IllegalArgumentException(name + " exceeds BRED's supported quantity.");
        return rounded;
    }

    private static BigDecimal requiredAmount(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !(value.isNumber() || value.isTextual())) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
        String input = value.asText();
        if (input.length() > 20) throw new IllegalArgumentException(name + " is too large.");
        try {
            return new BigDecimal(input);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a valid number.");
        }
    }

    private static String receiptPreview(Receipt receipt, Supplier supplier, Project project) {
        String items = receipt.items().stream()
                .map(item -> "- " + item.description() + ": " + item.quantity().toPlainString()
                        + " × " + item.unitPrice().toPlainString() + " = " + item.subtotal().toPlainString())
                .collect(Collectors.joining("\n"));
        return "Review all fields before approving. BRED will save this receipt only after you confirm.\n\n"
                + "Receipt number: " + receipt.receiptNo() + "\nDate: " + receipt.date()
                + "\nType: " + receipt.type().label() + "\nSupplier: " + safe(supplier.businessName())
                + "\nProject: " + (project == null ? "None" : safe(project.name()))
                + "\nItems:\n" + items + "\nDiscount: " + receipt.discount().toPlainString()
                + "\nVAT: " + receipt.vat().toPlainString()
                + "\nAdditional charges: " + receipt.additionalCharges().toPlainString()
                + "\nTotal: " + receipt.grandTotal().toPlainString();
    }

    private static boolean containsCredentialValue(String value) {
        return java.util.regex.Pattern.compile(
                "(?i)(?:api[_ -]?key|password|passcode|secret|access[_ -]?token|refresh[_ -]?token|bearer)"
                        + "\\s*[:=]\\s*\\S+|\\bgsk_[A-Za-z0-9_-]{16,}\\b|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----")
                .matcher(value).find();
    }

    private static ToolResult receiptResult(String label, List<Receipt> receipts) {
        if (receipts.isEmpty()) return new ToolResult("No authorized receipts matched.", "", false);
        String result = label + ":\n" + receipts.stream().map(DuckyToolRegistry::receiptSummary)
                .collect(Collectors.joining("\n"));
        return new ToolResult(result, result, true);
    }

    private static ToolResult supplierResult(String label, List<Supplier> suppliers) {
        if (suppliers.isEmpty()) return new ToolResult("No authorized suppliers matched.", "", false);
        String result = label + ":\n" + suppliers.stream()
                .map(item -> safe(item.businessName()) + " (ID " + item.id() + ")")
                .collect(Collectors.joining("\n"));
        return new ToolResult(result, result, true);
    }

    private static String receiptSummary(Receipt receipt) {
        return "Receipt ID " + receipt.id() + "; number " + safe(receipt.receiptNo()) + "; date "
                + receipt.date() + "; total " + receipt.grandTotal().toPlainString() + ".";
    }

    private static String receiptDetails(Receipt receipt) {
        String items = receipt.items().stream()
                .limit(20)
                .map(item -> safe(item.description()) + " (quantity " + item.quantity()
                        + ", unit price " + item.unitPrice().toPlainString() + ")")
                .collect(Collectors.joining("; "));
        return receiptSummary(receipt) + " Items: " + items;
    }

    private static List<GroqChatClient.ToolDefinition> createDefinitions() {
        List<GroqChatClient.ToolDefinition> tools = new ArrayList<>();
        tools.add(tool("openReceiptEntry", "Open BRED's validated Add Receipt form for the user to complete and save.",
                fields()));
        tools.add(createReceiptDefinition());
        tools.add(tool("submitBugReport",
                "Prepare a text-only bug report for Creator Clark Hilario. Always show the exact report and ask "
                        + "the user to confirm before submitting it. Never include credentials or secrets.",
                fields("title", "string", true, "Short report title, up to 200 characters",
                        "description", "string", true, "Problem details, up to 10000 characters")));
        tools.add(tool("searchReceipts", "Search authorized receipts by text; returns at most 10 brief summaries.",
                fields("query", "string", true, "Search phrase", "limit", "integer", false, "Maximum results")));
        tools.add(tool("getReceiptDetails", "Read one receipt available to the signed-in user.",
                fields("receiptId", "string", true, "Receipt UUID")));
        tools.add(tool("getRecentReceipts", "List a bounded number of recent authorized receipts.",
                fields("limit", "integer", false, "Maximum results")));
        tools.add(tool("calculateReceiptTotals", "Calculate totals for authorized receipts within an optional date range.",
                fields("dateFrom", "string", false, "Inclusive ISO date", "dateTo", "string", false, "Inclusive ISO date")));
        tools.add(tool("searchSuppliers", "Search authorized suppliers by business name.",
                fields("query", "string", true, "Business name search phrase", "limit", "integer", false, "Maximum results")));
        tools.add(tool("getSupplierDetails", "Read limited details for one authorized supplier.",
                fields("supplierId", "string", true, "Supplier UUID")));
        tools.add(tool("navigateToModule", "Open a BRED section visible to the signed-in user.",
                fields("module", "string", true, "Visible BRED module name")));
        tools.add(tool("openReceipt", "Open a receipt only if it is visible to the signed-in user.",
                fields("receiptId", "string", true, "Receipt UUID")));
        tools.add(tool("getCurrentUser", "Return the current signed-in user's name and role.",
                fields()));
        tools.add(tool("getCurrentCompany", "Return the company associated with the current account.",
                fields()));
        tools.add(tool("getApplicationVersion", "Return the BRED application version.", fields()));
        tools.add(tool("getConstructionAlerts",
                "Read authorized construction compliance, test and safety alert counts. Read-only.",
                fields()));
        tools.add(tool("getConstructionReport",
                "Read one authorized construction report. Read-only; never changes records or approvals.",
                fields("reportName", "string", true,
                        "One of: Compliance expiry, Failed tests, Open safety, Bid summary, "
                                + "Subcontract balances, Equipment costs, Cash advances, Tax calendar, "
                                + "Progress and accomplishments, Milestones, Billing position, Collections, "
                                + "Overdue billings, Project P&L, Payables, Cash flow, Procurement, "
                                + "Inventory stock, Equipment usage, Project risks, Project map, "
                                + "Quality NCR, Punch items, Closeout")));
        return List.copyOf(tools);
    }

    private static GroqChatClient.ToolDefinition createReceiptDefinition() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("receiptNo").put("type", "string").put("description", "Receipt number");
        properties.putObject("date").put("type", "string").put("description", "Receipt date in YYYY-MM-DD format");
        ObjectNode type = properties.putObject("type").put("type", "string");
        type.putArray("enum").add("OFFICIAL_RECEIPT").add("SALES_INVOICE").add("CASH_INVOICE")
                .add("DELIVERY_RECEIPT").add("OTHER");
        properties.putObject("supplierName").put("type", "string")
                .put("description", "Exact existing supplier business name in the current company");
        properties.putObject("projectName").put("type", "string")
                .put("description", "Exact existing project name, or omit if there is no project");
        ObjectNode items = properties.putObject("items").put("type", "array");
        items.put("minItems", 1).put("maxItems", 30);
        ObjectNode item = items.putObject("items").put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("description").put("type", "string");
        itemProperties.putObject("quantity").put("type", "number");
        itemProperties.putObject("unitPrice").put("type", "number");
        item.putArray("required").add("description").add("quantity").add("unitPrice");
        item.put("additionalProperties", false);
        properties.putObject("discount").put("type", "number");
        properties.putObject("vat").put("type", "number");
        properties.putObject("additionalCharges").put("type", "number");
        schema.putArray("required").add("receiptNo").add("date").add("type").add("supplierName").add("items");
        schema.put("additionalProperties", false);
        return tool("createReceiptFromChat",
                "Create a receipt using BRED's validated ReceiptService only after collecting all required fields, "
                        + "showing a complete preview, and obtaining explicit user confirmation. Never guess values.",
                schema);
    }

    private static GroqChatClient.ToolDefinition tool(String name, String description, ObjectNode parameters) {
        return new GroqChatClient.ToolDefinition(name, description, parameters);
    }

    private static ObjectNode fields(Object... definitions) {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();
        for (int i = 0; i < definitions.length; i += 4) {
            String name = (String) definitions[i];
            String type = (String) definitions[i + 1];
            boolean isRequired = (boolean) definitions[i + 2];
            String description = (String) definitions[i + 3];
            properties.putObject(name).put("type", type).put("description", description);
            if (isRequired) required.add(name);
        }
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            var requiredArray = schema.putArray("required");
            required.forEach(requiredArray::add);
        }
        return schema;
    }

    private static void only(JsonNode args, String... allowed) {
        for (var names = args.fieldNames(); names.hasNext();) {
            String field = names.next();
            if (java.util.Arrays.stream(allowed).noneMatch(field::equals)) {
                throw new IllegalArgumentException("Unexpected tool parameter: " + field + ".");
            }
        }
    }

    private static String text(JsonNode args, String name, int min, int max) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " must be text.");
        }
        String text = value.asText().trim();
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " characters.");
        }
        return text;
    }

    private static int limit(JsonNode args) {
        JsonNode value = args.get("limit");
        if (value == null) return DEFAULT_LIMIT;
        if (!value.canConvertToInt() || !value.isIntegralNumber()
                || value.asInt() < 1 || value.asInt() > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be an integer from 1 to " + MAX_LIMIT + ".");
        }
        return value.asInt();
    }

    private static UUID uuid(JsonNode args, String name) {
        String value = text(args, name, 1, 36);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be a valid UUID.");
        }
    }

    private static LocalDate optionalDate(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be an ISO date.");
        try {
            return LocalDate.parse(value.asText());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be an ISO date.");
        }
    }

    private static String dateRange(LocalDate from, LocalDate to) {
        return (from == null ? "all dates" : "from " + from) + (to == null ? "" : " to " + to);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "not provided" : value;
    }

    private static void invokeOnEventThread(Runnable action) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            javax.swing.SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The requested BRED action was interrupted.", error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("The requested BRED action could not be completed.", cause);
        }
    }
}
