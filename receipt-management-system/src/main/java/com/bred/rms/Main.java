package com.bred.rms;

import com.bred.rms.config.AppConfig;
import com.bred.rms.config.AppContext;
import com.bred.rms.api.LocalApiServer;
import com.bred.rms.ui.MainWindow;
import com.bred.rms.ui.theme.ThemeManager;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Launches the Business Receipt Encoding and Documentation System (BRED). */
public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static volatile LocalApiServer localApiServer;

    /** Prevents instantiation of the application launcher. */
    private Main() { }

    /** Starts the application using its configured cloud database. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ThemeManager.installSaved();
            AppContext ctx = null;
            String problem = null;
            try {
                AppConfig config = AppConfig.load();
                if (config.isConfigured()) {
                    ctx = AppContext.create(config);
                } else {
                    problem = "Cloud Database configuration is incomplete. Contact your administrator.";
                }
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING, "Cloud Database configuration could not be loaded.", e);
                problem = "Cloud Database configuration could not be loaded. Contact your administrator.";
            }
            LocalApiServer api = ctx == null ? null : startLocalApi(ctx);
            new MainWindow(ctx, problem, api).setVisible(true);
        });
    }

    private static LocalApiServer startLocalApi(AppContext context) {
        try {
            LocalApiServer server = LocalApiServer.start(context);
            localApiServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "bred-local-api-shutdown"));
            return server;
        } catch (IOException error) {
            LOGGER.log(Level.SEVERE, "The BRED loopback API could not start; the Swing fallback remains available.",
                    error);
            return null;
        }
    }

    static LocalApiServer localApiServer() {
        return localApiServer;
    }
}
