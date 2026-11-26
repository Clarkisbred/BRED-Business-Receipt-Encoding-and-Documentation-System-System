package com.bred.rms.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAvatarTest {
    @Test
    void matchesTheDuckProfileByNormalizedEmail() {
        assertTrue(UserAvatar.isDuckProfile(" CLARKHILARIO.BREAD@GMAIL.COM "));
        assertFalse(UserAvatar.isDuckProfile("someone.else@example.com"));
        assertFalse(UserAvatar.isDuckProfile(null));
    }

    @Test
    void loadsTheDuckImageForClark() {
        UserAvatar avatar = new UserAvatar(38, 36, false);
        assertDoesNotThrow(() -> avatar.setProfile("Clark Hilario", "clarkhilario.bread@gmail.com"));
    }

    @Test
    void imagePaintingPreservesTheParentClip() {
        UserAvatar avatar = new UserAvatar(76, 54, false);
        avatar.setProfile("Clark Hilario", "clarkhilario.bread@gmail.com");
        avatar.setSize(76, 76);
        BufferedImage canvas = new BufferedImage(76, 76, BufferedImage.TYPE_INT_ARGB);
        int background = Color.MAGENTA.getRGB();
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            graphics.setClip(new Rectangle(0, 0, 76, 40));
            avatar.paint(graphics);
        } finally {
            graphics.dispose();
        }

        for (int y = 40; y < canvas.getHeight(); y++) {
            for (int x = 0; x < canvas.getWidth(); x++) {
                assertEquals(background, canvas.getRGB(x, y));
            }
        }
    }
}
