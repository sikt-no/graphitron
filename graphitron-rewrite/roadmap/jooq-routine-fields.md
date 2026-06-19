---
id: R300
title: "First-class jOOQ routine support: read functions as target-shape provenance"
status: In Review
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

## Shipped (day-one table-valued read slice)

Landed in three slices: the fixture + jOOQ-regen foundation (`8249abb`), the model + directive +
reflection + resolver + emitter vertical (`0643d3c`), and the execution-tier proof + docs + gates
(`a141944`). Full reactor green (`mvn install -Plocal-db`), including an execution test that runs the
driving function end-to-end and asserts selection narrowing.

Three decisions diverged from the spec-as-reviewed, all settled by empirical jOOQ findings and a user
steer during implementation:

- **Emission rides the global `Routines` convenience method, not `<ROUTINE>.call(...)`.** jOOQ generates
  the table-valued function as a catalog `Table<R>` *and* a static method on the schema's global
  `Routines` class returning the configured table. The user steered to the `Routines` surface; the
  emitter produces `select(Type.$fields(...)).from(Routines.<method>(<bound args>)).fetch()`, which is
  the same SQL the pinned `.call(...)` shape would produce. The execution test (not a method-body
  string assertion) is the behavioural proof, per "no code-string assertions on generated bodies".
- **Discovery via the catalog; parameter metadata via the `Routines` method.** A table-valued function
  is in `Schema.getTables()` tagged `TableOptions.function()`, so `JooqCatalog.resolveTableValuedFunction`
  discovers it through the existing catalog path (no `AbstractRoutine` is generated for table-valued
  functions, even with `<routines>true</routines>`). The IN-parameter order/types/names come from
  reflecting the `Routines` table-form method; names depend on the consumer compiling jOOQ with
  `-parameters` (the fixture does).
- **Classification reuses `FieldClassification.QueryTableMethod`** (className = the generated `Routines`
  class) to keep the slice from rippling into the LSP label/hover surface. A dedicated `QueryRoutine`
  classification is a follow-up.

**Deferred from the validator-mirror section (follow-ups, not done in this slice):** the explicit
procedure-write `Operation` arm modeled-but-unpopulated with a `STUBBED_VARIANTS` entry, and the
translation of legacy's 26 `procedureCall*` rejection fixtures. The deferred scalar-read and
procedure-write forks already fail at validate time (not emit) because they do not resolve as
table-valued functions: `JooqCatalog.resolveTableValuedFunction` returns `NotInCatalog` /
`NotATableValuedFunction` and `RoutineDirectiveResolver` surfaces a typed rejection, which satisfies
"validator mirrors classifier". A child-positioned `@routine` is a typed rejection at `classifyField`.

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

1. **The provenance rides a new leaf, not a `TargetShape` payload slot (decision).** The R316 pivot prose
   says provenance "rides the target shape," but in landed code `TargetShape.Table` / `TargetShape.Column`
   are payload-free; `@tableMethod`'s `MethodRef` rides the **leaf record**
   (`QueryField.QueryTableMethodTableField`) and `target()` projects a bare `new TargetShape.Table()`.
   **R300 follows that landed leaf-carrier precedent rather than populating the deferred `TargetShape`
   payload slice:** the table-valued read field is a new `GraphitronField` leaf
   `QueryField.QueryRoutineTableField`, the routine analogue of `QueryTableMethodTableField`. It carries the
   `RoutineRef` provenance + the `ReturnTypeRef`, and its `target()` projects a bare `new TargetShape.Table()`
   exactly as `QueryTableMethodTableField` does today. `TargetShape.Table` **stays payload-free** (no
   model-wide change; populating `TargetShape` provenance is R316's deferred slice, not R300's). Choosing
   the leaf route keeps R300 consistent with the one provenance carrier the codebase actually has, and
   confines R300's blast radius to one new leaf plus its dispatch entry.

   The driving use case is a root `Query` field, so day-one mints only the root leaf. A child-positioned
   read routine (the `ChildField.RecordTableMethodField` analogue) is a deferred follow-up; the resolver
   produces a typed rejection for `@routine` on a non-root coordinate until that leaf lands.

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
  Reusing `argMapping` here is a deliberate exception to the "directive surfaces should not lean on
  `ExternalCodeReference`'s shape" caution: routine IN-param binding is irreducibly a param→arg map with an
  identity-bind default, which is *exactly and only* what `argMapping` expresses, so the vocabulary is the
  minimal SDL surface for the job rather than the over-broad reach the caution targets.
- **Routine catalog reflection on `JooqCatalog`.** The rewrite cannot depend on legacy `RoutineReflection`
  (it lives in an out-of-scope legacy module). `JooqCatalog` is the canonical permitted holder of raw jOOQ
  catalog types, so routine reflection (signature, IN/OUT params, return kind, generated `Routines` class +
  method) lands there as the routine-side analogue of `ServiceCatalog.reflectTableMethod`. Everything
  downstream switches on the typed `RoutineRef` and never touches `org.jooq.Routine`.
- **A `RoutineDirectiveResolver` in `FieldBuilder`** (per the existing per-directive resolver pattern:
  sealed `Resolved` result with success + `Rejected` arms), producing the `RoutineRef` provenance and the
  resolved `target` shape (`List(Table)` for the table-valued case).
- **Emit**: the `QueryRoutineTableField` fetcher mirrors `buildQueryTableMethodFetcher` — it declares the
  routine-call `Table<R>` local (`Routines.foo(<bound IN params>)`) and feeds it to the existing
  selection-aware `select(fields).from(<routine-call table>).where(...).orderBy(...).fetch()` path.
  **Projection decision (selection narrowing applies):** the function executes its full body regardless,
  but the wrapping `SELECT` projects **only the routine-result columns mapped to GraphQL subfields the
  query selected**, exactly as a catalog table field narrows. This honours the rewrite's selection-aware
  queries commitment rather than emitting `routine.*`. Concretely, for a query selecting only
  `organisasjonskode` against `tilganger_for_feidebruker_med_fs_fiktivt_fnr`, the pipeline test asserts the
  emitted shape is `select(<ROUTINE>.ORGANISASJONSKODE).from(<ROUTINE>.call(p_env, p_service_id, p_feide_id)).fetch()`
  (the single narrowed column, not both result columns and not `*`).

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
- **Pipeline tier**: the table-valued read classifies to `Fetch` / `List(Table)` carried by the new
  `QueryRoutineTableField` leaf, and emits the narrowed `select(...).from(<ROUTINE>.call(...)).fetch()`
  shape pinned above; the deferred forks classify to the typed rejection.
- **Generator dispatch coverage**: the new `QueryField.QueryRoutineTableField` leaf is added to
  `TypeFetcherGenerator.IMPLEMENTED_LEAVES`, keeping `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`'s
  four-way partition (`IMPLEMENTED_LEAVES` / `STUBBED_VARIANTS` / `NOT_DISPATCHED_LEAVES` / `PROJECTED_LEAVES`)
  exhaustive. The deferred child-position and procedure-write leaves, if minted as leaves rather than
  resolver-stage rejections, land in `STUBBED_VARIANTS` so the same partition test stays green and
  `ValidateMojo` fails the build on those coordinates rather than the generator throwing at emit.
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
  builds on. The read-routine field rides a new leaf carrying `RoutineRef` provenance (following the
  landed `QueryTableMethodTableField` precedent) and projects a bare `TargetShape.Table()`; the deferred
  procedure arm extends `Operation`. R300 does **not** populate R316's deferred `TargetShape` payload
  slice. R222 is the umbrella, not a blocker.
- **R95** (`routines-as-data-model-citizens`): the same feature from the data-model-citizen angle,
  superseded by this item; its error-catalog floor and 26-fixture inventory are absorbed above.
- Legacy reference: PR sikt-no/graphitron#489 (`@experimental_procedureCall`) and the `ProcedureCallError`
  / `procedureCall*` fixtures, useful as the rejection-arm inventory, **not** as a shape to copy (it locks
  in the scalar-inline corner and a `target:` mode flag this item rejects).
