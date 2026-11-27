package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/** Consistent title and description header for modern workspace pages. */
public final class SectionHeader extends JPanel {
    private final JLabel title;
    private final JLabel description;

    public SectionHeader(String titleText, String descriptionText) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 2, 10, 2));
        title = new JLabel(titleText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(RmsColors.text());
        description = new JLabel(descriptionText);
        description.setFont(description.getFont().deriveFont(Font.PLAIN, 12f));
        description.setForeground(RmsColors.SECONDARY_TEXT);
        add(title, BorderLayout.NORTH);
        add(description, BorderLayout.SOUTH);
    }

    public void setTitle(String value) { title.setText(value); }
    public void setDescription(String value) { description.setText(value); }

    @Override
    public void updateUI() {
        super.updateUI();
        if (title != null) title.setForeground(RmsColors.text());
        if (description != null) description.setForeground(RmsColors.SECONDARY_TEXT);
    }
}
