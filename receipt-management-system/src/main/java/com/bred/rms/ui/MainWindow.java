package com.bred.rms.ui;

import com.bred.rms.api.LocalApiServer;
import com.bred.rms.config.AppContext;
import com.bred.rms.config.AppVersion;
import com.bred.rms.services.AuthException;
import com.bred.rms.ui.auth.LoginPanel;
import com.bred.rms.ui.auth.SetupPanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.utils.BackgroundTask;
import com.bred.rms.updates.GitHubReleaseClient;
import com.bred.rms.updates.UpdateChecker;
import com.bred.rms.updates.UpdateDownloadException;
import com.bred.rms.updates.UpdateDownloadManager;
import com.bred.rms.updates.UpdateInfo;
import com.bred.rms.updates.UpdatePreferences;
import com.bred.rms.updates.UpdateWorkflow;
import com.bred.rms.updates.WindowsInstallerHandoff;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The application window: setup screen, sign-in screen, or the signed-in shell. */
public final class MainWindow extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final AppContext ctx;
    private final LocalApiServer localApi;
    private final LoginPanel login;
    private Shell shell;
    private ReactDesktopPanel reactPanel;
    private UpdatePreferences updatePreferences;
    private UpdateWorkflow updateWorkflow;
    private final WindowsInstallerHandoff installerHandoff = new WindowsInstallerHandoff();
    private JDialog updateReadyDialog;
    private TrayIcon trayIcon;

    /** @param ctx the application, or null when Supabase is not configured
     *  @param setupProblem detail to show on the setup screen when ctx is null */
    public MainWindow(AppContext ctx, String setupProblem) {
        this(ctx, setupProblem, null);
    }

    public MainWindow(AppContext ctx, String setupProblem, LocalApiServer localApi) {
        super("BRED - Business Receipt Encoding and Documentation System");
        this.ctx = ctx;
        this.localApi = localApi;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 620));
        setSize(1180, 760);
        setLocationRelativeTo(null);
        setContentPane(root);
        URL iconUrl = getClass().getResource("/bred-logo.png");
        if (iconUrl != null) {
            setIconImage(Toolkit.getDefaultToolkit().getImage(iconUrl));
        }

        if (ctx == null) {
            login = null;
            root.add(new SetupPanel(setupProblem), "setup");
            cards.show(root, "setup");
            return;
        }
        login = new LoginPanel(this::attemptLogin, (fullName, email, role, companyId, companyName, password, done) ->
                BackgroundTask.run(() -> {
                    try {
                        ctx.auth.registerAccount(fullName, email, role, companyId, companyName, password);
                    } catch (AuthException e) {
                        throw new RegistrationFailure(e.getMessage());
                    }
                    return Boolean.TRUE;
                }, ignored -> done.accept(null), error -> done.accept(
                        error instanceof RegistrationFailure ? error.getMessage() : UiErrors.friendly(error))),
                ctx.userRepository::availableCompanies);
        login.setHint(ctx.loginHint);
        login.setReleaseVersion(AppVersion.current());
        root.add(login, "login");
        cards.show(root, "login");
        refreshReleaseVersion();

        updatePreferences = new UpdatePreferences();
        UpdateChecker updateChecker = new UpdateChecker(AppVersion.current(), new GitHubReleaseClient(), updatePreferences);
        UpdateDownloadManager downloadManager = new UpdateDownloadManager();
        updateWorkflow = new UpdateWorkflow(updatePreferences, updateChecker, downloadManager);
        updateWorkflow.addProgressListener(this::onUpdateProgress);
        installTray();
        SwingUtilities.invokeLater(this::checkForUpdatesAutomatically);
        SwingUtilities.invokeLater(this::showPreviousInstallResult);
    }

    /** Hides to the system tray on close instead of exiting. Falls back to normal exit if no tray exists. */
    private void installTray() {
        if (!SystemTray.isSupported()) return;

        Runnable showWindow = () -> {
            setVisible(true);
            setExtendedState(NORMAL);
            toFront();
        };

        Image icon = getIconImage();
        if (icon == null) icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        TrayIcon tray = new TrayIcon(icon, "BRED");
        tray.setImageAutoSize(true);
        trayIcon = tray;

        MenuItem open = new MenuItem("Open BRED");
        open.addActionListener(e -> showWindow.run());
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> System.exit(0));

        PopupMenu menu = new PopupMenu();
        menu.add(open);
        menu.add(quit);
        tray.setPopupMenu(menu);
        tray.addActionListener(e -> showWindow.run()); // double-click the icon

        try {
            SystemTray.getSystemTray().add(tray);
            setDefaultCloseOperation(HIDE_ON_CLOSE); // only now is it safe to hide
        } catch (AWTException e) {
            // tray unavailable: keep EXIT_ON_CLOSE
        }
    }

    private void attemptLogin(String email, char[] password, java.util.function.Consumer<String> done) {
        BackgroundTask.run(() -> ctx.auth.login(email, password), user -> {
            ctx.auth.onSessionExpired(() -> javax.swing.SwingUtilities.invokeLater(() -> {
                showLogin();
                login.showMessage("Your session expired. Please sign in again.");
            }));
            shell = new Shell(ctx, this::signOut, updatePreferences, updateWorkflow);
            root.add(shell, "shell");
            if (localApi != null && ReactDesktopPanel.isSupportedPlatform()) {
                if (reactPanel == null) {
                    reactPanel = new ReactDesktopPanel(localApi);
                    root.add(reactPanel, "react");
                }
                cards.show(root, "react");
                reactPanel.start();
            } else {
                cards.show(root, "shell");
            }
            done.accept(null);
        }, error -> done.accept(error instanceof AuthException ? error.getMessage()
                : "Something went wrong while signing in. Please try again."));
    }

    private void signOut() {
        BackgroundTask.run(() -> { ctx.auth.logout(); return null; }, ignored -> {
            showLogin();
            refreshReleaseVersion();
        }, error -> {
            showLogin();
            refreshReleaseVersion();
        });
    }

    private void refreshReleaseVersion() {
        if (ctx.appReleases == null) return;
        BackgroundTask.run(ctx.appReleases::current,
                release -> login.setReleaseVersion(release.version()),
                error -> login.setReleaseVersionUnavailable());
    }

    private void checkForUpdatesAutomatically() {
        try {
            updateWorkflow.checkAutomatically().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "The automatic BRED update check failed.", unwrap(error));
                    return;
                }
                if (result == null || result.isEmpty()) return;
                UpdateInfo available = result.orElseThrow();
                try {
                    UpdateWorkflow.Detection detection = updateWorkflow.handleDetectedUpdate(available);
                    if (detection.decision() == UpdateWorkflow.Decision.ASK_USER
                            && updatePreferences.markUpdateNotificationShown()) {
                        promptToDownload(available);
                    }
                } catch (UpdateDownloadException failure) {
                    LOGGER.log(Level.WARNING, "Unable to start the BRED update download.", failure);
                }
            }));
        } catch (RuntimeException error) {
            LOGGER.log(Level.WARNING, "Unable to start the automatic BRED update check.", error);
        }
    }

    private void promptToDownload(UpdateInfo update) {
        int answer = JOptionPane.showConfirmDialog(this,
                update.releaseName() + " is available.\nDownload " + update.installerFilename()
                        + " now? BRED will not install or restart automatically.",
                "BRED update available", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        try {
            updateWorkflow.downloadNow(update);
        } catch (UpdateDownloadException failure) {
            JOptionPane.showMessageDialog(this, failure.getMessage(), "Unable to download update",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void onUpdateProgress(UpdateDownloadManager.DownloadProgress progress) {
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null && progress.status() == UpdateDownloadManager.Status.DOWNLOADING) {
                trayIcon.setToolTip("BRED - Downloading update " + progress.version()
                        + " (" + progress.percentage() + "%)");
            } else if (progress.status() == UpdateDownloadManager.Status.READY) {
                UpdateDownloadManager.DownloadResult download = progress.downloadResult().orElse(null);
                if (download == null) {
                    LOGGER.warning("The BRED update completed without a verified installer result.");
                    return;
                }
                String message = "Version " + progress.version() + " is ready. "
                        + progress.installerFilename();
                if (trayIcon != null) {
                    trayIcon.setToolTip("BRED - Update " + progress.version() + " is ready");
                    trayIcon.displayMessage("BRED update ready", message, TrayIcon.MessageType.INFO);
                }
                showUpdateReadyDialog(download, progress.installerFilename(), progress.message());
            } else if (progress.status() == UpdateDownloadManager.Status.CANCELLED && trayIcon != null) {
                trayIcon.setToolTip("BRED");
            } else if (progress.status() == UpdateDownloadManager.Status.FAILED) {
                if (trayIcon != null) {
                    trayIcon.displayMessage("BRED update failed", progress.message(), TrayIcon.MessageType.WARNING);
                } else {
                    JOptionPane.showMessageDialog(this, progress.message(), "BRED update failed",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        });
    }

    private void showUpdateReadyDialog(UpdateDownloadManager.DownloadResult download, String filename, String status) {
        if (updateReadyDialog != null && updateReadyDialog.isDisplayable()) {
            updateReadyDialog.toFront();
            return;
        }

        JDialog dialog = new JDialog((java.awt.Frame) null, "BRED update ready", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel();
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 20, 16, 20));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JLabel("A BRED MSI update is ready."));
        content.add(Box.createVerticalStrut(8));
        content.add(new JLabel("Current version: " + download.currentVersion()));
        content.add(new JLabel("Available version: " + download.version()));
        content.add(new JLabel("Installer: " + filename));
        content.add(Box.createVerticalStrut(6));
        content.add(new JLabel(status));
        content.add(Box.createVerticalStrut(14));

        JButton updateNow = new JButton("Restart and Update");
        JButton later = new JButton("Update Later");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(later);
        actions.add(updateNow);
        content.add(actions);
        later.addActionListener(event -> dialog.dispose());
        updateNow.addActionListener(event -> confirmAndStartInstaller(dialog, updateNow, later, download));

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        updateReadyDialog = dialog;
        dialog.setVisible(true);
    }

    private void confirmAndStartInstaller(JDialog dialog, JButton updateNow, JButton later,
                                          UpdateDownloadManager.DownloadResult download) {
        int answer = JOptionPane.showConfirmDialog(dialog,
                "Save your work before continuing. BRED will close, then Windows will open the interactive "
                        + "MSI installer and may request administrator permission.\n\nInstall version "
                        + download.version() + " now?",
                "Confirm BRED update", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        updateNow.setEnabled(false);
        later.setEnabled(false);
        CompletableFuture.supplyAsync(() -> installerHandoff.startAfterExit(download, ProcessHandle.current().pid()))
                .whenComplete((handoff, error) -> SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        LOGGER.log(Level.WARNING, "Unable to prepare the BRED MSI installation.", unwrap(error));
                        updateNow.setEnabled(true);
                        later.setEnabled(true);
                        JOptionPane.showMessageDialog(dialog,
                                friendlyHandoffError(error), "BRED update could not start",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    dialog.dispose();
                    if (trayIcon != null) {
                        SystemTray.getSystemTray().remove(trayIcon);
                        trayIcon = null;
                    }
                    setDefaultCloseOperation(EXIT_ON_CLOSE);
                    processWindowEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
                }));
    }

    private void showPreviousInstallResult() {
        installerHandoff.readPreviousResult(AppVersion.current()).ifPresent(message ->
                JOptionPane.showMessageDialog(this, message, "BRED update result",
                        JOptionPane.INFORMATION_MESSAGE));
    }

    private static String friendlyHandoffError(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof UpdateDownloadException && cause.getMessage() != null
                ? cause.getMessage() : "BRED could not safely start the MSI installer. The application is still open.";
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private void showLogin() {
        if (shell != null) root.remove(shell);
        shell = null;
        if (reactPanel != null) {
            root.remove(reactPanel);
            reactPanel = null;
        }
        login.reset();
        cards.show(root, "login");
        root.revalidate();
        root.repaint();
    }

    private static final class RegistrationFailure extends RuntimeException {
            private RegistrationFailure(String message) { super(message); }
        }
}
