package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Creator-only, cross-company history of direct, group, and broadcast messages. */
public final class HistoryPanel extends JPanel implements RefreshablePanel {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final AppContext ctx;
    private final JTextField search = new JTextField(24);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"When", "From", "To", "Type", "Message"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private List<ChatMessage> all = new ArrayList<>();

    public HistoryPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton reload = new JButton("Refresh");
        reload.addActionListener(event -> refreshData());
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(search, BorderLayout.CENTER);
        bar.add(reload, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);

        ModernTable.apply(table);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);
        add(new StyledScrollPane(table), BorderLayout.CENTER);

        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        refreshData();
    }

    @Override public void refreshData() {
        status.setText("Loading chat history...");
        BackgroundTask.run(ctx.messageService::findHistory, messages -> {
            all = messages;
            applyFilter();
        }, error -> status.setText(UiErrors.friendly(error)));
    }

    private void applyFilter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        model.setRowCount(0);
        for (ChatMessage message : all) {
            String sender = label(message.senderName());
            String recipient = message.companyId() == null ? label(message.recipientName()) : "Company chat";
            String type = message.companyId() != null
                    ? (message.urgent() ? "Company · Urgent" : "Company")
                    : message.broadcast() ? "Broadcast" : "Direct";
            String when = message.sentAt() == null ? "" : STAMP.format(message.sentAt().atZone(ZoneId.systemDefault()));
            String haystack = (when + " " + sender + " " + recipient + " " + type + " " + message.body())
                    .toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            model.addRow(new Object[]{when, sender, recipient, type, message.body()});
        }
        status.setText(model.getRowCount() + " of " + all.size() + " messages");
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "Deleted user" : value;
    }
}
