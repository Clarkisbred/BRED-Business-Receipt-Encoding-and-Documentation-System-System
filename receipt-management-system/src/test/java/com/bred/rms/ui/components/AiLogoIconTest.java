package com.bred.rms.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiLogoIconTest {
    @Test
    void loadsAndPaintsThePackagedDuckyImage() {
        AiLogoIcon icon = new AiLogoIcon(48);
        BufferedImage canvas = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);

        assertEquals(48, icon.getIconWidth());
        var graphics = canvas.createGraphics();
        try {
            assertDoesNotThrow(() -> icon.paintIcon(null, graphics, 0, 0));
        } finally {
            graphics.dispose();
        }
    }
}
