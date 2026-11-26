package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Ducky AI profile icon loaded from the packaged duckyai.png resource. */
public final class AiLogoIcon implements Icon {
    private static final BufferedImage IMAGE = loadImage();
    private final int size;

    public AiLogoIcon(int size) {
        this.size = size;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Ellipse2D circle = new Ellipse2D.Float(1, 1, size - 2, size - 2);
        Graphics2D imageGraphics = (Graphics2D) g.create();
        imageGraphics.clip(circle);
        imageGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        imageGraphics.drawImage(IMAGE, 1, 1, size - 2, size - 2, null);
        imageGraphics.dispose();
        g.setColor(RmsColors.primaryAccent());
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new Ellipse2D.Float(1.5f, 1.5f, size - 3, size - 3));
        g.dispose();
    }

    private static BufferedImage loadImage() {
        var resource = AiLogoIcon.class.getResource("/com/bred/rms/ui/logo/duckyai.png");
        if (resource == null) {
            throw new IllegalStateException("Could not load the Ducky AI profile image.");
        }
        try {
            BufferedImage image = ImageIO.read(resource);
            if (image == null) throw new IllegalStateException("The Ducky AI profile image has an unsupported format.");
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the Ducky AI profile image.", e);
        }
    }
}
