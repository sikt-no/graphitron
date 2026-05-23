---
id: R222
title: "Input model: dimensional pivot under visitor-driven classification"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-23
---

# Input model: dimensional pivot under visitor-driven classification

The input-type classification surface has been the most-churned model area in the rewrite over the last quarter: R94, R96, R150, R155, R178, R191, R205, R210, R211, R215 all landed surgical patches on it, and Backlog still carries R171, R97, R213, R209, R200, R195, R98, R220, R193, R172 on the same surface. R164 (`field-model-two-axis-pivot`) names the underlying disease one layer over: a sealed hierarchy that tries to encode a multi-dimensional space as a one-dimensional permit set. Input types are the same disease in a smaller organ; this item performs the pivot on inputs first, as the proof-of-concept for R164 and as the natural home for an R166-style visitor walk on the smaller surface.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits today encode three independent axes onto one identity slot:

| Permit | Backing-class kind | Table-bound? | Eager column classification? |
|---|---|---|---|
| `PojoInputType` | POJO (or null) | no | no |
| `JavaRecordInputType` | Java record | no | no |
| `JooqRecordInputType` | jOOQ `Record<?>` | no | no |
| `JooqTableRecordInputType` | jOOQ `TableRecord<?>` | yes, from backing class | no |
| `TableInputType` | (graphitron-emitted from R94) | yes, from `@table` directive **or** consumer chain | yes, `List<InputField>` |

Three axes (*backing-class kind*, *table-binding and where the binding came from*, *whether fields are eagerly column-classified*) collapsed onto five permit identities. The cross product is sparse and lopsided:

- The "table-bound × non-jOOQ-TableRecord-backed × eager-classified" cell is `TableInputType`.
- The "table-bound × jOOQ-TableRecord-backed" cell is `JooqTableRecordInputType`, and **the table comes from a different source** (reflection on the backing class vs `@table` directive vs consumer inference).
- The "unbound" row has four permits that differ only on backing-class kind.

Two further signals point at the encoding being the wrong shape:

1. `TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` (an O(N) back-scan over all schema fields). Whether an SDL input is "table-bound" is *already* a property of the consumer chain, not the input declaration; it is encoded today as a back-scan that runs once per input rather than once per use.

2. R215's lift (In Review) admits `InputField.UnboundField` into `TableInputType.inputFields()`, so the column-coverage invariant that previously distinguished `TableInputType` from `InputType` (one classifies columns eagerly, the other doesn't) has eroded. The classifier-side commitment is now "best-effort eager, deferred to consumption on miss", which collapses the third axis.

The capability marker `HasValidationShape` (`HasValidationShape.java:17-22`) already has to list five permits to span both branches; R171 (Backlog) names this as the immediate symptom and proposes a `sealed interface InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact.

Nine consumer sites discriminate on the split today:

- `GraphitronSchemaValidator.java:80-81`: case arms on `InputType` and `TableInputType`.
- `MutationInputResolver.java:368`: `instanceof GraphitronType.TableInputType` gate.
- `EnumMappingResolver.buildLookupBindings(GraphitronType.TableInputType, ...)` (`EnumMappingResolver.java:303`).
- `CatalogBuilder.java:206 / 498 / 570 / 639`: four switch sites on `TableInputType`.
- `FieldBuilder.java:967`: `instanceof GraphitronType.TableInputType` arm in `classifyInputFieldOnArg`.
- `TypeBuilder.java:209-210`: the type-pivot in the synthesis pass.

Each consumer asks "does the input have a table?" by switching on the permit identity. None of them care about the backing-class kind on the unbound side.

## What's to be: recursive input-usage model

The pivot separates three concerns onto three records: the *SDL declaration* (`Input`, consumer-independent), the *use-site application* (`InputUsage`, the recursive node that captures an `Input` used at a particular site, with whatever table and backing class apply there), and the *arg-site occurrence* (`InputTypeArg`, a thin wrapper that pairs a root `InputUsage` with arg-site coordinates). `InputUsage` is the load-bearing carrier; it is recursive so nested-input fields hold their own `InputUsage` rather than re-deriving the use-site context from the parent. The name does not assume any axis is resolved: a use site may have neither a table nor a backing class (both `Optional`), and the structure carries the SDL fact (`input`) plus whatever was resolvable at that site.

```java
sealed interface Input extends GraphitronType, EmitsPerTypeFile permits Input.Of {

    ValidationShape validationShape();          // R94, always present, SDL-derived
    GraphQLInputObjectType schemaType();
    List<InputFieldDecl> fields();           // SDL field declarations, pre-binding
}

record Of(
    String name,
    SourceLocation location,
    GraphQLInputObjectType schemaType,
    ValidationShape validationShape,
    List<InputFieldDecl> fields
) implements Input {
    public Of {
        fields = List.copyOf(fields);
    }
}

sealed interface BackingClass {
    String fqClassName();
}
record Pojo(String fqClassName) implements BackingClass {}
record JavaRecord(String fqClassName) implements BackingClass {}
record JooqTableRecord(String fqClassName, TableRef table) implements BackingClass {}

/**
 * The SDL field declaration on an {@link Input}, before any table binding has been
 * applied. Carries the field's name, type, source location, and directives. Distinct
 * from {@link InputField}: that family is the *classified* shape (column-bound or
 * unbound), produced when an {@link Input} is wrapped in an {@link InputUsage} for
 * a particular use site.
 */
record InputFieldDecl(GraphQLInputObjectField rawField) {
    public String name()              { return rawField.getName(); }
    public GraphQLInputType type()    { return rawField.getType(); }
    public SourceLocation location()  { return rawField.getDefinition().getSourceLocation(); }
    // The classifier reads each field's applied directives at InputUsage construction;
    // @field(name:) is the column-name override read for column resolution. The wrapper
    // does not enumerate one accessor per directive; the classifier body owns the
    // directive vocabulary.
}

/**
 * An {@link Input} used at a particular site. {@code InputUsage} is the recursive
 * carrier of the use-site state: it holds the SDL declaration (via {@code input}),
 * the {@code backingClass} resolved for this site (when one applies), the effective
 * {@code table} for column resolution at this site (when one applies), and the
 * classified field list produced for that pair. {@link InputField.NestingField} arms
 * in {@code classifiedFields} hold a child {@code InputUsage}, so the use-site
 * context is explicit at every tree node rather than inherited implicitly from a
 * parent. Both {@code backingClass} and {@code table} are {@link Optional}: the
 * structure names the use, not the binding, and a use site may have neither.
 *
 * <p>The same {@link Input} can produce many {@code InputUsage}s — one per arg-site
 * occurrence, plus one per nested position transitively. {@code InputUsage} equality
 * is structural; identity is not load-bearing.
 *
 * <p>The single producer is the classifier (visitor in Phase 3 onward); consumers
 * read {@code InputUsage} without rebuilding it.
 */
record InputUsage(
    Input input,
    Optional<BackingClass> backingClass,
    Optional<TableRef> table,
    List<InputField> classifiedFields
) {
    public InputUsage {
        classifiedFields = List.copyOf(classifiedFields);
        // Cross-axis invariant: when the backing class supplies a table (JooqTableRecord)
        // and the use site also asserts a table, they must agree. The classifier is the
        // producer; LoadBearingGuaranteeAuditTest pins consumers via
        // `input-usage.backing-table-aligns-with-effective-table`.
        if (backingClass.orElse(null) instanceof BackingClass.JooqTableRecord jtr
            && table.isPresent() && !table.get().equals(jtr.table())) {
            throw new IllegalStateException(
                "InputUsage effective table disagrees with backing-class table");
        }
        // Classified fields require an effective table: when table is empty, the classifier
        // produces an empty classified list. Holds by the producer's construction; the check
        // here pins the carrier-side contract.
        if (table.isEmpty() && !classifiedFields.isEmpty()) {
            throw new IllegalStateException(
                "InputUsage with no effective table cannot carry classified fields");
        }
    }
}
```

The `Input` record carries what's true of the SDL declaration in isolation: name, location, the assembled-schema form, the SDL-derived per-input-type emit shape (R94's `ValidationShape`), and the pre-binding SDL field declarations. `Input` carries no backing-class slot: the active backing class is a use-site fact that lives on `InputUsage`, resolved by the visitor at construction time. At the root, the resolution is: (1) when the use site has an effective table, the backing class is `JooqTableRecord(<TableRecord-for-table>, table)` — the jOOQ-generated `TableRecord` is the natural carrier whether the table came from a `@table`-returning consumer or from a service-method param typed as a `TableRecord`; (2) when there is no table binding but a user service method consumes the input, the backing class is the param's runtime-shape arm (`Pojo` or `JavaRecord`); (3) otherwise, empty. A service-method param typed as a jOOQ `Record` subclass that is *not* a `TableRecord` (UDT records, embeddable records, custom Record subclasses) is rejected at this step with `Rejection.AuthorError("backing class %s is a jOOQ Record but not a TableRecord; supported non-table backings are Java record or POJO")`; the embedded-record case will land as a follow-up (R234). For nested use sites, the backing class is derived by reflection through the parent's class. The legacy SDL hints `@record(class:)` and `@table` on input types are dropped at this pivot; see §"`@table` and `@record(class:)` on inputs are dropped" below. `BackingClass` is a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) covering the populated cells of the legacy `*InputType` cross-product. `InputFieldDecl` is the SDL declaration (name, type, location, directives) before any table binding is applied. `InputUsage` is the recursive classified-state carrier: it pairs an `Input` with a resolved `(backingClass, table)` and the classified fields produced for that pair.

`InputField.NestingField` flips from `List<InputField> fields` to `InputUsage nested` (see Phase 1 below). The recursion that was implicit in the legacy "nested fields inherit parent table" prose becomes a structural property of the model: a nested input's use-site context is whatever the parent's classifier produced for it, carried explicitly on the nested `InputUsage`.

```java
record InputTypeArg(
    String name,
    InputUsage usage,                             // the classified-state carrier, root of the recursive tree
    boolean nonNull,
    boolean list,
    Optional<ArgConditionRef> argCondition
) implements ArgumentRef {
    @Override public String typeName() { return usage.input().name(); }
    public Input input() { return usage.input(); }
    public Optional<TableRef> effectiveTable() { return usage.table(); }
    public List<InputField> classifiedFields() { return usage.classifiedFields(); }
}

// The visit-site coordinate lives on a thin wrapper, not on InputTypeArg itself, so
// the arg record cannot be constructed with a wrong coordinate at a non-visit-time
// call site (e.g. a test fixture). Consumers that need the coordinate (LSP hover,
// diagnostics) read it through the wrapper; consumers that don't read InputTypeArg
// directly.
record ArgumentOccurrence(SchemaCoordinate consumer, InputTypeArg arg) {}
```

`InputTypeArg` is the arg-site wrapper: a name, the nullability/list flags, the optional arg condition, and the root `InputUsage` that carries the classified state. The convenience accessors (`input()`, `effectiveTable()`, `classifiedFields()`) delegate to the usage carrier so call sites that previously read these slots off `InputTypeArg` keep their existing signatures. The classified state itself lives on `InputUsage`; `InputTypeArg` adds nothing per-occurrence that the usage carrier doesn't already determine.

`ArgumentOccurrence` is the thin wrapper the visitor produces at the visit site. It carries the coordinate (`Query.films` etc.) and the `InputTypeArg`. LSP hover and diagnostics consume `ArgumentOccurrence`; emitters that don't need the coordinate consume `arg()` directly. The wrapper exists so the coordinate cannot drift from the arg: a test fixture that constructs an `InputTypeArg` alone cannot lie about the coordinate, since the arg doesn't carry one.

No `ConsumerBinding` record exists. The table fact lives on `InputUsage.table()`; the consumer coordinate that drove the inference lives on the enclosing `ArgumentOccurrence`. One slot for "the table at this use site," not two halves to compose.

### `@table` and `@record(class:)` on inputs are dropped

The pivot drops two directives as binding sources on `INPUT_OBJECT`. Each is read once at classification, surfaced as a `BuildWarning` via `ctx.addWarning(...)` (the existing channel `TypeBuilder.emitDirectiveIgnoredWarnings` already uses for the legacy "shadowed by `@table`" case), and then ignored for binding purposes. Phase 6 narrows both SDL directive declarations to drop `INPUT_OBJECT` and sweeps every decorated SDL input across fixtures.

**`@table` on input.** Three table-binding sources collapse to two: backing class (`BackingClass.JooqTableRecord.table`, sourced from the consumer-derived class's reflected `TableRecord` binding) and consumer (the enclosing field's `@table` return). The realistic consequence: an SDL input decorated `@table` used at a `@table`-returning consumer keeps the same effective table via consumer-derivation; the warning surfaces the now-redundant decoration. An input decorated `@table` and used only at consumers that do *not* return a `@table` type loses its binding (with the warning explaining why). Sakila's 16 `@table`-decorated inputs all consume at `@table`-returning consumers (`Query.films(filter: FilmConditionInput!): [Film @table]` etc.); the Phase 6 sweep confirms no orphans.

**`@record(class:)` on input.** The directive's "type the deserialization target" function collapses into the three-step resolution named above (table-derived `JooqTableRecord` → service-method-param-derived arm → empty). The visitor inspects the use-site context once per `InputUsage` and picks the first applicable source. The per-`Input` emit produced by `InputRecordGenerator` is for Jakarta-validation use only, not a DTO target — graphitron's SQL emission walks `InputUsage.classifiedFields` directly without materialising the input into a Java object. The directive's secondary "skip codegen, use my class" role is dropped: graphitron never produced DTOs as a binding target, only as a Jakarta-validation type, and the validation type is consistently emitted from the SDL shape.

The realistic consequence: a `@record(class:)` annotation on a table-bound input was redundant in the new model anyway (the table-derived `JooqTableRecord` arm wins regardless), so removing the directive is a no-op for those sites. A `@record(class:)` annotation on a non-table-bound input either matches the user's service-method param type (in which case the directive is redundant — the param type wins) or contradicts it (in which case the directive was masking an inconsistency that the visitor now surfaces as `Rejection.AuthorError` via the resolver-param-class-shape-matches-SDL check). The fixture sweep in Phase 6 confirms each removal lands cleanly.

### Directives the new model carries

The pivot affects how `@table` and `@record` resolve into the model; other input-related directives stay on their existing resolution paths. Mapping for the reviewer:

| Directive | Pre-pivot home | Post-pivot home |
|---|---|---|
| `@record(class: ...)` on input type | identity of the four `*InputType` permits (carried as the SDL hint that selected the permit) | **ignored at classification**; the visitor emits a `BuildWarning` at the directive's `SourceLocation` ("@record(class:) on input types is deprecated; the backing class at each use site is derived from the binding's table (when present) or from the consumer's resolver-method parameter type"). The root `InputUsage.backingClass()` resolves as: table-bound → `JooqTableRecord(<TableRecord-for-table>, table)`; non-table-bound with a service-method consumer → param-type arm; otherwise empty. Nested use sites derive via reflection through the parent's class. The per-`Input` Jakarta-validation type is emitted from the SDL shape regardless |
| `@table` on input type | `TableInputType.table` (legacy) / `JooqTableRecordInputType.table` (reflected) | **ignored at classification**; the visitor emits a `BuildWarning` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return or the backing class"). Binding resolves from `BackingClass.JooqTableRecord.table` (consumer-derived class's reflected binding) or the enclosing field's `@table` return (consumer-derived) and lands on `InputUsage.table()`; invariant #2 pins the two-source agreement |
| `@condition` on input *type* | read from the assembled `GraphQLInputObjectType` via `TypeBuilder.isUsedWithOverrideCondition` | unchanged: read from `Input.schemaType()` (the assembled-schema slot is preserved) |
| `@inputBean` / `@enumMap` / `@field(name:)` on input *fields* | read by `InputBeanResolver`, `EnumMappingResolver` from the assembled-schema field | unchanged: those resolvers continue to consult the assembled-schema; R200 / R195 / R98 stay scoped where they are |

Anything not in this table is read from `Input.schemaType()` exactly as today, since the pivot preserves the assembled-schema reference on the model record.

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | `Input` (no backing-class slot); three arms move onto `BackingClass` (`Pojo`, `JavaRecord`, `JooqTableRecord`) with the arm chosen by the consumer-derived class's runtime shape; the active backing class at a use site lives on `InputUsage.backingClass()`. The legacy `JooqRecordInputType` arm (jOOQ `Record<?>` non-table subclass) is rejected at classification with `Rejection.AuthorError`; the embeddable-record and UDT-record cases land as R234 |
| `GraphitronType.TableInputType` sibling root | `Input` + `InputUsage.table()` populated when the enclosing field's `@table` return drives the inference at the visit site |
| `JooqTableRecordInputType.table` slot, `TableInputType.table` slot | Both collapse to `InputUsage.table` (the use-site fact) + `InputUsage.backingClass = JooqTableRecord(<TableRecord-for-table>, table)`. The legacy distinction (was the table sourced from `@record(class:)` reflection or from `@table`/consumer chain) erases at the use-site model: both legacy carriers produce a `JooqTableRecord`-backed `InputUsage` for the same table. The legacy directive-driven path is dropped, surfacing as `BuildWarning`s for the duration of Phase 6's sweep |
| `TableInputType.inputFields` (stored, eager-classified) | `Input.fields()` carries the SDL declarations (`List<InputFieldDecl>`); `InputUsage.classifiedFields()` carries the classified list produced by the visitor at construction. Slot retires. See "Why InputUsage is the classified-state carrier" below. |
| `HasValidationShape` capability marker (5 permits) | direct slot on `Input` |
| `ArgumentRef.InputTypeArg.TableInputArg` | merged into `InputTypeArg` (with `usage.table().isPresent()`) |
| `ArgumentRef.InputTypeArg.PlainInputArg` | merged into `InputTypeArg` (with `usage.table().isEmpty()` and `usage.classifiedFields().isEmpty()`) |
| `TableInputArg.lookupKeyFields` / `setFields` slots (R144 DmlKind-driven partition) | retired from the model; `MutationInputResolver` derives the partition on demand from `usage.classifiedFields()` plus the consuming verb's `DmlKind`. See "What this absorbs" below. |
| 9 consumer-side discriminations on `TableInputType` (instanceof gates and switch arms) | `arg.usage().table().isPresent()` at the consumption point (via the convenience accessor `arg.effectiveTable().isPresent()`) |
| `TypeBuilder.findReturnTablesForInput` back-scan | resolved at the visit site under R166 walk |
| `InputField.NestingField.fields` (`List<InputField>`, parent-table-inherited) | `InputField.NestingField.nested` (`InputUsage`): the nested use-site context is structural rather than inherited; future R122 nested `@table` lands as `nested.table` distinct from the parent's |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes a `walkInputs` pass that visits every `InputTypeArg` and dispatches on `usage().table().isPresent()`; the per-permit-identity dispatch retires. The walk recurses into `InputField.NestingField.nested()` so nested-input validation uses the same machinery as root-level validation, rather than the parent-table-inheritance side path it walks today.

## Visitor-driven classification (R166 absorption for inputs)

Today's classifier is iteration-driven: `TypeBuilder` walks `schema.getAllTypesAsList()` once and classifies each `INPUT_OBJECT` in isolation. The table-binding decision is made per-input-type via the `findReturnTablesForInput` back-scan, which scans every schema field asking "who returns a `@table` type and consumes this input?" The decision is the wrong shape for two reasons: it's O(N) per input rather than O(1) at the use site, and it collapses divergent consumers (the `tables.size() > 1` arm at `TypeBuilder.java:1022` demotes the input to `InputType` even when each consumer has a coherent local table).

Under the pivot, classification is driven by `graphql.schema.SchemaTraverser.depthFirst(visitor, roots)` seeded with the consumer-reachable surface (root operation types). The visitor visits each field with its arguments in scope: when it reaches `Query.films(filter: FilmConditionInput!)`, the *visiting context* already carries the consumer's return type (`[Film @table]`). The root `InputUsage` for `filter` is constructed at the visit site with `table` populated from the enclosing field's `@table` return when applicable. Nested input fields recurse: the classifier constructs a child `InputUsage` for each nested-typed slot, carrying the parent's effective table forward and deriving the nested backing class from the parent's class. No back-scan.

Five consequences fall out structurally:

1. **Consumer-derived table resolution is local.** With `@table` on input dropped, consumer-derivation is the *only* per-arg binding source (the other source, the input's backing class, is consumer-independent and lives on `BackingClass.JooqTableRecord`). R97 Phase 2 becomes the default, not a flag flip; the consumer coordinate that drove the inference is carried on the enclosing `ArgumentOccurrence` (for the root) and is recoverable from the tree-walk for nested nodes. R97's Phase 1 (`argMapping` grouping) remains as follow-up; R97's Phase 3 (narrow directive scope + fixture sweep) lands as this item's Phase 6.
2. **Cross-consumer reuse works correctly.** `FilmConditionInput` used by two queries returning different tables produces two `InputTypeArg` carriers, each with its own root `InputUsage` (and its own `table`). The `tables.size() > 1` collapse retires.
3. **R213 dissolves.** Plain-input field rejections currently lose source location because the classifier is detached from the use site. The visitor sits *at* the input field when constructing the `InputUsage`; the `SourceLocation` it carries is the input field, not the consumer.
4. **Reachability falls out.** An SDL input never referenced from any reachable consumer produces no `InputTypeArg` (and no `InputUsage`). The `Input` record still exists for the per-type emit, but `InputUsage` construction is gated on visit. Subset of R166 Phase 1's reachability discipline. **Federation seed set:** the visitor seeds from operation roots only, not from `_Entity`-bearing types. Federation entity-fetch inputs decode at the `representations: [_Any!]!` wire boundary (handled by `EntityFetcherDispatch.resolveByReps` outside the model) and intentionally do not flow through the per-arg classification path. An SDL input used *only* as an argument to an entity-fetch service method without appearing on a reachable operation field is therefore unclassified by this walk; today's `schema.types()`-driven classifier produces the same effective outcome (the input has no classified consumer), but the spec names the decision explicitly so a future reader does not treat it as a regression.
5. **R209 re-homes.** The typed-rejection lift `FieldRegistry.classifyInput` defers today (because `InputFieldResolution.Unresolved` doesn't carry a typed `Rejection` payload) becomes structurally trivial: column-miss is detected at the `InputUsage` construction site, where the `TableRef` and the candidate-column list are both in scope. The `RejectionKind.AUTHOR_ERROR` default arm at `FieldRegistry.java:108-110` retires; typed `Rejection.AuthorError.UnknownName` with Levenshtein hint is the only path.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves: capability declarations land on `Input` directly; the parent-root fix becomes moot. |
| R97 (deprecate `@table` on input types) | Phase 2 falls out structurally; Phase 3's runtime ignore + deprecation warning lands at Phase 3 of this item; Phase 3's SDL-narrowing + fixture sweep lands at this item's Phase 6. `argMapping` grouping (R97 Phase 1) remains as separate follow-up. |
| R213 (rejections at consumer field) | Visit-time source location. |
| R209 (FieldRegistry classify-input trace) | Typed rejection lift becomes trivial at the per-arg visit site. |
| R166 Phase 2 (`InputTypeGenerator` under visitor) | Subset: the input-side slice of R166's broader visitor-driven emission. |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves: `GraphitronSchemaValidator.validateTableInputType` becomes `validateInputArg` (Phase 4) that walks every `InputTypeArg.classifiedFields` uniformly. The `TableInputType.inputFields()` vs `PlainInputArg.fields()` split that motivated R221 retires under the pivot's unified `InputTypeArg`. |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker on input fields) | Two reversals. (1) The partition leaves the classifier-side model and moves to the consumer. `arg.usage().classifiedFields()` is the single sealed list (produced by the classifier at `InputUsage` construction); `MutationInputResolver` derives the WHERE-vs-SET partition at consumption from that list plus the verb's `DmlKind`. (2) The `@value` directive is dropped. The partition mechanism becomes PK-derivation against the target table's primary-key column set (a fact the jOOQ catalog already carries); no per-input-field marker is needed. R144's cardinality-safety surface (`multiRow`, PK-coverage check) survives unchanged in shape. See the R144 rationale paragraph below. |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164**: this item proves out the dimensional pattern on the smaller input surface. R164 then applies the pattern to the larger field hierarchy with the precedent already in tree.
- **R98** (multi-source input validation): once `HasValidationShape` becomes a direct slot on `Input`, attaching `ConstraintSet` to it is a one-site change. Lands as a follow-up.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): these are about *naming binding* between SDL fields and Java members on the backing class. The pivot doesn't change naming resolution; both items stay in scope on `InputBeanResolver`.
- **R226** (classification dimensional pivot: diagnostics off the model): forward-compatible. R222's `ctx.addWarning(...)` call shape and its `Rejection.AuthorError` emissions are R226-compatible by design: R222 leads its classification-failure language with "a `Rejection` is signalled through the classifier-output channel," treating today's `UnclassifiedArg` carrier as the surfacing rather than the contract. R226's eventual rewrite will retire the `Unclassified*` carriers in favour of `Diagnostic` events on a sink, touching two R222-introduced producers (the `@table`-on-input and `@record(class:)`-on-input deprecations in Phase 3), three R222 doc sites (invariant #2, Phase 3 description, the disagreement test), and the `UnclassifiedArg` constructions in R222's Phase 3 implementation. No structural front-load of R226 in R222: the post-R222 rework is mechanical and R226's own scope is comparable to R164's, which the budget for R222 cannot absorb.

### Why R144's partition reverses

R144 committed the WHERE-vs-SET partition at classify time, on the carrier itself: `TableInputArg.lookupKeyFields()` and `TableInputArg.setFields()` are populated by a DmlKind-aware factory (`ArgumentRef.java:284-312`), and `MutationInputResolver` reads the partition as fact. Two problems show up under the pivot.

First, the partition is a property of the *consumer*, not of the *arg*: the same input used by `Mutation.updateFilm` (UPDATE) and `Mutation.deleteFilm` (DELETE) belongs in different partitions at each site. Today's model handles this because the partition lives on `TableInputArg` (per-arg-occurrence), which masks the underlying mistake. The pivot's dimensional principle ("one slot per dimension; the consumer's concern is on the consumer") makes the shape visible: WHERE-vs-SET is the mutation emitter's concern, not the classifier's.

Second, the partition is *denormalized*: `InputField.LookupKeyField` and `InputField.SetField` are intersection-marker interfaces on the same concrete `ColumnField`/`CompositeColumnField`/etc., which means the typed lists store the same fields, partitioned. R144's factory does the partitioning; consumers read it. Reverting the commitment is cheap (the factory logic moves into `MutationInputResolver`, the only consumer of `lookupKeyFields()` / `setFields()`), and it eliminates the intersection-marker leak at the model-shape level.

The intersection-marker pattern on `InputField` (ColumnField IS-A both LookupKeyField and SetField) stays in place under R222 since making those disjoint is a separate model refactor; this item only moves *where* the partition decision is made, not *how* the underlying types express eligibility.

### Why R144's `@value` directive retires

Once the partition lives on the consumer (above), the marker that fed it surfaces as the same disease one layer down. `@value` on an input field is rejected on DELETE / INSERT / UPSERT, admitted on UPDATE, and mutually exclusive with `@condition` on the same field. The directive's validity is a function of the consumer's verb, which is exactly the "if two consumers evaluate the same predicate over a model field, the branch belongs in the model" smell the principles list names. A per-input-field directive encoding per-consumer-verb semantics is dimensionally inverted.

The dimensionally honest mechanism, once the catalog is in scope: the WHERE-vs-SET partition on UPDATE is `(arg.classifiedFields(), targetTable.primaryKey())` derivable; PK-column carriers go to WHERE, the rest go to SET. The jOOQ catalog already carries the PK column set; no per-input-field marker is needed to recover the partition. R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) is orthogonal to the directive and survives unchanged in shape: the trigger is the catalog's PK column set, not the `@value` complement.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical with or without the directive. The fixture sweep that drops them is a one-line edit per site. `docs/manual/reference/directives/value.adoc` retires. Two `@LoadBearingClassifierCheck` keys swap on the same `MutationInputResolver` site: `mutation-input.update-set-fields-equal-value-marked` retires; `mutation-input.update-partition-by-pk-membership` replaces it (PK-column carriers in `classifiedFields` go to WHERE, the rest to SET, on UPDATE; the existing PK-coverage check key stays).

### Why InputUsage is the classified-state carrier

Two earlier drafts wrestled with where the classified field list lives. Draft A stored `List<InputField> classifiedFields` directly on `InputTypeArg`; Draft B demoted it to a derived view computed by `Input.classifyAgainst(table)`. Draft A denormalises (the field list is a function of inputs, not an independent fact); Draft B hides the recursion (nested-input classification re-enters `Input.classifyAgainst` from inside the legacy `InputField.NestingField` carrier, which then has no slot to hold its own use-site context).

`InputUsage` resolves both. It is the carrier of `(input, backingClass, table, classifiedFields)` for one use site. The classified fields are stored on it because they are produced once at construction (by the visitor in Phase 3 onward) and read many times by consumers; recomputing them on every read is the alternative the legacy eager-classification model already chose, and the recursion through nested inputs makes the recompute non-trivial. Storing them on the recursive node makes the classified state a structural property of the model rather than an accessor.

Each `InputUsage` is produced exactly once by the classifier: the root by the visitor at the arg-visit site, the nested by the classifier when it walks an `InputField.NestingField`-shaped declaration. The carrier-side invariants in the compact constructor pin the table-vs-backing-table agreement and the table-vs-classified-fields agreement; the producer-side commitment that `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (key `per-type-emit-ignores-binding-context`) keeps the per-type Jakarta-validation emit independent of any use site.

Per-arg consumers (`MutationInputResolver`'s R144-relocated partition logic, `GraphitronSchemaValidator.validateInputArg`, `EnumMappingResolver.buildLookupBindings`) read `arg.usage().classifiedFields()` directly; consumers that descend into nested inputs read `nestedUsage.classifiedFields()`. The single classified-state shape works for both depths. The cost is one classified-field list per `InputUsage` (one per root use site plus one per nested position transitively); each is a lookup per SDL field against the table's column set, typically low tens. If profiling shows a hotspot, identical `InputUsage`s (same `input`, same `backingClass`, same `table`) can be deduplicated by a side cache keyed on that triple; the spec does not bake the cache in.

### Recursion through nested inputs

The classifier constructs an `InputUsage` for each nested input field structurally: `InputField.NestingField.nested` is a child `InputUsage`, not a `List<InputField>` that re-inherits parent context implicitly. The use-site context (table, backing class) is explicit at every tree node, and the recursion is visible in the type system rather than hidden in a method body.

**GraphQL allows input cycles; graphitron does not.** Per the GraphQL spec (October 2021 §3.6.1), an input object's references can recurse, including indirectly, *provided at least one field in the cycle is nullable* (otherwise no finite input value is constructible). `graphql-java` enforces the nullable-link rule at schema-build time, so `TreeFilter { children: [TreeFilter] }` parses but `Cycle { c: Cycle! }` does not. Graphitron is stricter: it rejects *all* cycles in nested-input recursion, because the classifier enumerates nested fields at classification time to produce fixed SQL projections, and arbitrary-depth nesting has no fixed lowering. This stance is graphitron-specific and pre-dates R222; the pivot preserves it.

**Recursion mechanic.** The classifier carries a `ClassifyContext` (`ClassifyContext.java`, pre-existing) holding a `Set<String> expandingTypes`. The entry point is `Classifier.classify(input, table, backingClass)` which returns an `InputUsage`; internally it walks `input.fields()` once, classifying each `InputFieldDecl` and pushing the input's type name onto the visited set before recursing into nested-input fields. When the recursive call sees a type name already in the set, the nested slot resolves to `UnboundField` with the "circular input type reference detected while expanding 'X'" reason (the same `Unresolved` reason today's `BuildContext.classifyInputFieldInternal` at `BuildContext.java:1640-1664` emits, surfaced through R215's deferred-rejection rail). The visited-set mechanic transfers from the legacy classifier unchanged; the change is where the result lands (on an `InputUsage` tree rather than a flat `List<InputField>` that hides depth).

**Per-field classification rules.** For each `InputFieldDecl` in `input.fields()`:

- **Scalar / enum type, resolves to a column on the use-site table:** produces `ColumnField` / `CompositeColumnField` or their `Reference` variants (the existing arms).
- **Scalar / enum type, no column resolves:** produces `UnboundField` per R215's admit set.
- **Nested input type without its own backing class:** recursive `Classifier.classify` call with the parent's table forwarded and the parent-derived nested backing class (derived by reflection from the parent's `BackingClass` through the field's name; `Optional.empty()` when the parent has no backing class). The result wraps in `InputField.NestingField(parentTypeName, name, location, nonNull, list, nestedUsage, condition)`. When `ctx.isExpanding(nestedTypeName)` returns true, the recursive call short-circuits and the slot resolves to `UnboundField` with the circular-reference reason.
- **Nested input type *with* its own `BackingClass.JooqTableRecord` binding:** the future R122 (`compound-entity-mutations`) territory — the nested `InputUsage` would carry its own `table` distinct from the parent's. Out of R222 scope: today's classifier produces `Unresolved("no column 'X' reachable")` for this case at the parent's level, which lifts to `UnboundField` under R215; the pivot inherits that outcome verbatim. The structural slot is now in place (the nested `InputUsage` can hold its own table) so R122 is a body change rather than a model change.

**Cycle detection ordering vs the deduplication escape hatch.** The Risk section names structural-key deduplication (cache keyed on `(Input, BackingClass, TableRef)`) as the profile-driven optimisation. If a cache is introduced, the lookup must happen *before* the visited-set check: a cached `InputUsage` for an Input not currently expanding is safe to return, but probing the cache after the visited-set check would silently miss any recursive call that legitimately resolves (a nested input is allowed to reference its parent indirectly through a separately-classified Input, just not into the *currently expanding* type set). The ordering is: cache → visited-set check → classify → cache write. The spec calls this out so a future implementer adding the cache does not invert it.

**Test surface.** Today's coverage in `GraphitronSchemaBuilderTest.java` exercises both the direct self-referential case (line 1358, "self-referential nested type → UnclassifiedField with circular-reference message") and the indirect chain (line 5812, "circular A → B → A chain rejects loudly as UnclassifiedField; build terminates"). Both assert on the classifier's "circular input type reference detected" / "circular type reference" reason strings. Under the pivot, the producer moves from `BuildContext.classifyInputField` to the new `Classifier.classify`; the reason string and the surfacing path through `UnboundField` (R215) stay verbatim. Both tests carry over with no behavioural change.

## Cross-axis invariants

The dimensional split admits states the old model could not. Three load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key. Producer: the classifier (the visitor under Phase 3 onward; the adapter under Phases 1-2). Consumers: the per-arg emitters plus the per-type generators as noted.

1. **`input-usage.empty-table-implies-empty-classified-fields`**: `InputUsage.table().isEmpty() ⇒ InputUsage.classifiedFields().isEmpty()`. Pinned in `InputUsage`'s compact constructor (the producer cannot construct an `InputUsage` that violates it). The `@LoadBearingClassifierCheck` annotation lands on the classifier (`Classifier.classify`); consumers (`MutationInputResolver`, `GraphitronSchemaValidator.validateInputArg`, `EnumMappingResolver.buildLookupBindings`, the four `CatalogBuilder` sites) read via `arg.usage().classifiedFields()` (or the `arg.classifiedFields()` convenience accessor) and the audit walker pins the chain through that single producer. The reverse direction is descriptive: when a table is present, the result carries `InputField` entries the per-field classifier produced against that table (the R215 admit set: column-bound carriers plus `UnboundField` for the override-cascade case). The list may be empty when an Input has no SDL fields (parser-level rare but reachable).
2. **`input-usage.backing-table-aligns-with-effective-table`**: when `InputUsage.backingClass()` is `Some(JooqTableRecord(_, table_backing))` and `InputUsage.table()` is `Some(table_effective)`, `table_backing == table_effective`. The producer enforces this; the compact constructor rejects violations. Mostly automatic by construction: when the use site has an effective table, the visitor sets `backingClass = JooqTableRecord(<TableRecord-for-table>, table)` from the same table fact, so agreement holds by source. The state space the invariant guards is the disagreement case — a service-method param typed as `JooqTableRecord` for table T₁ at a use site whose consumer-derived table is T₂ — which the visitor catches *before* `InputUsage` construction and surfaces as `Rejection.AuthorError` through the standard classifier-output channel (today, an `UnclassifiedArg` carrier with the `Rejection` payload). The compact-constructor check is the safety net; the legacy model made this state structurally unreachable (a `JooqTableRecordInputType` carried one `TableRef` and nothing else could override it). Consumers: every DML emitter that reads `arg.usage().table()` (currently `MutationInputResolver`, the four `CatalogBuilder` sites).
3. **`per-type-emit-ignores-binding-context`**: `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never `InputUsage`); per-arg consumers read only `InputUsage` (never bypass to `Input` to redo classification work). The reachability degenerate-state (an `Input` with no `InputUsage` occurrences emits per-type-for-Jakarta-validation but has no classified consumption) is consistent by construction once this key holds. Pinned with `@DependsOnClassifierCheck` on the two per-type generator entry points.

The legacy `input.record-shape-derived-from-backing` key retires entirely with the `Input.declaredBackingClass()` slot. `ValidationShape` is purely SDL-derived now (the emit produces a Jakarta-validation type from the SDL declaration), and the active backing class is per-site on `InputUsage`; there is no relationship between the two to pin. A separate validator-side check (the resolver-param-class-shape-matches-SDL check) lands as a Phase 3 visitor concern: when reflecting a resolver-method parameter class to build the root `InputUsage.backingClass`, the visitor must verify the class's accessible members cover the SDL input's field set, raising `Rejection.AuthorError` on mismatch. That check is producer-side (in the visitor) rather than a model invariant on `InputUsage`.

Invariants #1 and #2 hold by construction in `InputUsage`'s compact constructor — they cannot be bypassed by direct construction. Invariant #3 is a producer-side commitment; the adapter shims in Phases 1-2 must satisfy it as part of their "lossless round-trip" criterion, otherwise consumers that construct the new types directly bypass the boundary. The audit walker (`LoadBearingGuaranteeAuditTest`) is the safety net.

## Phasing

Six phases, each independently shippable and individually reversible.

### Phase 1: introduce `Input`, `BackingClass`, `InputUsage` alongside existing permits

- Add `sealed interface Input`, the `BackingClass` family, `InputFieldDecl`, and the `InputUsage` record to `model/`.
- **Rename R94's `InputRecordShape` → `ValidationShape`** (and the slot `recordShape` → `validationShape` on every input variant). The old name carried framing from when graphitron's emitted record was a deserialization target; with the emit narrowed to Jakarta validation, "record shape" reads like a generic DTO descriptor and invites future drift back into "the input's DTO type" semantics. The capability marker renames from `HasInputRecordShape` → `HasValidationShape` in lockstep (it retires entirely in Phase 5 when the slot moves to `Input` directly, but the rename happens here so the lockstep is exact). Mechanical change touching the type declaration, the marker, and every legacy `*InputType` permit; no behavior change.
- Flip `InputField.NestingField` from `List<InputField> fields` to `InputUsage nested`. This is a body-only model change: the `NestingField` record stays in tree under the same name, with its slot type changed. Callers in `GraphitronSchemaValidator.validateInputNestingField`, `EnumMappingResolver` (line 333, ignores), `MutationInputResolver.java:529`, and `ContextArgumentClassifier.java:139` migrate to `nf.nested().classifiedFields()` for the field walk; nestingField's parent-table-inheritance semantics carry over (the parent's table is what gets forwarded into the nested `InputUsage` at construction).
- Implement an adapter `Classifier.adaptLegacy(GraphitronType.InputType)` that constructs an `Input` plus, given a use-site `(BackingClass, TableRef)`, an `InputUsage`. The `Input` adapter is lossless on the SDL-side facts (name, location, validationShape, fields); the legacy `@record(class:)` directive that today drove the permit choice is read by the adapter only to seed the Phase 1-2 `InputUsage.backingClass` (preserving binding behavior until Phase 3 ships the visitor's resolver-param-derived path). Phase 3 emits the `BuildWarning` and retires the SDL-side read. The `InputUsage` adapter is lossless against the legacy classified-field shape (the body lifts `BuildContext.classifyInputFieldInternal` onto the new classifier entry point).
- `Input.fields()` is populated from `inputType.getFieldDefinitions()` at `Input` construction: one `InputFieldDecl` per SDL field.
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- No consumer migrates yet; the adapter is the bridge.

Acceptance: every classified input today produces an equivalent `Input` via the adapter (lossless on the SDL-side facts) and an equivalent `InputUsage` for every use site that legacy classification today produces a classified field list for. The adapter pair (Phase 1 plus Phase 2) is lossless against the legacy classification *modulo the `@table`-on-input cases*: directive-derived bindings surface as `BuildWarning` plus consumer-derivation when applicable; the directive-only-at-non-`@table`-consumer case becomes unbound (and the Phase 6 fixture sweep is where any such case is corrected). No other behaviour change.

### Phase 2: introduce `InputTypeArg` carrying `InputUsage`

- Add the unified `InputTypeArg` record carrying a root `InputUsage` plus the convenience accessors (`input()`, `effectiveTable()`, `classifiedFields()`) that delegate to it.
- Adapter from existing `TableInputArg` / `PlainInputArg` → new `InputTypeArg`. The adapter constructs the root `InputUsage` from the legacy classification context: for `TableInputArg` produced today via `findReturnTablesForInput`'s consumer back-scan, the consumer-derived `TableRef` and the input's adapted backing class compose into the `InputUsage`'s `(backingClass, table)`; for `TableInputArg` produced from the `@table` directive on the input itself, the adapter emits a `BuildWarning` (the Phase 3 visitor inherits this contract) and falls back to consumer-derivation when the consumer is also `@table`-bound. Legacy `TableInputArg.inputFields` translates 1:1 onto the new `InputUsage.classifiedFields` slot.
- `InputUsage`'s compact-constructor invariants (`input-usage.empty-table-implies-empty-classified-fields`, `input-usage.backing-table-aligns-with-effective-table`) hold for every adapter-produced instance; the adapter is the producer for Phases 1-2.
- New record sits alongside the existing arg types; no consumer migrates yet.

Acceptance: every classified arg today produces an equivalent `InputTypeArg` (and a structurally-equivalent root `InputUsage`) via the adapter, *modulo* the `@table`-on-input directive translation called out in Phase 1's acceptance; both compact-constructor invariants hold for every adapter-produced instance; `arg.usage().classifiedFields()` produces a list equal-by-value to the legacy `TableInputArg.inputFields()` for the same `(input, backingClass, table)`.

### Phase 3: visitor-driven per-arg classification

- Add `GraphQLSchemaVisitor`-based walker seeded from root operations.
- At each `argument` visit on an `INPUT_OBJECT`-typed arg, produce the `ArgumentOccurrence` with an `InputTypeArg` carrying a root `InputUsage`. The root's `table` is populated from the enclosing field's `@table` return when applicable. The root's `backingClass` resolves in this order: (1) if `table` is present, `backingClass = BackingClass.JooqTableRecord(<TableRecord-class-for-table>, table)` — the jOOQ `TableRecord` is the natural binding carrier for any table-bound site; (2) else if a user service method consumes the visited field, `backingClass` is the param-type-derived arm (`Pojo` or `JavaRecord`) classified by the class's runtime shape; a param typed as a jOOQ `Record` subclass that is *not* a `TableRecord` surfaces `Rejection.AuthorError("backing class %s is a jOOQ Record but not a TableRecord; supported non-table backings are Java record or POJO")` and the visitor does not construct an `InputUsage` for that arg; (3) else, empty (graphitron's SQL emission walks `classifiedFields` directly, no DTO target needed). When a service-method param is present and the use site is table-bound, the visitor verifies the param is itself a `JooqTableRecord` for the same table; mismatch surfaces as `Rejection.AuthorError` (invariant #2's pre-construction check). The visitor recurses into each nested-input field declaration in `Input.fields()`: it constructs a child `InputUsage` applying the same three-step resolution against the nested context — parent's table forwarded into step (1), parent-class reflection through the field's name into step (2). The recursion is structural: `InputField.NestingField.nested` holds the child `InputUsage` directly.
- **`@table` and `@record(class:)` on input are dropped at this phase.** The visitor reads each directive once at the input's classification site, emits a `BuildWarning` via `ctx.addWarning(...)` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return or the backing class" / "@record(class:) on input types is deprecated; the backing class is derived from the consumer's resolver-method parameter type, or from reflection through the parent's class for nested inputs"), and otherwise ignores both. Neither directive contributes to `InputUsage.table()` or `InputUsage.backingClass()`. The companion fixture sweeps that drop both directives across SDL fixtures land at Phase 6.
- Retire `TypeBuilder.findReturnTablesForInput`. Per-input-type table classification disappears; per-`InputUsage` construction replaces it.
- **Annotation continuity:** migrate R215's `input-field.unbound-implies-no-column` `@LoadBearingClassifierCheck` key from its legacy producer site at `BuildContext.classifyInputFieldInternal` (`BuildContext.java:1558`) to the new `Classifier.classify` body (the new home for the column-binding decision). The second R215 key, `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`, lives on the mutation-time admission check at `MutationInputResolver.java:314` and stays put: it pins the verb-admission rule, not the column-binding decision, and its producer site is not affected by the Phase 3 classifier move. The legacy `input.record-shape-derived-from-backing` key retires entirely (its premise — that the Input declaration carries a backing class — disappears with `@record(class:)`-on-input). Run `LoadBearingGuaranteeAuditTest` before and after to confirm no orphaned consumers and no stale-producer references. The three keys introduced under Cross-axis invariants land on their named producer sites: `input-usage.empty-table-implies-empty-classified-fields` and `input-usage.backing-table-aligns-with-effective-table` on `InputUsage`'s compact constructor; `per-type-emit-ignores-binding-context` on the two per-type generator entry points.
- Wire R97 Phase 2's consumer-derived inference here. Test: cross-consumer divergence fixture (one input used by two consumers with different tables) classifies successfully per-arg.
- **Anchor one consumer in the same phase** so the visitor's load-bearing producer-consumer chain is exercised before the phase boundary. The chosen anchor is `EnumMappingResolver.buildLookupBindings`: smallest signature lift in the Phase 4 list, no behavioural cousins on the same surface (unlike `MutationInputResolver`'s R215-adjacent admission rules), and the audit walker has something concrete to anchor `input-usage.backing-table-aligns-with-effective-table` against. Without an in-phase consumer, the audit detects neither producer drift nor missing producers on the new keys; with one, the chain is live from the day Phase 3 ships.

  **Bridge shape.** The legacy method is not rewritten in Phase 3; it gains a sibling overload. The two legacy callers (`MutationInputResolver.java:433`, `FieldBuilder.java:974`) keep their existing `(GraphitronType.TableInputType, ...)` call site and stay on the legacy walk; the new visitor calls the new `(InputTypeArg, ...)` overload at the visit site, which reads `arg.usage().table()` and `arg.usage().classifiedFields()`. Both overloads delegate into a shared private body that reads `(table, classifiedFields)` so the binding logic lives in one place. Phase 4 deletes the legacy overload when its callers migrate. The pattern generalises if a future phase needs to anchor a different consumer earlier than its caller chain migrates: add an overload, point the new producer at it, keep the legacy overload until its callers migrate.

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged (each `@table`-decorated SDL input surfaces one `BuildWarning` but classifies identically via consumer-derivation). The cross-consumer divergence fixture works. `EnumMappingResolver.buildLookupBindings` reads the visitor's output, and the three new keys are pinned by `LoadBearingGuaranteeAuditTest`.

### Phase 4: migrate the remaining consumers

Move each remaining consumer off the legacy permit discrimination onto the new slots. Order chosen to keep blast radius small per PR; the R215 follow-ups deferred during R215's review fold in at the listed slots:

- `MutationInputResolver`: reads `arg.usage().table()` directly and `arg.usage().classifiedFields()` for the per-arg list. **R144 partition relocated + `@value` retired:** the DmlKind-aware WHERE-vs-SET partition logic moves from `ArgumentRef.java:284-312` (the legacy `TableInputArg` factory) into this resolver, operating on the result of `arg.usage().classifiedFields()` plus the target table's PK column set (sourced from `JooqCatalog`). The two readers of `lookupKeyFields()` / `setFields()` (`MutationInputResolver.java:543` and the cousin sites the same file already owns) consume the partition at the site that decides the verb. The `@value`-marked-name-set parameter on `TableInputArg.of` / `EnumMappingResolver.buildLookupBindings` disappears; the verb-validity arms in `MutationInputResolver` that reject `@value`-on-DELETE / `@value`-on-INSERT / `@value`+`@condition` retire with the directive. Load-bearing key swap on the same producer site: `mutation-input.update-set-fields-equal-value-marked` retires, `mutation-input.update-partition-by-pk-membership` replaces it. R144's `mutation-input.where-columns-cover-pk` key stays (the cardinality-safety check is orthogonal to the directive). **R215 follow-up:** the deferred `MutationField.{Value, Condition}` projection lands here if it has not already shipped standalone.
- `FieldBuilder.classifyInputFieldOnArg`: drops the `instanceof TableInputType` arm.
- `GraphitronSchemaValidator.validateTableInputType`: becomes `validateInputArg`, dispatches on `arg.usage().table().isPresent()`. The walk recurses through `InputField.NestingField.nested()` so nested-input validation is uniform with root validation. **R221 absorbed:** the validator now walks `arg.usage().classifiedFields()` uniformly across all `InputTypeArg`s; the `PlainInputArg.fields()` vs `TableInputType.inputFields()` split that motivated R221 has retired in Phase 2-3.
- `CatalogBuilder` four sites: each reads `arg.usage()` for use-site facts; `arg.input()` only when an SDL-side fact is needed (name, source location, record shape).
- `InputRecordGenerator`, `InputTypeGenerator`: read `Input.validationShape()` and `Input.schemaType()` directly; permit-identity dispatch retires. These two consumers do *not* touch `InputUsage`; the `per-type-emit-ignores-binding-context` invariant pins that boundary.

Compiler exhaustiveness on the sealed `Input` interface is the safety net for each migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or any of the four `*InputType` permits directly.

### Phase 5: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete the `HasValidationShape` capability marker (the slot lives on `Input` directly).
- Delete `ArgumentRef.InputTypeArg.TableInputArg` and `PlainInputArg`.
- Delete the Phase 1-2 adapter shims.

Acceptance: build green; one PR worth of deletions; nothing references the old permits.

### Phase 6 (picks up R97's residue + retires `@value` + retires `@record(class:)` on inputs)

Three per-input directives whose semantics belong elsewhere come out in one sweep: `@table` (handled by Phase 3's runtime ignore, finished here on the schema-declaration side), `@record(class:)` on input types (Phase 3 runtime ignore, finished here), and `@value` (mechanism replaced by PK-derivation in Phase 4, finished here on the directive-declaration side).

- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive. Each removal silences one `BuildWarning`; nothing else changes.
- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`. The directive declaration becomes structurally unable to land on `INPUT_OBJECT`; any consumer schema that still carries `@table` on an input fails to parse with the standard graphql-java location error.
- Closes R97.
- Migrate every `@record(class:)`-decorated SDL input type across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive. For each removed annotation, the Phase 3 visitor's three-step resolution (table-derived `JooqTableRecord` → service-method-param-derived arm → empty) recovers the same `BackingClass` arm: table-bound inputs reach step (1) regardless of the prior directive, and non-table-bound inputs with a `@record(class:)` matching the user's service-method param reach step (2) via that param. The codegen-opt-out role is dropped; the per-`Input` Jakarta-validation emit was the only graphitron-side artifact and survives unchanged in shape.
- Narrow the SDL `@record` directive's scope to exclude `INPUT_OBJECT` (it remains valid on `OBJECT`-typed declarations where it has different semantics). Any consumer schema that still carries `@record` on an input fails to parse with the standard graphql-java location error.
- Migrate every `@value`-decorated SDL input field across Sakila (`FilmUpdateInput.title`, `FilmUpdateInput.description`) and any fixture schemas to drop the directive. Both Sakila annotations sit on non-PK columns; the partition outcome is identical with or without them.
- Delete the `@value` directive declaration from `directives.graphqls`; delete `BuildContext.DIR_VALUE` / `ARG_VALUE` and the `assertDirective(ctx, DIR_VALUE)` registration in `GraphitronSchemaBuilder`. Delete `docs/manual/reference/directives/value.adoc`. After this delete, any consumer schema still carrying `@value` fails to parse with the standard graphql-java "unknown directive" error.

Can land independently after Phase 5; not a blocker for the structural pivot. The "optional" framing from earlier drafts is dropped: with the runtime ignore live from Phase 3 (for `@table` and `@record(class:)`) and the partition mechanism already PK-derived in Phase 4 (for `@value`), leaving the warnings and the now-redundant directive declarations indefinitely is its own cost.

## Dependencies and sequencing

- **R215** (`column-binding-at-classification-not-usage`, Done at `131e0df`): the `UnboundField` deferral R215 introduced is the same column-coverage relaxation the pivot generalises to "no per-input-type column classification at all." Of the two `@LoadBearingClassifierCheck` keys R215 shipped, `input-field.unbound-implies-no-column` names `BuildContext.classifyInputFieldInternal` as its producer (the column-binding decision) and moves into the visitor walk under this pivot's Phase 3; `input-field.unbound-with-override-condition-admits-on-mutation-update-delete` is produced at `MutationInputResolver` (the verb-admission decision) and is not affected by the producer-site move. `LoadBearingGuaranteeAuditTest` does not detect producer drift (only missing producers), so Phase 3 carries an explicit commitment to migrate both annotation producer-sites to the new visitor entry point and to re-run the audit per phase. With R215 in tree the precondition work is done; the annotation continuity is the only remaining build-order concern.
- **R94** (shipped): the `HasValidationShape` slot R94 lifted onto every input variant becomes a direct field on `Input`, sourced from the SDL declaration. No new prerequisite; the R94 invariant (validationShape derived from backing class) retires because `Input` no longer carries a backing class — the per-`Input` emit is purely SDL-derived now and exists for Jakarta-validation use only.
- **R166 Phase 1** (reachability slot): orthogonal. The visitor scaffolding this item lands can be reused by R166 Phase 1, or R166 Phase 1 can land standalone first and this item piggybacks. Spec-stage call.
- **R164**: this item is the *partial* proof-of-concept. What transfers cleanly: the dimensional decomposition discipline (one sealed sub-taxonomy per dimension, not one sealed sub-taxonomy of the cross product), the phased adapter approach, the consumer-migration ordering, the `@LoadBearingClassifierCheck` continuity. What does **not** transfer: the two-level split (`Input` consumer-independent / `InputTypeArg` per-occurrence) is specific to input usage; fields are properties of the SDL field declaration with no per-consumer level to lift onto. R164's authors should model on the dimensional and phasing halves, not on the two-level split.

Likely scope: 1-2 weeks of focused work, somewhat smaller than R164's 2-4 weeks because the consumer surface is narrower and the existing model is already mid-erosion (R215 did the precondition work).

## Vocabulary

- **`Input`** (the SDL declaration), **`InputUsage`** (the recursive classified-state carrier for a use site), and **`InputTypeArg`** (the arg-site wrapper). The three-level distinction is explicit in the names so readers don't conflate "the SDL input declaration" with "this input applied at this use site" with "the arg-site occurrence."
- **`InputUsage`** is the load-bearing carrier: `(Input input, Optional<BackingClass> backingClass, Optional<TableRef> table, List<InputField> classifiedFields)`. Produced by the classifier (visitor under Phase 3 onward) at every use site; consumed by every per-arg consumer. Recursive through `InputField.NestingField.nested`. Equality is structural; identity is not load-bearing.
- **`BackingClass`** is a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) replacing the cells of the legacy four `*InputType` permit cross-product that are actually populated in practice. Reading `usage.backingClass()` directly answers "which Java class materialises this input at this use site?", which is what consumers of that signal always wanted. The active fact lives on `InputUsage`; `Input` carries no backing-class slot. The arm is selected by a three-step resolution at the use site: table-bound → `JooqTableRecord(<TableRecord-for-table>, table)`; non-table-bound with a service-method consumer → param-type runtime-shape arm (`Pojo` or `JavaRecord`, rejecting non-`TableRecord` jOOQ Record subclasses); otherwise empty. Nested use sites inherit step (1) via the parent's table and reach step (2) via parent-class reflection.
- **`InputFieldDecl`** is the SDL field declaration on `Input`, pre-binding. Carries name, type, source location, and the wrapped `GraphQLInputObjectField` so the classifier can read applied directives at `InputUsage` construction time. Distinct from `InputField` (the post-classification carrier on `InputUsage.classifiedFields`).
- **`ConsumerBinding`** does not exist as a model type. The table fact lives on `InputUsage.table()`; the consumer coordinate that drove the inference is carried on the enclosing `ArgumentOccurrence` (for the root) and recoverable from the parent walk for nested nodes.
- **`InputTypeArg`** is the arg-site wrapper carrying a name, the nullability/list flags, the optional arg condition, and the root `InputUsage`. Convenience accessors (`input()`, `effectiveTable()`, `classifiedFields()`) delegate to the usage carrier so existing call sites keep their signatures.
- **`ArgumentOccurrence`** is the visit-site carrier (coordinate + `InputTypeArg`). Consumers that need the coordinate (LSP hover, diagnostics) read through it; consumers that don't read `InputTypeArg` directly. The coordinate cannot drift from the arg because the arg doesn't carry one.
- "Table-bound input" prose retires: in code, the predicate is `usage.table().isPresent()` (or `arg.effectiveTable().isPresent()` via the convenience accessor); in prose, "use site with a resolved table."

## Out of scope

- **Field-side dimensional pivot**: R164. This item validates the pattern; R164 applies it to the field surface.
- **`ServiceCatalog` predicate consolidation**: R220 / R193. Same disease in a different file.
- **`argMapping` grouping syntax**: R97 Phase 1. Separable; the per-arg classification slot the pivot introduces is the natural home for the grouping outcome, but the syntax and parser work is independent.
- **Visitor-driven emission for non-input types**: broader R166. The input-side slice is what this item absorbs.
- **Reachability pruning across all type kinds**: R166 Phase 1. Orthogonal; this item only prunes inputs via the visit gate.

## Risk

- **Per-use-site classification multiplies work for inputs reused across many consumers.** A filter input used by ten queries today is classified once eagerly; under the pivot, the classifier builds one `InputUsage` per use site (and one per nested position transitively). The classified fields are stored on the `InputUsage` so consumer-side access is a slot read, not a recompute; the multiplier is on build time, not on consumption. Mitigation 1: the per-use-site work is a lookup-per-SDL-field against the table's column set, typically low tens of fields; profile before optimising. Mitigation 2: if profiling shows a hotspot, deduplicate `InputUsage` construction by a side cache keyed on the `(Input, BackingClass, TableRef)` triple; identical bindings share an `InputUsage` instance. The cache lives outside the model record. The spec does not bake this in; structural equality is the contract.
- **Phases 1-3 keep both models alive simultaneously; the adapter is a deferred-deletion liability.** Mitigation: phases 4-5 are scheduled with the same urgency as the rest; the adapter is gone within the same release window.
- **`ArgumentOccurrence` adds a level of indirection at every consumer that wants both the coordinate and the arg.** Most consumers do not (DML emission reads only `InputTypeArg.effectiveTable()`); the LSP and diagnostics surfaces are the population that consumes the wrapper. Mitigation: the wrapper is a record-of-two, the cost is one field-access per consumer that uses it, and the integrity win (the coordinate cannot lie about which visit produced the arg, because the arg doesn't carry one) is the structural payoff the architect read flagged.
- **Test coverage gap: the cross-consumer divergence case has no fixture today.** Mitigation: add the fixture in Phase 3 as acceptance.

## Tests

- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `BuildWarning` entries for `@table`-decorated SDL inputs, which the test asserts on as part of the deprecation-warning fixture below).
- **Pipeline-tier (new):** consumer-derived path. Input with no `@table` directive used by a single `@table`-returning consumer produces an `InputTypeArg` whose root `InputUsage.table().isPresent()` carries the consumer's table; the consumer's `SchemaCoordinate` is recoverable from the enclosing `ArgumentOccurrence`. The root `InputUsage.backingClass()` is `JooqTableRecord(<TableRecord-for-table>, table)` (the table-bound arm of the three-step resolution). R97 Phase 2's acceptance fixture lands here. Companion: a non-table-bound input consumed by a user service method whose param is a Java record produces a `InputUsage.backingClass()` of `JavaRecord(<class>)`; a non-table-bound input reached only by graphitron-emitted resolvers produces empty.
- **Pipeline-tier (new):** `@table` on input emits `BuildWarning`. A fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer produces a `BuildWarning` at the directive's `SourceLocation` with the deprecation message, and the resulting root `InputUsage.table()` carries the consumer-derived table (same as if the directive were absent). The R97 Phase 3 runtime-ignore decision is pinned here.
- **Pipeline-tier (new):** backing-class-vs-consumer disagreement. A `JooqTableRecord`-backed input used by a consumer returning a *different* `@table` type signals a classification failure carrying `Rejection.AuthorError` through the standard classifier-output channel (today asserted on `UnclassifiedArg.rejection()`) rather than constructing an `InputUsage` that throws from its compact constructor. Pins invariant `input-usage.backing-table-aligns-with-effective-table` at the producer site.
- **Pipeline-tier (new):** non-`TableRecord` jOOQ Record rejected. A service-method param typed as a jOOQ `Record` subclass that is not a `TableRecord` (e.g. a generated UDT record) at a non-table-bound use site produces `Rejection.AuthorError` with the "supported non-table backings are Java record or POJO" message; no `InputUsage` is constructed for the arg. Pins the explicit-rejection commitment that replaces the legacy `JooqRecordInputType` arm.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two consumers with different return tables produces two `InputTypeArg` occurrences whose root `InputUsage.table()` slots hold the distinct tables. Today's `InputType` collapse becomes per-use-site success.
- **Pipeline-tier (new):** nested-input recursion. A fixture with an input `Outer { inner: Inner }` used at a `@table`-returning consumer produces a root `InputUsage` whose `classifiedFields` contains an `InputField.NestingField` carrying a child `InputUsage` for `Inner`; the child's `table` equals the parent's (parent-table-inheritance); the child's `backingClass` is derived from the parent's class when present. Pins the structural-recursion contract.
- **Pipeline-tier (new):** unreached input. An SDL input declared but never referenced from any reachable consumer produces an `Input` record with no `InputTypeArg` (and no `InputUsage`); `InputRecordGenerator` still emits, downstream classifiers see no per-arg work.
- **Unit-tier (new):** `InputUsage` compact-constructor rejects violating triples. `new InputUsage(input, Optional.empty(), Optional.empty(), nonEmptyClassifiedFields)` throws `IllegalStateException`; `new InputUsage(input, Optional.of(JooqTableRecord(c, tA)), Optional.of(tB), ...)` throws when `tA != tB`. Pins invariants `input-usage.empty-table-implies-empty-classified-fields` and `input-usage.backing-table-aligns-with-effective-table` at the carrier level. Companion: `Classifier.classify(input, Optional.of(table), backingClass)` produces an `InputField` per `Input.fields()` entry on the resulting `InputUsage.classifiedFields()`, with miss → `UnboundField` and hit → the column-bound arm; equality against the legacy `BuildContext.classifyInputFieldInternal` output for the same `(input, backingClass, table)` triple is the regression anchor that survives the producer-site move in Phase 3.
- **Pipeline-tier (new):** federation entity-fetch boundary. An SDL input referenced only by an `_Entity` resolver (not by any operation-root field) produces an `Input` record with no `InputTypeArg` and no `InputUsage`; the existing `EntityFetcherDispatch.resolveByReps` boundary handles wire decoding unchanged. Pins the federation-seed-set decision named in §Visitor-driven classification consequence (4).
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required by the pivot itself.

## Architectural principle this codifies

R164 frames the disease: a sealed hierarchy that tries to represent multiple independent dimensions through a single permit set. The cross product is the permit set; adding a value to any axis multiplies the permits below it; the leaves carry redundant or divergent encodings of the same axis.

The cure is the same in both organs: separate the dimensions onto orthogonal slots, with a sealed sub-taxonomy *per dimension* rather than a sealed sub-taxonomy of the *cross product*. This item ships the cure on the input surface where the cross product is smallest (5 permits encoding 3 axes), then R164 ships the same cure on the field surface where it's largest (46 permits across 3 sealed hierarchies encoding 5+ axes). R220 / R193 then apply it to the consumer-side classifier predicates.

The principle is not "minimise the model"; it is "make the model honest about what it's saying." The cross-product encoding hides axes; the dimensional encoding surfaces them. Hidden axes drift, surfaced axes get the compiler's help.
