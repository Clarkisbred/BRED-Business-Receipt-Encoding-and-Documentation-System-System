package com.bred.rms.ui;

import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Renders the Markdown constructs used in Ducky replies into a Swing styled document. */
final class DuckyMarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,3})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)([-+*]|\\d+\\.)\\s+(.+)$");

    private DuckyMarkdownRenderer() { }

    static void render(StyledDocument document, String markdown, Font baseFont, Color foreground) {
        try {
            document.remove(0, document.getLength());
            if (markdown == null || markdown.isEmpty()) return;

            String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            int index = 0;
            boolean previousBlock = false;
            while (index < lines.length) {
                String line = lines[index];
                if (line.isBlank()) {
                    if (previousBlock && document.getLength() > 0) {
                        document.insertString(document.getLength(), "\n", null);
                    }
                    previousBlock = false;
                    index++;
                    continue;
                }

                if (isFence(line)) {
                    String fence = line.stripLeading().substring(0, 3);
                    index++;
                    StringBuilder code = new StringBuilder();
                    while (index < lines.length && !lines[index].stripLeading().startsWith(fence)) {
                        if (!code.isEmpty()) code.append('\n');
                        code.append(lines[index++]);
                    }
                    if (index < lines.length) index++;
                    SimpleAttributeSet style = style(baseFont, foreground);
                    StyleConstants.setFontFamily(style, Font.MONOSPACED);
                    StyleConstants.setBackground(style, new Color(0xE8, 0xEC, 0xF2));
                    StyleConstants.setBold(style, false);
                    StyleConstants.setItalic(style, false);
                    StyleConstants.setForeground(style, foreground);
                    document.insertString(document.getLength(), code + "\n", style);
                    previousBlock = true;
                    continue;
                }

                var heading = HEADING.matcher(line);
                if (heading.matches()) {
                    int level = heading.group(1).length();
                    SimpleAttributeSet style = style(baseFont, foreground);
                    StyleConstants.setBold(style, true);
                    StyleConstants.setFontSize(style, Math.round(baseFont.getSize2D()
                            * (level == 1 ? 1.55f : level == 2 ? 1.35f : 1.18f)));
                    renderInline(document, heading.group(2), style, baseFont, foreground);
                    document.insertString(document.getLength(), "\n", null);
                    previousBlock = true;
                    index++;
                    continue;
                }

                String tableHeader = index + 1 < lines.length ? lines[index + 1] : "";
                if (isTableSeparator(tableHeader) && hasTablePipes(line)) {
                    renderTable(document, lines, index, baseFont, foreground);
                    while (index < lines.length && hasTablePipes(lines[index]) && !lines[index].isBlank()) {
                        index++;
                    }
                    previousBlock = true;
                    continue;
                }

                var list = LIST.matcher(line);
                if (list.matches()) {
                    String indent = " ".repeat(Math.min(8, list.group(1).length()));
                    String marker = list.group(2).matches("\\d+\\.") ? list.group(2) : "-";
                    SimpleAttributeSet style = style(baseFont, foreground);
                    document.insertString(document.getLength(), indent + marker + " ", style);
                    renderInline(document, list.group(3), style, baseFont, foreground);
                    document.insertString(document.getLength(), "\n", null);
                    previousBlock = true;
                    index++;
                    continue;
                }

                if (line.stripLeading().startsWith(">")) {
                    String quote = line.stripLeading().substring(1).stripLeading();
                    SimpleAttributeSet style = style(baseFont, foreground);
                    StyleConstants.setItalic(style, true);
                    document.insertString(document.getLength(), "> ", style);
                    renderInline(document, quote, style, baseFont, foreground);
                    document.insertString(document.getLength(), "\n", null);
                    previousBlock = true;
                    index++;
                    continue;
                }

                SimpleAttributeSet style = style(baseFont, foreground);
                renderInline(document, line, style, baseFont, foreground);
                document.insertString(document.getLength(), "\n", null);
                previousBlock = true;
                index++;
            }
        } catch (javax.swing.text.BadLocationException error) {
            throw new IllegalStateException("Could not render Ducky Markdown.", error);
        }
    }

    private static void renderTable(StyledDocument document, String[] lines, int start,
                                    Font baseFont, Color foreground)
            throws javax.swing.text.BadLocationException {
        List<String> rows = new ArrayList<>();
        rows.add(lines[start]);
        int index = start + 2;
        while (index < lines.length && hasTablePipes(lines[index]) && !lines[index].isBlank()) {
            rows.add(lines[index++]);
        }

        for (int row = 0; row < rows.size(); row++) {
            List<String> cells = tableCells(rows.get(row));
            SimpleAttributeSet style = style(baseFont, foreground);
            if (row == 0) StyleConstants.setBold(style, true);
            for (int cell = 0; cell < cells.size(); cell++) {
                if (cell > 0) document.insertString(document.getLength(), " | ", style);
                renderInline(document, cells.get(cell), style, baseFont, foreground);
            }
            document.insertString(document.getLength(), "\n", null);
        }
    }

    private static List<String> tableCells(String line) {
        String value = line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        return List.of(value.split("\\|", -1)).stream().map(String::trim).toList();
    }

    private static boolean hasTablePipes(String line) {
        return line.indexOf('|') >= 0;
    }

    private static boolean isTableSeparator(String line) {
        if (!hasTablePipes(line)) return false;
        for (String cell : tableCells(line)) {
            if (!cell.matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private static boolean isFence(String line) {
        String value = line.stripLeading();
        return value.startsWith("```") || value.startsWith("~~~");
    }

    private static void renderInline(StyledDocument document, String text, SimpleAttributeSet inherited,
                                     Font baseFont, Color foreground)
            throws javax.swing.text.BadLocationException {
        int index = 0;
        while (index < text.length()) {
            String marker = markerAt(text, index);
            if (marker == null) {
                int next = index + 1;
                while (next < text.length() && markerAt(text, next) == null) next++;
                document.insertString(document.getLength(), text.substring(index, next), inherited);
                index = next;
                continue;
            }

            int closing = text.indexOf(marker, index + marker.length());
            if (closing <= index + marker.length() || text.substring(index + marker.length(), closing).isBlank()
                    || text.substring(index + marker.length(), closing).indexOf('\n') >= 0) {
                document.insertString(document.getLength(), marker, inherited);
                index += marker.length();
                continue;
            }

            SimpleAttributeSet style = new SimpleAttributeSet(inherited);
            String content = text.substring(index + marker.length(), closing);
            switch (marker) {
                case "***", "___" -> {
                    StyleConstants.setBold(style, true);
                    StyleConstants.setItalic(style, true);
                    renderInline(document, content, style, baseFont, foreground);
                }
                case "**", "__" -> {
                    StyleConstants.setBold(style, true);
                    renderInline(document, content, style, baseFont, foreground);
                }
                case "*", "_" -> {
                    StyleConstants.setItalic(style, true);
                    renderInline(document, content, style, baseFont, foreground);
                }
                case "~~" -> {
                    StyleConstants.setStrikeThrough(style, true);
                    renderInline(document, content, style, baseFont, foreground);
                }
                case "`" -> {
                    StyleConstants.setFontFamily(style, Font.MONOSPACED);
                    StyleConstants.setBackground(style, new Color(0xE8, 0xEC, 0xF2));
                    StyleConstants.setBold(style, false);
                    StyleConstants.setItalic(style, false);
                    document.insertString(document.getLength(), content, style);
                }
                default -> document.insertString(document.getLength(), marker + content + marker, inherited);
            }
            index = closing + marker.length();
        }
    }

    private static String markerAt(String text, int index) {
        if (text.startsWith("***", index) || text.startsWith("___", index)) {
            return text.substring(index, index + 3);
        }
        if (text.startsWith("**", index) || text.startsWith("__", index)
                || text.startsWith("~~", index)) {
            return text.substring(index, index + 2);
        }
        char character = text.charAt(index);
        if (character == '`' || character == '*' || character == '_') return String.valueOf(character);
        return null;
    }

    private static SimpleAttributeSet style(Font font, Color foreground) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setFontFamily(style, font.getFamily());
        StyleConstants.setFontSize(style, font.getSize());
        StyleConstants.setForeground(style, foreground);
        return style;
    }
}
