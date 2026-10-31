package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.forgejo.ForgejoOperations.Context;
import org.fuin.porelo.forge.forgejo.model.Comment;
import org.fuin.porelo.forge.forgejo.model.ListActionRunResponse;
import org.fuin.porelo.forge.forgejo.model.OrganizationPermissions;
import org.fuin.porelo.forge.forgejo.model.PublicKey;
import org.fuin.porelo.forge.forgejo.model.PullRequest;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.PullReviewComment;
import org.fuin.porelo.forge.forgejo.model.Repository;
import org.jspecify.annotations.Nullable;

/** Drives all operations in dependency order in one isolated live environment. */
final class ForgejoScenario {
    /**
     * A public key made only for this test, added and deleted again; its private half was
     * discarded.
     */
    static final String ADDED_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK0/KIjf5jKU1F6jYSWxCNnQbmCGSfgPnYf0DlLinAJn forgejo-added";

    static final String EARLIER_COMMENT = "Earlier general comment fixture";
    static final String EDITED_COMMENT = "Edited general comment fixture";

    private final ForgejoContainer fixture;
    private final ObjectMapper json = ForgejoJson.mapper(Strictness.STRICT);
    private final RecordingHttpClient transport;
    private final ForgejoClient client;
    private final List<Capture> captures = new ArrayList<>();
    private final String sha;
    private String actionsSha = "pending";
    private long index = 1;
    private long review = 1;
    private long run = 1;
    private long job = 1;
    private long key = 1;
    private long comment = 1;
    private long issueComment = 1;
    private String since = "1970-01-01T00:00:00Z";

    /**
     * The recorder reads leniently, so an answer is kept even where it differs from the description
     * and the strict replay then names every difference; the live check reads strictly.
     */
    ForgejoScenario(ForgejoContainer fixture, Strictness strictness) {
        this.fixture = fixture;
        transport = new RecordingHttpClient(fixture.transport());
        client = new ForgejoClient(fixture.base(), transport, strictness);
        sha =
                fixture.api("GET", ForgejoContainer.repoPath() + "/branches/feature", null)
                        .path("commit")
                        .path("id")
                        .asText();
        if (!sha.matches("[a-f0-9]{40,64}")) {
            throw new IllegalStateException("Feature branch did not return a commit identity");
        }
    }

    /** What the operations so far have made, for the next one to address. */
    private Context context() {
        return new Context(
                ForgejoContainer.OWNER,
                ForgejoContainer.REPO,
                sha,
                actionsSha,
                index,
                review,
                run,
                job,
                key,
                comment,
                issueComment,
                since);
    }

    List<Capture> run() throws Exception {
        for (ForgejoOperations operation : ForgejoOperations.values()) {
            if (operation == ForgejoOperations.runs) {
                fixture.workflow();
                JsonNode jobs = fixture.waitForJobs();
                actionsSha = jobs.path("sha").asText();
                run = jobs.path("run").asLong();
                job = jobs.path("job").asLong();
            }
            if (operation == ForgejoOperations.createIssueComment) {
                since = afterAnEarlierComment();
            }
            JsonNode body = body(operation);
            String token =
                    operation == ForgejoOperations.createReview
                            ? fixture.reviewerToken()
                            : fixture.adminToken();
            Object result = operation.invoke(client, token, context(), body);
            requireUsefulResult(operation, result);
            if (operation == ForgejoOperations.plainKeys) {
                String seeded =
                        String.join(
                                " ",
                                List.of(ForgejoContainer.REVIEWER_PUBLIC_KEY.split(" "))
                                        .subList(0, 2));
                assertThat((List<Object>) result)
                        .as("the key page lists exactly the seeded key, without its comment")
                        .containsExactly(seeded);
            }
            switch (result) {
                case PullRequest pull when operation == ForgejoOperations.createPull ->
                        index = identity(pull.number(), "Created pull request has no number");
                case PullReview created when operation == ForgejoOperations.createReview ->
                        review = identity(created.id(), "Created review has no identity");
                case PullReviewComment created
                        when operation == ForgejoOperations.createReviewComment ->
                        comment = identity(created.id(), "Created review comment has no identity");
                case Comment created when operation == ForgejoOperations.createIssueComment ->
                        issueComment = identity(created.id(), "Created comment has no identity");
                case PublicKey added when operation == ForgejoOperations.addKey ->
                        key = identity(added.id(), "Added key has no identity");
                default -> {}
            }
            List<ObjectNode> exchanges = transport.take();
            assertThat(exchanges)
                    .as("each operation must actually exchange a response")
                    .isNotEmpty();
            ObjectNode recording = exchanges.getLast().deepCopy();
            recording.set("request_uri", exchanges.getFirst().path("request_uri"));
            recording.set("exchanges", json.valueToTree(exchanges));
            recording.set("context", json.valueToTree(context()));
            recording.set("request_body", body);
            captures.add(new Capture(operation.name(), recording));
        }
        return List.copyOf(captures);
    }

    private static long identity(@Nullable Long id, String missing) {
        if (id == null || id < 1) {
            throw new IllegalStateException(missing);
        }
        return id;
    }

    /**
     * Writes a comment, then waits into the next whole second and returns it, so reading since then
     * must leave the earlier comment out.
     */
    private String afterAnEarlierComment() throws InterruptedException {
        fixture.api(
                "POST",
                ForgejoContainer.repoPath() + "/issues/" + index + "/comments",
                json.valueToTree(Map.of("body", EARLIER_COMMENT)));
        Instant next = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), next).toMillis()) + 50);
        return next.toString();
    }

    private JsonNode body(ForgejoOperations operation) {
        return json.valueToTree(
                switch (operation) {
                    case createHook ->
                            Map.of(
                                    "type",
                                    "forgejo",
                                    "active",
                                    true,
                                    "events",
                                    // Forgejo has no wildcard: an unknown name subscribes to
                                    // nothing.
                                    List.of(
                                            "pull_request",
                                            "pull_request_review_approved",
                                            "pull_request_review_rejected",
                                            "pull_request_review_comment",
                                            "action_run_success",
                                            "action_run_failure"),
                                    "config",
                                    Map.of(
                                            "url",
                                            fixture.hookUrl(),
                                            "content_type",
                                            "json",
                                            "secret",
                                            fixture.hookSecret()));
                    case createPull ->
                            Map.of(
                                    "base",
                                    "main",
                                    "head",
                                    "feature",
                                    "title",
                                    "Review fixture",
                                    "body",
                                    "Exercise a real review");
                    case createReview ->
                            Map.of(
                                    "event",
                                    "APPROVED",
                                    "body",
                                    "Approved fixture",
                                    "commit_id",
                                    sha,
                                    "comments",
                                    List.of(
                                            Map.of(
                                                    "path",
                                                    "review.txt",
                                                    "new_position",
                                                    1,
                                                    "old_position",
                                                    0,
                                                    "body",
                                                    "Review comment fixture")));
                    case createReviewComment ->
                            Map.of(
                                    "path",
                                    "review.txt",
                                    "new_position",
                                    1,
                                    "body",
                                    "Added review comment fixture");
                    case createIssueComment -> Map.of("body", "General comment fixture");
                    case editIssueComment -> Map.of("body", EDITED_COMMENT);
                    case dismissReview -> Map.of("message", "Exercise dismissal", "priors", false);
                    case createStatus ->
                            Map.of(
                                    "state",
                                    "success",
                                    "context",
                                    "fixture/build",
                                    "description",
                                    "Fixture passed",
                                    "target_url",
                                    "https://example.org/build/1");
                    case createRepository -> Map.of("name", ForgejoOperations.CREATED_REPO);
                    case addKey -> Map.of("title", "Added key", "key", ADDED_PUBLIC_KEY);
                    default -> null;
                });
    }

    static void requireUsefulResult(ForgejoOperations operation, Object result) {
        assertThat(result).as("%s must return a payload", operation).isNotNull();
        if (operation == ForgejoOperations.issueComments) {
            assertThat((List<?>) result)
                    .extracting(value -> ((Comment) value).body())
                    .as("reading since a time finds the comment after it and not the one before")
                    .contains(EDITED_COMMENT)
                    .doesNotContain(EARLIER_COMMENT);
        }
        if (result instanceof Collection<?> collection) {
            assertThat(collection)
                    .as("%s must exercise a seeded item, not only an empty array", operation)
                    .isNotEmpty();
        } else if (result instanceof ListActionRunResponse runs) {
            assertThat(runs.workflowRuns())
                    .as("the live fixture must produce a real Actions run")
                    .isNotEmpty();
        } else if (result instanceof String logs) {
            assertThat(logs)
                    .as("the live job must execute and produce its expected output")
                    .contains("fixture-complete");
        } else if (result instanceof OrganizationPermissions permissions) {
            assertThat(permissions.isOwner())
                    .as("the fixture administrator owns the seeded organization")
                    .isTrue();
        } else if (result instanceof Repository repository) {
            assertThat(repository.name())
                    .as("repository creation and lookup address the same fixture repository")
                    .isEqualTo(ForgejoOperations.CREATED_REPO);
        }
    }

    record Capture(String operation, ObjectNode recording) {}
}
