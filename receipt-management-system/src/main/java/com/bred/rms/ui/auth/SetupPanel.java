package com.bred.rms.ui.auth;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Font;
import java.awt.GridBagLayout;

/** Shown instead of the sign-in screen when the cloud service is unavailable. */
public final class SetupPanel extends JPanel {
    public SetupPanel() { this(null); }

    /** @param problem extra detail to show (for example a rejected key), or null */
    public SetupPanel(String problem) {
        super(new GridBagLayout());
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
        JLabel title = new JLabel("Cloud Database unavailable");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(RmsColors.primary());
        JTextArea text = new JTextArea(
                "BRED could not connect to Cloud Database. Check your internet connection or contact your administrator."
                + (problem == null || problem.isBlank() ? "" : "\n\nProblem found: " + problem));
        text.setEditable(false);
        text.setOpaque(false);
        text.setFont(text.getFont().deriveFont(14f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        text.setAlignmentX(LEFT_ALIGNMENT);
        card.add(title);
        card.add(javax.swing.Box.createVerticalStrut(14));
        card.add(text);
        add(card);
    }
}
