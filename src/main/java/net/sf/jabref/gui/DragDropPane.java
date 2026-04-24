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
package net.sf.jabref.gui;

import com.formdev.flatlaf.FlatLightLaf;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.Icon;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import net.sf.jabref.GUIGlobals;

/**
 * Extends the JTabbedPane class to support Drag&Drop of Tabs.
 *
 * @author kleinms, strassfn
 */
public class DragDropPane extends JTabbedPane {

    private boolean draggingState = false; // State var if we are at dragging or not
    private int indexDraggedTab; // The index of the tab we drag at the moment
    MarkerPane markerPane; // The glass panel for painting the position marker

    public DragDropPane() {
        super();
        indexDraggedTab = -1;
        markerPane = new MarkerPane();
        markerPane.setVisible(false);

        // -------------------------------------------
        // Adding listeners for Drag&Drop Actions
        // -------------------------------------------
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) { // Mouse is dragging
                // Calculates the tab index based on the mouse position
                int indexActTab = getUI().tabForCoordinate(DragDropPane.this,
                        e.getX(), e.getY());
                if (!draggingState) { // We are not at tab dragging
                    if (indexActTab >= 0) { // Mouse is above a tab, otherwise tabNumber would be -1
                        // -->Now we are at tab tragging
                        draggingState = true; // Mark now we are at dragging
                        indexDraggedTab = indexActTab; // Set draggedTabIndex to the tabNumber where we are now
                        repaint();
                    }

                } else { //We are at tab tragging
                    if (indexDraggedTab >= 0 && indexActTab >= 0) { //Is it a valid scenario?
                        boolean toTheLeft = e.getX() <= getUI().getTabBounds(DragDropPane.this, indexActTab).getCenterX(); //Go to the left or to the right of the actual Tab
                        DragDropPane.this.getRootPane().setGlassPane(markerPane); //Set the MarkerPane as glass Pane
                        Rectangle actTabRect = SwingUtilities.convertRectangle(DragDropPane.this, getBoundsAt(indexActTab),
                                DragDropPane.this.markerPane); //Rectangle with the same dimensions as the tab at the mouse position
                        if (toTheLeft) {
                            markerPane.setPicLocation(new Point(actTabRect.x, actTabRect.y
                                    + actTabRect.height)); //Set pic to the left of the tab at the mouse position
                        } else {
                            markerPane.setPicLocation(new Point(actTabRect.x + actTabRect.width, actTabRect.y
                                    + actTabRect.height)); //Set pic to the right of the tab at the mouse position
                        }
                        markerPane.setVisible(true);
                        markerPane.repaint();
                        repaint();
                    } else { //We have no valid tab tragging scenario
                        markerPane.setVisible(false);
                        markerPane.repaint();
                    }
                }
                super.mouseDragged(e);
            }
        });

        addMouseListener(new MouseAdapter() {
            public void mouseReleased(MouseEvent e) {
                DragDropPane.this.markerPane.setVisible(false);

                int indexActTab = getUI().tabForCoordinate(DragDropPane.this, e.getX(), e.getY());
                try {
                    if (indexDraggedTab >= 0 && indexActTab >= 0 && indexDraggedTab != indexActTab) {
                        if (draggingState) {
                            boolean toTheLeft = e.getX() <= getUI().getTabBounds(DragDropPane.this, indexActTab).getCenterX();

                            Component actTab = getComponentAt(indexDraggedTab);
                            String actTabTitle = getTitleAt(indexDraggedTab);
                            Icon actTabIcon = getIconAt(indexDraggedTab);
                            String actTabTooltip = getToolTipTextAt(indexDraggedTab);
                            boolean actTabEnabled = isEnabledAt(indexDraggedTab);
                            Component customTabComponent = getTabComponentAt(indexDraggedTab);
                            int mnemonic = getMnemonicAt(indexDraggedTab);
                            int displayedMnemonicIndex = getDisplayedMnemonicIndexAt(indexDraggedTab);
                            Color foreground = getForegroundAt(indexDraggedTab);
                            Color background = getBackgroundAt(indexDraggedTab);

                            removeTabAt(indexDraggedTab);

                            int newTabPos;
                            if (indexActTab < indexDraggedTab) {
                                if (toTheLeft && indexActTab < DragDropPane.this.getTabCount()) {
                                    newTabPos = indexActTab;
                                } else {
                                    newTabPos = indexActTab + 1;
                                }
                            } else {
                                if (toTheLeft && indexActTab > 0) {
                                    newTabPos = indexActTab - 1;
                                } else {
                                    newTabPos = indexActTab;
                                }
                            }

                            insertTab(actTabTitle, actTabIcon, actTab, actTabTooltip, newTabPos);
                            setEnabledAt(newTabPos, actTabEnabled);
                            setTabComponentAt(newTabPos, customTabComponent);
                            setMnemonicAt(newTabPos, mnemonic);
                            setDisplayedMnemonicIndexAt(newTabPos, displayedMnemonicIndex);
                            setForegroundAt(newTabPos, foreground);
                            setBackgroundAt(newTabPos, background);
                            DragDropPane.this.setSelectedIndex(newTabPos);
                        }
                    }
                } finally {
                    draggingState = false;
                    indexDraggedTab = -1;
                }
            }
        });
    }

    /**
     * A glass panel which sets the marker for Dragging of Tabs.
     *
     */
    class MarkerPane extends JPanel {

        private Point locationP;
        private Image markerImg;

        public MarkerPane() {
            setOpaque(false);
            markerImg = Toolkit.getDefaultToolkit().getImage(
                    GUIGlobals.getIconUrl("dragNdropArrow")); // Sets the marker image
        }

        @Override
        public void paintComponent(Graphics g) {
            ((Graphics2D) g).setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 0.9f)); // Set transparency
            g.drawImage(markerImg, locationP.x - (markerImg.getWidth(null) / 2),
                    locationP.y, null); // draw the image at the middle of the given location
        }

        /**
         * Sets the new location, where the marker should be placed.
         *
         * @param pt the point for the marker
         */
        public void setPicLocation(Point pt) {
            this.locationP = pt;
        }

    }
}
