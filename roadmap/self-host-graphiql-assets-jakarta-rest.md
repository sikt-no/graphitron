---
id: R416
title: "Self-host GraphiQL assets in graphitron-jakarta-rest (retire CDN, relocate Vite recipe)"
status: In Review
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

## Design decision (settled)

Self-host in the **core** `graphitron-jakarta-rest` module (the architect's "option B"), not a companion artifact ("option C"). The consumer set is small and known; we accept the bundle weight in the core jar now and split to an opt-in artifact later only if a real consumer is pinched. Rejected alternatives: keeping the CDN and merely pinning versions ("option A") leaves the runtime unpkg/CSP/air-gap coupling unaddressed; a companion artifact ("option C") is the principled shape for leanness but adds a module boundary and a new signed-to-Maven-Central artifact carrying vendored JS, which is not worth the ceremony for a small consumer set today.

## Implemented

Shipped as one change. What landed, and where it deviated from the plan below:

- **Recipe relocated + pinned.** `git mv` moved `tools/graphiql-build/` into `graphitron-jakarta-rest/`. `package.json` pins exact `graphiql@5.2.2`, `react@18.3.1`, `react-dom@18.3.1`, `graphql@16.13.2`, `@graphiql/toolkit@0.11.3`, `vite@6.4.2`, `@vitejs/plugin-react@4.7.0`; `package-lock.json` refreshed. **Deviation:** stayed on vite 6 / plugin-react 4 rather than #506's vite 8 / plugin-react 6 majors, since these build a working bundle (the plan's "take whatever versions build" latitude); plugin-react 6 drops Babel for Oxc, a larger change with no benefit here.
- **Bundle committed** under `.../rest/graphiql/`. **Deviation from §4's "swap four URLs":** GraphiQL 5 is a monaco-based bundler SPA, not the old UMD global-script shape, so the shell became a module-entry shell (a `graphiql.css` `<link>`, a `graphiql.js` `<script type="module">`, and the `#graphiql` mount div) rather than four swapped CDN tags. `vite.config.js` does a JS-entry build (no `index.html`; the recipe's `index.html` was deleted as unused), `base: './'` so every chunk/worker/font resolves relative to the entry files' served URL, `cssCodeSplit: false` for a single fixed `graphiql.css`, fixed `graphiql.js` entry, hashed names for chunks/workers/the codicon font. `src/main.jsx`'s fetcher uses `window.location.origin + window.location.pathname` to stay mount-agnostic.
- **Asset serving** added to `GraphqlResource`: `@GET @Path("assets/{name}")` streams `graphiql/{name}` via `getResourceAsStream`, guarded by a `[A-Za-z0-9._-]+` allowlist + explicit `..` reject, an extension→MIME map, and the `graphiqlEnabled()` gate. **Note:** the MIME map covers `js`/`css`/`map`/`ttf`/`woff`/`woff2`/`svg` (the plan listed only js/css/map; monaco emits a codicon `.ttf`). `graphiql()` gained `@Context UriInfo` and rewrites `{{ASSET_BASE}}` to the absolute `.../graphql/assets/` prefix per request.
- **Docs reconciled:** `graphiql.html` rationale comment, relocated `tools/graphiql-build/README.md`, `modules.adoc`, both tutorial pages, and the GraphiQL surfaces of the sakila-example README. **Scope call:** the sakila-example README's broader "app" section (dead `GraphqlEngine`/`GraphqlResource`/`AppContext` links) is uniform R399 drift, not GraphiQL-specific; filed as **R417** rather than expanded into R416. `changelog.md`'s historical `/graphiql/` mention (R68 record) left intact as history.
- **Verification.** `graphitron-jakarta-rest` compiles clean. Full `mvn install -Plocal-db` could **not** be run green because trunk is pre-existing-red: a `Person` joined-table-inheritance type fails schema validation in `graphitron-sakila-example` generation and `JoinedTableInheritancePipelineTest` + `allParties` corpus tests fail in `graphitron` core, all on a tree byte-identical to trunk (unrelated to this item). The `@QuarkusTest` conformance test (below) is written but blocked from running in-pipeline by that breakage. The asset endpoints and the `{{ASSET_BASE}}` scheme were instead verified end-to-end in headless Chromium against a faithful mirror of the resource: the SPA mounts (`.graphiql-container` renders, all chunks/workers/font resolve), zero failed/external requests, no `unpkg`, and bogus/unknown-extension names 404 — exactly what the conformance test asserts.
- **Dependabot #504/#506 closed** as superseded, each with a comment pointing at R416.

## Plan

### 1. Relocate and pin the Vite recipe

- Move `graphitron-sakila-example/tools/graphiql-build/` to `graphitron-jakarta-rest/tools/graphiql-build/`, unchanged in spirit: a one-shot, commit-the-output recipe. Maven and CI never invoke it (no `<build>` binding, no exec-maven/frontend plugin), preserving the reactor's "CI never touches node" property. `git mv` so history follows.
- Pin `package.json` to **exact** versions (drop the `^` ranges) for `graphiql`, `react`, `react-dom`, `graphql`, `@graphiql/toolkit`, `vite`, `@vitejs/plugin-react`, and refresh the committed `package-lock.json`. Pinning closes the unpinned-`graphiql` reproducibility hole directly. Rebuild against current pinned versions rather than merging #504/#506's bumps blind (esp. #506's vite 6→8 / plugin-react 4→6 majors); take whatever versions build a working bundle.
- The recipe's build output target changes from the example's (deleted) `META-INF/resources/graphiql/` to the jakarta-rest resource package (see §2).

### 2. Commit the bundle under the library's resource package

- Output the built JS/CSS into `graphitron-jakarta-rest/src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/` (co-located with `graphiql.html`, same package the resource already loads from via `getResourceAsStream`). Committed as plain classpath resources; no `META-INF/resources/` (that path is Quarkus-specific static-asset serving and would not work on a vendor-neutral Jakarta EE container, contradicting the module's ethos).

### 3. Serve the assets from `GraphqlResource` (vendor-neutral)

- Add a JAX-RS method to stream committed assets from the classpath, e.g. `@GET @Path("assets/{name}") @Produces(...)`, reading `no/sikt/graphitron/jakarta/rest/graphiql/{name}` via `getResourceAsStream`. Guard with a **filename allowlist** (or strict `[A-Za-z0-9._-]+` validation) to prevent classpath/path traversal, and a small extension→MIME map (`.js`→`text/javascript`, `.css`→`text/css`, `.map`→`application/json`); unknown/missing → 404. Gate behind the existing `application.graphiqlEnabled()` seam, same as the HTML page.
- **Asset-path wiring (key implementation risk).** The page is served at the consumer's mount path (`/graphql`, or `/api/graphql`, etc.); assets must resolve there regardless of mount point. `window.location`-relative references in the static HTML are fragile (a page at `/graphql` with no trailing slash resolves `assets/x` to `/assets/x`). Recommended approach: inject the correct base at serve time. `GraphqlResource.graphiql()` gains `@Context UriInfo`, computes the absolute request path, and rewrites a `{{ASSET_BASE}}` placeholder in `graphiql.html` to the absolute `.../graphql/assets/` prefix before returning it. This keeps the served page correct at any mount point and keeps the fetcher's existing `origin + pathname` logic intact. Alternative considered: emit a `<base href>` from the same computed path (simpler markup, but `<base>` also affects the fetcher URL and in-page links, so the explicit placeholder is safer).

### 4. Rewrite `graphiql.html`

- Replace the four unpkg URLs (one `<link>` stylesheet, three `<script>` tags: react, react-dom, graphiql) with `{{ASSET_BASE}}`-prefixed local references. Update the rationale comment (lines 11-15): it currently justifies the CDN "so the runtime jar stays lean"; replace with the self-hosted rationale (offline/CSP/air-gap-safe, version-pinned, reproducible) and a one-line note that leanness was consciously traded for a small known consumer set, with option C as the escape hatch if that changes.

### 5. Reconcile stale docs (already false today, independent of this change)

- `graphitron-sakila-example/README.md` line 12, line 22, and lines 39-45: rewrite to describe the library-provided self-hosted playground at `GET /graphql` (browser + `Accept: text/html`). Remove the dead links to the deleted `META-INF/resources/graphiql/` and the deleted example-owned `GraphqlResource.java`, and the `/graphiql/` path. Line 22 is prose in the module walkthrough asserting the (deleted) example `GraphqlResource` returns a `303` redirect to `/graphiql/`; lines 39-45 are the whole `### GraphiQL playground` section, including the closing paragraph that still claims the recipe under `tools/graphiql-build/` is "the only place node lives in this repo" (it moves to jakarta-rest under §1).
- Relocated `tools/graphiql-build/README.md`: rewrite for its new home and new output path under jakarta-rest.
- `docs/architecture/reference/modules.adoc`: note jakarta-rest now carries the committed bundle + recipe.
- Tutorial: `docs/manual/tutorial/01-prerequisites.adoc:103` and `03-first-query.adoc:5` point at `http://localhost:8080/graphiql/`, which no longer serves anything (the example's bundle is already gone) — repoint to `http://localhost:8080/graphql`. **Verify `TutorialSmokeTest` and `GraphqlResourceSmokeTest` in `graphitron-sakila-example` do not assert on the `/graphiql/` path** before/while editing the prose.

### 6. Close the superseded PRs

- Close Dependabot #504 (linkify-it) and #506 (vite/plugin-react) with a short note pointing at R416: the tooling they bump is being relocated and re-pinned here, so the bumps are subsumed.

## Testing

`graphitron-jakarta-rest` carries no `@Test` classes by design (R399: coverage lives in the sakila-example conformance suite). Add/extend a conformance test there:

- `GET /graphql` with `Accept: text/html` returns 200, body contains the GraphiQL mount div and **no** `unpkg.com` reference, and the injected `{{ASSET_BASE}}` resolved to an absolute `.../graphql/assets/` prefix.
- `GET /graphql/assets/<the built js filename>` returns 200 with a JS content-type and non-empty body; a bogus/traversal name (`../graphiql.html`, `nope.js`) returns 404.
- Existing GraphQL-over-HTTP conformance stays green.
- Full `mvn install -Plocal-db` green (JDK 25); confirm no node process is spawned by the build.

## Out of scope (deferred)

- Companion/opt-in `-graphiql` artifact (option C) to spare lean headless consumers the jar weight. Deferred until a real consumer is affected; the trade-off and trigger are recorded above.

## Verification checklist for reviewer

- Recipe relocation preserves the no-node-in-CI property (no build-plugin binding).
- Versions are pinned exact, not ranges; bundle is reproducible from the committed recipe.
- Asset serving is path-traversal-safe and vendor-neutral (no reliance on container static-asset serving).
- `graphiql.html` has zero external network references after the change.
- All four doc surfaces (two READMEs, modules.adoc, two tutorial pages) reconciled; no lingering `/graphiql/` or `META-INF/resources/graphiql` references anywhere.
