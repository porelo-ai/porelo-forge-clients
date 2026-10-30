package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.fuin.porelo.forge.forgejo.model.ActionRun;
import org.fuin.porelo.forge.forgejo.model.PullRequest;
import org.fuin.porelo.forge.forgejo.model.Repository;
import org.fuin.porelo.forge.forgejo.model.User;
import org.jspecify.annotations.Nullable;

/** Authenticates bounded raw webhook bodies before interpreting their event or JSON. */
public final class ForgejoWebhook {
    private final int maximumBytes;
    private final ObjectMapper json;

    /** Creates a lenient reader with a positive raw-body byte limit. */
    public ForgejoWebhook(int maximumBytes) {
        this(maximumBytes, Strictness.LENIENT);
    }

    /** Creates a reader with a positive raw-body byte limit and explicit JSON strictness. */
    public ForgejoWebhook(int maximumBytes, Strictness strictness) {
        if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "The webhook body limit must be positive and below Integer.MAX_VALUE");
        }
        this.maximumBytes = maximumBytes;
        json = ForgejoJson.mapper(strictness);
    }

    /**
     * Reads at most limit plus one bytes, checks the hexadecimal X-Forgejo-Signature, then reads
     * X-Forgejo-Event. The caller owns the input stream. Event names are provisional until
     * recorded.
     */
    public Payload read(InputStream body, String signature, String event, byte[] secret) {
        byte[] raw;
        try {
            raw = body.readNBytes(maximumBytes + 1);
        } catch (IOException ex) {
            throw new ForgejoException(0, "Cannot read webhook body");
        }
        if (raw.length > maximumBytes) {
            throw new ForgejoException(0, "Webhook body exceeds its byte limit");
        }
        byte[] supplied;
        try {
            if (signature.length() != 64) {
                throw new IllegalArgumentException();
            }
            supplied = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException ex) {
            throw new ForgejoException(0, "Invalid webhook signature");
        }
        if (!MessageDigest.isEqual(hmac(raw, secret), supplied)) {
            throw new ForgejoException(0, "Invalid webhook signature");
        }
        Class<? extends Payload> type =
                switch (event) {
                    case "pull_request" -> PullRequestEvent.class;
                    case "pull_request_approved", "pull_request_rejected", "pull_request_comment" ->
                            ReviewEvent.class;
                    case "action_run_success", "action_run_failure" -> ActionsRunEvent.class;
                    default -> throw new ForgejoException(0, "Unsupported webhook event");
                };
        return ForgejoJson.read(
                json,
                new String(raw, StandardCharsets.UTF_8),
                json.constructType(type),
                new String(secret, StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new ForgejoException(0, "Cannot verify webhook signature");
        }
    }

    /** Common type for authenticated deliveries. */
    public sealed interface Payload permits PullRequestEvent, ReviewEvent, ActionsRunEvent {}

    /**
     * The verdict a review delivery carries: its type, such as pull_request_review_approved, and
     * its text.
     */
    public record Review(@JsonProperty(required = true) String type, @Nullable String content) {
        /** Refuses a review without its type. */
        public Review {
            Objects.requireNonNull(type, "type");
        }
    }

    /** A pull request opened, changed or closed, delivered as the event pull_request. */
    public record PullRequestEvent(
            @JsonProperty(required = true) String action,
            @JsonProperty(required = true) Long number,
            @JsonProperty(value = "pull_request", required = true) PullRequest pullRequest,
            @JsonProperty("requested_reviewer") @Nullable User requestedReviewer,
            @Nullable Repository repository,
            @Nullable User sender,
            @JsonProperty("commit_id") @Nullable String commitId,
            @Nullable Review review)
            implements Payload {
        /** Refuses null in the fields needed to identify the pull request. */
        public PullRequestEvent {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(pullRequest, "pull_request");
        }
    }

    /**
     * A review submitted on a pull request, delivered as pull_request_approved,
     * pull_request_rejected or pull_request_comment by its verdict; Forgejo 17 sends one payload
     * for the three, of which the first is recorded.
     */
    public record ReviewEvent(
            @JsonProperty(required = true) String action,
            @JsonProperty(required = true) Long number,
            @JsonProperty(value = "pull_request", required = true) PullRequest pullRequest,
            @JsonProperty("requested_reviewer") @Nullable User requestedReviewer,
            @Nullable Repository repository,
            @Nullable User sender,
            @JsonProperty("commit_id") @Nullable String commitId,
            @JsonProperty(required = true) Review review)
            implements Payload {
        /** Refuses null in the fields needed to identify the verdict. */
        public ReviewEvent {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(pullRequest, "pull_request");
            Objects.requireNonNull(review, "review");
        }
    }

    /**
     * An Actions run that finished, delivered as action_run_success or action_run_failure; Forgejo
     * 17 sends one payload for the two, of which the first is recorded, and names a success after a
     * failure by the action recover.
     */
    public record ActionsRunEvent(
            @JsonProperty(required = true) String action,
            @JsonProperty(required = true) ActionRun run,
            @JsonProperty("prior_status") @Nullable String priorStatus)
            implements Payload {
        /** Refuses null in the fields needed to identify the build. */
        public ActionsRunEvent {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(run, "run");
        }
    }
}
