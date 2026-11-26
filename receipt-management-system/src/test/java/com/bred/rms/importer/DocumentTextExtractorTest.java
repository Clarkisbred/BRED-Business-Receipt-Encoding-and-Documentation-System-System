package com.bred.rms.importer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsXlsxHeadingsDatesAndFormattedCurrency() throws Exception {
        Path workbookPath = temporaryDirectory.resolve("receipts.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("November");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Receipt No");
            header.createCell(1).setCellValue("Date");
            header.createCell(2).setCellValue("Price");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("R-100");
            row.createCell(1).setCellValue(46000);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("m/d/yy"));
            row.getCell(1).setCellStyle(dateStyle);
            row.createCell(2).setCellValue(1234.5);
            CellStyle currencyStyle = workbook.createCellStyle();
            currencyStyle.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));
            row.getCell(2).setCellStyle(currencyStyle);
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        assertEquals(DocumentFileType.XLSX, new DocumentFileValidator().validate(workbookPath));
        ExtractedDocumentText extracted = new DocumentTextExtractor(noOcr())
                .extract(workbookPath, DocumentFileType.XLSX, temporaryDirectory);

        assertTrue(extracted.text().contains("Worksheet: November"));
        assertTrue(extracted.text().contains("Receipt No=R-100"));
        assertTrue(extracted.text().contains("Date="));
        assertTrue(extracted.text().contains("$1,234.50"));
    }

    @Test
    void extractsLegacyXlsAndKeepsMissingCellsVisible() throws Exception {
        Path workbookPath = temporaryDirectory.resolve("receipts.xls");
        try (Workbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Quantity");
            header.createCell(2).setCellValue("Unit price");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Paper");
            row.createCell(2).setCellValue(12.5);
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        assertEquals(DocumentFileType.XLS, new DocumentFileValidator().validate(workbookPath));
        ExtractedDocumentText extracted = new DocumentTextExtractor(noOcr())
                .extract(workbookPath, DocumentFileType.XLS, temporaryDirectory);
        assertTrue(extracted.text().contains("Quantity=(blank)"));
        assertTrue(extracted.text().contains("Unit price=12.5"));
    }

    @Test
    void extractsCsvWithQuotedValuesAndRowReferences() throws Exception {
        Path csv = temporaryDirectory.resolve("receipts.csv");
        Files.writeString(csv, "Receipt No,Supplier,Item,Qty,Price\nR-1,Store,\"Paper, A4\",2,12.50\n",
                StandardCharsets.UTF_8);

        assertEquals(DocumentFileType.CSV, new DocumentFileValidator().validate(csv));
        ExtractedDocumentText extracted = new DocumentTextExtractor(noOcr())
                .extract(csv, DocumentFileType.CSV, temporaryDirectory);
        assertTrue(extracted.text().contains("Headings: Receipt No | Supplier | Item | Qty | Price"));
        assertTrue(extracted.text().contains("Row 2: Receipt No=R-1 | Supplier=Store | Item=Paper, A4"));
    }

    @Test
    void extractsSelectablePdfTextAndOcrsScannedPagesWithPageReferences() throws Exception {
        Path pdf = temporaryDirectory.resolve("receipts.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage pageOne = new PDPage();
            document.addPage(pageOne);
            try (PDPageContentStream content = new PDPageContentStream(document, pageOne)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Receipt R-1 Grand total 25.00");
                content.endText();
            }
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        AtomicInteger ocrCalls = new AtomicInteger();
        DocumentTextExtractor extractor = new DocumentTextExtractor((image, workspace) -> {
            ocrCalls.incrementAndGet();
            assertTrue(Files.isRegularFile(image));
            return "Scanned receipt R-2";
        });

        ExtractedDocumentText extracted = extractor.extract(pdf, DocumentFileType.PDF, temporaryDirectory);

        assertTrue(extracted.text().contains("Page 1 (selectable text)"));
        assertTrue(extracted.text().contains("Receipt R-1 Grand total 25.00"));
        assertTrue(extracted.text().contains("Page 2 (OCR text)"));
        assertTrue(extracted.text().contains("Scanned receipt R-2"));
        assertEquals(1, ocrCalls.get());
    }

    @Test
    void detectsEmptyCsvAndReportsOcrFailureInsteadOfDroppingImage() throws Exception {
        Path emptyCsv = temporaryDirectory.resolve("empty.csv");
        Files.createFile(emptyCsv);
        ExtractedDocumentText empty = new DocumentTextExtractor(noOcr())
                .extract(emptyCsv, DocumentFileType.CSV, temporaryDirectory);
        assertTrue(empty.text().isEmpty());
        assertTrue(empty.warnings().stream().anyMatch(warning -> warning.contains("empty")));

        Path image = temporaryDirectory.resolve("scan.png");
        Files.write(image, new byte[] {1});
        ExtractedDocumentText failedOcr = new DocumentTextExtractor(
                (input, workspace) -> { throw new OcrException("simulated failure"); })
                .extract(image, DocumentFileType.PNG, temporaryDirectory);
        assertTrue(failedOcr.text().isEmpty());
        assertTrue(failedOcr.warnings().get(0).contains("simulated failure"));
    }

    @Test
    void rejectsUnsupportedMismatchedAndOversizedFiles() throws Exception {
        Path unsupported = temporaryDirectory.resolve("receipt.exe");
        Files.write(unsupported, "not an import".getBytes(StandardCharsets.UTF_8));
        assertThrows(DocumentImportException.class, () -> new DocumentFileValidator().validate(unsupported));

        Path mismatched = temporaryDirectory.resolve("receipt.pdf");
        Files.writeString(mismatched, "ordinary text", StandardCharsets.UTF_8);
        assertThrows(DocumentImportException.class, () -> new DocumentFileValidator().validate(mismatched));

        Path tooLarge = temporaryDirectory.resolve("receipt.csv");
        Files.writeString(tooLarge, "abc", StandardCharsets.UTF_8);
        assertThrows(DocumentImportException.class, () -> new DocumentFileValidator(2).validate(tooLarge));
    }

    @Test
    void validatesSupportedImageSignaturesRatherThanTrustingExtensions() throws Exception {
        assertImage("photo.png", "png", DocumentFileType.PNG);
        assertImage("photo.jpg", "jpeg", DocumentFileType.JPG);
        assertImage("photo.jpeg", "jpeg", DocumentFileType.JPEG);
        assertImage("photo.bmp", "bmp", DocumentFileType.BMP);
        assertImage("photo.tif", "tiff", DocumentFileType.TIF);
        assertImage("photo.tiff", "tiff", DocumentFileType.TIFF);
    }

    @Test
    void reportsWhenAWorkbookHasNoWorksheetsOrRows() throws Exception {
        Path workbookPath = temporaryDirectory.resolve("empty.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Empty");
            workbook.write(Files.newOutputStream(workbookPath));
        }
        ExtractedDocumentText extracted = new DocumentTextExtractor(noOcr())
                .extract(workbookPath, DocumentFileType.XLSX, temporaryDirectory);
        assertTrue(extracted.text().isEmpty());
        assertTrue(extracted.warnings().stream().anyMatch(warning -> warning.contains("empty")));
    }

    private static OcrService noOcr() {
        return (image, workspace) -> {
            throw new OcrException("OCR not expected in this test.");
        };
    }

    private void assertImage(String filename, String format, DocumentFileType expected) throws Exception {
        Path image = temporaryDirectory.resolve(filename);
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(pixels, format, image.toFile()), "No writer available for " + format);
        assertEquals(expected, new DocumentFileValidator().validate(image));
    }
}
