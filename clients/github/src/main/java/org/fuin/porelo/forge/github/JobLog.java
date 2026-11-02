package org.fuin.porelo.forge.github;

/**
 * A job's log.
 *
 * @param text the whole log, or its last mebibyte when it was cut
 * @param cut whether the log was longer than what {@code text} holds
 */
public record JobLog(String text, boolean cut) {}
