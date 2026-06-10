---
id: R297
title: "Collapse the shareable boolean on ConnectionType/EdgeType/PageInfoType; read federation flags off schemaType()"
status: Backlog
bucket: tech-debt
priority: 6
theme: pagination
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Collapse the shareable boolean on ConnectionType/EdgeType/PageInfoType; read federation flags off schemaType()

`ConnectionType` / `EdgeType` / `PageInfoType` each carry a `shareable` boolean component alongside their `schemaType()` `GraphQLObjectType`. After R295, federation `@tag` propagation onto these synthesised types is driven entirely off `schemaType()` (the applied directives ride on the schema form; no `tags` record component, per "Model metadata over parallel type systems"). The `shareable` boolean is now the asymmetric survivor: it is a second representation of `@shareable`, redundant with the schema form for emission (the synthesised `schemaType()` already carries the directive, and no emitter reads `shareable()`), and its only consumer is the `pageInfoShareable |=` fold inside `ConnectionPromoter.promote`. That fold also reads tags off the schema forms, so it mixes two representations of the same class of information in one place.

The cleanup: remove the `shareable` boolean from all three records and have the PageInfo fold read both `@shareable` and `@tag` uniformly off `schemaType()` (e.g. `hasAppliedDirective("shareable")` and `getAppliedDirectives("tag")`), making the dedupe-arm union and the fold symmetric. Blast radius: the three record signatures in `GraphitronType`, their construction sites in `ConnectionPromoter`, the structural-arm `pageInfoObj.hasAppliedDirective("shareable")` read, and the `GraphitronSchemaBuilderTest` rows that assert the boolean. R295 deferred this as an optional cleanup ("in scope if it falls out naturally, otherwise note it for a cleanup item"); it did not fall out naturally, so it is filed here.
