# Argument Resolution — Phase 4

> **Status:** In Review
>
> Phases 1–3 have shipped. Phase 4 (`@condition` on `INPUT_FIELD_DEFINITION`) remains deferred. This plan now covers Phase 4 only.

## Foundation (shipped — relied on by Phase 4)

- `FieldBuilder.classifyArguments` produces `List<ArgumentRef>` — a builder-internal sealed hierarchy (`ColumnArg`, `UnboundArg`, `TableInputArg`, `PlainInputArg`, `OrderByArg`, `PaginationArgRef`, `UnclassifiedArg`).
- Projection helpers (`projectFilters`, `projectOrderBySpec`, `projectPaginationSpec`, `projectForLookup`) turn refs into `WhereFilter`, `OrderBySpec`, `PaginationSpec`, `LookupMapping`.
- `@condition` on `FIELD_DEFINITION` and `ARGUMENT_DEFINITION` is supported; `contextArguments` flow through `ServiceCatalog.reflectTableMethod` into trailing `ParamSource.Context` parameters on the emitted `ConditionFilter`.
- `InputColumnBinding` is defined in `model/`; `TableInputArg.fieldBindings` is its consumer. Composite-key `@lookupKey` via `@table` input types is wired end-to-end via `FieldBuilder.buildLookupBindings` → 2-segment `LookupColumn.sourcePath` → `LookupValuesJoinEmitter` grouping by root argument.
- `LookupField` capability interface with a non-`Optional` `LookupMapping lookupMapping()` is populated at classify time; `projectForLookup` is the sole reader.

## Phase 4 — `@condition` on `INPUT_FIELD_DEFINITION`

**Goal.** Support `@condition` on fields *inside* input types (the third legal position per `directives.graphqls`). Each input-type field with `@condition` contributes its own predicate, scoped to that field. Nested input types can each carry their own conditions.

**Sub-items.**

- **4a.** Extend `InputField` to carry an optional `ArgConditionRef` — the per-input-field `@condition` directive, reflected at type-build time.
- **4b.** When an input-type arg is used at a call site, its per-field conditions become additional `ConditionFilter` entries in the lookup emitter's output.
- **4c.** Override propagation: an outer-level `@condition(override: true)` on the arg suppresses inner fields' auto-predicates but not their explicit `@condition` methods (per legacy semantics, README §645–674).

**Test matrix (required before 4b begins landing).** The override-propagation interaction has four legal states per field and compounds across nested input types. Write the full matrix out before emitting any code:

| Outer arg `@condition` | Inner field `@condition` | Inner field auto-predicate | Inner explicit condition method |
|---|---|---|---|
| Absent | Absent | Emitted | — |
| Absent | Present (no `override`) | Emitted | AND-ed |
| Absent | Present (`override: true`) | Suppressed | Replaces |
| `override: true` | Absent | Suppressed | — |
| `override: true` | Present (no `override`) | Suppressed | AND-ed |
| `override: true` | Present (`override: true`) | Suppressed | Replaces |
| `override: false` | Absent | Emitted | — |
| `override: false` | Present (no `override`) | Emitted | AND-ed |
| `override: false` | Present (`override: true`) | Suppressed | Replaces |

One classification test + one execution test per row. Without this matrix written up front, the four-state-per-field compounds to N × M states across nested inputs and we'll debug cases instead of specifying them.

## Out of Scope

- **Mutations.** Input-type arguments for DML use a different mapping. Mutations get their own plan.
- **Non-`@table` input types with columns.** The legacy "implicit-table" heuristic is not reproduced. Inputs must carry `@table`.
