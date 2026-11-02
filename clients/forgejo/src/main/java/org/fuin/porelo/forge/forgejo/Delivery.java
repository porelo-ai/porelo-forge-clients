package org.fuin.porelo.forge.forgejo;

/** Whether a failed request may have reached Forgejo, which decides if it is safe to send again. */
public enum Delivery {
    /** Nothing left this client: a bad argument, or a connection never made. */
    NOT_SENT,
    /**
     * The request may have reached Forgejo and no answer came: a timeout, a reset, an interrupt.
     */
    SENT_UNANSWERED,
    /** Forgejo answered, the answer perhaps a refusal, undecodable or over its bound. */
    ANSWERED
}
