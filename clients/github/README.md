# GitHub client

`org.fuin.porelo:porelo-forge-client-github` covers github.com pull requests, reviews, review comments
and replies, check runs, Actions runs, jobs and logs, git refs, the authenticated account, organization
membership and collaborator permissions. GraphQL supplies paged review threads and their resolution;
GitHub Enterprise Server and webhooks are outside this module. The library uses the caller's JDK
`HttpClient`, Jackson and JSpecify, and never depends on Porelo contracts; its REST records are generated from
the committed extract.

## Use

```java
HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
GitHubClient github = new GitHubClient(URI.create("https://api.github.com/"), http);
List<PullRequestSimple> open = github.pulls(token, "owner", "repo", "open", 1, 50);
```

- The base is required. The caller supplies a token per call; requests use Bearer authentication,
  `Accept: application/vnd.github+json` and API version `2026-03-10`.
- List calls take `page` and `perPage`. Callers advance pages. GraphQL exposes cursors for threads and comments.
- The transport must use `Redirect.NEVER`. Log redirects are followed manually, at most five times;
  authentication stays removed after leaving the base's scheme, host and port. HTTPS never downgrades to HTTP.
- Reading defaults to `Strictness.LENIENT`; `Strictness.STRICT` rejects unknown fields. Both reject missing
  required fields and null collection elements. Nullable enum properties read an unknown value as null in
  either mode; unknown values of non-null enum properties are refused.
- `GitHubException.status()` is the HTTP status, or zero for transport and payload failures.
  Diagnostics scrub credentials and do not attach raw payloads or transport causes.

## Replay in other repositories

The `tests` jar of this module holds GitHub's recorded answers and
`org.fuin.porelo.forge.github.replay.ReplayHttpClient`, an `HttpClient` that replays them without opening a socket,
and nothing else. A test in another repository depends on it beside the main jar:

```xml
<dependency>
    <groupId>org.fuin.porelo</groupId>
    <artifactId>porelo-forge-client-github</artifactId>
    <version>${forge-clients.version}</version>
    <classifier>tests</classifier>
    <scope>test</scope>
</dependency>
```

```java
var http = new ReplayHttpClient().recording("reviewComments");
var comments = new GitHubClient(URI.create("https://api.github.com"), http, Strictness.STRICT)
        .reviewComments(token, "fixture-org", "fixture-repo", 6, 1, 50);
```

`recording(name)` queues a recording's answers in order, `recorded(name)` returns them for a test to change one first,
`answer` and `otherwise` add answers of the test's own, and `requests()` returns what was sent. The recordings are at
`/org/fuin/porelo/forge/github/recordings/<name>.json`, one per call of `GitHubClient` and `GitHubGraphQl`, named
after it, such as `pull` or `reviewThreads`. Each holds the `request_uri` it answered, the `request_body` of a write
or a GraphQL query, the `status`, the kept `headers` and the `body` as text, and under `exchanges` every answer of the
call, two for a job log, whose first answer GitHub redirects; `context` names the fixture's owner, repository, pull
request and ids the requests used. Accounts, addresses and credentials are scrubbed, as below. The recordings change
only when this repository records again.

## Build

Unit tests replay the GitHub answers in `src/test/resources/org/fuin/porelo/forge/github/recordings/`, with no
network or credential. The recordings are made by the recorder below, which scrubs accounts, addresses and
credentials before it writes them.
An account it was not told about stops it, unless the account wrote or committed a commit, as GitHub's own committer
does; such an account is named `fixture-committer-<n>`. A signed commit's raw text and signature are dropped, since
the raw text names the author as git does.
They were recorded from GitHub; the job log is the two-step workflow's, ending in its `fixture-complete` marker. A
test that needs what the recorded answers lack, such as a label or a private account, takes GitHub's own example from
the extract.
The GraphQL queries are recorded and replayed as the REST calls are; they are checked against GitHub itself by the
recorder and the live check, not against GitHub's schema file.

Build and replay from the repository root with `./mvnw -B -s settings.xml verify`.
The manual recorder runs from that root with
`./mvnw -B -s settings.xml -pl clients/github test-compile exec:java@record-github`. It reads
`LIVE_GITHUB_USER_TOKEN`, `LIVE_GITHUB_REVIEWER_TOKEN`, `LIVE_GITHUB_APP_ID` and `LIVE_GITHUB_APP_KEY`
from the environment, targeting `porelo-ai-test/forge-clients-live`. The App key is PEM RSA, PKCS#1 or
PKCS#8. It refuses matching leftovers and closes or removes its own resources, including on interrupt.
The two-step workflow must already be installed in that repository as `two-step.yml`.

The `github-live` profile runs the strict reading scenario against the kept review fixture and a newly
dispatched Actions run. Workflow proposals are delivered separately for installation by the repository owner.
The live fixture requires an open pull request labeled `forge-client-live`, an approving review, inline
comments and a resolved thread, and a check run on its head commit.

The extract is fetched with `org.fuin.porelo.forge.openapi.OpenApiExtract` (see
[openapi-extract](../../openapi-extract/README.md)), the source and digest below, and one `--operation` for each
operation in the `check-extract` execution in `pom.xml`. Its `--set` corrections are named in
`info.x-extracted-from.set`: both pull request label schemas use a nullable `simple-user` union for `archived_by` and
a nullable date-time string for `archived_at`; the simple pull request label's `description` is nullable too; every
integer identifier of a record that the description gives no format (`id`, a name ending in `_id`, and a new review
comment's `in_reply_to`) is `int64`, since GitHub's identifiers have passed the 32-bit range; and a check run's
`conclusion` also takes `stale` and `startup_failure`, which only GitHub sets and its check run request bodies list;
a commit's `author` and `committer` are a `oneOf` that starts with `null`, since a commit whose author is no GitHub
account comes as `null`, and the client reads an empty object as none too.
Its `--add-property` additions are named in `info.x-extracted-from.added`: reviews and review comments (both of
GitHub's review comment schemas) carry `is_copilot_code_review`, a review `copilot_code_review_effort` and a review
comment `copilot_code_review_sources`,
which GitHub sends and no version of its description has; only `null` and an empty array have been seen of the last
two, so their types are left open. Each setting takes the JSON value at that pointer in the extract. The corrections
apply during extraction; generated Java records are never edited.

## OpenAPI description license

The extract is from `api.github.com.2026-03-10.json` in the
[GitHub description](https://github.com/github/rest-api-description/tree/751731faa5b0b8fb78caeca37404dbbd77c5567f).
Its `info.x-extracted-from.source` records the exact file URL.
Its source SHA-256 is `b5bed44865babdcef9c66ac6e3ffd61beb5926816e50f9470a9001055f235fb5`.
The description retains its MIT license; the Java library is LGPL-3.0-or-later.

Copyright GitHub, Inc.

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation
> the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
> to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions
> of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
> THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
> CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
> IN THE SOFTWARE.
