---
id: R530
title: "Manual how-to: serve your schema over HTTP (the GraphitronApplication SPI adapter)"
status: Backlog
bucket: cleanup
priority: 6
theme: docs
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Manual how-to: serve your schema over HTTP (the GraphitronApplication SPI adapter)

The manual documents the `GraphitronApplication` SPI adapter shape nowhere: the tutorial boots the sakila example and curls `/graphql` without ever showing the wiring, and `AbstractGraphitronApplication` appears in `docs/manual/` only incidentally (`how-to/tenant-scoping.adoc`). The only consumer-facing "how do I serve my schema over HTTP" recipe is `graphitron-sakila-example/README.md`'s app section, a per-module README the docs system does not treat as a primary surface; that positioning gap is why the section kept drifting (R416, R417). Write a manual how-to ("Serve your schema over HTTP") covering the SPI seam: implement `GraphitronApplication` (or extend `AbstractGraphitronApplication`), supply the schema over the generated facade, build the engine on the owned-connection path via `Graphitron.runtime(dataSource, dialect)`, produce per-request execution input (with the real claims-payload derivation, not the example's hard-coded placeholder), and depend on `graphitron-jakarta-rest` plus a JSON-B provider. `ManualXrefIntegrityTest` already guards manual xrefs, the tutorial can point at it, and the example README's app section can shrink to a pointer plus the adapter link. Surfaced by the R417 Spec-time principles-architect consult.
