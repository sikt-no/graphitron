---
id: R300
title: "First-class jOOQ routine support: read functions as target-shape provenance"
status: Spec
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-06-13
last-updated: 2026-06-19
---

# First-class jOOQ routine support: read functions as target-shape provenance

Graphitron has no first-class way to back a GraphQL field with a database **routine** (a jOOQ-generated
stored function or procedure). Today a user wraps a routine in an `@externalField` / `@tableMethod` Java
method, routing through the *domain* side (graphitron reflects an opaque `Field<X>` / `Table<R>` off a
hand-written shim) and re-deriving by hand the typing jOOQ already generates. This item makes routines a
recognised, **catalog-side** construct.

The driving use case is a table-valued PostgreSQL function:

```sql
CREATE OR REPLACE FUNCTION tilgangsstyring.tilganger_for_feidebruker_med_fs_fiktivt_fnr(
    p_env TEXT, p_service_id TEXT, p_feide_id TEXT
) RETURNS TABLE(organisasjonskode INTEGER, rollekode TEXT)
```

This is a **read** (no side effects, no OUT parameters, `RETURNS TABLE`). It should back a list-of-object
GraphQL field, with the three `TEXT` IN parameters bound from GraphQL field arguments.

> **Reframed 2026-06-19.** This item was originally written against R222's `carrier x intent x mapping`
> sketch and proposed a `RoutineCall` walker carrier plus a new `Procedure` *intent*. The field-side model
> has since pivoted (R316) to the landed `(source, operation, target)` model. The spec below is re-homed
> onto that model, and a `principles-architect` read corrected the carrier placement: read routines are
> **target-shape provenance** (operation stays `Fetch`), not a new operation arm. The write-procedure arm
> survives as a deferred, modeled-but-unpopulated `Operation` member. R95 (`routines-as-data-model-citizens`)
> is the same feature from the data-model-citizen angle; it is **superseded by this item**, with its
> error-catalog floor and fixture inventory pulled in below.

## The model fit: routines are the database twin of `@tableMethod` / `@externalField`

The landed `(source, operation, target)` model already models developer-supplied target shapes as
**provenance**, not as operations: `@tableMethod` supplies the `Table<R>` that `@table` would otherwise
resolve from the catalog, and `@externalField` supplies the `Field<X>` expression a `Column` would otherwise
read. The operation stays `Fetch`; only the *origin* of the projected Table/Column changes.

A **read routine is the database-native twin** of exactly that pair, so it folds into the same slot:

[cols="1,1,1,1"]
|===
| Routine shape | jOOQ surface | Developer-side twin | `(source, operation, target)`

| Table-valued function (`RETURNS TABLE` / `SETOF`)
| `Table<R>` attached in `FROM`
| `@tableMethod`
| `Fetch`, `List(Table)` whose Table origin is a routine call

| Scalar function
| `Field<T>` inline in `SELECT`
| `@externalField`
| `Fetch`, `Single(Column)` whose Column expression is a routine call

| Procedure (OUT/INOUT params, `CALL`)
| `Routine<R>`, `execute(ctx)` + OUTs
| (none; genuinely verb-shaped)
| a new `Operation` write arm (deferred)
|===

This is why a read routine is **not** a new `Operation` arm: its operation payload is identical to
`Fetch` (filter surface + ordering over the resulting Table/Column); the only thing that varies is where
the Table/Column *came from*, which is a `TargetShape` provenance distinction. Minting a `RoutineCall`
operation arm for reads would duplicate `Fetch`'s payload on a sibling, the cross-product disease the
operation seal exists to cure. (R300-as-written proposed a function operation arm and then immediately
walked it back with "functions fold into `Fetch`"; the provenance framing makes the spec internally
consistent.)

## Correcting the carrier placement (the two `principles-architect` fixes)

1. **`TargetShape` provenance is a slot R300 *creates*, not one it consumes.** The R316 pivot prose says
   provenance "rides the target shape," but in landed code `TargetShape.Table` / `TargetShape.Column` are
   payload-free; `@tableMethod`'s `MethodRef` rides the **leaf record** (`QueryField.QueryTableMethodTableField`)
   and `target()` projects a bare `new TargetShape.Table()`. So R300 owns introducing the provenance
   component for the routine sub-case (creating the slot at the `TargetShape` altitude, or following the
   landed leaf-carrier precedent at the same altitude `@tableMethod` uses today). The spec must not lean on
   a provenance slot that does not yet exist.

2. **The carrier is a new `RoutineRef`, not a reused `MethodRef`.** `@tableMethod` provenance is a
   `MethodRef.StaticOnly` (a reflected developer Java method). A jOOQ routine is a different resolution
   outcome: a **catalog handle** (`Routines.foo`) with typed IN parameters that bind from GraphQL arguments
   (and, later, columns / parent context / literals). Folding a catalog handle under `MethodRef` is the
   "god accessor whose meaning depends on the variant" smell. `RoutineRef` is a sibling provenance carrier
   that holds the routine's catalog identity plus its IN-parameter bindings. **Argument binding lives on
   `RoutineRef`, at the target endpoint** — because routine inputs parameterise the projected expression,
   the same role `@tableMethod`'s args play, *not* an operation payload the way `ServiceCall`'s params are.

## Day-one scope (the table-valued read slice)

Driven by `tilganger_for_feidebruker_med_fs_fiktivt_fnr`: a table-valued function backing a list field.

- **A `@routine` directive** (noun-shaped, engine-shaped, alongside `@tableMethod` / `@externalField` /
  `@reference`). It names the routine and maps Java IN parameters to GraphQL arguments via the existing
  `argMapping` vocabulary (`javaParam: graphqlArg`, comma-separated, identity-bind for unmentioned). No
  legacy-style `target:` / mode flag: the read shape (table-valued vs scalar) is **read off the routine's
  jOOQ kind**, not declared. (Naming and the `experimental_` prefix are settled here as `@routine` with no
  prefix; the carrier shape this item commits is the stabilising decision the prefix was waiting on.)
- **Routine catalog reflection on `JooqCatalog`.** The rewrite cannot depend on legacy `RoutineReflection`
  (it lives in an out-of-scope legacy module). `JooqCatalog` is the canonical permitted holder of raw jOOQ
  catalog types, so routine reflection (signature, IN/OUT params, return kind, generated `Routines` class +
  method) lands there as the routine-side analogue of `ServiceCatalog.reflectTableMethod`. Everything
  downstream switches on the typed `RoutineRef` and never touches `org.jooq.Routine`.
- **A `RoutineDirectiveResolver` in `FieldBuilder`** (per the existing per-directive resolver pattern:
  sealed `Resolved` result with success + `Rejected` arms), producing the `RoutineRef` provenance and the
  resolved `target` shape (`List(Table)` for the table-valued case).
- **Emit**: the resolved Table provenance feeds the existing selection-aware
  `select(fields).from(<routine call>).where(...).orderBy(...).fetch()` path. **Open spec detail to pin:**
  whether selection narrowing applies to the routine's fixed `RETURNS TABLE` result columns or whether the
  projection is `routine.*` (a function's result columns are a fixed projection, so selection-aware
  threading needs an explicit decision rather than assuming the table-field default).

## Validator mirror: reject the deferred forks (do not silently drop them)

The classifier will fork three ways on `@routine` (table-valued read / scalar read / procedure write).
Day-one implements only the first, so per "validator mirrors classifier invariants" the other two **must
fail at validate time**, not throw at emit. R95's legacy error floor is the rejection-arm inventory to
replicate (translated to the rewrite's typed `Rejection` channel):

- From `ProcedureCallError`: `UNKNOWN_ROUTINE`, `AMBIGUOUS_ROUTINE`, `NOT_A_FUNCTION` (procedure where a
  function is required), `MISSING_TABLE`, `UNKNOWN_PARAMETER`, `MISSING_PARAMETER`, `ARGUMENT_NOT_FOUND`,
  `RETURN_TYPE_MISMATCH`, `ON_ROOT_OPERATION`, `ON_NON_SCALAR_FIELD_TYPE`, `ON_INTERFACE_DECLARATION`,
  `ILLEGAL_COMBINATION` (with `@field` / `@externalField` / `@reference`).
- The deferred **scalar-read** and **procedure-write** forks each get a typed rejection until implemented;
  the procedure-write `Operation` arm is **modeled-but-unpopulated now** (alongside `UpdateMatching` /
  `DeleteMatching`) with a `STUBBED_VARIANTS` entry so `ValidateMojo` fails the build rather than the
  generator throwing `UnsupportedOperationException`.

## Test coverage

- **Classification tier**: one fixture per rejection arm, translated from legacy's 26 `procedureCall*`
  validation fixtures (preserve them rather than re-deriving).
- **Pipeline tier**: the table-valued read classifies to `Fetch` / `List(Table)` with `RoutineRef`
  provenance; the deferred forks classify to the typed rejection.
- **Execution tier**: a real `RETURNS TABLE` routine in the Sakila (or fixtures) DB backing a list field,
  invoked end-to-end with arguments bound, asserting rows come back. This is the proof the catalog handle
  and FROM-attach actually run.

## Out of scope (deferred follow-ups, each its own slice)

- **Scalar-function projection** (`Single(Column)` whose expression is a routine call) — the `@externalField`
  twin. Modeled by the same `RoutineRef` provenance on a `Column`; deferred so day-one stays the user's case.
- **Procedure with OUT/INOUT params** — the genuine new `Operation` write arm. Modeled-but-unpopulated now;
  populated in a later slice. Side-effecting `CALL` with OUT projection is mutation-shaped.
- **Record-returning functions** (`Field<R>` row constructor) — walks the record-mapping pipeline.
- **Heterogeneous binding sources** (columns / parent context / joined-row columns / literals as routine
  inputs). Day-one binds IN params from GraphQL arguments only; `RoutineRef`'s binding vocabulary is built
  to extend to these incrementally (an attribute of each binding, never a directive-level mode flag).
- **UDT / composite returns** — fold into the future embedded-records extension via jOOQ's generated UDT
  types.
- **Read-only-connection enforcement** to *guarantee* read polarity (today we trust the jOOQ kind:
  function -> read, procedure -> write).

## Relationships

- **R316** (`source-operation-target-pivot`): supplies the `(source, operation, target)` model this item
  builds on; the read-routine provenance extends `TargetShape`, the deferred procedure arm extends
  `Operation`. R222 is the umbrella, not a blocker.
- **R95** (`routines-as-data-model-citizens`): the same feature from the data-model-citizen angle,
  superseded by this item; its error-catalog floor and 26-fixture inventory are absorbed above.
- Legacy reference: PR sikt-no/graphitron#489 (`@experimental_procedureCall`) and the `ProcedureCallError`
  / `procedureCall*` fixtures, useful as the rejection-arm inventory, **not** as a shape to copy (it locks
  in the scalar-inline corner and a `target:` mode flag this item rejects).
