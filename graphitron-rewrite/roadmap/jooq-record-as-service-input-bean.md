---
id: R195
title: "Support jOOQ records as @service input-bean parameters via @field/@nodeId mapping"
status: Backlog
bucket: feature
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Support jOOQ records as @service input-bean parameters via @field/@nodeId mapping

A `@service` mutation whose Java parameter is a jOOQ generated `*Record`
(rather than a consumer-authored POJO/record) cannot be populated from an
SDL input type today, even when the input carries the legacy
`@table` + per-field `@field(name: "...")` + `@nodeId(typeName: "...")`
directives that already describe the column mapping for the legacy DML
path. `InputBeanResolver` matches SDL field names directly against
JavaBean setter names; it consults zero directives. The jOOQ record's
setters are derived from column names (`UTDANNINGSSTATUSKODE` →
`setUtdanningsstatuskode`), so SDL fields named `statuskode` /
`fraDato` / `utdanningsspesifikasjonsId` produce zero matches and the
binding list is empty, surfacing as
"bean class '…UtdanningsspesifikasjonsstatusRecord' has no fields
matching the SDL input type 'EndreUtdanningsspesifikasjonsstatusInput'"
at `InputBeanResolver.java:307`. R194 (the sibling fix) rejects this
combination loudly; this item is the feature that would make it work.

## Why this is not "just teach the resolver to read `@field`"

The mapping is not name-to-name. `@field(name: "UTDANNINGSSTATUSKODE")`
names a database column; to reach the jOOQ setter we need to go
column → jOOQ `TableField` → setter (jOOQ's column-to-property casing
is not always invertible via heuristic). `@nodeId(typeName: "X")`
requires global-ID decoding at the fetcher boundary and PK type
coercion (the wire value is an opaque string, the target column is a
`Long`). Both transforms already exist in the legacy DML path
(`MutationInputResolver`, `EnumMappingResolver.buildLookupBindings`,
`@table`-driven binding); this item asks whether they should be
factored out and reused from the `@service` path, or whether `@service`
should stay strictly POJO-shaped and consumers wanting jOOQ-record
population should declare their mutation through the DML path
instead.

## Open design questions for Spec

- **Scope of `@service` ambitions.** `@service` was introduced as the
  consumer-authored-bean path: SDL field name = Java property name,
  no directive translation. Should it grow a "jOOQ record" arm, or
  should we instead route the user toward the DML path
  (`@mutation(typeName: INSERT/UPDATE/DELETE)`) when their Java
  parameter is a jOOQ record? R97 ("Deprecate `@table` on input")
  pushes toward consumer-derived tables; this item's "feature"
  cements `@table`-on-input in a new place, which may be the wrong
  direction.
- **Directive surface.** If we do support it, which directives must
  the resolver honor? `@field(name:)` and `@nodeId(typeName:)` are
  the two seen in the bug report. `@lookupKey`,
  `@reference(name:)`, and other input-side directives need an
  explicit answer.
- **Type coercion.** SDL `ID!` → PK `Long`, SDL `String` → enum
  column, SDL `Date` → `LocalDate`, etc. Reuse the existing
  conversion machinery in the DML path, or write a thinner one
  scoped to this resolver?
- **Sequencing vs R97.** R97 deprecates `@table` on input in favor
  of consumer-derived tables. If R97 lands first, this item's
  directive contract changes (no `@table` on input → table comes
  from the consumer field's return type). Likely R97 should land
  first, or this item should adopt R97's consumer-derivation rule
  from day one.
- **Rejection vs feature.** R194 (separate item) is the small
  defensive fix: detect jOOQ records by `org.jooq.Record` supertype
  in `looksLikeBeanCandidate` and reject with a clear message. That
  ships first regardless of whether this item ever moves out of
  Backlog.

## Affected code

- `InputBeanResolver.java` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`)
  — the resolver that today reads zero directives.
- `MutationInputResolver`, `EnumMappingResolver.buildLookupBindings`
  — the legacy paths whose column-binding and enum-lookup
  machinery would be reused.
- Fetcher emit — the generated fetcher would need to wrap the
  jOOQ-record instantiation and `setX(...)` calls, plus `@nodeId`
  decoding at the boundary.

## Out of scope

- The defensive rejection (R194 sibling).
- Any change to the DML / `@mutation` path's existing behavior.
- Non-jOOQ ORM record types (Hibernate entities, MyBatis objects,
  etc.) — `@service` continues to accept POJOs and records of any
  origin via the existing path.
