package com.bred.rms.ui;

import com.bred.rms.api.LocalApiServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Hosts the bundled React shell inside the Windows desktop application. */
public final class ReactDesktopPanel extends JPanel {
    private static final Logger LOGGER = Logger.getLogger(ReactDesktopPanel.class.getName());
    private static final String BOOTSTRAP_QUERY = "getBootstrap";
    private static final String THEME_QUERY = "getTheme";
    private static final String BRIDGE_SCRIPT = """
            (() => {
              if (window.bredDesktop) return;
              const getBootstrap = () => new Promise((resolve, reject) => {
                if (typeof window.cefQuery !== 'function') {
                  reject(new Error('The BRED native bridge is unavailable.'));
                  return;
                }
                window.cefQuery({
                  request: 'getBootstrap',
                  onSuccess: value => {
                    try { resolve(JSON.parse(value)); }
                    catch { reject(new Error('The BRED native bridge returned invalid data.')); }
                  },
                  onFailure: () => reject(new Error('The BRED native bridge rejected the request.'))
                });
              });
              const getTheme = () => new Promise((resolve, reject) => {
                if (typeof window.cefQuery !== 'function') {
                  reject(new Error('The BRED native bridge is unavailable.'));
                  return;
                }
                window.cefQuery({
                  request: 'getTheme',
                  onSuccess: value => {
                    try { resolve(JSON.parse(value)); }
                    catch { reject(new Error('The BRED native bridge returned invalid theme data.')); }
                  },
                  onFailure: () => reject(new Error('The BRED native bridge rejected the theme request.'))
                });
              });
              Object.defineProperty(window, 'bredDesktop', {
                value: Object.freeze({ getBootstrap, getTheme }),
                configurable: false,
                writable: false
              });
              if (typeof window.bredDesktopReadyCallback === 'function') {
                window.bredDesktopReadyCallback();
              }
            })();
            """;

    private final LocalApiServer api;
    private final JPanel message = new JPanel(new BorderLayout(0, 12));
    private final JLabel status = new JLabel("Loading BRED workspace…", JLabel.CENTER);
    private final JButton retry = new JButton("Retry");
    private boolean starting;
    private CefApp cefApp;
    private CefClient cefClient;
    private volatile CefBrowser browser;
    private CefMessageRouter messageRouter;

    public ReactDesktopPanel(LocalApiServer api) {
        this.api = api;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        status.getAccessibleContext().setAccessibleName("Loading BRED workspace");
        message.add(createLoadingSkeleton(), BorderLayout.CENTER);
        retry.addActionListener(event -> {
            if (browser == null) start();
            else {
                removeAll();
                setBorder(BorderFactory.createEmptyBorder());
                add(browser.getUIComponent(), BorderLayout.CENTER);
                revalidate();
                repaint();
                browser.reloadIgnoreCache();
            }
        });
        message.add(retry, BorderLayout.SOUTH);
        add(message, BorderLayout.CENTER);
        retry.setVisible(false);
    }

    private JPanel createLoadingSkeleton() {
        JPanel loading = new JPanel(new BorderLayout(0, 20));
        loading.setOpaque(false);
        loading.add(status, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setOpaque(false);
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 8));
        heading.setOpaque(false);
        heading.add(skeletonBar(210, 20));
        heading.add(skeletonBar(340, 12));
        content.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        JPanel statistics = new JPanel(new GridLayout(1, 4, 12, 0));
        statistics.setOpaque(false);
        for (int index = 0; index < 4; index++) {
            JPanel card = new JPanel(new GridLayout(3, 1, 0, 10));
            card.setBorder(BorderFactory.createEmptyBorder(15, 14, 15, 14));
            card.setBackground(UIManager.getColor("Panel.background"));
            card.add(skeletonBar(100, 11));
            card.add(skeletonBar(130, 23));
            card.add(skeletonBar(85, 10));
            statistics.add(card);
        }
        body.add(statistics, BorderLayout.NORTH);

        JPanel panels = new JPanel(new GridLayout(1, 2, 14, 0));
        panels.setOpaque(false);
        for (int index = 0; index < 2; index++) {
            JPanel panel = new JPanel(new BorderLayout(0, 18));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            panel.setBackground(UIManager.getColor("Panel.background"));
            panel.add(skeletonBar(190, 18), BorderLayout.NORTH);
            panel.add(skeletonBar(400, 180), BorderLayout.CENTER);
            panels.add(panel);
        }
        body.add(panels, BorderLayout.CENTER);
        content.add(body, BorderLayout.CENTER);
        loading.add(content, BorderLayout.CENTER);
        return loading;
    }

    private static JPanel skeletonBar(int width, int height) {
        JPanel bar = new JPanel();
        bar.setPreferredSize(new Dimension(width, height));
        bar.setBackground(UIManager.getColor("Component.borderColor") == null
                ? UIManager.getColor("Panel.background")
                : UIManager.getColor("Component.borderColor"));
        return bar;
    }

    public static boolean isSupportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                && "amd64".equalsIgnoreCase(System.getProperty("os.arch", ""));
    }

    public void start() {
        if (starting || browser != null) return;
        starting = true;
        retry.setVisible(false);
        Path installRoot = runtimeRoot();
        CompletableFuture.supplyAsync(() -> {
            try {
                return buildCef(installRoot);
            } catch (CefInitializationException e) {
                throw new IllegalStateException("Could not initialize the JCEF runtime.", e);
            }
        })
                .whenComplete((app, error) -> SwingUtilities.invokeLater(() -> {
                    starting = false;
                    if (error != null) {
                        showFailure(unwrap(error));
                        return;
                    }
                    cefApp = app;
                    createBrowser();
                }));
    }

    private CefApp buildCef(Path installRoot) throws CefInitializationException {
        try {
            Files.createDirectories(installRoot);
            Path profilePath = installRoot.resolve("profile");
            Files.createDirectories(profilePath);
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(installRoot.resolve("runtime").toFile());
            builder.setMirrors(List.of());
            CefSettings settings = builder.getCefSettings();
            settings.windowless_rendering_enabled = false;
            settings.root_cache_path = profilePath.toString();
            settings.cache_path = profilePath.resolve("cache").toString();
            return builder.build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
        } catch (IOException | UnsupportedPlatformException error) {
            throw new CompletionException(error);
        }
    }

    private void createBrowser() {
        try {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            String pageUrl = bootstrap.origin() + "/";
            String expectedOrigin = bootstrap.origin();
            cefClient = cefApp.createClient();
            messageRouter = CefMessageRouter.create();
            messageRouter.addHandler(new CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(CefBrowser queryBrowser, CefFrame frame, long queryId, String request,
                                       boolean persistent, org.cef.callback.CefQueryCallback callback) {
                    if (queryBrowser != browser || frame == null
                            || persistent || !frame.isMain()
                            || !isTrustedRendererUrl(frame.getURL(), expectedOrigin)) {
                        return false;
                    }
                    if (BOOTSTRAP_QUERY.equals(request)) {
                        callback.success("{\"origin\":\"" + bootstrap.origin()
                                + "\",\"token\":\"" + bootstrap.token() + "\"}");
                        return true;
                    }
                    if (THEME_QUERY.equals(request)) {
                        try {
                            callback.success(new ObjectMapper().writeValueAsString(
                                    com.bred.rms.ui.theme.ThemeManager.reactTheme()));
                        } catch (IOException error) {
                            callback.failure(-1, "Could not serialize the BRED theme.");
                        }
                        return true;
                    }
                    return false;
                }
            }, true);
            cefClient.addMessageRouter(messageRouter);
            cefClient.addRequestHandler(new CefRequestHandlerAdapter() {
                @Override
                public boolean onBeforeBrowse(CefBrowser requestBrowser, CefFrame frame, CefRequest request,
                                              boolean userGesture, boolean isRedirect) {
                    return frame == null || !frame.isMain()
                            || !isTrustedRendererUrl(request.getURL(), expectedOrigin);
                }

                @Override
                public boolean onOpenURLFromTab(CefBrowser requestBrowser, CefFrame frame, String targetUrl,
                                                boolean userGesture) {
                    return true;
                }
            });
            cefClient.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser loadedBrowser, CefFrame frame, int httpStatusCode) {
                    if (loadedBrowser == browser && frame != null && frame.isMain()
                            && isTrustedRendererUrl(frame.getURL(), expectedOrigin)) {
                        frame.executeJavaScript(BRIDGE_SCRIPT, frame.getURL(), 0);
                    }
                }

                @Override
                public void onLoadError(CefBrowser failedBrowser, CefFrame frame,
                                        org.cef.handler.CefLoadHandler.ErrorCode errorCode,
                                        String errorText, String failedUrl) {
                    if (failedBrowser == browser
                            && errorCode != org.cef.handler.CefLoadHandler.ErrorCode.ERR_ABORTED) {
                        SwingUtilities.invokeLater(() -> showFailure(
                                new IOException("The React page failed to load: " + errorText)));
                    }
                }
            });
            browser = cefClient.createBrowser(pageUrl, false, false);
            Component browserComponent = browser.getUIComponent();
            removeAll();
            setBorder(BorderFactory.createEmptyBorder());
            add(browserComponent, BorderLayout.CENTER);
            revalidate();
            repaint();
        } catch (RuntimeException | LinkageError error) {
            showFailure(error);
        }
    }

    private void showFailure(Throwable error) {
        LOGGER.log(Level.WARNING, "The embedded React interface could not be started.", error);
        status.setText("The React interface could not start. The Swing interface is still available.");
        if (message.getParent() != this) {
            removeAll();
            setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            add(message, BorderLayout.CENTER);
        }
        retry.setVisible(true);
        revalidate();
        repaint();
    }

    private static Path runtimeRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve("BRED").resolve("jcef");
    }

    private static boolean hasOrigin(String value, String expectedOrigin) {
        try {
            URI uri = URI.create(value);
            return uri.getUserInfo() == null && uri.getPort() >= 0
                    && expectedOrigin.equals(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean isTrustedRendererUrl(String value, String expectedOrigin) {
        try {
            URI uri = URI.create(value);
            return hasOrigin(value, expectedOrigin) && "/".equals(uri.getPath())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
