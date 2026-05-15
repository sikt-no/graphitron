---
id: R164
title: "Field model: DataFetcherBuilder and QueryBuilder pivot"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [unify-record-dml-on-carrier-walk]
created: 2026-05-14
last-updated: 2026-05-15
---

# Field model: DataFetcherBuilder and QueryBuilder pivot

The code graphitron emits today is close to what we want. The runtime DataFetchers and the jOOQ QueryBuilders both work and are recognisable; the trouble is the *model* describing them. This pivot reorganises the model into the two dimensions the emit already lives along: the DataFetcherBuilder dimension (runtime fetcher) and the QueryBuilder dimension (jOOQ SQL). Nothing about what gets emitted changes; the sealed hierarchy gets honest about what it's already saying.

## What is

46 permits across three sealed hierarchies: `QueryField` (10), `MutationField` (8), `ChildField` (28). `TypeFetcherGenerator` dispatches on permit identity with one arm per leaf. A mixin-interface overlay (`BatchKeyField`, `SqlGeneratingField`, `MethodBackedField`, `LookupField`, `TableTargetField`) carries cross-cutting traits.

Each permit name packs several decisions:

- where source comes from (root, parent-keyed, list-parent context)
- what the fetcher does at request time (no I/O, `@service` invocation, generated jOOQ)
- the field's output shape (single, list, connection)
- the jOOQ contribution (none, inlined column, own SELECT, UNION ALL, DML)
- modifiers (lookup mapping, error channel, splitQuery, ...)

`RecordLookupTableField` collapses four of these onto one identifier; `QueryServiceRecordField` collapses three. The cross product is the permit set, and adding a value to any axis multiplies the permits below it.

## What's to be: two dimensions

The emit splits along two dimensions, and the model should too:

1. **DataFetcherBuilder dimension** describes the runtime fetcher: how it consumes source, what it does at request time, what shape it returns.
2. **QueryBuilder dimension** describes the jOOQ SQL graphitron generates (or doesn't): which verb, target table, filter shape, value source.

Each dimension has its own sealed hierarchy, and `Field` carries one slot per dimension plus a small `Modifiers` bag.

```java
sealed interface Field permits QueryField, MutationField, ChildField {
    String name();
    SourceLocation location();
    ReturnTypeRef returnType();
    DataFetcherBuilder dataFetcher();
    QueryBuilder builder();
    Modifiers modifiers();
}
```

The `QueryField` / `MutationField` / `ChildField` sub-seals survive as authoring-scope organisation. The permit-per-combination internals do not: each consolidates to a small number of records carrying the two-dimension slots plus the existing identity components (name, location, return type).

## DataFetcherBuilder dimension

Three sub-axes describe the runtime fetcher:

- **Source cardinality** (`Zero` / `One` / `Many`). Where the field sits in the call tree. `Zero` is a root field. `One` is a child of a singleton-parent context. `Many` is a child of a list-parent context, where DataLoader amortises across sibling invocations.
- **Action** (`Trivial` / `Service` / `Query`). What the fetcher does. `Trivial` reads source / args / static config with no I/O. `Service` invokes a developer's `@service` method. `Query` executes a graphitron-generated jOOQ statement and consumes the result.
- **Field cardinality** (`Single` / `List` / `Connection`). The SDL return shape per invocation.

```java
sealed interface DataFetcherBuilder permits
        DataFetcherBuilder.Trivial,
        DataFetcherBuilder.Service,
        DataFetcherBuilder.Query {
    SourceCardinality sourceCardinality();
    FieldCardinality fieldCardinality();
}

record Service(SourceCardinality sc, FieldCardinality fc, MethodRef method)
        implements DataFetcherBuilder {}

record Query(SourceCardinality sc, FieldCardinality fc,
             Optional<LoaderRegistration> loader)
        implements DataFetcherBuilder {}
```

(Sub-shapes settled at Spec; the snippet shows shape, not final field set.)

Each action pairs with a constrained subset of `QueryBuilder` arms (compact-constructor invariant):

- `Trivial` pairs with `QueryBuilder.None` (constants, computed values with no SQL) or `QueryBuilder.Projection` (the field's columns inlined into the parent's SELECT; the fetcher reads materialised columns off the parent record).
- `Service` pairs with `QueryBuilder.Opaque`.
- `Query` pairs with `QueryBuilder.Select` for read paths and with `QueryBuilder.Insert | Update | Delete | Upsert` for mutations.

## QueryBuilder dimension

```java
sealed interface QueryBuilder permits
        QueryBuilder.None,
        QueryBuilder.Projection,
        QueryBuilder.Select,
        QueryBuilder.Insert,
        QueryBuilder.Update,
        QueryBuilder.Delete,
        QueryBuilder.Upsert,
        QueryBuilder.Opaque {}
```

`None` and `Opaque` are degenerate: no SQL is generated, or `@service` owns the I/O. The substance is in the Query and Mutation sub-chapters below.

### Query

Read-only SQL has two arms: `Projection` (contributes to the parent's SELECT) and `Select` (initiates its own SELECT).

```java
record Projection(
    TargetSurface target,             // column / expression / scalar-subselect / MULTISET shape
    Optional<SourceKey> sourceKey,    // present when this Projection joins a child table into the parent's FROM/JOIN
    Optional<Polymorphism> polymorphism
) implements QueryBuilder {}

record Select(
    TargetTable target,
    Optional<SourceJoin> sourceJoin,  // present iff sourceCardinality != Zero
    Projection projection,            // columns to return
    Filter filter,                    // arg-driven WHERE only
    Optional<Polymorphism> polymorphism
) implements QueryBuilder {}

record SourceJoin(SourceKey sourceKey) {}

sealed interface Filter permits Filter.None, Filter.KeyLookup,
                                Filter.BulkKeyLookup, Filter.Predicate {}
```

The `@splitQuery` SDL directive is the lever that flips a child field's QueryBuilder from `Projection` (inline-join, fetcher `Trivial`) to `Select` (separate query, fetcher `Query`). The same `SourceKey` payload travels with the field in both shapes; the arm decides where it lands in emitted SQL.

`SourceJoin` does not split further. The single batched shape (`SourceKey` plus a JOIN-onto-target predicate) covers both `sourceCardinality=One` (one-element VALUES) and `sourceCardinality=Many` (multi-element VALUES); both are N+1-safe by construction because `Select` always implies DataLoader on the runtime side. The would-be "direct inline" case is `Projection`, not `Select`.

Polymorphism (UNION ALL across tables, discriminator-column dispatch) is a sub-component of `Projection` and `Select`, not its own arm. It's orthogonal: a polymorphic field can be projection-shaped or select-shaped.

### Mutation

Mutations are root-only. `sourceCardinality` is always `Zero`. `SourceJoin` never appears on a mutation arm; FROM-clause content, when present, comes from argument-derived sub-components rather than from source.

```java
record Insert(
    TargetTable target,
    InsertRows rows,                  // arg-derived row source (one input or list of inputs)
    ReturnShape returnShape
) implements QueryBuilder {}

record Update(
    TargetTable target,
    SetSource set,                    // arg-derived SET expressions
    Filter filter,                    // arg-driven WHERE (typically required for safety)
    ReturnShape returnShape
) implements QueryBuilder {}

record Delete(
    TargetTable target,
    Filter filter,                    // arg-driven WHERE (typically required)
    ReturnShape returnShape
) implements QueryBuilder {}

record Upsert(
    TargetTable target,
    InsertRows rows,
    ConflictTarget conflict,
    ReturnShape returnShape
) implements QueryBuilder {}
```

`ReturnShape` consolidates R162's intuition: each verb's `RETURNING` shape (single record, list of records, projected wrapper, carrier wrapper) lives as a sub-component of the verb arm rather than spawning permit identities. The four verbs flatten from R162's per-permit-identity proposal into one arm per verb.

`Filter` is the same sealed family as on `Select`. `InsertRows`, `SetSource`, `ConflictTarget`, and `ReturnShape` are sealed payload families pinned at Spec.

## Other dimensions

The two main dimensions absorb most cross-cutting concerns into their payloads. What's left for `Modifiers` is small:

- error-channel routing (the `errorChannel` slot)
- the derived `ColumnConstraint` view projected from the field's `ColumnRef` at classify time (R92's vocabulary; the field-level surface for SDL constraint directives and Hibernate `ConstraintMapping` entries)
- condition-join reportability (the existing `ConditionJoinReportable` axis)

What earlier drafts pushed into `Modifiers` and which now lives elsewhere:

- `@splitQuery` is the `Projection` → `Select` lever, not a modifier
- `@lookupKey` is a `SourceKey` variant inside `Projection.sourceKey` / `Select.sourceJoin`
- `@condition`, `@reference` are sub-components of `SourceKey` / `Filter`

Validation is not a `Field` axis. Column constraints live on `TableType` (R92), surface on fields via the SDL-directive emit path that reads the field's `ColumnRef`, and at runtime via the wrapper-emission paths R12 §5 and R92 specify. The Field-level interaction is the derived `ColumnConstraint` view in `Modifiers`.

Polymorphism is a sub-component of the `Projection` and `Select` arms, not its own dimension. Final placement (sub-component vs. its own slot) settles at Spec; the cross product doesn't change either way.

More dimensions may surface once the pivot lands and gets exercised against the consumer surface (LSP, capability catalog, schema validator). Cataloging that is Spec work; this section flags that the model has room.

## Mixin retirement

The five mixin interfaces collapse into pattern matches on the two main dimensions:

- `BatchKeyField` → `dataFetcher() instanceof Query q && q.loader().isPresent()` (paired with `builder() instanceof Select`).
- `SqlGeneratingField` → `builder() instanceof Projection | Select | Insert | Update | Delete | Upsert`.
- `MethodBackedField` → `dataFetcher() instanceof Service` (equivalently `builder() instanceof Opaque`).
- `LookupField` → `Projection.sourceKey()` / `Select.sourceJoin().sourceKey()` is the `@lookupKey` variant.
- `TableTargetField` → `Projection.target` / `Select.target` carry the table.

`WithErrorChannel` survives as the `errorChannel` slot on `Modifiers`. `ConditionJoinReportable` survives as a sibling interface or `Modifiers` slot.

## Consumer-side refactor scope

- `TypeFetcherGenerator` reorganises the 46-arm megaswitch into outer-on-`DataFetcherBuilder` arm, inner-on-`QueryBuilder` arm.
- `FetcherEmitter` dispatches on `QueryBuilder` within the `dataFetcher() instanceof Trivial` branch.
- `FetcherRegistrationsEmitter` consumes `Query` fields whose payload carries a `LoaderRegistration`.
- `TypeClassGenerator.emitSelectionSwitch` and `collectRequiredProjectionColumns` dispatch on `QueryBuilder.Projection | Select` and read their payloads directly.
- `QueryConditionsGenerator` consumes the SQL-generating `QueryBuilder` arms.
- `MultiTablePolymorphicEmitter` reads `Polymorphism` sub-components on `Projection` / `Select`.
- `SplitRowsMethodEmitter` reads the `LoaderRegistration` payload on `DataFetcherBuilder.Query`.
- `GraphitronSchemaValidator` replaces per-permit-identity dispatch with per-axis validation rules.
- Mixin retirements (above).
- `GraphitronSchemaBuilderTest`, `TypeFetcherGeneratorTest`, and every test that switches on permit type. Full enumeration is Spec work.

## Dependencies and sequencing

- **Supersedes R162 and R163.** R162's verb-on-permit-identity becomes the verb-per-arm flattening on `QueryBuilder` (`Insert` / `Update` / `Delete` / `Upsert`) with `ReturnShape` as a per-arm sub-component. R163's `Record → Carrier` rename evaporates because the carrier-walk plumbing types decompose onto `Select` with appropriate `SourceJoin` shapes. Both Discardable once R164 enters Spec.
- **Depends on R161** (`unify-record-dml-on-carrier-walk`). The carrier-walk unification must settle in code before the model rewrites onto it.
- Touches every emitter, validator, and a substantial portion of the test surface. Likely 2-4 weeks of focused work; should land before further model evolution to avoid rebasing on a moving target.
- Recommended sequencing inside R164: (1) introduce `DataFetcherBuilder` and `QueryBuilder` as sealed slots on `Field` alongside the existing permits; (2) populate them from existing permit data via a transitional adapter; (3) migrate consumers one at a time; (4) retire old permits and mixins; (5) delete the adapter. Each step verifiable and reversible.
