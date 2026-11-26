package com.bred.rms.importer;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Extracts text and table structure without executing document macros or formulas. */
public final class DocumentTextExtractor {
    private static final int OCR_DPI = 200;
    private static final double MAX_RENDER_PIXELS = 20_000_000;
    private static final int OCR_TEXT_THRESHOLD = 20;

    private final OcrService ocrService;

    public DocumentTextExtractor(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public ExtractedDocumentText extract(Path document, DocumentFileType type, Path temporaryDirectory)
            throws DocumentImportException, InterruptedException {
        return switch (type) {
            case XLSX, XLS -> extractWorkbook(document);
            case CSV -> extractCsv(document);
            case PDF -> extractPdf(document, temporaryDirectory);
            case PNG, JPG, JPEG, BMP, TIF, TIFF -> extractImage(document, temporaryDirectory);
        };
    }

    private static ExtractedDocumentText extractWorkbook(Path document) throws DocumentImportException {
        StringBuilder text = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(java.util.Locale.US);
        try (Workbook workbook = WorkbookFactory.create(document.toFile())) {
            if (workbook.getNumberOfSheets() == 0) {
                return new ExtractedDocumentText("", List.of("The workbook contains no worksheets."));
            }
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<String> headingRow = null;
                int headingIndex = -1;
                for (Row row : sheet) {
                    List<String> values = rowValues(row, formatter);
                    if (values.stream().anyMatch(value -> !value.isBlank())) {
                        headingRow = values;
                        headingIndex = row.getRowNum();
                        break;
                    }
                }
                if (headingRow == null) {
                    warnings.add("Worksheet \"" + safe(sheet.getSheetName()) + "\" is empty.");
                    continue;
                }
                appendLine(text, "Worksheet: " + safe(sheet.getSheetName()));
                List<String> headings = headings(headingRow);
                appendLine(text, "Headings: " + String.join(" | ", headings));
                for (Row row : sheet) {
                    if (row.getRowNum() <= headingIndex) continue;
                    List<String> values = rowValues(row, formatter);
                    if (values.stream().allMatch(String::isBlank)) continue;
                    appendLine(text, formatDataRow(row.getRowNum() + 1, headings, values));
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentImportException("The Excel workbook could not be read.", e);
        }
        if (text.isEmpty()) warnings.add("The workbook contains no non-empty rows.");
        return new ExtractedDocumentText(text.toString(), warnings);
    }

    private static List<String> rowValues(Row row, DataFormatter formatter) {
        int lastCell = Math.max(0, row.getLastCellNum());
        List<String> values = new ArrayList<>(lastCell);
        for (int column = 0; column < lastCell; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cell == null ? "" : formatCell(cell, formatter));
        }
        return values;
    }

    private static String formatCell(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield formatter.formatRawCellContents(value, cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString());
            }
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> "Excel error " + cell.getErrorCellValue();
            case BLANK, _NONE, FORMULA -> "";
        };
    }

    private static List<String> headings(List<String> cells) {
        List<String> headings = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            String value = cells.get(i).trim();
            headings.add(value.isEmpty() ? "Column " + (i + 1) : safe(value));
        }
        return headings;
    }

    private static ExtractedDocumentText extractCsv(Path document) throws DocumentImportException {
        StringBuilder text = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                java.nio.file.Files.newInputStream(document), StandardCharsets.UTF_8))) {
            String[] first = reader.readNext();
            if (first == null) {
                return new ExtractedDocumentText("", List.of("The CSV document is empty."));
            }
            List<String> headerValues = new ArrayList<>(List.of(first));
            if (!headerValues.isEmpty() && headerValues.get(0).startsWith("\uFEFF")) {
                headerValues.set(0, headerValues.get(0).substring(1));
            }
            List<String> headings = headings(headerValues);
            appendLine(text, "CSV table");
            appendLine(text, "Headings: " + String.join(" | ", headings));
            long rowNumber = 1;
            String[] row;
            while ((row = reader.readNext()) != null) {
                rowNumber++;
                List<String> values = List.of(row);
                if (values.stream().allMatch(String::isBlank)) continue;
                appendLine(text, formatDataRow((int) rowNumber, headings, values));
            }
        } catch (IOException | CsvValidationException | RuntimeException e) {
            throw new DocumentImportException("The CSV document could not be read.", e);
        }
        if (text.isEmpty()) warnings.add("The CSV document contains no non-empty rows.");
        return new ExtractedDocumentText(text.toString(), warnings);
    }

    private ExtractedDocumentText extractPdf(Path document, Path temporaryDirectory)
            throws DocumentImportException, InterruptedException {
        StringBuilder text = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(document.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);
            if (pdf.getNumberOfPages() == 0) {
                return new ExtractedDocumentText("", List.of("The PDF contains no pages."));
            }
            for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                checkInterrupted();
                int pageNumber = index + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String pageText = stripper.getText(pdf).strip();
                appendLine(text, "Page " + pageNumber + " (selectable text)");
                if (pageText.length() < OCR_TEXT_THRESHOLD) {
                    appendLine(text, pageText.isEmpty() ? "[No selectable text on this page.]" : pageText);
                    try {
                        PDPage page = pdf.getPage(index);
                        if (!pageCanBeRendered(page)) {
                            warnings.add("PDF page " + pageNumber + " is too large to safely render for OCR.");
                            appendLine(text, "[Page not OCR-processed; verify this page visually.]");
                            continue;
                        }
                        BufferedImage image = renderer.renderImageWithDPI(index, OCR_DPI);
                        Path renderedPage = temporaryDirectory.resolve("pdf-page-" + pageNumber + ".png");
                        if (!ImageIO.write(image, "png", renderedPage.toFile())) {
                            warnings.add("PDF page " + pageNumber + " could not be rendered to an OCR image.");
                            appendLine(text, "[Page not OCR-processed; verify this page visually.]");
                            continue;
                        }
                        String recognized = ocrService.recognize(renderedPage, temporaryDirectory).strip();
                        if (recognized.isEmpty()) {
                            warnings.add("OCR found no text on PDF page " + pageNumber + ".");
                            appendLine(text, "[OCR found no text on this page; verify it visually.]");
                        } else {
                            appendLine(text, "Page " + pageNumber + " (OCR text)");
                            appendLine(text, recognized);
                        }
                    } catch (OcrException | IOException | RuntimeException e) {
                        warnings.add("PDF page " + pageNumber + " could not be OCR-processed: " + safeMessage(e));
                        appendLine(text, "[OCR failed on this page; verify it visually.]");
                    }
                } else {
                    appendLine(text, pageText);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentImportException("The PDF could not be read.", e);
        }
        return new ExtractedDocumentText(text.toString(), warnings);
    }

    private ExtractedDocumentText extractImage(Path document, Path temporaryDirectory)
            throws InterruptedException {
        try {
            String text = ocrService.recognize(document, temporaryDirectory).strip();
            if (text.isEmpty()) {
                return new ExtractedDocumentText("", List.of("OCR found no text in the image."));
            }
            return new ExtractedDocumentText("Image OCR text\n" + text, List.of());
        } catch (OcrException e) {
            return new ExtractedDocumentText("", List.of("Image OCR failed: " + safeMessage(e)));
        }
    }

    private static boolean pageCanBeRendered(PDPage page) {
        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return false;
        double pixels = (width * OCR_DPI / 72.0) * (height * OCR_DPI / 72.0);
        return Double.isFinite(pixels) && pixels <= MAX_RENDER_PIXELS;
    }

    private static String formatDataRow(int rowNumber, List<String> headings, List<String> values) {
        StringBuilder line = new StringBuilder("Row ").append(rowNumber).append(": ");
        int columns = Math.max(headings.size(), values.size());
        for (int column = 0; column < columns; column++) {
            if (column > 0) line.append(" | ");
            String heading = column < headings.size() ? headings.get(column) : "Column " + (column + 1);
            String value = column < values.size() ? values.get(column) : "";
            line.append(heading).append('=').append(value.isBlank() ? "(blank)" : safe(value));
        }
        return line.toString();
    }

    private static void appendLine(StringBuilder target, String line) {
        if (!target.isEmpty()) target.append('\n');
        target.append(line);
    }

    private static String safe(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replace("|", "\\|");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "processing error" : message;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Document import cancelled.");
    }
}
