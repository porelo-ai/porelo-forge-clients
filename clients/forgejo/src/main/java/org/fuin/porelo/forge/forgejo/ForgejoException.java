package org.fuin.porelo.forge.forgejo;

/**
 * A sanitized API, transport or payload failure, without request headers or raw response bodies.
 */
public final class ForgejoException extends RuntimeException {
    private final int status;

    ForgejoException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Returns the HTTP status, or zero for a transport, validation or webhook failure. */
    public int status() {
        return status;
    }
}
