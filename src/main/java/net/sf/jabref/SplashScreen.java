/*  Copyright (C) 2003-2011 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package net.sf.jabref;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Frame;
import java.awt.Window;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Lightweight startup splash screen.
 *
 * The background is painted directly with Java2D.
 */
public class SplashScreen extends Window {

    private static final int SPLASH_WIDTH = 640;
    private static final int SPLASH_HEIGHT = 360;

    private static final Color TEXT = new Color(28, 31, 54);
    private static final Color MUTED_TEXT = new Color(91, 96, 125);
    private static final Color ACCENT = new Color(77, 73, 150);
    private static final Color ACCENT_LIGHT = new Color(112, 108, 193);
    private final BufferedImage background;

    private volatile String status = "Starting JabRef...";
    private volatile int progress = 8;
    private boolean paintCalled;

    public SplashScreen() {
        // No hidden owner Frame is required. Keeping the splash ownerless avoids
        // recursive AWT disposal of an owner and its owned splash window.
        super((Frame) null);

        setSize(SPLASH_WIDTH, SPLASH_HEIGHT);
        setLocationRelativeTo(null);
        background = createBackground();
    }

    private BufferedImage createBackground() {
        BufferedImage image = new BufferedImage(
                SPLASH_WIDTH, SPLASH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            enableQualityRendering(g);

            // background.
            g.setPaint(new GradientPaint(
                    0, 0, new Color(252, 252, 255),
                    SPLASH_WIDTH, SPLASH_HEIGHT, new Color(225, 229, 250)));
            g.fillRect(0, 0, SPLASH_WIDTH, SPLASH_HEIGHT);

            // geometric waves near the bottom.
            GeneralPath backWave = new GeneralPath();
            backWave.moveTo(0, 255);
            backWave.curveTo(160, 285, 285, 325, 640, 265);
            backWave.lineTo(640, 360);
            backWave.lineTo(0, 360);
            backWave.closePath();
            g.setColor(new Color(165, 173, 231, 48));
            g.fill(backWave);

            GeneralPath frontWave = new GeneralPath();
            frontWave.moveTo(0, 300);
            frontWave.curveTo(185, 330, 390, 348, 640, 305);
            frontWave.lineTo(640, 360);
            frontWave.lineTo(0, 360);
            frontWave.closePath();
            g.setColor(new Color(118, 126, 210, 34));
            g.fill(frontWave);

            // JabRef mark
            JabRefLogo.paint(g, 42, 72, 116, 116);

            g.setColor(TEXT);
            g.setFont(new Font("SansSerif", Font.BOLD, 46));
            g.drawString("JabRef", 178, 133);

            g.setColor(MUTED_TEXT);
            g.setFont(new Font("SansSerif", Font.PLAIN, 20));
            g.drawString("Reference Manager", 180, 163);

            String version = getVersionLabel();
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            FontMetrics versionMetrics = g.getFontMetrics();
            int versionWidth = versionMetrics.stringWidth(version) + 28;
            Shape versionPill = new RoundRectangle2D.Double(
                    180, 180, versionWidth, 30, 30, 30);
            g.setColor(new Color(97, 91, 181, 28));
            g.fill(versionPill);
            g.setColor(TEXT);
            g.drawString(version, 194, 201);

            // Very subtle bibliography motif. Decorative only.
            g.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g.setColor(new Color(83, 92, 161, 30));
            int motifX = 420;
            int motifY = 70;
            g.drawString("@article{", motifX, motifY);
            g.drawString("  author = {Author, A.},", motifX + 8, motifY + 20);
            g.drawString("  title  = {An Example Title},", motifX + 8, motifY + 40);
            g.drawString("  year   = {2026}", motifX + 8, motifY + 60);
            g.drawString("}", motifX, motifY + 80);
            g.drawString("[1] Author, A.  Journal of Examples.", motifX, motifY + 118);
            g.drawString("    DOI: 10.1234/example.001", motifX, motifY + 138);

            // JabRef mark.
            JabRefLogo.paint(g, 480, 212, 145, 145, ACCENT_LIGHT, 0.08f);

            // Footer.
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setColor(new Color(73, 77, 121, 170));
            String footer = "jabref.org";
            int footerWidth = g.getFontMetrics().stringWidth(footer);
            g.drawString(footer, SPLASH_WIDTH - footerWidth - 22, SPLASH_HEIGHT - 24);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static String getVersionLabel() {
        String version = Globals.VERSION;
        if ((version == null) || version.trim().isEmpty()) {
            return "2.11 development";
        }
        return version.trim();
    }

    private static void enableQualityRendering(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
    }

    /**
     * Update the startup phase shown by the splash.
     *
     * @param text status text to display
     * @param percentage approximate startup progress, 0..100
     */
    public void setStatus(String text, int percentage) {
        if (text != null) {
            status = text;
        }
        progress = Math.max(0, Math.min(100, percentage));

        if (EventQueue.isDispatchThread()) {
            repaint();
        } else {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    repaint();
                }
            });
        }
    }

    @Override
    public void update(Graphics g) {
        // Repaint the complete cached background and the small dynamic overlay.
        // This avoids the default clear-before-paint flicker of AWT Window.
        paint(g);
    }

    @Override
    public void paint(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            enableQualityRendering(g);
            g.drawImage(background, 0, 0, this);

            // Dynamic startup status.
            g.setFont(new Font("SansSerif", Font.PLAIN, 15));
            g.setColor(TEXT);
            g.drawString(status, 44, 287);

            int barX = 44;
            int barY = 303;
            int barWidth = 310;
            int barHeight = 6;

            g.setColor(new Color(77, 73, 150, 28));
            g.fillRoundRect(barX, barY, barWidth, barHeight, barHeight, barHeight);

            int filledWidth = Math.max(0, (barWidth * progress) / 100);
            if (filledWidth > 0) {
                g.setColor(ACCENT);
                g.fillRoundRect(barX, barY, filledWidth, barHeight,
                        barHeight, barHeight);
            }
        } finally {
            g.dispose();
        }

        if (!paintCalled) {
            paintCalled = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Constructs and displays the splash window.
     *
     * @return the splash window, which should be disposed when startup ends
     */
    public static SplashScreen splash() {
        SplashScreen splash = new SplashScreen();

        splash.setVisible(true);
        splash.toFront();

        // Ensure the splash has been painted before startup continues. Keep the
        // existing timeout so an obscured window cannot block startup forever.
        if (!EventQueue.isDispatchThread()) {
            synchronized (splash) {
                if (!splash.paintCalled) {
                    try {
                        splash.wait(3000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return splash;
    }
}
