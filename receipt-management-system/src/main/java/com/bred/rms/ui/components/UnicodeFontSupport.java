package com.bred.rms.ui.components;

import java.awt.Font;

/** Selects a logical fallback font when the preferred UI font cannot display message text. */
public final class UnicodeFontSupport {
    private static final String[] FALLBACK_FAMILIES = {
            "Segoe UI Emoji", "Segoe UI Symbol", Font.DIALOG, "Noto Color Emoji", "Apple Color Emoji"
    };

    private UnicodeFontSupport() { }

    public static Font forText(String text, Font preferred) {
        if (preferred == null) throw new IllegalArgumentException("preferred");
        if (text == null || text.isEmpty() || preferred.canDisplayUpTo(text) == -1) return preferred;
        for (String family : FALLBACK_FAMILIES) {
            Font fallback = new Font(family, preferred.getStyle(), preferred.getSize())
                    .deriveFont(preferred.getSize2D());
            if (fallback.canDisplayUpTo(text) == -1) return fallback;
        }
        return preferred;
    }
}
