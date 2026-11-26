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
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Duration;
import java.util.List;

/** Groq connection, privacy, and permission settings for Ducky. */
final class DuckySettingsPopup extends JPopupMenu {
    private final GroqCredentials credentials;
    private final Runnable onSave;
    private final JTextField model = new JTextField(DuckyPreferences.model());
    private final JPasswordField apiKey = new JPasswordField(30);
    private final JComboBox<DuckyPermission> permission = new JComboBox<>(DuckyPermission.values());
    private final JCheckBox cloudConsent = new JCheckBox("I consent to enabling Groq cloud AI");
    private final JCheckBox confirmation = new JCheckBox("I understand prompts are processed outside BRED");
    private final JButton test = new JButton("Test connection");
    private final JButton removeKey = new JButton("Remove saved key");
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel status = new JLabel();
    private final JLabel credentialStatus = new JLabel();

    DuckySettingsPopup(GroqCredentials credentials, Runnable onSave) {
        this.credentials = credentials;
        this.onSave = onSave;
        model.setName("groqModel");
        apiKey.setName("groqApiKey");
        apiKey.setEchoChar('\u2022');
        permission.setName("duckyPermission");
        cloudConsent.setName("duckyCloudConsent");
        confirmation.setName("duckyCloudConfirmation");
        test.setName("groqTestConnection");
        removeKey.setName("groqRemoveKey");
        save.setName("duckySaveSettings");
        cancel.setName("duckyCancelSettings");
        status.setName("groqConnectionStatus");
        credentialStatus.setName("groqCredentialStatus");
        cloudConsent.setSelected(DuckyPreferences.cloudConsent());
        permission.setSelectedItem(DuckyPreferences.permission());
        permission.setRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel(value == DuckyPermission.EXECUTE
                    ? "Execute — not yet available" : value.name().substring(0, 1)
                    + value.name().substring(1).toLowerCase(java.util.Locale.ROOT));
            if (selected) {
                label.setOpaque(true);
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            return label;
        });

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(heading("AI Provider"));
        content.add(new JLabel("Groq · OpenAI-compatible chat completions"));
        content.add(Box.createVerticalStrut(8));
        content.add(heading("Model"));
        content.add(model);
        content.add(Box.createVerticalStrut(8));
        content.add(heading("API key"));
        content.add(apiKey);
        credentialStatus.setText(credentials.environmentConfigured()
                ? "Using GROQ_API_KEY from the development environment."
                : storedKeyStatus());
        content.add(credentialStatus);
        JPanel connection = new JPanel(new BorderLayout(8, 0));
        connection.setOpaque(false);
        connection.add(test, BorderLayout.WEST);
        connection.add(removeKey, BorderLayout.EAST);
        content.add(Box.createVerticalStrut(6));
        content.add(connection);
        content.add(status);
        content.add(Box.createVerticalStrut(10));
        content.add(heading("Agent permission"));
        content.add(permission);
        content.add(new JLabel("Ask: no BRED tools. Assist: authorized read-only tools. Execute: reserved."));
        content.add(Box.createVerticalStrut(10));
        content.add(heading("Cloud privacy"));
        JTextArea notice = description("Ducky uses Groq cloud AI. Prompts are processed externally. "
                + "Uploaded images and OCR text stay local unless you separately approve sharing. "
                + "BRED record results, supplier information, and financial details are never sent without "
                + "confirmation for the exact result. Enabling Groq does not grant automatic record access.");
        content.add(notice);
        content.add(cloudConsent);
        content.add(confirmation);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.add(cancel);
        right.add(save);
        actions.add(right, BorderLayout.EAST);
        content.add(Box.createVerticalStrut(8));
        content.add(actions);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(435, 460));
        add(scroll);

        if (credentials.environmentConfigured()) {
            apiKey.setEnabled(false);
            removeKey.setEnabled(false);
        }
        confirmation.setSelected(false);
        cloudConsent.addActionListener(event -> updateSaveState());
        confirmation.addActionListener(event -> updateSaveState());
        permission.addActionListener(event -> {
            if (permission.getSelectedItem() == DuckyPermission.EXECUTE) {
                permission.setSelectedItem(DuckyPermission.ASK);
                status.setText("Execute mode is reserved for future development.");
            }
        });
        cancel.addActionListener(event -> setVisible(false));
        save.addActionListener(event -> saveSettings());
        removeKey.addActionListener(event -> removeCredential());
        test.addActionListener(event -> testConnection());
        updateSaveState();
    }

    private void saveSettings() {
        char[] enteredKey = apiKey.getPassword();
        try {
            if (model.getText() == null || model.getText().isBlank() || model.getText().trim().length() > 120) {
                throw new IllegalArgumentException("Choose a Groq model name up to 120 characters.");
            }
            if (enteredKey.length > 0) credentials.save(enteredKey);
            DuckyPreferences.setModel(model.getText());
            DuckyPreferences.setCloudConsent(cloudConsent.isSelected());
            if (cloudConsent.isSelected()) {
                DuckyPreferences.setPermission((DuckyPermission) permission.getSelectedItem());
            }
            credentialStatus.setText(credentials.environmentConfigured()
                    ? "Using GROQ_API_KEY from the development environment."
                    : storedKeyStatus());
            onSave.run();
            setVisible(false);
        } catch (RuntimeException error) {
            status.setText(error.getMessage() == null ? "Ducky settings could not be saved." : error.getMessage());
        } finally {
            java.util.Arrays.fill(enteredKey, '\0');
        }
    }

    private void removeCredential() {
        try {
            credentials.removeSaved();
            apiKey.setText("");
            credentialStatus.setText("Key is not saved. Enter a key and save it securely.");
            status.setText("Saved Groq API key removed.");
        } catch (RuntimeException error) {
            status.setText(error.getMessage() == null ? "Saved Groq key could not be removed." : error.getMessage());
        }
    }

    private void testConnection() {
        char[] enteredKey = apiKey.getPassword();
        char[] key = enteredKey.length == 0 ? credentials.read() : enteredKey.clone();
        java.util.Arrays.fill(enteredKey, '\0');
        if (key == null || key.length == 0) {
            status.setText("Enter or configure a Groq API key first.");
            return;
        }
        String selectedModel = model.getText().trim();
        test.setEnabled(false);
        status.setText("Testing Groq connection…");
        BackgroundTask.run(() -> {
            try (GroqChatClient client = new GroqChatClient(selectedModel, key, Duration.ofSeconds(30))) {
                GroqChatClient.Completion result = client.complete(
                        List.of(GroqChatClient.Message.text("user", "Reply with OK.")), List.of());
                if (result.content() == null || result.content().isBlank()) {
                    throw new IllegalStateException("Groq returned an empty connection-test response.");
                }
                return "Connected to Groq with model " + selectedModel + ".";
            } finally {
                java.util.Arrays.fill(key, '\0');
            }
        }, message -> {
            test.setEnabled(true);
            status.setText(message);
        }, error -> {
            test.setEnabled(true);
            status.setText(error.getMessage() == null ? "Groq connection test failed." : error.getMessage());
        });
    }

    private String storedKeyStatus() {
        try {
            return credentials.credentialSaved()
                    ? "A key is saved in Windows Credential Manager. It is never displayed."
                    : "Key is not saved. Enter a key and save it securely.";
        } catch (RuntimeException error) {
            return "Credential Manager is unavailable; configure GROQ_API_KEY for development.";
        }
    }

    private void updateSaveState() {
        save.setEnabled(!cloudConsent.isSelected() || confirmation.isSelected());
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

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(RmsColors.text());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JTextArea description(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(RmsColors.SECONDARY_TEXT);
        area.setFont(area.getFont().deriveFont(11f));
        area.setRows(5);
        return area;
    }
}
