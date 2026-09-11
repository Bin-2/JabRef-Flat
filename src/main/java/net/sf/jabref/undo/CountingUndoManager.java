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
package net.sf.jabref.undo;

import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

import net.sf.jabref.BasePanel;

public class CountingUndoManager extends UndoManager {

    private int unchangedPoint = 0,
            current = 0;
    private boolean unchangedPointValid = true;
    private BasePanel panel = null;

    public CountingUndoManager(BasePanel basePanel) {
        super();
        panel = basePanel;
    }

    public synchronized boolean addEdit(UndoableEdit edit) {
        // Adding an edit after undoing past the saved state discards the redo
        // branch containing that state. The old numeric depth must therefore
        // no longer be considered a valid unchanged point.
        if (unchangedPointValid && (current < unchangedPoint)) {
            unchangedPointValid = false;
        }

        current++;
        boolean added = super.addEdit(edit);
        panel.updateUndoRedoActions();
        return added;
    }

    public synchronized void undo() throws CannotUndoException {
        super.undo();
        current--;
        panel.updateEntryEditorIfShowing();
        panel.updateUndoRedoActions();
    }

    public synchronized void redo() throws CannotRedoException {
        super.redo();
        current++;
        panel.updateEntryEditorIfShowing();
        panel.updateUndoRedoActions();
    }

    public synchronized void markUnchanged() {
        unchangedPoint = current;
        unchangedPointValid = true;
    }

    public synchronized boolean hasChanged() {
        return !unchangedPointValid || (current != unchangedPoint);
    }
}
