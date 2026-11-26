package com.bred.rms.ui.receipts;

import com.bred.rms.importer.DocumentImportException;
import com.bred.rms.importer.DocumentImportService;
import com.bred.rms.importer.ImportSessionModel;
import com.bred.rms.importer.ImportedDocument;
import com.bred.rms.importer.ReceiptCandidate;
import com.bred.rms.models.Supplier;
import com.bred.rms.services.ReceiptImportApprovalService;
import com.bred.rms.services.ReceiptImportApprovalService.Draft;
import com.bred.rms.services.ReceiptImportApprovalService.Prepared;
import com.bred.rms.services.ReceiptImportApprovalService.ResultType;
import com.bred.rms.services.ReceiptImportApprovalService.SaveResult;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Multi-document import session, administrator approval and per-receipt save results. */
public final class ReceiptImportDialog extends JDialog {
    private record Progress(int current, int total, String filename) { }

    private final DocumentImportService importService;
    private final ReceiptImportApprovalService approvalService;
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
    private final JButton approveButton = new JButton("Approve and Save");
    private final JButton cancelButton = new JButton("Cancel");
    private final javax.swing.JProgressBar progress = new javax.swing.JProgressBar();
    private final Map<UUID, UUID> selectedSupplierByReceipt = new HashMap<>();
    private final Map<UUID, SaveResult> approvalResults = new HashMap<>();
    private List<Supplier> companySuppliers = List.of();
    private SwingWorker<List<ImportedDocument>, Progress> worker;
    private SwingWorker<List<Supplier>, Void> supplierWorker;
    private SwingWorker<List<SaveResult>, SaveResult> approvalWorker;
    private boolean supplierLoading;
    private boolean approvalBusy;
    private boolean closeAfterImport;
    private boolean closing;

    public ReceiptImportDialog(Window owner, DocumentImportService importService,
                               ReceiptImportApprovalService approvalService) {
        super(owner, "AI Receipt Importer — Pending Review", ModalityType.APPLICATION_MODAL);
        this.importService = importService;
        this.approvalService = approvalService;
        buildLayout();
        wireEvents();
        setSize(1080, 720);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(owner);
        loadCompanySuppliers();
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        JLabel pending = new JLabel(
                "Review each receipt before approval. Nothing is saved until you choose Approve and Save; failed drafts remain available.");
        pending.setForeground(RmsColors.WARNING);
        pending.setBorder(BorderFactory.createEmptyBorder(2, 2, 8, 2));
        root.add(pending, BorderLayout.NORTH);

        documents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        receipts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        documents.setVisibleRowCount(7);
        receipts.setVisibleRowCount(9);
        receipts.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ReceiptCandidate candidate) {
                    SaveResult result = approvalResults.get(candidate.id());
                    if (result != null) {
                        setText(candidate + " — " + statusLabel(result));
                        setToolTipText(result.message());
                        if (!selected && result.type() == ResultType.FAILED
                                || !selected && result.type() == ResultType.ATTACHMENT_FAILED) {
                            setForeground(RmsColors.DANGER);
                        }
                    } else {
                        setToolTipText(null);
                    }
                }
                return this;
            }
        });
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
        approveButton.setBackground(RmsColors.primaryAccent());
        approveButton.setForeground(RmsColors.primaryForeground());
        actions.add(approveButton);
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
        approveButton.setEnabled(false);
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
        approveButton.addActionListener(event -> approveReceipts());
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
                worker = null;
                updateActions();
                if (closeAfterImport) {
                    cleanupAndClose();
                    return;
                }
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
        updateActions();
        worker.execute();
    }

    private void loadCompanySuppliers() {
        supplierLoading = true;
        updateActions();
        supplierWorker = new SwingWorker<>() {
            @Override protected List<Supplier> doInBackground() {
                return approvalService.loadCompanySuppliers();
            }

            @Override protected void done() {
                supplierLoading = false;
                supplierWorker = null;
                try {
                    companySuppliers = get();
                    status.setText(companySuppliers.isEmpty()
                            ? "No company suppliers are available. Add a supplier before approval."
                            : "Select an existing company supplier for each receipt before approval.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Supplier authorization check was interrupted.");
                } catch (ExecutionException e) {
                    status.setText("Approval unavailable: " + safeMessage(e.getCause()));
                }
                updateActions();
            }
        };
        supplierWorker.execute();
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
        ReceiptReviewDialog.Result result = ReceiptReviewDialog.showDialog(this, document, candidate,
                companySuppliers, selectedSupplierByReceipt.get(candidate.id()));
        if (result != null) {
            session.replaceReceipt(result.candidate());
            selectedSupplierByReceipt.put(candidate.id(), result.supplierId());
            showSelectedDocument();
            for (int i = 0; i < receiptListModel.size(); i++) {
                if (receiptListModel.get(i).id().equals(result.candidate().id())) {
                    receipts.setSelectedIndex(i);
                    break;
                }
            }
            receipts.repaint();
        }
    }

    private void removeSelectedReceipt() {
        ImportedDocument document = documents.getSelectedValue();
        ReceiptCandidate candidate = receipts.getSelectedValue();
        if (document == null || candidate == null) return;
        if (isPersisted(candidate.id())) {
            status.setText("This receipt has already been saved and cannot be removed from the review session.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove this extracted receipt from the current review session?",
                "Remove receipt", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        session.removeReceipt(document.id(), candidate.id());
        selectedSupplierByReceipt.remove(candidate.id());
        approvalResults.remove(candidate.id());
        showSelectedDocument();
    }

    private void removeSelectedDocument() {
        ImportedDocument document = documents.getSelectedValue();
        if (document == null) return;
        if (document.receipts().stream().anyMatch(candidate -> isPersisted(candidate.id()))) {
            status.setText("This document contains a saved receipt and cannot be removed from the review session.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove " + document.filename() + " and all its receipt candidates from this review session?",
                "Remove document", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        session.removeDocument(document.id());
        document.receipts().forEach(candidate -> {
            selectedSupplierByReceipt.remove(candidate.id());
            approvalResults.remove(candidate.id());
        });
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
        boolean busy = worker != null || supplierLoading || approvalBusy || closing;
        boolean hasDocument = documents.getSelectedValue() != null;
        ReceiptCandidate selectedReceipt = receipts.getSelectedValue();
        boolean hasReceipt = selectedReceipt != null;
        reviewButton.setEnabled(!busy && hasReceipt && !isPersisted(selectedReceipt.id()));
        removeReceiptButton.setEnabled(!busy && hasReceipt && !isPersisted(selectedReceipt.id()));
        boolean documentHasSavedReceipt = hasDocument && documents.getSelectedValue().receipts().stream()
                .anyMatch(candidate -> isPersisted(candidate.id()));
        removeDocumentButton.setEnabled(!busy && hasDocument && !documentHasSavedReceipt);
        importButton.setEnabled(!busy);
        approveButton.setEnabled(!busy && suppliersWorkerReady() && hasPendingCandidates());
        cancelButton.setEnabled(!approvalBusy && !closing);
    }

    private boolean suppliersWorkerReady() {
        return supplierWorker == null && !supplierLoading && !companySuppliers.isEmpty();
    }

    private boolean hasPendingCandidates() {
        return session.documents().stream().flatMap(document -> document.receipts().stream())
                .anyMatch(candidate -> {
                    SaveResult result = approvalResults.get(candidate.id());
                    return result == null || result.type() == ResultType.FAILED
                            || result.type() == ResultType.ATTACHMENT_FAILED;
                });
    }

    private boolean isPersisted(UUID candidateId) {
        SaveResult result = approvalResults.get(candidateId);
        return result != null && result.type() != ResultType.FAILED;
    }

    private void approveReceipts() {
        if (approvalBusy || worker != null || !suppliersWorkerReady()) return;
        List<Draft> drafts = new ArrayList<>();
        for (ImportedDocument document : session.documents()) {
            for (ReceiptCandidate candidate : document.receipts()) {
                SaveResult previous = approvalResults.get(candidate.id());
                if (previous != null && (previous.type() == ResultType.SAVED
                        || previous.type() == ResultType.SAVED_WITHOUT_ATTACHMENT)) continue;
                UUID supplierId = selectedSupplierByReceipt.get(candidate.id());
                if (supplierId == null) {
                    status.setText("Review every receipt and link it to an existing company supplier first.");
                    return;
                }
                drafts.add(new Draft(candidate.id(), candidate, supplierId, document.attachmentSource()));
            }
        }
        if (drafts.isEmpty()) return;

        approvalBusy = true;
        status.setText("Revalidating administrator access and checking company-scoped duplicates…");
        updateActions();
        SwingWorker<List<Prepared>, Void> previewWorker = new SwingWorker<>() {
            @Override protected List<Prepared> doInBackground() {
                return approvalService.preview(drafts);
            }

            @Override protected void done() {
                try {
                    List<Prepared> prepared = get();
                    if (prepared.isEmpty()) {
                        approvalBusy = false;
                        status.setText("There are no receipts awaiting approval.");
                        updateActions();
                        return;
                    }
                    if (confirmApproval(prepared)) {
                        savePrepared(prepared);
                    } else {
                        approvalBusy = false;
                        status.setText("Approval cancelled. No receipts were saved.");
                        updateActions();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    approvalBusy = false;
                    status.setText("Approval preview was interrupted. No receipts were saved.");
                    updateActions();
                } catch (ExecutionException e) {
                    approvalBusy = false;
                    status.setText("Approval could not proceed: " + safeMessage(e.getCause()));
                    updateActions();
                }
            }
        };
        previewWorker.execute();
    }

    private boolean confirmApproval(List<Prepared> prepared) {
        StringBuilder summary = new StringBuilder("Review this approval summary. Each receipt will be saved separately.\n\n");
        for (Prepared item : prepared) {
            summary.append("• ").append(item.receipt().receiptNo()).append(" — ")
                    .append(item.supplierName()).append(" — ").append(item.receipt().date())
                    .append(" — total ").append(item.receipt().grandTotal().toPlainString())
                    .append("\n  Source: ").append(session.documents().stream()
                            .filter(document -> document.receipts().stream()
                                    .anyMatch(candidate -> candidate.id().equals(item.candidateId())))
                            .map(ImportedDocument::filename).findFirst().orElse("unknown"))
                    .append('\n');
            item.possibleDuplicates().forEach(duplicate -> summary.append("  DUPLICATE WARNING: ")
                    .append(duplicate).append('\n'));
            item.warnings().forEach(warning -> summary.append("  Review: ").append(warning).append('\n'));
            summary.append('\n');
        }
        if (prepared.stream().anyMatch(item -> !item.possibleDuplicates().isEmpty())) {
            summary.append("Possible duplicates are company-scoped. The server may still reject an exact duplicate.\n\n");
        }
        summary.append("Only receipts explicitly approved here will be saved. Continue?");
        javax.swing.JTextArea text = new javax.swing.JTextArea(summary.toString(), 22, 72);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        Object[] options = {"Approve and Save", "Cancel Approval"};
        return JOptionPane.showOptionDialog(this, new StyledScrollPane(text), "Approve imported receipts",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0;
    }

    private void savePrepared(List<Prepared> prepared) {
        status.setText("Saving approved receipts and handling attachments…");
        approvalWorker = new SwingWorker<>() {
            @Override protected List<SaveResult> doInBackground() {
                return approvalService.approve(prepared, this::publish);
            }

            @Override protected void process(List<SaveResult> updates) {
                for (SaveResult result : updates) {
                    approvalResults.put(result.candidateId(), result);
                    status.setText(statusLabel(result));
                }
                receipts.repaint();
                updateActions();
            }

            @Override protected void done() {
                approvalWorker = null;
                approvalBusy = false;
                try {
                    List<SaveResult> results = get();
                    long saved = results.stream().filter(result -> result.type() == ResultType.SAVED
                            || result.type() == ResultType.SAVED_WITHOUT_ATTACHMENT).count();
                    long failed = results.size() - saved;
                    status.setText(saved + " receipt(s) saved; " + failed
                            + " receipt(s) need attention. Failed drafts remain available for correction.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Saving was interrupted. Check each receipt status before retrying.");
                } catch (ExecutionException e) {
                    status.setText("The approval batch stopped: " + safeMessage(e.getCause()));
                }
                receipts.repaint();
                updateActions();
            }
        };
        updateActions();
        approvalWorker.execute();
    }

    private static String statusLabel(SaveResult result) {
        return switch (result.type()) {
            case SAVED -> "Saved";
            case SAVED_WITHOUT_ATTACHMENT -> "Saved — attachment not uploaded";
            case ATTACHMENT_FAILED -> "Saved — attachment needs attention";
            case FAILED -> "Not saved — " + result.message();
        };
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Please verify your access and try again." : cause.getMessage();
    }

    private void cancelSession() {
        if (approvalBusy || closing) return;
        if (!session.documents().isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Close this review session? Unsaved drafts will be discarded, and saved receipts will remain in the database.",
                    "Close receipt import", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }
        if (worker != null) {
            closeAfterImport = true;
            worker.cancel(true);
            status.setText("Cancelling import and cleaning temporary files…");
            updateActions();
            return;
        }
        cleanupAndClose();
    }

    private void cleanupAndClose() {
        if (closing) return;
        closing = true;
        updateActions();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                importService.close();
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    JOptionPane.showMessageDialog(ReceiptImportDialog.this,
                            "Temporary import files may not have been fully cleaned up.",
                            "Cleanup warning", JOptionPane.WARNING_MESSAGE);
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(ReceiptImportDialog.this,
                            "Temporary import files could not be fully cleaned up: " + safeMessage(e.getCause()),
                            "Cleanup warning", JOptionPane.WARNING_MESSAGE);
                }
                dispose();
            }
        }.execute();
    }
}
