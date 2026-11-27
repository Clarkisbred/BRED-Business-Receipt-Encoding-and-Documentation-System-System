package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.config.AppVersion;
import com.bred.rms.models.BugReport;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.ui.components.UserAvatar;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import com.bred.rms.updates.GitHubReleaseClient;
import com.bred.rms.updates.UpdateCheckException;
import com.bred.rms.updates.UpdateChecker;
import com.bred.rms.updates.UpdateDownloadManager;
import com.bred.rms.updates.UpdateDownloadException;
import com.bred.rms.updates.UpdateInfo;
import com.bred.rms.updates.UpdateMode;
import com.bred.rms.updates.UpdatePreferences;
import com.bred.rms.updates.UpdateWorkflow;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JColorChooser;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.imageio.ImageIO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import javax.swing.border.AbstractBorder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Account details, appearance options and "change my password". */
public final class SettingsPanel extends JPanel {
    private final JPasswordField newPassword = new JPasswordField(20);
    private final JPasswordField confirm = new JPasswordField(20);
    private final JLabel message = new JLabel(" ");
    private final JButton change = new JButton("Change password");
    private final JButton importLocal = new JButton("Import local data into Supabase");
    private final JButton importExcel = new JButton("Import receipts from Excel");
    private final JButton excelTemplate = new JButton("Create Excel template");
    private final JLabel importMessage = new JLabel(" ");
    private final JTextField reportTitle = new JTextField(42);
    private final JTextArea reportDescription = new JTextArea(5, 42);
    private final JLabel reportSubtitle = new JLabel("Tell us what went wrong. A screenshot can help us fix it faster.");
    private final JLabel imageName = new JLabel("No image selected");
    private final JButton attachImage = new RoundedReportButton("Attach image");
    private final RoundedReportSubmitButton submitReport = new RoundedReportSubmitButton("Submit bug report");
    private final JLabel reportMessage = new JLabel(" ");
    private final JButton viewImage = new JButton("View attached image");
    private final DefaultListModel<BugReport> reports = new DefaultListModel<>();
    private final JList<BugReport> reportList = new JList<>(reports);
    private final JTextArea reportDetails = new JTextArea(5, 42);
    private final JLabel releaseVersion = new JLabel(" ");
    private final JLabel releaseMessage = new JLabel(" ");
    private final JTextField releaseInput = new JTextField(16);
    private final JButton publishRelease = new JButton("Update version");
    private final UpdatePreferences updatePreferences;
    private final Supplier<CompletableFuture<Optional<UpdateInfo>>> updateCheck;
    private final JLabel installedVersion = new JLabel("Current Version: " + AppVersion.current());
    private final JLabel updateStatus = new JLabel(" ");
    private final JLabel lastChecked = new JLabel(" ");
    private final JRadioButton askBeforeInstalling = new JRadioButton("Ask Before Installing (Recommended)");
    private final JRadioButton automaticUpdates = new JRadioButton("Automatic Updates");
    private final JCheckBox automaticallyCheck = new JCheckBox("Automatically Check for Updates");
    private final JButton checkForUpdates = new JButton("Check for Updates");
    private final JLabel downloadProgressLabel = new JLabel(" ");
    private final JProgressBar downloadProgressBar = new JProgressBar(0, 100);
    private final JButton cancelDownload = new JButton("Cancel download");
    private final UpdateWorkflow updateWorkflow;
    private UpdateDownloadManager.ProgressSubscription downloadProgressSubscription;
    private boolean updateStatusError;
    private final Consumer<Boolean> themeListener = dark -> applyTheme();
    private Path selectedImage;
    private JPanel themePreview;

    /** Builds the settings screen for the signed-in user. */
    public SettingsPanel(AppContext ctx) {
        this(ctx, new UpdatePreferences(), null, null);
    }

    SettingsPanel(AppContext ctx, UpdatePreferences updatePreferences,
                  Supplier<CompletableFuture<Optional<UpdateInfo>>> updateCheck) {
        this(ctx, updatePreferences, updateCheck, null);
    }

    SettingsPanel(AppContext ctx, UpdatePreferences updatePreferences,
                  Supplier<CompletableFuture<Optional<UpdateInfo>>> updateCheck, UpdateWorkflow updateWorkflow) {
        this.updatePreferences = Objects.requireNonNull(updatePreferences, "updatePreferences");
        this.updateWorkflow = updateWorkflow;
        if (updateWorkflow != null) {
            this.updateCheck = updateWorkflow::checkManually;
        } else if (updateCheck != null) {
            this.updateCheck = updateCheck;
        } else {
            this.updateCheck = new UpdateChecker(AppVersion.current(), new GitHubReleaseClient(),
                    this.updatePreferences)::checkForUpdate;
        }
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        User u = ctx.currentUser.get();
        add(heading("Account"));
        add(line(u == null ? "Not signed in" : u.fullName() + " · " + u.email() + " · " + u.role().label()));
        if (u != null && !u.companyName().isBlank()) add(line("Company: " + u.companyName()));
        add(line("Data stored in: cloud database (Supabase)"));
        if (ctx.localDataImporter != null && u != null && u.role().canManageCompany()) {
            add(Box.createVerticalStrut(12));
            add(heading("Move existing local data to Supabase"));
            add(line("Import a local BRED data folder, or import receipts and line items from an Excel workbook."));
            add(line("Local passwords cannot be transferred; recreate user accounts in Supabase."));
            importLocal.setAlignmentX(LEFT_ALIGNMENT);
            importLocal.addActionListener(e -> chooseLocalDataDirectory(ctx));
            add(importLocal);
            importExcel.setAlignmentX(LEFT_ALIGNMENT);
            importExcel.addActionListener(e -> chooseExcelFile(ctx));
            add(Box.createVerticalStrut(6));
            add(importExcel);
            excelTemplate.setAlignmentX(LEFT_ALIGNMENT);
            excelTemplate.addActionListener(e -> saveExcelTemplate());
            add(Box.createVerticalStrut(6));
            add(excelTemplate);
            importMessage.setAlignmentX(LEFT_ALIGNMENT);
            importMessage.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            add(importMessage);
        }
        add(Box.createVerticalStrut(20));
        add(heading("Appearance"));
        JButton theme = new JButton(ThemeManager.isDark() ? "Switch to light mode" : "Switch to dark mode");
        theme.addActionListener(e -> {
            ThemeManager.toggle();
            theme.setText(ThemeManager.isDark() ? "Switch to light mode" : "Switch to dark mode");
        });
        theme.setAlignmentX(LEFT_ALIGNMENT);
        add(theme);
        add(Box.createVerticalStrut(8));
        add(heading("Other Appearances"));
        JComboBox<ThemeManager.Appearance> appearances =
                new JComboBox<>(ThemeManager.Appearance.values());
        appearances.setSelectedItem(ThemeManager.appearance());
        appearances.setAlignmentX(LEFT_ALIGNMENT);
        Dimension appearanceSize = appearances.getPreferredSize();
        appearances.setMaximumSize(appearanceSize);
        appearances.addActionListener(e ->
                {
                    ThemeManager.Appearance selected =
                            (ThemeManager.Appearance) appearances.getSelectedItem();
                    ThemeManager.setAppearance(selected);
                    if (selected == ThemeManager.Appearance.CLEAN_LIGHT
                            && ThemeManager.isDark()) ThemeManager.apply(false);
                });
        add(appearances);
        JButton accent = new JButton("Choose accent color");
        accent.setAlignmentX(LEFT_ALIGNMENT);
        accent.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(SwingUtilities.getWindowAncestor(this),
                    "Choose BRED accent", ThemeManager.customAccent() == null
                            ? RmsColors.primaryAccent() : ThemeManager.customAccent());
            if (chosen != null) ThemeManager.setCustomAccent(chosen);
        });
        add(Box.createVerticalStrut(8));
        add(accent);
        JButton resetAccent = new JButton("Use theme accent");
        resetAccent.setAlignmentX(LEFT_ALIGNMENT);
        resetAccent.addActionListener(e -> ThemeManager.clearCustomAccent());
        add(Box.createVerticalStrut(6));
        add(resetAccent);
        themePreview = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        themePreview.setName("themeLivePreview");
        themePreview.setAlignmentX(LEFT_ALIGNMENT);
        themePreview.setBorder(BorderFactory.createTitledBorder("Live theme preview"));
        themePreview.add(new JButton("Button"));
        JLabel accentPreview = new JLabel("Accent");
        accentPreview.setName("themeAccentPreview");
        accentPreview.setOpaque(true);
        accentPreview.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        themePreview.add(accentPreview);
        add(themePreview);
        add(Box.createVerticalStrut(20));
        addUpdateSection();
        add(Box.createVerticalStrut(20));
        add(heading("Change my password"));
        add(labelled("New password (min. 6)", newPassword));
        add(labelled("Repeat new password", confirm));
        change.setAlignmentX(LEFT_ALIGNMENT);
        change.addActionListener(e -> submit(ctx));
        add(change);
        message.setAlignmentX(LEFT_ALIGNMENT);
        message.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        add(message);
        add(Box.createVerticalStrut(24));
        addContacts(ctx, u);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        ThemeManager.addThemeListener(themeListener);
        applyTheme();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ThemeManager.addThemeListener(themeListener);
        if (updateWorkflow != null && downloadProgressSubscription == null) {
            downloadProgressSubscription = updateWorkflow.addProgressListener(progress ->
                    javax.swing.SwingUtilities.invokeLater(() -> showDownloadProgress(progress)));
        }
        applyTheme();
    }

    @Override
    public void removeNotify() {
        ThemeManager.removeThemeListener(themeListener);
        if (downloadProgressSubscription != null) {
            downloadProgressSubscription.close();
            downloadProgressSubscription = null;
        }
        super.removeNotify();
    }

    private void applyTheme() {
        applyTextTheme(this);
        if (themePreview != null) {
            themePreview.setBackground(RmsColors.surface());
            themePreview.setForeground(RmsColors.text());
            for (Component child : themePreview.getComponents()) {
                if ("themeAccentPreview".equals(child.getName())) {
                    JLabel accentPreview = (JLabel) child;
                    accentPreview.setBackground(RmsColors.primaryAccent());
                    accentPreview.setForeground(RmsColors.primaryForeground());
                }
            }
        }
        reportSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        imageName.setForeground(RmsColors.SECONDARY_TEXT);
        releaseMessage.setForeground(RmsColors.SECONDARY_TEXT);
        releaseInput.setForeground(RmsColors.text());
        releaseInput.setCaretColor(RmsColors.primary());
        installedVersion.setForeground(RmsColors.text());
        lastChecked.setForeground(RmsColors.SECONDARY_TEXT);
        askBeforeInstalling.setForeground(RmsColors.text());
        automaticUpdates.setForeground(RmsColors.text());
        automaticallyCheck.setForeground(RmsColors.text());
        checkForUpdates.setForeground(RmsColors.text());
        cancelDownload.setForeground(RmsColors.text());
        downloadProgressLabel.setForeground(RmsColors.SECONDARY_TEXT);
        updateStatus.setForeground(updateStatusError ? RmsColors.DANGER : RmsColors.SECONDARY_TEXT);
        reportTitle.setCaretColor(RmsColors.primary());
        reportDescription.setForeground(RmsColors.text());
        reportDescription.setCaretColor(RmsColors.primary());
        reportDetails.setForeground(RmsColors.text());
        submitReport.setBackground(RmsColors.primaryAccent());
        submitReport.setForeground(RmsColors.primaryForeground());
        attachImage.setBackground(RmsColors.surface());
        attachImage.setForeground(RmsColors.text());
        repaint();
    }

    private void applyTextTheme(Component component) {
        if (component instanceof JLabel label && label != message && label != importMessage
                && label != reportMessage && label != releaseMessage && label != updateStatus
                && label != imageName) {
            label.setForeground(RmsColors.text());
        } else if (component instanceof javax.swing.text.JTextComponent text) {
            text.setForeground(RmsColors.text());
        } else if (component instanceof JButton button && button != submitReport) {
            button.setForeground(RmsColors.text());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) applyTextTheme(child);
        }
    }

    private void addUpdateSection() {
        add(heading("Application Updates"));
        installedVersion.setAlignmentX(LEFT_ALIGNMENT);
        add(installedVersion);
        add(Box.createVerticalStrut(8));

        JLabel preferenceLabel = line("Update Preference");
        preferenceLabel.setFont(preferenceLabel.getFont().deriveFont(Font.BOLD));
        preferenceLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(preferenceLabel);

        ButtonGroup modes = new ButtonGroup();
        modes.add(askBeforeInstalling);
        modes.add(automaticUpdates);
        askBeforeInstalling.setOpaque(false);
        automaticUpdates.setOpaque(false);
        askBeforeInstalling.setAlignmentX(LEFT_ALIGNMENT);
        automaticUpdates.setAlignmentX(LEFT_ALIGNMENT);
        askBeforeInstalling.setSelected(updatePreferences.mode() == UpdateMode.ASK);
        automaticUpdates.setSelected(updatePreferences.mode() == UpdateMode.AUTOMATIC);
        askBeforeInstalling.addActionListener(e -> updatePreferences.setMode(UpdateMode.ASK));
        automaticUpdates.addActionListener(e -> updatePreferences.setMode(UpdateMode.AUTOMATIC));
        add(askBeforeInstalling);
        add(automaticUpdates);

        automaticallyCheck.setOpaque(false);
        automaticallyCheck.setAlignmentX(LEFT_ALIGNMENT);
        automaticallyCheck.setSelected(updatePreferences.automaticallyCheckForUpdates());
        automaticallyCheck.addActionListener(e ->
                updatePreferences.setAutomaticallyCheckForUpdates(automaticallyCheck.isSelected()));
        add(Box.createVerticalStrut(4));
        add(automaticallyCheck);

        lastChecked.setText("Last Checked: " + formatLastChecked(updatePreferences.lastAttemptedCheck()));
        lastChecked.setAlignmentX(LEFT_ALIGNMENT);
        lastChecked.setFont(lastChecked.getFont().deriveFont(12f));
        add(Box.createVerticalStrut(4));
        add(lastChecked);

        checkForUpdates.setAlignmentX(LEFT_ALIGNMENT);
        checkForUpdates.addActionListener(e -> checkForUpdates());
        add(Box.createVerticalStrut(8));
        add(checkForUpdates);

        updateStatus.setAlignmentX(LEFT_ALIGNMENT);
        updateStatus.setFont(updateStatus.getFont().deriveFont(12f));
        add(Box.createVerticalStrut(4));
        add(updateStatus);

        downloadProgressLabel.setAlignmentX(LEFT_ALIGNMENT);
        downloadProgressLabel.setFont(downloadProgressLabel.getFont().deriveFont(12f));
        downloadProgressBar.setAlignmentX(LEFT_ALIGNMENT);
        downloadProgressBar.setStringPainted(true);
        downloadProgressBar.setVisible(false);
        cancelDownload.setAlignmentX(LEFT_ALIGNMENT);
        cancelDownload.setVisible(false);
        cancelDownload.addActionListener(e -> cancelUpdateDownload());
        add(Box.createVerticalStrut(4));
        add(downloadProgressLabel);
        add(downloadProgressBar);
        add(cancelDownload);
    }

    private void checkForUpdates() {
        checkForUpdates.setEnabled(false);
        showUpdateStatus("Checking for updates…", false);
        CompletableFuture<Optional<UpdateInfo>> check = CompletableFuture
                .supplyAsync(updateCheck::get)
                .thenCompose(result -> Objects.requireNonNull(result, "update check result"));
        check.whenComplete((result, error) -> javax.swing.SwingUtilities.invokeLater(
                () -> completeUpdateCheck(result, error)));
    }

    private void completeUpdateCheck(Optional<UpdateInfo> result, Throwable error) {
        checkForUpdates.setEnabled(true);
        lastChecked.setText("Last Checked: " + formatLastChecked(updatePreferences.lastAttemptedCheck()));
        if (error != null) {
            showUpdateStatus(friendlyUpdateError(error), true);
        } else if (result == null) {
            showUpdateStatus("The update check returned an invalid response.", true);
        } else if (result.isEmpty()) {
            showUpdateStatus("You're up to date (version " + AppVersion.current() + ").", false);
        } else {
            UpdateInfo available = result.orElseThrow();
            showUpdateStatus("Version " + available.latestVersion() + " is available.", false);
            offerOrStartDownload(available);
        }
    }

    private void offerOrStartDownload(UpdateInfo update) {
        if (updateWorkflow == null) return;
        try {
            UpdateWorkflow.Detection detection = updateWorkflow.handleDetectedUpdate(update);
            if (detection.decision() == UpdateWorkflow.Decision.ASK_USER) {
                int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                        update.releaseName() + " is available.\nDownload " + update.installerFilename()
                                + " now? BRED will not install or restart automatically.",
                        "BRED update available", javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                if (answer == javax.swing.JOptionPane.YES_OPTION) updateWorkflow.downloadNow(update);
            } else {
                showUpdateStatus("Downloading version " + update.latestVersion() + " in the background.", false);
            }
        } catch (RuntimeException error) {
            showUpdateStatus(friendlyUpdateError(error), true);
        }
    }

    private void cancelUpdateDownload() {
        if (updateWorkflow == null) return;
        updateWorkflow.currentProgress().ifPresent(progress -> updateWorkflow.cancel(progress.version()));
    }

    private void showDownloadProgress(UpdateDownloadManager.DownloadProgress progress) {
        downloadProgressLabel.setText(downloadLabel(progress));
        boolean active = progress.status() == UpdateDownloadManager.Status.DOWNLOADING
                || progress.status() == UpdateDownloadManager.Status.VERIFYING;
        downloadProgressBar.setVisible(active || progress.status() == UpdateDownloadManager.Status.READY);
        cancelDownload.setVisible(active);
        checkForUpdates.setEnabled(!active);
        downloadProgressBar.setIndeterminate(active && progress.totalBytes().isEmpty());
        if (progress.totalBytes().isPresent()) {
            downloadProgressBar.setValue(progress.percentage());
            downloadProgressBar.setString(progress.percentage() + "%");
        } else if (active) {
            downloadProgressBar.setString("Downloading...");
        }
        if (progress.status() == UpdateDownloadManager.Status.READY) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setValue(100);
            downloadProgressBar.setString("Ready");
            showUpdateStatus(progress.message(), false);
        } else if (progress.status() == UpdateDownloadManager.Status.FAILED) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setVisible(false);
            showUpdateStatus(progress.message(), true);
        } else if (progress.status() == UpdateDownloadManager.Status.CANCELLED) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setVisible(false);
            showUpdateStatus(progress.message(), false);
        } else if (progress.status() == UpdateDownloadManager.Status.DOWNLOADING) {
            showUpdateStatus("Downloading version " + progress.version() + "...", false);
        } else {
            showUpdateStatus("Verifying downloaded MSI installer...", false);
        }
        revalidate();
    }

    private static String downloadLabel(UpdateDownloadManager.DownloadProgress progress) {
        String downloaded = formatBytes(progress.downloadedBytes());
        String total = progress.totalBytes().isPresent() ? " of " + formatBytes(progress.totalBytes().getAsLong()) : "";
        return progress.status() == UpdateDownloadManager.Status.READY
                ? progress.installerFilename() + " — " + progress.message()
                : "MSI installer: " + progress.installerFilename() + " · " + downloaded + total;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String friendlyUpdateError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if ((cause instanceof UpdateCheckException || cause instanceof UpdateDownloadException)
                && cause.getMessage() != null) return cause.getMessage();
        return "Unable to check for updates. Check your internet connection and try again.";
    }

    private static String formatLastChecked(java.time.Instant checkedAt) {
        if (checkedAt == null) return "Never";
        return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                .withZone(java.time.ZoneId.systemDefault()).format(checkedAt);
    }

    private void showUpdateStatus(String text, boolean bad) {
        updateStatusError = bad;
        updateStatus.setForeground(bad ? RmsColors.DANGER : RmsColors.SECONDARY_TEXT);
        updateStatus.setText(text);
    }

    private void addContacts(AppContext ctx, User user) {
        JPanel contacts = new JPanel(new BorderLayout(0, 12));
        contacts.setAlignmentX(LEFT_ALIGNMENT);
        contacts.setOpaque(false);
        contacts.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, RmsColors.border()),
                BorderFactory.createEmptyBorder(16, 0, 0, 0)));

        JPanel contactColumn = new JPanel();
        contactColumn.setOpaque(false);
        contactColumn.setLayout(new BoxLayout(contactColumn, BoxLayout.Y_AXIS));
        contactColumn.add(heading("Contacts"));
        contactColumn.add(Box.createVerticalStrut(12));

        JPanel creatorContact = new JPanel(new BorderLayout(12, 0));
        creatorContact.setOpaque(false);
        creatorContact.setAlignmentX(LEFT_ALIGNMENT);
        UserAvatar creatorAvatar = new UserAvatar(76, 54, true);
        creatorAvatar.setProfile("Clark Hilario", "clarkhilario.bread@gmail.com");
        creatorContact.add(creatorAvatar, BorderLayout.WEST);
        JTextArea contactCopy = new JTextArea(
        "Creator: Clark Hilario\nEmail: clarkhilario.bread@gmail.com\nPhone: +639944826590", 3, 28);
        contactCopy.setEditable(false);
        contactCopy.setFocusable(false);
        contactCopy.setLineWrap(true);
        contactCopy.setWrapStyleWord(false);
        contactCopy.setOpaque(false);
        contactCopy.setForeground(RmsColors.text());
        contactCopy.setFont(line("").getFont());
        contactCopy.setBorder(BorderFactory.createEmptyBorder());
        contactCopy.setMaximumSize(new Dimension(Integer.MAX_VALUE, contactCopy.getPreferredSize().height));
        
        // GridBagLayout centres the text block vertically so it lines up with the avatar's centre.
        JPanel copyCentered = new JPanel(new java.awt.GridBagLayout());
        copyCentered.setOpaque(false);
        java.awt.GridBagConstraints centre = new java.awt.GridBagConstraints();
        centre.weightx = 1;
        centre.fill = java.awt.GridBagConstraints.HORIZONTAL;
        copyCentered.add(contactCopy, centre);
        creatorContact.add(copyCentered, BorderLayout.CENTER);
        contactColumn.add(creatorContact);
        addVersionSection(ctx, user, contactColumn);

        JPanel reportForm = new RoundedReportCard();
        reportForm.setLayout(new BoxLayout(reportForm, BoxLayout.Y_AXIS));
        reportForm.setAlignmentX(LEFT_ALIGNMENT);
        reportForm.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        JLabel reportHeading = new JLabel("Submit a bug report");
        reportHeading.setForeground(RmsColors.text());
        reportHeading.setFont(reportHeading.getFont().deriveFont(Font.BOLD, 17f));
        reportHeading.setAlignmentX(LEFT_ALIGNMENT);
        reportSubtitle.setForeground(RmsColors.SECONDARY_TEXT);
        reportSubtitle.setFont(reportSubtitle.getFont().deriveFont(12f));
        reportSubtitle.setAlignmentX(LEFT_ALIGNMENT);
        reportForm.add(reportHeading);
        reportForm.add(reportSubtitle);
        reportForm.add(Box.createVerticalStrut(14));

        JLabel titleLabel = formLabel("Title");
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        reportForm.add(titleLabel);
        styleReportField(reportTitle);
        reportTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        reportTitle.setPreferredSize(new Dimension(420, 42));
        reportTitle.setAlignmentX(LEFT_ALIGNMENT);
        reportForm.add(reportTitle);
        reportForm.add(Box.createVerticalStrut(12));

        JLabel descriptionLabel = formLabel("What happened?");
        descriptionLabel.setAlignmentX(LEFT_ALIGNMENT);
        reportForm.add(descriptionLabel);
        reportDescription.setLineWrap(true);
        reportDescription.setWrapStyleWord(true);
        reportDescription.setOpaque(false);
        reportDescription.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane descriptionScroll = new StyledScrollPane(reportDescription);
        descriptionScroll.setOpaque(false);
        descriptionScroll.getViewport().setOpaque(false);
        descriptionScroll.setBorder(new RoundedFieldBorder(RmsColors.border(), 14));
        descriptionScroll.setAlignmentX(LEFT_ALIGNMENT);
        descriptionScroll.setPreferredSize(new Dimension(520, 116));
        descriptionScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));
        reportForm.add(descriptionScroll);
        reportForm.add(Box.createVerticalStrut(14));

        JPanel imagePicker = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        imagePicker.setOpaque(false);
        imagePicker.setAlignmentX(LEFT_ALIGNMENT);
        attachImage.setBackground(RmsColors.surface());
        attachImage.setForeground(RmsColors.text());
        attachImage.addActionListener(e -> chooseReportImage());
        imageName.setForeground(RmsColors.SECONDARY_TEXT);
        imageName.setFont(imageName.getFont().deriveFont(12f));
        imagePicker.add(attachImage);
        imagePicker.add(imageName);
        reportForm.add(imagePicker);
        reportForm.add(Box.createVerticalStrut(12));

        submitReport.setText("Submit bug report");
        submitReport.setAlignmentX(LEFT_ALIGNMENT);
        submitReport.setMaximumSize(new Dimension(180, 42));
        submitReport.setPreferredSize(new Dimension(180, 42));
        submitReport.setFont(submitReport.getFont().deriveFont(Font.BOLD, 12f));
        submitReport.setBackground(RmsColors.primaryAccent());
        submitReport.setForeground(RmsColors.primaryForeground());
        submitReport.setContentAreaFilled(false);
        submitReport.setOpaque(false);
        submitReport.addActionListener(e -> submitReport(ctx));
        reportForm.add(submitReport);
        reportForm.add(Box.createVerticalStrut(8));
        reportMessage.setAlignmentX(LEFT_ALIGNMENT);
        reportMessage.setFont(reportMessage.getFont().deriveFont(12f));
        reportForm.add(reportMessage);
        if (ctx.bugReports == null) {
            JLabel unavailable = line("Bug reports are available when connected to Supabase.");
            unavailable.setForeground(RmsColors.SECONDARY_TEXT);
            unavailable.setAlignmentX(LEFT_ALIGNMENT);
            reportForm.add(Box.createVerticalStrut(6));
            reportForm.add(unavailable);
            submitReport.setEnabled(false);
            attachImage.setEnabled(false);
        }

        JPanel reportColumn = new JPanel();
        reportColumn.setOpaque(false);
        reportColumn.setLayout(new BoxLayout(reportColumn, BoxLayout.Y_AXIS));
        reportColumn.add(reportForm);
        if (user != null && user.role() == Role.CREATOR && ctx.bugReports != null) {
            reportColumn.add(Box.createVerticalStrut(18));
            JLabel submittedHeading = heading("Submitted reports");
            submittedHeading.setAlignmentX(LEFT_ALIGNMENT);
            reportColumn.add(submittedHeading);
            reportList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            reportList.setVisibleRowCount(5);
            reportList.setCellRenderer(new DefaultListCellRenderer() {
                @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                        int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof BugReport report) {
                        label.setText(report.title() + " · " + report.companyName() + " · " + report.reporterEmail());
                    }
                    return label;
                }
            });
            reportList.addListSelectionListener(e -> {
                BugReport selected = reportList.getSelectedValue();
                reportDetails.setText(selected == null ? "" : selected.description());
                viewImage.setEnabled(selected != null && selected.attachmentPath() != null
                        && !selected.attachmentPath().isBlank());
            });
            reportDetails.setEditable(false);
            reportDetails.setLineWrap(true);
            reportDetails.setWrapStyleWord(true);
            viewImage.setEnabled(false);
            viewImage.addActionListener(e -> showReportImage(ctx));
            JScrollPane reportsScroll = new StyledScrollPane(reportList);
            reportsScroll.setAlignmentX(LEFT_ALIGNMENT);
            reportColumn.add(reportsScroll);
            JScrollPane detailsScroll = new StyledScrollPane(reportDetails);
            detailsScroll.setAlignmentX(LEFT_ALIGNMENT);
            reportColumn.add(detailsScroll);
            viewImage.setAlignmentX(LEFT_ALIGNMENT);
            reportColumn.add(viewImage);
            loadReports(ctx);
        }

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.add(reportColumn);
        body.add(Box.createVerticalStrut(24));
        body.add(contactColumn);
        contacts.add(body, BorderLayout.CENTER);
        add(contacts);
    }

    private void addVersionSection(AppContext ctx, User user, JPanel contactColumn) {
        contactColumn.add(Box.createVerticalStrut(18));
        contactColumn.add(heading("Version"));
        releaseVersion.setAlignmentX(LEFT_ALIGNMENT);
        contactColumn.add(releaseVersion);

        if (ctx.appReleases == null) {
            releaseVersion.setText("Installed version: " + AppVersion.current());
            releaseMessage.setText("Shared release versions require a Supabase connection.");
        } else {
            releaseVersion.setText("Loading current version…");
            loadCurrentRelease(ctx);
        }

        if (ctx.appReleases != null && user != null && user.role() == Role.CREATOR) {
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            controls.setOpaque(false);
            controls.setAlignmentX(LEFT_ALIGNMENT);
            releaseInput.setToolTipText("Enter a version such as 1.2.3");
            releaseInput.setMaximumSize(new Dimension(220, 36));
            styleReportField(releaseInput);
            controls.add(releaseInput);
            publishRelease.setAlignmentX(LEFT_ALIGNMENT);
            publishRelease.addActionListener(e -> publishRelease(ctx));
            controls.add(publishRelease);
            contactColumn.add(Box.createVerticalStrut(8));
            contactColumn.add(controls);
        } else if (ctx.appReleases != null) {
            releaseMessage.setText("Only the Creator can publish a new version.");
        }

        releaseMessage.setAlignmentX(LEFT_ALIGNMENT);
        releaseMessage.setFont(releaseMessage.getFont().deriveFont(12f));
        contactColumn.add(releaseMessage);
    }

    private void loadCurrentRelease(AppContext ctx) {
        BackgroundTask.run(ctx.appReleases::current, current -> {
            releaseVersion.setText("Current version: " + current.version());
            if (releaseInput.getText().isBlank()) releaseInput.setText(current.version());
        }, error -> {
            releaseVersion.setText("Current version unavailable.");
            showRelease(UiErrors.friendly(error), true);
        });
    }

    private void publishRelease(AppContext ctx) {
        publishRelease.setEnabled(false);
        showRelease("Publishing version…", false);
        String version = releaseInput.getText();
        BackgroundTask.run(() -> ctx.appReleases.publish(version), published -> {
            publishRelease.setEnabled(true);
            releaseVersion.setText("Current version: " + published.version());
            showRelease("Version updated for all users.", false);
        }, error -> {
            publishRelease.setEnabled(true);
            showRelease(UiErrors.friendly(error), true);
        });
    }

    private void chooseReportImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a screenshot or image");
        chooser.setFileFilter(new FileNameExtensionFilter("Image files (PNG, JPEG, GIF, BMP, WebP)",
                "png", "jpg", "jpeg", "gif", "bmp", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        selectedImage = chooser.getSelectedFile().toPath();
        imageName.setText(selectedImage.getFileName().toString());
    }

    private void submitReport(AppContext ctx) {
        submitReport.setEnabled(false);
        String title = reportTitle.getText();
        String description = reportDescription.getText();
        Path attachment = selectedImage;
        showReport("Submitting bug report…", false);
        BackgroundTask.run(() -> {
            if (attachment != null && Files.size(attachment) > com.bred.rms.services.BugReportService.MAX_IMAGE_BYTES) {
                throw new IOException("Choose an image smaller than 8 MB.");
            }
            byte[] data = attachment == null ? null : Files.readAllBytes(attachment);
            String contentType = attachment == null ? null : imageContentType(attachment);
            ctx.bugReports.submit(title, description,
                    attachment == null ? null : attachment.getFileName().toString(), data, contentType);
            return Boolean.TRUE;
        }, ignored -> {
            submitReport.setEnabled(true);
            reportTitle.setText("");
            reportDescription.setText("");
            selectedImage = null;
            imageName.setText("No image selected");
            showReport("Bug report sent to the Creator. Thank you.", false);
            if (ctx.currentUser.get() != null && ctx.currentUser.get().role() == Role.CREATOR) loadReports(ctx);
        }, error -> {
            submitReport.setEnabled(true);
            showReport(UiErrors.friendly(error), true);
        });
    }

    private static String imageContentType(Path path) throws IOException {
        String type = Files.probeContentType(path);
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return type;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";
        throw new IOException("Choose a supported image file.");
    }

    private void loadReports(AppContext ctx) {
        BackgroundTask.run(ctx.bugReports::listForCreator, items -> {
            reports.clear();
            items.forEach(reports::addElement);
        }, error -> showReport(UiErrors.friendly(error), true));
    }

    private void showReportImage(AppContext ctx) {
        BugReport selected = reportList.getSelectedValue();
        if (selected == null || selected.attachmentPath() == null) return;
        BackgroundTask.run(() -> ctx.bugReports.downloadAttachment(selected.attachmentPath()), bytes -> {
            try {
                java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) throw new IOException("The attached file is not a supported image.");
                int width = Math.min(image.getWidth(), 1000);
                int height = Math.min(image.getHeight(), 700);
                double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
                JLabel preview = new JLabel(new javax.swing.ImageIcon(
                        image.getScaledInstance((int) (image.getWidth() * scale), (int) (image.getHeight() * scale),
                                java.awt.Image.SCALE_SMOOTH)));
                JDialog dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this), "Bug report image");
                dialog.setContentPane(new StyledScrollPane(preview));
                dialog.pack();
                dialog.setLocationRelativeTo(this);
                dialog.setVisible(true);
            } catch (IOException e) {
                showReport(e.getMessage(), true);
            }
        }, error -> showReport(UiErrors.friendly(error), true));
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return l;
    }

    private static JLabel line(String text) {
        JLabel l = new JLabel(text);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JPanel labelled(String label, JPasswordField field) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2));
        p.setAlignmentX(LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setPreferredSize(new java.awt.Dimension(170, l.getPreferredSize().height));
        p.add(l);
        p.add(field);
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private void submit(AppContext ctx) {
        char[] a = newPassword.getPassword();
        char[] b = confirm.getPassword();
        if (!Arrays.equals(a, b)) {
            show("The two passwords do not match.", true);
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
            return;
        }
        String pw = new String(a);
        Arrays.fill(a, '\0');
        Arrays.fill(b, '\0');
        change.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.auth.changePassword(pw);
            return Boolean.TRUE;
        }, ok -> {
            change.setEnabled(true);
            newPassword.setText("");
            confirm.setText("");
            show("Password changed.", false);
        }, e -> {
            change.setEnabled(true);
            show(e instanceof com.bred.rms.services.AuthException && e.getMessage() != null ? e.getMessage() : UiErrors.friendly(e), true);
        });
    }

    private void chooseLocalDataDirectory(AppContext ctx) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the folder containing data.json");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String configured = System.getenv("RMS_DATA_DIR");
        Path defaultDirectory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "RMS-Data")
                : Path.of(configured.trim());
        chooser.setSelectedFile(defaultDirectory.toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                "This imports data into the current Supabase project. Records with matching IDs are updated. "
                        + "The local data is not deleted. Continue?",
                "Import local data", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) return;

        Path directory = chooser.getSelectedFile().toPath();
        importLocal.setEnabled(false);
        showImport("Importing local data…", false);
        BackgroundTask.run(() -> ctx.localDataImporter.importDirectory(directory), result -> {
            importLocal.setEnabled(true);
            showImport("Imported " + result.suppliers() + " suppliers, " + result.projects() + " projects, "
                    + result.receipts() + " receipts, " + result.attachments() + " attachments, and "
                    + result.activityEntries() + " activity entries. " + result.localUsers()
                    + " local accounts were not transferred; recreate accounts in Users.", false);
        }, error -> {
            importLocal.setEnabled(true);
            showImport(UiErrors.friendly(error), true);
        });
    }

    private void chooseExcelFile(AppContext ctx) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select an Excel receipt workbook");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Excel workbooks (*.xlsx, *.xls)", "xlsx", "xls"));
        String configured = System.getenv("RMS_DATA_DIR");
        if (configured != null && !configured.isBlank()) chooser.setCurrentDirectory(Path.of(configured.trim()).toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                "This imports receipt details and line items into the current Supabase project. "
                        + "Existing receipts with the same supplier TIN and receipt number are skipped. "
                        + "Receipt scans and attachments are not included. Continue?",
                "Import receipts from Excel", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) return;

        Path workbook = chooser.getSelectedFile().toPath();
        importExcel.setEnabled(false);
        showImport("Importing receipts from Excel…", false);
        BackgroundTask.run(() -> ctx.localDataImporter.importExcel(workbook), result -> {
            importExcel.setEnabled(true);
            showImport("Imported " + result.receiptsImported() + " receipts; skipped "
                    + result.receiptsSkipped() + " existing receipts. Created " + result.suppliersCreated()
                    + " suppliers and " + result.projectsCreated() + " projects.", false);
        }, error -> {
            importExcel.setEnabled(true);
            showImport(UiErrors.friendly(error), true);
        });
    }

    private void saveExcelTemplate() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Excel receipt template");
        chooser.setSelectedFile(new java.io.File("BRED-receipts-template.xlsx"));
        chooser.setFileFilter(new FileNameExtensionFilter("Excel workbook (*.xlsx)", "xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            target = target.resolveSibling(target.getFileName() + ".xlsx");
        }
        Path destination = target;
        if (Files.exists(destination)
                && javax.swing.JOptionPane.showConfirmDialog(this, "Replace the existing template file?",
                "Confirm overwrite", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE)
                != javax.swing.JOptionPane.YES_OPTION) return;
        excelTemplate.setEnabled(false);
        showImport("Creating Excel template…", false);
        BackgroundTask.run(() -> {
            com.bred.rms.repositories.supabase.SupabaseLocalDataImporter.writeExcelTemplate(destination);
            return destination;
        }, created -> {
            excelTemplate.setEnabled(true);
            showImport("Excel template saved to " + created.toAbsolutePath(), false);
        }, error -> {
            excelTemplate.setEnabled(true);
            showImport(UiErrors.friendly(error), true);
        });
    }

    private void showImport(String text, boolean bad) {
        importMessage.setForeground(bad ? RmsColors.DANGER : RmsColors.SUCCESS);
        importMessage.setText(text);
    }

    private void show(String text, boolean bad) {
        message.setForeground(bad ? RmsColors.DANGER : RmsColors.SUCCESS);
        message.setText(text);
    }

    private void showReport(String text, boolean bad) {
        reportMessage.setForeground(bad ? RmsColors.DANGER : RmsColors.SUCCESS);
        reportMessage.setText(text);
    }

    private void showRelease(String text, boolean bad) {
        releaseMessage.setForeground(bad ? RmsColors.DANGER : RmsColors.SUCCESS);
        releaseMessage.setText(text);
    }

    private static JLabel formLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(RmsColors.text());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return label;
    }

    private static void styleReportField(JTextField field) {
        field.setOpaque(false);
        field.setForeground(RmsColors.text());
        field.setCaretColor(RmsColors.primary());
        field.setFont(field.getFont().deriveFont(13f));
        field.setBorder(BorderFactory.createCompoundBorder(
                new RoundedFieldBorder(RmsColors.border(), 14),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private static final class RoundedReportCard extends JPanel {
        private static final int RADIUS = 18;

        private RoundedReportCard() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(RmsColors.surface());
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS));
            g.setColor(RmsColors.border());
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, RADIUS, RADIUS));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedFieldBorder extends AbstractBorder {
        private final Color color;
        private final int radius;

        private RoundedFieldBorder(Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override
        public java.awt.Insets getBorderInsets(java.awt.Component component) {
            return new java.awt.Insets(0, 0, 0, 0);
        }

        @Override
        public void paintBorder(java.awt.Component component, Graphics graphics, int x, int y,
                                int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f,
                    width - 1, height - 1, radius, radius));
            g.dispose();
        }
    }

    private static final class RoundedReportSubmitButton extends JButton {
        private RoundedReportSubmitButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16);
            g.setColor(isEnabled() ? getBackground() : RmsColors.border());
            g.fill(shape);
            g.clip(shape);
            super.paintComponent(g);
            g.dispose();
        }
    }

    private static final class RoundedReportButton extends JButton {
        private RoundedReportButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    new RoundedFieldBorder(RmsColors.border(), 14),
                    BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14);
            g.setColor(getBackground());
            g.fill(shape);
            g.clip(shape);
            super.paintComponent(g);
            g.dispose();
        }
    }

}
