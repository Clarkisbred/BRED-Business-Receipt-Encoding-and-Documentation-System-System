
package com.bred.rms.ui;

import com.bred.rms.ai.DuckyPermission;
import com.bred.rms.ai.DuckyPreferences;
import com.bred.rms.ai.GroqChatClient;
import com.bred.rms.ai.GroqCredentials;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Modern Groq connection, privacy, and permission settings for Ducky. */
final class DuckySettingsPopup extends JPopupMenu {

    private static final int POPUP_WIDTH = 440;
    private static final int POPUP_HEIGHT = 500;
    private static final int CONTENT_WIDTH = 390;

    private final GroqCredentials credentials;
    private final Runnable onSave;

    private final JTextField model =
            new JTextField(DuckyPreferences.model());

    private final JPasswordField apiKey =
            new JPasswordField(26);

    private final JComboBox<DuckyPermission> permission =
            new JComboBox<>(DuckyPermission.values());

    private final JCheckBox cloudConsent =
            new JCheckBox("I consent to enabling Groq cloud AI");

    private final JCheckBox confirmation =
            new JCheckBox("I understand prompts are processed outside BRED");

    private final JButton test =
            new JButton("Test connection");

    private final JButton removeKey =
            new JButton("Remove saved key");

    private final JButton save =
            new JButton("Save settings");

    private final JButton cancel =
            new JButton("Cancel");

    private final JLabel status = new JLabel(" ");

    private final JLabel credentialStatus = new JLabel(" ");

    DuckySettingsPopup(
            GroqCredentials credentials,
            Runnable onSave
    ) {
        this.credentials = credentials;
        this.onSave = onSave;

        setName("duckySettingsPopup");
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());

        configureComponents();

        RoundedPanel content = new RoundedPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(
                BorderFactory.createEmptyBorder(18, 20, 18, 20)
        );

        content.add(sectionHeading("AI PROVIDER"));
        content.add(gap(5));
        content.add(description(
                "Groq provides the cloud AI service used by Ducky."
        ));

        content.add(gap(14));
        content.add(sectionHeading("MODEL"));
        content.add(gap(6));
        content.add(model);

        content.add(gap(13));
        content.add(sectionHeading("API KEY"));
        content.add(gap(6));
        content.add(apiKey);
        content.add(gap(5));
        content.add(wrappedLabel(credentialStatus));

        content.add(gap(10));

        JPanel connectionButtons = new JPanel(
                new FlowLayout(FlowLayout.LEFT, 7, 0)
        );
        connectionButtons.setOpaque(false);
        connectionButtons.setAlignmentX(LEFT_ALIGNMENT);
        connectionButtons.add(test);
        connectionButtons.add(removeKey);

        content.add(connectionButtons);
        content.add(gap(5));
        content.add(wrappedLabel(status));

        content.add(gap(16));
        content.add(sectionHeading("AGENT PERMISSION"));
        content.add(gap(6));
        content.add(permission);
        content.add(gap(5));
        content.add(description(
                "Ask: no BRED tools. Assist: authorized read-only tools. "
                + "Execute is reserved for future development."
        ));

        content.add(gap(16));
        content.add(sectionHeading("CLOUD PRIVACY"));
        content.add(gap(6));
        content.add(description(
                "Ducky uses Groq cloud AI. Prompts are processed externally. "
                + "Uploaded images and OCR text stay local unless you separately "
                + "approve sharing. BRED record results, supplier information, "
                + "and financial details are not sent without confirmation "
                + "for the exact result. Enabling Groq does not grant automatic "
                + "access to your records."
        ));

        content.add(gap(10));
        content.add(cloudConsent);
        content.add(gap(5));
        content.add(confirmation);

        content.add(gap(17));
        content.add(new javax.swing.JSeparator());
        content.add(gap(10));

        JPanel actions = new JPanel(
                new BorderLayout()
        );
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 40)
        );

        JPanel actionButtons = new JPanel(
                new FlowLayout(FlowLayout.RIGHT, 7, 0)
        );
        actionButtons.setOpaque(false);
        actionButtons.add(cancel);
        actionButtons.add(save);

        actions.add(actionButtons, BorderLayout.EAST);
        content.add(actions);

        JScrollPane scroll = new JScrollPane(content);

        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        // The horizontal scrollbar is deliberately disabled.
        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );

        scroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        );

        scroll.getVerticalScrollBar().setUnitIncrement(16);

        scroll.setPreferredSize(
                new Dimension(POPUP_WIDTH, POPUP_HEIGHT)
        );

        add(scroll, BorderLayout.CENTER);

        if (credentials.environmentConfigured()) {
            apiKey.setEnabled(false);
            removeKey.setEnabled(false);
        }

        credentialStatus.setText(
                credentials.environmentConfigured()
                        ? "Using GROQ_API_KEY from the development environment."
                        : storedKeyStatus()
        );

        confirmation.setSelected(false);
        configurePermissionRenderer();
        configureActions();
        updateSaveState();

        setPreferredSize(
                new Dimension(POPUP_WIDTH, POPUP_HEIGHT)
        );
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

        apiKey.setEchoChar('\u2022');

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
        credentialStatus.setFont(
                credentialStatus.getFont().deriveFont(11f)
        );

        model.setText(DuckyPreferences.model());
    }

    private void configurePermissionRenderer() {
        permission.setSelectedItem(
                DuckyPreferences.permission()
        );

        permission.setRenderer((list, value, index, selected, focused) -> {
            String text;

            if (value == DuckyPermission.EXECUTE) {
                text = "Execute — not available";
            } else {
                String name = value.name().toLowerCase(
                        java.util.Locale.ROOT
                );

                text = Character.toUpperCase(name.charAt(0))
                        + name.substring(1);
            }

            JLabel label = new JLabel(text);
            label.setBorder(
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)
            );

            label.setOpaque(selected);
            label.setForeground(
                    selected
                            ? list.getSelectionForeground()
                            : RmsColors.text()
            );

            if (selected) {
                label.setBackground(list.getSelectionBackground());
            }

            return label;
        });
    }

    private void configureActions() {
        cloudConsent.addActionListener(event -> updateSaveState());
        confirmation.addActionListener(event -> updateSaveState());

        permission.addActionListener(event -> {
            if (permission.getSelectedItem() == DuckyPermission.EXECUTE) {
                permission.setSelectedItem(DuckyPermission.ASK);
                setStatus(
                        "Execute mode is reserved for future development."
                );
            }
        });

        cancel.addActionListener(event -> setVisible(false));
        save.addActionListener(event -> saveSettings());
        removeKey.addActionListener(event -> removeCredential());
        test.addActionListener(event -> testConnection());
    }

    private void saveSettings() {
        char[] enteredKey = apiKey.getPassword();

        try {
            String selectedModel = model.getText() == null
                    ? ""
                    : model.getText().trim();

            if (selectedModel.isBlank() || selectedModel.length() > 120) {
                throw new IllegalArgumentException(
                        "Enter a valid Groq model name (up to 120 characters)."
                );
            }

            if (cloudConsent.isSelected() && !confirmation.isSelected()) {
                throw new IllegalArgumentException(
                        "Confirm the cloud privacy notice before enabling Groq."
                );
            }

            if (enteredKey.length > 0) {
                credentials.save(enteredKey);
            }

            DuckyPreferences.setModel(selectedModel);
            DuckyPreferences.setCloudConsent(
                    cloudConsent.isSelected()
            );

            if (cloudConsent.isSelected()) {
                DuckyPreferences.setPermission(
                        (DuckyPermission) permission.getSelectedItem()
                );
            }

            credentialStatus.setText(
                    credentials.environmentConfigured()
                            ? "Using GROQ_API_KEY from the development environment."
                            : storedKeyStatus()
            );

            onSave.run();
            setVisible(false);

        } catch (RuntimeException error) {
            setStatus(
                    error.getMessage() == null
                            ? "Ducky settings could not be saved."
                            : error.getMessage()
            );

        } finally {
            Arrays.fill(enteredKey, '\0');
        }
    }

    private void removeCredential() {
        try {
            credentials.removeSaved();
            apiKey.setText("");

            credentialStatus.setText(
                    "Key is not saved. Enter a key and save it securely."
            );

            setStatus("Saved Groq API key removed.");

        } catch (RuntimeException error) {
            setStatus(
                    error.getMessage() == null
                            ? "Saved Groq key could not be removed."
                            : error.getMessage()
            );
        }
    }

    private void testConnection() {
        char[] enteredKey = apiKey.getPassword();

        char[] key = enteredKey.length == 0
                ? credentials.read()
                : enteredKey.clone();

        Arrays.fill(enteredKey, '\0');

        if (key == null || key.length == 0) {
            setStatus("Enter or configure a Groq API key first.");
            return;
        }

        String selectedModel = model.getText() == null
                ? ""
                : model.getText().trim();

        if (selectedModel.isBlank()) {
            Arrays.fill(key, '\0');
            setStatus("Enter a Groq model name before testing.");
            return;
        }

        test.setEnabled(false);
        setStatus("Testing Groq connection...");

        BackgroundTask.run(
                () -> {
                    try (GroqChatClient client = new GroqChatClient(
                            selectedModel,
                            key,
                            Duration.ofSeconds(30)
                    )) {
                        GroqChatClient.Completion result =
                                client.complete(
                                        List.of(
                                                GroqChatClient.Message.text(
                                                        "user",
                                                        "Reply with OK."
                                                )
                                        ),
                                        List.of()
                                );

                        if (result.content() == null
                                || result.content().isBlank()) {
                            throw new IllegalStateException(
                                    "Groq returned an empty response."
                            );
                        }

                        return "Connected to Groq using "
                                + selectedModel + ".";

                    } finally {
                        Arrays.fill(key, '\0');
                    }
                },
                message -> {
                    test.setEnabled(true);
                    setStatus(message);
                },
                error -> {
                    test.setEnabled(true);

                    String message = error.getMessage();

                    setStatus(
                            message == null
                                    ? "Groq connection test failed."
                                    : message
                    );
                }
        );
    }

    private String storedKeyStatus() {
        try {
            return credentials.credentialSaved()
                    ? "A key is saved in Windows Credential Manager. It is never displayed."
                    : "Key is not saved. Enter a key and save it securely.";

        } catch (RuntimeException error) {
            return "Credential Manager is unavailable. Configure GROQ_API_KEY for development.";
        }
    }

    private void updateSaveState() {
        boolean consent = cloudConsent.isSelected();

        permission.setEnabled(consent);
        confirmation.setEnabled(consent);

        save.setEnabled(
                !consent || confirmation.isSelected()
        );
    }

    private void setStatus(String message) {
        status.setText(
                "<html><body style='width:350px'>"
                        + escapeHtml(message)
                        + "</body></html>"
        );
    }

    JCheckBox cloudConsentCheckBox() {
        return cloudConsent;
    }

    JCheckBox confirmationCheckBox() {
        return confirmation;
    }

    JComboBox<DuckyPermission> permissionSelector() {
        return permission;
    }

    JTextField modelField() {
        return model;
    }

    JPasswordField apiKeyField() {
        return apiKey;
    }

    JButton saveButton() {
        return save;
    }

    JButton cancelButton() {
        return cancel;
    }

    JButton testButton() {
        return test;
    }

    JButton removeKeyButton() {
        return removeKey;
    }

    JLabel statusLabel() {
        return status;
    }

    JLabel credentialStatusLabel() {
        return credentialStatus;
    }

    private static JLabel sectionHeading(String text) {
        JLabel label = new JLabel(text);

        label.setForeground(RmsColors.text());
        label.setFont(
                label.getFont().deriveFont(Font.BOLD, 11f)
        );

        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 22)
        );

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
        area.setMaximumSize(
                new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE)
        );

        return area;
    }

    private static JLabel wrappedLabel(JLabel label) {
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 38)
        );
        return label;
    }

    private static ComponentGap gap(int height) {
        return new ComponentGap(height);
    }

    private static void styleField(JTextField field) {
        field.setForeground(RmsColors.text());
        field.setBackground(RmsColors.surface());
        field.setCaretColor(RmsColors.primary());

        field.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                                RmsColors.border(),
                                1,
                                true
                        ),
                        BorderFactory.createEmptyBorder(7, 10, 7, 10)
                )
        );

        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 38)
        );
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(RmsColors.text());
        combo.setBackground(RmsColors.surface());
        combo.setAlignmentX(LEFT_ALIGNMENT);
        combo.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 38)
        );
    }

    private static void styleCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(RmsColors.text());
        checkBox.setFont(
                checkBox.getFont().deriveFont(11f)
        );

        checkBox.setAlignmentX(LEFT_ALIGNMENT);
        checkBox.setMaximumSize(
                new Dimension(CONTENT_WIDTH, 28)
        );
    }

    private static void styleButton(
            JButton button,
            boolean primary
    ) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(
                primary
                        ? RmsColors.primary()
                        : RmsColors.surface()
        );

        button.setForeground(
                primary
                        ? RmsColors.primaryForeground()
                        : RmsColors.text()
        );

        button.setFont(
                button.getFont().deriveFont(Font.BOLD, 11f)
        );

        button.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                                primary
                                        ? RmsColors.primary()
                                        : RmsColors.border(),
                                1,
                                true
                        ),
                        BorderFactory.createEmptyBorder(7, 11, 7, 11)
                )
        );
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static final class ComponentGap extends JPanel {
        private ComponentGap(int height) {
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(CONTENT_WIDTH, height));
            setPreferredSize(new Dimension(CONTENT_WIDTH, height));
            setMinimumSize(new Dimension(CONTENT_WIDTH, height));
        }
    }

    private static final class RoundedPanel extends JPanel {

        private RoundedPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    0.5f,
                    0.5f,
                    getWidth() - 1f,
                    getHeight() - 1f,
                    22,
                    22
            );

            g.setColor(RmsColors.surface());
            g.fill(shape);

            g.setColor(RmsColors.border());
            g.draw(shape);

            g.dispose();

            super.paintComponent(graphics);
        }
    }
}
