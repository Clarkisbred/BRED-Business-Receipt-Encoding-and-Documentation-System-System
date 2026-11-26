package com.bred.rms.ui.receipts;

import com.bred.rms.importer.DocumentImportException;
import com.bred.rms.importer.DocumentImportService;
import com.bred.rms.importer.ImportSessionModel;
import com.bred.rms.importer.ImportedDocument;
import com.bred.rms.importer.ReceiptCandidate;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Multi-document import session and pending-review workspace; it has no persistence actions. */
public final class ReceiptImportDialog extends JDialog {
    private record Progress(int current, int total, String filename) { }

    private final DocumentImportService importService;
    private final ImportSessionModel session = new ImportSessionModel(List.of());
    private final DefaultListModel<ImportedDocument> documentListModel = new DefaultListModel<>();
    private final DefaultListModel<ReceiptCandidate> receiptListModel = new DefaultListModel<>();
    private final JList<ImportedDocument> documents = new JList<>(documentListModel);
    private final JList<ReceiptCandidate> receipts = new JList<>(receiptListModel);
    private final JTextArea sourceText = new JTextArea();
    private final JTextArea documentNotices = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JButton importButton = new JButton("Import Documents");
    private final JButton reviewButton = new JButton("Review Receipt");
    private final JButton removeReceiptButton = new JButton("Remove");
    private final JButton removeDocumentButton = new JButton("Remove document");
    private final JButton cancelButton = new JButton("Cancel");
    private final javax.swing.JProgressBar progress = new javax.swing.JProgressBar();
    private SwingWorker<List<ImportedDocument>, Progress> worker;

    public ReceiptImportDialog(Window owner, DocumentImportService importService) {
        super(owner, "AI Receipt Importer — Pending Review", ModalityType.APPLICATION_MODAL);
        this.importService = importService;
        buildLayout();
        wireEvents();
        setSize(1080, 720);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(owner);
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        JLabel pending = new JLabel(
                "PENDING ADMINISTRATOR REVIEW — imported data remains in this session and is not saved.");
        pending.setForeground(RmsColors.WARNING);
        pending.setBorder(BorderFactory.createEmptyBorder(2, 2, 8, 2));
        root.add(pending, BorderLayout.NORTH);

        documents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        receipts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        documents.setVisibleRowCount(7);
        receipts.setVisibleRowCount(9);
        JPanel documentPanel = titled("Documents", new StyledScrollPane(documents));
        JPanel receiptPanel = titled("Extracted receipts", new StyledScrollPane(receipts));
        JSplitPane lists = new JSplitPane(JSplitPane.VERTICAL_SPLIT, documentPanel, receiptPanel);
        lists.setResizeWeight(0.42);
        lists.setPreferredSize(new Dimension(310, 500));

        sourceText.setEditable(false);
        sourceText.setLineWrap(true);
        sourceText.setWrapStyleWord(true);
        documentNotices.setEditable(false);
        documentNotices.setLineWrap(true);
        documentNotices.setWrapStyleWord(true);
        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab("Original extracted text", new StyledScrollPane(sourceText));
        tabs.addTab("Document status", new StyledScrollPane(documentNotices));
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, lists, tabs);
        content.setResizeWeight(0);
        content.setDividerLocation(315);
        root.add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 5));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        importButton.setBackground(RmsColors.primaryAccent());
        importButton.setForeground(RmsColors.primaryForeground());
        actions.add(importButton);
        actions.add(reviewButton);
        actions.add(removeReceiptButton);
        actions.add(removeDocumentButton);
        actions.add(cancelButton);
        progress.setStringPainted(true);
        progress.setString("Ready");
        footer.add(progress, BorderLayout.NORTH);
        footer.add(status, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        reviewButton.setEnabled(false);
        removeReceiptButton.setEnabled(false);
        removeDocumentButton.setEnabled(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    }

    private static JPanel titled(String title, JScrollPane content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void wireEvents() {
        importButton.addActionListener(event -> chooseDocuments());
        reviewButton.addActionListener(event -> reviewReceipt());
        removeReceiptButton.addActionListener(event -> removeSelectedReceipt());
        removeDocumentButton.addActionListener(event -> removeSelectedDocument());
        cancelButton.addActionListener(event -> cancelSession());
        documents.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedDocument();
        });
        receipts.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateActions();
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { cancelSession(); }
        });
    }

    private void chooseDocuments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select receipt documents");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Receipts (xlsx, xls, csv, pdf, png, jpg, jpeg, bmp, tif, tiff)",
                "xlsx", "xls", "csv", "pdf", "png", "jpg", "jpeg", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        FileSelection selectedFiles = selectedFiles(chooser);
        if (selectedFiles.paths().isEmpty()) return;
        startImport(selectedFiles.paths());
    }

    private void startImport(List<Path> selectedFiles) {
        setBusy(true);
        progress.setMinimum(0);
        progress.setMaximum(selectedFiles.size());
        progress.setValue(0);
        progress.setString("Starting…");
        status.setText("Validating and processing selected documents.");
        worker = new SwingWorker<>() {
            @Override
            protected List<ImportedDocument> doInBackground() throws Exception {
                return importService.importFiles(selectedFiles,
                        (current, filename) -> publish(new Progress(current, selectedFiles.size(), filename)));
            }

            @Override
            protected void process(List<Progress> updates) {
                Progress latest = updates.get(updates.size() - 1);
                progress.setValue(latest.current());
                progress.setString(latest.current() + " / " + latest.total());
                status.setText("Processing " + latest.filename());
            }

            @Override
            protected void done() {
                setBusy(false);
                worker = null;
                if (isCancelled()) {
                    status.setText("Import cancelled. Temporary files are being cleaned up.");
                    return;
                }
                try {
                    List<ImportedDocument> results = get();
                    results.forEach(session::addDocument);
                    refreshDocuments(results.isEmpty() ? null : results.get(0).id());
                    progress.setString("Complete");
                    status.setText(results.size() + " document(s) processed. Review every field before closing.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Import was interrupted.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    status.setText(cause instanceof DocumentImportException
                            ? cause.getMessage() : "The documents could not be imported.");
                } catch (CancellationException e) {
                    status.setText("Import cancelled.");
                }
            }
        };
        worker.execute();
    }

    private record FileSelection(List<Path> paths) { }

    private static FileSelection selectedFiles(JFileChooser chooser) {
        java.io.File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) {
            java.io.File file = chooser.getSelectedFile();
            return new FileSelection(file == null ? List.of() : List.of(file.toPath()));
        }
        List<Path> paths = new ArrayList<>();
        for (java.io.File file : selected) paths.add(file.toPath());
        return new FileSelection(List.copyOf(paths));
    }

    private void showSelectedDocument() {
        ImportedDocument selected = documents.getSelectedValue();
        receiptListModel.clear();
        if (selected == null) {
            sourceText.setText("");
            documentNotices.setText("");
            updateActions();
            return;
        }
        ImportedDocument live = session.document(selected.id());
        live.receipts().forEach(receiptListModel::addElement);
        sourceText.setText(live.extractedText());
        sourceText.setCaretPosition(0);
        documentNotices.setText(documentStatus(live));
        documentNotices.setCaretPosition(0);
        updateActions();
    }

    private static String documentStatus(ImportedDocument document) {
        StringBuilder text = new StringBuilder();
        if (!document.error().isBlank()) text.append("Error: ").append(document.error()).append('\n');
        if (document.warnings().isEmpty() && document.error().isBlank()) text.append("No document-level warnings.");
        else document.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
        if (document.receipts().isEmpty()) text.append("\nNo receipt candidates were extracted.");
        return text.toString();
    }

    private void reviewReceipt() {
        ImportedDocument document = documents.getSelectedValue();
        ReceiptCandidate candidate = receipts.getSelectedValue();
        if (document == null || candidate == null) return;
        ReceiptCandidate updated = ReceiptReviewDialog.showDialog(this, document, candidate);
        if (updated != null) {
            session.replaceReceipt(updated);
            showSelectedDocument();
            for (int i = 0; i < receiptListModel.size(); i++) {
                if (receiptListModel.get(i).id().equals(updated.id())) {
                    receipts.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void removeSelectedReceipt() {
        ImportedDocument document = documents.getSelectedValue();
        ReceiptCandidate candidate = receipts.getSelectedValue();
        if (document == null || candidate == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove this extracted receipt from the current review session?",
                "Remove receipt", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        session.removeReceipt(document.id(), candidate.id());
        showSelectedDocument();
    }

    private void removeSelectedDocument() {
        ImportedDocument document = documents.getSelectedValue();
        if (document == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove " + document.filename() + " and all its receipt candidates from this review session?",
                "Remove document", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        session.removeDocument(document.id());
        refreshDocuments(null);
    }

    private void refreshDocuments(java.util.UUID selectId) {
        documentListModel.clear();
        for (ImportedDocument document : session.documents()) documentListModel.addElement(document);
        if (selectId != null) {
            for (int i = 0; i < documentListModel.size(); i++) {
                if (documentListModel.get(i).id().equals(selectId)) {
                    documents.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (!documentListModel.isEmpty()) documents.setSelectedIndex(0);
        else showSelectedDocument();
    }

    private void updateActions() {
        boolean busy = worker != null;
        boolean hasDocument = documents.getSelectedValue() != null;
        boolean hasReceipt = receipts.getSelectedValue() != null;
        reviewButton.setEnabled(!busy && hasReceipt);
        removeReceiptButton.setEnabled(!busy && hasReceipt);
        removeDocumentButton.setEnabled(!busy && hasDocument);
        importButton.setEnabled(!busy);
    }

    private void setBusy(boolean busy) {
        importButton.setEnabled(!busy);
        reviewButton.setEnabled(!busy && receipts.getSelectedValue() != null);
        removeReceiptButton.setEnabled(!busy && receipts.getSelectedValue() != null);
        removeDocumentButton.setEnabled(!busy && documents.getSelectedValue() != null);
        cancelButton.setText(busy ? "Cancel import" : "Cancel");
    }

    private void cancelSession() {
        if (worker != null) worker.cancel(true);
        dispose();
    }
}
