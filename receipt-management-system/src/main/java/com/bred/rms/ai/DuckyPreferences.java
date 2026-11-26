package com.bred.rms.ai;

import java.util.Locale;
import java.util.prefs.Preferences;

/** Per-user non-secret settings for Ducky's Groq connection and permission level. */
public final class DuckyPreferences {
    private static final String MODEL = "ducky.groq.model";
    private static final String CONSENT = "ducky.groq.cloudConsent";
    private static final String PERMISSION = "ducky.permission";
    private static final String DEFAULT_MODEL = "openai/gpt-oss-120b";
    private static final Preferences VALUES = Preferences.userNodeForPackage(DuckyPreferences.class);

    private DuckyPreferences() { }

    public static String model() {
        String configured = VALUES.get(MODEL, DEFAULT_MODEL);
        return "llama-3.3-70b-versatile".equals(configured) ? DEFAULT_MODEL : configured;
    }

    public static void setModel(String model) {
        if (model == null || model.isBlank() || model.length() > 120) {
            throw new IllegalArgumentException("Choose a valid Groq model name.");
        }
        VALUES.put(MODEL, model.trim());
    }

    public static boolean cloudConsent() {
        return VALUES.getBoolean(CONSENT, false);
    }

    public static void setCloudConsent(boolean consent) {
        VALUES.putBoolean(CONSENT, consent);
        if (!consent) setPermission(DuckyPermission.ASK);
    }

    public static DuckyPermission permission() {
        String value = VALUES.get(PERMISSION, DuckyPermission.ASK.name());
        try {
            return DuckyPermission.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DuckyPermission.ASK;
        }
    }

    public static void setPermission(DuckyPermission permission) {
        if (permission == null || !permission.available()) {
            throw new IllegalArgumentException("Ducky Execute permission is reserved and is not available.");
        }
        VALUES.put(PERMISSION, permission.name());
    }
}
