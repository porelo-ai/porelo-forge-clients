package org.fuin.porelo.forge.github;

/** Whether a reader refuses JSON fields absent from its model. */
public enum Strictness {
    /** Ignore additional fields introduced within an API version. */
    LENIENT,
    /** Refuse additional fields to detect API drift. */
    STRICT
}
