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
import com.bred.rms.ui.components.LucideIcon;
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
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
    private final Consumer<Boolean> themeListener = dark -> {
        brand.setForeground(RmsColors.primary());
        updateThemeControls();
    };
    private static final String DETAIL_CARD = "RECEIPT_DETAIL";
    private NavListPanel navList;
    private JTextField moduleSearch;
    private JPopupMenu moduleSuggestions;
    private JButton themeToggle;
    private JMenuItem profileThemeItem;
    private SurfacePanel moduleSearchSurface;
    private SurfacePanel sidebarProfileSurface;
    private JLabel sidebarSubtitle;
    private JLabel sidebarProfileName;
    private JLabel sidebarProfileRole;
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
        setBackground(RmsColors.background());

        add(buildSidebar(user), BorderLayout.WEST);
        SurfacePanel right = new SurfacePanel(new BorderLayout(), RmsColors::background);
        right.add(buildHeader(user, onSignOut), BorderLayout.NORTH);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        content.setOpaque(false);
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
        if (moduleSearch != null) moduleSearch.setText("");
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
        int sidebarContentWidth = UiConstants.SIDEBAR_WIDTH - 36;
        SurfacePanel side = new SurfacePanel(new BorderLayout(), RmsColors::sidebar) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setColor(RmsColors.border());
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                g.dispose();
            }
        };
        side.setBorder(BorderFactory.createEmptyBorder(24, 18, 18, 18));
        side.setPreferredSize(new Dimension(UiConstants.SIDEBAR_WIDTH, 0));
        brand.setName("sidebarBrand");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 24f));
        brand.setForeground(RmsColors.primary());
        String companyName = user == null ? "" : user.companyName();
        String companyText = companyName.isBlank()
                ? (user != null && user.role() == Role.CREATOR ? "All companies" : "No company")
                : companyName;
        sidebarSubtitle = new JLabel("<html><b>" + escapeHtml(companyText) + "</b><br>"
                + "Business Receipt<br>Encoding and<br>Documentation System</html>");
        sidebarSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        sidebarSubtitle.setFont(sidebarSubtitle.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel sidebarBrandBlock = new JPanel(new GridBagLayout());
        sidebarBrandBlock.setOpaque(false);
        sidebarBrandBlock.setBorder(BorderFactory.createEmptyBorder(0, 0, 30, 0));
        GridBagConstraints brandConstraints = new GridBagConstraints();
        brandConstraints.gridx = 0;
        brandConstraints.gridy = 0;
        brandConstraints.weightx = 1;
        brandConstraints.fill = GridBagConstraints.HORIZONTAL;
        brandConstraints.anchor = GridBagConstraints.LINE_START;
        sidebarBrandBlock.add(brand, brandConstraints);
        brandConstraints.gridy = 1;
        sidebarBrandBlock.add(sidebarSubtitle, brandConstraints);
        side.add(sidebarBrandBlock, BorderLayout.NORTH);

        ButtonGroup group = new ButtonGroup();
        navList = new NavListPanel();
        String activeGroup = null;
        for (Module m : modules) {
            String groupName = navigationGroup(m);
            if (!groupName.equals(activeGroup)) {
                activeGroup = groupName;
                navList.addSection(groupName);
            }
            JToggleButton b = new NavButton(m);
            b.setHorizontalAlignment(JToggleButton.LEFT);
            b.setMargin(new Insets(0, 16, 0, 12));
            b.setFocusable(true);
            b.addActionListener(e -> show(m));
            group.add(b);
            navButtons.put(m, b);
            navList.addButton(b);
        }
        int navHeight = navList.preferredHeight();
        int navigationContentWidth = sidebarContentWidth - 12;
        navList.setPreferredSize(new Dimension(navigationContentWidth, navHeight));
        navList.setMaximumSize(new Dimension(Integer.MAX_VALUE, navHeight));
        navList.setAlignmentX(LEFT_ALIGNMENT);
        JScrollPane navScroll = new StyledScrollPane(navList);
        navScroll.setName("sidebarNavigationScroll");
        navScroll.setBorder(BorderFactory.createEmptyBorder());
        navScroll.setOpaque(false);
        navScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        navScroll.getViewport().setOpaque(false);
        navScroll.setPreferredSize(
                new Dimension(sidebarContentWidth, Math.min(modules.size() * 56, 440)));
        navScroll.setMinimumSize(new Dimension(sidebarContentWidth, 72));
        navScroll.setMaximumSize(new Dimension(sidebarContentWidth, Integer.MAX_VALUE));
        navScroll.setViewportBorder(BorderFactory.createEmptyBorder());

        JScrollBar navBar = navScroll.getVerticalScrollBar();
        navBar.setUnitIncrement(42);
        navBar.setBlockIncrement(168);
        side.add(navScroll, BorderLayout.CENTER);
        side.add(buildSidebarProfile(user), BorderLayout.SOUTH);
        return side;
    }

    private static final class NavButton extends JToggleButton {
        private NavButton(Module module) {
            super(module.title());
            setIcon(new LucideIcon(moduleIcon(module), 18,
                    () -> isSelected() ? RmsColors.primaryAccent() : RmsColors.SECONDARY_TEXT));
            setIconTextGap(12);
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
                g.setStroke(new BasicStroke(hasFocus() ? 1.8f : 1.2f));
                g.draw(shape);
            }
            if (hasFocus() && !isSelected()) {
                g.setColor(RmsColors.primaryAccent());
                g.setStroke(new BasicStroke(1.4f));
                g.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 14, 14));
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

    private static final class NavListPanel extends JPanel implements Scrollable {
        private static final int ROW_HEIGHT = 50;
        private static final int ROW_GAP = 6;
        private static final int SECTION_HEIGHT = 24;
        private final List<JToggleButton> buttons = new ArrayList<>();
        private int contentHeight;

        private NavListPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        private void addButton(JToggleButton button) {
            buttons.add(button);
            button.setAlignmentX(LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
            button.setPreferredSize(new Dimension(UiConstants.SIDEBAR_WIDTH - 36 - 12, ROW_HEIGHT));
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
            section.setPreferredSize(new Dimension(UiConstants.SIDEBAR_WIDTH - 36 - 12, SECTION_HEIGHT));
            add(section);
            contentHeight += SECTION_HEIGHT;
        }

        private int preferredHeight() {
            return Math.max(ROW_HEIGHT, contentHeight);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL ? ROW_HEIGHT + ROW_GAP : 16;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? Math.max(ROW_HEIGHT, visibleRect.height - ROW_HEIGHT) : visibleRect.width - 16;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }

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
        SurfacePanel header = new SurfacePanel(new BorderLayout(16, 0), RmsColors::headerSurface);
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

        header.add(buildModuleSearch(), BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        tools.setOpaque(false);
        boolean sharedCloud = ctx.storageLabel.startsWith("Supabase project ");
        StatusBadge storage = StatusBadge.of(sharedCloud ? "Shared cloud" : "Local only",
                sharedCloud ? RmsColors.SUCCESS : RmsColors.WARNING);
        storage.setToolTipText(sharedCloud
                ? "Receipts are stored in the shared Supabase project."
                : "Receipts are stored only on this computer.");
        tools.add(storage);
        themeToggle = new JButton(new LucideIcon(ThemeManager.isDark() ? "sun" : "moon", 17,
                RmsColors::text));
        themeToggle.setName("themeToggle");
        themeToggle.setToolTipText(ThemeManager.isDark() ? "Switch to light theme" : "Switch to dark theme");
        themeToggle.getAccessibleContext().setAccessibleName("Toggle color theme");
        themeToggle.addActionListener(e -> ThemeManager.toggle());
        tools.add(themeToggle);
        JButton messages = new JButton(new LucideIcon("bell", 17, RmsColors::text));
        messages.setName("messageNotificationsButton");
        messages.setToolTipText("Open messages and broadcasts");
        messages.getAccessibleContext().setAccessibleName("Open messages and broadcasts");
        messages.addActionListener(event -> show(Module.MESSAGES));
        tools.add(messages);
        if (user != null) tools.add(buildProfileMenu(user, onSignOut));
        header.add(tools, BorderLayout.EAST);
        return header;
    }

    private JComponent buildModuleSearch() {
        moduleSearch = new JTextField();
        moduleSearch.setName("shellModuleSearch");
        moduleSearch.setOpaque(false);
        moduleSearch.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        moduleSearch.putClientProperty("JTextField.placeholderText", "Search modules");
        moduleSearch.getAccessibleContext().setAccessibleName("Search available modules");
        moduleSearch.setToolTipText("Find an available module");
        moduleSuggestions = new JPopupMenu();

        SurfacePanel searchField = new SurfacePanel(new BorderLayout(8, 0), RmsColors::surface);
        moduleSearchSurface = searchField;
        searchField.setName("shellModuleSearchField");
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border()),
                BorderFactory.createEmptyBorder(0, 10, 0, 10)));
        searchField.add(new JLabel(new LucideIcon("search", 16, () -> RmsColors.SECONDARY_TEXT)),
                BorderLayout.WEST);
        searchField.add(moduleSearch, BorderLayout.CENTER);
        searchField.setPreferredSize(new Dimension(220, 36));

        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateModuleSuggestions(); }
            @Override public void removeUpdate(DocumentEvent event) { updateModuleSuggestions(); }
            @Override public void changedUpdate(DocumentEvent event) { updateModuleSuggestions(); }
        };
        moduleSearch.getDocument().addDocumentListener(listener);
        moduleSearch.addActionListener(event -> openFirstModuleMatch());
        moduleSearch.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    moduleSuggestions.setVisible(false);
                    moduleSearch.setText("");
                }
            }
        });
        moduleSearch.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) {
                SwingUtilities.invokeLater(() -> {
                    if (!moduleSuggestions.isFocusOwner()) moduleSuggestions.setVisible(false);
                });
            }
        });
        return searchField;
    }

    private void updateModuleSuggestions() {
        if (moduleSuggestions == null || moduleSearch == null) return;
        moduleSuggestions.removeAll();
        String query = moduleSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty()) {
            moduleSuggestions.setVisible(false);
            return;
        }
        for (Module module : modules) {
            if (!module.title().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            JMenuItem suggestion = new JMenuItem(module.title(),
                    new LucideIcon(moduleIcon(module), 16, RmsColors::primary));
            suggestion.addActionListener(event -> show(module));
            moduleSuggestions.add(suggestion);
        }
        if (moduleSuggestions.getComponentCount() == 0) {
            moduleSuggestions.setVisible(false);
        } else if (moduleSearch.isShowing()) {
            moduleSuggestions.show(moduleSearch, 0, moduleSearch.getHeight());
        }
    }

    private void openFirstModuleMatch() {
        String query = moduleSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        modules.stream()
                .filter(module -> module.title().toLowerCase(java.util.Locale.ROOT).contains(query))
                .findFirst()
                .ifPresent(this::show);
    }

    private JComponent buildSidebarProfile(User user) {
        SurfacePanel profile = new SurfacePanel(new BorderLayout(10, 0), RmsColors::headerSurface);
        sidebarProfileSurface = profile;
        profile.setName("sidebarProfile");
        profile.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border()),
                BorderFactory.createEmptyBorder(9, 8, 9, 8)));
        if (user == null) return profile;

        String name = user.fullName() == null || user.fullName().isBlank() ? user.email() : user.fullName();
        String email = user.email() == null ? "" : user.email();
        UserAvatar avatar = new UserAvatar(38, 34, false);
        avatar.setProfile(name, email);
        profile.add(avatar, BorderLayout.WEST);
        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        sidebarProfileName = new JLabel(name);
        sidebarProfileName.setFont(sidebarProfileName.getFont().deriveFont(Font.BOLD, 12f));
        sidebarProfileName.setForeground(RmsColors.text());
        sidebarProfileName.setToolTipText(name);
        sidebarProfileRole = new JLabel(user.role().label());
        sidebarProfileRole.setFont(sidebarProfileRole.getFont().deriveFont(Font.PLAIN, 11f));
        sidebarProfileRole.setForeground(RmsColors.SECONDARY_TEXT);
        identity.add(sidebarProfileName);
        identity.add(Box.createVerticalStrut(3));
        identity.add(sidebarProfileRole);
        profile.add(identity, BorderLayout.CENTER);
        return profile;
    }

    private JButton buildProfileMenu(User user, Runnable onSignOut) {
        String name = user.fullName() == null || user.fullName().isBlank() ? user.email() : user.fullName();
        JButton account = new JButton(name, new LucideIcon("user-round", 16, RmsColors::primary));
        account.setName("profileMenuButton");
        account.setToolTipText("Account options");
        account.getAccessibleContext().setAccessibleName("Account options for " + name);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem identity = new JMenuItem(name);
        identity.setEnabled(false);
        menu.add(identity);
        JMenuItem role = new JMenuItem(user.role().label());
        role.setEnabled(false);
        menu.add(role);
        menu.addSeparator();
        JMenuItem settings = new JMenuItem("Settings",
                new LucideIcon("settings", 16, () -> RmsColors.SECONDARY_TEXT));
        settings.addActionListener(event -> show(Module.SETTINGS));
        menu.add(settings);
        profileThemeItem = new JMenuItem();
        profileThemeItem.addActionListener(event -> ThemeManager.toggle());
        menu.add(profileThemeItem);
        JMenuItem signOut = new JMenuItem("Sign out",
                new LucideIcon("log-out", 16, () -> RmsColors.SECONDARY_TEXT));
        signOut.addActionListener(event -> onSignOut.run());
        menu.add(signOut);
        account.addActionListener(event -> menu.show(account, 0, account.getHeight()));
        updateThemeControls();
        return account;
    }

    private void updateThemeControls() {
        breadcrumb.setForeground(RmsColors.SECONDARY_TEXT);
        if (sidebarSubtitle != null) sidebarSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        if (sidebarProfileName != null) sidebarProfileName.setForeground(RmsColors.text());
        if (sidebarProfileRole != null) sidebarProfileRole.setForeground(RmsColors.SECONDARY_TEXT);
        if (navList != null) {
            for (java.awt.Component component : navList.getComponents()) {
                if (component instanceof JLabel section) section.setForeground(RmsColors.SECONDARY_TEXT);
            }
        }
        if (moduleSearchSurface != null) {
            moduleSearchSurface.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(RmsColors.border()),
                    BorderFactory.createEmptyBorder(0, 10, 0, 10)));
        }
        if (sidebarProfileSurface != null) {
            sidebarProfileSurface.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(RmsColors.border()),
                    BorderFactory.createEmptyBorder(9, 8, 9, 8)));
        }
        if (themeToggle != null) {
            themeToggle.setIcon(new LucideIcon(ThemeManager.isDark() ? "sun" : "moon", 17,
                    RmsColors::text));
            themeToggle.setToolTipText(ThemeManager.isDark() ? "Switch to light theme" : "Switch to dark theme");
        }
        if (profileThemeItem != null) {
            boolean dark = ThemeManager.isDark();
            profileThemeItem.setText(dark ? "Switch to light theme" : "Switch to dark theme");
            profileThemeItem.setIcon(new LucideIcon(dark ? "sun" : "moon", 16,
                    () -> RmsColors.SECONDARY_TEXT));
        }
    }

    private static String moduleIcon(Module module) {
        return switch (module) {
            case DASHBOARD -> "layout-dashboard";
            case MESSAGES -> "message-square";
            case DUCKY -> "sparkles";
            case RECEIPTS -> "receipt";
            case SUPPLIERS -> "truck";
            case PROJECTS -> "folder-kanban";
            case LIVE_MAP -> "map-pin";
            case CONSTRUCTION -> "hard-hat";
            case REPORTS -> "chart-column";
            case USERS -> "users";
            case ACTIVITY_LOG -> "activity";
            case COMPANIES -> "building-2";
            case HISTORY -> "history";
            case SETTINGS -> "settings";
        };
    }

}
