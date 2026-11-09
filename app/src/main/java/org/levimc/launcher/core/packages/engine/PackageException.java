package org.levimc.launcher.core.packages.engine;

/**
 * The one failure type of the package engine. {@link #kind} classifies the failure for the UI,
 * {@link #argument} names the source or identity it concerns (or null), and the message is an
 * English detail for the log.
 */
public final class PackageException extends Exception {
    public enum Kind {
        /** No archive of a source is a package (or none can be obtained). */
        NO_PACKAGE_FOUND,
        /** A source names packages, but none has an entry for the environment. */
        NO_ENTRY_FOR_ENVIRONMENT,
        ALREADY_EXPLICITLY_INSTALLED,
        NOT_INSTALLED,
        NOT_EXPLICIT,
        /** No valid set of packages satisfies the requests (workspace 3). */
        UNSATISFIABLE,
        /** A file owned by another package, a duplicate identity or mod name. */
        CONFLICT,
        INVALID_ARGUMENT,
        INVALID_PACKAGE,
        INVALID_WORKSPACE,
        /** The state file records another environment. */
        ENV_MISMATCH,
        WORKSPACE_LOCKED,
        NETWORK,
        HOOK_FAILED,
        IO,
        RECOVERY_FAILED,
        CANCELLED,
        TIMEOUT,
    }

    public final Kind kind;
    public final String argument;

    public PackageException(Kind kind, String message) {
        this(kind, null, message, null);
    }

    public PackageException(Kind kind, String argument, String message) {
        this(kind, argument, message, null);
    }

    public PackageException(Kind kind, String argument, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.argument = argument;
    }

    /** The same failure, now naming {@code argument} (kept when this one already names one). */
    public PackageException withArgument(String argument) {
        if (this.argument != null) return this;
        PackageException e = new PackageException(kind, argument, getMessage(), getCause());
        e.setStackTrace(getStackTrace());
        return e;
    }
}
