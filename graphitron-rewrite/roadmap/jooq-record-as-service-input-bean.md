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
property names are derived from column names
(`UTDANNINGSSTATUSKODE` → property `utdanningsstatuskode`), so SDL
fields named `statuskode` / `fraDato` / `utdanningsspesifikasjonsId`
produce zero matches and the binding list is empty, surfacing as
"bean class '…UtdanningsspesifikasjonsstatusRecord' has no fields
matching the SDL input type 'EndreUtdanningsspesifikasjonsstatusInput'"
at `InputBeanResolver.java:307`. R194 (the sibling fix) rejects this
combination loudly; this item is the feature that would make it work.

## Why this is not "just teach the resolver to read `@field`"

The mechanism is already in the codebase; what's missing is the
wiring into `InputBeanResolver`'s receiver-is-a-jOOQ-record case.
`@field(name: "UTDANNINGSSTATUSKODE")` names a database column,
which jOOQ-generated table classes expose directly as the static
`TableField` constant `Tables.T.UTDANNINGSSTATUSKODE` (no
setter-name inversion needed — `Record.set(Field<T>, T)` consumes
the `TableField` directly).
`TypeFetcherGenerator.emitSetMapPuts` (around `TypeFetcherGenerator.java:2078`)
already emits exactly that lookup
(`Tables.T.<col.javaName()>`) from `setFields()` and feeds it into a
`Map<Field<?>, Object>` that drives `.set(map)` on the DML chain.
`@nodeId(typeName: "X")` adds global-ID decoding at the fetcher
boundary and PK type coercion (wire value is an opaque string, target
column is a `Long`); that transform also already exists in the DML
path (`appendDecodeLocal` + the `nidk != null` branch in
`emitSetMapPuts`). So the question this item asks is not "build the
machinery" but: should the `@service` path grow a jOOQ-record arm that
reuses the existing column-binding plumbing, or should we route
consumers whose Java parameter is a jOOQ record toward the DML path
(`@mutation(typeName: ...)`) instead?

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
  column, SDL `Date` → `LocalDate`, etc. Most of this falls out of
  `Record.set(Field<T>, value)` + `DSL.val(value, field.getDataType())`,
  which the DML path already uses (`emitSetMapPuts` wraps every put
  in `DSL.val(..., getDataType())`); the open question is whether
  the `@service` arm reuses that same wrapping or whether populating
  a record (vs. building an update SET map) wants a thinner shape.
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
  — the DML paths whose column-binding and enum-lookup machinery
  would be reused.
- `TypeFetcherGenerator.emitSetMapPuts` (and the `nidk`/decode
  helpers it calls) — the existing column → `Tables.T.COL` +
  `DSL.val(value, field.getDataType())` emission that a
  jOOQ-record arm of `InputBeanResolver` would call into (likely
  emitting `record.set(Tables.T.COL, DSL.val(..., dataType))` per
  field rather than the DML path's `.set(Map)` form).
- Fetcher emit — the generated fetcher would need to instantiate
  the jOOQ record, call `record.set(Field, value)` per SDL field
  via the column lookup, and decode `@nodeId` at the boundary.

## Out of scope

- The defensive rejection (R194 sibling).
- Any change to the DML / `@mutation` path's existing behavior.
- Non-jOOQ ORM record types (Hibernate entities, MyBatis objects,
  etc.) — `@service` continues to accept POJOs and records of any
  origin via the existing path.
