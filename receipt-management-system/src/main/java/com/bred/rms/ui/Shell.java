package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.config.AiImportConfig;
import com.bred.rms.ai.AIProviders;
import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.ai.GroqDuckyService;
import com.bred.rms.models.Role;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.User;
import com.bred.rms.security.AccessPolicy;
import com.bred.rms.security.Module;
import com.bred.rms.ui.components.StatusBadge;
import com.bred.rms.ui.components.SurfacePanel;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.UserAvatar;
import com.bred.rms.ui.components.UiConstants;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.admin.ActivityLogPanel;
import com.bred.rms.ui.admin.UserManagementPanel;
import com.bred.rms.ui.CompanyPanel;
import com.bred.rms.ui.HistoryPanel;
import com.bred.rms.ui.lookups.ProjectPanel;
import com.bred.rms.ui.lookups.SupplierPanel;
import com.bred.rms.ui.construction.ConstructionPanel;
import com.bred.rms.ui.map.LiveProjectMapPanel;
import com.bred.rms.ui.receipts.ReceiptDetailPanel;
import com.bred.rms.ui.reports.ReportsPanel;
import com.bred.rms.ui.receipts.ReceiptDirectoryPanel;
import com.bred.rms.ui.receipts.ReceiptFormDialog;
import com.bred.rms.ui.receipts.ReceiptImportDialog;
import com.bred.rms.importer.DocumentImportService;
import com.bred.rms.importer.TesseractOcrService;
import com.bred.rms.services.ReceiptImportApprovalService;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.utils.BackgroundTask;
import com.bred.rms.updates.UpdatePreferences;
import com.bred.rms.updates.UpdateWorkflow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/** The signed-in frame: sidebar menu (filtered by role), header with user info, and the active screen. */
public final class Shell extends JPanel {
    private final AppContext ctx;
    private final UpdatePreferences updatePreferences;
    private final UpdateWorkflow updateWorkflow;
    private final List<Module> modules;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<Module, JComponent> built = new EnumMap<>(Module.class);
    private final Map<Module, JToggleButton> navButtons = new EnumMap<>(Module.class);
    private final JLabel title = new JLabel();
    private final JLabel breadcrumb = new JLabel("Workspace");
    private final JLabel brand = new JLabel("BRED");
    private final Consumer<Boolean> themeListener = dark -> brand.setForeground(RmsColors.primary());
    private static final String DETAIL_CARD = "RECEIPT_DETAIL";
    private NavListPanel navList;
    private ReceiptDirectoryPanel directory;
    private ReceiptDetailPanel detail;
    private Module activeModule;
    private final Timer broadcastPollTimer = new Timer(2500, event -> pollBroadcasts());
    private final Queue<ChatMessage> pendingBroadcasts = new LinkedList<>();
    private final Set<UUID> seenBroadcastIds = new HashSet<>();
    private final Instant broadcastPollStart = Instant.now().minusMillis(100);
    private Instant broadcastCursor = broadcastPollStart;
    private boolean broadcastPollActive;
    private boolean broadcastToastVisible;

    public Shell(AppContext ctx, Runnable onSignOut) {
        this(ctx, onSignOut, null, null);
    }

    public Shell(AppContext ctx, Runnable onSignOut, UpdatePreferences updatePreferences,
                 UpdateWorkflow updateWorkflow) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.updatePreferences = updatePreferences;
        this.updateWorkflow = updateWorkflow;
        User user = ctx.currentUser.get();
        Role role = user == null ? Role.ENCODER : user.role();
        this.modules = AccessPolicy.modulesFor(role);
        broadcastPollTimer.setCoalesce(true);

        add(buildSidebar(user), BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout());
        right.add(buildHeader(user, onSignOut), BorderLayout.NORTH);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        right.add(content, BorderLayout.CENTER);
        add(right, BorderLayout.CENTER);
        show(modules.get(0));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ThemeManager.addThemeListener(themeListener);
        if (userIsRecipient()) broadcastPollTimer.start();
    }

    @Override
    public void removeNotify() {
        broadcastPollTimer.stop();
        pendingBroadcasts.clear();
        ThemeManager.removeThemeListener(themeListener);
        super.removeNotify();
    }

    /** The menu entries this user can see, in order. */
    public List<Module> visibleModules() { return modules; }

    /** Opens a screen, building it on first use. */
    public void show(Module m) {
        if (!modules.contains(m)) return;
        activeModule = m;
        boolean existed = built.containsKey(m);
        built.computeIfAbsent(m, k -> {
            JComponent c = build(k);
            content.add(c, k.name());
            return c;
        });
        if (existed && built.get(m) instanceof RefreshablePanel p) p.refreshData();
        cards.show(content, m.name());
        title.setText(m.title());
        breadcrumb.setText(navigationGroup(m).equals("WORKSPACE")
                ? "Workspace / " + m.title()
                : navigationGroup(m) + " / " + m.title());
        JToggleButton b = navButtons.get(m);
        if (b != null) {
            b.setSelected(true);
            navList.select(modules.indexOf(m));
        }
    }

    Module activeModule() { return activeModule; }

    /** Creates the screen for a module. */
    private JComponent build(Module m) {
        return switch (m) {
            case RECEIPTS -> {
                directory = new ReceiptDirectoryPanel(ctx, this::openReceipt, this::addReceipt,
                        this::importReceiptDocuments);
                yield directory;
            }
            case DASHBOARD -> new DashboardPanel(ctx, this::openReceipt);
            case MESSAGES -> new MessagesPanel(ctx);
            case DUCKY -> {
                GroqCredentials credentials = new GroqCredentials();
                GroqDuckyService service = ctx.supabaseRest == null
                        ? new GroqDuckyService(ctx, credentials, this::show, this::openReceipt, this::addReceipt)
                        : new GroqDuckyService(ctx, credentials, ctx.supabaseRest,
                                this::show, this::openReceipt, this::addReceipt);
                var signedIn = ctx.currentUser.get();
                yield new DuckyPanel(service, credentials,
                        signedIn != null && signedIn.role() == Role.CREATOR,
                        signedIn);
            }
            case REPORTS -> new ReportsPanel(ctx);
            case USERS -> new UserManagementPanel(ctx);
            case ACTIVITY_LOG -> new ActivityLogPanel(ctx);
            case COMPANIES -> new CompanyPanel(ctx);
            case HISTORY -> new HistoryPanel(ctx);
            case SETTINGS -> {
                SettingsPanel settings = updateWorkflow == null
                        ? new SettingsPanel(ctx)
                        : new SettingsPanel(ctx, updatePreferences, null, updateWorkflow);
                JScrollPane scroll = new StyledScrollPane(settings);
                scroll.getVerticalScrollBar().setUnitIncrement(40);
                yield scroll;
            }
            case SUPPLIERS -> new SupplierPanel(ctx);
            case PROJECTS -> new ProjectPanel(ctx);
            case LIVE_MAP -> new LiveProjectMapPanel(ctx);
            case CONSTRUCTION -> new ConstructionPanel(ctx);
        };
    }

    /** Opens a receipt's paper-style detail view in place of the current screen. */
    public void openReceipt(java.util.UUID id) {
        activeModule = Module.RECEIPTS;
        if (detail != null) content.remove(detail);
        detail = new ReceiptDetailPanel(ctx, id, this::backToDirectory, () -> {
            if (directory != null) directory.refreshData();
        });
        content.add(detail, DETAIL_CARD);
        cards.show(content, DETAIL_CARD);
        title.setText("Receipt");
        breadcrumb.setText("Workspace / Receipts / Receipt");
        JToggleButton b = navButtons.get(Module.RECEIPTS);
        if (b != null) {
            b.setSelected(true);
            navList.select(modules.indexOf(Module.RECEIPTS));
        }
    }

    private void backToDirectory() {
        show(Module.RECEIPTS);
        if (directory != null) directory.refreshData();
    }

    /** Opens the add-receipt form; the directory reloads after a successful save. */
    private void addReceipt() {
        new ReceiptFormDialog(SwingUtilities.getWindowAncestor(this), ctx, null, saved -> {
            if (directory != null) directory.refreshData();
            openReceipt(saved.id());
        }).setVisible(true);
    }

    private void importReceiptDocuments() {
        User user = ctx.currentUser.get();
        if (user == null || user.role() != Role.ADMIN || !user.active()) return;
        if (user.companyId() == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Your administrator account is not associated with a company. Contact an administrator.",
                    "AI Receipt Importer", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            AiImportConfig config = AiImportConfig.load();
            DocumentImportService importer = new DocumentImportService(AIProviders.create(config),
                    new TesseractOcrService(config.tesseractCommand(), config.requestTimeout()));
            ReceiptImportApprovalService approval = new ReceiptImportApprovalService(ctx.receipts, ctx.suppliers,
                    ctx.userRepository, ctx.attachments, ctx.currentUser);
            new ReceiptImportDialog(SwingUtilities.getWindowAncestor(this), importer, approval).setVisible(true);
            if (directory != null) directory.refreshData();
        } catch (IllegalArgumentException | IllegalStateException error) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "The AI receipt importer configuration is invalid: " + error.getMessage(),
                    "AI Receipt Importer", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    private boolean userIsRecipient() {
        User user = ctx.currentUser.get();
        return user != null && user.active() && user.role() != Role.CREATOR;
    }

    private void pollBroadcasts() {
        User user = ctx.currentUser.get();
        if (!userIsRecipient() || broadcastPollActive) return;
        broadcastPollActive = true;
        BackgroundTask.run(() -> ctx.messages.findBroadcastsFor(user.id(), broadcastCursor), messages -> {
            broadcastPollActive = false;
            for (ChatMessage message : messages) {
                if (message.sentAt().isBefore(broadcastPollStart)) continue;
                if (message.sentAt().isAfter(broadcastCursor)) {
                    broadcastCursor = message.sentAt();
                    seenBroadcastIds.clear();
                }
                if (seenBroadcastIds.add(message.id())) pendingBroadcasts.add(message);
            }
            showNextBroadcast();
        }, error -> {
            broadcastPollActive = false;
            broadcastPollTimer.stop();
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Broadcast notifications stopped: " + UiErrors.friendly(error),
                    "Broadcast notifications", javax.swing.JOptionPane.ERROR_MESSAGE);
        });
    }

    private void showNextBroadcast() {
        if (broadcastToastVisible || pendingBroadcasts.isEmpty()) return;
        ChatMessage message = pendingBroadcasts.remove();
        broadcastToastVisible = true;
        BroadcastToast toast = new BroadcastToast(SwingUtilities.getWindowAncestor(this), message.senderName(),
                message.senderEmail(),
                message.body(), closed -> {
                    broadcastToastVisible = false;
                    showNextBroadcast();
                });
        toast.showToast();
    }

    private JComponent buildSidebar(User user) {
        SurfacePanel side = new SurfacePanel(null, RmsColors::surface) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setColor(RmsColors.border());
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                g.dispose();
            }
        };
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(24, 18, 18, 18));
        side.setPreferredSize(new Dimension(UiConstants.SIDEBAR_WIDTH, 0));
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 24f));
        brand.setForeground(RmsColors.primary());
        String companyName = user == null ? "" : user.companyName();
        String companyText = companyName.isBlank()
                ? (user != null && user.role() == Role.CREATOR ? "All companies" : "No company")
                : companyName;
        JLabel sub = new JLabel("<html><b>" + escapeHtml(companyText) + "</b><br>"
                + "Business Receipt<br>Encoding and<br>Documentation System</html>");
        sub.setForeground(RmsColors.SECONDARY_TEXT);
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));
        brand.setAlignmentX(LEFT_ALIGNMENT);
        sub.setAlignmentX(LEFT_ALIGNMENT);
        side.add(brand);
        side.add(sub);
        side.add(Box.createVerticalStrut(30));

        ButtonGroup group = new ButtonGroup();
        navList = new NavListPanel();
        String activeGroup = null;
        for (Module m : modules) {
            String groupName = navigationGroup(m);
            if (!groupName.equals(activeGroup)) {
                activeGroup = groupName;
                navList.addSection(groupName);
            }
            JToggleButton b = new NavButton(m.title());
            b.setHorizontalAlignment(JToggleButton.LEFT);
            b.setMargin(new Insets(0, 16, 0, 12));
            b.setFocusable(false);
            b.addActionListener(e -> show(m));
            group.add(b);
            navButtons.put(m, b);
            navList.addButton(b);
        }
        int navHeight = navList.preferredHeight();
        navList.setPreferredSize(new Dimension(216, navHeight));
        navList.setMaximumSize(new Dimension(Integer.MAX_VALUE, navHeight));
        navList.setAlignmentX(LEFT_ALIGNMENT);
        JScrollPane navScroll = new StyledScrollPane(navList);
        navScroll.setName("sidebarNavigationScroll");
        navScroll.setBorder(BorderFactory.createEmptyBorder());
        navScroll.setOpaque(false);
        navScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        navScroll.getViewport().setOpaque(false);
        navScroll.setPreferredSize(new Dimension(216, Math.min(modules.size() * 56, 440)));
        navScroll.setMinimumSize(new Dimension(216, 72));
        navScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        navScroll.setAlignmentX(LEFT_ALIGNMENT);
        navScroll.setViewportBorder(BorderFactory.createEmptyBorder());

        JScrollBar navBar = navScroll.getVerticalScrollBar();
        navBar.setUnitIncrement(42);
        navBar.setBlockIncrement(168);
        if (user != null && user.role() == Role.CREATOR) {
            navList.setSelectionTrailingInset(navBar.getPreferredSize().width);
        }
        side.add(navScroll);
        side.add(Box.createVerticalGlue());
        return side;
    }

    private static final class NavButton extends JToggleButton {
        private NavButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorderPainted(false);
            setFocusPainted(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) {
                RoundRectangle2D shape = new RoundRectangle2D.Float(
                        1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
                g.setColor(ThemeManager.isDark()
                        ? new Color(RmsColors.primaryAccent().getRed(),
                                RmsColors.primaryAccent().getGreen(),
                                RmsColors.primaryAccent().getBlue(), 38)
                        : RmsColors.primaryLight());
                g.fill(shape);
                g.setColor(RmsColors.primaryAccent());
                g.setStroke(new BasicStroke(1.2f));
                g.draw(shape);
            }
            if (getModel().isRollover() && !isSelected()) {
                RoundRectangle2D shape = new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
                g.setColor(ThemeManager.isDark()
                        ? new Color(255, 255, 255, 12)
                        : new Color(RmsColors.primaryAccent().getRed(), RmsColors.primaryAccent().getGreen(), RmsColors.primaryAccent().getBlue(), 14));
                g.fill(shape);
            }
            setForeground(isSelected() && !ThemeManager.isDark() ? RmsColors.primaryDark() : RmsColors.text());
            super.paintComponent(g);
            g.dispose();
        }
    }

    private static final class NavListPanel extends JPanel {
        private static final int ROW_HEIGHT = 50;
        private static final int ROW_GAP = 6;
        private static final int SECTION_HEIGHT = 24;
        private static final int ARC = 14;
        private final List<JToggleButton> buttons = new ArrayList<>();
        private int contentHeight;
        private int selectionTrailingInset;

        private NavListPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        private void addButton(JToggleButton button) {
            buttons.add(button);
            button.setAlignmentX(LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
            button.setPreferredSize(new Dimension(216, ROW_HEIGHT));
            add(button);
            add(Box.createVerticalStrut(ROW_GAP));
            contentHeight += ROW_HEIGHT + ROW_GAP;
        }

        private void addSection(String title) {
            JLabel section = new JLabel(title);
            section.setFont(section.getFont().deriveFont(Font.BOLD, 10f));
            section.setForeground(RmsColors.SECONDARY_TEXT);
            section.setAlignmentX(LEFT_ALIGNMENT);
            section.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 2));
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE, SECTION_HEIGHT));
            section.setPreferredSize(new Dimension(216, SECTION_HEIGHT));
            add(section);
            contentHeight += SECTION_HEIGHT;
        }

        private int preferredHeight() {
            return Math.max(ROW_HEIGHT, contentHeight);
        }

        private void setSelectionTrailingInset(int inset) {
            selectionTrailingInset = Math.max(0, inset);
            repaint();
        }

        private void select(int index) {
            if (index < 0 || index >= buttons.size()) return;
            JToggleButton selected = buttons.get(index);
            selected.setSelected(true);
            scrollRectToVisible(new java.awt.Rectangle(
                    0, selected.getY(), getWidth(), selected.getHeight()));
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String navigationGroup(Module module) {
        return switch (module) {
            case DASHBOARD, MESSAGES, DUCKY, RECEIPTS -> "WORKSPACE";
            case PROJECTS, LIVE_MAP, CONSTRUCTION -> "PROJECT MANAGEMENT";
            case SUPPLIERS, REPORTS -> "FINANCIAL & OPERATIONS";
            case USERS, ACTIVITY_LOG, COMPANIES, HISTORY, SETTINGS -> "SYSTEM";
        };
    }

    private JComponent buildHeader(User user, Runnable onSignOut) {
        SurfacePanel header = new SurfacePanel(new BorderLayout(), RmsColors::surface);
        header.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        header.setPreferredSize(new Dimension(0, UiConstants.HEADER_HEIGHT));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        breadcrumb.setFont(breadcrumb.getFont().deriveFont(Font.PLAIN, 11f));
        breadcrumb.setForeground(RmsColors.SECONDARY_TEXT);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(breadcrumb);
        heading.add(Box.createVerticalStrut(2));
        heading.add(title);
        header.add(heading, BorderLayout.WEST);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        tools.setOpaque(false);
        boolean sharedCloud = ctx.storageLabel.startsWith("Supabase project ");
        StatusBadge storage = StatusBadge.of(sharedCloud ? "Shared cloud" : "Local only",
                sharedCloud ? RmsColors.SUCCESS : RmsColors.WARNING);
        storage.setToolTipText(sharedCloud
                ? "Receipts are stored in the shared Supabase project."
                : "Receipts are stored only on this computer.");
        tools.add(storage);
        if (user != null) {
            if (user.role() == Role.CREATOR || UserAvatar.isDuckProfile(user.email())) {
                UserAvatar avatar = new UserAvatar(44, 40, false);
                avatar.setProfile(user.fullName(), user.email());
                tools.add(avatar);
            }
            tools.add(new JLabel(user.fullName()));
            tools.add(StatusBadge.of(user.role().label(), user.role().canManageCompany() ? RmsColors.primary() : RmsColors.INFO));
        }
        JButton theme = new JButton(ThemeManager.isDark() ? "Light mode" : "Dark mode");
        theme.addActionListener(e -> {
            ThemeManager.toggle();
            theme.setText(ThemeManager.isDark() ? "Light mode" : "Dark mode");
        });
        JButton out = new JButton("Sign out");
        out.addActionListener(e -> onSignOut.run());
        tools.add(theme);
        tools.add(out);
        header.add(tools, BorderLayout.EAST);
        return header;
    }

}
