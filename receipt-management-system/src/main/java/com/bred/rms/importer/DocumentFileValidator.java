package com.bred.rms.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

/** Validates the selected extension against file signatures and enforces a per-file size limit. */
public final class DocumentFileValidator {
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    private static final byte[] OLE_HEADER = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final long MAX_IMAGE_PIXELS = 30_000_000;
    private final long maximumBytes;

    public DocumentFileValidator() {
        this(DEFAULT_MAX_BYTES);
    }

    public DocumentFileValidator(long maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("The maximum document size must be positive.");
        this.maximumBytes = maximumBytes;
    }

    public DocumentFileType validate(Path file) throws DocumentImportException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) {
            throw new DocumentImportException("The selected document is missing, unreadable, or not a regular file.");
        }
        DocumentFileType extension = DocumentFileType.fromFilename(file.getFileName().toString())
                .orElseThrow(() -> new DocumentImportException(
                        "Unsupported document type. Use xlsx, xls, csv, pdf, png, jpg, jpeg, bmp, tif, or tiff."));
        try {
            long size = Files.size(file);
            if (size > maximumBytes) {
                throw new DocumentImportException("The document exceeds the 20 MiB per-file import limit.");
            }
            byte[] header;
            try (InputStream input = Files.newInputStream(file)) {
                header = input.readNBytes(16);
            }
            boolean matchingType = switch (extension) {
                case XLSX -> startsWithZip(header);
                case XLS -> startsWith(header, OLE_HEADER);
                case CSV -> isUtf8TextCsv(file, size, maximumBytes);
                case PDF -> startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII));
                case PNG -> startsWith(header, PNG_HEADER);
                case JPG, JPEG -> header.length >= 3 && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
                case BMP -> header.length >= 2 && header[0] == 'B' && header[1] == 'M';
                case TIF, TIFF -> isTiff(header);
            };
            if (matchingType && isImage(extension)) matchingType = isReadableImage(file, extension);
            if (!matchingType) {
                throw new DocumentImportException("The file contents do not match the ." + extension.extension()
                        + " extension.");
            }
            return extension;
        } catch (DocumentImportException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentImportException("The selected document could not be read.", e);
        } catch (SecurityException e) {
            throw new DocumentImportException("The selected document could not be accessed.", e);
        }
    }

    public long maximumBytes() {
        return maximumBytes;
    }

    private static boolean isUtf8TextCsv(Path file, long size, long maximumBytes) throws IOException {
        if (size == 0) return true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(size, 64 * 1024));
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) return false;
                bytes.write(buffer, 0, count);
            }
        }
        byte[] content = bytes.toByteArray();
        for (byte value : content) {
            if (value == 0) return false;
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            return decoded.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t' && codePoint != '\f');
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean startsWithZip(byte[] header) {
        return header.length >= 4 && header[0] == 'P' && header[1] == 'K'
                && header[2] == 3 && header[3] == 4;
    }

    private static boolean isTiff(byte[] header) {
        return header.length >= 4 && ((header[0] == 'I' && header[1] == 'I'
                && header[2] == 42 && header[3] == 0)
                || (header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == 42));
    }

    private static boolean isImage(DocumentFileType type) {
        return switch (type) {
            case PNG, JPG, JPEG, BMP, TIF, TIFF -> true;
            default -> false;
        };
    }

    private static boolean isReadableImage(Path file, DocumentFileType type) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                boolean matches = switch (type) {
                    case PNG -> format.equals("png");
                    case JPG, JPEG -> format.equals("jpeg") || format.equals("jpg");
                    case BMP -> format.equals("bmp");
                    case TIF, TIFF -> format.equals("tif") || format.equals("tiff");
                    default -> false;
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return matches && width > 0 && height > 0 && (long) width * height <= MAX_IMAGE_PIXELS;
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean startsWith(byte[] input, byte[] prefix) {
        return input.length >= prefix.length && Arrays.equals(prefix, Arrays.copyOf(input, prefix.length));
    }
}
