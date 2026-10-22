---
id: R1090
title: "A connection has no maximum page size, so first: 100000 reads 100000 rows"
status: Backlog
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A connection has no maximum page size, so first: 100000 reads 100000 rows

## Goal

A consumer should be able to set a maximum page size for every `@asConnection` field, as Graphitron 9's `<maxAllowedPageSize>` did. Today a client that sends `first: 100000` (or `last:`) gets that many rows in one query. The manual tells consumers to enforce a cap themselves with a graphql-java instrumentation. After this item the generated `ConnectionHelper.pageRequest` applies the configured maximum. Without a configured maximum, behaviour is unchanged. The setting is opt-in.

## Why in Graphitron rather than in each consumer

Reported by sis, whose Graphitron 9 build set `<maxAllowedPageSize>1000</maxAllowedPageSize>` (`sis-graphql-spec/pom.xml`). Under 9 a larger `first:` was silently clamped to 1000 with `hasNextPage: true`, and sis's consumers page on that basis. A consumer can rebuild this with an instrumentation that rewrites `first`/`last` before the fetcher reads them, but:

- **The instrumentation has to work out which fields are connections**, from argument names and return-type naming. The generator already knows, and every connection path goes through one method: `pageRequest`. Its callers are the root and batched (`@splitQuery`) launchers in `RootLauncherRenderer` and `BatchedRowsFragments` and the multi-table interface paths in `MultiTablePolymorphicEmitter`.
- **Every Graphitron 9 consumer has the same need.** In fs-plattform that is sis now, and utdanningsregisteret and opptak when they move to Graphitron 10. Each would write and test the same instrumentation.
- **A cap protects the database**, which is the generator's concern as much as the API layer's. `defaultFirstValue:` already shows the generator owning page-size policy for the omitted-`first:` case.

The manual records that the setting was "deliberately excised". The changelog gives no reason beyond the Maven plugin's parameter clean-up. This item asks for the capability back as opt-in, not for the old parameter as it was.

## Sketch (fill in at Spec)

- **Where it is configured**: a Maven plugin parameter, which makes it a constant in the generated `ConnectionHelper` as in 9, or a `GraphitronRuntime` setting, which a deployment can change without regenerating. A per-field override on `@asConnection` is optional.
- **Clamp or reject**: 9 clamped silently. Clamping keeps 9 parity, and `hasNextPage` stays correct because the query fetches `pageSize + 1` rows. Rejecting with a `GraphitronClientException` tells the client explicitly. The Spec may offer both, with clamp as the 9-compatible default.
- **Scope of the cap**: `first` and `last` both. `defaultFirstValue:` above the cap is a build error, or is clamped too.
- **Docs**: `connections.adoc` "Page size", `asConnection.adoc` and the `<maxAllowedPageSize>` line in `migrating-from-legacy.adoc` are rewritten to describe the setting.
