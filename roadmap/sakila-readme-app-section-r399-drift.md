---
id: R417
title: "Reconcile sakila-example README app-section with R399 (dead GraphqlEngine/GraphqlResource/AppContext links)"
status: Backlog
bucket: cleanup
priority: 7
theme: docs
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Reconcile sakila-example README app-section with R399 (dead GraphqlEngine/GraphqlResource/AppContext links)

`graphitron-sakila-example/README.md` still describes the runtime under a "Runnable reference (the app)" section (roughly lines 17-27) as three example-owned files: `GraphqlEngine.java`, `GraphqlResource.java`, and `AppContext.java`. R399 extracted all of that into `graphitron-jakarta-rest`; the example now ships a single `SakilaGraphitronApplication` adapter (a `GraphitronApplication` SPI implementation) and depends on the library for the `/graphql` resource, the engine, status-code semantics, the `/schema` endpoint, and the GraphiQL page. The three source links in that section are dead, and the "three files cover the runtime" framing is false.

R416 reconciled only the GraphiQL-specific claims in this README (the `/graphiql/` redirect prose and the deleted `META-INF/resources/graphiql/` bundle) because those were in its scope; it deliberately left the broader R399 app-section drift alone rather than expand scope. This item is that follow-up: rewrite the app section around `SakilaGraphitronApplication` + the `graphitron-jakarta-rest` dependency, fix or remove the dead `GraphqlEngine`/`GraphqlResource`/`AppContext` links, and keep the "why plain JAX-RS" and per-request-context prose accurate to where that logic now lives.
