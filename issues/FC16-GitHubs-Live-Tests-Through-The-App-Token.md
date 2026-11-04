# FC16 — GitHub's Live Tests Through The App Token

**Status:** Idea

**What must be true.** The GitHub recorder and the live check run with the App's installation token and the
reviewer test account's tokens only, from the environment, so no person's own token is needed for them:
`LIVE_GITHUB_APP_TOKEN` takes the place of `LIVE_GITHUB_USER_TOKEN`, `LIVE_GITHUB_APP_ID` and `LIVE_GITHUB_APP_KEY`.
The reviewer's own keys keep `LIVE_GITHUB_REVIEWER_KEYS_TOKEN`, since a token owned by the organization holds no account
permissions, and the decision *Three credentials, since GitHub splits them* changes with this.

## To be checked

- An installation token has no user, so `GET /user`, `GET /user/memberships/orgs/{org}` and a collaborator's
  permission by the signed-in login go through the reviewer test account's token; the account that opens the pull
  request becomes the App's bot account, which the scrubber must map as it maps the test user today.
