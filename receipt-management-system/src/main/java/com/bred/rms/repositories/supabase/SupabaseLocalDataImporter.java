package com.bred.rms.repositories.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.Project;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.local.LocalAttachmentStore;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.RepositoryException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;
import com.bred.rms.supabase.SupabaseHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Imports an existing local data folder into the signed-in Supabase project. */
public final class SupabaseLocalDataImporter {
    private static final int ACTIVITY_BATCH_SIZE = 200;

    public record Result(int suppliers, int projects, int receipts, int attachments, int activityEntries, int localUsers) { }
    public record ExcelResult(int suppliersCreated, int projectsCreated, int receiptsImported, int receiptsSkipped) { }

    private final RestClient rest;
    private final SupabaseReceiptRepository receipts;
    private final SupabaseAttachmentStore cloudAttachments;
    private final SupabaseSupplierRepository suppliers;
    private final SupabaseProjectRepository projects;

    public SupabaseLocalDataImporter(RestClient rest, SupabaseReceiptRepository receipts,
                                     SupabaseAttachmentStore cloudAttachments) {
        this.rest = rest;
        this.receipts = receipts;
        this.cloudAttachments = cloudAttachments;
        this.suppliers = new SupabaseSupplierRepository(rest);
        this.projects = new SupabaseProjectRepository(rest);
    }

    /** Imports a receipt workbook; each worksheet row represents one receipt line item. */
    public ExcelResult importExcel(Path workbookPath) {
        List<ExcelReceipt> rows = readExcel(workbookPath);
        Map<String, Supplier> supplierByTin = new LinkedHashMap<>();
        for (Supplier supplier : suppliers.findAll()) supplierByTin.put(normalize(supplier.tin()), supplier);
        Map<String, Project> projectByCode = new LinkedHashMap<>();
        for (Project project : projects.findAll()) projectByCode.put(normalize(project.code()), project);
        Set<String> existingReceipts = new LinkedHashSet<>();
        for (Receipt receipt : receipts.findAll()) {
            existingReceipts.add(receiptKey(receipt.supplierId(), receipt.receiptNo()));
        }

        int createdSuppliers = 0;
        int createdProjects = 0;
        int importedReceipts = 0;
        int skippedReceipts = 0;
        for (ExcelReceipt row : rows) {
            String tinKey = normalize(row.supplierTin());
            Supplier supplier = supplierByTin.get(tinKey);
            if (supplier == null) {
                supplier = suppliers.save(new Supplier(null, row.supplierName(), row.supplierTin(),
                        row.supplierAddress(), row.supplierContact()));
                supplierByTin.put(tinKey, supplier);
                createdSuppliers++;
            }

            String key = receiptKey(supplier.id(), row.receiptNo());
            if (!existingReceipts.add(key)) {
                skippedReceipts++;
                continue;
            }

            UUID projectId = null;
            if (!row.projectCode().isBlank()) {
                String codeKey = normalize(row.projectCode());
                Project project = projectByCode.get(codeKey);
                if (project == null) {
                    project = projects.save(new Project(null, row.projectName(), row.projectCode(), row.fundSource()));
                    projectByCode.put(codeKey, project);
                    createdProjects++;
                }
                projectId = project.id();
            }

            Receipt receipt = new Receipt(null, row.receiptNo(), row.date(), row.type(), supplier.id(), projectId,
                    row.items(), row.discount(), row.vat(), row.additionalCharges(), List.of(), null, null, null);
            receipts.save(receipt);
            importedReceipts++;
        }
        return new ExcelResult(createdSuppliers, createdProjects, importedReceipts, skippedReceipts);
    }

    /** Writes a workbook with the supported columns and import guidance. */
    public static void writeExcelTemplate(Path target) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Receipts");
            Row headers = sheet.createRow(0);
            String[] columns = {"receipt_no", "date", "type", "supplier_name", "supplier_tin",
                    "supplier_address", "supplier_contact", "project_name", "project_code", "project_fund_source",
                    "item_description", "quantity", "unit_price", "discount", "vat", "additional_charges"};
            var headerStyle = workbook.createCellStyle();
            var bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headers.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, Math.min(42 * 256, Math.max(14 * 256, columns[i].length() * 256)));
            }
            Sheet instructions = workbook.createSheet("Instructions");
            String[] notes = {
                    "Enter one row per line item. Repeat the receipt and supplier fields on every line of the same receipt.",
                    "Required columns: receipt_no, date, supplier_name, supplier_tin, item_description, quantity, unit_price.",
                    "Dates may be Excel date cells or yyyy-MM-dd text. Receipt types: Official Receipt, Sales Invoice, Cash Invoice, Delivery Receipt, Other.",
                    "Supplier TIN identifies an existing supplier; an unknown supplier is created from the workbook details.",
                    "Project fields are optional, but project_name and project_code must be supplied together.",
                    "Amounts are non-negative. Leave discount, vat, or additional_charges blank when they are zero.",
                    "This import includes receipt details and line items; it does not import scans or other attachments.",
                    "Receipts already in Cloud Database with the same supplier TIN and receipt number are skipped."
            };
            for (int i = 0; i < notes.length; i++) {
                Row row = instructions.createRow(i);
                row.createCell(0).setCellValue(notes[i]);
            }
            instructions.setColumnWidth(0, 115 * 256);
            try (OutputStream output = Files.newOutputStream(target)) {
                workbook.write(output);
            }
        }
    }

    private List<ExcelReceipt> readExcel(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new RepositoryException("Select an Excel workbook to import.", false);
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            throw new RepositoryException("Choose an Excel workbook (.xlsx or .xls).", false);
        }
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("Receipts");
            if (sheet == null && workbook.getNumberOfSheets() > 0) sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new RepositoryException("The workbook has no receipt rows.", false);
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new RepositoryException("The workbook is missing its header row.", false);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                String header = normalizeHeader(formatter.formatCellValue(cell, evaluator));
                if (!header.isBlank() && columns.putIfAbsent(header, cell.getColumnIndex()) != null) {
                    throw new RepositoryException("The workbook has a duplicate column: " + header + ".", false);
                }
            }
            String[] required = {"receipt_no", "date", "supplier_name", "supplier_tin",
                    "item_description", "quantity", "unit_price"};
            for (String column : required) {
                if (!columns.containsKey(normalizeHeader(column))) {
                    throw new RepositoryException("The workbook is missing the required column: " + column + ".", false);
                }
            }

            Map<String, ExcelReceipt> grouped = new LinkedHashMap<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isBlank(row, formatter, evaluator)) continue;
                int rowNumber = rowIndex + 1;
                String receiptNo = cellText(row, columns, "receipt_no", formatter, evaluator);
                String tin = cellText(row, columns, "supplier_tin", formatter, evaluator);
                String supplierName = cellText(row, columns, "supplier_name", formatter, evaluator);
                if (receiptNo.isBlank()) throw excelError(rowNumber, "receipt_no is required.");
                if (tin.isBlank()) throw excelError(rowNumber, "supplier_tin is required.");
                if (supplierName.isBlank()) throw excelError(rowNumber, "supplier_name is required.");

                LocalDate date = excelDate(row, columns.get(normalizeHeader("date")), formatter, evaluator, rowNumber);
                ReceiptType type = excelType(cellText(row, columns, "type", formatter, evaluator), rowNumber);
                String projectName = cellText(row, columns, "project_name", formatter, evaluator);
                String projectCode = cellText(row, columns, "project_code", formatter, evaluator);
                String fundSource = cellText(row, columns, "project_fund_source", formatter, evaluator);
                if (projectName.isBlank() != projectCode.isBlank()
                        || (projectName.isBlank() && !fundSource.isBlank())) {
                    throw excelError(rowNumber, "project_name and project_code must both be filled in or both be blank.");
                }
                String description = cellText(row, columns, "item_description", formatter, evaluator);
                if (description.isBlank()) throw excelError(rowNumber, "item_description is required.");
                BigDecimal quantity = excelNumber(row, columns, "quantity", formatter, evaluator, rowNumber, true)
                        .setScale(3, RoundingMode.HALF_UP);
                BigDecimal unitPrice = excelNumber(row, columns, "unit_price", formatter, evaluator, rowNumber, true)
                        .setScale(2, RoundingMode.HALF_UP);
                if (quantity.signum() <= 0) throw excelError(rowNumber, "quantity must be greater than zero.");
                if (unitPrice.signum() < 0) throw excelError(rowNumber, "unit_price cannot be negative.");
                BigDecimal discount = optionalMoney(row, columns, "discount", formatter, evaluator, rowNumber);
                BigDecimal vat = optionalMoney(row, columns, "vat", formatter, evaluator, rowNumber);
                BigDecimal additionalCharges = optionalMoney(row, columns, "additional_charges", formatter, evaluator, rowNumber);
                if (discount.signum() < 0 || vat.signum() < 0 || additionalCharges.signum() < 0) {
                    throw excelError(rowNumber, "discount, vat and additional_charges cannot be negative.");
                }

                ExcelReceipt candidate = new ExcelReceipt(receiptNo, date, type, supplierName, tin,
                        cellText(row, columns, "supplier_address", formatter, evaluator),
                        cellText(row, columns, "supplier_contact", formatter, evaluator),
                        projectName, projectCode, fundSource, discount, vat, additionalCharges,
                        new ArrayList<>(List.of(new ReceiptItem(description, quantity, unitPrice))));
                String key = normalize(tin) + "|" + normalize(receiptNo);
                ExcelReceipt existing = grouped.get(key);
                if (existing == null) {
                    grouped.put(key, candidate);
                } else {
                    if (!existing.sameHeader(candidate)) {
                        throw excelError(rowNumber, "receipt-level fields must match across rows for the same receipt.");
                    }
                    existing.items().add(candidate.items().get(0));
                }
            }
            if (grouped.isEmpty()) throw new RepositoryException("The workbook contains no receipt data rows.", false);
            for (ExcelReceipt receipt : grouped.values()) {
                BigDecimal subtotal = receipt.items().stream()
                        .map(ReceiptItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (receipt.discount().compareTo(ReceiptMath.money(subtotal)) > 0) {
                    throw new RepositoryException("Discount cannot be more than the subtotal for receipt "
                            + receipt.receiptNo() + ".", false);
                }
            }
            return List.copyOf(grouped.values());
        } catch (RepositoryException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RepositoryException("Could not read the Excel workbook: " + e.getMessage(), false);
        }
    }

    private static BigDecimal optionalMoney(Row row, Map<String, Integer> columns, String column,
                                            DataFormatter formatter, FormulaEvaluator evaluator,
                                            int rowNumber) {
        BigDecimal value = excelNumber(row, columns, column, formatter, evaluator, rowNumber, false);
        if (value.signum() < 0) throw excelError(rowNumber, column + " cannot be negative.");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal excelNumber(Row row, Map<String, Integer> columns, String column,
                                          DataFormatter formatter, FormulaEvaluator evaluator,
                                          int rowNumber, boolean required) {
        String value = cellText(row, columns, column, formatter, evaluator);
        if (value.isBlank()) {
            if (required) throw excelError(rowNumber, column + " is required.");
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("₱", "").trim());
        } catch (NumberFormatException e) {
            throw excelError(rowNumber, column + " must be a number.");
        }
    }

    private static LocalDate excelDate(Row row, Integer column, DataFormatter formatter,
                                       FormulaEvaluator evaluator, int rowNumber) {
        Cell cell = column == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) throw excelError(rowNumber, "date is required.");
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
            } catch (RuntimeException e) {
                throw excelError(rowNumber, "date is invalid.");
            }
        }
        String value = formatter.formatCellValue(cell, evaluator).trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw excelError(rowNumber, "date must be an Excel date or yyyy-MM-dd.");
            }
        }
    }

    private static ReceiptType excelType(String value, int rowNumber) {
        if (value.isBlank()) return ReceiptType.OFFICIAL_RECEIPT;
        String normalized = normalizeHeader(value);
        for (ReceiptType type : ReceiptType.values()) {
            if (normalizeHeader(type.name()).equals(normalized)
                    || normalizeHeader(type.label()).equals(normalized)) return type;
        }
        throw excelError(rowNumber, "type is not a supported receipt type.");
    }

    private static String cellText(Row row, Map<String, Integer> columns, String name,
                                   DataFormatter formatter, FormulaEvaluator evaluator) {
        Integer column = columns.get(normalizeHeader(name));
        if (column == null) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    private static boolean isBlank(Row row, DataFormatter formatter,
                                   FormulaEvaluator evaluator) {
        if (row == null) return true;
        for (Cell cell : row) if (!formatter.formatCellValue(cell, evaluator).isBlank()) return false;
        return true;
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String receiptKey(UUID supplierId, String receiptNo) {
        return supplierId + "|" + normalize(receiptNo);
    }

    private static RepositoryException excelError(int row, String message) {
        return new RepositoryException("Excel row " + row + ": " + message, false);
    }

    private record ExcelReceipt(String receiptNo, LocalDate date, ReceiptType type, String supplierName,
                                String supplierTin, String supplierAddress, String supplierContact,
                                String projectName, String projectCode, String fundSource,
                                BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                                List<ReceiptItem> items) {
        private boolean sameHeader(ExcelReceipt other) {
            return receiptNo.equals(other.receiptNo) && date.equals(other.date) && type == other.type
                    && supplierName.equals(other.supplierName) && supplierTin.equals(other.supplierTin)
                    && supplierAddress.equals(other.supplierAddress) && supplierContact.equals(other.supplierContact)
                    && projectName.equals(other.projectName) && projectCode.equals(other.projectCode)
                    && fundSource.equals(other.fundSource) && discount.compareTo(other.discount) == 0
                    && vat.compareTo(other.vat) == 0 && additionalCharges.compareTo(other.additionalCharges) == 0;
        }
    }

    public Result importDirectory(Path dataDirectory) throws AttachmentException {
        Path directory = dataDirectory.toAbsolutePath().normalize();
        Path dataFile = directory.resolve("data.json");
        JsonNode root;
        try {
            if (!Files.isRegularFile(dataFile)) {
                throw new RepositoryException("No data.json was found in the selected folder.", false);
            }
            root = SupabaseHttp.JSON.readTree(Files.readAllBytes(dataFile));
        } catch (IOException e) {
            throw new RepositoryException("Could not read the local data file: " + e.getMessage(), false);
        }
        if (root == null || !root.isObject() || root.path("version").asInt(-1) != 1) {
            throw new RepositoryException("The selected data.json is not a supported BRED data file.", false);
        }
        requireArray(root, "users");
        requireArray(root, "suppliers");
        requireArray(root, "projects");
        requireArray(root, "receipts");
        requireArray(root, "activity");

        LocalAttachmentStore localAttachments = new LocalAttachmentStore(directory);
        Map<UUID, UUID> userIds = loadCloudUserIds(root.path("users"));
        int supplierCount = importSuppliers(root.path("suppliers"));
        int projectCount = importProjects(root.path("projects"));
        int[] receiptCounts = importReceipts(root.path("receipts"), localAttachments);
        int activityCount = importActivity(root.path("activity"), userIds);
        return new Result(supplierCount, projectCount, receiptCounts[0], receiptCounts[1],
                activityCount, root.path("users").size());
    }

    private Map<UUID, UUID> loadCloudUserIds(JsonNode localUsers) {
        try {
            Map<String, UUID> byEmail = new LinkedHashMap<>();
            for (JsonNode profile : rest.selectAll("profiles", "select=id,email")) {
                byEmail.put(profile.path("email").asText().trim().toLowerCase(Locale.ROOT),
                        UUID.fromString(profile.path("id").asText()));
            }
            Map<UUID, UUID> mapped = new LinkedHashMap<>();
            for (JsonNode user : localUsers) {
                UUID cloudId = byEmail.get(text(user, "email").trim().toLowerCase(Locale.ROOT));
                if (cloudId != null) mapped.put(uuid(user, "id"), cloudId);
            }
            return mapped;
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    private int importSuppliers(JsonNode rows) {
        for (JsonNode row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", uuid(row, "id").toString());
            values.put("business_name", text(row, "businessName"));
            values.put("tin", text(row, "tin"));
            values.put("address", text(row, "address"));
            values.put("contact_number", text(row, "contactNumber"));
            upsert("suppliers", values);
        }
        return rows.size();
    }

    private int importProjects(JsonNode rows) {
        for (JsonNode row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", uuid(row, "id").toString());
            values.put("name", text(row, "name"));
            values.put("code", text(row, "code"));
            values.put("fund_source", text(row, "fundSource"));
            upsert("projects", values);
        }
        return rows.size();
    }

    private int[] importReceipts(JsonNode rows, LocalAttachmentStore localAttachments) throws AttachmentException {
        int attachmentCount = 0;
        for (JsonNode row : rows) {
            UUID receiptId = uuid(row, "id");
            List<Attachment> attachments = new ArrayList<>();
            for (JsonNode node : row.path("attachments")) {
                Attachment source = new Attachment(text(node, "fileName"), text(node, "storagePath"));
                byte[] data = localAttachments.download(source);
                attachments.add(cloudAttachments.importCopy(receiptId, source, data));
                attachmentCount++;
            }

            List<ReceiptItem> items = new ArrayList<>();
            for (JsonNode node : row.path("items")) {
                items.add(new ReceiptItem(text(node, "description"),
                        new BigDecimal(text(node, "quantity")), new BigDecimal(text(node, "unitPrice"))));
            }
            UUID projectId = nullableUuid(row.get("projectId"));
            Receipt receipt = new Receipt(receiptId, text(row, "receiptNo"),
                    LocalDate.parse(text(row, "date")), ReceiptType.fromDb(text(row, "type")),
                    uuid(row, "supplierId"), projectId, items,
                    decimal(row, "discount"), decimal(row, "vat"), decimal(row, "additionalCharges"),
                    attachments, null, instant(row, "createdAt"), instant(row, "modifiedAt"));
            receipts.save(receipt);
        }
        return new int[] {rows.size(), attachmentCount};
    }

    private int importActivity(JsonNode rows, Map<UUID, UUID> cloudUserIds) {
        if (rows.isEmpty()) return 0;

        int imported = 0;
        List<Map<String, Object>> batch = new ArrayList<>(ACTIVITY_BATCH_SIZE);
        int index = 0;
        for (JsonNode row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source_key", activityKey(index++, row));
            entry.put("at", instant(row, "at").toString());
            UUID localUserId = nullableUuid(row.get("userId"));
            UUID cloudUserId = localUserId == null ? null : cloudUserIds.get(localUserId);
            entry.put("user_id", cloudUserId == null ? null : cloudUserId.toString());
            entry.put("user_name", text(row, "userName"));
            entry.put("action", text(row, "action"));
            entry.put("detail", text(row, "detail"));
            batch.add(entry);
            if (batch.size() == ACTIVITY_BATCH_SIZE) {
                invokeActivityImport(batch);
                imported += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            invokeActivityImport(batch);
            imported += batch.size();
        }
        return imported;
    }

    private void invokeActivityImport(List<Map<String, Object>> entries) {
        try {
            rest.invokeFunction("admin-import-activity", Map.of("entries", entries));
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    private void upsert(String table, Map<String, Object> values) {
        try {
            rest.upsert(table, "id", values);
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    private static void requireArray(JsonNode root, String field) {
        if (!root.path(field).isArray()) {
            throw new RepositoryException("The local data file is missing its " + field + " records.", false);
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException e) {
            throw new RepositoryException("A local record has an invalid " + field + ".", false);
        }
    }

    private static UUID nullableUuid(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new RepositoryException("A local record has an invalid UUID.", false);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        return value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (RuntimeException e) {
            throw new RepositoryException("A local record has an invalid " + field + " timestamp.", false);
        }
    }

    private static String activityKey(int index, JsonNode row) {
        String canonical = index + "|" + text(row, "at") + "|" + text(row, "userId") + "|"
                + text(row, "userName") + "|" + text(row, "action") + "|" + text(row, "detail");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "local-import:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
