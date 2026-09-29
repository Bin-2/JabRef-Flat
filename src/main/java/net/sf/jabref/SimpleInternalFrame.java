//
package net.sf.jabref;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Paint;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

/**
 * Lightweight side-pane container with a title, optional icon and toolbar.
 *
 * This replaces JGoodies UIF Lite SimpleInternalFrame while preserving the
 * appearance and small API surface used by JabRef.
 */
public class SimpleInternalFrame extends JPanel {

    private final JLabel titleLabel;
    private final GradientPanel gradientPanel;
    private final JPanel headerPanel;

    private Component content;
    private JToolBar toolBar;
    private boolean selected;

    public SimpleInternalFrame(String title) {
        this(null, title, null, null);
    }

    public SimpleInternalFrame(Icon icon, String title) {
        this(icon, title, null, null);
    }

    public SimpleInternalFrame(String title, JToolBar toolBar, JComponent content) {
        this(null, title, toolBar, content);
    }

    public SimpleInternalFrame(Icon icon, String title, JToolBar toolBar, JComponent content) {
        super(new BorderLayout());

        selected = false;
        titleLabel = new JLabel(title, icon, JLabel.LEADING);
        titleLabel.setOpaque(false);

        gradientPanel = new GradientPanel(new BorderLayout(), getHeaderBackground());
        gradientPanel.add(titleLabel, BorderLayout.WEST);
        gradientPanel.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 1));

        headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(gradientPanel, BorderLayout.CENTER);
        headerPanel.setBorder(new RaisedHeaderBorder());
        headerPanel.setOpaque(false);
        add(headerPanel, BorderLayout.NORTH);

        setToolBar(toolBar);
        if (content != null) {
            setContent(content);
        }

        setBorder(new ShadowBorder());
        setSelected(true);
    }

    public Icon getFrameIcon() {
        return titleLabel.getIcon();
    }

    public void setFrameIcon(Icon icon) {
        Icon oldIcon = getFrameIcon();
        titleLabel.setIcon(icon);
        firePropertyChange("frameIcon", oldIcon, icon);
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    public void setTitle(String title) {
        String oldTitle = getTitle();
        titleLabel.setText(title);
        firePropertyChange("title", oldTitle, title);
    }

    public Font getTitleFont() {
        return titleLabel.getFont();
    }

    public void setTitleFont(Font font) {
        Font oldFont = getTitleFont();
        titleLabel.setFont(font);
        firePropertyChange("titleFont", oldFont, font);
    }

    public JToolBar getToolBar() {
        return toolBar;
    }

    public void setToolBar(JToolBar newToolBar) {
        JToolBar oldToolBar = toolBar;
        if (oldToolBar == newToolBar) {
            return;
        }

        if (oldToolBar != null) {
            headerPanel.remove(oldToolBar);
        }

        toolBar = newToolBar;
        if (newToolBar != null) {
            newToolBar.setFloatable(false);
            newToolBar.setBorder(BorderFactory.createEmptyBorder());
            headerPanel.add(newToolBar, BorderLayout.EAST);
        }

        updateHeader();
        headerPanel.revalidate();
        headerPanel.repaint();
        firePropertyChange("toolBar", oldToolBar, newToolBar);
    }

    public Component getContent() {
        return content;
    }

    public void setContent(Component newContent) {
        Component oldContent = content;
        if (oldContent == newContent) {
            return;
        }

        if (oldContent != null) {
            remove(oldContent);
        }

        content = newContent;
        if (newContent != null) {
            add(newContent, BorderLayout.CENTER);
        }

        revalidate();
        repaint();
        firePropertyChange("content", oldContent, newContent);
    }

    public Container getContentPane() {
        if (content instanceof Container) {
            return (Container) content;
        }
        return this;
    }

    public Border getContentPaneBorder() {
        if (content instanceof JComponent) {
            return ((JComponent) content).getBorder();
        }
        return null;
    }

    public void setContentPaneBorder(Border border) {
        if (content instanceof JComponent) {
            ((JComponent) content).setBorder(border);
        }
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean newSelected) {
        boolean oldSelected = selected;
        selected = newSelected;
        updateHeader();
        firePropertyChange("selected", oldSelected, newSelected);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (titleLabel != null) {
            updateHeader();
        }
    }

    private void updateHeader() {
        gradientPanel.setBackground(getHeaderBackground());
        gradientPanel.setOpaque(isSelected());
        titleLabel.setForeground(getTextForeground(isSelected()));
        headerPanel.repaint();
    }

    protected Color getTextForeground(boolean active) {
        Color color = UIManager.getColor(active
                ? "SimpleInternalFrame.activeTitleForeground"
                : "SimpleInternalFrame.inactiveTitleForeground");
        if (color != null) {
            return color;
        }

        color = UIManager.getColor(active
                ? "InternalFrame.activeTitleForeground"
                : "Label.foreground");
        return color != null ? color : getForeground();
    }

    protected Color getHeaderBackground() {
        Color color = UIManager.getColor("SimpleInternalFrame.activeTitleBackground");
        if (color == null) {
            color = UIManager.getColor("InternalFrame.activeTitleBackground");
        }
        if (color == null) {
            color = UIManager.getColor("Panel.background");
        }
        return color != null ? color : getBackground();
    }

    private static Color getUiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static final class GradientPanel extends JPanel {

        private GradientPanel(LayoutManager layout, Color background) {
            super(layout);
            setBackground(background);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!isOpaque()) {
                return;
            }

            Color control = UIManager.getColor("control");
            if (control == null) {
                control = UIManager.getColor("Panel.background");
            }
            if (control == null) {
                control = getBackground();
            }

            Graphics2D g2 = (Graphics2D) g;
            Paint oldPaint = g2.getPaint();
            g2.setPaint(new GradientPaint(0, 0, getBackground(), getWidth(), 0, control));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setPaint(oldPaint);
        }
    }

    private static final class RaisedHeaderBorder extends AbstractBorder {

        private static final Insets INSETS = new Insets(1, 1, 1, 0);

        @Override
        public Insets getBorderInsets(Component c) {
            return INSETS;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color highlight = getUiColor("controlLtHighlight", Color.WHITE);
            Color shadow = getUiColor("controlShadow", Color.GRAY);

            g.translate(x, y);
            g.setColor(highlight);
            g.fillRect(0, 0, width, 1);
            g.fillRect(0, 1, 1, height - 1);
            g.setColor(shadow);
            g.fillRect(0, height - 1, width, 1);
            g.translate(-x, -y);
        }
    }

    private static final class ShadowBorder extends AbstractBorder {

        private static final Insets INSETS = new Insets(1, 1, 3, 3);

        @Override
        public Insets getBorderInsets(Component c) {
            return INSETS;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color shadow = getUiColor("controlShadow", Color.GRAY);
            Color mediumShadow = new Color(shadow.getRed(), shadow.getGreen(), shadow.getBlue(), 170);
            Color lightShadow = new Color(shadow.getRed(), shadow.getGreen(), shadow.getBlue(), 70);

            g.translate(x, y);

            g.setColor(shadow);
            g.fillRect(0, 0, width - 3, 1);
            g.fillRect(0, 0, 1, height - 3);
            g.fillRect(width - 3, 1, 1, height - 3);
            g.fillRect(1, height - 3, width - 3, 1);

            g.setColor(mediumShadow);
            g.fillRect(width - 3, 0, 1, 1);
            g.fillRect(0, height - 3, 1, 1);
            g.fillRect(width - 2, 1, 1, height - 3);
            g.fillRect(1, height - 2, width - 3, 1);

            g.setColor(lightShadow);
            g.fillRect(width - 2, 0, 1, 1);
            g.fillRect(0, height - 2, 1, 1);
            g.fillRect(width - 2, height - 2, 1, 1);
            g.fillRect(width - 1, 1, 1, height - 2);
            g.fillRect(1, height - 1, width - 2, 1);

            g.translate(-x, -y);
        }
    }
}
