package com.bred.rms.ui;

import com.bred.rms.ai.DuckyPermission;
import com.bred.rms.ai.DuckyPreferences;
import com.bred.rms.ai.GroqChatClient;
import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.utils.BackgroundTask;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/** Creator-only application dialog for local or shared Ducky AI settings. */
final class DuckySettingsPopup extends JDialog {
    private static final int CONTENT_WIDTH = 390;

    private final GroqCredentials localCredentials;
    private final RestClient sharedBackend;
    private final Runnable onSave;

    private final JTextField model = new RoundedTextField(DuckyPreferences.model());
    private final JPasswordField apiKey = new RoundedPasswordField(26);
    private final JComboBox<DuckyPermission> permission =
            new RoundedComboBox<>(DuckyPermission.values());
    private final JCheckBox cloudConsent = new JCheckBox("I consent to enabling Groq cloud AI");
    private final JCheckBox confirmation = new JCheckBox("I understand prompts are processed outside BRED");
    private final JButton test = new RoundedButton("Test connection", false);
    private final JButton removeKey = new RoundedButton("Remove saved key", false);
    private final JButton save = new RoundedButton("Save settings", true);
    private final JButton cancel = new RoundedButton("Cancel", false);
    private final JLabel status = new JLabel(" ");
    private final JLabel credentialStatus = new JLabel(" ");
    private final boolean creatorOnly;
    private final Consumer<Boolean> themeListener = ignored -> applyTheme();

    DuckySettingsPopup(GroqCredentials credentials, Runnable onSave) {
        this(null, credentials, null, onSave, false);
    }

    DuckySettingsPopup(Window owner, GroqCredentials credentials, Runnable onSave, boolean creatorOnly) {
        this(owner, credentials, null, onSave, creatorOnly);
    }

    DuckySettingsPopup(Window owner, RestClient sharedBackend, Runnable onSave, boolean creatorOnly) {
        this(owner, null, sharedBackend, onSave, creatorOnly);
    }

    private DuckySettingsPopup(Window owner, GroqCredentials credentials, RestClient sharedBackend,
                               Runnable onSave, boolean creatorOnly) {
        super(owner, "Ducky AI Settings", ModalityType.APPLICATION_MODAL);
        this.localCredentials = credentials;
        this.sharedBackend = sharedBackend;
        this.onSave = onSave;
        this.creatorOnly = creatorOnly;
        if (sharedBackend != null && !creatorOnly) {
            throw new SecurityException("Only the Creator can open shared Ducky AI settings.");
        }

        setName("duckySettingsDialog");
        setResizable(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        configureComponents();
        setContentPane(buildContent());
        pack();
        fitAndCenter(owner);
        configureActions();
        ThemeManager.addThemeListener(themeListener);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                ThemeManager.removeThemeListener(themeListener);
            }
        });
        applyTheme();
        if (sharedBackend != null) loadSharedStatus();
        else loadLocalStatus();
    }

    private JPanel buildContent() {
        JPanel content = new JPanel();
        content.setName("duckySettingsForm");
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        content.add(sectionHeading("AI PROVIDER"));
        content.add(gap(5));
        content.add(description("Groq"));

        content.add(gap(14));
        content.add(sectionHeading("MODEL"));
        content.add(gap(6));
        content.add(model);

        content.add(gap(13));
        content.add(sectionHeading("API KEY"));
        content.add(gap(6));
        if (sharedBackend == null) content.add(apiKey);
        content.add(gap(5));
        content.add(wrappedLabel(credentialStatus));

        content.add(gap(10));
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 7, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.add(test);
        if (sharedBackend == null) buttons.add(removeKey);
        content.add(buttons);
        content.add(gap(5));
        content.add(wrappedLabel(status));

        content.add(gap(16));
        content.add(sectionHeading("AGENT PERMISSION"));
        content.add(gap(6));
        content.add(permission);
        content.add(gap(5));
        content.add(description("Ask: no BRED tools. Assist: authorized read-only tools. "
                + "Execute is reserved for future development."));

        content.add(gap(16));
        content.add(sectionHeading("PRIVACY"));
        content.add(gap(6));
        content.add(description("Ordinary chat prompts are processed by Groq. "
                + "Sensitive BRED records, OCR text, uploads, suppliers, and financial details "
                + "are never sent automatically. Ducky asks for separate confirmation before "
                + "sharing local OCR or authorized BRED results."));
        content.add(gap(10));
        content.add(cloudConsent);
        content.add(gap(5));
        content.add(confirmation);

        content.add(gap(17));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setName("duckySettingsScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(480, 490));

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setName("duckySettingsFooter");
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        javax.swing.JSeparator separator = new javax.swing.JSeparator();
        JPanel actionButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        actionButtons.setName("duckySettingsActions");
        actionButtons.setOpaque(false);
        actionButtons.add(cancel);
        actionButtons.add(save);
        footer.add(separator, BorderLayout.NORTH);
        footer.add(actionButtons, BorderLayout.SOUTH);

        RoundedDialogSurface surface = new RoundedDialogSurface();
        surface.setName("duckySettingsSurface");
        surface.setLayout(new BorderLayout(0, 0));
        surface.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        surface.add(scroll, BorderLayout.CENTER);
        surface.add(footer, BorderLayout.SOUTH);
        return surface;
    }

    private void configureComponents() {
        model.setName("groqModel");
        apiKey.setName("groqApiKey");
        permission.setName("duckyPermission");
        cloudConsent.setName("duckyCloudConsent");
        confirmation.setName("duckyCloudConfirmation");
        test.setName("groqTestConnection");
        removeKey.setName("groqRemoveKey");
        save.setName("duckySaveSettings");
        cancel.setName("duckyCancelSettings");
        status.setName("groqConnectionStatus");
        credentialStatus.setName("groqCredentialStatus");

        styleField(model);
        styleField(apiKey);
        styleCombo(permission);
        styleCheckBox(cloudConsent);
        styleCheckBox(confirmation);
        styleButton(test, false);
        styleButton(removeKey, false);
        styleButton(cancel, false);
        styleButton(save, true);

        status.setForeground(RmsColors.SECONDARY_TEXT);
        credentialStatus.setForeground(RmsColors.SECONDARY_TEXT);
        status.setFont(status.getFont().deriveFont(11f));
        credentialStatus.setFont(credentialStatus.getFont().deriveFont(11f));
        apiKey.setEchoChar('\u2022');
        permission.setSelectedItem(DuckyPreferences.permission());
    }

    private void configureActions() {
        cancel.addActionListener(event -> dispose());
        test.addActionListener(event -> testConnection());
        save.addActionListener(event -> saveSettings());
        removeKey.addActionListener(event -> removeCredential());
        cloudConsent.addActionListener(event -> updateSaveState());
        confirmation.addActionListener(event -> updateSaveState());
        permission.addActionListener(event -> {
            if (permission.getSelectedItem() == DuckyPermission.EXECUTE) {
                permission.setSelectedItem(DuckyPermission.ASK);
                setStatus("Execute mode is reserved for future development.");
            }
        });
        updateSaveState();
    }

    private void loadLocalStatus() {
        boolean environment = localCredentials != null && localCredentials.environmentConfigured();
        apiKey.setEnabled(!environment);
        removeKey.setEnabled(!environment);
        credentialStatus.setText(environment
                ? "Using GROQ_API_KEY from the development environment."
                : localCredentials != null && localCredentials.credentialSaved()
                        ? "A key is saved in Windows Credential Manager. It is never displayed."
                        : "Key is not configured.");
        status.setText(" " );
        updateSaveState();
    }

    private void loadSharedStatus() {
        apiKey.setVisible(false);
        cloudConsent.setVisible(false);
        confirmation.setVisible(false);
        permission.setEnabled(false);
        test.setEnabled(false);
        save.setEnabled(false);
        credentialStatus.setText("Checking server-side credential status...");
        status.setText("Loading shared AI configuration...");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "status");
        BackgroundTask.run(
                () -> sharedBackend.invokeFunction("ducky-ai", body),
                node -> SwingUtilities.invokeLater(() -> applySharedStatus(node)),
                error -> SwingUtilities.invokeLater(() -> setSharedLoadError(error))
        );
    }

    private void applySharedStatus(JsonNode node) {
        model.setText(node.path("model").asText(DuckyPreferences.model()));
        String credential = node.path("credential").asText("not_configured");
        credentialStatus.setText("configured".equals(credential)
                ? "Groq API key: configured on the server"
                : "Groq API key: not configured");
        String serviceStatus = node.path("status").asText("service_unavailable");
        status.setText("configured".equals(serviceStatus)
                ? "Server settings are present. Use Test Connection to verify Groq."
                : "not_configured".equals(serviceStatus) ? "Server-side Groq key is not configured."
                        : "Service status: " + serviceStatus.replace('_', ' '));
        test.setEnabled(true);
        save.setEnabled(true);
    }

    private void setSharedLoadError(Throwable error) {
        credentialStatus.setText("Could not retrieve server-side configuration.");
        status.setText(safeBackendError(error));
        test.setEnabled(true);
        save.setEnabled(true);
    }

    private void saveSettings() {
        char[] enteredKey = apiKey.getPassword();
        try {
            String selectedModel = model.getText() == null ? "" : model.getText().trim();
            if (selectedModel.isBlank() || selectedModel.length() > 120) {
                throw new IllegalArgumentException("Enter a valid Groq model name (up to 120 characters).");
            }
            if (sharedBackend != null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("action", "configure");
                body.put("model", selectedModel);
                test.setEnabled(false);
                save.setEnabled(false);
                setStatus("Saving shared AI configuration...");
                BackgroundTask.run(
                        () -> sharedBackend.invokeFunction("ducky-ai", body),
                        node -> SwingUtilities.invokeLater(() -> {
                            test.setEnabled(true);
                            save.setEnabled(true);
                            setStatus("Shared AI configuration saved.");
                            applySharedStatus(node);
                            onSave.run();
                            dispose();
                        }),
                        error -> SwingUtilities.invokeLater(() -> {
                            test.setEnabled(true);
                            save.setEnabled(true);
                            setStatus(safeBackendError(error));
                        })
                );
                return;
            }
            if (cloudConsent.isSelected() && !confirmation.isSelected()) {
                throw new IllegalArgumentException("Confirm the cloud privacy notice before enabling Groq.");
            }
            if (enteredKey.length > 0) localCredentials.save(enteredKey);
            DuckyPreferences.setModel(selectedModel);
            DuckyPreferences.setCloudConsent(cloudConsent.isSelected());
            if (cloudConsent.isSelected()) DuckyPreferences.setPermission((DuckyPermission) permission.getSelectedItem());
            onSave.run();
            dispose();
        } catch (RuntimeException error) {
            setStatus(error.getMessage() == null ? "Ducky settings could not be saved." : error.getMessage());
        } finally {
            Arrays.fill(enteredKey, '\0');
        }
    }

    private void removeCredential() {
        if (sharedBackend != null) {
            setStatus("The Groq key is managed with Supabase secrets and cannot be removed here.");
            return;
        }
        try {
            localCredentials.removeSaved();
            apiKey.setText("");
            credentialStatus.setText("Key is not configured.");
            setStatus("Saved Groq API key removed.");
        } catch (RuntimeException error) {
            setStatus(error.getMessage() == null ? "Saved Groq key could not be removed." : error.getMessage());
        }
    }

    private void testConnection() {
        if (sharedBackend != null) {
            test.setEnabled(false);
            setStatus("Testing the shared Groq connection...");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "test");
            body.put("model", model.getText().trim());
            BackgroundTask.run(
                    () -> sharedBackend.invokeFunction("ducky-ai", body),
                    node -> SwingUtilities.invokeLater(() -> {
                        test.setEnabled(true);
                        String statusValue = node.path("status").asText("connection_failed");
                        setStatus(switch (statusValue) {
                            case "connected" -> "Connected to Groq using " + model.getText().trim() + ".";
                            case "not_configured" -> "Server-side Groq API key is not configured.";
                            case "authentication_failed" -> "Groq authentication failed. Check the server-side Groq secret.";
                            case "model_unavailable" -> "The selected Groq model is unavailable.";
                            case "rate_limited" -> "Groq rate limit reached. Wait briefly, then try again.";
                            case "service_unavailable" -> "Groq is temporarily unavailable.";
                            default -> "Groq connection failed unexpectedly.";
                        });
                    }),
                    error -> SwingUtilities.invokeLater(() -> {
                        test.setEnabled(true);
                        setStatus(safeBackendError(error));
                        test.setEnabled(true);
                    })
            );
            return;
        }
        char[] enteredKey = apiKey.getPassword();
        char[] key = enteredKey.length == 0 ? localCredentials.read() : enteredKey.clone();
        Arrays.fill(enteredKey, '\0');
        if (key == null || key.length == 0) {
            setStatus("Enter or configure a Groq API key first.");
            return;
        }
        String selectedModel = model.getText().trim();
        test.setEnabled(false);
        setStatus("Testing Groq connection...");
        BackgroundTask.run(
                () -> {
                    try (GroqChatClient client = new GroqChatClient(selectedModel, key, Duration.ofSeconds(30))) {
                        GroqChatClient.Completion result = client.complete(
                                List.of(GroqChatClient.Message.text("user", "Reply with OK.")), List.of());
                        if (result.content() == null || result.content().isBlank()) {
                            throw new IllegalStateException("Groq returned an empty response.");
                        }
                        return "Connected to Groq using " + selectedModel + ".";
                    } finally {
                        Arrays.fill(key, '\0');
                    }
                },
                message -> SwingUtilities.invokeLater(() -> {
                    test.setEnabled(true);
                    setStatus(message);
                }),
                error -> SwingUtilities.invokeLater(() -> {
                    test.setEnabled(true);
                    setStatus(error.getMessage() == null ? "Groq connection test failed." : error.getMessage());
                })
        );
    }

    private void updateSaveState() {
        boolean consent = cloudConsent.isSelected();
        permission.setEnabled(sharedBackend == null && consent);
        confirmation.setEnabled(sharedBackend == null && consent);
        save.setEnabled(sharedBackend != null || !consent || confirmation.isSelected());
    }

    private void setStatus(String message) {
        status.setText("<html><body style='width:350px'>" + escapeHtml(message) + "</body></html>");
    }

    private void fitAndCenter(Window owner) {
        Rectangle bounds = screenBounds(owner);
        Dimension screenFit = new Dimension(
                Math.min(520, Math.max(320, bounds.width - 80)),
                Math.min(680, Math.max(320, bounds.height - 80)));
        Dimension current = getPreferredSize();
        int minimumWidth = Math.min(480, bounds.width);
        int minimumHeight = Math.min(400, bounds.height);
        setMinimumSize(new Dimension(minimumWidth, minimumHeight));
        setSize(Math.max(minimumWidth, Math.min(current.width, screenFit.width)),
                Math.max(minimumHeight, Math.min(current.height, screenFit.height)));
        setLocation(
                bounds.x + (bounds.width - getWidth()) / 2,
                bounds.y + (bounds.height - getHeight()) / 2);
    }

    private static Rectangle screenBounds(Window owner) {
        GraphicsConfiguration gc = owner == null ? null : owner.getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private static JLabel sectionHeading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(RmsColors.text());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));
        return label;
    }

    private static JTextArea description(String text) {
        JTextArea area = new JTextArea(text, 3, 35);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(RmsColors.SECONDARY_TEXT);
        area.setFont(area.getFont().deriveFont(11f));
        area.setAlignmentX(LEFT_ALIGNMENT);
        area.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));
        return area;
    }

    private static JLabel wrappedLabel(JLabel label) {
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(CONTENT_WIDTH, 38));
        return label;
    }

    private static JPanel gap(int height) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        panel.setPreferredSize(new Dimension(0, height));
        panel.setMinimumSize(new Dimension(0, height));
        return panel;
    }

    private static void styleField(JTextField field) {
        field.setForeground(RmsColors.text());
        field.setBackground(RmsColors.surface());
        field.setCaretColor(RmsColors.primary());
        field.setBorder(BorderFactory.createCompoundBorder(
                new RoundedControlBorder(12),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        field.setOpaque(false);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(CONTENT_WIDTH, 38));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(RmsColors.text());
        combo.setBackground(RmsColors.surface());
        combo.setOpaque(false);
        combo.setBorder(new RoundedControlBorder(12));
        combo.setAlignmentX(LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(CONTENT_WIDTH, 38));
    }

    private static void styleCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(RmsColors.text());
        checkBox.setFont(checkBox.getFont().deriveFont(11f));
        checkBox.setAlignmentX(LEFT_ALIGNMENT);
        checkBox.setMaximumSize(new Dimension(CONTENT_WIDTH, 28));
    }

    private static void styleButton(JButton button, boolean primary) {
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBackground(primary ? RmsColors.primary() : RmsColors.surface());
        button.setForeground(primary ? RmsColors.primaryForeground() : RmsColors.text());
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
    }

    private static String safeBackendError(Throwable error) {
        if (error instanceof SupabaseException exception) {
            if (exception.status() == 401) return "Your session has expired. Please sign in again.";
            if (exception.status() == 403) return "Permission denied. Your account cannot manage or test shared Ducky AI settings.";
            if (exception.status() == 404) return "The Ducky AI backend endpoint is missing. Deploy the ducky-ai function.";
            if (exception.status() == 429) return "The Ducky AI backend rate limit was reached. Wait briefly, then try again.";
            if (exception.status() == 0) return "Ducky AI backend unreachable. Check the network and Supabase URL.";
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("groq authentication failed")) {
                return "Groq authentication failed. Verify the server-side Groq secret.";
            }
            if (message.contains("model")) return "The configured Groq model is unavailable. Check the server-side model configuration.";
            if (message.contains("rate limit")) return "Groq rate limit reached. Wait briefly, then try again.";
            if (exception.status() == 504 || message.contains("timed out")) return "The Groq connection timed out. Try again.";
            if (message.contains("could not reach")) return "Groq is unreachable from the backend. Check the network and try again.";
            if (message.contains("required supabase function configuration is missing")) {
                return "Supabase function configuration is incomplete. Check the deployed function environment.";
            }
            if (message.contains("shared ai configuration")) {
                return "The shared AI configuration could not be loaded. Apply migration 015_ducky_shared_ai.sql.";
            }
            if (exception.status() == 502) return "Groq rejected the request. Check the model and request format.";
            if (exception.status() == 503) return "Groq or the Ducky AI backend is unavailable. Try again shortly.";
            if (exception.status() >= 500) return "Unexpected Ducky AI backend error (HTTP "
                    + exception.status() + "). Check the deployed function configuration.";
        }
        return "Unexpected error while contacting Ducky AI.";
    }

    private void applyTheme() {
        Runnable update = () -> {
            applyTextTheme(getContentPane());
            status.setForeground(RmsColors.SECONDARY_TEXT);
            credentialStatus.setForeground(RmsColors.SECONDARY_TEXT);
            if (getContentPane() instanceof JComponent component) {
                component.revalidate();
                component.repaint();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private void applyTextTheme(Component component) {
        if (component instanceof JLabel label && label != status && label != credentialStatus) {
            label.setForeground(RmsColors.text());
        } else if (component instanceof javax.swing.text.JTextComponent text) {
            text.setForeground(text instanceof JTextArea area && !area.isEditable()
                    ? RmsColors.SECONDARY_TEXT : RmsColors.text());
            text.setCaretColor(RmsColors.primary());
        } else if (component instanceof JButton button) {
            boolean primary = button == save;
            button.setForeground(primary ? RmsColors.primaryForeground() : RmsColors.text());
            button.setBackground(primary ? RmsColors.primary() : RmsColors.surface());
        } else if (component instanceof JComboBox<?> combo) {
            combo.setForeground(RmsColors.text());
            combo.setBackground(RmsColors.surface());
        } else if (component instanceof JCheckBox checkBox) {
            checkBox.setForeground(RmsColors.text());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) applyTextTheme(child);
        }
    }

    private static final class RoundedDialogSurface extends JPanel {
        private RoundedDialogSurface() {
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, 18, 18);
            g.setColor(RmsColors.surface());
            g.fill(shape);
            g.setColor(RmsColors.border());
            g.draw(shape);
            g.dispose();
        }

        @Override protected void paintChildren(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.clip(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 17, 17));
            super.paintChildren(g);
            g.dispose();
        }
    }

    private static final class RoundedTextField extends JTextField {
        private RoundedTextField(String value) {
            super(value);
        }

        @Override protected void paintComponent(Graphics graphics) {
            paintRoundedInput(graphics, this);
        }
    }

    private static final class RoundedPasswordField extends JPasswordField {
        private RoundedPasswordField(int columns) {
            super(columns);
        }

        @Override protected void paintComponent(Graphics graphics) {
            paintRoundedInput(graphics, this);
        }
    }

    private static void paintRoundedInput(Graphics graphics, javax.swing.text.JTextComponent component) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                component.getWidth() - 1f, component.getHeight() - 1f, 12, 12);
        g.setColor(RmsColors.surface());
        g.fill(shape);
        g.clip(shape);
        component.getUI().paint(g, component);
        g.dispose();
    }

    private static final class RoundedComboBox<E> extends JComboBox<E> {
        private RoundedComboBox(E[] values) {
            super(values);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, 12, 12);
            g.setColor(RmsColors.surface());
            g.fill(shape);
            g.clip(shape);
            super.paintComponent(g);
            g.dispose();
        }
    }

    private static final class RoundedButton extends JButton {
        private final boolean primary;

        private RoundedButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, 12, 12);
            Color fill = primary ? RmsColors.primary() : RmsColors.surface();
            if (getModel().isPressed()) {
                Color pressed = UIManager.getColor("Button.pressedBackground");
                if (pressed != null) fill = pressed;
            } else if (getModel().isRollover()) {
                Color hover = UIManager.getColor("Button.hoverBackground");
                if (hover != null) fill = hover;
            }
            g.setColor(isEnabled() ? fill : RmsColors.border());
            g.fill(shape);
            g.clip(shape);
            super.paintComponent(g);
            g.dispose();
        }

        @Override protected void paintBorder(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(hasFocus() ? 2f : 1f));
            g.setColor(hasFocus() ? RmsColors.primaryAccent()
                    : primary ? RmsColors.primary() : RmsColors.border());
            g.draw(new RoundRectangle2D.Float(1, 1,
                    getWidth() - 2f, getHeight() - 2f, 12, 12));
            g.dispose();
        }
    }

    private static final class RoundedControlBorder extends AbstractBorder {
        private final int radius;

        private RoundedControlBorder(int radius) {
            this.radius = radius;
        }

        @Override public Insets getBorderInsets(Component component) {
            return new Insets(1, 1, 1, 1);
        }

        @Override public void paintBorder(Component component, Graphics graphics, int x, int y,
                                          int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(component.hasFocus() ? 2f : 1f));
            Color focus = UIManager.getColor("Component.focusColor");
            g.setColor(component.hasFocus() && focus != null ? focus : RmsColors.border());
            g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f,
                    width - 1f, height - 1f, radius, radius));
            g.dispose();
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    JCheckBox cloudConsentCheckBox() { return cloudConsent; }
    JCheckBox confirmationCheckBox() { return confirmation; }
    JComboBox<DuckyPermission> permissionSelector() { return permission; }
    JTextField modelField() { return model; }
    JPasswordField apiKeyField() { return apiKey; }
    JButton saveButton() { return save; }
    JButton cancelButton() { return cancel; }
    JButton testButton() { return test; }
    JButton removeKeyButton() { return removeKey; }
    JLabel statusLabel() { return status; }
    JLabel credentialStatusLabel() { return credentialStatus; }
}
