package org.levimc.launcher.core.packages.engine;

/** Receives the log and the progress of an operation; called on the operation's thread. */
public interface Listener {
    enum Level { INFO, WARN, ERROR }

    enum Stage { RESOLVE, GIT_FETCH, DOWNLOAD, EXTRACT, PLACE, SCRIPTS, REMOVE }

    Listener NONE = new Listener() {
        @Override
        public void log(Level level, String message) {
        }

        @Override
        public void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes) {
        }
    };

    void log(Level level, String message);

    /**
     * Progress of the bar {@code id}: {@code current} of {@code total} ({@code total < 0}: unknown;
     * {@code current == total}: done), in bytes when {@code bytes}. {@code pkg} names the package or source.
     */
    void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes);
}
