package org.fuin.porelo.forge.github;

/** Whether a failed request may have reached GitHub, which decides if it is safe to send again. */
public enum Delivery {
    /** Nothing left this client: a bad argument, or a connection never made. */
    NOT_SENT,
    /** The request may have reached GitHub and no answer came: a timeout, a reset, an interrupt. */
    SENT_UNANSWERED,
    /** GitHub answered, the answer perhaps a refusal, undecodable or over its bound. */
    ANSWERED
}
