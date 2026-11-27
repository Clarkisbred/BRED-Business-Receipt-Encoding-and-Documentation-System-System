package com.bred.rms.ui.admin;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Administrators add accounts, change names/roles and activate or deactivate people. */
public final class UserManagementPanel extends JPanel implements RefreshablePanel {
    private static final DateTimeFormatter REGISTERED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final AppContext ctx;
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Name", "Email", "Applying Company", "Role", "Registration Date", "Account Status"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JButton edit = new JButton("Edit");
    private final JButton toggle = new JButton("Deactivate");
    private final JButton approve = new JButton("Approve");
    private final JButton reject = new JButton("Reject");
    private final JButton resetPassword = new JButton("Send reset link");
    private final JButton delete = new JButton("Delete");
    private final boolean creator;
    private List<User> shown = new ArrayList<>();

    /** Builds the panel and loads accounts. */
    public UserManagementPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        User actor = ctx.currentUser.get();
        creator = actor != null && actor.role() == Role.CREATOR;
        JButton add = new JButton("Add user");
        add.addActionListener(e -> openForm(null));
        edit.addActionListener(e -> selected().ifPresent(this::openForm));
        toggle.addActionListener(e -> selected().ifPresent(this::toggleActive));
        approve.addActionListener(e -> selected().ifPresent(u -> review(u, AccountStatus.APPROVED)));
        reject.addActionListener(e -> selected().ifPresent(u -> review(u, AccountStatus.REJECTED)));
        resetPassword.addActionListener(e -> selected().ifPresent(this::sendPasswordReset));
        delete.addActionListener(e -> selected().ifPresent(this::deleteUser));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.add(add);
        bar.add(edit);
        bar.add(toggle);
        bar.add(approve);
        bar.add(reject);
        if (creator) {
            bar.add(resetPassword);
            bar.add(delete);
        }
        add(bar, BorderLayout.NORTH);
        ModernTable.apply(table);
        table.getColumnModel().getColumn(5).setCellRenderer(new StatusRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selected().ifPresent(UserManagementPanel.this::openForm);
            }
        });
        add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        updateButtons();
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(ctx.users::list, this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(List<User> users) {
        shown = new ArrayList<>(users);
        shown.sort((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()));
        model.setRowCount(0);
        int pending = 0;
        for (User u : shown) {
            if (u.accountStatus() == AccountStatus.PENDING) pending++;
            String accountStatus = u.accountStatus() == AccountStatus.APPROVED && !u.active()
                    ? "INACTIVE" : u.accountStatus().name();
            model.addRow(new Object[]{u.fullName(), u.email(), u.companyName(), u.role().label(),
                    REGISTERED.format(u.createdAt()), accountStatus});
        }
        status.setText(shown.size() + " user(s) · " + pending + " pending registration(s)");
        updateButtons();
    }

    private Optional<User> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= shown.size() ? Optional.empty() : Optional.of(shown.get(row));
    }

    private void updateButtons() {
        Optional<User> u = selected();
        edit.setEnabled(u.isPresent());
        toggle.setEnabled(u.isPresent() && u.get().accountStatus() == AccountStatus.APPROVED);
        toggle.setText(u.isPresent() && !u.get().active() ? "Activate" : "Deactivate");
        boolean pending = u.isPresent() && u.get().accountStatus() == AccountStatus.PENDING;
        approve.setEnabled(pending);
        reject.setEnabled(pending);
        if (creator) {
            boolean otherAccount = u.isPresent() && ctx.currentUser.get() != null
                    && !u.get().id().equals(ctx.currentUser.get().id());
            resetPassword.setEnabled(otherAccount);
            delete.setEnabled(otherAccount);
        }
    }

    private void openForm(User existing) {
        new UserFormDialog(SwingUtilities.getWindowAncestor(this), ctx, existing, this::refreshData).setVisible(true);
    }

    private void toggleActive(User u) {
        toggle.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.users.update(u.id(), u.fullName(), u.role(), !u.active());
            return Boolean.TRUE;
        }, ok -> refreshData(), e -> {
            status.setText(UiErrors.friendly(e));
            updateButtons();
        });
    }

    private void review(User user, AccountStatus decision) {
        String verb = decision == AccountStatus.APPROVED ? "approve" : "reject";
        int answer = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to " + verb + " " + user.fullName() + " (" + user.email() + ")?",
                decision == AccountStatus.APPROVED ? "Approve registration" : "Reject registration",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        approve.setEnabled(false);
        reject.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.users.reviewRegistration(user.id(), decision);
            return Boolean.TRUE;
        }, ignored -> refreshData(), error -> {
            status.setText(UiErrors.friendly(error));
            updateButtons();
        });
    }

    private void sendPasswordReset(User user) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Send the BRED-branded password reset email to " + user.email() + "?\n"
                        + "The recipient must open the email to choose a new password.",
                "Send password reset email", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        resetPassword.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.users.sendPasswordReset(user.id());
            return Boolean.TRUE;
        }, ignored -> {
            status.setText("BRED-branded password reset email sent to " + user.email() + ".");
            updateButtons();
        }, error -> {
            status.setText(UiErrors.friendly(error));
            updateButtons();
        });
    }

    private void deleteUser(User user) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Permanently delete " + user.fullName() + " (" + user.email() + ")?\n"
                        + "Their message text will remain in History as a deleted user.",
                "Delete account", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        delete.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.users.delete(user.id());
            return Boolean.TRUE;
        }, ignored -> {
            status.setText("Deleted " + user.email() + ".");
            refreshData();
        }, error -> {
            status.setText(UiErrors.friendly(error));
            updateButtons();
        });
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            java.awt.Component cell = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (!selected) {
                String status = value == null ? "" : value.toString();
                setForeground(switch (status) {
                    case "PENDING" -> RmsColors.WARNING;
                    case "APPROVED" -> RmsColors.SUCCESS;
                    case "REJECTED", "INACTIVE" -> RmsColors.DANGER;
                    default -> RmsColors.SECONDARY_TEXT;
                });
            }
            return cell;
        }
    }
}
