# Forgejo client

`org.fuin.porelo:porelo-forge-client-forgejo` covers Forgejo 17 pull requests, reviews and their comments,
review dismissal and undismissal, commit statuses, Actions runs, jobs and logs, branches, repository webhooks,
the authenticated account, organizations and collaborator permissions, an account's public SSH keys, the
authenticated account's own SSH keys, an account's permissions in an organization, and repositories. It uses the
caller's JDK `HttpClient`, Jackson and JSpecify, and knows nothing of Porelo contracts; its models are generated
from the committed extract.

## Use

```java
HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
ForgejoClient forgejo = new ForgejoClient(URI.create("https://forgejo.example.org/api/v1/"), http);
List<PullRequest> open = forgejo.pulls(token, "owner", "repo", "open", 1, 50);
```

- The base ends in `/api/v1/`. The caller owns the `HttpClient` and its certificate trust; one that follows
  redirects itself is refused, since the client sends the token only to the base's own scheme, host and port.
  A job log's redirect is followed by hand, at most five times, without the token to another origin.
- Every API call takes a token, passed with the call and never stored; Forgejo 17 asks for one even for an
  account's public keys. `plainKeys(login)` is the one call without a token: it reads the page `<login>.keys`
  at the web root beside the API, one key per line, and a missing login is a `ForgejoException` with status 404.
  `keys` lists an account's public SSH keys; `ownKeys`, `addKey` and `deleteKey` list, add
  and delete the authenticated account's own, and a key Forgejo already has is refused with its status.
  `organizationPermissions` reads an account's permissions in an organization; Forgejo 17 leaves out `is_owner`
  for anyone but an owner, so it reads as null then. `repository` reads a repository, and `createRepository`
  creates one in an organization. List calls take a page and a limit, and the caller advances the page.
- Reading is lenient by default, ignoring fields Forgejo adds within its API version; `Strictness.STRICT` refuses
  them, as the tests do. In both, a required field may not be missing or null.
- A failure is a `ForgejoException` with the HTTP status, or 0 when nothing was answered; its message carries no
  token, header or body.
- `ForgejoWebhook.read(body, signature, event, secret)` reads at most its bound, checks `X-Forgejo-Signature`
  before parsing, and gives a `PullRequestEvent` for `pull_request`, a `ReviewEvent` for `pull_request_approved`,
  `_rejected` and `_comment`, and an `ActionsRunEvent` for `action_run_success` and `_failure`.

## Build

- The unit tests replay the answers and deliveries recorded in `src/test/resources/recordings/`, with no network.
- `./mvnw -B -s settings.xml -Dforgejo.live verify` also runs `ForgejoLiveIT`, which needs podman: it starts a
  Forgejo 17 server and a runner, seeds them, runs every call strictly and removes them again.
- `./mvnw -B -s settings.xml -pl clients/forgejo test-compile exec:java@record-forgejo` records anew from the same
  containers; the recordings are scrubbed to `example.org` and deliveries signed again with the test secret.
- The extract is fetched again with `org.fuin.porelo.forge.openapi.OpenApiExtract` (see
  [openapi-extract](../../openapi-extract/README.md)), with the source and digest below, one `--operation` for
  each of the `check-extract` execution in `pom.xml`, and:

  ```
  --replace '{{AppVer | JSEscape}}=17.0.1' --replace '{{AppSubUrl | JSEscape}}='
  --add-property 'User.username={"type": "string", "description": "the user'"'"'s username, sent by Forgejo 17 but not in its description"}'
  ```

## OpenAPI description license

The extract's source is
[`v1_json.tmpl` at commit `363f3dc30beef902b187af5a5244ff7af44901e6`](https://codeberg.org/forgejo/forgejo/raw/commit/363f3dc30beef902b187af5a5244ff7af44901e6/templates/swagger/v1_json.tmpl).
Its source SHA-256 is `9e6e453e631793e47c01b2b1391274c3a78296aab8fd192d029484304ddd4bc9`.
The description declares: “This file is distributed under the MIT license for the purpose of interoperability.”
The Java library is licensed under LGPL-3.0-or-later; the description retains its MIT notice:

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
