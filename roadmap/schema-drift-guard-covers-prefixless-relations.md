---
id: R635
title: "The schema drift guard covers the prefix-less relations too"
status: Backlog
bucket: cleanup
priority: 4
theme: docs
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# The schema drift guard covers the prefix-less relations too

`SchemaIdentifierDriftCheck` decides whether a backtick-quoted identifier is the store's to police
by asking whether it starts with an observed family prefix
(`SchemaIdentifierDriftCheck.java:163`). That predicate is exactly right for the twelve families and
exactly wrong for the relations the schema deliberately places outside every family: `diagnostic` is
cited five times across four authored architecture pages
(`fact-model.adoc:19`, `fact-model.adoc:45`, `pipeline-overview.adoc:38`, `pipeline-overview.adoc:64`,
`typed-rejection.adoc:7`), and renaming it would leave every one of those citations stale with the
guard silent, which is the drift the guard exists to catch. The fix is small and needs no new source
of truth: the exemption roster already enumerates precisely this set, `StoreCatalog` already carries
it, and the scope predicate can read the union of the family prefixes and the exemption rows'
relation names. Worth checking at the same time whether the guard should scan the wider authored
`.adoc` surface (`docs/history/` cites the schema too) or stay scoped to `docs/architecture`.
