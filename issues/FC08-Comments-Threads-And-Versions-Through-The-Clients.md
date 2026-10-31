# FC08 — Comments, Threads And Versions Through The Clients

**Status:** Accepted (operator, 2026-10-31); blocked by [FC01](README.md#fc01) and [FC02](README.md#fc02).

**What must be true.** A caller that writes a code review on GitHub or Forgejo reads, writes, edits and deletes a pull
request's general comments, reading only those since a given time; edits and deletes a review comment; reads one GitHub
review thread with its state by its id; and reads a Forgejo server's version, through the `github` and `forgejo`
modules, in each forge's own terms. Where Forgejo's API cannot do what GitHub's does, the client says so.

## The shape

- **General comments on both forges:** `GET .../issues/{number}/comments` with `since` and paging,
  `POST .../issues/{number}/comments`, `PATCH` and `DELETE .../issues/comments/{id}`.
- **GitHub review comments:** `PATCH` and `DELETE /repos/{owner}/{repo}/pulls/comments/{comment_id}`, beside the
  create and reply calls the client has.
- **One GitHub review thread by its id:** its resolved and outdated state and who resolved it, beside its comments;
  today only the list of a pull request's threads carries the state, and nothing reads who resolved one.
- **Forgejo's version:** `GET /version`.
- **What Forgejo's API can do, by recording against a real Forgejo:** whether a review comment can be answered, and
  how, and whether `PATCH` and `DELETE .../issues/comments/{id}` reach a review comment. A call Forgejo does not have
  is not offered by the client, and its README says so.
- Each new call is tested against recorded answers in the strict reading, and the live checks read them.

## To be checked

- Which of the Forgejo calls exist in its pinned Swagger 2.0 description, and which answer as described, measured by
  the recording.
