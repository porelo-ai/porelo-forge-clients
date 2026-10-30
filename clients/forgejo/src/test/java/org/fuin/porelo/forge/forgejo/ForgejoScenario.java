package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.forgejo.ForgejoOperations.Context;
import org.fuin.porelo.forge.forgejo.model.ListActionRunResponse;
import org.fuin.porelo.forge.forgejo.model.OrganizationPermissions;
import org.fuin.porelo.forge.forgejo.model.PublicKey;
import org.fuin.porelo.forge.forgejo.model.PullRequest;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.Repository;

/** Drives all operations in dependency order in one isolated live environment. */
final class ForgejoScenario {
    /**
     * A public key made only for this test, added and deleted again; its private half was
     * discarded.
     */
    static final String ADDED_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK0/KIjf5jKU1F6jYSWxCNnQbmCGSfgPnYf0DlLinAJn forgejo-added";

    private final ForgejoContainer fixture;
    private final ObjectMapper json = ForgejoJson.mapper(Strictness.STRICT);
    private final RecordingHttpClient transport;
    private final ForgejoClient client;
    private final List<Capture> captures = new ArrayList<>();
    private Context context;

    /**
     * The recorder reads leniently, so an answer is kept even where it differs from the description
     * and the strict replay then names every difference; the live check reads strictly.
     */
    ForgejoScenario(ForgejoContainer fixture, Strictness strictness) {
        this.fixture = fixture;
        transport = new RecordingHttpClient(fixture.transport());
        client = new ForgejoClient(fixture.base(), transport, strictness);
        String sha =
                fixture.api("GET", ForgejoContainer.repoPath() + "/branches/feature", null)
                        .path("commit")
                        .path("id")
                        .asText();
        if (!sha.matches("[a-f0-9]{40,64}")) {
            throw new IllegalStateException("Feature branch did not return a commit identity");
        }
        context =
                new Context(
                        ForgejoContainer.OWNER,
                        ForgejoContainer.REPO,
                        sha,
                        "pending",
                        1,
                        1,
                        1,
                        1,
                        1);
    }

    List<Capture> run() throws Exception {
        for (ForgejoOperations operation : ForgejoOperations.values()) {
            if (operation == ForgejoOperations.runs) {
                fixture.workflow();
                JsonNode jobs = fixture.waitForJobs();
                context =
                        new Context(
                                context.owner(),
                                context.repo(),
                                context.sha(),
                                jobs.path("sha").asText(),
                                context.index(),
                                context.review(),
                                jobs.path("run").asLong(),
                                jobs.path("job").asLong(),
                                context.key());
            }
            JsonNode body = body(operation);
            String token =
                    operation == ForgejoOperations.createReview
                            ? fixture.reviewerToken()
                            : fixture.adminToken();
            Object result = operation.invoke(client, token, context, body);
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
            if (operation == ForgejoOperations.createPull) {
                Long index = ((PullRequest) result).number();
                if (index == null || index < 1) {
                    throw new IllegalStateException("Created pull request has no number");
                }
                context =
                        new Context(
                                context.owner(),
                                context.repo(),
                                context.sha(),
                                context.actionsSha(),
                                index,
                                context.review(),
                                context.run(),
                                context.job(),
                                context.key());
            } else if (operation == ForgejoOperations.createReview) {
                Long id = ((PullReview) result).id();
                if (id == null || id < 1) {
                    throw new IllegalStateException("Created review has no identity");
                }
                context =
                        new Context(
                                context.owner(),
                                context.repo(),
                                context.sha(),
                                context.actionsSha(),
                                context.index(),
                                id,
                                context.run(),
                                context.job(),
                                context.key());
            } else if (operation == ForgejoOperations.addKey) {
                Long id = ((PublicKey) result).id();
                if (id == null || id < 1) {
                    throw new IllegalStateException("Added key has no identity");
                }
                context =
                        new Context(
                                context.owner(),
                                context.repo(),
                                context.sha(),
                                context.actionsSha(),
                                context.index(),
                                context.review(),
                                context.run(),
                                context.job(),
                                id);
            }
            List<ObjectNode> exchanges = transport.take();
            assertThat(exchanges)
                    .as("each operation must actually exchange a response")
                    .isNotEmpty();
            ObjectNode recording = exchanges.getLast().deepCopy();
            recording.set("request_uri", exchanges.getFirst().path("request_uri"));
            recording.set("exchanges", json.valueToTree(exchanges));
            recording.set("context", json.valueToTree(context));
            recording.set("request_body", body);
            captures.add(new Capture(operation.name(), recording));
        }
        return List.copyOf(captures);
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
                                    context.sha(),
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
