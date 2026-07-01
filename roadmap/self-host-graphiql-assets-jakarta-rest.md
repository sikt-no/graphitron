---
id: R416
title: "Self-host GraphiQL assets in graphitron-jakarta-rest (retire CDN, relocate Vite recipe)"
status: Backlog
bucket: feature
priority: 6
theme: structural-refactor
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Self-host GraphiQL assets in graphitron-jakarta-rest (retire CDN, relocate Vite recipe)

`graphitron-jakarta-rest` serves its GraphiQL playground (`GET /graphql`, `Accept: text/html`) from a bundled HTML shell that loads React and GraphiQL from unpkg at runtime. This has two defects for "the first hand-written runtime artifact consumers depend on": the GraphiQL asset URLs are unpinned (they resolve to *latest*, so the playground silently tracks upstream and can break with no commit), and the page requires `unpkg.com` reachable from the browser at runtime, so it is dead behind the strict-CSP or air-gapped networks that Sikt's gov/edu consumers plausibly run. Separately, `graphitron-sakila-example/tools/graphiql-build/` holds an orphaned Vite recipe (the only place node lives in the repo) that used to build a self-hosted bundle into the example; that bundle and the example's redirect resource have already been deleted, leaving the recipe dead and two Dependabot PRs (#504, #506) bumping its npm deps for nothing.

Self-host the GraphiQL assets *in the core `graphitron-jakarta-rest` module*: commit a version-pinned GraphiQL 5 + React bundle under the module's resources, rewrite `graphiql.html` to reference those local classpath assets instead of unpkg, and have `GraphqlResource` serve them locally. Relocate the Vite recipe into jakarta-rest as a one-shot, commit-the-output recipe (node/npm build it locally; Maven and CI never invoke it, preserving the reactor's "CI never touches node" property; the committed bundle is the artifact, the recipe is the reproducibility receipt). A companion/opt-in artifact to spare lean headless consumers the jar weight (the principled "option C") is **explicitly deferred**: the consumer set is small and known, so we accept the bundle weight in the core jar now and split it out later *if* a real consumer is pinched. Also reconcile the stale docs that still describe the deleted self-hosted `/graphiql/` arrangement (`graphitron-sakila-example/README.md` lines 12 and 39-43, the relocated `tools/graphiql-build/README.md`, and the lean-jar rationale comment in `graphiql.html`), and close #504/#506 as superseded. Design fork and its resolution recorded in the session that filed this item; consult `principles-architect` output on the leanness-vs-CDN tension before Spec sign-off.
