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

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

public abstract class AbstractWorker implements Worker, CallBack {

    private static final ExecutorService BG = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "AbstractWorker-BG");
                t.setDaemon(true);
                return t;
            });

    private final Worker worker;
    private final CallBack callBack;

    public AbstractWorker() {
        this.worker = this::runOffEdtButKeepGuiResponsive;
        this.callBack = this::updateOnEdt;
    }

    public void init() throws Throwable {
        // no-op, same as before
    }

    @Override
    public void update() {
        // default no-op
    }

    public Worker getWorker() {
        return worker;
    }

    public CallBack getCallBack() {
        return callBack;
    }

    private void runOffEdtButKeepGuiResponsive() {
        if (!SwingUtilities.isEventDispatchThread()) {
            runUnchecked();
            return;
        }

        final SecondaryLoop loop = Toolkit.getDefaultToolkit()
                .getSystemEventQueue()
                .createSecondaryLoop();

        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean entered = new AtomicBoolean(false);

        BG.execute(() -> {
            try {
                runUnchecked();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                if (entered.get()) {
                    loop.exit();
                } else {
                    SwingUtilities.invokeLater(loop::exit);
                }
            }
        });

        boolean ok = loop.enter();
        entered.set(ok);

        Throwable t = thrown.get();
        if (t != null) {
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException("Background worker failed", t);
        }
    }

    private void updateOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            update();
        } else {
            SwingUtilities.invokeLater(this::update);
        }
    }

    private void runUnchecked() {
        // If Worker.run() declares checked exceptions, change signatures accordingly.
        // Assume Worker.run() does not declare checked exceptions.
        try {
            // If AbstractWorker itself implements Worker, run() method in your hierarchy.
            // Call that run() here.
            ((Worker) this).run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
