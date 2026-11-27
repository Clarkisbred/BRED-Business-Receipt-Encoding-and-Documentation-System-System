package com.bred.rms.ui.admin;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.ActivityEntry;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
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

/** Newest-first list of who did what, with a search box (administrators only). */
public final class ActivityLogPanel extends JPanel implements RefreshablePanel {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final AppContext ctx;
    private final JTextField search = new JTextField(24);
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"When", "User", "Action", "Detail"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private List<ActivityEntry> all = new ArrayList<>();

    /** Builds the panel and loads the log. */
    public ActivityLogPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> refreshData());
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(search, BorderLayout.CENTER);
        bar.add(reload, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        ModernTable.apply(table);
        add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(ctx.activityLog::findAll, entries -> {
            all = entries;
            applyFilter();
        }, e -> status.setText(UiErrors.friendly(e)));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void applyFilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        model.setRowCount(0);
        int shown = 0;
        for (ActivityEntry a : all) {
            String when = a.at() == null ? "" : STAMP.format(a.at().atZone(ZoneId.systemDefault()));
            String hay = (when + " " + nz(a.userName()) + " " + nz(a.action()) + " " + nz(a.detail())).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            model.addRow(new Object[]{when, nz(a.userName()), nz(a.action()), nz(a.detail())});
            shown++;
        }
        status.setText(shown + " of " + all.size() + " entries");
    }
}
