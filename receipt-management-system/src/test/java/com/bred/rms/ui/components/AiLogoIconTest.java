package com.bred.rms.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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

    @Test
    void paintingPreservesTheParentClip() {
        AiLogoIcon icon = new AiLogoIcon(48);
        BufferedImage canvas = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        int background = Color.MAGENTA.getRGB();
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            graphics.setClip(new Rectangle(0, 0, 48, 24));
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }

        for (int y = 24; y < canvas.getHeight(); y++) {
            for (int x = 0; x < canvas.getWidth(); x++) {
                assertEquals(background, canvas.getRGB(x, y));
            }
        }
    }
}
