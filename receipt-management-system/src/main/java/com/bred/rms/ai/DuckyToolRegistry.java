package com.bred.rms.ai;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.security.AccessPolicy;
import com.bred.rms.security.Module;
import com.bred.rms.services.ReceiptQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Explicit, allow-listed read-only actions available to Ducky Assist mode. */
public final class DuckyToolRegistry {
    public record ToolResult(String content, String localDisplay, boolean requiresDisclosure) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    private final AppContext context;
    private final Consumer<Module> navigate;
    private final Consumer<UUID> openReceipt;
    private final List<GroqChatClient.ToolDefinition> definitions;

    public DuckyToolRegistry(AppContext context, Consumer<Module> navigate, Consumer<UUID> openReceipt) {
        this.context = context;
        this.navigate = navigate;
        this.openReceipt = openReceipt;
        this.definitions = createDefinitions();
    }

    public List<GroqChatClient.ToolDefinition> definitions() {
        return definitions;
    }

    public ToolResult execute(String name, JsonNode arguments, DuckyPermission permission) {
        if (permission == null || !permission.permitsTools()) {
            throw new IllegalStateException("BRED tools are available only in Assist mode.");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object.");
        }
        requireAuthorizedUser();
        return switch (name) {
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
            default -> throw new IllegalArgumentException("This BRED tool is not available.");
        };
    }

    private ToolResult searchReceipts(JsonNode args) {
        only(args, "query", "limit");
        String query = text(args, "query", 1, 100);
        int limit = limit(args);
        List<Receipt> found = context.receipts.search(new ReceiptQuery(query, null, null, null, null))
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
        List<Receipt> found = context.receipts.search(ReceiptQuery.all()).stream()
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
        List<Receipt> found = context.receipts.search(new ReceiptQuery(null, from, to, null, null));
        BigDecimal total = found.stream().map(Receipt::grandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String result = "Found " + found.size() + " authorized receipts; total is " + total.toPlainString()
                + (from == null && to == null ? "." : " for " + dateRange(from, to) + ".");
        return new ToolResult(result, result, !found.isEmpty());
    }

    private ToolResult searchSuppliers(JsonNode args) {
        only(args, "query", "limit");
        String query = text(args, "query", 1, 100).toLowerCase(Locale.ROOT);
        int limit = limit(args);
        List<Supplier> found = context.suppliers.findAll().stream()
                .filter(supplier -> supplier.businessName() != null
                        && supplier.businessName().toLowerCase(Locale.ROOT).contains(query))
                .limit(limit).toList();
        return supplierResult("Supplier search", found);
    }

    private ToolResult getSupplierDetails(JsonNode args) {
        only(args, "supplierId");
        UUID id = uuid(args, "supplierId");
        Supplier supplier = context.suppliers.findAll().stream().filter(item -> item.id().equals(id)).findFirst()
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
        requireAuthorizedUser();
        return context.receipts.search(ReceiptQuery.all());
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
        return List.copyOf(tools);
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
