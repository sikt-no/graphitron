---
id: R672
title: "Register every built-in scalar the emitted schema references, not just the ones the SDL uses"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Register every built-in scalar the emitted schema references, not just the ones the SDL uses

A schema whose SDL text never mentions `Int` generates without complaint and then fails at application startup with `graphql.AssertException: type Int not found in schema`. Reported against 10.0.0-RC30 on a schema whose only `Int` usage is the pagination surface `@asConnection` synthesises: the author declares `applikasjoner: [Applikasjon] @asConnection`, never writes `Int` anywhere, and the generated `GraphitronSchema.build()` names `Int` through a `typeRef` with no matching `additionalType` registration. Declaring `first: Int` by hand on the field makes the build work, which is the workaround consumers are on today.

## Why it happens

Built-in scalars are not auto-registered on a programmatic schema the way `SchemaGenerator` registers them for SDL, so `GraphitronSchemaClassGenerator` emits one `schemaBuilder.additionalType(...)` per `GraphitronType.ScalarType` row in `schema.types()`. Those rows come from `TypeBuilder.classifyAndRegister`, driven by the classification walk, so the set is "scalars the walk reached through authored SDL", not "scalars the emitted schema references".

Connection synthesis runs after that walk. `ConnectionPromoter.rebuildAssembledForConnections` mints the `first` / `after` arguments, the `Connection` and `Edge` types, `PageInfo` and `totalCount` on the assembled schema once classification is over, so the scalars those surfaces reference are never candidates for registration. `Int` is the reported case; `String` (via `after`, `endCursor`) and `Boolean` (via `hasNextPage`) sit behind the same hole and would surface on a schema that happens not to use them either.

The property the generator does not hold: **every scalar the emitted schema references is registered on the builder, independent of what the author wrote.** Today registration is sourced from author-reachability, and the two sets diverge the moment the generator synthesises a surface of its own.

## Notes for whoever specs this

- `TypeBuilder` already builds a `scalarVerdicts` map over `ctx.schema.getAllTypesAsList()`, an all-declared superset keyed by scalar name, deliberately independent of walk order. Whether the fix sources registration from the emitted reference set (accurate, needs a reference sweep over the assembled schema) or from that all-declared superset (cheap, over-registers unused scalars) is the design question, not a foregone conclusion.
- A close sibling shipped before: `@scalarType`-aliased consumer scalars produced the same `type <SdlName> not found in schema` failure through a different path (`ScalarResolution.Synthesised` registering under the constant's name). Its execution-tier proof shape, a fixture asserting the assembled schema registers the name, transfers directly.
- The registration site became SDL-conditional when the hardcoded five-site built-in `additionalType` block was retired in favour of resolver-driven registration. That change was right for consumer scalars and silently narrowed the built-ins.
- A regression test wants to be a schema where a synthesised surface is the *only* user of a built-in scalar. The obvious shape is an `@asConnection` field on a schema with no other `Int`.

Reported at https://github.com/sikt-no/graphitron/issues/527.
