# Ops Bridge

Connect the tools the project runs on (Sentry, Google Play, GitHub, App Store
Connect...) so a signal in one becomes a work item in another. EPIC 48,
[ADR 0028](../../docs/adr/0028-ops-bridge-runs-in-github-actions.md).

It is **not** "sync": in this repo that word means `SyncEngine`, the user-library
sync. It runs in GitHub Actions plus the scripts here. There is no server, the
secrets are already in Actions, and it works on any plan.

## The shape

```
connector.items  ->  OpsBridge::Item  ->  GithubSink#create
                                          connector.resolve(id)   (optional, the reverse)
```

- A **connector** polls its source and returns normalised `OpsBridge::Item`s
  (`id`, `title`, `body`, `labels`, `link`).
- The **sink** (`GithubSink`) opens one issue per item that has none.
- **Idempotent**: each body ends in `<!-- <kind>:<id> -->`. The sink reads the
  markers from the issues it already made (open and closed, by label, *not* the
  search index, which lags by minutes) before creating anything, so every run
  can be repeated or double-delivered safely.
- **Reverse mapping** is optional: if the connector responds to `resolve(id)`,
  closing its issue on GitHub calls it. Sentry does (resolve in the next release);
  Play reviews do not (there is nothing to resolve).

| Connector | Source | Runs | Reverse |
|---|---|---|---|
| `sentry` (`connectors/sentry.rb`, #300) | new unhandled production crashes | hourly | closing the issue resolves it in the next release |
| `play-reviews` (`connectors/play_reviews.rb`) | Play reviews of 3 stars or fewer | daily | none |

```sh
ruby fastlane/ops_bridge.rb sync sentry            # needs SENTRY_AUTH_TOKEN, SENTRY_ORG, GITHUB_TOKEN, GITHUB_REPOSITORY
DRY_RUN=1 ruby fastlane/ops_bridge.rb sync play-reviews
```

`DRY_RUN=1` prints what would be opened and writes nothing. A manual workflow run
is dry by default.

## Adding a connector

1. **A class in `connectors/`** with `name`, `kind` (the marker prefix, unique),
   `source_label` (the label `GithubSink` finds its issues by, `from-<thing>`),
   `labels`, and `items` returning `OpsBridge::Item`s. Add `resolve(id)` only if a
   closed issue should change something at the source.
2. **Take an injectable `http:`** (default `OpsBridge::Http.new`) and raise
   `OpsBridge::ApiError` with a message that never contains a token or key.
3. **Keep the body to counts and names.** Nothing that identifies a person: the
   repository is public. Put any user-written text in a code fence (see
   `play_reviews.rb`) so an `@name` cannot ping anyone.
4. **Register it** in `connector_for` in `fastlane/ops_bridge.rb`.
5. **Test it** in `fastlane/test/<name>_test.rb` against `FakeHttp`
   (`ops_bridge_test_helper.rb`): item mapping, the request shapes, that an error
   never carries a secret, and that a second run opens nothing. Add the file to the
   loop in `pr-guards.yml`.
6. **A workflow** in `.github/workflows/`: a `schedule` sized to how much history
   the source keeps (Play keeps a week, so daily), `workflow_dispatch` with
   `dry_run` on by default, `permissions: issues: write`, a `concurrency` group so
   two runs never overlap, and a step that does nothing when the secret is unset.
7. **Document the owner steps** (permissions, secrets) in `docs/RELEASING.md` and
   `scripts/release/owner-setup.sh`.

## Future options (recorded, not chosen: ADR 0028)

A hosted service (a Cloudflare Worker or the dormant `:server`) for webhooks and
real two-way latency; n8n or Zapier for the least code, with the connectors living
outside this repo's review and CI. Revisit when a connector needs sub-hourly latency
or webhooks only.
