---
id: R609
title: "Capture-load residuals from the fact-store delivery"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-10
---

# Capture-load residuals from the fact-store delivery

The fact-store item (R595, shipped; see `roadmap/changelog.md`) closed with a set of improvements
its contract did not demand, recorded in its body so a later pass could take or leave them. The
spec file deletes on Done, so this item preserves them. None is a defect; each is a sharpening a
consumer migration or a capture pass can pick up, and they need not ship together.

One of the five, the retained-partition scan skip, left this ledger and is recorded under R620
(`dev-loop-duplicate-classpath-scan`) as a route not taken: it was the one entry whose framing the
code did not support, the skip being unreachable from the scan's caller and gated on the store
gaining its first production reader. R620 carries the reachable saving in that area instead, the dev
loop's duplicate scan. The four below are unchanged and still consumer-gated.

- **A declined decode leaves no semantic-stratum record.** A decode arm that hits a missing
  required argument returns without writing either its decoded row or a
  `graphitron_undecoded_argument` row (`GraphitronFactCapture`'s `sourceRow`, `mutation` and
  `pivot` arms among others). The verbatim `graphql_` row survives, so a detection can still find
  the application, but nothing records that the decode declined. Either quarantine the
  application, or name the "verbatim graphitron application with no decoded row" detection as the
  intended reading.
- **`captureFacts` re-walks what `buildOutput` already holds.** `captureFacts` builds a second
  `JooqCatalog` and re-walks the catalog and the classpath (`GraphQLRewriteGenerator`) while
  `buildOutput` reuses the catalog walk it already has. Shadow-period cost only, cheap to thread
  through.
- **The nested-class filter is disclosed, not resolved.** `jvm_class` skips any simple name
  containing `$`, stated in its comment. A nested class named in `@record` resolves through the
  codegen loader and would be reported unknown by a resolution detection over this relation, the
  jar-census bug one axis over (top-level against nested rather than directories against jars).
  Widening the census here should land with its own measurement.
- **A shadowed classpath duplicate is discarded, not quarantined.** Cross-root dedup is
  first-wins in classpath order; the losing occurrence vanishes. `jvm_class.source_name` makes a
  duplicate-class detection possible (the SDL side's `graphql_duplicate_declaration` is the
  precedent); it can land with the consumer that wants it.
