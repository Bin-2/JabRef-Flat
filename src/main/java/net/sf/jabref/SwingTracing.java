/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.sf.jabref;

/**
 *
 * @author bin
 */
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;

public final class SwingTracing {

    private SwingTracing() {
    }

    public static void install() {
        // Optional FlatLaf inspectors if flatlaf-extras is on the classpath (safe to ignore otherwise)
        try {
            Class<?> insp = Class.forName("com.formdev.flatlaf.extras.FlatInspector");
            insp.getMethod("install", String.class).invoke(null, "ctrl shift alt X");
        } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | NoSuchMethodException | SecurityException | InvocationTargetException ignored) {
        }
        try {
            Class<?> insp2 = Class.forName("com.formdev.flatlaf.extras.FlatUIDefaultsInspector");
            insp2.getMethod("install", String.class).invoke(null, "ctrl shift alt Y");
        } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | NoSuchMethodException | SecurityException | InvocationTargetException ignored) {
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(new HierarchyDumpOnClick(), AWTEvent.MOUSE_EVENT_MASK);
//        RepaintManager.setCurrentManager(new TracingRepaintManager());
    }

    private static final class HierarchyDumpOnClick implements AWTEventListener {

        @Override
        public void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent)) {
                return;
            }
            MouseEvent me = (MouseEvent) event;
            if (me.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }

            Component src = me.getComponent();
            if (src == null) {
                return;
            }

            JRootPane root = getRootPane(src);
            if (root == null) {
                return;
            }

            Point pOnRoot = SwingUtilities.convertPoint(src, me.getPoint(), root);
            Component deepest = SwingUtilities.getDeepestComponentAt(root, pOnRoot.x, pOnRoot.y);
            if (deepest == null) {
                deepest = src;
            }

            System.out.println("\n=== SwingTracing click at " + pOnRoot + " on " + id(deepest) + " ===");
            dumpChain(deepest);
            dumpScrollContext(deepest);
            dumpListContext(deepest, root, pOnRoot);
            dumpTableContext(deepest, root, pOnRoot);
        }
    }

    private static void dumpChain(Component c) {
        System.out.println("-- Parent chain (child -> window) --");
        int i = 0;
        for (Component cur = c; cur != null; cur = cur.getParent(), i++) {
            System.out.printf("  #%d %s  bounds=%s  opaque=%s  bg=%s  dblBuf=%s%n",
                    i, id(cur), cur.getBounds(),
                    isOpaque(cur), toHex(bg(cur)), isDoubleBuffered(cur));
        }
    }

    private static void dumpScrollContext(Component c) {
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
        if (sp == null) {
            return;
        }
        JViewport vp = sp.getViewport();
        Component view = (vp != null) ? vp.getView() : null;
        System.out.println("-- JScrollPane context --");
        System.out.printf("  ScrollPane: opaque=%s bg=%s%n", sp.isOpaque(), toHex(sp.getBackground()));
        if (vp != null) {
            System.out.printf("  Viewport:  opaque=%s bg=%s%n", vp.isOpaque(), toHex(vp.getBackground()));
        }
        if (view != null) {
            System.out.printf("  View:      %s  opaque=%s bg=%s%n", id(view), isOpaque(view), toHex(bg(view)));
        }
    }

    // JDK 8-safe generic method: no wildcard-capture problems
    @SuppressWarnings("unchecked")
    private static <E> void dumpListContext(Component c, JRootPane root, Point pOnRoot) {
        JList<E> list = (JList<E>) SwingUtilities.getAncestorOfClass(JList.class, c);
        if (list == null) {
            return;
        }

        Point pOnList = SwingUtilities.convertPoint(root, pOnRoot, list);
        int idx = list.locationToIndex(pOnList);
        Rectangle cell = (idx >= 0) ? list.getCellBounds(idx, idx) : null;
        boolean insideCell = cell != null && cell.contains(pOnList);

        System.out.println("-- JList context --");
        System.out.printf("  List: opaque=%s bg=%s selBg=%s selFg=%s renderer=%s%n",
                list.isOpaque(), toHex(list.getBackground()),
                toHex(list.getSelectionBackground()), toHex(list.getSelectionForeground()),
                (list.getCellRenderer() != null ? list.getCellRenderer().getClass().getName() : "null"));

        if (idx >= 0) {
            E value = list.getModel().getElementAt(idx);
            ListCellRenderer<? super E> r = list.getCellRenderer();
            if (r != null) {
                Component rc = r.getListCellRendererComponent(
                        list, value, idx,
                        /* isSelected */ list.isSelectedIndex(idx),
                        /* cellHasFocus */ insideCell && list.hasFocus());
                System.out.printf("  Cell[%d]: rendererComp=%s opaque=%s bg=%s%n",
                        idx, id(rc), isOpaque(rc), toHex(bg(rc)));
            }
        }
    }

    private static void dumpTableContext(Component c, JRootPane root, Point pOnRoot) {
        JTable table = (JTable) SwingUtilities.getAncestorOfClass(JTable.class, c);
        if (table == null) {
            return;
        }

        Point pOnTable = SwingUtilities.convertPoint(root, pOnRoot, table);
        int row = table.rowAtPoint(pOnTable);
        int col = table.columnAtPoint(pOnTable);

        System.out.println("-- JTable context --");
        System.out.printf("  Table: opaque=%s bg=%s showGrid(H=%s,V=%s) gridColor=%s%n",
                table.isOpaque(), toHex(table.getBackground()),
                table.getShowHorizontalLines(), table.getShowVerticalLines(),
                toHex(table.getGridColor()));

        if (row >= 0 && col >= 0) {
            TableCellRenderer r = table.getCellRenderer(row, col);
            Object value = table.getValueAt(row, col);
            Component rc = r.getTableCellRendererComponent(
                    table, value, table.isCellSelected(row, col), table.hasFocus(), row, col);
            System.out.printf("  Cell[%d,%d]: rendererComp=%s opaque=%s bg=%s%n",
                    row, col, id(rc), isOpaque(rc), toHex(bg(rc)));
        }
    }

    private static final class TracingRepaintManager extends RepaintManager {

        @Override
        public synchronized void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            super.addDirtyRegion(c, x, y, w, h);
            System.out.printf("Repaint: %s rect=[%d,%d %dx%d] opaque=%s bg=%s%n",
                    id(c), x, y, w, h, c.isOpaque(), toHex(c.getBackground()));
        }
    }

    // ---- helpers (no getUI anywhere) ----
    private static JRootPane getRootPane(Component c) {
        if (c == null) {
            return null;
        }
        return (c instanceof JRootPane) ? (JRootPane) c : SwingUtilities.getRootPane(c);
    }

    private static String id(Component c) {
        if (c == null) {
            return "null";
        }
        String name = c.getName();
        return c.getClass().getName() + (name != null ? "('" + name + "')" : "");
    }

    private static boolean isOpaque(Component c) {
        return (c instanceof JComponent) ? ((JComponent) c).isOpaque() : true;
    }

    private static boolean isDoubleBuffered(Component c) {
        return (c instanceof JComponent) && ((JComponent) c).isDoubleBuffered();
    }

    private static Color bg(Component c) {
        return (c != null) ? c.getBackground() : null;
    }

    private static String toHex(Color c) {
        if (c == null) {
            return "null";
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
