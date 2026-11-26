package com.bred.rms.ui;

import com.bred.rms.config.AppConfig;
import com.bred.rms.config.AppVersion;
import com.bred.rms.models.Role;
import com.bred.rms.security.Module;
import com.bred.rms.ui.auth.LoginPanel;
import com.bred.rms.ui.auth.SetupPanel;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.receipts.ReceiptDirectoryPanel;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JToggleButton;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSmokeTest {
    @Test
    void loginAndSetupPanelsBuildHeadless() throws Exception {
        LoginPanel login = new LoginPanel((email, password, done) -> { });
        assertNotNull(login);
        assertEquals("Version: " + AppVersion.current(), fieldLabel(login, "releaseVersionLabel").getText());
        assertNotNull(new SetupPanel());
    }

    @Test
    void shellForEncoderOffersNoUsersOrActivityLog() {
        var ctx = TestContexts.withUser(Role.ENCODER);
        Shell shell = new Shell(ctx, () -> { });
        assertFalse(shell.visibleModules().contains(Module.USERS));
        assertFalse(shell.visibleModules().contains(Module.ACTIVITY_LOG));
        assertTrue(shell.visibleModules().contains(Module.RECEIPTS));
        assertTrue(shell.visibleModules().contains(Module.DUCKY));
        assertDoesNotThrow(() -> shell.show(Module.SETTINGS));
        assertDoesNotThrow(() -> shell.show(Module.REPORTS));
        assertDoesNotThrow(() -> shell.show(Module.DASHBOARD));
    }

    @Test
    void duckyPanelShowsItsAiIdentityAndPrivacyNotice() {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        assertNotNull(findComponentNamed(panel, "duckyAssistantName"));
        assertNotNull(findComponentNamed(panel, "duckyAiTag"));
        assertNotNull(findComponentNamed(panel, "AI.logo"));
        assertNotNull(findComponentNamed(panel, "duckySessionsSurface"));
        assertNotNull(findComponentNamed(panel, "duckySessionList"));
        assertNotNull(findComponentNamed(panel, "duckyNewChat"));
        assertFalse(hasLabel(panel, "DUCKY AI"));
        assertNull(findComponentNamed(panel, "duckySettings"));
        assertFalse(panel.activeRecordAccessEnabled());
        assertFalse(hasComponentType(panel, JCheckBox.class));
    }

    @Test
    void duckyTitlesKeepTheCompleteQuestion() {
        String title = "A complete conversation title that is longer than thirty characters.";
        assertEquals(title, DuckyPanel.titleFrom(title));
        assertEquals("New chat", DuckyPanel.titleFrom(" \n\t "));
    }

    @Test
    void customMessageBodiesSizeToContentWithoutChangingTheirMaximumWidth() {
        JLabel label = new JLabel();
        var font = label.getFont().deriveFont(13f);
        var metrics = label.getFontMetrics(font);
        int shortWidth = MessagesPanel.messageBodyWidth("Hi", metrics, 300);
        int longWidth = MessagesPanel.messageBodyWidth("A longer message. ".repeat(80), metrics, 300);
        assertTrue(shortWidth < 100);
        assertEquals(300, longWidth);
    }

    @Test
    void duckyMessagesWrapWithinViewportWithoutHorizontalScrolling() throws Exception {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        JScrollPane messages = messageScroll(panel);
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, messages.getHorizontalScrollBarPolicy());
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, messages.getVerticalScrollBarPolicy());
        assertEquals(javax.swing.JViewport.SIMPLE_SCROLL_MODE, messages.getViewport().getScrollMode());
        assertFalse(messages.getViewport().isOpaque());
        assertTrue(((javax.swing.Scrollable) messages.getViewport().getView()).getScrollableTracksViewportWidth());

        assertTrue(DuckyPanel.messageBubbleWidth(360) <= 360);
        assertTrue(DuckyPanel.messageBubbleWidth(1000) <= 690);
        int shortBubble = DuckyPanel.messageBubbleWidth("Hi", 500, DuckyPanel.Sender.ASSISTANT);
        int mediumBubble = DuckyPanel.messageBubbleWidth("How are you?", 500, DuckyPanel.Sender.ASSISTANT);
        int longBubble = DuckyPanel.messageBubbleWidth(
                "I could not complete that request because the AI service is temporarily unavailable.",
                500,
                DuckyPanel.Sender.ASSISTANT
        );
        int maxIncomingWidth = DuckyPanel.messageBubbleWidth(500, DuckyPanel.Sender.ASSISTANT);
        assertTrue(shortBubble < mediumBubble);
        assertTrue(mediumBubble < longBubble);
        assertTrue(longBubble <= maxIncomingWidth);
        assertTrue(maxIncomingWidth <= (500 - 24 - 42) * 0.75);
        assertTrue(DuckyPanel.wrappedLineCount("https://example.test/" + "a".repeat(300),
                panel.getFont(), 120) > 1);
        assertTrue(DuckyPanel.wrappedLineCount("A very long message with multiple words ".repeat(20),
                panel.getFont(), 180) > 1);

        String longUrl = "https://example.test/" + "a".repeat(300);
        JPanel narrowRow = DuckyPanel.createMessage(new DuckyPanel.ChatEntry(DuckyPanel.Sender.ASSISTANT, longUrl,
                java.time.Instant.EPOCH, java.util.List.of("receipt-" + "x".repeat(150) + ".pdf")), 320);
        JPanel wideRow = DuckyPanel.createMessage(new DuckyPanel.ChatEntry(DuckyPanel.Sender.USER,
                "Long text ".repeat(30),
                java.time.Instant.EPOCH, java.util.List.of()), 900);
        JTextArea message = (JTextArea) findComponentNamed(narrowRow, "duckyMessageText");
        JPanel bubble = (JPanel) findComponentNamed(narrowRow, "duckyMessageBubble");
        JPanel wideBubble = (JPanel) findComponentNamed(wideRow, "duckyMessageBubble");
        assertTrue(message.getLineWrap());
        assertTrue(message.getWrapStyleWord());
        assertTrue(message.getPreferredSize().height > message.getFontMetrics(message.getFont()).getHeight());
        assertTrue(bubble.getPreferredSize().width <= 320);
        assertTrue(bubble.getPreferredSize().width + 42 + 24 <= 320);
        assertTrue(wideBubble.getPreferredSize().width <= 690);
        assertTrue(wideBubble.getPreferredSize().width > bubble.getPreferredSize().width);
        assertTrue(wideRow.getPreferredSize().width >= narrowRow.getPreferredSize().width);
    }

    @Test
    void assistantAndUserMessagesUseExplicitRolesAndSeparateAlignment() {
        JPanel assistantRow = DuckyPanel.createMessage(new DuckyPanel.ChatEntry(DuckyPanel.Sender.ASSISTANT,
                "Ducky greeting", java.time.Instant.EPOCH, java.util.List.of()), 320);
        JPanel userRow = DuckyPanel.createMessage(new DuckyPanel.ChatEntry(DuckyPanel.Sender.USER,
                "User message", java.time.Instant.EPOCH, java.util.List.of()), 320,
                TestContexts.withUser(Role.ENCODER).currentUser.get());
        assistantRow.setSize(320, assistantRow.getPreferredSize().height);
        userRow.setSize(320, userRow.getPreferredSize().height);
        layoutRecursively(assistantRow);
        layoutRecursively(userRow);

        Component avatar = findComponentNamed(assistantRow, "duckyMessageAvatar");
        Component assistantBubble = findComponentNamed(assistantRow, "duckyMessageBubble");
        Component userBubble = findComponentNamed(userRow, "duckyMessageBubble");
        Component userAvatar = findComponentNamed(userRow, "duckyMessageUserAvatar");
        Component assistantTime = findComponentNamed(assistantRow, "duckyMessageTimestamp");
        Component userTime = findComponentNamed(userRow, "duckyMessageTimestamp");
        assertNotNull(avatar);
        assertNotNull(userAvatar);
        assertNotNull(assistantTime);
        assertNotNull(userTime);
        java.awt.Point avatarPoint = javax.swing.SwingUtilities.convertPoint(
                avatar.getParent(), avatar.getLocation(), assistantRow);
        java.awt.Point assistantBubblePoint = javax.swing.SwingUtilities.convertPoint(
                assistantBubble.getParent(), assistantBubble.getLocation(), assistantRow);
        java.awt.Point userBubblePoint = javax.swing.SwingUtilities.convertPoint(
                userBubble.getParent(), userBubble.getLocation(), userRow);
        java.awt.Point userAvatarPoint = javax.swing.SwingUtilities.convertPoint(
                userAvatar.getParent(), userAvatar.getLocation(), userRow);
        assertEquals(0, avatarPoint.x);
        assertEquals(avatar.getWidth() + 8, assistantBubblePoint.x);
        assertTrue(userBubblePoint.x > assistantBubblePoint.x);
        assertEquals(userBubblePoint.x + userBubble.getWidth() + 8, userAvatarPoint.x);
        assertTrue(userAvatarPoint.x + userAvatar.getWidth() <= userRow.getWidth());
    }

    @Test
    void shortOutgoingWordsHaveEnoughWidthToAvoidBreakingAcrossLines() {
        JPanel row = DuckyPanel.createMessage(new DuckyPanel.ChatEntry(DuckyPanel.Sender.USER,
                "testing", java.time.Instant.EPOCH, java.util.List.of()), 320,
                TestContexts.withUser(Role.ENCODER).currentUser.get());
        row.setSize(320, row.getPreferredSize().height);
        layoutRecursively(row);

        JTextArea body = (JTextArea) findComponentNamed(row, "duckyMessageText");
        assertEquals(1, DuckyPanel.wrappedLineCount("testing", body.getFont(), body.getWidth()));
        assertTrue(body.getWidth() >= body.getFontMetrics(body.getFont()).stringWidth("testing") + 20);
    }

    @Test
    void assistantAndUserMessagesUseOppositePanelEdgesWithoutClippingText()
            throws Exception {
        String assistantText =
                "Hi, I'm Ducky. Ask me a question or attach a document for local OCR.";
        String longText =
                "A long message should wrap naturally without losing any text. ".repeat(30).trim();
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        JPanel list = messageList(panel);
        list.removeAll();
        var addRow = list.getClass().getDeclaredMethod("addRow", Component.class);
        addRow.setAccessible(true);
        addRow.invoke(list, DuckyPanel.createMessage(
                new DuckyPanel.ChatEntry(
                        DuckyPanel.Sender.ASSISTANT,
                        assistantText,
                        java.time.Instant.EPOCH,
                        java.util.List.of()
                ),
                520
        ));
        addRow.invoke(list, DuckyPanel.createMessage(
                new DuckyPanel.ChatEntry(
                        DuckyPanel.Sender.USER,
                        "testing",
                        java.time.Instant.EPOCH,
                        java.util.List.of()
                ),
                520
        ));
        addRow.invoke(list, DuckyPanel.createMessage(
                new DuckyPanel.ChatEntry(
                        DuckyPanel.Sender.USER,
                        longText,
                        java.time.Instant.EPOCH,
                        java.util.List.of()
                ),
                520
        ));
        list.setSize(520, 3000);
        layoutRecursively(list);

        Component avatar = findComponentNamed(list, "duckyMessageAvatar");
        java.util.List<JTextArea> textAreas = new java.util.ArrayList<>();
        collectMessageTextAreas(list, textAreas);
        JTextArea assistantBody = textAreas.stream()
                .filter(area -> assistantText.equals(area.getText()))
                .findFirst()
                .orElseThrow();
        Container assistantRow = (Container) ancestorNamed(assistantBody, "duckyMessageRow");
        JPanel assistantBubble = (JPanel) findComponentNamed(assistantRow, "duckyMessageBubble");
        JTextArea userBody = textAreas.stream()
                .filter(area -> "testing".equals(area.getText()))
                .findFirst()
                .orElseThrow();
        Container userRow = (Container) ancestorNamed(userBody, "duckyMessageRow");
        JPanel userBubble = (JPanel) findComponentNamed(userRow, "duckyMessageBubble");
        JTextArea longBody = textAreas.stream()
                .filter(area -> longText.equals(area.getText()))
                .findFirst()
                .orElseThrow();
        java.awt.Point avatarPoint = javax.swing.SwingUtilities.convertPoint(
                avatar.getParent(), avatar.getLocation(), list);
        java.awt.Point assistantBubblePoint = javax.swing.SwingUtilities.convertPoint(
                assistantBubble.getParent(), assistantBubble.getLocation(), list);
        java.awt.Point userBubblePoint = javax.swing.SwingUtilities.convertPoint(
                userBubble.getParent(), userBubble.getLocation(), list);

        assertEquals(12, avatarPoint.x);
        assertEquals(avatar.getWidth() + 8, assistantBubblePoint.x - avatarPoint.x);
        assertTrue(assistantBody.getPreferredSize().height
                > assistantBody.getFontMetrics(assistantBody.getFont()).getHeight());
        assertTrue(assistantBody.getHeight() >= assistantBody.getPreferredSize().height);
        assertTrue(assistantBody.getY() + assistantBody.getHeight()
                <= assistantBody.getParent().getHeight());

        assertEquals("testing", userBody.getText());
        assertTrue(
                userBody.getWidth() >= userBody.getPreferredSize().width,
                () -> "User message width " + userBody.getWidth()
                        + " is smaller than its preferred width "
                        + userBody.getPreferredSize().width
                        + " inside bubble width " + userBubble.getWidth()
                        + " and content width " + userBody.getParent().getWidth()
        );
        assertEquals(1, DuckyPanel.wrappedLineCount(
                "testing", userBody.getFont(), userBody.getWidth()));
        assertTrue(userBubblePoint.x > list.getWidth() / 2);
        assertTrue(userBubblePoint.x + userBubble.getWidth() <= list.getWidth() - 12);
        assertEquals(longText, longBody.getText());
        assertTrue(longBody.getPreferredSize().height > longBody.getFontMetrics(longBody.getFont()).getHeight());
        java.awt.geom.Rectangle2D lastCharacter = longBody.modelToView2D(longBody.getDocument().getLength());
        assertTrue(
                lastCharacter.getMaxY() <= longBody.getHeight(),
                () -> "Last character extends to " + lastCharacter.getMaxY()
                        + "px in a " + longBody.getHeight() + "px text area"
        );
    }

    @Test
    void duckyThinkingIndicatorAnimatesAndStopsWhenProcessingCompletes() throws Exception {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        javax.swing.Timer[] timer = new javax.swing.Timer[1];
        String[] initialDots = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    panel.addNotify();
                    Object session = fieldValue(panel, "activeSession");
                    setField(panel, "sending", true);
                    setField(panel, "processingSession", session);
                    invokeRender(panel);
                    timer[0] = (javax.swing.Timer) fieldValue(panel, "thinkingTimer");
                    JLabel dots = (JLabel) findComponentNamed(messageList(panel), "duckyThinkingDots");
                    initialDots[0] = dots.getText();
                    assertTrue(timer[0].isRunning());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });

            Thread.sleep(350);
            SwingUtilities.invokeAndWait(() -> { });
            JLabel animatedDots = (JLabel) findComponentNamed(messageList(panel), "duckyThinkingDots");
            assertTrue(!initialDots[0].equals(animatedDots.getText()));

            SwingUtilities.invokeAndWait(() -> {
                try {
                    setField(panel, "sending", false);
                    invokeRender(panel);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            assertNull(findComponentNamed(messageList(panel), "duckyThinkingDots"));
            assertFalse(timer[0].isRunning());
        } finally {
            SwingUtilities.invokeAndWait(panel::removeNotify);
        }
    }

    @Test
    void duckyResponseTypesActualTextAndSurvivesThemeRebuilds() throws Exception {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        String answer = "Ducky response text gradually appears. ".repeat(20);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    panel.addNotify();
                    DuckyPanel.ChatEntry entry = new DuckyPanel.ChatEntry(
                            DuckyPanel.Sender.ASSISTANT,
                            answer,
                            java.time.Instant.now(),
                            java.util.List.of()
                    );
                    addAnimatingMessage(panel, entry);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });

            Thread.sleep(120);
            SwingUtilities.invokeAndWait(() -> { });
            String beforeTheme = lastMessageTextArea(messageList(panel)).getText();
            assertTrue(!beforeTheme.isEmpty());
            assertTrue(beforeTheme.length() < answer.length());

            ThemeManager.apply(!previousDark);
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> { });
            String afterTheme = lastMessageTextArea(messageList(panel)).getText();
            assertTrue(afterTheme.length() > beforeTheme.length());

            SwingUtilities.invokeAndWait(panel::removeNotify);
            assertEquals(answer, lastMessageTextArea(messageList(panel)).getText());
        } finally {
            if (panel.isDisplayable()) {
                SwingUtilities.invokeAndWait(panel::removeNotify);
            }
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void duckyComposerGrowsOnlyToFourLinesAndNeverScrollsHorizontally() throws Exception {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        var field = DuckyPanel.class.getDeclaredField("composer");
        field.setAccessible(true);
        javax.swing.JTextArea composer = (javax.swing.JTextArea) field.get(panel);

        assertEquals(1, composer.getRows());
        composer.setText("line\n".repeat(12));
        assertEquals(4, composer.getRows());
        JScrollPane composerScroll = (JScrollPane) findComponentNamed(panel, "duckyComposerScroll");
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, composerScroll.getHorizontalScrollBarPolicy());
    }

    @Test
    void repeatedThemeRefreshPreservesActiveModuleAndChatWithoutOrphanComponents() throws Exception {
        DuckyPanel panel = DuckyPanel.unavailable("test configuration");
        Shell shell = new Shell(TestContexts.withUser(Role.ENCODER), () -> { });
        shell.show(Module.DUCKY);
        assertEquals(Module.DUCKY, shell.activeModule());
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        JButton newChat = (JButton) findComponentNamed(panel, "duckyNewChat");
        newChat.setEnabled(true);
        newChat.doClick();
        assertEquals(2, panel.sessionCount());
        try {
            var sessionField = DuckyPanel.class.getDeclaredField("sessionList");
            sessionField.setAccessible(true);
            @SuppressWarnings("unchecked")
            javax.swing.JList<Object> sessions = (javax.swing.JList<Object>) sessionField.get(panel);
            sessions.setSelectedIndex(1);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        addActiveMessage(panel, new DuckyPanel.ChatEntry(DuckyPanel.Sender.USER,
                "A prior user request", java.time.Instant.EPOCH, java.util.List.of()));
        assertEquals(2, countComponentsNamed(messageList(panel), "duckyMessageBubble"));
        assertEquals(1, countComponentsNamed(messageList(panel), "duckyMessageAvatar"));
        selectConversation(panel, 0);
        assertEquals(1, countComponentsNamed(messageList(panel), "duckyMessageBubble"));
        selectConversation(panel, 1);
        assertEquals(2, countComponentsNamed(messageList(panel), "duckyMessageBubble"));
        assertEquals(2, panel.sessionCount());
        assertEquals(1, panel.activeSessionIndex());
        int initialMessages = panel.activeConversationMessageCount();
        assertTrue(messageList(panel).getComponentCount() > 0,
                "message components: " + messageList(panel).getComponentCount());
        int initialComponentCount = countComponents(messageList(panel));
        assertNotNull(findComponentNamed(messageList(panel), "duckyMessageRow"));
        assertEquals(1, countComponentsNamed(messageList(panel), "duckyMessageAvatar"));
        try {
            panel.addNotify();
            for (int i = 0; i < 10; i++) {
                ThemeManager.apply((i % 2 == 0) != previousDark);
                assertEquals(1, countComponentsNamed(messageList(panel), "duckyMessageAvatar"));
                assertEquals(2, countComponentsNamed(messageList(panel), "duckyMessageBubble"));
                assertEquals(Module.DUCKY, shell.activeModule());
                assertEquals(2, panel.sessionCount());
                assertEquals(1, panel.activeSessionIndex());
                assertEquals(initialMessages, panel.activeConversationMessageCount());
                assertEquals(initialComponentCount, countComponents(messageList(panel)));
                assertNoOrphanMessageComponents(messageList(panel));
            }
            assertEquals(2, panel.sessionCount());
        } finally {
            panel.removeNotify();
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void openingDuckyDoesNotRequireAnAvailableAiServer() throws ReflectiveOperationException {
        Shell shell = new Shell(TestContexts.withUser(Role.ENCODER), () -> { });
        assertDoesNotThrow(() -> shell.show(Module.DUCKY));
        assertEquals(Module.DUCKY.title(), shellTitle(shell).getText());
    }

    @Test
    void shellForAdminOffersEverything() {
        Shell shell = new Shell(TestContexts.withUser(Role.ADMIN), () -> { });
        assertFalse(shell.visibleModules().contains(Module.COMPANIES));
        assertFalse(shell.visibleModules().contains(Module.HISTORY));
        assertTrue(shell.visibleModules().contains(Module.USERS));
        assertTrue(shell.visibleModules().contains(Module.DUCKY));
    }

    @Test
    void documentImportEntryPointIsVisibleOnlyToAdministrators() throws Exception {
        ReceiptDirectoryPanel encoder = new ReceiptDirectoryPanel(TestContexts.withUser(Role.ENCODER),
                ignored -> { }, () -> { }, () -> { });
        ReceiptDirectoryPanel admin = new ReceiptDirectoryPanel(TestContexts.withUser(Role.ADMIN),
                ignored -> { }, () -> { }, () -> { });
        ReceiptDirectoryPanel creator = new ReceiptDirectoryPanel(TestContexts.withUser(Role.CREATOR),
                ignored -> { }, () -> { }, () -> { });

        assertFalse(directoryImportButton(encoder).isVisible());
        assertTrue(directoryImportButton(admin).isVisible());
        assertFalse(directoryImportButton(creator).isVisible());
    }

    @Test
    void shellForCreatorOffersGlobalCompaniesAndHistory() throws Exception {
        Shell shell = new Shell(TestContexts.withUser(Role.CREATOR), () -> { });
        assertTrue(shell.visibleModules().contains(Module.USERS));
        assertTrue(shell.visibleModules().contains(Module.COMPANIES));
        assertTrue(shell.visibleModules().contains(Module.HISTORY));
        assertTrue(shell.visibleModules().contains(Module.DUCKY));

        JPanel sidebar = (JPanel) shell.getComponent(0);
        JScrollPane navigation = java.util.Arrays.stream(sidebar.getComponents())
                .filter(JScrollPane.class::isInstance)
                .map(JScrollPane.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, navigation.getVerticalScrollBarPolicy());
        assertEquals(12, navigation.getVerticalScrollBar().getPreferredSize().width);

        JToggleButton companies = navigationButton(shell, Module.COMPANIES);
        assertTrue(companies.isEnabled());
        SwingUtilities.invokeAndWait(companies::doClick);
        assertTrue(companies.isSelected());
        assertEquals(Module.COMPANIES.title(), shellTitle(shell).getText());

        JToggleButton history = navigationButton(shell, Module.HISTORY);
        SwingUtilities.invokeAndWait(history::doClick);
        assertTrue(history.isSelected());
        assertEquals(Module.HISTORY.title(), shellTitle(shell).getText());
    }

    @Test
    void selectedNavigationOutlineIsInsetOnlyForCreatorSidebar() throws Exception {
        Shell creatorShell = new Shell(TestContexts.withUser(Role.CREATOR), () -> { });
        Shell encoderShell = new Shell(TestContexts.withUser(Role.ENCODER), () -> { });

        assertEquals(12, selectionTrailingInset(creatorShell));
        assertEquals(0, selectionTrailingInset(encoderShell));
    }

    @Test
    void settingsOffersTheSignatureAppearance() throws Exception {
        SettingsPanel settings = new SettingsPanel(TestContexts.withUser(Role.ENCODER));
        JComboBox<?> appearances = java.util.Arrays.stream(settings.getComponents())
                .filter(JComboBox.class::isInstance)
                .map(JComboBox.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(ThemeManager.Appearance.values().length, appearances.getItemCount());
        assertEquals(ThemeManager.Appearance.BREDUCK_SIGNATURE, appearances.getItemAt(1));
        assertEquals(appearances.getPreferredSize(), appearances.getMaximumSize());
        assertEquals("Installed version: " + AppVersion.current(),
                fieldLabel(settings, "releaseVersion").getText());
    }

    @Test
    void styledScrollbarsKeepTheirCustomUiAfterThemeUpdates() throws Exception {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            StyledScrollPane scroll = new StyledScrollPane(new JPanel());
            Class<?> verticalUi = scroll.getVerticalScrollBar().getUI().getClass();
            Class<?> horizontalUi = scroll.getHorizontalScrollBar().getUI().getClass();

            ThemeManager.setAppearance(ThemeManager.Appearance.SILVER_LINING);
            ThemeManager.apply(!previousDark);
            SwingUtilities.invokeAndWait(scroll::updateUI);

            assertEquals(verticalUi, scroll.getVerticalScrollBar().getUI().getClass());
            assertEquals(horizontalUi, scroll.getHorizontalScrollBar().getUI().getClass());
            assertEquals(12, scroll.getVerticalScrollBar().getPreferredSize().width);
            assertEquals(12, scroll.getHorizontalScrollBar().getPreferredSize().height);
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    @Test
    void settingsAndLoginTextStayReadableWithSilverLining() throws Exception {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        SettingsPanel settings = null;
        LoginPanel login = null;
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.SILVER_LINING);
            ThemeManager.apply(true);
            settings = new SettingsPanel(TestContexts.withUser(Role.ENCODER));
            login = new LoginPanel((email, password, done) -> { });

            ThemeManager.apply(false);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(RmsColors.primaryForeground(), settingsSubmitButton(settings).getForeground());
            assertEquals(Color.WHITE, findLabel(login, "BRED").getForeground());
            assertEquals(RmsColors.primaryForeground(), loginSignInButton(login).getForeground());

            ThemeManager.apply(true);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(RmsColors.primaryForeground(), settingsSubmitButton(settings).getForeground());
            assertEquals(RmsColors.LIGHT_TEXT, findLabel(login, "BRED").getForeground());
            assertEquals(RmsColors.primaryForeground(), loginSignInButton(login).getForeground());
        } finally {
            if (login != null) login.removeNotify();
            if (settings != null) settings.removeNotify();
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static JLabel findLabel(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return label;
            if (component instanceof Container child) {
                JLabel match = findLabel(child, text);
                if (match != null) return match;
            }
        }
        throw new AssertionError("Label not found: " + text);
    }

    private static boolean hasLabel(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return true;
            if (component instanceof Container child && hasLabel(child, text)) return true;
        }
        return false;
    }

    private static Component findComponentNamed(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) {
                Component match = findComponentNamed(child, name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static int countComponentsNamed(Container parent, String name) {
        int count = 0;
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) count++;
            if (component instanceof Container child) count += countComponentsNamed(child, name);
        }
        return count;
    }

    private static void assertNoOrphanMessageComponents(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component.getName() != null && component.getName().startsWith("duckyMessage")) {
                assertEquals(parent, component.getParent());
            }
            if (component instanceof Container child) assertNoOrphanMessageComponents(child);
        }
    }

    private static void layoutRecursively(Container parent) {
        parent.doLayout();
        for (Component component : parent.getComponents()) {
            if (component instanceof Container child) layoutRecursively(child);
        }
    }

    private static void addActiveMessage(DuckyPanel panel, DuckyPanel.ChatEntry entry)
            throws ReflectiveOperationException {
        var activeField = DuckyPanel.class.getDeclaredField("activeSession");
        activeField.setAccessible(true);
        Object session = activeField.get(panel);
        var entriesField = session.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<DuckyPanel.ChatEntry> entries =
                (java.util.List<DuckyPanel.ChatEntry>) entriesField.get(session);
        entries.add(entry);
        var render = DuckyPanel.class.getDeclaredMethod("renderMessages", boolean.class);
        render.setAccessible(true);
        render.invoke(panel, false);
    }

    private static void addAnimatingMessage(DuckyPanel panel, DuckyPanel.ChatEntry entry)
            throws ReflectiveOperationException, ClassNotFoundException {
        Object session = fieldValue(panel, "activeSession");
        var entriesField = session.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<DuckyPanel.ChatEntry> entries =
                (java.util.List<DuckyPanel.ChatEntry>) entriesField.get(session);
        entries.add(entry);

        Class<?> animationClass = Class.forName(
                DuckyPanel.class.getName() + "$TypewriterAnimation"
        );
        var constructor = animationClass.getDeclaredConstructor(
                session.getClass(),
                DuckyPanel.ChatEntry.class
        );
        constructor.setAccessible(true);
        setField(panel, "typewriter", constructor.newInstance(session, entry));
        invokeRender(panel);
    }

    private static Object fieldValue(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value)
            throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void invokeRender(DuckyPanel panel) throws ReflectiveOperationException {
        var render = DuckyPanel.class.getDeclaredMethod("renderMessages", boolean.class);
        render.setAccessible(true);
        render.invoke(panel, false);
    }

    private static JTextArea lastMessageTextArea(Container parent) {
        java.util.List<JTextArea> areas = new java.util.ArrayList<>();
        collectMessageTextAreas(parent, areas);
        if (areas.isEmpty()) throw new AssertionError("Ducky message text not found.");
        return areas.get(areas.size() - 1);
    }

    private static void collectMessageTextAreas(Container parent, java.util.List<JTextArea> areas) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTextArea area
                    && "duckyMessageText".equals(area.getName())) {
                areas.add(area);
            }
            if (component instanceof Container child) collectMessageTextAreas(child, areas);
        }
    }

    private static Component ancestorNamed(Component component, String name) {
        Component current = component;
        while (current != null) {
            if (name.equals(current.getName())) return current;
            current = current.getParent();
        }
        throw new AssertionError("Component ancestor not found: " + name);
    }

    private static void selectConversation(DuckyPanel panel, int index) throws ReflectiveOperationException {
        var sessionField = DuckyPanel.class.getDeclaredField("sessionList");
        sessionField.setAccessible(true);
        @SuppressWarnings("unchecked")
        javax.swing.JList<Object> sessions = (javax.swing.JList<Object>) sessionField.get(panel);
        sessions.setSelectedIndex(index);
    }

    private static int countComponents(Container parent) {
        int count = parent.getComponentCount();
        for (Component component : parent.getComponents()) {
            if (component instanceof Container child) count += countComponents(child);
        }
        return count;
    }

    private static boolean hasComponentType(Container parent, Class<?> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return true;
            if (component instanceof Container child && hasComponentType(child, type)) return true;
        }
        return false;
    }

    private static JScrollPane messageScroll(DuckyPanel panel) throws ReflectiveOperationException {
        var field = DuckyPanel.class.getDeclaredField("messageScroll");
        field.setAccessible(true);
        return (JScrollPane) field.get(panel);
    }

    private static JPanel messageList(DuckyPanel panel) throws ReflectiveOperationException {
        var field = DuckyPanel.class.getDeclaredField("messageList");
        field.setAccessible(true);
        return (JPanel) field.get(panel);
    }

    private static JButton settingsSubmitButton(SettingsPanel settings) throws ReflectiveOperationException {
        var field = SettingsPanel.class.getDeclaredField("submitReport");
        field.setAccessible(true);
        return (JButton) field.get(settings);
    }

    private static JLabel fieldLabel(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (JLabel) field.get(owner);
    }

    private static JButton loginSignInButton(LoginPanel login) throws ReflectiveOperationException {
        var field = LoginPanel.class.getDeclaredField("signInButton");
        field.setAccessible(true);
        return (JButton) field.get(login);
    }

    private static JToggleButton navigationButton(Shell shell, Module module) throws ReflectiveOperationException {
        var field = Shell.class.getDeclaredField("navButtons");
        field.setAccessible(true);
        return (JToggleButton) ((Map<?, ?>) field.get(shell)).get(module);
    }

    private static JLabel shellTitle(Shell shell) throws ReflectiveOperationException {
        var field = Shell.class.getDeclaredField("title");
        field.setAccessible(true);
        return (JLabel) field.get(shell);
    }

    private static int selectionTrailingInset(Shell shell) throws ReflectiveOperationException {
        var navField = Shell.class.getDeclaredField("navList");
        navField.setAccessible(true);
        Object navList = navField.get(shell);
        var insetField = navList.getClass().getDeclaredField("selectionTrailingInset");
        insetField.setAccessible(true);
        return insetField.getInt(navList);
    }

    private static JButton directoryImportButton(ReceiptDirectoryPanel panel) throws ReflectiveOperationException {
        var field = ReceiptDirectoryPanel.class.getDeclaredField("importDocuments");
        field.setAccessible(true);
        return (JButton) field.get(panel);
    }
}

