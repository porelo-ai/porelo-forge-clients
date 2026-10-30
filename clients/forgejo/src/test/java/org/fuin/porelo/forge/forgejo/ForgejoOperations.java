package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fuin.porelo.forge.forgejo.model.*;

/** The single inventory exercised live, captured, and replayed. */
enum ForgejoOperations {
    createHook("POST"),
    createPull("POST"),
    createReview("POST"),
    pulls("GET"),
    pull("GET"),
    reviews("GET"),
    reviewComments("GET"),
    dismissReview("POST"),
    undismissReview("POST"),
    createStatus("POST"),
    statuses("GET"),
    runs("GET"),
    jobs("GET"),
    jobLogs("GET"),
    branch("GET"),
    hooks("GET"),
    user("GET"),
    organizations("GET"),
    permission("GET");

    final String method;
    private static final ObjectMapper JSON = ForgejoJson.mapper(Strictness.STRICT);

    ForgejoOperations(String method) {
        this.method = method;
    }

    Object invoke(ForgejoClient client, String token, Context c, JsonNode body) throws Exception {
        return switch (this) {
            case createPull ->
                    client.createPull(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, CreatePullRequestOption.class));
            case createReview ->
                    client.createReview(
                            token,
                            c.owner(),
                            c.repo(),
                            c.index(),
                            JSON.treeToValue(body, CreatePullReviewOptions.class));
            case createHook ->
                    client.createHook(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, CreateHookOption.class));
            case createStatus ->
                    client.createStatus(
                            token,
                            c.owner(),
                            c.repo(),
                            c.sha(),
                            JSON.treeToValue(body, CreateStatusOption.class));
            case dismissReview ->
                    client.dismissReview(
                            token,
                            c.owner(),
                            c.repo(),
                            c.index(),
                            c.review(),
                            JSON.treeToValue(body, DismissPullReviewOptions.class));
            case undismissReview ->
                    client.undismissReview(token, c.owner(), c.repo(), c.index(), c.review());
            case pulls -> client.pulls(token, c.owner(), c.repo(), "open", 1, 50);
            case pull -> client.pull(token, c.owner(), c.repo(), c.index());
            case reviews -> client.reviews(token, c.owner(), c.repo(), c.index(), 1, 50);
            case reviewComments ->
                    client.reviewComments(token, c.owner(), c.repo(), c.index(), c.review());
            case statuses -> client.statuses(token, c.owner(), c.repo(), c.sha(), 1, 50);
            case runs -> client.runs(token, c.owner(), c.repo(), c.actionsSha(), 1, 50);
            case jobs -> client.jobs(token, c.owner(), c.repo(), c.run());
            case jobLogs -> client.jobLogs(token, c.owner(), c.repo(), c.job());
            case branch -> client.branch(token, c.owner(), c.repo(), "feature");
            case hooks -> client.hooks(token, c.owner(), c.repo(), 1, 50);
            case user -> client.user(token);
            case organizations -> client.organizations(token, 1, 50);
            case permission ->
                    client.permission(token, c.owner(), c.repo(), ForgejoContainer.REVIEWER);
        };
    }

    String path(Context c) {
        String repo = "/api/v1/repos/" + c.owner() + "/" + c.repo();
        String pull = repo + "/pulls/" + c.index();
        return switch (this) {
            case createPull, pulls -> repo + "/pulls";
            case pull -> pull;
            case createReview, reviews -> pull + "/reviews";
            case reviewComments -> pull + "/reviews/" + c.review() + "/comments";
            case dismissReview -> pull + "/reviews/" + c.review() + "/dismissals";
            case undismissReview -> pull + "/reviews/" + c.review() + "/undismissals";
            case createStatus, statuses -> repo + "/statuses/" + c.sha();
            case runs -> repo + "/actions/runs";
            case jobs -> repo + "/actions/runs/" + c.run() + "/jobs";
            case jobLogs -> repo + "/actions/jobs/" + c.job() + "/logs";
            case branch -> repo + "/branches/feature";
            case createHook, hooks -> repo + "/hooks";
            case user -> "/api/v1/user";
            case organizations -> "/api/v1/user/orgs";
            case permission -> repo + "/collaborators/" + ForgejoContainer.REVIEWER + "/permission";
        };
    }

    record Context(
            String owner,
            String repo,
            String sha,
            String actionsSha,
            long index,
            long review,
            long run,
            long job) {}
}
