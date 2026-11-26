package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.UserAvatar;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.border.AbstractBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Company group chat and private one-to-one chats with a theme-aware glass interface. */
public final class MessagesPanel extends JPanel implements RefreshablePanel {
    private static final int POLL_MS = 5000;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");

    private record Loaded(List<User> contacts, List<ChatMessage> messages, List<ChatMessage> companyMessages) { }

    private final AppContext ctx;
    private final Consumer<Boolean> themeListener = dark -> applyTheme();
    private final Timer pollTimer = new Timer(POLL_MS, e -> refreshData());
    private final DefaultListModel<User> contactModel = new DefaultListModel<>();
    private final JList<User> contactList = new JList<>(contactModel);
    private final JTextField search = new JTextField();
    private final JButton companyChatButton = new RoundedMessageButton("Company chat");
    private final JPanel messageList = new JPanel();
    private final JScrollPane messageScroll = new StyledScrollPane(messageList);
    private final JTextArea composer = new JTextArea(3, 24);
    private final JButton sendButton = new JButton("Send");
    private final JCheckBox urgentMessage = new JCheckBox("Urgent");
    private final RoundedSurface composerSurface = new RoundedSurface();
    private final JLabel contactCount = new JLabel(" ");
    private final JLabel conversationTitle = new JLabel("Your messages");
    private final JLabel conversationSubtitle = new JLabel("Select someone to start a conversation");
    private final JLabel conversationType = new JLabel("PRIVATE CHAT");
    private final JLabel emptyState = new JLabel("<html><div style='text-align:center'>"
            + "<b>Your messages, in one place</b><br>Choose a teammate to start a private chat.</div></html>",
            SwingConstants.CENTER);
    private final JLabel status = new JLabel("Messages refresh automatically.");
    private List<User> contacts = List.of();
    private List<ChatMessage> messages = List.of();
    private List<ChatMessage> companyMessages = List.of();
    private User selectedContact;
    private boolean companyChatSelected;
    private boolean sending;
    private boolean rebuildingContacts;

    public MessagesPanel(AppContext ctx) {
        super(new BorderLayout(14, 14));
        this.ctx = ctx;
        User current = ctx.currentUser.get();
        companyChatSelected = current != null && current.companyId() != null;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        pollTimer.setCoalesce(true);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildChatLayout(), BorderLayout.CENTER);

        contactList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        contactList.setCellRenderer(new ContactRenderer());
        contactList.setOpaque(false);
        companyChatButton.setVisible(current != null && current.companyId() != null);
        companyChatButton.addActionListener(e -> selectCompanyChat());
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !rebuildingContacts) {
                User chosen = contactList.getSelectedValue();
                if (chosen != null) {
                    companyChatSelected = false;
                    urgentMessage.setSelected(false);
                    selectedContact = chosen;
                    showConversation();
                }
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterContacts(); }
            @Override public void removeUpdate(DocumentEvent e) { filterContacts(); }
            @Override public void changedUpdate(DocumentEvent e) { filterContacts(); }
        });
        configureComposer();
        applyTheme();
        refreshData();
    }

    @Override public void addNotify() {
        super.addNotify();
        ThemeManager.addThemeListener(themeListener);
        pollTimer.start();
    }

    @Override public void removeNotify() {
        pollTimer.stop();
        ThemeManager.removeThemeListener(themeListener);
        super.removeNotify();
    }

    @Override public void refreshData() {
        User me = ctx.currentUser.get();
        if (me == null) {
            status.setText("Sign in to view your messages.");
            return;
        }
        BackgroundTask.run(() -> {
            List<User> available = ctx.userRepository == null
                    ? List.of() : ctx.userRepository.findMessagingContacts();
            List<ChatMessage> recent = ctx.messages.findFor(me.id());
            List<ChatMessage> group = me.companyId() == null
                    ? List.of() : ctx.messages.findCompanyChat(me.companyId());
            return new Loaded(available, recent, group);
        }, this::applyLoaded, error -> status.setText(UiErrors.friendly(error)));
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Custom Messages");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        JLabel subtitle = new JLabel("Company updates together, with private chats when you need them.");
        subtitle.setFont(subtitle.getFont().deriveFont(13f));
        subtitle.setForeground(RmsColors.SECONDARY_TEXT);
        status.setFont(status.getFont().deriveFont(11f));
        status.setForeground(RmsColors.SECONDARY_TEXT);
        labels.add(title);
        labels.add(Box.createVerticalStrut(4));
        labels.add(subtitle);
        labels.add(Box.createVerticalStrut(4));
        labels.add(status);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        if (ctx.currentUser.get() != null && ctx.currentUser.get().role() == Role.CREATOR) {
            JButton broadcast = new JButton("Broadcast…");
            broadcast.addActionListener(event -> new BroadcastDialog(
                    SwingUtilities.getWindowAncestor(this), ctx).setVisible(true));
            actions.add(broadcast);
        }
        JButton refresh = new JButton("Refresh");
        refresh.setMargin(new Insets(6, 12, 6, 12));
        refresh.setPreferredSize(new Dimension(refresh.getPreferredSize().width, 36));
        refresh.addActionListener(e -> refreshData());
        actions.add(refresh);
        header.add(labels, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JComponent buildChatLayout() {
        GlassPanel people = new GlassPanel(new BorderLayout(0, 12));
        people.setBorder(BorderFactory.createEmptyBorder(17, 15, 14, 15));
        people.setPreferredSize(new Dimension(280, 0));

        JPanel peopleHeader = new JPanel(new BorderLayout());
        peopleHeader.setOpaque(false);
        JLabel teamHeading = new JLabel("TEAM");
        teamHeading.setFont(teamHeading.getFont().deriveFont(Font.BOLD, 11f));
        contactCount.setFont(contactCount.getFont().deriveFont(11f));
        contactCount.setHorizontalAlignment(SwingConstants.RIGHT);
        peopleHeader.add(teamHeading, BorderLayout.WEST);
        peopleHeader.add(contactCount, BorderLayout.EAST);
        search.setToolTipText("Find a teammate");
        search.setOpaque(false);
        search.setBorder(BorderFactory.createCompoundBorder(
                new RoundedOutlineBorder(12),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        search.setPreferredSize(new Dimension(0, 38));
        companyChatButton.setHorizontalAlignment(SwingConstants.LEFT);
        companyChatButton.setMargin(new Insets(9, 10, 9, 10));
        companyChatButton.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));

        JPanel peopleTop = new JPanel(new BorderLayout(0, 11));
        peopleTop.setOpaque(false);
        peopleTop.add(peopleHeader, BorderLayout.NORTH);
        peopleTop.add(search, BorderLayout.CENTER);
        if (companyChatButton.isVisible()) peopleTop.add(companyChatButton, BorderLayout.SOUTH);
        people.add(peopleTop, BorderLayout.NORTH);
        JScrollPane contactsScroll = new StyledScrollPane(contactList);
        contactsScroll.setBorder(null);
        contactsScroll.setOpaque(false);
        contactsScroll.getViewport().setOpaque(false);
        contactsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        contactsScroll.getVerticalScrollBar().setUnitIncrement(36);
        people.add(contactsScroll, BorderLayout.CENTER);

        GlassPanel conversation = new GlassPanel(new BorderLayout(0, 0));
        conversation.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JPanel chatHeader = new JPanel(new BorderLayout(10, 0));
        chatHeader.setOpaque(false);
        chatHeader.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        JPanel conversationLabels = new JPanel();
        conversationLabels.setOpaque(false);
        conversationLabels.setLayout(new BoxLayout(conversationLabels, BoxLayout.Y_AXIS));
        conversationTitle.setFont(conversationTitle.getFont().deriveFont(Font.BOLD, 16f));
        conversationSubtitle.setFont(conversationSubtitle.getFont().deriveFont(12f));
        conversationSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        conversationLabels.add(conversationTitle);
        conversationLabels.add(Box.createVerticalStrut(4));
        conversationLabels.add(conversationSubtitle);
        conversationType.setFont(conversationType.getFont().deriveFont(Font.BOLD, 9f));
        conversationType.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border(), 1, true),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        chatHeader.add(conversationLabels, BorderLayout.WEST);
        chatHeader.add(conversationType, BorderLayout.EAST);

        messageList.setOpaque(false);
        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        messageList.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        messageScroll.setBorder(null);
        messageScroll.setOpaque(false);
        messageScroll.getViewport().setOpaque(false);
        messageScroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        messageScroll.getVerticalScrollBar().setUnitIncrement(40);
        messageScroll.getVerticalScrollBar().setBlockIncrement(200);

        JPanel composerBox = new JPanel(new BorderLayout(10, 0));
        composerBox.setOpaque(false);
        composerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, RmsColors.border()),
                BorderFactory.createEmptyBorder(13, 16, 13, 16)));
        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setOpaque(false);
        composer.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        composer.setFont(composer.getFont().deriveFont(13f));
        composer.setToolTipText("Write a message. Press Enter to send; Shift+Enter for a new line.");
        JScrollPane composerScroll = new StyledScrollPane(composer);
        composerScroll.setBorder(null);
        composerScroll.setOpaque(false);
        composerScroll.getViewport().setOpaque(false);
        composerScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        composerScroll.getVerticalScrollBar().setUnitIncrement(32);
        composerSurface.setLayout(new BorderLayout());
        composerSurface.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 10));
        composerSurface.setPreferredSize(new Dimension(0, 66));
        composerSurface.add(composerScroll, BorderLayout.CENTER);
        composerBox.add(composerSurface, BorderLayout.CENTER);
        sendButton.setMargin(new Insets(6, 14, 6, 14));
        sendButton.setPreferredSize(new Dimension(sendButton.getPreferredSize().width, 36));
        sendButton.addActionListener(e -> sendMessage());
        urgentMessage.setForeground(new Color(177, 52, 52));
        urgentMessage.setToolTipText("Mark this company chat message as urgent.");
        urgentMessage.setOpaque(false);
        JPanel sendHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        sendHolder.setOpaque(false);
        sendHolder.add(urgentMessage);
        sendHolder.add(sendButton);
        composerBox.add(sendHolder, BorderLayout.EAST);

        JPanel thread = new JPanel(new BorderLayout());
        thread.setOpaque(false);
        thread.add(messageScroll, BorderLayout.CENTER);
        thread.add(composerBox, BorderLayout.SOUTH);
        conversation.add(chatHeader, BorderLayout.NORTH);
        conversation.add(thread, BorderLayout.CENTER);

        JPanel layout = new JPanel(new BorderLayout(13, 0));
        layout.setOpaque(false);
        layout.add(people, BorderLayout.WEST);
        layout.add(conversation, BorderLayout.CENTER);
        return layout;
    }

    private void configureComposer() {
        composer.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        composer.getActionMap().put("send-message", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendMessage(); }
        });
    }

    private void applyLoaded(Loaded loaded) {
        UUID me = ctx.currentUser.get() == null ? null : ctx.currentUser.get().id();
        Map<UUID, ChatMessage> latest = new HashMap<>();
        for (ChatMessage message : loaded.messages()) {
            UUID other = otherParticipant(message, me);
            if (other != null) latest.put(other, message);
        }
        List<User> updatedContacts = loaded.contacts().stream()
                .filter(User::active)
                .filter(u -> me == null || !u.id().equals(me))
                .sorted(Comparator.<User, Instant>comparing(u ->
                                latest.containsKey(u.id()) ? latest.get(u.id()).sentAt() : Instant.MIN)
                        .reversed().thenComparing(User::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (messages.equals(loaded.messages()) && companyMessages.equals(loaded.companyMessages())
                && contacts.equals(updatedContacts)) {
            status.setText("Company chat and private messages · Refreshes every " + (POLL_MS / 1000) + " seconds");
            return;
        }
        boolean privateMessagesChanged = !messages.equals(loaded.messages());
        boolean companyMessagesChanged = !companyMessages.equals(loaded.companyMessages());
        boolean wasAtBottom = isNearBottom();
        contacts = updatedContacts;
        messages = loaded.messages();
        companyMessages = loaded.companyMessages();
        UUID previousId = selectedContact == null ? null : selectedContact.id();
        if (previousId != null) {
            selectedContact = contacts.stream().filter(u -> u.id().equals(previousId)).findFirst().orElse(null);
        }
        User current = ctx.currentUser.get();
        if (selectedContact == null && current != null && current.companyId() != null) companyChatSelected = true;
        rebuildingContacts = true;
        try {
            filterContacts();
            if (selectedContact != null) {
                for (int i = 0; i < contactModel.size(); i++) {
                    if (contactModel.get(i).id().equals(selectedContact.id())) {
                        contactList.setSelectedIndex(i);
                        break;
                    }
                }
            } else {
                contactList.clearSelection();
            }
        } finally {
            rebuildingContacts = false;
        }
        boolean activeMessagesChanged = companyChatSelected ? companyMessagesChanged : privateMessagesChanged;
        showConversation(false, activeMessagesChanged && wasAtBottom);
        contactCount.setText(contacts.size() + (contacts.size() == 1 ? " person" : " people"));
        status.setText("Company chat and private messages · Refreshes every " + (POLL_MS / 1000) + " seconds");
    }

    private void selectCompanyChat() {
        companyChatSelected = true;
        selectedContact = null;
        urgentMessage.setSelected(false);
        rebuildingContacts = true;
        try {
            contactList.clearSelection();
        } finally {
            rebuildingContacts = false;
        }
        showConversation();
    }

    private void filterContacts() {
        String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        UUID chosenId = selectedContact == null ? null : selectedContact.id();
        contactModel.clear();
        for (User contact : contacts) {
            if (contact.fullName().toLowerCase(java.util.Locale.ROOT).contains(query)
                    || contact.companyName().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                contactModel.addElement(contact);
                if (chosenId != null && chosenId.equals(contact.id())) {
                    contactList.setSelectedIndex(contactModel.size() - 1);
                }
            }
        }
        contactList.repaint();
    }

    private void showConversation() {
        showConversation(true, true);
    }

    private void showConversation(boolean forceScroll, boolean followLatest) {
        messageList.removeAll();
        UUID me = ctx.currentUser.get() == null ? null : ctx.currentUser.get().id();
        if (selectedContact == null && companyChatSelected) {
            User current = ctx.currentUser.get();
            conversationTitle.setText(current == null || current.companyName().isBlank()
                    ? "Company chat" : current.companyName() + " chat");
            conversationSubtitle.setText("Shared with everyone in your company");
            conversationType.setText("COMPANY CHAT");
            messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
            List<ChatMessage> thread = companyMessages.stream()
                    .filter(m -> m.recipientId() == null)
                    .toList();
            if (thread.isEmpty()) {
                JLabel start = new JLabel("Start a conversation with your whole company.");
                start.setForeground(RmsColors.SECONDARY_TEXT);
                start.setAlignmentX(Component.CENTER_ALIGNMENT);
                messageList.add(Box.createVerticalGlue());
                messageList.add(start);
                messageList.add(Box.createVerticalGlue());
            } else {
                addThreadMessages(thread, me);
            }
        } else if (selectedContact == null) {
            conversationTitle.setText("Your messages");
            conversationSubtitle.setText("Select someone to start a conversation");
            conversationType.setText("PRIVATE CHAT");
            messageList.setLayout(new GridBagLayout());
            emptyState.setForeground(RmsColors.SECONDARY_TEXT);
            messageList.add(emptyState);
        } else {
            conversationTitle.setText(selectedContact.fullName());
            conversationSubtitle.setText(selectedContact.role().label()
                    + (selectedContact.companyName().isBlank() ? "" : " · " + selectedContact.companyName()));
            conversationType.setText("PRIVATE CHAT");
            messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
            List<ChatMessage> thread = messages.stream()
                    .filter(m -> isBetween(m, me, selectedContact.id()))
                    .toList();
            if (thread.isEmpty()) {
                JLabel start = new JLabel("Say hello to " + selectedContact.fullName() + " to get started.");
                start.setForeground(RmsColors.SECONDARY_TEXT);
                start.setAlignmentX(Component.CENTER_ALIGNMENT);
                messageList.add(Box.createVerticalGlue());
                messageList.add(start);
                messageList.add(Box.createVerticalGlue());
            } else {
                addThreadMessages(thread, me);
            }
        }
        boolean conversationSelected = selectedContact != null || companyChatSelected;
        composer.setEnabled(conversationSelected);
        urgentMessage.setVisible(companyChatSelected);
        sendButton.setEnabled(conversationSelected && !sending);
        messageList.revalidate();
        messageList.repaint();
        if (forceScroll || followLatest) {
            SwingUtilities.invokeLater(() -> {
                if (messageScroll.getVerticalScrollBar().isVisible()) {
                    messageScroll.getVerticalScrollBar().setValue(messageScroll.getVerticalScrollBar().getMaximum());
                }
            });
        }
    }

    private void addThreadMessages(List<ChatMessage> thread, UUID me) {
        LocalDate previousDate = null;
        for (ChatMessage message : thread) {
            if (message.sentAt() != null) {
                LocalDate date = message.sentAt().atZone(ZoneId.systemDefault()).toLocalDate();
                if (!date.equals(previousDate)) {
                    messageList.add(dateSeparator(ChatDateFormatting.separator(message.sentAt())));
                    messageList.add(Box.createVerticalStrut(10));
                    previousDate = date;
                }
            }
            messageList.add(createMessageRow(message, me));
            messageList.add(Box.createVerticalStrut(10));
        }
    }

    private static JComponent dateSeparator(String date) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(date);
        label.setName("chatDateSeparator");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(RmsColors.SECONDARY_TEXT);
        label.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        row.add(label);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        return row;
    }

    private boolean isNearBottom() {
        var bar = messageScroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;
    }

    private JComponent createMessageRow(ChatMessage message, UUID me) {
        boolean mine = me != null && me.equals(message.senderId());
        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        User sender = mine ? ctx.currentUser.get() : null;
        UserAvatar avatar = new UserAvatar(34, 32, false);
        avatar.setProfile(sender == null ? message.senderName() : sender.fullName(),
                sender == null ? message.senderEmail() : avatarEmail(sender));
        JPanel bubble = new JPanel(new BorderLayout(0, 5)) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = mine ? RmsColors.primaryAccent() : RmsColors.surface();
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), mine ? 75 : 170));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g.setColor(new Color(RmsColors.border().getRed(), RmsColors.border().getGreen(),
                        RmsColors.border().getBlue(), 145));
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.dispose();
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(BorderFactory.createEmptyBorder(10, 13, 8, 13));
        if (companyChatSelected && message.companyId() != null) {
            JPanel meta = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            meta.setOpaque(false);
            JLabel author = new JLabel(mine ? "You" : message.senderName());
            author.setFont(author.getFont().deriveFont(Font.BOLD, 10f));
            author.setForeground(RmsColors.SECONDARY_TEXT);
            meta.add(author);
            if (message.urgent()) {
                JLabel urgent = new JLabel("  URGENT");
                urgent.setFont(urgent.getFont().deriveFont(Font.BOLD, 10f));
                urgent.setForeground(new Color(190, 48, 48));
                meta.add(urgent);
            }
            bubble.add(meta, BorderLayout.NORTH);
        }
        JLabel body = new JLabel();
        Font bodyFont = body.getFont().deriveFont(13f);
        body.setFont(bodyFont);
        int bodyWidth = messageBodyWidth(message.body(), body.getFontMetrics(bodyFont), 300);
        String html = "<html><div style='width:" + bodyWidth + "px'>"
                + escapeHtml(message.body()).replace("\n", "<br>")
                + "</div></html>";
        body.setText(html);
        body.setForeground(RmsColors.text());
        JLabel time = new JLabel(message.sentAt() == null ? ""
                : TIME.format(message.sentAt().atZone(ZoneId.systemDefault())));
        time.setHorizontalAlignment(mine ? SwingConstants.RIGHT : SwingConstants.LEFT);
        time.setFont(time.getFont().deriveFont(10f));
        time.setForeground(RmsColors.SECONDARY_TEXT);
        bubble.add(body, BorderLayout.CENTER);
        bubble.add(time, BorderLayout.SOUTH);
        if (!mine) {
            row.add(avatar);
            row.add(Box.createHorizontalStrut(8));
        }
        row.add(bubble);
        if (mine) {
            row.add(Box.createHorizontalStrut(8));
            row.add(avatar);
        }
        return row;
    }

    static int messageBodyWidth(String text, FontMetrics metrics, int maximumWidth) {
        int widestLine = 0;
        if (text != null) {
            for (String line : text.split("\\R", -1)) {
                widestLine = Math.max(widestLine, metrics.stringWidth(line));
            }
        }
        int cap = Math.max(1, maximumWidth);
        return Math.min(cap, Math.max(Math.min(24, cap), widestLine + 8));
    }

    private static String avatarEmail(User user) {
        return user.email().isBlank() && user.role() == Role.CREATOR
                ? "clarkhilario.bread@gmail.com" : user.email();
    }

    private void sendMessage() {
        if (sending || (selectedContact == null && !companyChatSelected)) return;
        String text = composer.getText().trim();
        if (text.isEmpty()) {
            status.setText("Write a message before sending.");
            return;
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            status.setText("Messages can be up to " + MAX_MESSAGE_LENGTH + " characters.");
            return;
        }
        boolean sendToCompany = companyChatSelected;
        UUID recipientId = selectedContact == null ? null : selectedContact.id();
        User current = ctx.currentUser.get();
        UUID companyId = current == null ? null : current.companyId();
        boolean urgent = sendToCompany && urgentMessage.isSelected();
        sending = true;
        sendButton.setEnabled(false);
        status.setText("Sending…");
        BackgroundTask.run(() -> sendToCompany
                ? ctx.messages.sendCompanyChat(companyId, text, urgent)
                : ctx.messages.send(recipientId, text), sent -> {
            sending = false;
            sendButton.setEnabled(selectedContact != null || companyChatSelected);
            boolean stillSameConversation = sendToCompany ? companyChatSelected
                    : selectedContact != null && selectedContact.id().equals(recipientId);
            if (stillSameConversation && composer.getText().trim().equals(text)) {
                composer.setText("");
                urgentMessage.setSelected(false);
            }
            refreshData();
        }, error -> {
            sending = false;
            sendButton.setEnabled(selectedContact != null || companyChatSelected);
            status.setText(UiErrors.friendly(error));
        });
    }

    private static UUID otherParticipant(ChatMessage message, UUID userId) {
        if (userId == null) return null;
        if (userId.equals(message.senderId())) return message.recipientId();
        if (userId.equals(message.recipientId())) return message.senderId();
        return null;
    }

    private static boolean isBetween(ChatMessage message, UUID first, UUID second) {
        return first != null && second != null
                && ((first.equals(message.senderId()) && second.equals(message.recipientId()))
                || (second.equals(message.senderId()) && first.equals(message.recipientId())));
    }

    private void applyTheme() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyTheme);
            return;
        }
        setForeground(RmsColors.text());
        search.setForeground(RmsColors.text());
        search.setOpaque(false);
        search.setBorder(BorderFactory.createCompoundBorder(
                new RoundedOutlineBorder(12),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        companyChatButton.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        composer.setForeground(RmsColors.text());
        composerSurface.repaint();
        conversationTitle.setForeground(RmsColors.text());
        conversationSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        emptyState.setForeground(RmsColors.SECONDARY_TEXT);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        contactList.revalidate();
        contactList.repaint();
        showConversation(false, isNearBottom());
        messageScroll.getViewport().revalidate();
        messageScroll.getViewport().repaint();
        messageScroll.revalidate();
        messageScroll.repaint();
        repaint();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private final class ContactRenderer extends JPanel implements ListCellRenderer<User> {
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();
        private final UserAvatar avatar = new UserAvatar(38, 36, false);

        private ContactRenderer() {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 8));
            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
            detail.setFont(detail.getFont().deriveFont(10f));
            labels.add(name);
            labels.add(Box.createVerticalStrut(4));
            labels.add(detail);
            add(avatar, BorderLayout.WEST);
            add(labels, BorderLayout.CENTER);
        }

        @Override public Component getListCellRendererComponent(JList<? extends User> list, User user, int index,
                                                                boolean selected, boolean focus) {
            name.setText(user.fullName());
            name.setForeground(RmsColors.text());
            detail.setText(user.role().label());
            detail.setForeground(RmsColors.SECONDARY_TEXT);
            avatar.setProfile(user.fullName(), avatarEmail(user));
            setBackground(selected ? RmsColors.primaryLight() : new Color(0, 0, 0, 0));
            return this;
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground().getAlpha() == 0 ? RmsColors.surface() : RmsColors.primaryLight());
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 14, 14);
            g.setColor(RmsColors.border());
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            g.dispose();
        }
    }

    private static final class GlassPanel extends JPanel {
        private GlassPanel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(RmsColors.surface());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g.setColor(new Color(RmsColors.border().getRed(), RmsColors.border().getGreen(),
                    RmsColors.border().getBlue(), 190));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g.dispose();
        }

        @Override protected void paintChildren(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.clip(new java.awt.geom.RoundRectangle2D.Float(
                    1, 1, getWidth() - 2, getHeight() - 2, 20, 20));
            super.paintChildren(g);
            g.dispose();
        }

    }

    private static final class RoundedSurface extends JPanel {
        private RoundedSurface() {
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(RmsColors.surface());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setColor(RmsColors.border());
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.dispose();
        }
    }

    private static final class RoundedMessageButton extends JButton {
        private RoundedMessageButton(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, 14, 14);
            Color fill = getBackground();
            if (getModel().isPressed()) {
                Color pressed = UIManager.getColor("Button.pressedBackground");
                if (pressed != null) fill = pressed;
            } else if (getModel().isRollover()) {
                Color hover = UIManager.getColor("Button.hoverBackground");
                if (hover != null) fill = hover;
            }
            g.setColor(fill);
            g.fill(shape);
            g.setColor(RmsColors.border());
            g.draw(shape);
            g.clip(shape);
            super.paintComponent(g);
            g.dispose();
        }
    }

    private static final class RoundedOutlineBorder extends AbstractBorder {
        private final int radius;

        private RoundedOutlineBorder(int radius) {
            this.radius = radius;
        }

        @Override public Insets getBorderInsets(Component component) {
            return new Insets(1, 1, 1, 1);
        }

        @Override public void paintBorder(Component component, Graphics graphics, int x, int y,
                                          int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(RmsColors.border());
            g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f,
                    width - 1f, height - 1f, radius, radius));
            g.dispose();
        }
    }

}
