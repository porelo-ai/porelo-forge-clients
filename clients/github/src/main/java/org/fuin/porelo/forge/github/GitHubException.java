package org.fuin.porelo.forge.github;

/**
 * A sanitized API, transport or payload failure, without request headers or raw response bodies.
 */
public final class GitHubException extends RuntimeException {
    private final int status;

    GitHubException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Returns the HTTP status, or zero for a transport or validation failure. */
    public int status() {
        return status;
    }
}
