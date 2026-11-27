package com.bred.rms.ui.receipts;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Supplier;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.AttachmentRules;
import com.bred.rms.ui.components.DateField;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.Rectangle;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Toolkit;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Add / edit a receipt: header, line items, charges, attachments. Files are uploaded only when the receipt is saved. */
public final class ReceiptFormDialog extends JDialog {
    /** One row of the attachments list: an already stored file, or a local file waiting to be uploaded. */
    private static final class Entry {
        final Attachment existing;
        final Path pending;
        Attachment uploaded;

        Entry(Attachment existing, Path pending) {
            this.existing = existing;
            this.pending = pending;
        }

        @Override public String toString() {
            return existing != null ? existing.fileName() : pending.getFileName() + "  (will upload on save)";
        }
    }

    private final AppContext ctx;
    private final Receipt existing;
    private final UUID receiptId;
    private final Consumer<Receipt> onSaved;

    private final JTextField receiptNo = new JTextField(16);
    private final DateField date = new DateField();
    private final JComboBox<ReceiptType> type = new JComboBox<>(ReceiptType.values());
    private final JComboBox<Supplier> supplierBox = new JComboBox<>();
    private final JComboBox<Project> projectBox = new JComboBox<>();
    private final JLabel fundSource = new JLabel("—");
    private final LineItemsTableModel itemsModel;
    private final JTable itemsTable;
    private final JTextField discount = new JTextField("0.00", 10);
    private final JTextField vat = new JTextField("0.00", 10);
    private final JTextField charges = new JTextField("0.00", 10);
    private final JLabel subtotalLabel = new JLabel();
    private final JLabel grandLabel = new JLabel();
    private final DefaultListModel<Entry> attachmentModel = new DefaultListModel<>();
    private final JList<Entry> attachmentList = new JList<>(attachmentModel);
    private final JLabel error = new JLabel(" ");
    private final JButton save = new JButton("Save receipt");
    private final JButton cancel = new JButton("Cancel");
    private final javax.swing.JPanel glass = new javax.swing.JPanel();
    private boolean busy;
    private boolean vatAuto;
    private boolean settingVat;

    public ReceiptFormDialog(Window owner, AppContext ctx, Receipt existing, Consumer<Receipt> onSaved) {
        super(owner, existing == null ? "Add Receipt" : "Edit Receipt", ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.existing = existing;
        this.receiptId = existing != null ? existing.id() : UUID.randomUUID();
        this.onSaved = onSaved;
        this.itemsModel = new LineItemsTableModel(existing == null ? List.of() : existing.items());
        this.itemsTable = new JTable(itemsModel);
        if (existing == null) itemsModel.addRow();

        buildLayout();
        fillFrom(existing);
        wireEvents();
        recalc();
        setBusy(true);
        error.setText("Loading suppliers and projects…");
        BackgroundTask.run(() -> new Lists(ctx.suppliers.findAll(), ctx.projects.findAll()), this::populate,
                e -> { setBusy(false); showError(e); });
        fitToScreen(owner, 920, 680, 820, 600);
    }

    private record Lists(List<Supplier> suppliers, List<Project> projects) { }

    private void populate(Lists l) {
        DefaultComboBoxModel<Supplier> sm = new DefaultComboBoxModel<>();
        l.suppliers().forEach(sm::addElement);
        supplierBox.setModel(sm);
        DefaultComboBoxModel<Project> pm = new DefaultComboBoxModel<>();
        pm.addElement(null);
        l.projects().forEach(pm::addElement);
        projectBox.setModel(pm);
        if (existing != null) {
            for (int i = 0; i < sm.getSize(); i++) if (sm.getElementAt(i).id().equals(existing.supplierId())) supplierBox.setSelectedIndex(i);
            for (int i = 0; i < pm.getSize(); i++) {
                Project p = pm.getElementAt(i);
                if (p != null && p.id().equals(existing.projectId())) projectBox.setSelectedIndex(i);
            }
        } else if (sm.getSize() > 0) {
            supplierBox.setSelectedIndex(0);
        }
        error.setText(sm.getSize() == 0 ? "Add a supplier first (use New supplier…)." : " ");
        setBusy(false);
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 8);
        g.anchor = GridBagConstraints.WEST;
        row(header, g, 0, "Receipt No.", receiptNo, "Date", date);
        row(header, g, 1, "Type", type, "Project", projectBox);
        g.gridx = 0; g.gridy = 2;
        header.add(new JLabel("Supplier"), g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        header.add(supplierBox, g);
        g.gridx = 3; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        JButton newSupplier = new JButton("New supplier…");
        newSupplier.addActionListener(e -> quickSupplier());
        header.add(newSupplier, g);
        g.gridx = 4;
        header.add(new JLabel("Fund source:"), g);
        g.gridx = 5;
        header.add(fundSource, g);
        supplierBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean foc) {
                String text = v instanceof Supplier s ? s.businessName() + "  ·  TIN " + s.tin() : "";
                return super.getListCellRendererComponent(list, text, i, sel, foc);
            }
        });
        projectBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean foc) {
                String text = v instanceof Project p ? p.name() + "  (" + p.code() + ")" : "— None —";
                return super.getListCellRendererComponent(list, text, i, sel, foc);
            }
        });
        root.add(header, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        ModernTable.apply(itemsTable);
        itemsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        itemsTable.getColumnModel().getColumn(0).setPreferredWidth(420);
        for (int c = 1; c <= 3; c++) {
            itemsTable.getColumnModel().getColumn(c).setPreferredWidth(110);
            javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            itemsTable.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        JScrollPane itemsScroll = new StyledScrollPane(itemsTable);
        itemsScroll.setPreferredSize(new Dimension(600, 150));
        JPanel itemButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addItem = new JButton("+ Add item");
        addItem.addActionListener(e -> itemsModel.addRow());
        JButton removeItem = new JButton("Remove selected");
        removeItem.addActionListener(e -> {
            if (itemsTable.isEditing()) itemsTable.getCellEditor().stopCellEditing();
            int r = itemsTable.getSelectedRow();
            if (r >= 0) itemsModel.removeRow(r);
        });
        itemButtons.add(addItem);
        itemButtons.add(removeItem);
        middle.add(itemButtons, BorderLayout.NORTH);
        middle.add(itemsScroll, BorderLayout.CENTER);

        JPanel lower = new JPanel(new BorderLayout(16, 0));
        JPanel attach = new JPanel(new BorderLayout(0, 6));
        attach.setBorder(BorderFactory.createTitledBorder("Attachments (png, jpg or pdf, up to 10 MB)"));
        attachmentList.setVisibleRowCount(4);
        attach.add(new StyledScrollPane(attachmentList), BorderLayout.CENTER);
        JPanel attachButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton addFile = new JButton("Add file…");
        addFile.addActionListener(e -> chooseFile());
        JButton removeFile = new JButton("Remove");
        removeFile.addActionListener(e -> {
            int i = attachmentList.getSelectedIndex();
            if (i >= 0) attachmentModel.remove(i);
        });
        attachButtons.add(addFile);
        attachButtons.add(removeFile);
        attach.add(attachButtons, BorderLayout.SOUTH);
        lower.add(attach, BorderLayout.CENTER);

        JPanel totals = new JPanel(new GridBagLayout());
        GridBagConstraints t = new GridBagConstraints();
        t.insets = new Insets(3, 4, 3, 4);
        t.anchor = GridBagConstraints.EAST;
        line(totals, t, 0, "Subtotal", subtotalLabel);
        line(totals, t, 1, "Discount", discount);
        t.gridx = 0; t.gridy = 2;
        JButton recompute = new JButton("Recompute 12%");
        recompute.addActionListener(e -> { vatAuto = true; recalc(); });
        totals.add(recompute, t);
        t.gridx = 1;
        totals.add(vat, t);
        line(totals, t, 3, "Additional charges", charges);
        grandLabel.setFont(grandLabel.getFont().deriveFont(Font.BOLD, 18f));
        grandLabel.setForeground(RmsColors.primary());
        line(totals, t, 4, "GRAND TOTAL", grandLabel);
        discount.setHorizontalAlignment(SwingConstants.RIGHT);
        vat.setHorizontalAlignment(SwingConstants.RIGHT);
        charges.setHorizontalAlignment(SwingConstants.RIGHT);
        lower.add(totals, BorderLayout.EAST);
        middle.add(lower, BorderLayout.SOUTH);
        JScrollPane middleScroll = new StyledScrollPane(middle);
        middleScroll.setBorder(BorderFactory.createEmptyBorder());
        middleScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        middleScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        middleScroll.getVerticalScrollBar().setUnitIncrement(24);
        root.add(middleScroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        error.setForeground(RmsColors.DANGER);
        bottom.add(error, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        save.setBackground(RmsColors.primaryAccent());
        save.setForeground(RmsColors.primaryForeground());
        buttons.add(cancel);
        buttons.add(save);
        bottom.add(buttons, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(save);
    }

    private void fitToScreen(Window owner, int preferredWidth, int preferredHeight,
                             int minimumWidth, int minimumHeight) {
        GraphicsConfiguration gc = owner == null ? null : owner.getGraphicsConfiguration();
        if (gc == null) gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        int width = Math.max(minimumWidth, Math.min(preferredWidth, bounds.width - 40));
        int height = Math.max(minimumHeight, Math.min(preferredHeight, bounds.height - 40));
        setSize(Math.min(width, bounds.width), Math.min(height, bounds.height));
        setLocation(bounds.x + (bounds.width - getWidth()) / 2,
                bounds.y + (bounds.height - getHeight()) / 2);
    }

    private static void row(JPanel p, GridBagConstraints g, int y, String l1, java.awt.Component c1, String l2, java.awt.Component c2) {
        g.gridy = y; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.gridx = 0; p.add(new JLabel(l1), g);
        g.gridx = 1; p.add(c1, g);
        g.gridx = 2; p.add(new JLabel(l2), g);
        g.gridx = 3; g.gridwidth = 3; p.add(c2, g);
    }

    private static void line(JPanel p, GridBagConstraints c, int y, String label, java.awt.Component value) {
        c.gridy = y;
        c.gridx = 0; p.add(new JLabel(label), c);
        c.gridx = 1; p.add(value, c);
    }

    private void fillFrom(Receipt r) {
        if (r == null) {
            date.setValue(LocalDate.now());
            vatAuto = true;
            return;
        }
        receiptNo.setText(r.receiptNo());
        date.setValue(r.date());
        type.setSelectedItem(r.type());
        discount.setText(ReceiptMath.money(r.discount()).toPlainString());
        vat.setText(ReceiptMath.money(r.vat()).toPlainString());
        charges.setText(ReceiptMath.money(r.additionalCharges()).toPlainString());
        r.attachments().forEach(a -> attachmentModel.addElement(new Entry(a, null)));
        vatAuto = false;
    }

    private void wireEvents() {
        DocumentListener moneyChanged = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { recalc(); }
            @Override public void removeUpdate(DocumentEvent e) { recalc(); }
            @Override public void changedUpdate(DocumentEvent e) { recalc(); }
        };
        discount.getDocument().addDocumentListener(moneyChanged);
        charges.getDocument().addDocumentListener(moneyChanged);
        vat.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { typed(); }
            @Override public void removeUpdate(DocumentEvent e) { typed(); }
            @Override public void changedUpdate(DocumentEvent e) { typed(); }
            private void typed() { if (!settingVat) vatAuto = false; recalc(); }
        });
        itemsModel.addTableModelListener(e -> recalc());
        projectBox.addActionListener(e -> {
            Project p = (Project) projectBox.getSelectedItem();
            fundSource.setText(p == null || p.fundSource() == null || p.fundSource().isBlank() ? "—" : p.fundSource());
        });
        cancel.addActionListener(e -> { if (!busy) dispose(); });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { if (!busy) dispose(); }
        });
        glass.setOpaque(false);
        glass.setFocusable(true);
        glass.addMouseListener(new java.awt.event.MouseAdapter() { });
        glass.addKeyListener(new java.awt.event.KeyAdapter() { });
        setGlassPane(glass);
        itemsModel.setEditMessageListener(msg -> error.setText(msg.isEmpty() ? " " : msg));
        save.addActionListener(e -> save());
    }

    /** Lenient parse for live totals: bad text counts as zero until the user saves. */
    private static BigDecimal lenient(JTextField f) {
        try {
            return FormParsing.amount("", f.getText());
        } catch (ValidationException e) {
            return ReceiptMath.money(BigDecimal.ZERO);
        }
    }

    private void recalc() {
        BigDecimal subtotal = itemsModel.subtotal();
        BigDecimal disc = lenient(discount);
        if (vatAuto) {
            settingVat = true;
            String v = ReceiptMath.defaultVat(subtotal, disc.min(subtotal)).toPlainString();
            if (!vat.getText().equals(v)) vat.setText(v);
            settingVat = false;
        }
        BigDecimal grand = subtotal.subtract(disc).add(lenient(vat)).add(lenient(charges));
        subtotalLabel.setText(ReceiptMath.format(subtotal));
        grandLabel.setText(ReceiptMath.format(grand));
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Receipt scans (png, jpg, pdf)", "png", "jpg", "jpeg", "pdf"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            AttachmentRules.check(p);
            attachmentModel.addElement(new Entry(null, p));
            error.setText(" ");
        } catch (AttachmentException e) {
            error.setText(e.getMessage());
        }
    }

    private void quickSupplier() {
        JTextField name = new JTextField(22);
        JTextField tin = new JTextField(22);
        JTextField address = new JTextField(22);
        JTextField contact = new JTextField(22);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        String[] labels = {"Business name", "TIN", "Address", "Contact number"};
        JTextField[] fields = {name, tin, address, contact};
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i; c.gridx = 0; p.add(new JLabel(labels[i]), c);
            c.gridx = 1; p.add(fields[i], c);
        }
        if (JOptionPane.showConfirmDialog(this, p, "New supplier", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        setBusy(true);
        BackgroundTask.run(() -> ctx.suppliers.save(new Supplier(null, name.getText(), tin.getText(), address.getText(), contact.getText())),
                s -> {
                    ((DefaultComboBoxModel<Supplier>) supplierBox.getModel()).addElement(s);
                    supplierBox.setSelectedItem(s);
                    error.setText(" ");
                    setBusy(false);
                }, e -> { setBusy(false); showError(e); });
    }

    private Receipt draft(List<Attachment> attachments) {
        String no = receiptNo.getText().trim();
        LocalDate d = date.value();
        if (d == null) throw new ValidationException("Enter the receipt date as YYYY-MM-DD.");
        Supplier s = (Supplier) supplierBox.getSelectedItem();
        if (s == null) throw new ValidationException("Choose a supplier.");
        Project p = (Project) projectBox.getSelectedItem();
        List<ReceiptItem> items = itemsModel.items();
        return new Receipt(receiptId, no, d, (ReceiptType) type.getSelectedItem(), s.id(), p == null ? null : p.id(), items,
                FormParsing.amount("Discount", discount.getText()), FormParsing.amount("VAT", vat.getText()),
                FormParsing.amount("Additional charges", charges.getText()), attachments,
                existing == null ? null : existing.encodedBy(), existing == null ? null : existing.createdAt(),
                existing == null ? null : existing.modifiedAt());
    }

    private void save() {
        if (busy) return;
        if (itemsTable.isEditing()) itemsTable.getCellEditor().stopCellEditing();
        error.setText(" ");
        // Everything is read from the screen here, on the UI thread; the worker only touches this immutable value.
        final Receipt draft;
        try {
            draft = draft(List.of());
        } catch (ValidationException e) {
            error.setText(e.getMessage());
            return;
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < attachmentModel.size(); i++) entries.add(attachmentModel.get(i));
        setBusy(true);
        BackgroundTask.run(() -> {
            ctx.receipts.precheck(draft); // full rules + duplicate check BEFORE any file is uploaded
            List<Attachment> atts = new ArrayList<>();
            for (Entry en : entries) {
                if (en.existing != null) {
                    atts.add(en.existing);
                } else {
                    if (en.uploaded == null) en.uploaded = ctx.attachments.upload(receiptId, en.pending);
                    atts.add(en.uploaded);
                }
            }
            return ctx.receipts.save(draft.withAttachments(atts));
        }, saved -> {
            busy = false;
            onSaved.accept(saved);
            dispose();
        }, e -> {
            setBusy(false);
            showError(e);
        });
    }

    private void showError(Throwable e) {
        boolean friendly = e instanceof ValidationException || e instanceof AttachmentException
                || e instanceof SecurityException || e instanceof com.bred.rms.repositories.RepositoryException;
        error.setText(friendly && e.getMessage() != null ? e.getMessage() : "Something went wrong. Please try again.");
    }

    private void setBusy(boolean value) {
        busy = value;
        save.setEnabled(!value);
        cancel.setEnabled(!value);
        supplierBox.setEnabled(!value);
        projectBox.setEnabled(!value);
        glass.setVisible(value);
        if (value) glass.requestFocusInWindow();
        setCursor(java.awt.Cursor.getPredefinedCursor(value ? java.awt.Cursor.WAIT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }
}
