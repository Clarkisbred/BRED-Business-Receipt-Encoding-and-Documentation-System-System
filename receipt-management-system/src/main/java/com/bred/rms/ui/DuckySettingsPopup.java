package com.bred.rms.ui;

import com.bred.rms.ai.DuckyPermission;
import com.bred.rms.ai.DuckyPreferences;
import com.bred.rms.ai.GroqChatClient;
import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creator-only application dialog for local or shared Ducky AI settings. */
final class DuckySettingsPopup extends JDialog {
    private static final int CONTENT_WIDTH = 390;

    private final GroqCredentials localCredentials;
    private final RestClient sharedBackend;
    private final Runnable onSave;

    private final JTextField model = new JTextField(DuckyPreferences.model());
    private final JPasswordField apiKey = new JPasswordField(26);
    private final JComboBox<DuckyPermission> permission =
            new JComboBox<>(DuckyPermission.values());
    private final JCheckBox cloudConsent = new JCheckBox("I consent to enabling Groq cloud AI");
    private final JCheckBox confirmation = new JCheckBox("I understand prompts are processed outside BRED");
    private final JButton test = new JButton("Test connection");
    private final JButton removeKey = new JButton("Remove saved key");
    private final JButton save = new JButton("Save settings");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel status = new JLabel(" ");
    private final JLabel credentialStatus = new JLabel(" ");
    private final boolean creatorOnly;

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

        setName("duckySettingsDialog");
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        configureComponents();
        setContentPane(buildContent());
        pack();
        fitAndCenter(owner);
        configureActions();
        if (sharedBackend != null) loadSharedStatus();
        else loadLocalStatus();
    }

    private JPanel buildContent() {
        JPanel content = new JPanel();
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
        content.add(new javax.swing.JSeparator());
        content.add(gap(10));

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        JPanel actionButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 7, 0));
        actionButtons.setOpaque(false);
        actionButtons.add(cancel);
        actionButtons.add(save);
        actions.add(actionButtons, BorderLayout.EAST);
        content.add(actions);

        JPanel surface = new JPanel(new BorderLayout());
        surface.setOpaque(false);
        surface.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setName("duckySettingsScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(480, 590));
        surface.add(scroll, BorderLayout.CENTER);
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
        status.setText("connected".equals(serviceStatus) ? "Service status: Connected"
                : "not_configured".equals(serviceStatus) ? "Service status: Not configured"
                        : "Service status: " + (serviceStatus.equals("connection_failed")
                                ? "Connection failed" : "Service unavailable"));
        test.setEnabled(true);
        save.setEnabled(true);
    }

    private void setSharedLoadError(Throwable error) {
        credentialStatus.setText("Could not verify server-side credential status.");
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
                        setStatus("connected".equals(statusValue)
                                ? "Connected to Groq using " + model.getText().trim() + "."
                                : "connection_failed".equals(statusValue)
                                        ? "Groq connection failed."
                                        : "Ducky AI service is unavailable.");
                    }),
                    error -> SwingUtilities.invokeLater(() -> {
                        test.setEnabled(false);
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
                Math.min(480, Math.max(320, bounds.width - 80)),
                Math.min(590, Math.max(320, bounds.height - 80)));
        Dimension current = getPreferredSize();
        setSize(Math.min(current.width, screenFit.width),
                Math.min(current.height, screenFit.height));
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
        panel.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
        panel.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
        panel.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
        return panel;
    }

    private static void styleField(JTextField field) {
        field.setForeground(RmsColors.text());
        field.setBackground(RmsColors.surface());
        field.setCaretColor(RmsColors.primary());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border(), 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(CONTENT_WIDTH, 38));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(RmsColors.text());
        combo.setBackground(RmsColors.surface());
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
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(primary ? RmsColors.primary() : RmsColors.surface());
        button.setForeground(primary ? RmsColors.primaryForeground() : RmsColors.text());
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? RmsColors.primary() : RmsColors.border(), 1, true),
                BorderFactory.createEmptyBorder(7, 11, 7, 11)));
    }

    private static String safeBackendError(Throwable error) {
        if (error instanceof SupabaseException exception) {
            if (exception.status() == 401) return "Your session has expired. Please sign in again.";
            if (exception.status() == 403) return "Only the Creator can manage Ducky AI settings.";
            if (exception.status() == 404) return "The shared Ducky AI service is not deployed.";
            if (exception.status() == 429) return "Ducky is busy. Please wait briefly and try again.";
            if (exception.status() == 0) return "Could not reach the Ducky AI service.";
            if (exception.status() >= 500) return "Ducky AI is temporarily unavailable.";
        }
        return "Could not contact the Ducky AI service.";
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
