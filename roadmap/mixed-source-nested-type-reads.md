---
id: R503
title: "Mixed-source nested types: first-wins classification leaves single-arm child reads"
status: Backlog
bucket: architecture
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-19
last-updated: 2026-07-19
---

# Mixed-source nested types: first-wins classification leaves single-arm child reads

A directiveless output type reached both as a nesting projection of a `@table` parent and as a
field of a class-backed `@service` result is classified first-edge-wins: `registerNestingTypesIn`
and `registerProducerBackedCarrier` are both `contains`-guarded, so whichever edge the walk visits
first decides between `NestingType` and a class-backed `ResultType`, and the other edge's
implication is silently dropped. Child-read emission then follows the single winner
(`FetcherEmitter.propertyOrRecordBinding` emits exactly one read per fetcher: by-name off a generic
jOOQ `Record`, or a pre-resolved accessor), while graphql-java registers one datafetcher per
`(type, field)` coordinate. At run time the losing edge hands that fetcher the other source shape:
a POJO to a `((Record) source).get(...)` read, or a jOOQ `Record` to an accessor cast. No validator
rejects the mix, so this surfaces as a `ClassCastException` on a live request, and which schemas
break depends on walk order. Audit whether any schema can actually reach the mix today, and either
reject it at validate time or emit the run-time source-shape dispatch over the classifier-known
shape set. The `@pivot` item (`roadmap/pivot-projection-directive.md`) specifies exactly that
arm-union mechanism at the nested-wiring seam for its own coexistence cases; once it ships, this
item likely reduces to extending the same seam to the plain nesting + service mix plus execution
coverage.
