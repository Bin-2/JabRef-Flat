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
package net.sf.jabref.export;

import net.sf.jabref.Globals;
import net.sf.jabref.Util;
import net.sf.jabref.GUIGlobals;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.charset.UnsupportedCharsetException;

import javax.swing.SwingUtilities;

/**
 * Class used to handle safe storage to disk.
 *
 * Usage: create a SaveSession giving the file to save to, the encoding, and
 * whether to make a backup. The SaveSession will provide a Writer to store to,
 * which actually goes to a temporary file. The Writer keeps track of whether
 * all characters could be saved, and if not, which characters were not
 * encodable.
 *
 * After saving is finished, the client should close the Writer. If the save
 * should be put into effect, call commit(), otherwise call cancel(). When
 * cancelling, the temporary file is simply deleted and the target file remains
 * unchanged. When committing, the temporary file is copied to the target file
 * after making a backup if requested and if the target file already existed,
 * and finally the temporary file is deleted.
 *
 * If committing fails, the temporary file will not be deleted.
 */
public class SaveSession {

    private static final boolean PERF_TIMERS = false;
    private static final long PERF_LOG_THRESHOLD_MS = 0L;

    public static final String LOCKFILE_SUFFIX = ".lock";
    // The age in ms of a lockfile before JabRef will offer to "steal" the locked file:
    public static final long LOCKFILE_CRITICAL_AGE = 60000;

    private static final String TEMP_PREFIX = "jabref";
    private static final String TEMP_SUFFIX = "save.bib";

    File file, tmp, backupFile;
    String encoding;
    boolean backup, useLockFile;
    VerifyingWriter writer;

    private static long startTimer() {
        return PERF_TIMERS ? System.nanoTime() : 0L;
    }

    private static void printTimer(String label, long start) {
        if (!PERF_TIMERS) {
            return;
        }
        long elapsedNs = System.nanoTime() - start;
        long elapsedMs = elapsedNs / 1000000L;
        if (elapsedMs >= PERF_LOG_THRESHOLD_MS) {
            System.out.println("[SaveSession timer] " + label
                    + " took " + elapsedMs + " ms (" + elapsedNs + " ns)"
                    + " thread=" + Thread.currentThread().getName()
                    + " edt=" + SwingUtilities.isEventDispatchThread());
        }
    }

    private static long fileLength(File f) {
        if (f == null || !f.exists()) {
            return -1L;
        }
        return f.length();
    }

    private static String fileInfo(File f) {
        if (f == null) {
            return "null";
        }
        return f.getPath() + " exists=" + f.exists() + " bytes=" + fileLength(f);
    }

    public SaveSession(File file, String encoding, boolean backup) throws IOException,
            UnsupportedCharsetException {
        long totalStart = startTimer();
        long blockStart;

        this.file = file;

        blockStart = startTimer();
        tmp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX);
        printTimer("constructor createTempFile tmp=" + fileInfo(tmp), blockStart);

        blockStart = startTimer();
        useLockFile = Globals.prefs.getBoolean("useLockFiles");
        printTimer("constructor read useLockFiles value=" + useLockFile, blockStart);

        this.backup = backup;
        this.encoding = encoding;

        blockStart = startTimer();
        writer = new VerifyingWriter(new FileOutputStream(tmp), encoding);
        printTimer("constructor open VerifyingWriter encoding=" + encoding, blockStart);

        printTimer("constructor total target=" + fileInfo(file) + " backup=" + backup, totalStart);
    }

    public VerifyingWriter getWriter() {
        return writer;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setUseBackup(boolean useBackup) {
        this.backup = useBackup;
    }

    public void commit() throws SaveException {
        long totalStart = startTimer();
        long blockStart;
        try {
            if (file == null) {
                return;
            }

            System.out.println("[SaveSession timer] commit start target=" + fileInfo(file)
                    + " tmp=" + fileInfo(tmp)
                    + " backup=" + backup
                    + " useLockFile=" + useLockFile
                    + " thread=" + Thread.currentThread().getName()
                    + " edt=" + SwingUtilities.isEventDispatchThread());

            if (file.exists() && backup) {
                String name = file.getName();
                String path = file.getParent();
                File backupFile = new File(path, name + GUIGlobals.backupExt);
                try {
                    blockStart = startTimer();
                    Util.copyFile(file, backupFile, true);
                    printTimer("commit backup copy sourceBytes=" + fileLength(file)
                            + " backup=" + fileInfo(backupFile), blockStart);
                } catch (IOException ex) {
                    ex.printStackTrace();
                    throw SaveException.BACKUP_CREATION;
                    //throw new SaveException(Globals.lang("Save failed during backup creation")+": "+ex.getMessage());
                }
            }
            try {
                if (useLockFile) {
                    try {
                        blockStart = startTimer();
                        boolean lockAlreadyExisted = createLockFile();
                        printTimer("commit createLockFile alreadyExisted=" + lockAlreadyExisted, blockStart);
                        if (lockAlreadyExisted) {
                            // Oops, the lock file already existed. Try to wait it out:
                            blockStart = startTimer();
                            if (!Util.waitForFileLock(file, 10)) {
                                printTimer("commit waitForFileLock failed", blockStart);
                                throw SaveException.FILE_LOCKED;
                            }
                            printTimer("commit waitForFileLock succeeded", blockStart);

                        }
                    } catch (IOException ex) {
                        System.err.println("Error when creating lock file");
                        ex.printStackTrace();
                    }
                }

                blockStart = startTimer();
                Util.copyFile(tmp, file, true);
                printTimer("commit temp-to-target copy tmpBytes=" + fileLength(tmp)
                        + " target=" + fileInfo(file), blockStart);
            } catch (IOException ex2) {
                // If something happens here, what can we do to correct the problem? The file is corrupted, but we still
                // have a clean copy in tmp. However, we just failed to copy tmp to file, so it's not likely that
                // repeating the action will have a different result.
                // On the other hand, our temporary file should still be clean, and won't be deleted.
                throw new SaveException(Globals.lang("Save failed while committing changes") + ": " + ex2.getMessage());
            } finally {
                if (useLockFile) {
                    blockStart = startTimer();
                    boolean deleted = deleteLockFile();
                    printTimer("commit deleteLockFile deleted=" + deleted, blockStart);
                }
            }

            blockStart = startTimer();
            boolean tmpDeleted = tmp.delete();
            printTimer("commit delete temp deleted=" + tmpDeleted + " tmp=" + fileInfo(tmp), blockStart);
        } finally {
            printTimer("commit total target=" + fileInfo(file), totalStart);
        }
    }

    public void cancel() {
        long blockStart = startTimer();
        boolean tmpDeleted = tmp.delete();
        printTimer("cancel delete temp deleted=" + tmpDeleted + " tmp=" + fileInfo(tmp), blockStart);
    }

    /**
     * Check if a lock file exists, and create it if it doesn't.
     *
     * @return true if the lock file already existed
     * @throws IOException if something happens during creation.
     */
    private boolean createLockFile() throws IOException {
        long totalStart = startTimer();
        File lock = new File(file.getPath() + LOCKFILE_SUFFIX);
        try {
            if (lock.exists()) {
                return true;
            }
            FileOutputStream out = new FileOutputStream(lock);
            try {
                out.write(0);
            } finally {
                try {
                    out.close();
                } catch (IOException ex) {
                    System.err.println("Error when creating lock file");
                    ex.printStackTrace();
                }
            }
            lock.deleteOnExit();
            return false;
        } finally {
            printTimer("createLockFile total lock=" + fileInfo(lock), totalStart);
        }
    }

    /**
     * Check if a lock file exists, and delete it if it does.
     *
     * @return true if the lock file existed, false otherwise.
     * @throws IOException if something goes wrong.
     */
    private boolean deleteLockFile() {
        long totalStart = startTimer();
        File lock = new File(file.getPath() + LOCKFILE_SUFFIX);
        try {
            if (!lock.exists()) {
                return false;
            }
            return lock.delete();
        } finally {
            printTimer("deleteLockFile total lock=" + fileInfo(lock), totalStart);
        }
    }

    public File getTemporaryFile() {
        return tmp;
    }
}
