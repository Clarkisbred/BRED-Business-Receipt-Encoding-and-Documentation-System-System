
package com.bred.rms.ui;

import com.bred.rms.ai.DuckyPreferences;
import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.ai.GroqDuckyService;
import com.bred.rms.ui.components.AiLogoIcon;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.ListCellRenderer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;

import java.text.AttributedString;
import java.text.BreakIterator;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Groq-backed Ducky chat with local OCR and explicitly approved, read-only BRED tools. */
public final class DuckyPanel extends JPanel {

    enum Sender {
        ASSISTANT, USER
    }

    record ChatEntry(
            Sender sender,
            String text,
            Instant sentAt,
            List<String> fileNames
    ) { }

    private static final String GREETING =
            "Hi, I’m Ducky. Ask me a question or attach a document for local OCR. "
            + "Groq is a cloud AI service; BRED records and OCR text are shared only after a separate confirmation.";

    private static final class ChatSession {

        private final List<ChatEntry> entries = new ArrayList<>();
        private final List<String> transcript = new ArrayList<>();
        private final List<Path> pendingFiles = new ArrayList<>();

        private String title = "New chat";
        private boolean privateHistory;

        private ChatSession() {
            entries.add(new ChatEntry(
                    Sender.ASSISTANT,
                    GREETING,
                    Instant.now(),
                    List.of()
            ));
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final GroqDuckyService assistant;
    private final GroqCredentials credentials;

    private final List<ChatSession> sessions = new ArrayList<>();

    private final DefaultListModel<ChatSession> sessionModel =
            new DefaultListModel<>();

    private final JList<ChatSession> sessionList =
            new JList<>(sessionModel);

    private final Consumer<Boolean> themeListener =
            dark -> applyTheme();

    private final JPanel messageList = new MessageList();

    private final JPanel messageFrame =
            new RoundedSurface(RmsColors::surface, RmsColors::border, 16);

    private final JScrollPane messageScroll =
            new StyledScrollPane(messageList);

    private final JTextArea composer = new JTextArea(1, 32);

    private final JButton sendButton =
            new RoundedButton("Send", true);

    private final JButton attachButton =
            new RoundedButton("+", false);

    private final JButton settingsButton =
            new RoundedButton("Settings", false);

    private final JButton clearButton =
            new RoundedButton("New chat", false);

    private final JLabel selectedFiles = new JLabel(" ");

    private final JLabel status =
            new JLabel("Groq cloud AI is opt-in. Configure Ducky in Settings.");

    private ChatSession activeSession;
    private ChatSession renderedSession;

    private int renderedEntryCount;
    private int renderedViewportWidth = -1;

    private boolean sending;
    private boolean selectingSession;

    public DuckyPanel(
            GroqDuckyService assistant,
            GroqCredentials credentials
    ) {
        super(new BorderLayout(0, 12));

        this.assistant = assistant;
        this.credentials = credentials;

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        messageList.setOpaque(false);
        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        messageList.setBorder(
                BorderFactory.createEmptyBorder(10, 12, 12, 12)
        );

        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageScroll.setName("duckyMessageScroll");
        messageScroll.setOpaque(false);

        messageScroll.getViewport().setOpaque(true);
        messageScroll.getViewport().setBackground(RmsColors.surface());

        messageScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );

        messageScroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        );

        messageScroll.getViewport().setScrollMode(
                javax.swing.JViewport.SIMPLE_SCROLL_MODE
        );

        messageFrame.setLayout(new BorderLayout());
        messageFrame.setBorder(
                BorderFactory.createEmptyBorder(1, 1, 1, 1)
        );
        messageFrame.add(messageScroll, BorderLayout.CENTER);

        sessionList.setName("duckySessionList");
        sessionList.setCellRenderer(new SessionRenderer());
        sessionList.setSelectionMode(
                javax.swing.ListSelectionModel.SINGLE_SELECTION
        );
        sessionList.setOpaque(false);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildWorkspace(), BorderLayout.CENTER);

        configureComposer();

        composer.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateComposerSize();
            }
        });

        messageScroll.getViewport().addComponentListener(
                new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent event) {
                        int width = messageScroll.getViewport()
                                .getExtentSize().width;

                        if (width > 0 && width != renderedViewportWidth) {
                            renderMessages(false, true);
                        }
                    }
                }
        );

        sessionList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()
                    && !selectingSession
                    && sessionList.getSelectedValue() != null) {

                activeSession = sessionList.getSelectedValue();

                updateSelectedFiles();
                renderMessages(true);
            }
        });

        if (assistant == null) {
            setBusy(true);
            status.setText("Ducky is unavailable.");
        }

        startNewChat();
        applyTheme();
    }

    public static DuckyPanel unavailable(String reason) {
        DuckyPanel panel = new DuckyPanel(null, null);

        panel.status.setText(
                "Ducky is unavailable: " + safeErrorMessage(reason)
        );

        return panel;
    }

    int sessionCount() {
        return sessions.size();
    }

    int activeConversationMessageCount() {
        return activeSession == null
                ? 0
                : activeSession.entries.size();
    }

    int activeSessionIndex() {
        return sessions.indexOf(activeSession);
    }

    boolean activeRecordAccessEnabled() {
        return DuckyPreferences.permission().permitsTools();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ThemeManager.addThemeListener(themeListener);
    }

    @Override
    public void removeNotify() {
        ThemeManager.removeThemeListener(themeListener);
        super.removeNotify();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JPanel identity = new JPanel(
                new FlowLayout(FlowLayout.LEFT, 10, 0)
        );
        identity.setOpaque(false);

        JLabel avatar = new JLabel(new AiLogoIcon(48));
        avatar.setName("AI.logo");
        identity.add(avatar);

        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(
                new FlowLayout(FlowLayout.LEFT, 7, 0)
        );
        titleRow.setOpaque(false);

        JLabel name = new JLabel("Ducky");
        name.setForeground(RmsColors.text());
        name.setName("duckyAssistantName");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 22f));

        JLabel aiTag = new RoundedTag(
                "AI",
                () -> RmsColors.primary(),
                () -> RmsColors.primary()
        );

        aiTag.setName("duckyAiTag");
        aiTag.setForeground(Color.WHITE);
        aiTag.setFont(aiTag.getFont().deriveFont(Font.BOLD, 10f));
        aiTag.setBorder(
                BorderFactory.createEmptyBorder(3, 7, 3, 7)
        );

        titleRow.add(name);
        titleRow.add(aiTag);

        JLabel subtitle =
                new JLabel("Your BRED assistant · Groq cloud AI");

        subtitle.setName("duckySubtitle");
        subtitle.setForeground(RmsColors.SECONDARY_TEXT);

        names.add(titleRow);
        names.add(Box.createVerticalStrut(3));
        names.add(subtitle);

        identity.add(names);
        header.add(identity, BorderLayout.WEST);

        clearButton.addActionListener(event -> startNewChat());
        clearButton.setName("duckyNewChat");

        header.add(clearButton, BorderLayout.EAST);

        return header;
    }

    private JPanel buildWorkspace() {
        JPanel workspace = new JPanel(
                new BorderLayout(12, 0)
        );
        workspace.setOpaque(false);

        JPanel sessions = new RoundedSurface(
                RmsColors::surface,
                RmsColors::border,
                16
        );

        sessions.setName("duckySessionsSurface");
        sessions.setLayout(new BorderLayout(0, 8));
        sessions.setBorder(
                BorderFactory.createEmptyBorder(12, 10, 10, 10)
        );
        sessions.setPreferredSize(new Dimension(200, 0));

        JLabel heading = new JLabel("RECENT CHATS");
        heading.setFont(
                heading.getFont().deriveFont(Font.BOLD, 10f)
        );
        heading.setName("duckyRecentChats");
        heading.setForeground(RmsColors.SECONDARY_TEXT);
        heading.setBorder(
                BorderFactory.createEmptyBorder(2, 5, 5, 5)
        );

        JScrollPane listScroll = new StyledScrollPane(sessionList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.getViewport().setScrollMode(
                javax.swing.JViewport.SIMPLE_SCROLL_MODE
        );

        sessions.add(heading, BorderLayout.NORTH);
        sessions.add(listScroll, BorderLayout.CENTER);

        JPanel conversation = new JPanel(
                new BorderLayout(0, 10)
        );
        conversation.setOpaque(false);

        conversation.add(messageFrame, BorderLayout.CENTER);
        conversation.add(buildComposer(), BorderLayout.SOUTH);

        workspace.add(sessions, BorderLayout.WEST);
        workspace.add(conversation, BorderLayout.CENTER);

        return workspace;
    }

    private JPanel buildComposer() {
        JPanel south = new JPanel(new BorderLayout(0, 5));
        south.setOpaque(false);

        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setOpaque(false);
        composer.setForeground(RmsColors.text());
        composer.setCaretColor(RmsColors.primary());
        composer.setBorder(
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        );
        composer.setName("duckyComposer");

        selectedFiles.setForeground(RmsColors.SECONDARY_TEXT);
        selectedFiles.setFont(
                selectedFiles.getFont().deriveFont(11f)
        );

        attachButton.addActionListener(event -> chooseFiles());
        attachButton.setName("duckyAttach");
        attachButton.setToolTipText("Attach local receipt documents");
        attachButton.setMargin(new Insets(4, 9, 4, 9));

        settingsButton.addActionListener(event -> showSettings());
        settingsButton.setName("duckySettings");

        sendButton.addActionListener(event -> send());
        sendButton.setName("duckySend");

        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setFont(status.getFont().deriveFont(11f));

        JPanel inputArea = new JPanel(
                new BorderLayout(8, 0)
        );
        inputArea.setOpaque(false);
        inputArea.add(attachButton, BorderLayout.WEST);

        JScrollPane composerScroll =
                new StyledScrollPane(composer);

        composerScroll.setBorder(BorderFactory.createEmptyBorder());
        composerScroll.setOpaque(false);
        composerScroll.setName("duckyComposerScroll");
        composerScroll.getViewport().setOpaque(false);

        composerScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        composerScroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        );

        inputArea.add(composerScroll, BorderLayout.CENTER);

        JPanel inputSurface = new RoundedSurface(
                RmsColors::surface,
                RmsColors::border,
                14
        );

        inputSurface.setLayout(new BorderLayout());
        inputSurface.setBorder(
                BorderFactory.createEmptyBorder(3, 5, 3, 5)
        );
        inputSurface.add(inputArea, BorderLayout.CENTER);

        JPanel topRow = new JPanel(
                new BorderLayout(8, 0)
        );
        topRow.setOpaque(false);
        topRow.add(status, BorderLayout.CENTER);

        JPanel controls = new JPanel(
                new FlowLayout(FlowLayout.RIGHT, 0, 0)
        );
        controls.setOpaque(false);
        controls.add(settingsButton);

        topRow.add(controls, BorderLayout.EAST);

        JPanel inputRow = new JPanel(
                new BorderLayout(8, 0)
        );
        inputRow.setOpaque(false);
        inputRow.add(inputSurface, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        JPanel content = new JPanel(
                new BorderLayout(0, 4)
        );
        content.setOpaque(false);
        content.add(topRow, BorderLayout.NORTH);
        content.add(selectedFiles, BorderLayout.CENTER);
        content.add(inputRow, BorderLayout.SOUTH);

        south.add(content, BorderLayout.CENTER);

        return south;
    }

    private void configureComposer() {
        composer.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                "send-message"
        );

        composer.getActionMap().put(
                "send-message",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(
                            java.awt.event.ActionEvent event
                    ) {
                        send();
                    }
                }
        );

        composer.getInputMap().put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER,
                        InputEvent.SHIFT_DOWN_MASK
                ),
                "insert-break"
        );

        composer.getActionMap().put(
                "insert-break",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(
                            java.awt.event.ActionEvent event
                    ) {
                        composer.append("\n");
                    }
                }
        );

        composer.getDocument().addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent event) {
                        updateComposerSize();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent event) {
                        updateComposerSize();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent event) {
                        updateComposerSize();
                    }
                }
        );

        updateComposerSize();
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();

        chooser.setDialogTitle("Attach local receipt documents");
        chooser.setMultiSelectionEnabled(true);

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        "Receipt documents (images, PDF, Excel, CSV)",
                        "png", "jpg", "jpeg", "bmp", "tif",
                        "tiff", "pdf", "xlsx", "xls", "csv"
                )
        );

        if (chooser.showOpenDialog(this)
                != JFileChooser.APPROVE_OPTION) {
            return;
        }

        if (activeSession == null) {
            return;
        }

        for (var file : chooser.getSelectedFiles()) {
            Path path = file.toPath()
                    .toAbsolutePath()
                    .normalize();

            if (!activeSession.pendingFiles.contains(path)) {
                activeSession.pendingFiles.add(path);
            }
        }

        updateSelectedFiles();
        updateSendButtonState();
    }

    private void send() {
        if (sending) {
            return;
        }

        if (assistant == null) {
            status.setText(
                    "Ducky is unavailable because its local configuration could not be loaded."
            );
            return;
        }

        String question = composer.getText().trim();
        ChatSession requestedSession = activeSession;

        if (requestedSession == null) {
            return;
        }

        if (question.isEmpty()
                && requestedSession.pendingFiles.isEmpty()) {
            status.setText(
                    "Write a message or attach a document first."
            );
            return;
        }

        if (question.length() > 4000) {
            status.setText(
                    "Messages can be up to 4,000 characters."
            );
            return;
        }

        if (question.isEmpty()) {
            question =
                    "Please read the attached receipt document and summarize its contents.";
        }

        List<Path> attachments =
                List.copyOf(requestedSession.pendingFiles);

        List<String> names = attachments.stream()
                .map(path -> path.getFileName().toString())
                .toList();

        List<String> priorTurns = requestedSession.privateHistory
                ? List.of()
                : List.copyOf(requestedSession.transcript);

        String submittedQuestion = question;

        requestedSession.entries.add(
                new ChatEntry(
                        Sender.USER,
                        submittedQuestion,
                        Instant.now(),
                        names
                )
        );

        requestedSession.transcript.add(
                "User: " + submittedQuestion
        );

        if ("New chat".equals(requestedSession.title)) {
            requestedSession.title = titleFrom(submittedQuestion);
            sessionList.repaint();
        }

        composer.setText("");
        requestedSession.pendingFiles.clear();

        updateSelectedFiles();

        sending = true;
        setBusy(true);

        status.setText(
                attachments.isEmpty()
                        ? "Ducky is thinking…"
                        : "Reading attachments locally with OCR…"
        );

        renderMessages(true);

        BackgroundTask.run(
                () -> assistant.respond(
                        submittedQuestion,
                        priorTurns,
                        attachments,
                        this::confirmDisclosure,
                        this::updateProgress
                ),
                response -> {
                    requestedSession.privateHistory |=
                            response.privateDataDisclosed();

                    for (String localResult : response.localResults()) {
                        requestedSession.entries.add(
                                new ChatEntry(
                                        Sender.ASSISTANT,
                                        localResult,
                                        Instant.now(),
                                        List.of()
                                )
                        );
                    }

                    requestedSession.transcript.add(
                            "Ducky: " + response.answer()
                    );

                    requestedSession.entries.add(
                            new ChatEntry(
                                    Sender.ASSISTANT,
                                    response.answer(),
                                    Instant.now(),
                                    List.of()
                            )
                    );

                    sending = false;
                    setBusy(false);

                    status.setText(
                            "Ready · Groq cloud AI · BRED data is shared only after confirmation."
                    );

                    if (activeSession == requestedSession) {
                        renderMessages(true);
                    }
                },
                error -> {
                    sending = false;
                    setBusy(false);

                    String message =
                            "I could not complete that request: "
                                    + safeError(error);

                    requestedSession.transcript.add(
                            "Ducky: " + message
                    );

                    requestedSession.entries.add(
                            new ChatEntry(
                                    Sender.ASSISTANT,
                                    message,
                                    Instant.now(),
                                    List.of()
                            )
                    );

                    status.setText(message);

                    if (activeSession == requestedSession) {
                        renderMessages(true);
                    }
                }
        );
    }

    private void startNewChat() {
        if (sending) {
            return;
        }

        ChatSession session = new ChatSession();

        sessions.add(0, session);
        sessionModel.add(0, session);

        activeSession = session;

        selectingSession = true;
        sessionList.setSelectedIndex(0);
        selectingSession = false;

        composer.setText("");

        updateSelectedFiles();

        status.setText("New chat · Groq cloud AI is opt-in.");

        renderMessages(true);
        updateSendButtonState();
    }

    private void setBusy(boolean busy) {
        updateSendButtonState();

        attachButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
        settingsButton.setEnabled(!busy);
        composer.setEnabled(!busy);
        sessionList.setEnabled(!busy);
    }

    private void updateSelectedFiles() {
        List<Path> pendingFiles = activeSession == null
                ? List.of()
                : activeSession.pendingFiles;

        if (pendingFiles.isEmpty()) {
            selectedFiles.setText(" ");
        } else {
            selectedFiles.setText(
                    "Local files: "
                            + pendingFiles.stream()
                                    .map(path -> path.getFileName().toString())
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("")
            );
        }

        updateSendButtonState();
    }

    private void renderMessages(boolean scrollToLatest) {
        renderMessages(scrollToLatest, false);
    }

    private void renderMessages(
            boolean scrollToLatest,
            boolean forceRebuild
    ) {
        if (activeSession == null) {
            return;
        }

        int viewportWidth =
                messageScroll.getViewport().getExtentSize().width;

        if (viewportWidth <= 0) {
            viewportWidth = Math.max(360, messageScroll.getWidth());
        }

        boolean rebuild = forceRebuild
                || renderedSession != activeSession
                || renderedEntryCount > activeSession.entries.size()
                || renderedViewportWidth != viewportWidth;

        if (rebuild) {
            messageList.removeAll();

            renderedEntryCount = 0;
            renderedSession = activeSession;
            renderedViewportWidth = viewportWidth;
        }

        LocalDate previousDate = renderedEntryCount == 0
                ? null
                : activeSession.entries
                        .get(renderedEntryCount - 1)
                        .sentAt()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();

        for (int index = renderedEntryCount;
             index < activeSession.entries.size();
             index++) {

            ChatEntry entry = activeSession.entries.get(index);

            boolean emptyMessage =
                    entry.text() == null || entry.text().isBlank();

            boolean hasAttachments =
                    entry.fileNames() != null
                            && !entry.fileNames().isEmpty();

            if (emptyMessage && !hasAttachments) {
                continue;
            }

            LocalDate date = entry.sentAt()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();

            if (!date.equals(previousDate)) {
                messageList.add(
                        dateSeparator(
                                ChatDateFormatting.separator(date)
                        )
                );

                messageList.add(Box.createVerticalStrut(10));

                previousDate = date;
            }

            JPanel message = createMessage(entry, viewportWidth);

            if (message != null) {
                messageList.add(message);
                messageList.add(Box.createVerticalStrut(10));
            }
        }

        renderedEntryCount = activeSession.entries.size();

        messageList.revalidate();
        messageList.repaint();

        messageScroll.getViewport().revalidate();
        messageScroll.revalidate();
        messageScroll.repaint();

        if (scrollToLatest) {
            SwingUtilities.invokeLater(() ->
                    messageScroll.getVerticalScrollBar().setValue(
                            messageScroll.getVerticalScrollBar().getMaximum()
                    )
            );
        }
    }

    static int messageBubbleWidth(int viewportWidth) {
        return messageBubbleWidth(viewportWidth, Sender.USER);
    }

    static int messageBubbleWidth(
            int viewportWidth,
            Sender sender
    ) {
        int horizontalInsets = 24;

        int avatarWidth =
                sender == Sender.ASSISTANT ? 42 : 0;

        int available = Math.max(
                1,
                viewportWidth - horizontalInsets - avatarWidth
        );

        return Math.max(
                1,
                Math.min(690, (int) (available * 0.78))
        );
    }

    private void updateComposerSize() {
        int rows = Math.max(
                1,
                Math.min(
                        4,
                        wrappedLineCount(
                                composer.getText(),
                                composer.getFont(),
                                Math.max(80, composer.getWidth() - 24)
                        )
                )
        );

        if (composer.getRows() != rows) {
            composer.setRows(rows);
            composer.revalidate();
            revalidate();
        }

        updateSendButtonState();
    }

    private void updateSendButtonState() {
        boolean hasText = !composer.getText().isBlank();

        boolean hasFiles =
                activeSession != null
                        && !activeSession.pendingFiles.isEmpty();

        sendButton.setEnabled(
                !sending
                        && assistant != null
                        && (hasText || hasFiles)
        );
    }

    private void showSettings() {
        if (activeSession == null) {
            return;
        }

        if (credentials == null) {
            status.setText("Ducky settings are unavailable.");
            return;
        }

        DuckySettingsPopup popup = new DuckySettingsPopup(
                credentials,
                () -> status.setText("Ducky settings updated.")
        );

        popup.show(
                settingsButton,
                0,
                settingsButton.getHeight()
        );
    }

    private boolean confirmDisclosure(
            String title,
            String details
    ) {
        AtomicBoolean approved = new AtomicBoolean();

        Runnable prompt = () -> {
            JTextArea content = new JTextArea(details, 10, 48);

            content.setEditable(false);
            content.setLineWrap(true);
            content.setWrapStyleWord(true);
            content.setCaretPosition(0);

            JScrollPane scroll = new JScrollPane(content);
            scroll.setPreferredSize(new Dimension(560, 250));

            approved.set(
                    JOptionPane.showConfirmDialog(
                            this,
                            scroll,
                            title,
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    ) == JOptionPane.YES_OPTION
            );
        };

        if (SwingUtilities.isEventDispatchThread()) {
            prompt.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(prompt);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new IllegalStateException(
                        "The cloud data confirmation could not be displayed.",
                        error.getCause()
                );
            }
        }

        return approved.get();
    }

    private void updateProgress(String message) {
        SwingUtilities.invokeLater(
                () -> status.setText(message)
        );
    }

    private static JPanel dateSeparator(String date) {
        JPanel row = new JPanel(
                new FlowLayout(FlowLayout.CENTER, 0, 0)
        );

        row.setOpaque(false);

        JLabel label = new JLabel(date);
        label.setName("chatDateSeparator");
        label.setFont(
                label.getFont().deriveFont(Font.BOLD, 11f)
        );
        label.setForeground(RmsColors.SECONDARY_TEXT);

        label.setBorder(
                BorderFactory.createCompoundBorder(
                        new RoundedLineBorder(RmsColors::border, 9),
                        BorderFactory.createEmptyBorder(5, 11, 5, 11)
                )
        );

        row.add(label);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);

        return row;
    }

    private void applyTheme() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyTheme);
            return;
        }

        setForeground(RmsColors.text());

        messageScroll.setBackground(RmsColors.surface());
        messageScroll.getViewport().setOpaque(true);
        messageScroll.getViewport().setBackground(RmsColors.surface());

        composer.setForeground(RmsColors.text());
        composer.setCaretColor(RmsColors.primary());

        selectedFiles.setForeground(RmsColors.SECONDARY_TEXT);
        status.setForeground(RmsColors.SECONDARY_TEXT);

        updateThemeComponents(this);

        sessionList.revalidate();
        sessionList.repaint();

        // Rebuild chat messages using the new theme colors.
        renderMessages(false, true);

        // Refresh the chat panel.
        revalidate();
        repaint();

        // Refresh the entire application window.
        java.awt.Window window =
                SwingUtilities.getWindowAncestor(this);

        if (window != null) {
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private void updateThemeComponents(
            java.awt.Container parent
    ) {
        for (Component component : parent.getComponents()) {

            if (component instanceof JLabel label) {
                String name = label.getName();

                if ("duckyAssistantName".equals(name)) {
                    label.setForeground(RmsColors.text());

                } else if (
                        "duckySubtitle".equals(name)
                                || "duckyRecentChats".equals(name)
                                || "duckyMessageTimestamp".equals(name)
                                || "chatDateSeparator".equals(name)
                ) {
                    label.setForeground(RmsColors.SECONDARY_TEXT);
                }
            }

            if (component instanceof java.awt.Container child) {
                updateThemeComponents(child);
            }
        }
    }

    private static String titleFrom(String question) {
        String title = question
                .replaceAll("\\s+", " ")
                .trim();

        if (title.length() > 30) {
            title = title.substring(0, 27) + "...";
        }

        return title.isEmpty() ? "New chat" : title;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static final class SessionRenderer
            extends JPanel
            implements ListCellRenderer<ChatSession> {

        private final JLabel title = new JLabel();
        private boolean selected;

        private SessionRenderer() {
            super(new BorderLayout());

            setOpaque(false);
            setBorder(
                    BorderFactory.createEmptyBorder(3, 5, 3, 5)
            );

            title.setBorder(
                    BorderFactory.createEmptyBorder(8, 9, 8, 9)
            );

            add(title, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ChatSession> list,
                ChatSession value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            title.setText(
                    "<html>" + escapeHtml(value.title) + "</html>"
            );

            title.setForeground(RmsColors.text());

            title.setFont(
                    title.getFont().deriveFont(
                            isSelected ? Font.BOLD : Font.PLAIN,
                            12f
                    )
            );

            selected = isSelected;

            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (selected) {
                Graphics2D g = (Graphics2D) graphics.create();

                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                g.setColor(RmsColors.primaryLight());

                g.fill(
                        new RoundRectangle2D.Float(
                                0, 0,
                                getWidth(),
                                getHeight(),
                                10, 10
                        )
                );

                g.dispose();
            }

            super.paintComponent(graphics);
        }
    }

    // Corrected message creation method.
    // Each message body and timestamp is created exactly once.
    static JPanel createMessage(
            ChatEntry entry,
            int viewportWidth
    ) {
        if (entry == null || entry.sender() == null) {
            throw new IllegalArgumentException(
                    "A message and sender are required."
            );
        }

        boolean assistant = entry.sender() == Sender.ASSISTANT;

        boolean hasText =
                entry.text() != null && !entry.text().isBlank();

        boolean hasFiles =
                entry.fileNames() != null
                        && !entry.fileNames().isEmpty();

        if (!hasText && !hasFiles) {
            return null;
        }

        int bubbleWidth =
                messageBubbleWidth(viewportWidth, entry.sender());

        int textWidth = Math.max(40, bubbleWidth - 26);

        JPanel contents = new JPanel();

        contents.setOpaque(false);
        contents.setLayout(
                new BoxLayout(contents, BoxLayout.Y_AXIS)
        );

        // Attachment names
        if (hasFiles) {
            JTextArea attachments = messageText(
                    "Local attachments: "
                            + String.join(", ", entry.fileNames()),
                    textWidth
            );

            attachments.setForeground(RmsColors.text());
            attachments.setFont(
                    attachments.getFont().deriveFont(10f)
            );

            contents.add(attachments);
            contents.add(Box.createVerticalStrut(4));
        }

        // Message body
        if (hasText) {
            JTextArea body = messageText(
                    entry.text(),
                    textWidth
            );

            // Use readable theme text instead of primaryForeground.
            body.setForeground(RmsColors.text());
            contents.add(body);
        }

        // Timestamp
        JLabel time = new JLabel(
                java.time.format.DateTimeFormatter
                        .ofPattern("h:mm a")
                        .format(
                                entry.sentAt()
                                        .atZone(ZoneId.systemDefault())
                        )
        );

        time.setName("duckyMessageTimestamp");

        time.setHorizontalAlignment(
                assistant
                        ? SwingConstants.LEFT
                        : SwingConstants.RIGHT
        );

        time.setForeground(RmsColors.SECONDARY_TEXT);

        time.setFont(
                time.getFont().deriveFont(10f)
        );

        contents.add(Box.createVerticalStrut(4));
        contents.add(time);

        // Message bubble
        JPanel bubble = new RoundedSurface(
                assistant
                        ? RmsColors::surface
                        : RmsColors::primaryLight,
                RmsColors::border,
                14
        );

        bubble.setName("duckyMessageBubble");
        bubble.setLayout(new BorderLayout());

        bubble.setBorder(
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        );

        bubble.add(contents, BorderLayout.CENTER);

        int bubbleHeight =
                contents.getPreferredSize().height + 16;

        bubble.setPreferredSize(
                new Dimension(bubbleWidth, bubbleHeight)
        );

        bubble.setMinimumSize(
                new Dimension(bubbleWidth, bubbleHeight)
        );

        bubble.setMaximumSize(
                new Dimension(bubbleWidth, Integer.MAX_VALUE)
        );

        // Message row and alignment
        JPanel row = new JPanel();

        row.setName("duckyMessageRow");
        row.setOpaque(false);

        row.setLayout(
                new BoxLayout(row, BoxLayout.X_AXIS)
        );

        if (assistant) {
            JLabel avatar = new JLabel(
                    new AiLogoIcon(34)
            );

            avatar.setName("duckyMessageAvatar");

            row.add(avatar);
            row.add(Box.createHorizontalStrut(8));
            row.add(bubble);
            row.add(Box.createHorizontalGlue());

        } else {
            row.add(Box.createHorizontalGlue());
            row.add(bubble);
        }

        row.setPreferredSize(
                new Dimension(viewportWidth, bubbleHeight)
        );

        row.setMinimumSize(
                new Dimension(viewportWidth, bubbleHeight)
        );

        row.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, bubbleHeight)
        );

        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        return row;
    }

    private static JTextArea messageText(
            String text,
            int width
    ) {
        JTextArea area = new JTextArea(text);

        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(RmsColors.text());

        area.setFont(
                area.getFont().deriveFont(13f)
        );

        area.setColumns(0);

        int rows = wrappedLineCount(
                text,
                area.getFont(),
                Math.max(1, width)
        );

        area.setRows(rows);

        Dimension size = new Dimension(
                width,
                rows * area.getFontMetrics(area.getFont()).getHeight() + 2
        );

        area.setPreferredSize(size);
        area.setMinimumSize(
                new Dimension(0, size.height)
        );

        area.setMaximumSize(
                new Dimension(width, Integer.MAX_VALUE)
        );

        area.setName("duckyMessageText");

        return area;
    }

    static int wrappedLineCount(
            String text,
            Font font,
            int width
    ) {
        if (text == null || text.isEmpty()) {
            return 1;
        }

        FontRenderContext context =
                new FontRenderContext(null, true, true);

        int lines = 0;

        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                lines++;
                continue;
            }

            AttributedString attributed =
                    new AttributedString(paragraph);

            attributed.addAttribute(
                    TextAttribute.FONT,
                    font
            );

            LineBreakMeasurer measurer =
                    new LineBreakMeasurer(
                            attributed.getIterator(),
                            BreakIterator.getLineInstance(),
                            context
                    );

            while (measurer.getPosition() < paragraph.length()) {
                if (measurer.nextLayout(
                        Math.max(1, width),
                        paragraph.length(),
                        false
                ) == null) {
                    break;
                }

                lines++;
            }
        }

        return Math.max(1, lines);
    }

    private static final class MessageList
            extends JPanel
            implements javax.swing.Scrollable {

        private MessageList() {
            setName("duckyMessageList");
            setOpaque(false);

            setLayout(
                    new BoxLayout(this, BoxLayout.Y_AXIS)
            );

            setBorder(
                    BorderFactory.createEmptyBorder(10, 12, 12, 12)
            );
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(560, 420);
        }

        @Override
        public int getScrollableUnitIncrement(
                java.awt.Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(
                java.awt.Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return Math.max(24, visibleRect.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static String safeError(Throwable error) {
        String message =
                error == null ? null : error.getMessage();

        return message == null || message.isBlank()
                ? "An unexpected local processing error occurred."
                : message;
    }

    private static String safeErrorMessage(String message) {
        return message == null || message.isBlank()
                ? "local AI configuration is invalid"
                : message;
    }

    private static final class RoundedSurface extends JPanel {

        private final Supplier<Color> fill;
        private final Supplier<Color> outline;
        private final int radius;

        private RoundedSurface(
                Supplier<Color> fill,
                Supplier<Color> outline,
                int radius
        ) {
            this.fill = fill;
            this.outline = outline;
            this.radius = radius;

            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            Graphics2D g =
                    (Graphics2D) graphics.create();

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            RoundRectangle2D shape =
                    new RoundRectangle2D.Float(
                            0.5f,
                            0.5f,
                            getWidth() - 1f,
                            getHeight() - 1f,
                            radius,
                            radius
                    );

            g.setColor(fill.get());
            g.fill(shape);

            g.setColor(outline.get());
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);

            g.dispose();
        }
    }

    private static final class RoundedLineBorder
            extends javax.swing.border.AbstractBorder {

        private final Supplier<Color> color;
        private final int radius;

        private RoundedLineBorder(
                Supplier<Color> color,
                int radius
        ) {
            this.color = color;
            this.radius = radius;
        }

        @Override
        public void paintBorder(
                Component component,
                Graphics graphics,
                int x,
                int y,
                int width,
                int height
        ) {
            Graphics2D g =
                    (Graphics2D) graphics.create();

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            g.setColor(color.get());

            g.draw(
                    new RoundRectangle2D.Float(
                            x + 0.5f,
                            y + 0.5f,
                            width - 1f,
                            height - 1f,
                            radius,
                            radius
                    )
            );

            g.dispose();
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(1, 1, 1, 1);
        }
    }

    private static final class RoundedTag extends JLabel {

        private final Supplier<Color> fill;
        private final Supplier<Color> outline;

        private RoundedTag(
                String text,
                Supplier<Color> fill,
                Supplier<Color> outline
        ) {
            super(text, SwingConstants.CENTER);

            this.fill = fill;
            this.outline = outline;

            setOpaque(false);
            setForeground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g =
                    (Graphics2D) graphics.create();

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            RoundRectangle2D shape =
                    new RoundRectangle2D.Float(
                            0.5f,
                            0.5f,
                            getWidth() - 1f,
                            getHeight() - 1f,
                            8,
                            8
                    );

            g.setColor(fill.get());
            g.fill(shape);

            g.setColor(outline.get());
            g.draw(shape);

            g.dispose();


            super.paintComponent(graphics);
        }
    }

    private static final class RoundedButton extends JButton {

        private final boolean primary;

        private RoundedButton(
                String text,
                boolean primary
        ) {
            super(text);

            this.primary = primary;

            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);

            setMargin(new Insets(8, 13, 8, 13));

            setFont(
                    getFont().deriveFont(Font.BOLD, 12f)
            );

            setCursor(
                    java.awt.Cursor.getPredefinedCursor(
                            java.awt.Cursor.HAND_CURSOR
                    )
            );
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g =
                    (Graphics2D) graphics.create();

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            boolean pressed = getModel().isPressed();
            boolean hovered = getModel().isRollover();

            Color fill = primary
                    ? (pressed
                            ? RmsColors.primaryDark()
                            : RmsColors.primary())
                    : (hovered
                            ? RmsColors.primaryLight()
                            : RmsColors.surface());

            RoundRectangle2D shape =
                    new RoundRectangle2D.Float(
                            0.5f,
                            0.5f,
                            getWidth() - 1f,
                            getHeight() - 1f,
                            12,
                            12
                    );

            g.setColor(fill);
            g.fill(shape);

            g.setColor(
                    primary ? fill : RmsColors.border()
            );

            g.draw(shape);

            g.dispose();

            setForeground(
                    primary
                            ? RmsColors.primaryForeground()
                            : RmsColors.text()
            );

            super.paintComponent(graphics);
        }
    }
}
