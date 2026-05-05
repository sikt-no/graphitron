---
id: R86
title: "Architecture chapter for the manual"
status: Backlog
bucket: Documentation
depends-on: []
---

# Architecture chapter for the manual

The diataxis user-manual chapter (R68) was authored with see-also and inline xrefs into a planned `docs/architecture/` chapter (entry point `getting-started.adoc`, plus `runtime-extension-points.adoc` and `rewrite-design-principles.adoc`). The chapter was never written. Under R68's UX review the broken xrefs were stripped: see-also bullets pointing at `architecture/...` were dropped, and inline mentions were retargeted to existing manual pages (`how-to/tenant-scoping`, `how-to/apollo-federation`, `reference/runtime-api`, the top-level `security` page) where the content overlapped, or to the rewrite-internal docs at `graphitron-rewrite/docs/` where the audience is contributors. This item covers writing the contributor-facing architecture chapter the manual originally promised. Scope: framework-level federation wiring (the `@link` opt-in surface, `<schemaInput tag>` flag, custom entity fetcher seam), runtime extension points (the `GraphitronContext` interface rationale, why per-app emission rather than a shared runtime jar), the typed-rejection design (sealed hierarchy, structured candidates), and the dev-loop runtime framing (LSP, schema watcher, classpath watcher) that the `dev` Mojo goal is the operational extract of. Audience: contributors and advanced users extending the runtime. The current `graphitron-rewrite/docs/` rewrite-internal docs are the closest existing material; this chapter should consolidate and surface them in the public manual.
