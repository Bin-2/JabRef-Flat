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
package net.sf.jabref.util;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import javax.swing.*;

import net.sf.jabref.Globals;

/**
 * This class redirects the System.err stream so it goes both the way it
 * normally goes, and into a ByteArrayOutputStream. We can use this stream to
 * display any error messages and stack traces to the user. Such an error
 * console can be useful in getting complete bug reports, especially from
 * Windows users, without asking users to run JabRef in a command window to
 * catch the error info.
 *
 * It also offers a separate tab for the log output.
 *
 * User: alver Date: Mar 1, 2006 Time: 11:13:03 PM
 */
public class ErrorConsole extends Handler {

    private final ByteArrayOutputStream errByteStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();

    private final Deque<String> logOutput = new ArrayDeque<String>();
    private final SimpleFormatter fmt = new SimpleFormatter();
    private static final int MAX_LOG_RECORDS = 2000;

    private static ErrorConsole instance = null;

    private JDialog dialog;

    public static ErrorConsole getInstance() {
        if (instance == null) {
            instance = new ErrorConsole();
        }

        return instance;
    }

    private ErrorConsole() {
        PrintStream myErr = new PrintStream(errByteStream);
        PrintStream tee = new TeeStream(System.err, myErr);
        System.setErr(tee);
        myErr = new PrintStream(outByteStream);
        tee = new TeeStream(System.out, myErr);
        System.setOut(tee);
    }

    private String getErrorMessages() {
        return errByteStream.toString();
    }

    private String getOutput() {
        return outByteStream.toString();
    }

    private synchronized String getLog() {
        StringBuilder sb = new StringBuilder();
        for (String record : logOutput) {
            sb.append(record);
        }
        return sb.toString();
    }

    /**
     *
     * @param tabbed the tabbed pane to add the tab to
     * @param output the text to display in the tab
     * @param ifEmpty Text to output if textbox is emtpy. may be null
     */
    private void addTextArea(JTabbedPane tabbed, String title, String output, String ifEmpty) {
        JTextArea ta = new JTextArea(output);
        ta.setEditable(false);
        ta.setLineWrap(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, ta.getFont().getSize()));

        if ((ifEmpty != null) && (ta.getText().length() == 0)) {
            ta.setText(ifEmpty);
        }

        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        tabbed.addTab(title, sp);
    }

    public void displayErrorConsole(JFrame parent) {
        if ((dialog != null) && dialog.isDisplayable()) {
            if (!dialog.isVisible()) {
                dialog.setVisible(true);
            }
            dialog.toFront();
            return;
        }

        final JDialog currentDialog = new JDialog(parent, Globals.lang("Program output"), false);
        dialog = currentDialog;
        currentDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        currentDialog.getRootPane().registerKeyboardAction(
                new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentDialog.dispose();
            }
        },
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JTabbedPane tabbed = new JTabbedPane();
        addTextArea(tabbed, Globals.lang("Output"), getOutput(), null);
        addTextArea(tabbed, Globals.lang("Exceptions"), getErrorMessages(),
                Globals.lang("No exceptions have ocurred."));
        addTextArea(tabbed, Globals.lang("Log"), getLog(), null);

        JButton closeButton = new JButton(Globals.lang("Close"));
        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentDialog.dispose();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(tabbed, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        currentDialog.setContentPane(content);
        currentDialog.setMinimumSize(new Dimension(600, 400));
        currentDialog.setSize(850, 600);
        currentDialog.setLocationRelativeTo(parent);
        currentDialog.setResizable(true);
        currentDialog.setVisible(true);
    }

    class ErrorConsoleAction extends AbstractAction {

        JFrame frame;

        public ErrorConsoleAction(JFrame frame) {
            super(Globals.menuTitle("Show error console"));
            putValue(SHORT_DESCRIPTION, Globals.lang("Display all error messages"));
            this.frame = frame;
        }

        public void actionPerformed(ActionEvent e) {
            displayErrorConsole(frame);
        }
    }

    public AbstractAction getAction(JFrame parent) {
        return new ErrorConsoleAction(parent);
    }

    // All writes to this print stream are copied to two print streams
    private static class TeeStream extends PrintStream {

        private final PrintStream out;

        TeeStream(PrintStream out1, PrintStream out2) {
            super(out1);
            out = out2;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            out.write(buf, off, len);
        }

        @Override
        public void flush() {
            super.flush();
            out.flush();
        }
    }

    /* * * methods for Logging (required by Handler) * * */
    @Override
    public void close() throws SecurityException {
    }

    @Override
    public void flush() {
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }

        logOutput.addLast(fmt.format(record));
        while (logOutput.size() > MAX_LOG_RECORDS) {
            logOutput.removeFirst();
        }
    }
}
