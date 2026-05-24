---
id: R222
title: "Input model: walker-driven carriers on output fields"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-24
---

# Input model: walker-driven carriers on output fields

The input-type classification surface has been the most-churned model area in the rewrite over the last quarter: R94, R96, R150, R155, R178, R191, R205, R210, R211, R215 all landed surgical patches on it, and Backlog still carries R171, R97, R213, R209, R200, R195, R98, R220, R193, R172 on the same surface. R164 (`field-model-two-axis-pivot`) names the underlying disease one layer over: a sealed hierarchy that tries to encode a multi-dimensional space as a one-dimensional permit set.

Earlier R222 drafts pivoted to a recursive `InputUsage` carrier, scoped to SQL emission. Review found the carrier was still the wrong granularity: it folded distinct consumer concerns (WHERE construction, DML row-shaping, lookup-key identification, method-param binding, pagination, ordering) into one classification output that consumers then re-discriminated. The dimensional pivot lands further: each consumer concern is its own *carrier*; each carrier is produced by an independent *walker* over the SDL; the output field carries the carriers as fixed-meaning Optional slots. No top-level sealed hierarchy. No shared recursive tree. No common classification carrier.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits encode three independent axes (backing-class kind, table-binding and where it came from, eager column classification) onto one identity slot. The cross product is sparse: the table-bound × non-jOOQ-TableRecord-backed × eager-classified cell is `TableInputType`; the table-bound × jOOQ-TableRecord-backed cell is `JooqTableRecordInputType`; the unbound row has four permits that differ only on backing-class kind.

`TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` — an O(N) back-scan over all schema fields. Whether an SDL input is "table-bound" is *already* a property of the consumer, not the input declaration. R215's lift (Done) admits `InputField.UnboundField` into `TableInputType.inputFields()`, collapsing the "eager classification" axis.

Nine consumer sites discriminate on the split today: `GraphitronSchemaValidator.java:80-81`, `MutationInputResolver.java:368`, `EnumMappingResolver.java:303`, `CatalogBuilder.java:206 / 498 / 570 / 639`, `FieldBuilder.java:967`, `TypeBuilder.java:209-210`. Each asks "does the input have a table?" or "what's its backing class?" by switching on permit identity, then proceeds.

R171 (Backlog) proposes a sealed `InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact. The deeper issue: classification produces one heterogeneous output that every consumer then re-discriminates by role. The cure is to surface the roles directly and have classification produce them in parallel.

## What's to be: walkers populate carriers; carriers live on output fields

- **Output fields gain carrier slots.** Each output field with arguments carries an `Optional<X>` slot per carrier type. A slot is present iff the field's classification ran the corresponding walker and the walker produced a result.
- **Walkers are independent.** Each walker takes a `GraphQLFieldDefinition` plus an event sink and produces exactly one carrier of its own type (or none). Walkers read `graphql.schema.*` primitives directly; they do not consult a shared graphitron-internal classification model.
- **The output field elects its walkers.** During classification, the field's directive set + return-type shape drive walker dispatch procedurally. No top-level sealed family; impossible combinations are excluded at dispatch.
- **Walkers are independently unit-testable.** Parse a small SDL fragment, get a `GraphQLFieldDefinition`, run the walker, assert on the carrier and sink contents. Pipeline coverage comes as a by-product when downstream emitters consume the carriers.
- **Schema warnings and errors flow through a side channel.** The carrier shape is no longer 1-1 with the SDL; walkers write structured `ClassifierEvent`s to a parallel sink. Diagnostics carry `SourceLocation` and reason; the sink drains post-walk to surface warnings (BuildContext) and errors (Rejection).

### The carrier vocabulary

| Carrier | Purpose | Internal shape |
|---|---|---|
| `Pagination` | first/after/last/before (keyset) or offset/limit; consumer emits ORDER BY + LIMIT + keyset predicate | flat |
| `Ordering` | `@orderBy`-family directives; consumer emits ORDER BY | flat |
| `PredicateCarrier` | predicates that filter (`Condition`) or identify rows to act on (`LookupRows`) | sealed: `Condition \| LookupRows`, both flat |
| `MethodArguments` | param bindings for a Java method call (`@service`, `@externalField`, `@tableMethod`'s table-returning call) | list of per-param `MethodArgumentBinding`; R164 carries internal variants |
| `InsertRows` | row plans to INSERT; compound (R122) carries parent + FK-threaded children | tree-shaped, R122 carries internal shape |
| `UpdateRows` | column-value bindings to write | flat |

**PredicateCarrier's two arms** share predicate shape but differ in semantic role. `Condition` is the default for SQL-emitting *read* fields; `LookupRows` is the default for *mutation* fields. The walker's bailout-restart pattern handles role-discovery lazily: start with the default arm's accumulator; if a sentinel directive shows up anywhere in the walk, discard and restart with the other arm. Sentinels:

- `@lookupKey` on a read field (or on a nested input field used by a read) → flip to `LookupRows`
- `@multirows` (working name) on a mutation field → flip to `Condition`

The carrier sealing lives inside the predicate-binding axis, not at the field level. The walker chooses the arm by construction; consumers pattern-match the arm at use time.

**MethodArguments' internal shape.** R164 territory. The carrier holds an ordered list of per-param bindings; each binding is one of: scalar passthrough, decoded NodeID, `BackingClass`-populated. R222 reserves the carrier; R164 ships the binding-variant sealed family and the `BackingClass` attachment.

**InsertRows' internal shape.** R122 territory. The simple INSERT is the one-node degenerate case of a tree; compound INSERTs (parent + FK-threaded children) carry the full tree with FK descriptors. R222 reserves the carrier; R122 ships the tree and the FK threading.

### The walker abstraction

Each walker is a reducer:

```java
interface Walker<C> {
    /**
     * Reduce the field's args + nested input contents into a single carrier.
     * Returns Optional.empty() iff the walker found nothing to produce (e.g.
     * Pagination on a field with no pagination args). Writes any diagnostics
     * (warnings, deferred rejections) to the event sink.
     */
    Optional<C> walk(GraphQLFieldDefinition field, ClassifierEventSink sink);
}
```

The carrier type `C` is the walker's product. Walkers are built on `graphql.schema.GraphQLArgument` / `GraphQLInputObjectField` / `GraphQLInputType` directly; the walk reads `getDirectives()`, `getType()`, and field-type-introspection methods. The walker's logic is "look at each arg, look at each nested input field, accumulate into the typed accumulator." No graphitron-internal recursive model is required.

**Bailout-restart.** The predicate walker illustrates:

```java
class PredicateWalker implements Walker<PredicateCarrier> {
    private final boolean defaultIsLookupRows;  // true for mutations, false for reads

    public Optional<PredicateCarrier> walk(GraphQLFieldDefinition field, ClassifierEventSink sink) {
        var primary = defaultIsLookupRows ? new LookupRowsAccumulator() : new ConditionAccumulator();
        var bailout = walkInto(field, primary, sink);
        if (bailout.isEmpty()) return primary.buildIfNonEmpty();

        sink.add(new PredicateRoleSwitched(bailout.get().location(), primary.armName(), ...));
        var secondary = defaultIsLookupRows ? new ConditionAccumulator() : new LookupRowsAccumulator();
        walkInto(field, secondary, sink);
        return secondary.buildIfNonEmpty();
    }
}
```

The bailout signal is the sentinel directive; the walker's outer loop doesn't pre-scan. Role discovery is structural — sentinel triggers restart, no separate role-decision pass.

**Unit-testability is load-bearing.** Each walker takes graphql-java primitives in and produces a carrier + diagnostic events out — a pure function with no graphitron classification context required. The unit-test surface:

```java
@Test
void condition_walker_reads_at_condition_directive_on_input_type() {
    var field = parseField("""
        type Query { films(filter: FilmFilter!): [Film] @table(name: "film") }
        input FilmFilter @condition(name: "film_in_genre", override: false) {
            genre: String
        }
    """);
    var sink = new ClassifierEventSink();
    var result = new ConditionWalker().walk(field, sink);

    assertThat(result).isPresent();
    assertThat(result.get().predicates()).contains(
        new MethodSuppliedPredicate("film_in_genre", List.of("genre"))
    );
    assertThat(sink.events()).isEmpty();
}
```

Pipeline-tier tests follow as by-product; the walker's own tests do not depend on downstream emitters.

### Walker dispatch

The output field decides which walkers to run from its own properties:

| Field shape | Walkers elected |
|---|---|
| Plain read (`Query.x`, no `@service`) | Pagination?, Ordering?, PredicateCarrier? |
| Read with `@service` | MethodArguments (total — short-circuits all others) |
| Read with `@externalField` | MethodArguments (total) |
| Read with `@tableMethod` | MethodArguments, Pagination?, Ordering? |
| INSERT mutation | InsertRows |
| UPDATE mutation | PredicateCarrier, UpdateRows |
| DELETE mutation | PredicateCarrier |
| UPSERT mutation | InsertRows, PredicateCarrier |
| `@service` mutation | MethodArguments (total) |

Dispatch is a procedural decision: read the field's directive set + return-type shape, decide which walkers to invoke, pass them the field, collect carriers into the output field's slots. `@service` and `@externalField` are *total*: they short-circuit to MethodArguments only, regardless of position. Constraints between walkers (e.g. "@service excludes everything else") live in the dispatch logic, not in a sealed type.

Adding a new carrier (Aggregation, GroupBy, future axes) is additive: new walker, new slot on Output, new dispatch rule. No enclosing taxonomy churns.

### Schema warnings and errors: the side channel

The walker model breaks SDL-shape 1-1 correspondence in several places:

- A walker drops a directive that is no longer load-bearing (`@table` on input, `@record(class:)` on input, `@value`).
- A walker bails and restarts; the bailout is worth surfacing when its trigger is subtle (e.g. `@lookupKey` deep in a nested input).
- Multiple walkers may surface findings at the same SDL location.
- A walker produces no carrier with a non-empty diagnostic trail (rejected args, missing required directives).

Today's classifier-output channel mixes structured signal (`Unclassified*` carriers) with the actual classification. R222 introduces `ClassifierEventSink` as a parallel diagnostic channel:

```java
sealed interface ClassifierEvent {
    SourceLocation location();
    String reason();
}
record DirectiveDeprecated(SourceLocation location, String directive, String reason) implements ClassifierEvent {}
record DirectiveDropped(SourceLocation location, String directive, String reason) implements ClassifierEvent {}
record PredicateRoleSwitched(SourceLocation location, String fromArm, String toArm, String trigger) implements ClassifierEvent {}
record AuthorError(SourceLocation location, String reason, Optional<String> hint) implements ClassifierEvent {}
record CarrierProducedNothing(SourceLocation location, String walker, String reason) implements ClassifierEvent {}
```

Drainage:

- `DirectiveDeprecated` / `DirectiveDropped` → BuildContext warnings (`TypeBuilder.emitDirectiveIgnoredWarnings`'s existing channel).
- `PredicateRoleSwitched` → BuildContext info-level message; surfaced behind a verbosity flag.
- `AuthorError` → terminates the build with the standard `Rejection.AuthorError` shape.
- `CarrierProducedNothing` → typically silent; surfaced on demand for debugging.

R226 (classification dimensional pivot: diagnostics off the model) is forward-compatible: its eventual `Diagnostic` family substitutes for `ClassifierEvent` when it lands; the walker's call-site signature is the only change.

### Records (the model)

```java
sealed interface Input extends GraphitronType, EmitsPerTypeFile permits Input.Of {
    ValidationShape validationShape();
    GraphQLInputObjectType schemaType();
    List<InputFieldDecl> fields();
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

record InputFieldDecl(GraphQLInputObjectField rawField) {
    public String name()              { return rawField.getName(); }
    public GraphQLInputType type()    { return rawField.getType(); }
    public SourceLocation location()  { return rawField.getDefinition().getSourceLocation(); }
}
```

`Input` is the SDL declaration only: name, location, the assembled-schema form, the SDL-derived per-input-type emit shape (R94's `ValidationShape`), the pre-binding SDL field declarations. No backing class. No table. No classified field list. The per-input emit (`InputRecordGenerator`) reads it for Jakarta-validation type generation.

`InputFieldDecl` wraps `GraphQLInputObjectField` so applied directives are accessible at consumption time. It is purely SDL-shape; no classification, no consumer awareness.

Output field gains carrier slots additively:

```java
// Shape sketch; the slots land on the existing Output/Field records as each
// walker ships, not all at once.
record OutputField(
    // ... existing slots ...
    Optional<Pagination> pagination,
    Optional<Ordering> ordering,
    Optional<PredicateCarrier> predicate,
    Optional<MethodArguments> methodArguments,
    Optional<InsertRows> insertRows,
    Optional<UpdateRows> updateRows
) { ... }
```

Each `Optional<X>` slot is fixed-meaning: present iff the X-walker produced a carrier; absent iff the walker was not elected or produced none. The Optionals do not discriminate context; they record presence. This is the principled use of Optional, distinct from the cross-product collapse the legacy permits exhibit.

The carrier types:

```java
sealed interface PredicateCarrier {
    record Condition(List<Predicate> predicates) implements PredicateCarrier {}
    record LookupRows(List<KeyPredicate> keys) implements PredicateCarrier {}
}

record MethodArguments(List<MethodArgumentBinding> paramBindings) {}
// MethodArgumentBinding is R164's sealed family with arms for scalar passthrough,
// NodeID-decoded, BackingClass-populated, etc.

record Pagination(...) {}
record Ordering(...) {}
record InsertRows(...) {}  // R122 carries the tree shape
record UpdateRows(List<ColumnValueBinding> columns) {}
```

`BackingClass` stays in vocabulary as a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) attached per `MethodArgumentBinding` variant by R164. R222 ships the `BackingClass` family declaration as vocabulary; R164 wires it onto MethodArguments' internal binding arms. `LoadBearingGuaranteeAuditTest` recognises the no-producer state as "awaiting R164" via a `@LoadBearingPlaceholder("R164 method-argument-binding arms")` annotation.

### `@table` and `@record(class:)` on inputs are dropped

The pivot drops two directives as binding sources on `INPUT_OBJECT`. Each is read once at classification, surfaced as a `DirectiveDeprecated` event, then ignored for binding purposes. Phase 6 narrows both SDL directive declarations to drop `INPUT_OBJECT` and sweeps every decorated SDL input across fixtures.

**`@table` on input.** Table-binding sources collapse to one: the enclosing field's `@table` return (consumer-derivation). The SQL-emitting walkers read the consumer's return-type-bound table at the visit site; no input-side directive participates. Sakila's 16 `@table`-decorated inputs all consume at `@table`-returning SQL-form consumers; the Phase 6 sweep confirms no orphans.

**`@record(class:)` on input.** The directive's "type the deserialization target" function collapses entirely. The materialization target is the user's declared service-method param type (`BackingClass`), read by reflection at the MethodArgumentsWalker site — not an input-side directive. The per-`Input` emit produced by `InputRecordGenerator` is for Jakarta-validation use only, not a DTO target.

### Directives the new model carries

| Directive | Pre-pivot home | Post-pivot home |
|---|---|---|
| `@record(class: ...)` on input type | identity of four `*InputType` permits | **ignored**; `DirectiveDeprecated` event at the directive's `SourceLocation`. The materialization target lives on R164's `MethodArgumentBinding` arm. The per-`Input` Jakarta-validation type is emitted from the SDL shape regardless |
| `@table` on input type | `TableInputType.table` / `JooqTableRecordInputType.table` | **ignored**; `DirectiveDeprecated` event at the directive's `SourceLocation`. Binding resolves from the enclosing field's `@table` return at the walker's visit site |
| `@condition` on input *type* | read from the assembled `GraphQLInputObjectType` | unchanged: read by `ConditionWalker` from `Input.schemaType()`; contributes a method-supplied predicate to the `Condition` accumulator |
| `@condition` on input *field* / arg | per-arg / per-field condition | read by `ConditionWalker`; contributes a predicate scoped to that arg/field |
| `@lookupKey` on input field / arg | n/a (new sentinel) | sentinel directive triggering `PredicateWalker`'s bailout from `Condition` to `LookupRows` |
| `@multirows` (working name) on field | n/a (new sentinel) | sentinel directive triggering `PredicateWalker`'s bailout from `LookupRows` to `Condition` on mutation fields |
| `@nodeId(typename: ...)` on input field | read by `NodeIdLeafResolver` on the SQL-side path | universal: `ConditionWalker` / `LookupRowsWalker` read it for FK-target resolution; R164's `MethodArgumentBinding` decoded-NodeID arm reads it for domain-method calls |
| `@reference(path:)` on input field | read by `NodeIdLeafResolver` for `@nodeId` leaves | unchanged on the `@nodeId` path; **R122 extends the directive's reach to nested-input slots** (the path's terminal element drives the FK threading in `InsertRowsWalker`). R222 preserves the directive's existing applicability and does not touch the `@nodeId`-path resolver |
| `@inputBean` / `@enumMap` / `@field(name:)` on input *fields* | read by `InputBeanResolver`, `EnumMappingResolver` | unchanged: those resolvers continue to consult the assembled-schema; R200 / R195 / R98 stay scoped where they are |
| `@value` on input field | read by R144's classifier-side partition | **removed**; partition derived from the target table's PK column set inside `UpdateRowsWalker` |

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | `Input` (no backing-class slot). `BackingClass` retires from `Input`; surfaces on R164's `MethodArgumentBinding` arm |
| `GraphitronType.TableInputType` sibling root | `Input` (no table slot). Table fact lives inside SQL-emitting walkers' carriers (`Condition`, `LookupRows`, `UpdateRows`, etc.), sourced from the consumer's `@table` return at walker-invocation time |
| `JooqTableRecordInputType.table` slot, `TableInputType.table` slot | Both retire. Table for SQL emission lives inside the carrier produced by each walker, derived from the consumer at walker time. Table for MethodArguments lives on R164's per-param `BackingClass.JooqTableRecord.table` (the user's declared param type) |
| `TableInputType.inputFields` (stored, eager-classified) | retires. Classification happens per-walker, per-output-field, at dispatch time. The classified output lives on the walker's carrier, not on `Input` |
| `HasValidationShape` capability marker (5 permits) | direct slot on `Input` |
| `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg` | retire. Args no longer carry classified state; the output field carries the carriers |
| `TableInputArg.lookupKeyFields` / `setFields` slots (R144 DmlKind-driven partition) | retired from the model. `PredicateCarrier` is `LookupRows` for UPDATE/DELETE by default (the partition arm choice); `UpdateRows` carries the SET columns; PK-vs-non-PK derivation lives inside `UpdateRowsWalker` |
| 9 consumer-side discriminations on `TableInputType` (instanceof gates and switch arms) | retire; consumers read the relevant carrier slot directly |
| `TypeBuilder.findReturnTablesForInput` back-scan | retires; the field's `@table` return is available at walker-invocation time per-field |
| `InputField.NestingField`, `InputField.UnboundField`, `InputField` family | refactored. The classification-output `InputField` hierarchy retires from `Input`; each walker carries its own typed accumulator. Where the same fact is needed by multiple walkers (e.g. `@nodeId @reference` decoded once), it is computed inside each walker — graphql-java is the shared substrate, not a graphitron-internal classification model. Walker-internal "column-bound" vs "unresolved" carriers stay typed and sealed inside each walker as needed |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes "validate each carrier the field produced"; per-permit-identity dispatch retires entirely.

## Two example walkers shipped in R222

R222 ships two walkers in tree as exemplars. Together they prove the abstraction: one demonstrates the bailout/restart pattern, one demonstrates the totalising directive. Both are independently unit-testable; both have downstream consumers ready to migrate (Phase 3).

### `ConditionWalker`

Produces a `PredicateCarrier.Condition` (or, on `@lookupKey` bailout, a `LookupRows`) for SQL-emitting read fields. Walks the field's args; for each arg, examines its directives (`@condition`, `@lookupKey`) and, if the arg is an INPUT_OBJECT, recurses into its fields. Accumulates predicates into the carrier.

Bailout: any `@lookupKey` encountered (on an arg or on a nested input field) triggers a restart with `LookupRowsAccumulator`. The sink records a `PredicateRoleSwitched` event with the trigger location.

```java
public Optional<PredicateCarrier> walk(GraphQLFieldDefinition field, ClassifierEventSink sink) {
    var primary = new ConditionAccumulator();
    var bailout = walkInto(field, primary, sink);
    if (bailout.isEmpty()) return primary.buildIfNonEmpty();

    sink.add(new PredicateRoleSwitched(bailout.get().location(),
        "Condition", "LookupRows", "@lookupKey"));
    var secondary = new LookupRowsAccumulator();
    walkInto(field, secondary, sink);
    return secondary.buildIfNonEmpty();
}
```

Unit-test surface: `@condition` on input type / input field / arg, plain input fields, nested-input recursion, `@lookupKey` bailout (at each nesting depth), unresolved column → `CarrierProducedNothing`, `@condition + @lookupKey` on the same field (error event). Each case is an SDL fragment + walker invocation + carrier/sink assertion.

### `MethodArgumentsWalker`

Produces a `MethodArguments` for fields carrying `@service`, `@externalField`, or `@tableMethod`. Walks the field's args; for each arg, produces a `MethodArgumentBinding` placeholder (R164 lands the binding-variant family; R222 produces a single uniform `MethodArgumentBinding.Pending` arm or equivalent).

The walker is *total* for its electing directives: when invoked, it consumes all the field's args. The dispatch logic ensures it is the only walker invoked on `@service` / `@externalField` fields.

```java
public Optional<MethodArguments> walk(GraphQLFieldDefinition field, ClassifierEventSink sink) {
    var bindings = new ArrayList<MethodArgumentBinding>();
    for (var arg : field.getArguments()) {
        bindings.add(bindArg(arg, sink));   // R164 specialises the binding kind
    }
    return bindings.isEmpty() ? Optional.empty() : Optional.of(new MethodArguments(bindings));
}
```

Unit-test surface: scalar arg, nested-input arg, mixed scalar+nested args, `@nodeId`-decorated arg, conflicting directives (e.g. `@condition` on an arg of a `@service` field — `AuthorError`), no args (`CarrierProducedNothing`). The R164 contract is exercised by extension tests R164 will add; R222's tests pin the walker's structural correctness against today's `MethodArgumentBinding.Pending` arm.

## Walkers not shipped in R222 (sibling items)

| Walker | Sibling item | Internal shape |
|---|---|---|
| `LookupRowsWalker` (paired with `ConditionWalker`) | new Backlog item | flat predicate list, `@lookupKey`/`@nodeId` triggered |
| `PaginationWalker` | new Backlog item | first/after/last/before slots |
| `OrderingWalker` | new Backlog item | `@orderBy`-family directive readout |
| `InsertRowsWalker` | **R122** (`compound-entity-mutations`) | tree of row plans + FK threading |
| `UpdateRowsWalker` | **R144 continuation** | column-value bindings; PK-derivation for the partition arm |

R222 reserves the carrier slot on Output for each but does not populate. Migration is walker-by-walker: each sibling item ships its walker + unit tests + the corresponding consumer migration, independently.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves: `Input` is the sole input-like type; capabilities are direct slots; the parent-root fix becomes moot |
| R97 (deprecate `@table` on input types) | Phase 2 / 3 fall out structurally; Phase 6's directive narrowing + fixture sweep closes the item. `argMapping` grouping (R97 Phase 1) remains separable |
| R213 (rejections at consumer field) | Walker-time `SourceLocation` is `InputFieldDecl.location()` / arg's source location |
| R209 (FieldRegistry classify-input trace) | Typed rejection at walker time; `Rejection.AuthorError.UnknownName` flows through `ClassifierEventSink` |
| R166 Phase 2 (`InputTypeGenerator` under visitor) | Subset: the input-side slice. The walker invocation is the visitor pass |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves: the validator walks each output field's carriers uniformly; no per-permit dispatch survives |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker) | Two reversals. (1) The partition lives in `PredicateCarrier`'s `Condition`/`LookupRows` arm choice (UPDATE/DELETE default to `LookupRows`; `@multirows` flips to `Condition`). (2) `@value` is dropped; `UpdateRowsWalker` derives the SET columns by subtracting the PK column set from the column-bound carriers. R144's cardinality-safety surface (PK-coverage check, `multiRow` opt-in) survives unchanged in shape |
| R215 (column-binding at classification, not usage) | Subsumed: column binding happens inside each SQL-emitting walker (`ConditionWalker`, `LookupRowsWalker`, `UpdateRowsWalker`) at the walker's leaf-resolution step. R215's `UnboundField` admit set translates per-walker into the walker's own "unresolved" arm |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164**: a contract partner, not just downstream. R222 ships `MethodArgumentsWalker` with a placeholder binding arm; R164 ships the `MethodArgumentBinding` sealed family (scalar passthrough, decoded NodeID, `BackingClass`-populated, etc.) plus the per-param attachment. R222 reserves the slot; R164 populates it.
- **R98** (multi-source input validation): once `ValidationShape` is a direct slot on `Input`, attaching `ConstraintSet` is a one-site change. Follow-up.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): naming binding between SDL fields and Java members on a backing class. The walker model doesn't change the naming resolution; both items stay scoped where they are.
- **R122** (compound-entity-mutations): structurally enabling. R122 ships `InsertRowsWalker` whose internal tree carries the parent + FK-threaded children. R222 reserves the carrier; R122 owns the walker and the emitter.
- **R226** (classification dimensional pivot: diagnostics off the model): forward-compatible by design. R222's `ClassifierEventSink` is the prototype shape; R226's eventual `Diagnostic` family substitutes for `ClassifierEvent` when it lands. The walker call-site is the only change.

## Why R144's partition reverses (under the walker model)

R144 committed the WHERE-vs-SET partition at classify time on the legacy carrier (`TableInputArg.lookupKeyFields` / `setFields`). Two problems show up under the walker pivot.

First, the partition is a property of the *consumer*, not of the input. UPDATE's "WHERE" is `LookupRows`; UPDATE's "SET" is `UpdateRows`. Each is its own walker, producing its own carrier. The consumer reads the slot it needs; nothing classifies "WHERE-vs-SET" on the input side.

Second, the `@value` marker becomes redundant. `UpdateRowsWalker` walks the field's args; for each column-bound input field, the walker checks whether the column is in the target table's PK column set (sourced from `JooqCatalog`). Non-PK columns go into `UpdateRows`; PK columns are claimed by `LookupRowsWalker` for the same field. The two walkers split the SDL columns by PK-membership, derived from the catalog. No per-input-field marker needed.

R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) survives unchanged in shape: the trigger is the catalog's PK column set examined by `UpdateRowsWalker` / `LookupRowsWalker`, not the `@value` complement.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical. The Phase 6 fixture sweep drops them.

## Cross-axis invariants

The dimensional split admits states the old model could not. Three load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key. Producer is the named walker; consumers are the per-carrier-slot readers on Output.

1. **`walker.produces-only-its-own-carrier-type`**: each `Walker<C>` produces only `Optional<C>` (or events) — never another walker's carrier. Pinned per walker; load-bearing because dispatch logic relies on it (no walker silently populates a slot it does not own).

2. **`output-field.carrier-slot-presence-iff-walker-elected`**: `OutputField.predicate().isPresent()` iff `PredicateWalker` was in the elected set for that field and produced a result. Same for every slot. Pinned at the dispatch site; the audit walker catches a producer that bypasses dispatch.

3. **`per-type-emit-ignores-walker-output`**: `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never any walker's carrier); per-output-field consumers read only the relevant carrier slot (never bypass to walk the input themselves). Pinned with `@DependsOnClassifierCheck` on both per-type generator entry points.

**Retirements:**

- The legacy `input.record-shape-derived-from-backing` key retires entirely with `Input`'s backing-class-slot removal. `ValidationShape` is purely SDL-derived now.
- The earlier draft's `input-usage.*` keys retire with `InputUsage`'s removal. The walker model produces no shared recursive carrier; per-walker invariants replace the recursive-tree invariant.
- The earlier draft's `r222-stand-in-form-from-directive-set` key retires with the field-level form discriminator. Walker dispatch reads the directive set directly; no stand-in is in play.

## Phasing

Six phases, each independently shippable and individually reversible. The change is *additive* through Phase 4; legacy code retires in Phase 5 once all consumers have migrated.

### Phase 1: introduce `Input`, `InputFieldDecl`, the carrier vocabulary, the walker abstraction, `ClassifierEventSink`

- Add `sealed interface Input`, `InputFieldDecl` to `model/`.
- Add the carrier vocabulary: `PredicateCarrier` (sealed `Condition`/`LookupRows`), `MethodArguments`, `Pagination`, `Ordering`, `InsertRows`, `UpdateRows`. Each is a record (or sealed family for `PredicateCarrier`). R164's `MethodArgumentBinding` and R122's tree shape land in their respective items; R222 ships `MethodArguments(List<MethodArgumentBinding.Pending>)` as the placeholder body.
- Add `interface Walker<C>` and `ClassifierEventSink`. Define the `ClassifierEvent` sealed family.
- Add `BackingClass` family (`Pojo`, `JavaRecord`, `JooqTableRecord`) with `@LoadBearingPlaceholder("R164 method-argument-binding arms")` so the audit doesn't flag the dead carrier.
- **Rename R94's `InputRecordShape` → `ValidationShape`** and the slot `recordShape` → `validationShape` on every input variant. The capability marker renames from `HasInputRecordShape` → `HasValidationShape` in lockstep (it retires entirely in Phase 5).
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- Add empty `Optional<X>` slots for each carrier type on the existing Output record. Compiler-visible, no consumer behavior change.
- No walker ships yet; no consumer reads the new slots.

Acceptance: model additions compile; every existing input today produces an equivalent `Input` from the type map; the per-input emit is unchanged.

### Phase 2: ship `ConditionWalker` and `MethodArgumentsWalker` with unit tests

- Implement `ConditionWalker`: walks args + nested inputs, accumulates predicates, bails to `LookupRowsAccumulator` on `@lookupKey`. The bailout target is in-tree even though `LookupRowsWalker` ships as a sibling item — the accumulator's class is the bailout's product, not a separate walker call.
- Implement `MethodArgumentsWalker`: walks args, produces `MethodArguments(List<MethodArgumentBinding.Pending>)`. The per-param specialization (decoded NodeID, BackingClass-populated, scalar passthrough) is R164's; R222's body produces uniform `Pending` bindings.
- Unit-test surface (the load-bearing demonstration of the pivot): each walker has a test class with one test per SDL shape variation. Tests are pure-graphql-java: parse a fragment, run the walker, assert on carrier and sink contents. No graphitron classification context is constructed.
- Add walker-dispatch logic to the existing classification pass: SQL-form read fields elect `ConditionWalker`; `@service` / `@externalField` fields elect `MethodArgumentsWalker` exclusively. Other walkers are not yet elected (slots stay `Optional.empty()`).
- The two new load-bearing keys land here: `walker.produces-only-its-own-carrier-type` on each walker; `output-field.carrier-slot-presence-iff-walker-elected` on the dispatch site.
- **Anchor one consumer in the same phase** so the producer-consumer chain is live before the phase boundary. Chosen anchor: `EnumMappingResolver.buildLookupBindings` migrating to read `OutputField.methodArguments()` for `@service` fields and `OutputField.predicate()` for SQL-form fields. Two legacy callers (`MutationInputResolver.java:433`, `FieldBuilder.java:974`) keep their existing call sites on the legacy walk; the anchor consumer reads the new slots through a sibling overload. Both overloads delegate into a shared private body so the binding logic lives in one place.
- `@table` and `@record(class:)` on input emit `DirectiveDeprecated` events at this phase; they're already ignored for binding (no walker reads them).

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged. Cross-consumer divergence fixture (same input used at two consumers with different return tables) works because each walker invocation is per-output-field, not per-input-type. Walker unit tests are green and demonstrate the pivot's testability claim.

### Phase 3: migrate remaining consumers to read carrier slots

Move each remaining consumer off legacy permit discrimination onto the new carrier slots. Order chosen to keep blast radius small per PR:

- `MutationInputResolver`: reads `field.predicate()` for the WHERE arm and (once `UpdateRowsWalker` ships as a sibling item) `field.updateRows()` for the SET arm. **R144 partition relocated + `@value` retired:** the DmlKind-aware partition is the `PredicateCarrier` variant (`LookupRows` for UPDATE/DELETE default, `Condition` after `@multirows`). Two load-bearing keys swap on this site: `mutation-input.update-set-fields-equal-value-marked` retires, `mutation-input.update-partition-by-pk-membership` replaces it (PK-column carriers in `LookupRows`, non-PK in `UpdateRows`, derived inside the walkers). R144's `mutation-input.where-columns-cover-pk` key stays.
- `FieldBuilder.classifyInputFieldOnArg`: drops the `instanceof TableInputType` arm; reads relevant carrier slot per consumer site.
- `GraphitronSchemaValidator.validateTableInputType`: becomes `validateOutputFieldCarriers`; receives `OutputField` and walks each populated slot. **R221 absorbed**: the validator walks carriers uniformly; the legacy permit-identity dispatch retires.
- `CatalogBuilder` four sites: read carrier slots per site.
- `InputRecordGenerator`, `InputTypeGenerator`: read `Input.validationShape()` and `Input.schemaType()` directly; permit-identity dispatch retires. These two consumers do *not* touch any carrier; `per-type-emit-ignores-walker-output` invariant pins the boundary.

Compiler exhaustiveness on the sealed `Input` interface is the safety net for each migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or the four `*InputType` permits directly.

### Phase 4: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete `HasValidationShape` capability marker (slot lives on `Input` directly).
- Delete `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg`. Args no longer carry classified state.
- Delete the Phase 1-2 adapter shims.
- Delete `TypeBuilder.findReturnTablesForInput` and the `Map<String, TableRef>` cache it builds.

Acceptance: build green; nothing references the old permits; the legacy classification surface is gone.

### Phase 5: sibling walkers ship (out of R222's scope, but R222 reserves the slots)

`LookupRowsWalker`, `PaginationWalker`, `OrderingWalker`, `UpdateRowsWalker`, `InsertRowsWalker` (R122) each ship in their own item. Each follows the same shape: walker + unit tests + consumer migration. R222 has reserved the carrier slot on Output; the sibling item populates it.

### Phase 6: directive narrowing + fixture sweep

Three per-input directives whose semantics belong elsewhere come out in one sweep:

- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive.
- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`.
- Closes R97.
- Migrate every `@record(class:)`-decorated SDL input type across Sakila, fixtures, and LSP fixtures to drop the directive.
- Narrow the SDL `@record` directive's scope to exclude `INPUT_OBJECT`.
- Migrate every `@value`-decorated SDL input field across Sakila and any fixture schemas to drop the directive.
- Delete the `@value` directive declaration; delete `BuildContext.DIR_VALUE` / `ARG_VALUE` and the registration in `GraphitronSchemaBuilder`. Delete `docs/manual/reference/directives/value.adoc`.

Can land independently after Phase 4; not a blocker for the structural pivot.

## Dependencies and sequencing

- **R215** (Done): the `UnboundField` deferral generalises to the walker model — each SQL-emitting walker's leaf-resolution step admits an "unresolved" arm with the same semantics. The two existing `@LoadBearingClassifierCheck` keys move into the walker bodies (`ConditionWalker.resolveLeaf` for `input-field.unbound-implies-no-column`; `MutationInputResolver` keeps `input-field.unbound-with-override-condition-admits-on-mutation-update-delete` on the verb-admission decision). No further build-order concern.
- **R94** (shipped): `HasValidationShape` becomes a direct slot on `Input`. The R94 invariant (validationShape derived from backing class) retires because `Input` no longer carries a backing class.
- **R166 Phase 1** (reachability slot): orthogonal. The walker invocations are output-field-driven; reachability can layer over without coupling.
- **R164** (field-model dimensional pivot): contract partner. R222 ships `MethodArgumentsWalker` with `MethodArgumentBinding.Pending`; R164 ships the sealed `MethodArgumentBinding` family (with `BackingClass`-populated, decoded-NodeID, scalar-passthrough arms). R164's authors model on R222's walker + carrier vocabulary; what does **not** transfer is the input-side slot list, which is specific to input-arg classification.

Likely scope: 2-3 weeks of focused work. The walker abstraction and ClassifierEventSink scaffolding is new; the two example walkers + their unit tests are the main lift. The migration phases are mechanical.

## Vocabulary

- **`Input`** — the SDL declaration: name, location, assembled-schema form, ValidationShape, `List<InputFieldDecl>`. Carries no backing class, no table, no classified state. Read by per-type emitters (Jakarta validation) and by walkers as their substrate.
- **`InputFieldDecl`** — the SDL field declaration on `Input`, pre-binding. Wraps `GraphQLInputObjectField` for applied-directive access.
- **`Walker<C>`** — a reducer over the SDL producing a single carrier of type C plus diagnostic events. Built on graphql-java primitives. Independently unit-testable. Stateless (per invocation).
- **Carriers** — `Pagination`, `Ordering`, `PredicateCarrier` (sealed `Condition`/`LookupRows`), `MethodArguments`, `InsertRows`, `UpdateRows`. Each produced by exactly one walker; each lives on the output field as `Optional<Carrier>`.
- **`BackingClass`** — three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) attached per `MethodArgumentBinding` variant by R164. R222 ships the family as vocabulary; not a slot on any R222-introduced model record.
- **`ClassifierEventSink`** — the side channel walkers write structured diagnostic events to. Drains post-walk into BuildContext warnings and Rejection.
- **`ClassifierEvent`** — sealed family of structured diagnostic event types. R226-compatible substitute for the legacy `Unclassified*` carriers.
- **No "table-bound input"** — the predicate retires. Inputs are SDL declarations; tables enter the picture at walker time via the consumer's `@table` return.

## Tests

The pivot's load-bearing test claim is *unit-tier coverage of the walker abstraction*. Pipeline coverage falls out as a by-product when walker output is consumed by downstream emitters; pipeline tests are not the primary contract.

- **Unit-tier (new, primary):** `ConditionWalker` tests. One test per SDL shape variation: `@condition` on input type, `@condition` on input field, `@condition` on arg, nested-input `@condition`, plain unresolved column → `CarrierProducedNothing` event, `@lookupKey` bailout (each nesting depth), `@condition + @lookupKey` on the same field → `AuthorError` event.
- **Unit-tier (new, primary):** `MethodArgumentsWalker` tests. One test per SDL shape: scalar arg, nested-input arg, mixed args, `@nodeId`-decorated arg, no args, `@condition` on a `@service` field's arg → `AuthorError` event.
- **Unit-tier (new):** dispatch logic tests. Field with `@service` → only `MethodArgumentsWalker` elected. Plain query field → `ConditionWalker` elected (plus future walkers as they ship). Mutation field → `PredicateWalker` defaults to `LookupRows`. `@lookupKey` on a read field → walker dispatches with `defaultIsLookupRows=false` and bails on first encounter (testable via the walker, but the dispatch logic itself doesn't pre-scan).
- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `DirectiveDeprecated` warnings for `@table`-decorated and `@record(class:)`-decorated SDL inputs).
- **Pipeline-tier (new):** `@table` on input emits `DirectiveDeprecated`. Fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer surfaces a `BuildWarning` at the directive's `SourceLocation`; carrier output is identical to the directive-absent case.
- **Pipeline-tier (new):** `@record(class:)` on input emits `DirectiveDeprecated`.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two consumers with different return tables produces distinct walker carriers per-output-field, each with its own table fact. Today's `InputType` collapse becomes per-output-field success.
- **Pipeline-tier (new):** `@condition` on a `@service` field's arg surfaces `Rejection.AuthorError` through the sink. Pins the principle that `@condition` is interior to SQL emission, not a separate consumer.
- **Pipeline-tier (new):** `@service` fields produce no `PredicateCarrier`. A fixture with a `@service`-decorated output field whose arg is an SDL input type produces an `OutputField` with `methodArguments` populated and `predicate` empty. Pins the totalising-directive principle and the walker-dispatch invariant.
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required.

## Risk

- **Walker-per-output-field multiplies work for inputs reused across many consumers.** Each output field that elects a walker pays per-field walker invocation cost; an input reused across ten fields runs each elected walker ten times. Mitigation 1: the walker walk is `List<InputFieldDecl>` against graphql-java's reflective accessors; typically low tens of fields, microseconds per invocation. Profile before optimising. Mitigation 2: if profiling shows a hotspot, walkers can deduplicate per `(Input, Consumer)` pair via a side cache. The cache lives outside the model; structural equality is the contract.
- **Phases 1-3 keep both models alive simultaneously.** Adapter overhead during the additive migration window. Mitigation: Phase 4 deletes legacy with the same urgency as the rest of the pivot.
- **R164 dependency for `MethodArgumentsWalker`'s binding variants.** R222 ships `MethodArgumentBinding.Pending` as a placeholder; downstream emitters that need the specialised variant must wait for R164. Mitigation: `Pending` is a single-arm carrier that delegates to today's reflection-based mapping at consumer time; the swap to R164's variants is a sealed-family extension, not a record-shape change.
- **`ClassifierEventSink` is a new diagnostic channel.** Drift between sink-emitted events and BuildContext warning surfacing is possible. Mitigation: the drainage logic is one site (the post-classification pass); a load-bearing invariant pins the contract.
- **Walker dispatch is procedural, not type-enforced.** Adding a new walker requires touching the dispatch logic in addition to the carrier slot. Mitigation: dispatch is a single function; the dispatch tests pin the rules. The trade-off is intentional — making dispatch a sealed family would re-introduce the cross-product encoding the pivot exists to dissolve.

## Out of scope

- **The R164 `MethodArgumentBinding` sealed family** (scalar passthrough, decoded NodeID, `BackingClass`-populated). R222 ships the placeholder arm only.
- **The R122 `InsertRowsWalker`** and its compound tree representation.
- **`LookupRowsWalker`, `PaginationWalker`, `OrderingWalker`, `UpdateRowsWalker`** — each is its own sibling item.
- **Producer-side unification of method invocation paths** (uniform reflection-mapping rules across `@service` / `@externalField` / `@tableMethod` / `@condition`). R164 and adjacent items carry that work.
- **Field-side dimensional pivot**: R164's broader scope. R222 demonstrates the pattern at carrier granularity on the input side.
- **`ServiceCatalog` predicate consolidation**: R220 / R193. Same disease in a different file.
- **`argMapping` grouping syntax**: R97 Phase 1.
- **Visitor-driven emission for non-input types**: broader R166.
- **Reachability pruning across all type kinds**: R166 Phase 1. Orthogonal; walker dispatch only runs on reachable output fields anyway.

## Architectural principle this codifies

R164 frames the disease: a sealed hierarchy that tries to represent multiple independent dimensions through a single permit set. The cross product is the permit set; adding a value to any axis multiplies the permits below it; the leaves carry redundant or divergent encodings of the same axis.

The cure earlier drafts proposed: separate the dimensions onto orthogonal slots on a recursive carrier (`InputUsage`). Review found the carrier still folded distinct consumer concerns into one classified output.

The cure this draft commits to: separate the *consumer concerns* into independent *carriers*; produce each by an independent *walker* over the SDL; let the output field carry the carriers as fixed-meaning Optional slots. No top-level sealed hierarchy; impossible combinations are excluded by dispatch-time election rather than by type. Walkers are pure functions over graphql-java primitives, independently unit-testable; carrier-internal shape (`PredicateCarrier`'s `Condition`/`LookupRows` arms, `InsertRows`' tree) seals only where the carrier's own semantics require it.

The principle is not "minimise the model"; it is "make the model honest about what each consumer needs." The cross-product encoding hides axes; the per-carrier encoding surfaces them. The walker abstraction makes each axis individually testable, individually evolvable, individually replaceable. Hidden axes drift; surfaced axes get the compiler's help — and the unit test's.
