# GitHub client

`org.fuin.porelo:porelo-forge-client-github` covers github.com pull requests, reviews, review comments
and replies, check runs, Actions runs, jobs and logs, git refs, the authenticated account, organization
membership and collaborator permissions, an account's SSH keys and signing keys, repositories and a commit's
combined status. GraphQL supplies paged review threads and their resolution;
GitHub Enterprise Server and webhooks are outside this module. The library uses the caller's JDK
`HttpClient`, Jackson and JSpecify, and never depends on Porelo contracts; its REST records are generated from
the committed extract.

## Use

```java
HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
GitHubClient github = new GitHubClient(URI.create("https://api.github.com/"), http);
Page<PullRequestSimple> open = github.pulls(token, "owner", "repo", "open", 1, 50);
```

- The base is required. The caller supplies a token per call; requests use Bearer authentication,
  `Accept: application/vnd.github+json` and API version `2026-03-10`.
- A paged list call takes `page` and `perPage` and returns a `Page` with its `items` and `more`, whether a further
  page follows, read from GitHub's `Link` header or an envelope's `total_count`; a full page without a next link is the
  last one. `status` keeps its envelope, which says a commit's `state` and `sha` beside the page, and its `total_count`
  tells the end. Callers advance pages. GraphQL exposes cursors for threads and comments.
- The transport must use `Redirect.NEVER`. Log redirects are followed manually, at most five times;
  authentication stays removed after leaving the base's scheme, host and port. HTTPS never downgrades to HTTP.
- Reading defaults to `Strictness.LENIENT`; `Strictness.STRICT` rejects unknown fields. Both reject missing
  required fields and null collection elements. Nullable enum properties read an unknown value as null in
  either mode; unknown values of non-null enum properties are refused.
- Three calls take no token and send none: `keys` and `signingKeys`, an account's public SSH authentication and
  signing keys, and `plainKeys`, the lines of the page `<login>.keys` beside the API, read from the web base
  (`https://github.com/` unless the four-argument constructor names another), as `text/plain` and up to 256 KiB.
  A missing login is a `GitHubException` with status 404; no failure reads as an empty list.
- The signed-in account's own keys of both kinds are listed, added and deleted with `ownKeys`, `addKey`,
  `deleteKey`, `ownSigningKeys`, `addSigningKey` and `deleteSigningKey`; GitHub refuses a key it already has, which
  the caller then finds in the list. `orgMembership` reads an account's membership in an organization: an owner
  has role `admin` and state `active`; an invited owner who has not accepted is `pending`. `repository` and
  `createRepository` read a repository and create one in an organization, and `status` reads a commit's combined
  status with a page of its statuses.
- `user` is refused with 403 for a GitHub App's installation token.
- `pullCommits` reads a pull request's commits, each with its revision, the author's and committer's accounts as GitHub
  links them, none when it links no account (`null` or an empty object), and GitHub's verdict on its signature in
  `commit.verification`. GitHub's description says the list stops at 250 commits, so a caller that needs more reads the
  branch's commits instead.
- `GitHubException.status()` is the HTTP status, or zero for transport and payload failures.
  Diagnostics scrub credentials and do not attach raw payloads or transport causes. `delivery()` says whether the
  request may have reached GitHub: `NOT_SENT`, `SENT_UNANSWERED`, or `ANSWERED`, the answer perhaps undecodable or over
  its bound. The JDK does not say whether bytes left before a failure, so a failure after the connection opened counts
  as sent.
- `rateLimited()` is true on a 429, on a 403 with `x-ratelimit-remaining: 0`, a `Retry-After` or a message naming a rate
  limit, and on a GraphQL answer whose first error has type `RATE_LIMITED`; any other 403 is a refusal. `rateLimit()`
  holds what the answer said, and `delay()` is its `Retry-After`, else the time until its reset, else one minute.
- Every answer is read up to a bound: 16 MiB, an error answer 64 KiB, past which the reading stops and the failure says
  `GitHub answer exceeds <n> bytes`, with no part of the answer. `jobLogs` returns a `JobLog`, read up to 64 MiB and
  keeping its last 1 MiB, with `cut` saying whether it was longer.

- `acceptedPermissions()` of a refusal is GitHub's `X-Accepted-GitHub-Permissions` header, such as `issues=write`,
  when it is a plain list of permissions, else null.
- The conditional reads `issuesIfChanged`, a repository's issues newest change first, and `projectItemsIfChanged`, an
  organization project's items, send the entity tag the caller last got as `If-None-Match`, none when it gives none; a
  tag that is blank or holds anything but visible ASCII and inner spaces is refused before sending. Each returns a
  `Conditional`: after a 304, `changed` false, no value, and GitHub's tag or else the one sent; after a 200, the value
  and the answer's `ETag` exactly as sent, weak prefix `W/` kept. Both carry the answer's rate limit and `date`,
  GitHub's time from its `Date` header, which a caller takes as the start of its next look, since its own clock may run
  ahead; null when missing or unreadable, and then the next look is not narrowed. Any other status fails as every call
  does.
- The project's items come as a `CursorPage` of `ProjectItemSummary`, whose `after` is the `after` parameter of the
  `Link` header's next link, null on the last page. An item carries its node id, time and whether it is archived; its
  issue's node id, repository, number, time, state and how many sub-issues and blockers (closed ones included) it has;
  and the values of the fields named by their numeric ids (`ProjectField.databaseId`, at most 50; GitHub refuses its
  built-in `Created`, `Updated` and `Closed` there). An item missing a part GitHub always sends fails the page, an
  `Issue` item without its issue included. Only single-select, text and number values are read; a value of another kind
  carries none, so its change is not seen in the values. GraphQL has no ETag, so the board itself is never read
  conditionally. As measured, GitHub sends strong tags, a 304 leaves `x-ratelimit-remaining` as it was, and the items'
  tag changes on any change of an item, its issue, its links or its values; with the fields named, an unchanged board
  answers 304 on every look, without them it sometimes answers 200. A link added moves neither the item's time nor its
  issue's, only the issue's counts.
- `issues` lists a repository's issues newest change first, with `state`, `labels` (left out when none; a blank name
  or one holding a comma is refused, since GitHub splits at commas) and `since`; it lists pull requests too, told by
  `pullRequest`. `issue`, `createIssue` and `updateIssue` read, create and change one; GitHub drops labels in silence
  for an account without push access. Closing is `state` `closed` with `stateReason` `completed` or `not_planned`.
- `parentIssue`, `subIssues`, `addSubIssue`, `removeSubIssue`, `reprioritizeSubIssue`, `blockedBy`, `addBlockedBy`,
  `removeBlockedBy` and `blocking` name the other issue by its `id`, not its number. `parentIssue` is a 404 for an
  issue without a parent, as for a missing one; `parentIssueUrl` shows it beforehand. A sub-issue and a blocking
  issue may be in another repository of the same owner, as recorded. GitHub's issue list shows a new issue some
  seconds after it is made.

## An organization project's board over GraphQL

`GitHubGraphQl` reads and writes an organization project's board. Every caller value travels in the request's
variables; the document holds only fixed text and, in a batch, each part's index.

- `projectFields` reads the project's id, title, last change and its fields, a single-select field with its options;
  `projectItems` reads 100 items a page, or as many as its caller gives up to 100, with whether GitHub archived each,
  their issues and their single-select, text and number values, an item that is a draft or a pull request without an
  issue, and on every page the project's whole item count, `totalCount`. A page GitHub ends for the resources it used
  fails with `resourceLimited()` set on its `GitHubException`, so a caller can read the board on in smaller pages. An
  issue's labels, sub-issues and blockers and an item's values come at the sizes of `ItemLimits` (`DEFAULT` 100, 100,
  100 and 50; each 1 to 100), and each of these lists carries its `PageInfo` (`labelsPage`, `valuesPage`), so what lies
  past a list is read by its cursor, never dropped: `issueLabels`, `issueSubIssues` and `issueBlockedBy` read an issue's
  rest by its node id and `itemValues` an item's, 100 a page from the cursor given, each a `ListPage` with its
  `PageInfo` and cost; a node of another kind fails the read. `issueBoard` reads up to 100 issues by node id at the
  default sizes, each with its item and values in the given project, null in the place of an id GitHub does not know or
  of no issue; an issue on more than 50 projects whose item in the given one is not among its first 50 fails the read
  rather than reading as off the board. `statusChanges` reads an issue's moves on a project's own `Status` field; a
  change of any other single-select field appears in no timeline. A variant of a union the board does not model, such as
  an iteration field or a date value, is left out. Each query's answer carries its `GraphQlCost`.
- `setFieldOptions` replaces a single-select field's options; GitHub gives every option a new id, one whose name stays
  included, so a caller takes the ids from the answer. As measured, an item's value in that field is gone afterwards,
  even for an option whose name stayed, so the options are set before any value.
- `addProjectItems`, `setFieldValues` and `createIssues` send up to 50 parts in one mutation, each aliased `a<i>` with
  its input in `$v<i>`, and return a `Batch`: one outcome per part, in order, the value GitHub saved or its message,
  scrubbed of credentials, for a part it refused; `complete()` says whether every part was saved. A `RATE_LIMITED`
  error anywhere, an error naming no part sent, or a part with neither value nor error fails the whole call. An empty
  list, a blank or repeated id, more parts than the bound, or two values for one item and field are refused before
  sending. A mutation cannot ask its cost, so a `Batch` carries the rate limit of the answer's headers. As measured,
  GitHub saves the parts before and after a refused one; and it once answered a whole batch with an internal error
  naming no part, which fails the call, as above, and was not seen again.
- `createIssues` takes a `parentIssueId`, as recorded. `statusChanges` listed only an item's first status, set either by
  `setFieldValues` or by the project's own automation, whichever came first, which the actor's `__typename` `Bot` tells;
  a later move by `setFieldValues`, read at once, was not listed.

The extract's `--set` corrections for these: an issue's labels read as label objects and are sent as names, its title
is sent as text, every identifier is `int64`, a project item's `id` is an integer, and the value GitHub suggests in an
issue update's answer is left open; an issue's label carries `archived_at` and `archived_by`, as a pull request's
does. Its `--add-property` addition: an issue carries `assignee`, a nullable account,
which GitHub sends beside `assignees`.

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
`LIVE_GITHUB_USER_TOKEN`, `LIVE_GITHUB_REVIEWER_TOKEN`, `LIVE_GITHUB_REVIEWER_KEYS_TOKEN`, `LIVE_GITHUB_APP_ID`
and `LIVE_GITHUB_APP_KEY` from the environment, targeting `porelo-ai-test/forge-clients-live`. The App key is PEM
RSA, PKCS#1 or PKCS#8. The user token belongs to the organization's owner, a test account, and may administer its
repositories and write commit statuses; the keys token belongs to the reviewer, also a test account, and may write
its SSH keys and signing keys, since a token owned by the organization holds no account permissions. The recorder
refuses a keys token of any other account. It refuses matching leftovers and closes or removes its own resources,
including on interrupt: its branch, a repository named after the run, and keys titled after it, each made for that
run only.
The two-step workflow must already be installed in that repository as `two-step.yml`.

The issue and project calls are recorded by
`./mvnw -B -s settings.xml -pl clients/github test-compile exec:java@record-github-issues`, which reads only
`LIVE_GITHUB_REVIEWER_TOKEN`, the token of a test organization member that holds Issues and organization Projects
read and write and may create projects. It makes two issues in `porelo-ai-test/forge-clients-live` and one in
`porelo-ai-test/forge-clients-live-2`, a second repository kept for links across repositories, and a project of its
own, records each call, and deletes the project at the end and on interrupt. Deleting an issue takes the repository's
administrator, so the run closes its five issues as `not_planned`, and they stay. `exec:java@kept-github-issues` makes
the kept issue, its sub-issue, which also blocks it, and the kept project with their values, unless they are there,
and `exec:java@live-github-issues` reads them strictly, both with the same token.

The `github-live` profile runs the strict reading scenario against the kept review fixture and a newly
dispatched Actions run. Workflow proposals are delivered separately for installation by the repository owner.
The live fixture requires an open pull request labeled `forge-client-live`, an approving review, inline
comments and a resolved thread, and a check run and a commit status on its head commit, which
`./mvnw -B -s settings.xml -pl clients/github test-compile exec:java@kept-github` makes from the recorder's environment
unless it is there; the approving reviewer
keeps one SSH key and one signing key made for the test, read without a token, and with
`LIVE_GITHUB_REVIEWER_KEYS_TOKEN` as its own.

The extract is fetched with `org.fuin.porelo.forge.openapi.OpenApiExtract` (see
[openapi-extract](../../openapi-extract/README.md)), the source and digest below, and one `--operation` for each
operation in the `check-extract` execution in `pom.xml`. Its `--set` corrections are named in
`info.x-extracted-from.set`: both pull request label schemas use a nullable `simple-user` union for `archived_by` and
a nullable date-time string for `archived_at`; the simple pull request label's `description` is nullable too; every
integer identifier of a record that the description gives no format (`id`, a name ending in `_id`, and a new review
comment's `in_reply_to`) is `int64`, since GitHub's identifiers have passed the 32-bit range; and a check run's
`conclusion` also takes `stale` and `startup_failure`, which only GitHub sets and its check run request bodies list;
a commit's `author` and `committer` are a `oneOf` that starts with `null`, since a commit whose author is no GitHub
account comes as `null`, and the client reads an empty object as none too. In both of a pull request's schemas `head.repo` and `head.user` are
an `anyOf` of `null` and their schema and `head.label` is `["string", "null"]`: a pull request from a deleted fork has no
`repo`, and when the fork's account is gone too it has no `user` and no `label`, while `ref` and `sha` stay. A review's
`body` is `["string", "null"]`, so a review with no text reads the same. All of them read as `null`, so a caller checks
them.
Its `--add-property` additions are named in `info.x-extracted-from.added`: reviews and review comments (both of
GitHub's review comment schemas) carry `is_copilot_code_review`, a review `copilot_code_review_effort` and a review
comment `copilot_code_review_sources`, and a repository's `security_and_analysis` carries
`secret_scanning_validity_checks` with the `status` its siblings have,
which GitHub sends and no version of its description has; only `null` and an empty array have been seen of the
copilot fields' effort and sources, so their types are left open. Each setting takes the JSON value at that pointer in the extract. The corrections
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
