package com.bred.rms.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LucideIconTest {
    @Test
    void everySupportedIconPaintsAtItsRequestedSize() {
        for (String name : LucideIcon.supportedNames()) {
            LucideIcon icon = new LucideIcon(name, 20, () -> Color.WHITE);
            assertEquals(20, icon.getIconWidth(), name);
            assertEquals(20, icon.getIconHeight(), name);
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            icon.paintIcon(null, graphics, 0, 0);
            graphics.dispose();

            boolean painted = false;
            for (int y = 0; y < image.getHeight() && !painted; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        painted = true;
                        break;
                    }
                }
            }
            assertTrue(painted, "Expected " + name + " to paint non-transparent pixels.");
        }
    }

    @Test
    void rejectsUnknownNamesAndInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> new LucideIcon("missing", 16));
        assertThrows(IllegalArgumentException.class, () -> new LucideIcon("search", 0));
    }
}
