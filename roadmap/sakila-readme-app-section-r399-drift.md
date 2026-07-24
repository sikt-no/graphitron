---
id: R417
title: "Reconcile sakila-example README app-section with R399 (dead GraphqlEngine/GraphqlResource/AppContext links)"
status: Spec
bucket: cleanup
priority: 7
theme: docs
depends-on: []
created: 2026-07-01
last-updated: 2026-07-24
---

# Reconcile sakila-example README app-section with R399 (dead GraphqlEngine/GraphqlResource/AppContext links)

`graphitron-sakila-example/README.md` still describes the runtime under a "Runnable reference (the app)" section (roughly lines 17-27) as three example-owned files: `GraphqlEngine.java`, `GraphqlResource.java`, and `AppContext.java`. R399 extracted all of that into `graphitron-jakarta-rest`; the example now ships a single `SakilaGraphitronApplication` adapter (a `GraphitronApplication` SPI implementation) and depends on the library for the `/graphql` resource, the engine, status-code semantics, the `/schema` endpoint, and the GraphiQL page. The three source links in that section are dead, and the "three files cover the runtime" framing is false.

R416 reconciled only the GraphiQL-specific claims in this README (the `/graphiql/` redirect prose and the deleted `META-INF/resources/graphiql/` bundle) because those were in its scope; it deliberately left the broader R399 app-section drift alone rather than expand scope. This item is that follow-up: rewrite the app section around `SakilaGraphitronApplication` + the `graphitron-jakarta-rest` dependency, fix or remove the dead `GraphqlEngine`/`GraphqlResource`/`AppContext` links, and keep the "why plain JAX-RS" and per-request-context prose accurate to where that logic now lives.

## Verified current state (Spec-time survey)

- `src/main/java/no/sikt/graphitron/sakila/example/app/` contains exactly one file, `SakilaGraphitronApplication.java`. It extends `AbstractGraphitronApplication` (constructor supplies the schema as a lambda over the generated `Graphitron` facade), overrides `engineBuilder()` with `Graphitron.runtime(dataSource, SQLDialect.POSTGRES).newGraphQL(schema())` over the Quarkus-injected `AgroalDataSource` (the owned-connection path: one pinned connection per operation, session-state mounting, transaction demarcation), and overrides `newExecutionInput()` with `Graphitron.newOwnedExecutionInput("{\"sub\":\"test-user\"}", "test-user")` (the opaque claims payload plus the schema's one `contextArgument`, `userId`). No `AppContext`, `GraphitronContext`, or hand-written `DataLoaderRegistry` wiring exists anywhere in the module.
- `graphitron-jakarta-rest` owns `GraphqlResource` (POST/GET negotiation, the media-type-driven status watershed, GET-mutation 405, `/graphql/schema` via `SchemaPrinter`, the self-hosted GraphiQL page and its `assets/{name}` streaming), `GraphqlEngine` (application-scoped engine cache), `GraphqlRequest`, and the `GraphitronApplication` SPI + `AbstractGraphitronApplication` base.
- The example `pom.xml` depends on `graphitron-jakarta-rest` plus Yasson as the JSON-B provider the library requires; the README's "the pom.xml shows the shape you need" sentence (line 15) does not mention either.
- The test-pattern section's "There's also one HTTP-shaped test: `GraphqlResourceSmokeTest`" (line 58) is false since R399/R421: `.../sakila/example/app/` now holds `GraphQLOverHttpConformanceTest` (the per-normative-requirement GraphQL-over-HTTP conformance suite, 15 cases), `GraphqlResourceSmokeTest`, `TutorialSmokeTest` (which pins the manual's tutorial, not the app), the test-scoped `FaultInjectingGraphitronApplication` `@Alternative`, and the `SmokeTestPostgresResource` lifecycle manager.
- `docs/architecture/reference/modules.adoc` line 47 declares "Per-module READMEs are deliberately not maintained", yet this README is the consumer-facing surface the root README and `docs/quick-start.adoc` both send readers to, and it is currently the only consumer-facing "serve my schema over HTTP" recipe (the manual's tutorial boots the app but never shows the SPI wiring). That unowned-surface contradiction is why this README keeps drifting (R416, now R417).
- Same drift class, surfaced by the Spec-time survey: `docs/quick-start.adoc` lines 64 and 66 carry a doubled `graphitron-rewrite/graphitron-rewrite/` path segment in two absolute GitHub URLs.

## Plan

Prose changes in `graphitron-sakila-example/README.md` plus one enforcer test, one sentence in `modules.adoc`, and one typo fix in `quick-start.adoc`; no other docs (R416 already reconciled the GraphiQL surfaces and the tutorials). Shaped by a principles-architect consult: the README states shapes and links files; per-method narration stays in the reference-gated javadoc, so this rewrite does not mint a second hand-maintained copy of it.

- **Rewrite "Runnable reference (the app)" at shape altitude.** The only hand-written runtime file is `SakilaGraphitronApplication` (markdown-linked), the `GraphitronApplication` SPI adapter riding the recommended owned-connection path; everything HTTP-shaped (the `/graphql` resource implementing the GraphQL-over-HTTP spec, engine caching, status-code semantics, the `/graphql/schema` SDL endpoint, the GraphiQL page) comes from the `graphitron-jakarta-rest` dependency. Link the adapter file and the library module for the details rather than restating the adapter's javadoc (which already narrates the constructor/`engineBuilder()`/`newExecutionInput()` seams and is `{@link}`-gate-protected; duplicating it in unguarded prose is the two-copies condition that produced this item). Delete the dead links and the `AppContext`/`DataLoaderRegistry` prose. If `newExecutionInput()` is mentioned at all, the hard-coded claims payload must be flagged as a placeholder in the same breath, pointing at the "Producing the claims payload" guidance in `docs/architecture/reference/runtime-extension-points.adoc`; this README is a copy-this surface and the payload is what the `<sessionState>` hook mounts as the caller's identity.
- **Keep the "why plain JAX-RS" rationale, relocated**: the SmallRye-collision reasoning still holds but the resource now lives in the library; reword to "why the stack is plain JAX-RS via `graphitron-jakarta-rest` rather than `quarkus-smallrye-graphql`".
- **Adjust the "What to copy" framing**: the table row linking `app/` stays (the directory is still the copy target); the "Three files cover the runtime" framing goes; the pom-shape sentence gains the `graphitron-jakarta-rest` + JSON-B-provider (Yasson) dependencies.
- **Fix the stale test-section sentence as an invariant, not a census** (same R399 fact-source, so folded in rather than split out): the enumeration style is what rotted. State the split ("HTTP-tier coverage lives in `@QuarkusTest` classes under `src/test/java/.../app/`; everything under `querydb/` stays in-process and Quarkus-free"), name `GraphQLOverHttpConformanceTest` as the one canonical exemplar, and link the directory rather than each file. Name the runtime-path axis in passing while touching both halves: the app section runs the owned-connection path, the in-process test pattern runs the escape hatch (`Graphitron.newGraphQL().build()` over a test-owned `DSLContext`), one clause each plus a single pointer to `runtime-extension-points.adoc`.
- **Add the enforcer the acceptance needs**: a `@UnitTier` sibling of `ManualXrefIntegrityTest` (same `internal/` package) that walks reader-facing `README.md` files, extracts relative markdown link targets (skipping `http(s)://` and pure anchors, as the adoc twin does), resolves them source-relative, and fails on a missing target. This ends the R416/R417 pattern of paying a roadmap item per hand-re-verification. Corollary applied in the prose: where the README names a file, prefer a markdown link over a backticked bare name, since the guard can only see links.
- **Reconcile `modules.adoc` line 47**: qualify "Per-module READMEs are deliberately not maintained" to name this README as the one deliberately-maintained exception, with the new link guard as its enforcer. (The alternative, promoting the app section into a manual how-to, is routed out as its own Backlog item: `roadmap/manual-howto-serve-schema-over-http.md`.)
- **Ride-along typo fix**: the doubled `graphitron-rewrite/graphitron-rewrite/` path segment in `docs/quick-start.adoc` lines 64/66 (absolute URLs, invisible to any local guard; two-character fix surfaced by this item's survey, cheaper to land here than to file).
- Keep the non-federated-boot paragraph and the "Run the app" / "GraphiQL playground" subsections as-is except where a sentence names the retired classes.

## Acceptance

- The new README link-integrity test is green and actually bites: it fails on the pre-fix README (the three dead `app/` links) and passes post-fix.
- The README nowhere references an example-owned `GraphqlEngine`, `GraphqlResource`, or `AppContext`; every statement about the adapter matches the actual `SakilaGraphitronApplication` source, by link rather than by paraphrase.
- Any `newExecutionInput()` mention carries the placeholder-credentials caveat; both README halves name which runtime path they demonstrate.
- The "what does a complete app look like" copy-this framing survives: a reader copying `app/` + schema + `application.properties` + the pom dependencies gets a working app, and the README says exactly that.
- `modules.adoc` no longer claims this README is unmaintained while the roadmap maintains it.
- Full reactor green under `mvn install -Plocal-db`.

## Routed out

- Manual how-to "Serve your schema over HTTP" covering the `GraphitronApplication` SPI adapter shape (the manual currently shows the wiring nowhere; the tutorial boots the app without explaining it): filed as `roadmap/manual-howto-serve-schema-over-http.md`.
