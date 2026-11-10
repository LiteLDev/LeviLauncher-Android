package org.levimc.launcher.core.packages.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A cancellation token for one task: {@link #cancel()} or a timeout makes {@link #check()} fail and
 * runs the registered actions (aborting network calls, killing hook processes).
 */
public final class Cancel {
    private static final Timer TIMER = new Timer("package-timeouts", true);
    private final List<Runnable> actions = new ArrayList<>();
    private volatile PackageException.Kind reason;

    public void cancel() {
        fire(PackageException.Kind.CANCELLED);
    }

    /** Cancels this token with {@code TIMEOUT} after {@code millis}; returns this token. */
    public Cancel withTimeout(long millis) {
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                fire(PackageException.Kind.TIMEOUT);
            }
        }, millis);
        return this;
    }

    public boolean isCancelled() {
        return reason != null;
    }

    /** Why the token fired ({@code CANCELLED} or {@code TIMEOUT}); null while it has not. */
    public PackageException.Kind reason() {
        return reason;
    }

    /** Throws {@code CANCELLED} or {@code TIMEOUT} once the token fired. */
    public void check() throws PackageException {
        PackageException.Kind k = reason;
        if (k != null) {
            throw new PackageException(k, k == PackageException.Kind.TIMEOUT ? "The task timed out" : "The task was cancelled");
        }
    }

    /** A registration of {@link #onCancel}; closing it unregisters the action. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /** Runs {@code action} when the token fires (at once when it already did). */
    public Handle onCancel(Runnable action) {
        boolean now;
        synchronized (this) {
            now = reason != null;
            if (!now) actions.add(action);
        }
        if (now) action.run();
        return () -> {
            synchronized (this) {
                actions.remove(action);
            }
        };
    }

    private void fire(PackageException.Kind kind) {
        List<Runnable> run;
        synchronized (this) {
            if (reason != null) return;
            reason = kind;
            run = new ArrayList<>(actions);
            actions.clear();
        }
        for (Runnable r : run) r.run();
    }
}
