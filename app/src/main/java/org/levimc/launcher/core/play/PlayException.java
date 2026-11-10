package org.levimc.launcher.core.play;

/** A Google Play request that failed in a way the UI can explain to the user. */
public final class PlayException extends Exception {
    public enum Kind {
        /** The sign-in or its tokens were rejected; signing in again is the fix. */
        UNAUTHORIZED,
        /** The Google account has not bought Minecraft. */
        NOT_PURCHASED,
        /** Play has no build of that version (code) this device profile can receive. */
        NOT_AVAILABLE,
        RATE_LIMITED,
        SERVER,
        NETWORK,
        MALFORMED,
        UNKNOWN
    }

    public final Kind kind;
    public final int httpCode;

    public PlayException(Kind kind, String message) {
        this(kind, message, null, -1);
    }

    public PlayException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, -1);
    }

    public PlayException(Kind kind, String message, Throwable cause, int httpCode) {
        super(message, cause);
        this.kind = kind;
        this.httpCode = httpCode;
    }
}
