package com.bred.rms.ui.receipts;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Shows one receipt as a paper slip, with Edit, Delete (admins) and attachment buttons. */
public final class ReceiptDetailPanel extends JPanel implements RefreshablePanel {
    private record Loaded(Optional<Receipt> receipt, Supplier supplier, Project project, String encodedBy,
                          String companyName) { }

    private final AppContext ctx;
    private final UUID receiptId;
    private final Runnable onBack;
    private final Runnable onChanged;
    private final JPanel paperHolder = new JPanel(new GridBagLayout());
    private final JPanel attachmentBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JLabel status = new JLabel(" ");
    private final JButton edit = new JButton("Edit");
    private final JButton delete = new JButton("Delete");
    private Receipt current;

    /** Builds the panel and starts loading; {@code onBack} returns to the directory, {@code onChanged} follows edits/deletes. */
    public ReceiptDetailPanel(AppContext ctx, UUID receiptId, Runnable onBack, Runnable onChanged) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        this.receiptId = receiptId;
        this.onBack = onBack;
        this.onChanged = onChanged;

        JButton back = new JButton("← Back to receipts");
        back.addActionListener(e -> onBack.run());
        edit.setEnabled(false);
        edit.addActionListener(e -> openEditor());
        delete.setEnabled(false);
        delete.addActionListener(e -> confirmDelete());
        status.setForeground(RmsColors.SECONDARY_TEXT);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.add(back);
        bar.add(edit);
        User user = ctx.currentUser.get();
        if (user != null && user.role().canManageCompany()) bar.add(delete);
        bar.add(status);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(bar, BorderLayout.NORTH);
        top.add(attachmentBar, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        paperHolder.setOpaque(false);
        JScrollPane scroll = new StyledScrollPane(paperHolder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        refreshData();
    }

    @Override
    public void refreshData() {
        status.setText("Loading…");
        BackgroundTask.run(() -> {
            Optional<Receipt> r = ctx.receipts.find(receiptId);
            if (r.isEmpty()) return new Loaded(r, null, null, null, null);
            Supplier supplier = ctx.suppliers.findAll().stream().filter(s -> s.id().equals(r.get().supplierId())).findFirst().orElse(null);
            Project project = r.get().projectId() == null ? null
                    : ctx.projects.findAll().stream().filter(p -> p.id().equals(r.get().projectId())).findFirst().orElse(null);
            User encoder = encoder(r.get().encodedBy());
            return new Loaded(r, supplier, project,
                    encoder == null ? null : encoder.fullName(),
                    encoder == null ? null : encoder.companyName());
        }, this::show, e -> status.setText(message(e)));
    }

    /** Looks up who encoded it; any failure just leaves the name out. */
    private User encoder(UUID id) {
        if (id == null || ctx.userRepository == null) return null;
        try {
            return ctx.userRepository.findById(id).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void show(Loaded loaded) {
        paperHolder.removeAll();
        attachmentBar.removeAll();
        if (loaded.receipt().isEmpty()) {
            current = null;
            edit.setEnabled(false);
            delete.setEnabled(false);
            status.setText("This receipt no longer exists.");
        } else {
            current = loaded.receipt().get();
            edit.setEnabled(true);
            delete.setEnabled(true);
            status.setText(" ");
            paperHolder.add(new PaperReceiptView(current, loaded.supplier(), loaded.project(),
                    loaded.encodedBy(), loaded.companyName()));
            for (Attachment a : current.attachments()) {
                JButton open = new JButton("Open " + a.fileName());
                open.addActionListener(e -> openAttachment(a, open));
                attachmentBar.add(open);
            }
        }
        revalidate();
        repaint();
    }

    private void openEditor() {
        if (current == null) return;
        new ReceiptFormDialog(SwingUtilities.getWindowAncestor(this), ctx, current, saved -> {
            onChanged.run();
            refreshData();
        }).setVisible(true);
    }

    private void confirmDelete() {
        if (current == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete receipt " + current.receiptNo() + " and its attachments? This cannot be undone.",
                "Delete receipt", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        delete.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.receipts.delete(receiptId);
            return Boolean.TRUE;
        }, ok -> {
            onChanged.run();
            onBack.run();
        }, e -> {
            delete.setEnabled(true);
            status.setText(message(e));
        });
    }

    /** Downloads the file to a temporary folder and opens it with the system viewer. */
    private void openAttachment(Attachment a, JButton button) {
        button.setEnabled(false);
        BackgroundTask.run(() -> {
            byte[] bytes = ctx.attachments.download(a);
            Path dir = Files.createTempDirectory("rms-attachment");
            String safe = a.fileName().replaceAll("[^A-Za-z0-9._ -]", "_");
            Path file = dir.resolve(safe.isBlank() ? "attachment" : safe);
            Files.write(file, bytes);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return file.toFile();
        }, (File file) -> {
            button.setEnabled(true);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file);
                } else {
                    status.setText("Saved to " + file.getAbsolutePath());
                }
            } catch (java.io.IOException | RuntimeException ex) {
                status.setText("Could not open the file. It was saved to " + file.getAbsolutePath());
            }
        }, e -> {
            button.setEnabled(true);
            status.setText(message(e));
        });
    }

    private static String message(Throwable e) { return com.bred.rms.ui.UiErrors.friendly(e); }
}
