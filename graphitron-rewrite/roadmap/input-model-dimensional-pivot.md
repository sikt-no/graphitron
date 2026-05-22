---
id: R222
title: "Input model: dimensional pivot under visitor-driven classification"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
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

The capability marker `HasInputRecordShape` (`HasInputRecordShape.java:17-22`) already has to list five permits to span both branches; R171 (Backlog) names this as the immediate symptom and proposes a `sealed interface InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact.

Nine consumer sites discriminate on the split today:

- `GraphitronSchemaValidator.java:80-81`: case arms on `InputType` and `TableInputType`.
- `MutationInputResolver.java:368`: `instanceof GraphitronType.TableInputType` gate.
- `EnumMappingResolver.buildLookupBindings(GraphitronType.TableInputType, ...)` (`EnumMappingResolver.java:303`).
- `CatalogBuilder.java:206 / 498 / 570 / 639`: four switch sites on `TableInputType`.
- `FieldBuilder.java:967`: `instanceof GraphitronType.TableInputType` arm in `classifyInputFieldOnArg`.
- `TypeBuilder.java:209-210`: the type-pivot in the synthesis pass.

Each consumer asks "does the input have a table?" by switching on the permit identity. None of them care about the backing-class kind on the unbound side.

## What's to be: two-level dimensional model

The pivot separates the *consumer-independent* surface (a fact about the SDL input declaration) from the *consumer-dependent* surface (a fact about how the input is used at a particular call site). Two records, one per level, with one slot per dimension.

```java
sealed interface Input extends GraphitronType, EmitsPerTypeFile permits Input.Of {

    InputRecordShape recordShape();          // R94, always present
    Optional<BackingClass> backingClass();   // axis 1
    GraphQLInputObjectType schemaType();
    List<InputFieldDecl> fields();           // SDL field declarations, pre-binding

    /**
     * Derive the per-field classification against the given effective table.
     * When {@code table.isEmpty()} returns the empty list (no binding means no
     * column resolution). When present, walks {@link #fields()} and resolves
     * each against the table: a hit produces a column-bound {@link InputField}
     * arm; a miss produces {@link InputField.UnboundField} per R215's admit set.
     *
     * <p>Pure function of {@code (this, table)}. Per-arg consumers call this
     * via {@link InputTypeArg#classifiedFields()}; the result is not cached on
     * {@code Input} in this spec (memoisation discussed under §Risk).
     */
    List<InputField> classifyAgainst(Optional<TableRef> table);
}

@LoadBearingClassifierCheck(
    key = "input.record-shape-derived-from-backing",
    guarantees = "Input.recordShape() is consistent with Input.backingClass(): a JavaRecord "
        + "backing implies a recordShape sourced from the record's canonical components; a Pojo "
        + "backing implies a recordShape sourced from JavaBean setters; absent backing implies "
        + "a recordShape sourced from SDL fields. The classifier is the single producer.")
record Of(
    String name,
    SourceLocation location,
    GraphQLInputObjectType schemaType,
    InputRecordShape recordShape,
    Optional<BackingClass> backingClass,
    List<InputFieldDecl> fields
) implements Input {
    public Of {
        // Spec'd compact-constructor invariant: recordShape and backingClass cannot drift.
        // The validator audit (LoadBearingGuaranteeAuditTest) pins this key against
        // InputRecordGenerator + the per-arg materialiser as the load-bearing consumers.
        fields = List.copyOf(fields);
    }

    @Override public List<InputField> classifyAgainst(Optional<TableRef> table) {
        if (table.isEmpty()) return List.of();
        return fields.stream()
            .map(decl -> resolveOne(decl, table.get()))
            .toList();
    }
    // resolveOne(decl, table): name + @field(name:) → table.column(name) hit → ColumnField,
    // miss → UnboundField. Composite-PK FK targets fan out to CompositeColumnField. Pure
    // function; the existing classifier logic in BuildContext.classifyInputFieldInternal is
    // the natural source for this body.
}

sealed interface BackingClass {
    String fqClassName();
}
record Pojo(String fqClassName) implements BackingClass {}
record JavaRecord(String fqClassName) implements BackingClass {}
record JooqRecord(String fqClassName) implements BackingClass {}
record JooqTableRecord(String fqClassName, TableRef table) implements BackingClass {}

/**
 * The SDL field declaration on an {@link Input}, before any table binding has been
 * applied. Carries the field's name, type, source location, and directives. Distinct
 * from {@link InputField}: that family is the *classified* shape (column-bound or
 * unbound), produced by {@link Input#classifyAgainst(Optional)} from these declarations
 * once an effective table is known.
 */
record InputFieldDecl(GraphQLInputObjectField rawField) {
    public String name()              { return rawField.getName(); }
    public GraphQLInputType type()    { return rawField.getType(); }
    public SourceLocation location()  { return rawField.getDefinition().getSourceLocation(); }
    // The classifier reads each field's applied directives at Input.classifyAgainst time;
    // @field(name:) is the column-name override read for column resolution. The wrapper
    // does not enumerate one accessor per directive; the body of classifyAgainst owns
    // the directive vocabulary.
}
```

The `Input` record carries what's true of the SDL declaration in isolation: name, location, the assembled-schema form, the per-input-type emit shape (R94's `InputRecordShape`), the backing class (when one is named or reflected), and the pre-binding SDL field declarations. `BackingClass` is a four-arm sealed family that carries what the four `*InputType` permits used to encode in their identity. `InputFieldDecl` is the SDL declaration (name, type, location, directives) before any table binding is applied; the classifier wraps each `GraphQLInputObjectField` once at `Input` construction. The classified per-field list is *not* a slot on `Input`; it is the return of `Input.classifyAgainst(table)`, a pure function whose inputs are `Input.fields()` and the effective table. The classifier-side invariant linking `recordShape` to `backingClass` is named explicitly (key `input.record-shape-derived-from-backing`) so consumers cannot drift the two slots apart and `LoadBearingGuaranteeAuditTest` enforces the producer-consumer chain.

```java
record InputTypeArg(
    String name,
    Input input,                                  // back-reference to the consumer-independent record
    boolean nonNull,
    boolean list,
    Optional<ConsumerBinding> consumerBinding,    // populated iff the consumer @table-anchors the arg
    Optional<ArgConditionRef> argCondition
) implements ArgumentRef {
    @Override public String typeName() { return input.name(); }

    /**
     * The arg's effective table at this visit site. The consumer-asserted table wins
     * when present (the consumer is the authority at the visit site); otherwise the
     * backing class supplies it via {@link BackingClass.JooqTableRecord#table()}.
     * Centralises the two-source compose so downstream consumers never reproduce the
     * predicate.
     */
    public Optional<TableRef> effectiveTable() {
        if (consumerBinding.isPresent()) return Optional.of(consumerBinding.get().table());
        return input.backingClass()
            .flatMap(bc -> bc instanceof BackingClass.JooqTableRecord jtr
                ? Optional.of(jtr.table())
                : Optional.empty());
    }

    /**
     * The per-arg classification, derived on demand from the Input's SDL field
     * declarations and the effective table. Not a stored slot: classification is a
     * function of {@code (Input, effectiveTable)}, and an InputTypeArg occurs exactly
     * once at one consumer coordinate, so there is nothing per-arg to cache that the
     * pair does not already determine. Storing the derivation would denormalize the
     * model and admit drift between the slot and its inputs.
     */
    public List<InputField> classifiedFields() {
        return input.classifyAgainst(effectiveTable());
    }

    public InputTypeArg {
        // Cross-axis invariant pinned for the duration of the pivot:
        //   input-arg.consumer-binding-aligns-with-backing-table
        // The adapter shims in Phase 1-2 preserve it; Phase 3's visitor produces by
        // construction. The validator audit (LoadBearingGuaranteeAuditTest) pins it
        // against MutationInputResolver, EnumMappingResolver,
        // FieldBuilder.classifyInputFieldOnArg, GraphitronSchemaValidator, and the four
        // CatalogBuilder sites as load-bearing consumers.
        if (consumerBinding.isPresent()
            && input.backingClass().orElse(null) instanceof BackingClass.JooqTableRecord jtr
            && !consumerBinding.get().table().equals(jtr.table())) {
            throw new IllegalStateException(
                "consumer-derived table disagrees with backing-class table");
        }
        // The "classified-fields-require-effective-table" invariant is no longer a
        // compact-constructor check: Input.classifyAgainst() returns the empty list
        // when the effective table is absent, so the invariant holds by derivation.
    }
}

// The visit-site coordinate lives on a thin wrapper, not on InputTypeArg itself, so
// the arg record cannot be constructed with a wrong coordinate at a non-visit-time
// call site (e.g. a test fixture). Consumers that need the coordinate (LSP hover,
// diagnostics) read it through the wrapper; consumers that don't read InputTypeArg
// directly.
record ArgumentOccurrence(SchemaCoordinate consumer, InputTypeArg arg) {}

// The consumer side of the table-binding axis. Populated iff the enclosing field's
// @table return drives the inference; carries the coordinate that did. No payload-less
// "FromBackingClass" sibling: when the backing class supplies the table, it lives on
// BackingClass.JooqTableRecord and there is nothing extra to record at the arg.
record ConsumerBinding(TableRef table, SchemaCoordinate origin) {}
```

`InputTypeArg` carries what's true of *this argument occurrence* of the input: name, the back-reference to its `Input`, nullability and list wrappers, the optional consumer-side table assertion (`consumerBinding`), and the optional arg condition. Each `InputTypeArg` occurs exactly once at one consumer coordinate by construction; there is no per-arg classified state to cache that is not already determined by `(Input, effectiveTable())`. The per-arg classification (`classifiedFields()`) is a derived accessor delegating to `Input.classifyAgainst(effectiveTable())`; that derivation is the load-bearing path.

`ArgumentOccurrence` is the thin wrapper the visitor produces at the visit site. It carries the coordinate (`Query.films` etc.) and the `InputTypeArg`. LSP hover and diagnostics consume `ArgumentOccurrence`; emitters that don't need the coordinate consume `arg()` directly. The wrapper exists so the coordinate cannot drift from the arg: a test fixture that constructs an `InputTypeArg` alone cannot lie about the coordinate, since the arg doesn't carry one.

The two-source table model collapses cleanly because the `@table` directive on input types is dropped at this pivot (see §"`@table` on input is dropped" below). With the directive gone, only two sources of binding remain: the input's backing class (consumer-independent; lives on `BackingClass.JooqTableRecord`) and the consumer's `@table` return (consumer-dependent; lives on `InputTypeArg.consumerBinding`). Each source is housed where it belongs; nothing back-derives one from the other; the `effectiveTable()` accessor on `InputTypeArg` centralises the compose so consumers don't reproduce the predicate.

### `@table` on input is dropped

The pivot drops `@table` as a binding source on `INPUT_OBJECT`. The directive is read once at classification, surfaced as a `BuildWarning` via `ctx.addWarning(...)` (the existing channel `TypeBuilder.emitDirectiveIgnoredWarnings` already uses for "shadowed by `@table`" on `@record`), and then ignored for binding purposes. Three sources collapse to two: backing class (`BackingClass.JooqTableRecord.table` on `Input`) and consumer (`ConsumerBinding.table` on `InputTypeArg`). Phase 6 narrows the SDL directive declaration to drop `INPUT_OBJECT` and sweeps every `@table`-decorated SDL input across fixtures.

The realistic consequence: an SDL input decorated `@table` used at a `@table`-returning consumer keeps the same effective table via consumer-derivation; the warning surfaces the now-redundant decoration. An input decorated `@table` and used only at consumers that do *not* return a `@table` type loses its binding (with the warning explaining why). The fixture sweep in Phase 6 confirms zero realistic cases of the latter; Sakila's 16 `@table`-decorated inputs all consume at `@table`-returning consumers (`Query.films(filter: FilmConditionInput!): [Film @table]` etc.).

### Directives the new model carries

The pivot affects how `@table` and `@record` resolve into the model; other input-related directives stay on their existing resolution paths. Mapping for the reviewer:

| Directive | Pre-pivot home | Post-pivot home |
|---|---|---|
| `@record(class: ...)` | identity of the four `*InputType` permits | folded into `BackingClass` (Pojo, JavaRecord, JooqRecord, JooqTableRecord); `fqClassName` is the slot |
| `@table` on input type | `TableInputType.table` (legacy) / `JooqTableRecordInputType.table` (reflected) | **ignored at classification**; the visitor emits a `BuildWarning` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return or the backing class"). Binding resolves from `BackingClass.JooqTableRecord.table` (consumer-independent) or `ConsumerBinding.table` (consumer-derived); invariant #4 pins the two-source agreement |
| `@condition` on input *type* | read from the assembled `GraphQLInputObjectType` via `TypeBuilder.isUsedWithOverrideCondition` | unchanged: read from `Input.schemaType()` (the assembled-schema slot is preserved) |
| `@inputBean` / `@enumMap` / `@field(name:)` on input *fields* | read by `InputBeanResolver`, `EnumMappingResolver` from the assembled-schema field | unchanged: those resolvers continue to consult the assembled-schema; R200 / R195 / R98 stay scoped where they are |

Anything not in this table is read from `Input.schemaType()` exactly as today, since the pivot preserves the assembled-schema reference on the model record.

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | `Input` with `Optional<BackingClass>` slot; 4 arms move onto `BackingClass` |
| `GraphitronType.TableInputType` sibling root | `Input` + `InputTypeArg.consumerBinding` populated at each consumer position that @table-anchors the arg |
| `JooqTableRecordInputType.table` slot | `BackingClass.JooqTableRecord.table` (the reflected-from-class path) |
| `TableInputType.table` slot | `BackingClass.JooqTableRecord.table` (when reflection supplies it) or `ConsumerBinding.table` (when the consumer's @table return drives it); the legacy directive-driven path is dropped, surfacing as a `BuildWarning` for the duration of Phase 6's sweep |
| `TableInputType.inputFields` (stored, eager-classified) | `Input.fields()` carries the SDL declarations (`List<InputFieldDecl>`); `Input.classifyAgainst(table)` produces the classified list on demand; `InputTypeArg.classifiedFields()` is a derived accessor over `(input, effectiveTable())`. Slot retires. See "Why classifiedFields demotes to a derived view" below. |
| `HasInputRecordShape` capability marker (5 permits) | direct slot on `Input` |
| `ArgumentRef.InputTypeArg.TableInputArg` | merged into `InputTypeArg` |
| `ArgumentRef.InputTypeArg.PlainInputArg` | merged into `InputTypeArg` (with `consumerBinding = empty`) |
| `TableInputArg.lookupKeyFields` / `setFields` slots (R144 DmlKind-driven partition) | retired from the model; `MutationInputResolver` derives the partition on demand from `classifiedFields` plus the consuming verb's `DmlKind`. See "What this absorbs" below. |
| 9 consumer-side discriminations on `TableInputType` (instanceof gates and switch arms) | `arg.effectiveTable().isPresent()` at the consumption point |
| `TypeBuilder.findReturnTablesForInput` back-scan | resolved at the visit site under R166 walk |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes a `walkInputs` pass that visits every `InputTypeArg` and dispatches on `effectiveTable()` presence; the per-permit-identity dispatch retires.

## Visitor-driven classification (R166 absorption for inputs)

Today's classifier is iteration-driven: `TypeBuilder` walks `schema.getAllTypesAsList()` once and classifies each `INPUT_OBJECT` in isolation. The table-binding decision is made per-input-type via the `findReturnTablesForInput` back-scan, which scans every schema field asking "who returns a `@table` type and consumes this input?" The decision is the wrong shape for two reasons: it's O(N) per input rather than O(1) at the use site, and it collapses divergent consumers (the `tables.size() > 1` arm at `TypeBuilder.java:1022` demotes the input to `InputType` even when each consumer has a coherent local table).

Under the pivot, classification is driven by `graphql.schema.SchemaTraverser.depthFirst(visitor, roots)` seeded with the consumer-reachable surface (root operation types). The visitor visits each field with its arguments in scope: when it reaches `Query.films(filter: FilmConditionInput!)`, the *visiting context* already carries the consumer's return type (`[Film @table]`). The `InputTypeArg` for `filter` is produced at the visit site, with `consumerBinding` populated from the enclosing field's `@table` return when applicable. No back-scan.

Five consequences fall out structurally:

1. **Consumer-derived table resolution is local.** With `@table` on input dropped, consumer-derivation is the *only* per-arg binding source (the other source, the input's backing class, is consumer-independent and lives on `Input.backingClass()`). R97 Phase 2 becomes the default, not a flag flip; `ConsumerBinding.origin` carries the coordinate that drove the inference for diagnostics and LSP hover, and the enclosing `ArgumentOccurrence` carries the consumer position of the arg itself. R97's Phase 1 (`argMapping` grouping) remains as follow-up; R97's Phase 3 (narrow directive scope + fixture sweep) lands as this item's Phase 6.
2. **Cross-consumer reuse works correctly.** `FilmConditionInput` used by two queries returning different tables produces two `InputTypeArg` carriers, each with its own `ConsumerBinding`. The `tables.size() > 1` collapse retires.
3. **R213 dissolves.** Plain-input field rejections currently lose source location because the classifier is detached from the use site. The visitor sits *at* the input field when classifying it; the `SourceLocation` it carries is the input field, not the consumer.
4. **Reachability falls out.** An SDL input never referenced from any reachable consumer produces no `InputTypeArg`. The `Input` record still exists for the per-type emit, but per-arg classification is gated on visit. Subset of R166 Phase 1's reachability discipline. **Federation seed set:** the visitor seeds from operation roots only, not from `_Entity`-bearing types. Federation entity-fetch inputs decode at the `representations: [_Any!]!` wire boundary (handled by `EntityFetcherDispatch.resolveByReps` outside the model) and intentionally do not flow through the per-arg classification path. An SDL input used *only* as an argument to an entity-fetch service method without appearing on a reachable operation field is therefore unclassified by this walk; today's `schema.types()`-driven classifier produces the same effective outcome (the input has no classified consumer), but the spec names the decision explicitly so a future reader does not treat it as a regression.
5. **R209 re-homes.** The typed-rejection lift `FieldRegistry.classifyInput` defers today (because `InputFieldResolution.Unresolved` doesn't carry a typed `Rejection` payload) becomes structurally trivial: column-miss is detected at the per-arg visit site, where the `TableRef` and the candidate-column list are both in scope. The `RejectionKind.AUTHOR_ERROR` default arm at `FieldRegistry.java:108-110` retires; typed `Rejection.AuthorError.UnknownName` with Levenshtein hint is the only path.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves: capability declarations land on `Input` directly; the parent-root fix becomes moot. |
| R97 (deprecate `@table` on input types) | Phase 2 falls out structurally; Phase 3's runtime ignore + deprecation warning lands at Phase 3 of this item; Phase 3's SDL-narrowing + fixture sweep lands at this item's Phase 6. `argMapping` grouping (R97 Phase 1) remains as separate follow-up. |
| R213 (rejections at consumer field) | Visit-time source location. |
| R209 (FieldRegistry classify-input trace) | Typed rejection lift becomes trivial at the per-arg visit site. |
| R166 Phase 2 (`InputTypeGenerator` under visitor) | Subset: the input-side slice of R166's broader visitor-driven emission. |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves: `GraphitronSchemaValidator.validateTableInputType` becomes `validateInputArg` (Phase 4) that walks every `InputTypeArg.classifiedFields` uniformly. The `TableInputType.inputFields()` vs `PlainInputArg.fields()` split that motivated R221 retires under the pivot's unified `InputTypeArg`. |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker on input fields) | Two reversals. (1) The partition leaves the classifier-side model and moves to the consumer. `arg.classifiedFields()` is the single sealed list (derived from `Input.classifyAgainst(arg.effectiveTable())`); `MutationInputResolver` derives the WHERE-vs-SET partition at consumption from that list plus the verb's `DmlKind`. (2) The `@value` directive is dropped. The partition mechanism becomes PK-derivation against the target table's primary-key column set (a fact the jOOQ catalog already carries); no per-input-field marker is needed. R144's cardinality-safety surface (`multiRow`, PK-coverage check) survives unchanged in shape. See the R144 rationale paragraph below. |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164**: this item proves out the dimensional pattern on the smaller input surface. R164 then applies the pattern to the larger field hierarchy with the precedent already in tree.
- **R98** (multi-source input validation): once `HasInputRecordShape` becomes a direct slot on `Input`, attaching `ConstraintSet` to it is a one-site change. Lands as a follow-up.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): these are about *naming binding* between SDL fields and Java members on the backing class. The pivot doesn't change naming resolution; both items stay in scope on `InputBeanResolver`.

### Why R144's partition reverses

R144 committed the WHERE-vs-SET partition at classify time, on the carrier itself: `TableInputArg.lookupKeyFields()` and `TableInputArg.setFields()` are populated by a DmlKind-aware factory (`ArgumentRef.java:284-312`), and `MutationInputResolver` reads the partition as fact. Two problems show up under the pivot.

First, the partition is a property of the *consumer*, not of the *arg*: the same input used by `Mutation.updateFilm` (UPDATE) and `Mutation.deleteFilm` (DELETE) belongs in different partitions at each site. Today's model handles this because the partition lives on `TableInputArg` (per-arg-occurrence), which masks the underlying mistake. The pivot's dimensional principle ("one slot per dimension; the consumer's concern is on the consumer") makes the shape visible: WHERE-vs-SET is the mutation emitter's concern, not the classifier's.

Second, the partition is *denormalized*: `InputField.LookupKeyField` and `InputField.SetField` are intersection-marker interfaces on the same concrete `ColumnField`/`CompositeColumnField`/etc., which means the typed lists store the same fields, partitioned. R144's factory does the partitioning; consumers read it. Reverting the commitment is cheap (the factory logic moves into `MutationInputResolver`, the only consumer of `lookupKeyFields()` / `setFields()`), and it eliminates the intersection-marker leak at the model-shape level.

The intersection-marker pattern on `InputField` (ColumnField IS-A both LookupKeyField and SetField) stays in place under R222 since making those disjoint is a separate model refactor; this item only moves *where* the partition decision is made, not *how* the underlying types express eligibility.

### Why R144's `@value` directive retires

Once the partition lives on the consumer (above), the marker that fed it surfaces as the same disease one layer down. `@value` on an input field is rejected on DELETE / INSERT / UPSERT, admitted on UPDATE, and mutually exclusive with `@condition` on the same field. The directive's validity is a function of the consumer's verb, which is exactly the "if two consumers evaluate the same predicate over a model field, the branch belongs in the model" smell the principles list names. A per-input-field directive encoding per-consumer-verb semantics is dimensionally inverted.

The dimensionally honest mechanism, once the catalog is in scope: the WHERE-vs-SET partition on UPDATE is `(arg.classifiedFields(), targetTable.primaryKey())` derivable; PK-column carriers go to WHERE, the rest go to SET. The jOOQ catalog already carries the PK column set; no per-input-field marker is needed to recover the partition. R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) is orthogonal to the directive and survives unchanged in shape: the trigger is the catalog's PK column set, not the `@value` complement.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical with or without the directive. The fixture sweep that drops them is a one-line edit per site. `docs/manual/reference/directives/value.adoc` retires. Two `@LoadBearingClassifierCheck` keys swap on the same `MutationInputResolver` site: `mutation-input.update-set-fields-equal-value-marked` retires; `mutation-input.update-partition-by-pk-membership` replaces it (PK-column carriers in `classifiedFields` go to WHERE, the rest to SET, on UPDATE; the existing PK-coverage check key stays).

### Why classifiedFields demotes to a derived view

An earlier draft of this spec carried `List<InputField> classifiedFields` as a stored slot on `InputTypeArg`, with a compact-constructor invariant pinning "no classified fields without an effective table." A walk-through of the dimensional principle showed the slot is denormalization.

Each `InputTypeArg` occurs exactly once at one consumer coordinate. The classified fields at that occurrence are entirely a function of `(input, effectiveTable())`: the input contributes its SDL field declarations, the effective table contributes its column set, and the classifier matches name-by-name (honoring `@field(name:)` overrides). There is nothing per-occurrence beyond that pair; storing the result on the carrier admits a state space the inputs cannot produce.

The fix: `classifiedFields` leaves the record sketch. `Input.fields()` carries the pre-binding `List<InputFieldDecl>`. `Input.classifyAgainst(Optional<TableRef>)` is the load-bearing producer (the `@LoadBearingClassifierCheck` annotation moves here). `InputTypeArg.classifiedFields()` is a derived accessor delegating to `input.classifyAgainst(effectiveTable())`. The invariant "no classified fields without an effective table" becomes a property of the derivation rather than a runtime check.

Per-arg consumers that read classified fields (`MutationInputResolver`'s R144-relocated partition logic, `GraphitronSchemaValidator.validateInputArg`, `EnumMappingResolver.buildLookupBindings`) call the accessor at consumption. The cost is one classification per occurrence rather than one per `Input`; for inputs reused across many sites with consumer-derived bindings, that is N classifications instead of one. Each is cheap (a lookup per SDL field against a column set typically in low tens). If profiling shows a hotspot, memoise on `Input` via `Map<TableRef, List<InputField>>`; the spec does not bake the cache in.

### Recursion through nested inputs

`Input.classifyAgainst` is recursive: a nested-input field on the parent recurses into the nested input's own classification against the same effective table. Two facts shape the contract.

**GraphQL allows input cycles; graphitron does not.** Per the GraphQL spec (October 2021 §3.6.1), an input object's references can recurse, including indirectly, *provided at least one field in the cycle is nullable* (otherwise no finite input value is constructible). `graphql-java` enforces the nullable-link rule at schema-build time, so `TreeFilter { children: [TreeFilter] }` parses but `Cycle { c: Cycle! }` does not. Graphitron is stricter: it rejects *all* cycles in `NestingField` recursion, because the classifier enumerates nested fields at classification time to produce fixed SQL projections, and arbitrary-depth nesting has no fixed lowering. This stance is graphitron-specific and pre-dates R222; the pivot preserves it.

**Recursion mechanic.** The pre-pivot classifier (`BuildContext.classifyInputField` at `BuildContext.java:1640-1664`) threads a `ClassifyContext` (`ClassifyContext.java`) carrying a `Set<String> expandingTypes`. When the classifier recurses into a nested input, it calls itself with `ctx.expanding(typeName)`; if the recursive call sees that name already in the set, it returns `Unresolved` with the "circular input type reference detected while expanding 'X'" reason, which today's caller lifts to an `UnclassifiedType`. The mechanic transfers to the pivot unchanged.

**The lifted signature.** The public entry point is `Input.classifyAgainst(Optional<TableRef>)`; internally the classifier carries a `ClassifyContext` for the visited-set, with the public form delegating to `classifyAgainst(table, ClassifyContext.root())`. For each `InputFieldDecl` in `Input.fields()`:

- **Scalar / enum type, resolves to a column on `table`:** produces `ColumnField` / `CompositeColumnField` or their `Reference` variants (the existing arms).
- **Scalar / enum type, no column resolves:** produces `UnboundField` per R215's admit set.
- **Nested input type without its own backing class:** recursive call into `nestedInput.classifyAgainst(Optional.of(parentTable), ctx.expanding(parentTypeName))`. The result wraps in `NestingField` with the existing semantics (shared parent-table context, fields enumerated). When `ctx.isExpanding(nestedTypeName)` returns true, the recursive call short-circuits and produces `UnboundField` with the "circular input type reference" reason (the same `Unresolved` reason today's `BuildContext` emits, surfaced through R215's deferred-rejection rail).
- **Nested input type *with* its own `BackingClass.JooqTableRecord` binding:** out of R222 scope. R122 (`compound-entity-mutations`) is the territory; today's classifier produces `Unresolved("no column 'X' reachable")` for this case at parent's level, which lifts to `UnboundField` under R215. The pivot inherits that outcome verbatim; R122 is the item that introduces a dedicated arm if one is ever needed.

**Cycle detection ordering vs the memoisation escape hatch.** The Risk section names memoisation on `Input` via `Map<TableRef, List<InputField>>` as the profile-driven optimisation. If a cache is introduced, the lookup must happen *before* the visited-set check: a cached result for an Input not currently expanding is safe to return, but probing the cache after the visited-set check would silently miss any recursive call that legitimately resolves (a nested input is allowed to reference its parent indirectly through a separately-classified Input, just not into the *currently expanding* type set). The ordering is: cache → visited-set check → classify → cache write. The spec calls this out so a future implementer adding the cache does not invert it.

**Test surface.** Today's coverage in `GraphitronSchemaBuilderTest.java` exercises both the direct self-referential case (line 1358, "self-referential nested type → UnclassifiedField with circular-reference message") and the indirect chain (line 5812, "circular A → B → A chain rejects loudly as UnclassifiedField; build terminates"). Both assert on the classifier's "circular input type reference detected" / "circular type reference" reason strings. Under the pivot, the producer of those rejections moves from `BuildContext.classifyInputField` to `Input.classifyAgainst`; the reason string and the surfacing path through `UnboundField` (R215) stay verbatim. Both tests carry over with no behavioural change.

## Cross-axis invariants

The dimensional split admits states the old model could not. Four load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key. Producers vary by invariant: `Input.classifyAgainst` for the derivation invariant; the visitor (Phase 3 onward) for the per-arg invariants; the classifier for the per-type invariants. Consumers are the per-arg emitters plus the per-type generators as noted.

1. **`input.classify-against-empty-table-returns-empty`**: `Input.classifyAgainst(Optional.empty()) ⇒ List.of()`. Holds by construction in `Input.classifyAgainst`: the first line is the empty-table guard. No compact-constructor check needed since `classifiedFields` is no longer a stored slot; the invariant is a property of the producer (`Input.classifyAgainst`) rather than of any carrier. The `@LoadBearingClassifierCheck` annotation lands on `Input.classifyAgainst`; consumers (`MutationInputResolver`, `GraphitronSchemaValidator`'s `validateInputArg`, `EnumMappingResolver.buildLookupBindings`, the four `CatalogBuilder` sites) read via `arg.classifiedFields()` and the audit walker pins the chain through that single derivation point. The reverse direction is descriptive: when an effective table is present, the result carries `InputField` entries the per-field classifier produced against that table (the R215 admit set: column-bound carriers plus `UnboundField` for the override-cascade case). The list may be empty when an Input has no SDL fields (parser-level rare but reachable).
2. **`input.record-shape-derived-from-backing`**: `Input.recordShape()` and `Input.backingClass()` cannot drift. Producer: classifier; consumers: `InputRecordGenerator`, the per-arg materialiser.
3. **`input.per-type-emit-ignores-per-arg-classification`**: `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never `InputTypeArg`); per-arg consumers read only `InputTypeArg` (never the consumer-position-dependent classification cached on an `Input`). The reachability degenerate-state (an `Input` with no `InputTypeArg` occurrences emits per-type but has no classified consumption) is consistent by construction once this key holds. Pinned with `@DependsOnClassifierCheck` on the two per-type generator entry points.
4. **`input-arg.consumer-binding-aligns-with-backing-table`**: when `arg.input().backingClass()` is `Some(JooqTableRecord(_, table_backing))` and `arg.consumerBinding()` is `Some(ConsumerBinding(table_consumer, _))`, the visitor enforces `table_backing == table_consumer`. Otherwise (the input's reflected backing table disagrees with the visited consumer's `@table` return), the visitor produces an `UnclassifiedArg` with `Rejection.AuthorError` rather than silently picking one path over the other. The legacy model made this state structurally unreachable (a `JooqTableRecordInputType` carried one `TableRef` and nothing else could override it); the pivot's two-source resolution admits the state, so it must be pinned. Producer: the visitor's two-way resolver in Phase 3; consumers: every DML emitter that reads `arg.effectiveTable()` (currently `MutationInputResolver`, the four `CatalogBuilder` sites).

Invariant #1 holds by construction in `Input.classifyAgainst` and requires nothing from the Phase 1-2 adapter. Invariants #2, #3, and #4 must be satisfied by the adapter shims as part of their "lossless round-trip" criterion; otherwise the legacy validator's invariants are bypassed by callers that construct the new types directly. The audit walker (`LoadBearingGuaranteeAuditTest`) is the safety net.

## Phasing

Six phases, each independently shippable and individually reversible.

### Phase 1: introduce `Input` and `BackingClass` alongside existing permits

- Add `sealed interface Input`, the `BackingClass` family, and `InputFieldDecl` to `model/`.
- Implement an adapter that constructs an `Input` from each of the five existing permits. The adapter is lossless on the consumer-independent half (name, location, recordShape, backingClass, fields); for `TableInputType`, the consumer-dependent half (table binding) is carried over in Phase 2 via the `InputTypeArg` adapter and is out of scope here. The eager-classified `inputFields` on legacy `TableInputType` is *not* carried into a slot; it is reproduced on demand via `Input.classifyAgainst(table)` (the body is the existing `BuildContext.classifyInputFieldInternal` logic, lifted into a method on `Input.Of`).
- `Input.fields()` is populated from `inputType.getFieldDefinitions()` at Input construction: one `InputFieldDecl` per SDL field.
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- No consumer migrates yet; the adapter is the bridge.

Acceptance: every classified input today produces an equivalent `Input` via the adapter (lossless on the consumer-independent half). The adapter pair (Phase 1 plus Phase 2) is lossless against the legacy classification *modulo the `@table`-on-input cases*: directive-derived bindings surface as `BuildWarning` plus consumer-derivation when applicable; the directive-only-at-non-`@table`-consumer case becomes unbound (and the Phase 6 fixture sweep is where any such case is corrected). No other behaviour change.

### Phase 2: introduce `InputTypeArg` with dimensional axes

- Add the unified `InputTypeArg` record + `ConsumerBinding` slot + `effectiveTable()` accessor + derived `classifiedFields()` accessor.
- Adapter from existing `TableInputArg` / `PlainInputArg` → new `InputTypeArg`. The adapter derives `consumerBinding` from the legacy classification context: for `TableInputArg` produced today via `findReturnTablesForInput`'s consumer back-scan, the consumer's coordinate is available at the adapter's call site; for `TableInputArg` produced from the `@table` directive on the input itself, the adapter emits a `BuildWarning` (the Phase 3 visitor inherits this contract) and falls back to consumer-derivation when the consumer is also `@table`-bound. The adapter does *not* copy the legacy `inputFields` list onto a slot; consumers that need the classified list call `arg.classifiedFields()` and the derivation reproduces it.
- The compact-constructor invariant `input-arg.consumer-binding-aligns-with-backing-table` (see Cross-axis invariants) lands here; the adapter is the producer for Phase 1-2 and must satisfy it. Invariant `input.classify-against-empty-table-returns-empty` holds by construction in `Input.classifyAgainst` and needs no compact-constructor check on `InputTypeArg`.
- New record sits alongside the existing arg types; no consumer migrates yet.

Acceptance: every classified arg today produces an equivalent `InputTypeArg` via the adapter, *modulo* the `@table`-on-input directive translation called out in Phase 1's acceptance; the compact-constructor invariant holds for every adapter-produced instance; `arg.classifiedFields()` produces a list equal-by-value to the legacy `TableInputArg.inputFields()` for the same `(input, effectiveTable)`.

### Phase 3: visitor-driven per-arg classification

- Add `GraphQLSchemaVisitor`-based walker seeded from root operations.
- At each `argument` visit on an `INPUT_OBJECT`-typed arg, produce the `ArgumentOccurrence` directly with an inner `InputTypeArg`. The arg's `consumerBinding` is populated iff the enclosing field's return type is `@table`-bound: `ConsumerBinding(consumerTable, consumerCoordinate)`. The backing-class half is consumer-independent and already lives on `Input.backingClass()` (specifically the `JooqTableRecord` arm); the per-arg site never copies it. When `arg.consumerBinding()` is empty and `arg.input().backingClass()` is not `JooqTableRecord`, `effectiveTable()` is empty and `classifiedFields` stays empty. When `consumerBinding` is present and the backing-class arm also supplies a table, the visitor enforces table agreement (invariant #4 below); disagreement produces an `UnclassifiedArg` with `Rejection.AuthorError`.
- **`@table` on input is dropped at this phase.** The visitor reads the directive once at the input's classification site, emits a `BuildWarning` via `ctx.addWarning(...)` at the directive's `SourceLocation` ("@table on input types is deprecated; binding is derived from the consumer's @table return or the backing class"), and otherwise ignores it. The directive does not contribute to `consumerBinding`. The companion fixture sweep that drops the directive across SDL fixtures lands at Phase 6.
- Retire `TypeBuilder.findReturnTablesForInput`. Per-input-type table classification disappears; per-arg replaces it.
- **Annotation continuity:** migrate R215's two `@LoadBearingClassifierCheck` keys (`input-field.unbound-implies-no-column`, `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`) from the legacy `BuildContext.classifyInputFieldInternal` producer-site to the lifted `Input.classifyAgainst` body (the new home for that logic). Run `LoadBearingGuaranteeAuditTest` before and after to confirm no orphaned consumers. Same for the four keys introduced under Cross-axis invariants. `input.classify-against-empty-table-returns-empty` lands on `Input.classifyAgainst` directly; `input-arg.consumer-binding-aligns-with-backing-table` lands on the visitor's two-source resolver.
- Wire R97 Phase 2's consumer-derived inference here. Test: cross-consumer divergence fixture (one input used by two consumers with different tables) classifies successfully per-arg.
- **Anchor one consumer in the same phase** so the visitor's load-bearing producer-consumer chain is exercised before the phase boundary. The chosen anchor is `EnumMappingResolver.buildLookupBindings`: smallest signature lift in the Phase 4 list (one mechanical edit from `(GraphitronType.TableInputType, ...)` to `(InputTypeArg, ...)`), no behavioural cousins on the same surface (unlike `MutationInputResolver`'s R215-adjacent admission rules), and the audit walker has something concrete to anchor `input-arg.consumer-binding-aligns-with-backing-table` against. Without an in-phase consumer, the audit detects neither producer drift nor missing producers on the new keys; with one, the chain is live from the day Phase 3 ships.

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged (each `@table`-decorated SDL input surfaces one `BuildWarning` but classifies identically via consumer-derivation). The cross-consumer divergence fixture works. `EnumMappingResolver.buildLookupBindings` reads the visitor's output, and the four new keys are pinned by `LoadBearingGuaranteeAuditTest`.

### Phase 4: migrate the remaining consumers

Move each remaining consumer off the legacy permit discrimination onto the new slots. Order chosen to keep blast radius small per PR; the R215 follow-ups deferred during R215's review fold in at the listed slots:

- `MutationInputResolver`: reads `arg.effectiveTable()` directly and calls `arg.classifiedFields()` (the derived accessor) for the per-arg list. **R144 partition relocated + `@value` retired:** the DmlKind-aware WHERE-vs-SET partition logic moves from `ArgumentRef.java:284-312` (the legacy `TableInputArg` factory) into this resolver, operating on the result of `arg.classifiedFields()` plus the target table's PK column set (sourced from `JooqCatalog`). The two readers of `lookupKeyFields()` / `setFields()` (`MutationInputResolver.java:543` and the cousin sites the same file already owns) consume the partition at the site that decides the verb. The `@value`-marked-name-set parameter on `TableInputArg.of` / `EnumMappingResolver.buildLookupBindings` disappears; the verb-validity arms in `MutationInputResolver` that reject `@value`-on-DELETE / `@value`-on-INSERT / `@value`+`@condition` retire with the directive. Load-bearing key swap on the same producer site: `mutation-input.update-set-fields-equal-value-marked` retires, `mutation-input.update-partition-by-pk-membership` replaces it. R144's `mutation-input.where-columns-cover-pk` key stays (the cardinality-safety check is orthogonal to the directive). **R215 follow-up:** the deferred `MutationField.{Value, Condition}` projection lands here if it has not already shipped standalone.
- `FieldBuilder.classifyInputFieldOnArg`: drops the `instanceof TableInputType` arm.
- `GraphitronSchemaValidator.validateTableInputType`: becomes `validateInputArg`, dispatches on `effectiveTable()` presence. **R221 absorbed:** the validator now walks `arg.classifiedFields()` uniformly across all `InputTypeArg`s; the `PlainInputArg.fields()` vs `TableInputType.inputFields()` split that motivated R221 has retired in Phase 2-3.
- `CatalogBuilder` four sites: each reads from `Input` or `InputTypeArg`.
- `InputRecordGenerator`, `InputTypeGenerator`: read `Input.recordShape()` and `Input.schemaType()` directly; permit-identity dispatch retires.

Compiler exhaustiveness on the sealed `Input` interface is the safety net for each migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or any of the four `*InputType` permits directly.

### Phase 5: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete the `HasInputRecordShape` capability marker (the slot lives on `Input` directly).
- Delete `ArgumentRef.InputTypeArg.TableInputArg` and `PlainInputArg`.
- Delete the Phase 1-2 adapter shims.

Acceptance: build green; one PR worth of deletions; nothing references the old permits.

### Phase 6 (picks up R97's residue + retires `@value`)

Two per-input directives whose semantics belong elsewhere come out in one sweep: `@table` (handled by Phase 3's runtime ignore, finished here on the schema-declaration side) and `@value` (mechanism replaced by PK-derivation in Phase 4, finished here on the directive-declaration side).

- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive. Each removal silences one `BuildWarning`; nothing else changes.
- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`. The directive declaration becomes structurally unable to land on `INPUT_OBJECT`; any consumer schema that still carries `@table` on an input fails to parse with the standard graphql-java location error.
- Closes R97.
- Migrate every `@value`-decorated SDL input field across Sakila (`FilmUpdateInput.title`, `FilmUpdateInput.description`) and any fixture schemas to drop the directive. Both Sakila annotations sit on non-PK columns; the partition outcome is identical with or without them.
- Delete the `@value` directive declaration from `directives.graphqls`; delete `BuildContext.DIR_VALUE` / `ARG_VALUE` and the `assertDirective(ctx, DIR_VALUE)` registration in `GraphitronSchemaBuilder`. Delete `docs/manual/reference/directives/value.adoc`. After this delete, any consumer schema still carrying `@value` fails to parse with the standard graphql-java "unknown directive" error.

Can land independently after Phase 5; not a blocker for the structural pivot. The "optional" framing from earlier drafts is dropped: with the runtime ignore live from Phase 3 (for `@table`) and the partition mechanism already PK-derived in Phase 4 (for `@value`), leaving the warnings and the now-redundant directive declarations indefinitely is its own cost.

## Dependencies and sequencing

- **R215** (`column-binding-at-classification-not-usage`, Done at `131e0df`): the `UnboundField` deferral R215 introduced is the same column-coverage relaxation the pivot generalises to "no per-input-type column classification at all." Two `@LoadBearingClassifierCheck` keys R215 shipped (`input-field.unbound-implies-no-column` and `input-field.unbound-with-override-condition-admits-on-mutation-update-delete`) name `BuildContext.classifyInputFieldInternal` as the producer; under this pivot's Phase 3 the producer site moves into the visitor walk. `LoadBearingGuaranteeAuditTest` does not detect producer drift (only missing producers), so Phase 3 carries an explicit commitment to migrate both annotation producer-sites to the new visitor entry point and to re-run the audit per phase. With R215 in tree the precondition work is done; the annotation continuity is the only remaining build-order concern.
- **R94** (shipped): the `HasInputRecordShape` slot R94 lifted onto every input variant becomes a direct field on `Input`. No new prerequisite; the new `input.record-shape-derived-from-backing` key formalises the invariant R94 left in prose.
- **R166 Phase 1** (reachability slot): orthogonal. The visitor scaffolding this item lands can be reused by R166 Phase 1, or R166 Phase 1 can land standalone first and this item piggybacks. Spec-stage call.
- **R164**: this item is the *partial* proof-of-concept. What transfers cleanly: the dimensional decomposition discipline (one sealed sub-taxonomy per dimension, not one sealed sub-taxonomy of the cross product), the phased adapter approach, the consumer-migration ordering, the `@LoadBearingClassifierCheck` continuity. What does **not** transfer: the two-level split (`Input` consumer-independent / `InputTypeArg` per-occurrence) is specific to input usage; fields are properties of the SDL field declaration with no per-consumer level to lift onto. R164's authors should model on the dimensional and phasing halves, not on the two-level split.

Likely scope: 1-2 weeks of focused work, somewhat smaller than R164's 2-4 weeks because the consumer surface is narrower and the existing model is already mid-erosion (R215 did the precondition work).

## Vocabulary

- **`Input`** (consumer-independent) vs **`InputTypeArg`** (per-consumer occurrence). The two-level distinction is explicit in the names so readers don't conflate "the SDL input declaration" with "the input at this call site."
- **`BackingClass`** replaces the four `*InputType` permit names. Reading `input.backingClass()` directly answers "which Java class materialises this input?", which is what consumers of that signal always wanted.
- **`InputFieldDecl`** is the SDL field declaration on `Input`, pre-binding. Carries name, type, source location, and the wrapped `GraphQLInputObjectField` so the classifier can read applied directives at `Input.classifyAgainst` time. Distinct from `InputField` (post-classification carrier): `Input.classifyAgainst(table)` is the bridge.
- **`Input.classifyAgainst(Optional<TableRef>)`** is the load-bearing producer of the per-field classified list. Pure function of `(input, table)`; empty table returns the empty list (invariant `input.classify-against-empty-table-returns-empty`). Per-arg consumers read via `InputTypeArg.classifiedFields()`, which delegates. Nothing caches; the derivation is the model.
- **`ConsumerBinding`** (`TableRef` + `SchemaCoordinate`) is the per-arg consumer-side table binding. Populated iff the enclosing field's `@table` return drives the inference; the coordinate is recorded for diagnostics and LSP hover. There is no payload-less sibling: when the backing class supplies the table, it lives on `BackingClass.JooqTableRecord.table` (consumer-independent) and the arg's `consumerBinding` slot stays empty unless the consumer also asserts a table.
- **`InputTypeArg.effectiveTable()`** centralises the two-source compose: consumer wins when present, backing-class fills in otherwise. Every downstream consumer reads this accessor; nobody re-implements the predicate.
- **`ArgumentOccurrence`** is the visit-site carrier (coordinate + `InputTypeArg`). Consumers that need the coordinate (LSP hover, diagnostics) read through it; consumers that don't read `InputTypeArg` directly. The coordinate cannot drift from the arg because the arg doesn't carry one.
- "Table-bound input" prose retires: in code, the predicate is `arg.effectiveTable().isPresent()`; in prose, "input arg with a resolved effective table."

## Out of scope

- **Field-side dimensional pivot**: R164. This item validates the pattern; R164 applies it to the field surface.
- **`ServiceCatalog` predicate consolidation**: R220 / R193. Same disease in a different file.
- **`argMapping` grouping syntax**: R97 Phase 1. Separable; the per-arg classification slot the pivot introduces is the natural home for the grouping outcome, but the syntax and parser work is independent.
- **Visitor-driven emission for non-input types**: broader R166. The input-side slice is what this item absorbs.
- **Reachability pruning across all type kinds**: R166 Phase 1. Orthogonal; this item only prunes inputs via the visit gate.

## Risk

- **Per-arg classification multiplies work for inputs reused across many consumers.** A filter input used by ten queries today is classified once eagerly; under the pivot, `Input.classifyAgainst(effectiveTable())` is called once per `InputTypeArg` occurrence (and again on every consumer-side access to `arg.classifiedFields()` unless the consumer locally caches). Mitigation 1: the per-call work is a lookup-per-SDL-field against the table's column set, typically low tens of fields; profile before optimising. Mitigation 2: if profiling shows a hotspot, memoise on `Input` via `Map<TableRef, List<InputField>>` keyed by the effective table; the cache lives on `Input` (consumer-independent) and survives across `InputTypeArg` occurrences that share the same effective table. The spec does not bake this in; the derivation is the model.
- **Phases 1-3 keep both models alive simultaneously; the adapter is a deferred-deletion liability.** Mitigation: phases 4-5 are scheduled with the same urgency as the rest; the adapter is gone within the same release window.
- **`ArgumentOccurrence` adds a level of indirection at every consumer that wants both the coordinate and the arg.** Most consumers do not (DML emission reads only `InputTypeArg.effectiveTable()`); the LSP and diagnostics surfaces are the population that consumes the wrapper. Mitigation: the wrapper is a record-of-two, the cost is one field-access per consumer that uses it, and the integrity win (the coordinate cannot lie about which visit produced the arg, because the arg doesn't carry one) is the structural payoff the architect read flagged.
- **Test coverage gap: the cross-consumer divergence case has no fixture today.** Mitigation: add the fixture in Phase 3 as acceptance.

## Tests

- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `BuildWarning` entries for `@table`-decorated SDL inputs, which the test asserts on as part of the deprecation-warning fixture below).
- **Pipeline-tier (new):** consumer-derived path. Input with no `@table` directive used by a single `@table`-returning consumer produces an `InputTypeArg` with `consumerBinding.isPresent()`, the consumer's `SchemaCoordinate` on `consumerBinding.get().origin()`, and an empty backing class (or a non-`JooqTableRecord` one). `arg.effectiveTable()` reads the consumer's table. R97 Phase 2's acceptance fixture lands here.
- **Pipeline-tier (new):** `@table` on input emits `BuildWarning`. A fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer produces a `BuildWarning` at the directive's `SourceLocation` with the deprecation message, and the resulting `InputTypeArg.consumerBinding` carries the consumer-derived table (same as if the directive were absent). The R97 Phase 3 runtime-ignore decision is pinned here.
- **Pipeline-tier (new):** backing-class-vs-consumer disagreement. A `JooqTableRecord`-backed input used by a consumer returning a *different* `@table` type produces an `UnclassifiedArg` with `Rejection.AuthorError` rather than silently picking a path. Pins invariant `input-arg.consumer-binding-aligns-with-backing-table`.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two consumers with different return tables produces two `InputTypeArg` occurrences with distinct `consumerBinding` slots. Today's `InputType` collapse becomes per-arg success.
- **Pipeline-tier (new):** unreached input. An SDL input declared but never referenced from any reachable consumer produces an `Input` record with no `InputTypeArg`; `InputRecordGenerator` still emits, downstream classifiers see no per-arg work.
- **Unit-tier (new):** `Input.classifyAgainst(Optional.empty())` returns `List.of()` regardless of the input's SDL fields. Pins invariant `input.classify-against-empty-table-returns-empty` against the producer site directly (rather than against the derived `arg.classifiedFields()` accessor); the unit harness avoids constructing an `InputTypeArg` so the test isolates the producer from the wrapper. Companion: `Input.classifyAgainst(Optional.of(table))` produces an `InputField` per `Input.fields()` entry, with miss → `UnboundField` and hit → the column-bound arm; equality against the legacy `BuildContext.classifyInputFieldInternal` output for the same `(input, table)` pair is the regression anchor that survives the producer-site move in Phase 3.
- **Pipeline-tier (new):** federation entity-fetch boundary. An SDL input referenced only by an `_Entity` resolver (not by any operation-root field) produces an `Input` record with no `InputTypeArg`; the existing `EntityFetcherDispatch.resolveByReps` boundary handles wire decoding unchanged. Pins the federation-seed-set decision named in §Visitor-driven classification consequence (4).
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required by the pivot itself.

## Architectural principle this codifies

R164 frames the disease: a sealed hierarchy that tries to represent multiple independent dimensions through a single permit set. The cross product is the permit set; adding a value to any axis multiplies the permits below it; the leaves carry redundant or divergent encodings of the same axis.

The cure is the same in both organs: separate the dimensions onto orthogonal slots, with a sealed sub-taxonomy *per dimension* rather than a sealed sub-taxonomy of the *cross product*. This item ships the cure on the input surface where the cross product is smallest (5 permits encoding 3 axes), then R164 ships the same cure on the field surface where it's largest (46 permits across 3 sealed hierarchies encoding 5+ axes). R220 / R193 then apply it to the consumer-side classifier predicates.

The principle is not "minimise the model"; it is "make the model honest about what it's saying." The cross-product encoding hides axes; the dimensional encoding surfaces them. Hidden axes drift, surfaced axes get the compiler's help.
