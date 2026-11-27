package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import java.awt.BorderLayout;
import java.awt.Font;

/** A glass surface for concise dashboard or section summaries. */
public final class GlassCard extends GlassPanel {
    private final JLabel title;

    public GlassCard(String titleText) {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        title = new JLabel(titleText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(RmsColors.text());
        add(title, BorderLayout.NORTH);
    }

    public JLabel titleLabel() {
        return title;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (title != null) title.setForeground(RmsColors.text());
    }
}
