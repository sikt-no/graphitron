---
id: R164
title: "Field model: three-dimension pivot"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-14
last-updated: 2026-05-15
---

# Field model: three-dimension pivot

The code graphitron emits today is close to what we want. The runtime DataFetchers, the jOOQ QueryBuilders, and the validation / error wiring all work and are recognisable; the trouble is the *model* describing them. This pivot reorganises the model into the three dimensions the emit already lives along: the DataFetcherBuilder dimension (runtime fetcher), the QueryBuilder dimension (jOOQ SQL), and the ValidationBuilder dimension (validation steps and error routing). Nothing about what gets emitted changes; the sealed hierarchy gets honest about what it's already saying.

## What is

46 permits across three sealed hierarchies: `QueryField` (10), `MutationField` (8), `ChildField` (28). `TypeFetcherGenerator` dispatches on permit identity with one arm per leaf. A mixin-interface overlay (`BatchKeyField`, `SqlGeneratingField`, `MethodBackedField`, `LookupField`, `TableTargetField`) carries cross-cutting traits.

Each permit name packs several decisions:

- where source comes from (root, parent-keyed, list-parent context)
- what the fetcher does at request time (no I/O, `@service` invocation, generated jOOQ)
- the field's output shape (single, list, connection)
- the jOOQ contribution (none, inlined column, own SELECT, UNION ALL, DML)
- modifiers (lookup mapping, error channel, splitQuery, ...)

`RecordLookupTableField` collapses four of these onto one identifier; `QueryServiceRecordField` collapses three. The cross product is the permit set, and adding a value to any axis multiplies the permits below it.

## What's to be: three dimensions

The emit splits along three dimensions, and the model should too:

1. **DataFetcherBuilder dimension** describes the runtime fetcher: how it consumes source, what it does at request time, what shape it returns.
2. **QueryBuilder dimension** describes the jOOQ SQL graphitron generates (or doesn't): which verb, target table, filter shape, value source.
3. **ValidationBuilder dimension** describes the validation / error wiring: which validation steps wrap the body, where violations route, which column-derived constraints surface at the field's SDL directive surface.

Each dimension has its own sealed hierarchy, and `Field` carries one slot per dimension.

```java
sealed interface Field permits QueryField, MutationField, ChildField {
    String name();
    SourceLocation location();
    ReturnTypeRef returnType();
    DataFetcherBuilder dataFetcher();
    QueryBuilder builder();
    ValidationBuilder validation();
}
```

The `QueryField` / `MutationField` / `ChildField` sub-seals survive as authoring-scope organisation. The permit-per-combination internals do not: each consolidates to a small number of records carrying the three-dimension slots plus the existing identity components (name, location, return type).

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

## ValidationBuilder dimension

The validator-wrapper machinery R12 §5 and R92 already emit is a coherent third artifact, with its own sub-shape and its own choices. Three sub-components describe what the wrapper around each fetcher invocation does:

- **Stages**: which `Validator.validate(...)` calls bracket the body. `None`, `OnInput` (R12 §5's pre-body `validate(input)`), `OnRecord` (R92's post-body `validate(record)`), or `OnBoth` (canonical mutation shape with R12 + R92 wired).
- **Error channel**: where `ConstraintViolation` translations route, and where any other field-level errors aggregate. The post-R164 home of the current `WithErrorChannel` mixin.
- **Surfaced constraints**: the column-derived constraints the field exposes at its SDL directive surface (for frontend introspection) and that the emitter ties into R92's `ConstraintMapping`. A derived view, projected from the field's `ColumnRef` at classify time; the source of truth stays on `TableType.columnConstraints` per R92.

```java
sealed interface ValidationBuilder permits
        ValidationBuilder.None,
        ValidationBuilder.OnInput,
        ValidationBuilder.OnRecord,
        ValidationBuilder.OnBoth {
    Optional<ErrorChannel> errorChannel();
    List<ColumnConstraint> surfacedConstraints();
}
```

(Final arm-vs-payload split settled at Spec; the snippet shows what the dimension carries.)

Pairings with the other two dimensions are constraint-by-construction rather than type-level invariants. The classifier infers the arm from the (`DataFetcherBuilder`, `QueryBuilder`) pair and the field's input shape: a `Trivial` root constant has `None`; a mutation with a typed input bean and a constructed record has `OnBoth`; a query with a typed input filter has `OnInput`; a `@service` field whose method takes a validated input bean has `OnInput` (and possibly `OnRecord` if the service returns a record graphitron then re-validates). The slot makes the choice explicit, so the wrapper emitter dispatches on identity rather than recomputing the decision mid-emit.

## Other dimensions

With the three main dimensions accounting for fetcher, SQL, and validation / error wiring, the cross-cutting `Modifiers` bag earlier drafts proposed shrinks to near-zero. The only remaining candidate slot is `ConditionJoinReportable` (the existing emit-time diagnostic axis); Spec decides whether it lives as a sibling slot, as a sub-component of one of the three Builders, or retires altogether.

What earlier drafts pushed into `Modifiers` and where it now lives:

- `@splitQuery` is the `Projection` → `Select` lever on `QueryBuilder`, not a modifier.
- `@lookupKey` is a `SourceKey` variant inside `Projection.sourceKey` / `Select.sourceJoin`.
- `@condition`, `@reference` are sub-components of `SourceKey` / `Filter`.
- `errorChannel` is the `ValidationBuilder.errorChannel` slot.
- The derived `ColumnConstraint` view is `ValidationBuilder.surfacedConstraints`.

Polymorphism is a sub-component of the `Projection` and `Select` arms, not its own dimension. Final placement (sub-component vs. its own slot) settles at Spec; the cross product doesn't change either way.

More dimensions may surface once the pivot lands and gets exercised against the consumer surface (LSP, capability catalog, schema validator). Cataloging that is Spec work; this section flags that the model has room.

## Mixin retirement

The five SQL / runtime mixin interfaces collapse into pattern matches on the three main dimensions:

- `BatchKeyField` → `dataFetcher() instanceof Query q && q.loader().isPresent()` (paired with `builder() instanceof Select`).
- `SqlGeneratingField` → `builder() instanceof Projection | Select | Insert | Update | Delete | Upsert`.
- `MethodBackedField` → `dataFetcher() instanceof Service` (equivalently `builder() instanceof Opaque`).
- `LookupField` → `Projection.sourceKey()` / `Select.sourceJoin().sourceKey()` is the `@lookupKey` variant.
- `TableTargetField` → `Projection.target` / `Select.target` carry the table.

`WithErrorChannel` retires onto `ValidationBuilder.errorChannel`. `ConditionJoinReportable` survives as a sibling interface, a `ValidationBuilder` sub-component, or its own slot; final placement settles at Spec.

## Consumer-side refactor scope

- `TypeFetcherGenerator` reorganises the 46-arm megaswitch into outer-on-`DataFetcherBuilder` arm, inner-on-`QueryBuilder` arm.
- `FetcherEmitter` dispatches on `QueryBuilder` within the `dataFetcher() instanceof Trivial` branch.
- `FetcherRegistrationsEmitter` consumes `Query` fields whose payload carries a `LoaderRegistration`.
- `TypeClassGenerator.emitSelectionSwitch` and `collectRequiredProjectionColumns` dispatch on `QueryBuilder.Projection | Select` and read their payloads directly.
- `QueryConditionsGenerator` consumes the SQL-generating `QueryBuilder` arms.
- `MultiTablePolymorphicEmitter` reads `Polymorphism` sub-components on `Projection` / `Select`.
- `SplitRowsMethodEmitter` reads the `LoaderRegistration` payload on `DataFetcherBuilder.Query`.
- `TypeFetcherGenerator`'s wrapper-emission sites (R12 §5's `validatorPreStep`, R92's `validate(record)` insertion site) dispatch on `ValidationBuilder` arms instead of computing the stage choice from channel + classifier data mid-emit.
- `GeneratedConstraintMappingGenerator` (R92) walks `ValidationBuilder.surfacedConstraints` across classified fields when emitting the `ConstraintMapping`. The future SDL constraint-directive emitter reads the same slot.
- `GraphitronSchemaValidator` replaces per-permit-identity dispatch with per-axis validation rules.
- Mixin retirements (above).
- `GraphitronSchemaBuilderTest`, `TypeFetcherGeneratorTest`, and every test that switches on permit type. Full enumeration is Spec work.

## Dependencies and sequencing

- R161 (`unify-record-dml-on-carrier-walk`) shipped; R178 then retired the carrier-walk family outright, so the starting model is closer to R164's target than this item's earlier drafts assumed. The `ReturnShape` per-arm sub-component on `QueryBuilder.Insert/Update/Delete/Upsert` lands on the surviving DML permits directly, with no intermediate consolidation step.
- Touches every emitter, validator, and a substantial portion of the test surface. Likely 2-4 weeks of focused work; should land before further model evolution to avoid rebasing on a moving target.
- Recommended sequencing inside R164: (1) introduce `DataFetcherBuilder`, `QueryBuilder`, and `ValidationBuilder` as sealed slots on `Field` alongside the existing permits; (2) populate them from existing permit data via a transitional adapter; (3) migrate consumers one at a time; (4) retire old permits and mixins; (5) delete the adapter. Each step verifiable and reversible.

## Vocabulary sharpening as part of the pivot

The codebase has accumulated overlapping vocabulary that mixes the three dimensions this item separates. The pivot is the right moment to resolve the duplication. Concretely:

- "Carrier" was the noun the retired carrier-walk regime used for an SDL Object wrapping a data field. R178 cleared the obvious R178-era survivors by renaming them to "payload" (`DmlPayloadScan`, `detectStructuralServicePayloadShape`, `findPayloadErrorsBinding`, `payloadDataFieldByType`, `PayloadDataField`, `SingleRecordPayloadPipelineTest`, `SingleRecordPayloadDmlTest`, etc.). "Payload" is the GraphQL-canonical noun for what the SDL fixtures already call `*Payload`, but it is also a pattern-narrow term; this pivot is the place to decide whether to fold the concept onto the more general "return type" / "target type" axes instead. Either choice is fine; what is not fine is keeping both vocabularies.
- "DML carrier" / "DML payload" treats DML as if it deserves its own structural concept. It does not: the generated DML code returns a domain `Record<X>` / `Result<RecordX<X>>` like any other generated query. The dimensional model under this pivot (`DataFetcherBuilder` × `QueryBuilder` × `ValidationBuilder`) replaces the per-verb wrapping vocabulary with a structural one. Expect "DML-payload" / "DML payload" prose to retire as the pivot lands.
- The error-channel scaffolding (`Optional<ErrorChannel>` on `WithErrorChannel`, `ErrorRouter.dispatch*`, `ChildField.ErrorsField.Transport`) is genuinely new infrastructure but is not DML-specific. Any field that uses `@error` can fail; the scaffolding lives on the `ValidationBuilder` dimension under this pivot.

Pivot work should treat vocabulary alignment as load-bearing alongside the structural reshape, not as a follow-up.
