package com.bred.rms.ui.receipts;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders a receipt as a monospaced, paper-style slip. The look is always dark-on-white, like paper. */
public final class PaperReceiptView extends JPanel {
    /** Maximum characters per line of the slip. */
    public static final int WIDTH = 64;
    private static final int DESC = 28;
    private static final int QTY = 6;
    private static final int PRICE = 12;
    private static final int AMOUNT = 14;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    private final String text;
    private final JTextArea area = new JTextArea();

    /** Builds the slip. Supplier, project and encoder name may be null (removed or unknown). */
    public PaperReceiptView(Receipt receipt, Supplier supplier, Project project, String encodedByName) {
        this(receipt, supplier, project, encodedByName, null);
    }

    public PaperReceiptView(Receipt receipt, Supplier supplier, Project project, String encodedByName,
                            String companyName) {
        this.text = render(receipt, supplier, project, encodedByName, companyName);
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(24, 28, 24, 28)));
        area.setText(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setBackground(Color.WHITE);
        area.setForeground(new Color(0x202124));
        area.setCaretPosition(0);
        add(area, BorderLayout.CENTER);
    }

    /** Returns the slip as plain text (used for display and tests). */
    public String plainText() { return text; }

    /** Returns the text currently shown on screen. */
    public String displayedText() { return area.getText(); }

    private static String render(Receipt r, Supplier supplier, Project project, String encodedBy,
                                 String companyName) {
        List<String> out = new ArrayList<>();
        if (supplier == null) {
            out.add(center("(supplier removed)"));
        } else {
            wrap(supplier.businessName().toUpperCase(Locale.ROOT), WIDTH).forEach(l -> out.add(center(l)));
            if (notBlank(supplier.address())) wrap(supplier.address(), WIDTH).forEach(l -> out.add(center(l)));
            if (notBlank(supplier.tin())) out.add(center("TIN: " + supplier.tin()));
            if (notBlank(supplier.contactNumber())) out.add(center(supplier.contactNumber()));
        }
        out.add("");
        out.add(center(r.type().label().toUpperCase(Locale.ROOT)));
        out.add(rule('='));
        out.add("No.:   " + r.receiptNo());
        out.add("Date:  " + DATE.format(r.date()));
        if (project != null) {
            out.add("Project: " + project.name());
            if (notBlank(project.code())) out.add("Project code: " + project.code());
            if (notBlank(project.fundSource())) out.add("Fund source: " + project.fundSource());
        }
        out.add(rule('-'));
        out.add(row("Description", "Qty", "Unit price", "Amount"));
        out.add(rule('-'));
        for (ReceiptItem item : r.items()) {
            List<String> lines = wrap(item.description() + " (" + item.unit() + ")", DESC);
            out.add(row(lines.get(0), qty(item.quantity()), num(item.unitPrice()), num(item.subtotal())));
            for (int i = 1; i < lines.size(); i++) out.add(lines.get(i));
        }
        out.add(rule('-'));
        out.add(total("Subtotal", ReceiptMath.format(r.subtotal())));
        out.add(total("Discount", ReceiptMath.format(r.discount())));
        out.add(total("VAT", ReceiptMath.format(r.vat())));
        out.add(total("Additional charges", ReceiptMath.format(r.additionalCharges())));
        out.add(rule('='));
        out.add(total("TOTAL", ReceiptMath.format(r.grandTotal())));
        if (r.ewtRate().signum() > 0) {
            out.add(total("EWT (" + r.ewtRate().stripTrailingZeros().toPlainString() + "%)", ReceiptMath.format(r.ewtAmount())));
        }
        out.add(total("NET PAYABLE", ReceiptMath.format(r.netTotal())));
        out.add("Account: " + r.paymentMethod());
        if (notBlank(r.notes())) {
            out.add(rule('-'));
            out.add("Notes:");
            wrap(r.notes(), WIDTH).forEach(out::add);
        }
        out.add(rule('='));
        if (notBlank(companyName)) out.add("Company: " + companyName);
        if (notBlank(encodedBy)) out.add("Encoded by: " + encodedBy);
        if (r.createdAt() != null) out.add("Created: " + STAMP.format(r.createdAt().atZone(ZoneId.systemDefault())));
        if (r.modifiedAt() != null) out.add("Last modified: " + STAMP.format(r.modifiedAt().atZone(ZoneId.systemDefault())));
        return String.join("\n", out);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String rule(char c) { return String.valueOf(c).repeat(WIDTH); }

    private static String center(String s) {
        int pad = Math.max(0, (WIDTH - s.length()) / 2);
        return " ".repeat(pad) + s;
    }

    private static String row(String desc, String qty, String price, String amount) {
        return String.format("%-" + DESC + "s %" + QTY + "s %" + PRICE + "s %" + AMOUNT + "s", desc, qty, price, amount);
    }

    private static String total(String label, String value) {
        return label + " ".repeat(Math.max(1, WIDTH - label.length() - value.length())) + value;
    }

    private static String qty(BigDecimal q) {
        BigDecimal s = q.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String num(BigDecimal v) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(ReceiptMath.money(v));
    }

    /** Greedy word wrap; words longer than the width are split. */
    private static List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : (s == null ? "" : s.trim()).split("\\s+")) {
            while (word.length() > width) {
                if (cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) { lines.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        lines.add(cur.toString());
        return lines;
    }
}
