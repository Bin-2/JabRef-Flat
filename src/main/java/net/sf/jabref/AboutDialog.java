/*  Copyright (C) 2003-2015 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
 */
package net.sf.jabref;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.net.URI;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * Native Swing About dialog for the desktop application.
 */
public final class AboutDialog extends JDialog {

    private static final int DIALOG_WIDTH = 560;
    private static final int HEADER_HEIGHT = 132;
    private static final String WEBSITE = "https://www.jabref.org/";

    public AboutDialog(Window owner) {
        super(owner, Globals.lang("About JabRef"), ModalityType.APPLICATION_MODAL);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout());

        add(new HeaderPanel(), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);

        getRootPane().registerKeyboardAction(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel createBody() {
        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setBorder(new EmptyBorder(20, 26, 20, 26));

        JLabel description = new JLabel(
                "Open-source reference management software.");
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont != null) {
            description.setFont(labelFont.deriveFont(Font.PLAIN, labelFont.getSize2D() + 1.0f));
        }
        body.add(description, BorderLayout.NORTH);

        JPanel info = new JPanel(new GridBagLayout());
        info.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(3, 0, 3, 18);
        c.anchor = GridBagConstraints.WEST;

        addInfoRow(info, c, Globals.lang("Version"), versionLabel());
        addInfoRow(info, c, "Java", systemProperty("java.version"));
        addInfoRow(info, c, Globals.lang("Platform"), platformLabel());
        addInfoRow(info, c, Globals.lang("License"), "GNU GPL v2 or later");

        body.add(info, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setOpaque(false);

        JButton website = new JButton("jabref.org");
        website.setToolTipText(WEBSITE);
        website.addActionListener(e -> openWebsite());
        bottom.add(website, BorderLayout.WEST);

        JButton close = new JButton(Globals.lang("Close"));
        close.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(close);
        bottom.add(close, BorderLayout.EAST);

        body.add(bottom, BorderLayout.SOUTH);
        return body;
    }

    private void addInfoRow(JPanel panel, GridBagConstraints c, String label, String value) {
        c.gridx = 0;
        c.weightx = 0;
        JLabel name = new JLabel(label + ":");
        Font font = name.getFont();
        name.setFont(font.deriveFont(Font.BOLD));
        panel.add(name, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(value), c);
        c.fill = GridBagConstraints.NONE;
        c.gridy++;
    }

    private static String versionLabel() {
        String version = Globals.VERSION;
        return ((version == null) || version.trim().isEmpty())
                ? "2.11 development" : version.trim();
    }

    private static String platformLabel() {
        String os = systemProperty("os.name");
        String version = systemProperty("os.version");
        String arch = systemProperty("os.arch");
        return os + " " + version + " (" + arch + ")";
    }

    private static String systemProperty(String key) {
        String value = System.getProperty(key);
        return (value == null) ? "" : value;
    }

    private void openWebsite() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(WEBSITE));
                return;
            }
        } catch (Exception ignored) {
        }

        JOptionPane.showMessageDialog(this, WEBSITE,
                Globals.lang("About JabRef"), JOptionPane.INFORMATION_MESSAGE);
    }

    private static final class HeaderPanel extends JPanel {

        private HeaderPanel() {
            setPreferredSize(new Dimension(DIALOG_WIDTH, HEADER_HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);

                JabRefLogo.paint(g, 28, 23, 84, 84);

                Font base = UIManager.getFont("Label.font");
                if (base == null) {
                    base = new Font("SansSerif", Font.PLAIN, 12);
                }

                Color primary = UIManager.getColor("Label.foreground");
                if (primary == null) {
                    primary = getForeground();
                }
                Color secondary = UIManager.getColor("Label.disabledForeground");
                if (secondary == null) {
                    secondary = primary;
                }

                g.setColor(primary);
                g.setFont(base.deriveFont(Font.BOLD, 32.0f));
                g.drawString("JabRef", 132, 54);

                g.setColor(secondary);
                g.setFont(base.deriveFont(Font.PLAIN, 15.0f));
                g.drawString("Reference Manager", 134, 80);

                g.setColor(primary);
                g.setFont(base.deriveFont(Font.BOLD, 12.0f));
                g.drawString(versionLabel(), 134, 104);

                Color separator = UIManager.getColor("Separator.foreground");
                if (separator == null) {
                    separator = UIManager.getColor("controlShadow");
                }
                if (separator != null) {
                    g.setColor(separator);
                    g.drawLine(24, HEADER_HEIGHT - 1, getWidth() - 24, HEADER_HEIGHT - 1);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
