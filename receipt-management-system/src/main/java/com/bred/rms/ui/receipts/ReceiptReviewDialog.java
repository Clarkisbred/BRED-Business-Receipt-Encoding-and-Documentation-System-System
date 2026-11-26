package com.bred.rms.ui.receipts;

import com.bred.rms.importer.ImportedDocument;
import com.bred.rms.importer.ImportedLineItemsTableModel;
import com.bred.rms.importer.ReceiptCandidate;
import com.bred.rms.importer.ReceiptCandidateValidator;
import com.bred.rms.importer.ReceiptField;
import com.bred.rms.models.Supplier;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Editable, in-memory review of one extracted receipt candidate. */
public final class ReceiptReviewDialog extends JDialog {
    public record Result(ReceiptCandidate candidate, UUID supplierId) { }

    private final ImportedDocument document;
    private final ReceiptCandidate draft;
    private final JComboBox<Supplier> supplierBox = new JComboBox<>();
    private final Map<ReceiptField, JTextField> fields = new EnumMap<>(ReceiptField.class);
    private final DefaultListModel<String> warningModel = new DefaultListModel<>();
    private final ReceiptCandidateValidator validator = new ReceiptCandidateValidator();
    private final ImportedLineItemsTableModel lineItemsModel;
    private Result result;
    private boolean initializing = true;

    private ReceiptReviewDialog(Window owner, ImportedDocument document, ReceiptCandidate candidate,
                                List<Supplier> suppliers, UUID selectedSupplierId) {
        super(owner, "Review Receipt", ModalityType.APPLICATION_MODAL);
        this.document = document;
        this.draft = candidate.copy();
        DefaultComboBoxModel<Supplier> supplierModel = new DefaultComboBoxModel<>();
        supplierModel.addElement(null);
        suppliers.forEach(supplierModel::addElement);
        supplierBox.setModel(supplierModel);
        supplierBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String label = value instanceof Supplier supplier
                        ? supplier.businessName() + "  ·  TIN " + supplier.tin()
                        : "Select an existing company supplier…";
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
        if (selectedSupplierId != null) {
            for (int i = 1; i < supplierModel.getSize(); i++) {
                if (selectedSupplierId.equals(supplierModel.getElementAt(i).id())) {
                    supplierBox.setSelectedIndex(i);
                    break;
                }
            }
        }
        this.lineItemsModel = new ImportedLineItemsTableModel(draft, this::refreshWarnings);
        buildLayout();
        populateFields();
        initializing = false;
        refreshWarnings();
        setSize(980, 740);
        setMinimumSize(new Dimension(820, 620));
        setLocationRelativeTo(owner);
    }

    public static Result showDialog(Window owner, ImportedDocument document, ReceiptCandidate candidate,
                                    List<Supplier> suppliers, UUID selectedSupplierId) {
        ReceiptReviewDialog dialog = new ReceiptReviewDialog(owner, document, candidate, suppliers, selectedSupplierId);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        JLabel pending = new JLabel("PENDING ADMINISTRATOR REVIEW — nothing has been saved.");
        pending.setForeground(RmsColors.WARNING);
        pending.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
        root.add(pending, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Receipt details", buildDetails());
        JTextArea extractedText = new JTextArea(document.extractedText());
        extractedText.setEditable(false);
        extractedText.setLineWrap(true);
        extractedText.setWrapStyleWord(true);
        extractedText.setCaretPosition(0);
        tabs.addTab("Original extracted text", new StyledScrollPane(extractedText));
        root.add(tabs, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        JButton done = new JButton("Done editing");
        done.setBackground(RmsColors.primaryAccent());
        done.setForeground(RmsColors.primaryForeground());
        done.addActionListener(event -> {
            Supplier supplier = (Supplier) supplierBox.getSelectedItem();
            if (supplier == null) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Choose an existing supplier in your company before continuing.",
                        "Supplier required", javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            result = new Result(draft, supplier.id());
            dispose();
        });
        buttons.add(cancel);
        buttons.add(done);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(done);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private JPanel buildDetails() {
        JPanel details = new JPanel(new BorderLayout(8, 8));
        JPanel sourceAndSupplier = new JPanel(new GridBagLayout());
        GridBagConstraints header = new GridBagConstraints();
        header.insets = new Insets(3, 5, 3, 8);
        header.anchor = GridBagConstraints.WEST;
        header.fill = GridBagConstraints.HORIZONTAL;
        header.gridx = 0;
        header.gridy = 0;
        header.weightx = 1;
        sourceAndSupplier.add(new JLabel("Source: " + document.filename() + " — " + draft.sourceReference()), header);
        header.gridy = 1;
        header.weightx = 0;
        sourceAndSupplier.add(new JLabel("Link to existing company supplier (required)"), header);
        header.gridx = 1;
        header.weightx = 1;
        sourceAndSupplier.add(supplierBox, header);
        details.add(sourceAndSupplier, BorderLayout.NORTH);

        JPanel editor = new JPanel(new BorderLayout(8, 8));
        JPanel fieldPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 5, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int row = 0;
        for (ReceiptField field : ReceiptField.values()) {
            JTextField input = new JTextField(22);
            fields.put(field, input);
            c.gridx = 0;
            c.gridy = row;
            fieldPanel.add(new JLabel(field.label() + confidenceSuffix(field)), c);
            c.gridx = 1;
            c.weightx = 1;
            fieldPanel.add(input, c);
            c.weightx = 0;
            row++;
            input.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { fieldChanged(field, input); }
                @Override public void removeUpdate(DocumentEvent event) { fieldChanged(field, input); }
                @Override public void changedUpdate(DocumentEvent event) { fieldChanged(field, input); }
            });
        }

        JPanel lineItems = new JPanel(new BorderLayout(6, 6));
        lineItems.setBorder(BorderFactory.createTitledBorder("Line items"));
        JTable table = new JTable(lineItemsModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JPanel itemButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton addItem = new JButton("+ Add item");
        addItem.addActionListener(event -> lineItemsModel.addItem());
        JButton removeItem = new JButton("Remove selected");
        removeItem.addActionListener(event -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            lineItemsModel.removeItem(table.getSelectedRow());
        });
        itemButtons.add(addItem);
        itemButtons.add(removeItem);
        lineItems.add(itemButtons, BorderLayout.NORTH);
        lineItems.add(new StyledScrollPane(table), BorderLayout.CENTER);

        JSplitPane editSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new StyledScrollPane(fieldPanel), lineItems);
        editSplit.setResizeWeight(0.55);
        editor.add(editSplit, BorderLayout.CENTER);

        JList<String> warnings = new JList<>(warningModel);
        warnings.setVisibleRowCount(5);
        JScrollPane warningScroll = new StyledScrollPane(warnings);
        warningScroll.setBorder(BorderFactory.createTitledBorder("Validation warnings — verify before approval"));
        warningScroll.setPreferredSize(new Dimension(300, 160));
        editor.add(warningScroll, BorderLayout.SOUTH);
        details.add(editor, BorderLayout.CENTER);
        return details;
    }

    private void populateFields() {
        for (ReceiptField field : ReceiptField.values()) {
            fields.get(field).setText(draft.value(field));
        }
    }

    private String confidenceSuffix(ReceiptField field) {
        String confidence = draft.confidence(field);
        return confidence.isBlank() ? "" : "  [" + confidence + "]";
    }

    private void fieldChanged(ReceiptField field, JTextField input) {
        if (initializing) return;
        draft.setValue(field, input.getText());
        refreshWarnings();
    }

    private void refreshWarnings() {
        if (initializing) return;
        List<String> warnings = validator.validate(draft);
        warningModel.clear();
        if (warnings.isEmpty()) {
            warningModel.addElement("No consistency warnings detected. Verify all fields against the source.");
            return;
        }
        warnings.forEach(warningModel::addElement);
    }
}
