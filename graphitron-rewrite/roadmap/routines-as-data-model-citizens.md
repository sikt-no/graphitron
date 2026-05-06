---
id: R95
title: "Routines as data-model citizens (jOOQ-native routine support)"
status: Backlog
bucket: feature
priority: 7
theme: legacy-migration
depends-on: []
---

# Routines as data-model citizens (jOOQ-native routine support)

The rewrite has no support for resolving GraphQL fields from jOOQ-generated routines (functions and stored procedures). Legacy is shipping `@experimental_procedureCall` (PR #489 in `sikt-no/graphitron`, on top of the already-merged `baf8c10`), but it cherry-picks the smallest possible slice of the design space and locks in a shape that does not generalise. The rewrite should treat routines as first-class data-model citizens alongside tables, in the same vocabulary it already uses for `@table`, `@reference`, and `@field`.

## Why legacy is the wrong starting point

The legacy directive supports exactly one corner: **scalar-returning functions, called inline as a `Field<T>` projection in the surrounding SELECT**. Everything else is rejected by the validator:

- "Procedure" in the name is misleading; the validator rejects anything without a return value or with OUT/INOUT parameters.
- Set-returning functions (`RETURNS SETOF` / `RETURNS TABLE(...)`) are not supported. These are an everyday PostgreSQL primitive and the most underused jOOQ surface in the existing codebase.
- Record-returning functions are not supported.
- Procedures with OUT/INOUT parameters (Oracle / SQL Server idiom) are not supported.
- The directive carries a `target:` mode flag that re-defines argument-source semantics (columns vs. GraphQL arguments). The flag exists because legacy needed two attachment points; in the rewrite there is no reason to bake mode into the directive.

The full space jOOQ already abstracts:

| Routine shape                 | jOOQ surface                                  | GraphQL shape          |
|------------------------------|----------------------------------------------|------------------------|
| Scalar function              | `Field<T>` (inline in SELECT)                | scalar field           |
| Set-returning / table fn     | `Table<R>` (in FROM/JOIN)                    | list-of-object field   |
| Record-returning function    | `Field<R>` row constructor                   | object field           |
| Procedure with OUT/INOUT     | `Routine<R>` instance, `execute(ctx)` + OUTs | mutation with outputs  |

Each row is a different jOOQ idiom and a different GraphQL field shape. The legacy carrier has no place to express any row but the first.

## Framing for the rewrite

Routines are catalog elements, same as tables. `Routines.foo(...)` is a typed handle into the jOOQ-generated catalog the same way `CUSTOMER.CUSTOMER_ID` is. `RoutineReflection` already lives in `graphitron-common` next to `TableReflection`; the rewrite has accepted the data-model framing at one layer.

The right neighbourhood for the carrier is therefore alongside `@field` / `@reference`, not alongside `@service`. From the outside, "this field's value comes from a Java method" (service) and "this field's value comes from a database routine" (routine) can look similar, but the rewrite's directive taxonomy is engine-shaped (`@splitQuery`, `@lookupKey`, `@batchKeyLifter`), not user-intent-shaped, and routines are SELECT-projection-shaped, not Java-dispatch-shaped.

The legacy `target:` parameter then reads not as a mode flag but as one variation in a larger binding-source space: the data-model element (the routine) is constant; what varies is where its inputs are bound from (row columns, GraphQL arguments on the surrounding fetcher, parent context, joined-row columns, literals). One carrier; heterogeneous binding sources.

## Day-one scope vs. carrier shape

Day-one *implementation* can stay narrow. Scalar-projection inline mode is the case Sikt has actual use cases for and is the smallest useful slice. The point of this item is the *carrier shape* does not preclude the other rows in the table above:

- The directive is named after routines (jOOQ's term, the SQL standard's term), not "procedureCall."
- The carrier has room to describe set-returning and record-returning shapes when those land.
- Argument-source vocabulary handles row columns and GraphQL arguments uniformly; adding parent / join / literal sources later is incremental.
- Procedure-with-OUT-params is acknowledged as mutation-shaped and out of this item's scope; it lives in the same neighbourhood as `@mutation` / `@tableMethod` write paths and is its own follow-up.

## Open design questions for Spec

These are the forks the Spec will need to land. None of them are settled here:

1. **One carrier or several.** Single directive (`@routineSource` / `@routine`) parameterised by return shape, or one directive per shape (`@routineProjection`, `@routineTable`, `@routineRecord`, `@routineExecute`)? The data-model framing argues for one; the implementation paths argue for separate.
2. **Folding into `@field`.** "This field's value comes from a routine over these columns" is a generalisation of "this field's value is this column." Is the right primitive `@field(routine: ..., arguments: ...)` rather than a sibling directive?
3. **Naming.** `@experimental_procedureCall` reads verb-shaped and service-flavoured. A noun-shaped, data-model-flavoured name (`@routineSource`, `@routineProjection`, or a `@field` extension) lines up with the rewrite's other catalog directives. Worth a `principles-architect` consult.
4. **`experimental_` prefix.** Legacy ships these `experimental_*`. The rewrite has no installed base, so it could either preserve the prefix (signal: still cooking) or drop it (signal: this is the stable shape we want). Defer until the carrier is decided.
5. **Validator placement.** Legacy puts routine resolution in `ProcedureCallValidator`. The rewrite's `FieldBuilder` dispatches via per-directive resolvers; the natural shape is a `RoutineDirectiveResolver` (or extension to `FieldDirectiveResolver`) that reuses `RoutineReflection`.

## What is not in scope here

- **Set-returning function support** (FROM/JOIN-attach via `Table<R>`). High-leverage, but its own item; this one only commits the carrier shape leaves room for it.
- **Procedure-with-OUT-params support.** Mutation-shaped, separate item.
- **Record-returning function support.** Walks the rewrite's record-mapping pipeline; separate item.
- **Renaming or stabilising past the `experimental_` prefix** once the carrier is decided. Standard stabilisation step, separate item.
- **A `mode:` / `target:` flag on the carrier** that re-defines argument-source semantics by structural presence (legacy's pattern). Heterogeneous binding sources are an attribute of each binding, not a directive-level mode switch; bundling them under one mode-flagged directive is precisely the legacy shape this item rejects.

## Adjacency

- Legacy reference: PR sikt-no/graphitron#489 (`GG-129-input-procedures` branch) and the merged `baf8c10` precursor. Useful as a list of error cases the validator should cover, not as a shape to copy. Two concrete copy-targets for the day-one scalar-projection slice:
    - **Error catalog floor.** Legacy's 12-entry `ProcedureCallError` enum (`graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/validation/messages/ProcedureCallError.java`) names the minimum set of rejection arms a routine-directive resolver in the rewrite must cover: `UNKNOWN_ROUTINE`, `AMBIGUOUS_ROUTINE`, `NOT_A_FUNCTION`, `MISSING_TABLE`, `UNKNOWN_PARAMETER`, `MISSING_PARAMETER`, `NONEXISTENT_COLUMN`, `RETURN_TYPE_MISMATCH`, `ON_ROOT_OPERATION`, `ON_NON_SCALAR_FIELD_TYPE`, `ON_INTERFACE_DECLARATION`, `ILLEGAL_COMBINATION` (with `@field` / `@externalField` / `@reference`). Several are taxonomy-shaped checks the resolver must replicate regardless of carrier shape.
    - **Validation fixture set.** Legacy's 18 minimal isolated fixtures under `graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/validation/query/procedureCall*/schema.graphqls` each tie one error code to one schema. They translate directly into classification-tier coverage (one fixture per resolver rejection arm); preserve them rather than re-deriving from scratch.
- Existing rewrite directive resolvers in `FieldBuilder` (ServiceDirectiveResolver, TableMethodDirectiveResolver, etc.) are the closest pattern to follow.
- `RoutineReflection` in `graphitron-common` is reusable across legacy and rewrite.
- `ArgBindingMap` / `ArgCallEmitter` are the existing mechanism for "GraphQL argument → Java parameter" binding and would be reused for the GraphQL-argument-source case, regardless of which carrier shape lands.
