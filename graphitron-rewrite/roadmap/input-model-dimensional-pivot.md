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

Earlier R222 drafts pivoted to a recursive `InputUsage` carrier, scoped to SQL emission. Review found the carrier was still the wrong granularity: it folded distinct consumer concerns (WHERE construction, DML row-shaping, lookup-key identification, method-param binding, pagination, ordering) into one classification output that consumers then re-discriminated. The dimensional pivot lands further: each consumer concern is its own *carrier*; each carrier is a sealed family whose arms include valid carriers plus an explicit absent arm (`No<Family>`) and explicit invalid arm (`Invalid<Family>`); each is produced by an independent *walker* over the SDL that returns a `WalkerResult` bundling the carrier with its diagnostics; the output field carries the carriers as fixed-meaning slots, always populated, never Optional. No top-level sealed hierarchy. No shared recursive tree. No common classification carrier. No shared mutable diagnostic sink.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits encode three independent axes (backing-class kind, table-binding and where it came from, eager column classification) onto one identity slot. The cross product is sparse: the table-bound × non-jOOQ-TableRecord-backed × eager-classified cell is `TableInputType`; the table-bound × jOOQ-TableRecord-backed cell is `JooqTableRecordInputType`; the unbound row has four permits that differ only on backing-class kind.

`TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` — an O(N) back-scan over all schema fields. Whether an SDL input is "table-bound" is *already* a property of the consumer, not the input declaration. R215's lift (Done) admits `InputField.UnboundField` into `TableInputType.inputFields()`, collapsing the "eager classification" axis.

Nine consumer sites discriminate on the split today: `GraphitronSchemaValidator.java:80-81`, `MutationInputResolver.java:368`, `EnumMappingResolver.java:303`, `CatalogBuilder.java:206 / 498 / 570 / 639`, `FieldBuilder.java:967`, `TypeBuilder.java:209-210`. Each asks "does the input have a table?" or "what's its backing class?" by switching on permit identity, then proceeds.

R171 (Backlog) proposes a sealed `InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact. The deeper issue: classification produces one heterogeneous output that every consumer then re-discriminates by role. The cure is to surface the roles directly and have classification produce them in parallel.

## What's to be: walkers populate carriers; carriers live on output fields

- **Output fields gain carrier slots.** Each output field carries one slot per carrier family. The slot is non-Optional, always populated with exactly one arm of the family — a valid arm (e.g. `Condition`), the explicit absent arm (`No<Family>`), or the explicit invalid arm (`Invalid<Family>`). Consumers pattern-match the arm at use time; absence and invalidity are first-class domain states, not present/missing flags.
- **Walkers are independent and total.** Each walker takes a `GraphQLFieldDefinition` and returns a `WalkerResult<C>` bundling the produced carrier (one arm of `C`'s sealed family, always populated) with the diagnostic events the walker accumulated. Walkers are pure functions over `graphql.schema.*` primitives; they do not consult a shared graphitron-internal classification model and do not depend on a shared mutable sink.
- **The output field elects its walkers.** During classification, the field's directive set + return-type shape drive walker dispatch procedurally. No top-level sealed family; impossible combinations are excluded at dispatch. Slots whose walker was not elected default to the family's `No<Family>` arm.
- **Walkers are independently unit-testable.** Parse a small SDL fragment, get a `GraphQLFieldDefinition`, run the walker, assert on the returned `WalkerResult` (carrier arm and events list). Pure function in, pure record out; no mocks required. Pipeline coverage comes as a by-product when downstream emitters consume the carriers.
- **Schema warnings and errors ride with the walker result.** The carrier shape is no longer 1-1 with the SDL; each `WalkerResult<C>` carries a `List<ClassifierEvent>` alongside its carrier. The orchestrator drains events from every walker result post-walk to surface warnings (BuildContext) and errors (Rejection). The carrier arm encodes the structural outcome (valid / `No<C>` / `Invalid<C>`); events explain the prose. The two stay in lockstep: every `Invalid<C>` arm is paired with at least one `AuthorError` event from the same walker.

### The carrier vocabulary

| Carrier family | Purpose | Valid-arm shape |
|---|---|---|
| `Pagination` | first/after/last/before (keyset) or offset/limit; consumer emits ORDER BY + LIMIT + keyset predicate | single valid arm (`Pagination.Of`), flat |
| `Ordering` | `@orderBy`-family directives; consumer emits ORDER BY | single valid arm (`Ordering.Of`), flat |
| `PredicateCarrier` | predicates that filter (`Condition`) or identify rows to act on (`LookupRows`) | two valid arms (`Condition`, `LookupRows`), both flat |
| `MethodArguments` | param bindings for a Java method call (`@service`, `@externalField`, `@tableMethod`'s table-returning call) | single valid arm (`MethodArguments.Of`) holding a list of per-param `MethodArgumentBinding`; R164 carries the binding-variant family |
| `InsertRows` | row plans to INSERT; compound (R122) carries parent + FK-threaded children | single valid arm (`InsertRows.Of`), tree-shaped, R122 carries internal shape |
| `UpdateRows` | column-value bindings to write | single valid arm (`UpdateRows.Of`), flat |

Each carrier family is a sealed interface. The valid arms above are the family's "produced something useful" cases. Two additional arms are universal across families: `No<Family>` records that the walker ran and found nothing meaningful (no condition args, no ordering directives, etc.) — "happy zero"; `Invalid<Family>` records that the walker ran, found structurally broken input, and registered diagnostics in its result — "broken zero". Downstream consumers pattern-match exhaustively over the family; `No<Family>` and `Invalid<Family>` are domain states that demand explicit handling, not nullability concerns.

**PredicateCarrier's two valid arms** share predicate shape but differ in semantic role. `Condition` is the default for SQL-emitting *read* fields; `LookupRows` is the default for *mutation* fields. The walker's bailout-restart pattern handles role-discovery lazily: start with the default arm's accumulator; if a sentinel directive shows up anywhere in the walk, discard and restart with the other arm. Sentinels:

- `@lookupKey` on a read field (or on a nested input field used by a read) → flip to `LookupRows`
- `@multirows` (working name) on a mutation field → flip to `Condition`

The valid-arm choice lives inside the predicate-binding axis, not at the field level. The walker chooses the arm by construction; consumers pattern-match the arm at use time, including `NoPredicates` and `InvalidPredicates`.

**MethodArguments' internal shape.** R164 territory. The carrier holds an ordered list of per-param bindings; each binding is one of: scalar passthrough, decoded NodeID, `BackingClass`-populated. R222 reserves the carrier; R164 ships the binding-variant sealed family and the `BackingClass` attachment.

**InsertRows' internal shape.** R122 territory. The simple INSERT is the one-node degenerate case of a tree; compound INSERTs (parent + FK-threaded children) carry the full tree with FK descriptors. R222 reserves the carrier; R122 ships the tree and the FK threading.

### The walker abstraction

Each walker is a total reducer; it returns a wrapper bundling its produced carrier with the diagnostics it accumulated:

```java
record WalkerResult<C>(C carrier, List<ClassifierEvent> events) {}

interface Walker<C> {
    /**
     * Reduce the field's args + nested input contents into a single carrier.
     * Walker is total: the returned carrier is always one arm of C's sealed
     * family — a valid arm if the walk yielded one, No<C> if the walk found
     * nothing meaningful, or Invalid<C> if the walk found structurally broken
     * input. Diagnostics accompany the carrier in the result; the walker has
     * no shared mutable state and no injected sink.
     */
    WalkerResult<C> walk(GraphQLFieldDefinition field);
}
```

The carrier type `C` is the walker's product. Walkers are built on `graphql.schema.GraphQLArgument` / `GraphQLInputObjectField` / `GraphQLInputType` directly; the walk reads `getDirectives()`, `getType()`, and field-type-introspection methods. The walker's logic is "look at each arg, look at each nested input field, accumulate into the typed accumulator, build the carrier arm at the end." No graphitron-internal recursive model is required.

**Bailout-restart.** The predicate walker illustrates:

```java
class PredicateWalker implements Walker<PredicateCarrier> {
    private final boolean defaultIsLookupRows;  // true for mutations, false for reads

    public WalkerResult<PredicateCarrier> walk(GraphQLFieldDefinition field) {
        var events = new ArrayList<ClassifierEvent>();
        var primary = defaultIsLookupRows ? new LookupRowsAccumulator() : new ConditionAccumulator();
        var bailout = walkInto(field, primary, events);
        if (bailout.isEmpty()) return new WalkerResult<>(primary.build(), events);

        events.add(new PredicateRoleSwitched(bailout.get().location(), primary.armName(), ...));
        var secondary = defaultIsLookupRows ? new ConditionAccumulator() : new LookupRowsAccumulator();
        walkInto(field, secondary, events);
        return new WalkerResult<>(secondary.build(), events);
    }
}
```

Each accumulator's `build()` returns the full carrier family — a valid arm if predicates accrued, `NoPredicates` if nothing did, `InvalidPredicates` if the accumulator's diagnostics include an `AuthorError`. The bailout signal is the sentinel directive; the walker's outer loop doesn't pre-scan. Role discovery is structural — sentinel triggers restart, no separate role-decision pass.

**Unit-testability is load-bearing.** Each walker is a pure function: graphql-java primitives in, `WalkerResult` out. No mocks, no shared sink, no graphitron classification context required. Tests assert against the returned record:

```java
@Test
void condition_walker_reads_at_condition_directive_on_input_type() {
    var field = parseField("""
        type Query { films(filter: FilmFilter!): [Film] @table(name: "film") }
        input FilmFilter @condition(name: "film_in_genre", override: false) {
            genre: String
        }
    """);
    var result = new ConditionWalker().walk(field);

    assertThat(result.carrier()).isEqualTo(new Condition(List.of(
        new MethodSuppliedPredicate("film_in_genre", List.of("genre"))
    )));
    assertThat(result.events()).isEmpty();
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

### Schema warnings and errors: bundled with the walker result

The walker model breaks SDL-shape 1-1 correspondence in several places:

- A walker drops a directive that is no longer load-bearing (`@table` on input, `@record(class:)` on input, `@value`).
- A walker bails and restarts; the bailout is worth surfacing when its trigger is subtle (e.g. `@lookupKey` deep in a nested input).
- Multiple walkers may surface findings at the same SDL location.
- A walker produces a `No<C>` arm with a non-empty diagnostic trail (e.g. `@service` field with non-method-shaped args) or an `Invalid<C>` arm (structurally broken input, accompanied by an `AuthorError`).

Today's classifier-output channel mixes structured signal (`Unclassified*` carriers) with the actual classification. R222 separates the two: the carrier arm encodes the structural outcome (valid / `No<C>` / `Invalid<C>`); the `WalkerResult.events()` list carries the diagnostic prose. The two ride together in the wrapper, not as parallel channels.

```java
record WalkerResult<C>(C carrier, List<ClassifierEvent> events) {}

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

Aggregation:

- Classification iterates output fields, dispatches walkers, installs each `result.carrier()` into the corresponding slot, and concatenates `result.events()` into a flat per-build diagnostic stream.
- `DirectiveDeprecated` / `DirectiveDropped` → BuildContext warnings (`TypeBuilder.emitDirectiveIgnoredWarnings`'s existing channel).
- `PredicateRoleSwitched` → BuildContext info-level message; surfaced behind a verbosity flag.
- `AuthorError` → terminates the build with the standard `Rejection.AuthorError` shape. An `Invalid<C>` carrier arm always accompanies any `AuthorError` from the same walker; the arm pins the structural state in the model while the event explains the cause.
- `CarrierProducedNothing` → typically silent; surfaced on demand for debugging. Accompanies a `No<C>` arm where the walker has something useful to say about why nothing was produced (e.g. dispatch elected the walker but the field had no relevant args).

The model is intentionally redundant on failure: the carrier arm is the *structurally consumable* signal (downstream pattern-match) and the events are the *human-consumable* signal (rejection messages, warnings). Consumers never silently coalesce `No<C>` with `Invalid<C>` because the arms differ in identity. Producers never silently emit `Invalid<C>` without an accompanying error event because the orchestrator validates the pairing post-walk.

R226 (classification dimensional pivot: diagnostics off the model) is forward-compatible: its eventual `Diagnostic` family substitutes for `ClassifierEvent` when it lands; the `WalkerResult` wrapper's events-list shape is unchanged.

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
    Pagination pagination,
    Ordering ordering,
    PredicateCarrier predicate,
    MethodArguments methodArguments,
    InsertRows insertRows,
    UpdateRows updateRows
) { ... }
```

Each slot is a sealed family; the field holds exactly one arm. Slots are always populated; the arm answers what the field actually does on that dimension:

- Walker was not elected for this field → slot defaults to the family's `No<Family>` arm.
- Walker was elected and produced a valid result → slot holds the relevant valid arm (e.g. `Condition`, `MethodArguments.Of`).
- Walker was elected and found nothing meaningful → slot holds `No<Family>` (and the diagnostic stream may carry a `CarrierProducedNothing` event).
- Walker was elected and found structurally broken input → slot holds `Invalid<Family>` (and the diagnostic stream carries the paired `AuthorError`).

Pattern matches at use sites are exhaustive against the full family — there is no "did the walker run" question to ask. The compiler's exhaustiveness check is the safety net for every consumer migration.

The carrier types:

```java
sealed interface PredicateCarrier {
    record Condition(List<Predicate> predicates) implements PredicateCarrier {}
    record LookupRows(List<KeyPredicate> keys) implements PredicateCarrier {}
    record NoPredicates() implements PredicateCarrier {}
    record InvalidPredicates() implements PredicateCarrier {}
}

sealed interface MethodArguments {
    record Of(List<MethodArgumentBinding> paramBindings) implements MethodArguments {}
    record NoMethodArguments() implements MethodArguments {}
    record InvalidMethodArguments() implements MethodArguments {}
}
// MethodArgumentBinding is R164's sealed family with arms for scalar passthrough,
// NodeID-decoded, BackingClass-populated, etc.

sealed interface Pagination {
    record Of(...) implements Pagination {}
    record NoPagination() implements Pagination {}
    record InvalidPagination() implements Pagination {}
}

sealed interface Ordering {
    record Of(...) implements Ordering {}
    record NoOrdering() implements Ordering {}
    record InvalidOrdering() implements Ordering {}
}

sealed interface InsertRows {              // R122 carries the valid-arm tree shape
    record Of(...) implements InsertRows {}
    record NoInsertRows() implements InsertRows {}
    record InvalidInsertRows() implements InsertRows {}
}

sealed interface UpdateRows {
    record Of(List<ColumnValueBinding> columns) implements UpdateRows {}
    record NoUpdateRows() implements UpdateRows {}
    record InvalidUpdateRows() implements UpdateRows {}
}
```

The split between `NoMethodArguments` (this field doesn't dispatch via method arguments at all — `@service` walker wasn't elected, or the field has no args to bind) and `MethodArguments.Of(...)` (the walker bound actual arguments) and `InvalidMethodArguments` (bindings failed with a paired `AuthorError`) carries domain meaning that `Optional<MethodArguments>` would conflate. The same three-way split pays off across every carrier: "walker had nothing to do" / "walker built a value" / "walker tried and failed".

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

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes "validate each carrier the field produced"; per-permit-identity dispatch retires entirely. The `Invalid<Family>` arms surface diagnostics already accumulated by the walker, so the validator's job narrows to cross-carrier consistency checks (e.g. `@service` field with non-empty `predicate` slot is a producer bug, not an author error) rather than per-carrier structural validation.

## Two example walkers shipped in R222

R222 ships two walkers in tree as exemplars. Together they prove the abstraction: one demonstrates the bailout/restart pattern, one demonstrates the totalising directive. Both are independently unit-testable; both have downstream consumers ready to migrate (Phase 3).

### `ConditionWalker`

Produces a `PredicateCarrier.Condition` (or, on `@lookupKey` bailout, a `LookupRows`) for SQL-emitting read fields. Walks the field's args; for each arg, examines its directives (`@condition`, `@lookupKey`) and, if the arg is an INPUT_OBJECT, recurses into its fields. Accumulates predicates into the carrier.

Bailout: any `@lookupKey` encountered (on an arg or on a nested input field) triggers a restart with `LookupRowsAccumulator`. The result's events list records a `PredicateRoleSwitched` event with the trigger location.

```java
public WalkerResult<PredicateCarrier> walk(GraphQLFieldDefinition field) {
    var events = new ArrayList<ClassifierEvent>();
    var primary = new ConditionAccumulator();
    var bailout = walkInto(field, primary, events);
    if (bailout.isEmpty()) return new WalkerResult<>(primary.build(), events);

    events.add(new PredicateRoleSwitched(bailout.get().location(),
        "Condition", "LookupRows", "@lookupKey"));
    var secondary = new LookupRowsAccumulator();
    walkInto(field, secondary, events);
    return new WalkerResult<>(secondary.build(), events);
}
```

Each accumulator's `build()` returns a `PredicateCarrier` arm directly: a valid arm if predicates accrued, `NoPredicates` if nothing did, `InvalidPredicates` if the accumulator's events include an `AuthorError`.

Unit-test surface: `@condition` on input type / input field / arg, plain input fields, nested-input recursion, `@lookupKey` bailout (at each nesting depth), unresolved column → `NoPredicates` + `CarrierProducedNothing` event, `@condition + @lookupKey` on the same field → `InvalidPredicates` + `AuthorError`. Each case is an SDL fragment + walker invocation + `WalkerResult` assertion.

### `MethodArgumentsWalker`

Produces a `MethodArguments` arm for fields carrying `@service`, `@externalField`, or `@tableMethod`. Walks the field's args; for each arg, produces a `MethodArgumentBinding` placeholder (R164 lands the binding-variant family; R222 produces a single uniform `MethodArgumentBinding.Pending` arm or equivalent).

The walker is *total* for its electing directives: when invoked, it consumes all the field's args. The dispatch logic ensures it is the only walker invoked on `@service` / `@externalField` fields.

```java
public WalkerResult<MethodArguments> walk(GraphQLFieldDefinition field) {
    var events = new ArrayList<ClassifierEvent>();
    var bindings = new ArrayList<MethodArgumentBinding>();
    var hadFailure = false;
    for (var arg : field.getArguments()) {
        var bound = bindArg(arg, events);     // R164 specialises the binding kind
        if (bound.isPresent()) bindings.add(bound.get());
        else hadFailure = true;
    }
    MethodArguments carrier =
        hadFailure                       ? new MethodArguments.InvalidMethodArguments() :
        field.getArguments().isEmpty()   ? new MethodArguments.NoMethodArguments() :
                                           new MethodArguments.Of(bindings);
    return new WalkerResult<>(carrier, events);
}
```

`NoMethodArguments` is the "field has no args" case; `Of(emptyList)` would only arise in a hypothetical "all args were filtered out" path that R222 does not exercise. `bindArg` failures escalate the carrier to `InvalidMethodArguments`; an accompanying `AuthorError` rides in `events`. Invariant #3 pins the pairing.

Unit-test surface: scalar arg, nested-input arg, mixed scalar+nested args, `@nodeId`-decorated arg, conflicting directives (e.g. `@condition` on an arg of a `@service` field) → `InvalidMethodArguments` + `AuthorError`, no args → `NoMethodArguments`. The R164 contract is exercised by extension tests R164 will add; R222's tests pin the walker's structural correctness against today's `MethodArgumentBinding.Pending` arm.

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
| R209 (FieldRegistry classify-input trace) | Typed rejection at walker time; `Rejection.AuthorError.UnknownName` rides on the walker's `WalkerResult.events()` and escalates the carrier to `Invalid<Family>` |
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
- **R226** (classification dimensional pivot: diagnostics off the model): forward-compatible by design. R222's `ClassifierEvent` + `WalkerResult.events()` is the prototype shape; R226's eventual `Diagnostic` family substitutes for `ClassifierEvent` when it lands. The wrapper's events-list shape is unchanged; only the element type swaps.

## Why R144's partition reverses (under the walker model)

R144 committed the WHERE-vs-SET partition at classify time on the legacy carrier (`TableInputArg.lookupKeyFields` / `setFields`). Two problems show up under the walker pivot.

First, the partition is a property of the *consumer*, not of the input. UPDATE's "WHERE" is `LookupRows`; UPDATE's "SET" is `UpdateRows`. Each is its own walker, producing its own carrier. The consumer reads the slot it needs; nothing classifies "WHERE-vs-SET" on the input side.

Second, the `@value` marker becomes redundant. `UpdateRowsWalker` walks the field's args; for each column-bound input field, the walker checks whether the column is in the target table's PK column set (sourced from `JooqCatalog`). Non-PK columns go into `UpdateRows`; PK columns are claimed by `LookupRowsWalker` for the same field. The two walkers split the SDL columns by PK-membership, derived from the catalog. No per-input-field marker needed.

R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) survives unchanged in shape: the trigger is the catalog's PK column set examined by `UpdateRowsWalker` / `LookupRowsWalker`, not the `@value` complement.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical. The Phase 6 fixture sweep drops them.

## Cross-axis invariants

The dimensional split admits states the old model could not. Four load-bearing invariants pin those states explicitly, each carried as a `@LoadBearingClassifierCheck` key. Producer is the named walker; consumers are the per-carrier-slot readers on Output.

1. **`walker.produces-only-its-own-family`**: each `Walker<C>` produces a carrier that is one arm of `C`'s sealed family — never another walker's carrier. Pinned per walker; load-bearing because dispatch logic relies on it (no walker silently populates a slot it does not own).

2. **`output-field.carrier-slot-always-populated-with-arm`**: every carrier slot on every `OutputField` is non-null and holds exactly one arm of its sealed family. When a walker is not elected for a field, the slot is initialized to the family's `No<Family>` arm; when a walker is elected, the slot is set to `result.carrier()`. Pinned at the orchestrator; the audit walker catches null or uninitialized slots and any slot whose arm comes from outside its declared family.

3. **`walker-result.invalid-arm-paired-with-author-error`**: every `Invalid<Family>` carrier produced by a walker is accompanied by at least one `AuthorError` event in the same `WalkerResult.events()`, sharing the failure's `SourceLocation`. The orchestrator validates the pairing post-walk; an `Invalid<Family>` carrier without an accompanying error is a producer bug. Pinned per walker; the audit walker catches both directions (Invalid without AuthorError, and — looser — AuthorError without the carrier escalating to Invalid).

4. **`per-type-emit-ignores-walker-output`**: `InputRecordGenerator` and `InputTypeGenerator` read only `Input` (never any walker's carrier); per-output-field consumers read only the relevant carrier slot (never bypass to walk the input themselves). Pinned with `@DependsOnClassifierCheck` on both per-type generator entry points.

**Retirements:**

- The legacy `input.record-shape-derived-from-backing` key retires entirely with `Input`'s backing-class-slot removal. `ValidationShape` is purely SDL-derived now.
- The earlier draft's `input-usage.*` keys retire with `InputUsage`'s removal. The walker model produces no shared recursive carrier; per-walker invariants replace the recursive-tree invariant.
- The earlier draft's `r222-stand-in-form-from-directive-set` key retires with the field-level form discriminator. Walker dispatch reads the directive set directly; no stand-in is in play.
- The previous-draft `output-field.carrier-slot-presence-iff-walker-elected` key retires with the Optional slots. The carrier arm itself now answers "did the walker run and what did it find"; presence-vs-absence has been domain-modelled into the sealed family.

## Phasing

Six phases, each independently shippable and individually reversible. The change is *additive* through Phase 4; legacy code retires in Phase 5 once all consumers have migrated.

### Phase 1: introduce `Input`, `InputFieldDecl`, the carrier vocabulary, the walker abstraction, `WalkerResult`

- Add `sealed interface Input`, `InputFieldDecl` to `model/`.
- Add the carrier vocabulary: each carrier is a sealed family with valid arms plus universal `No<Family>` and `Invalid<Family>` arms. `PredicateCarrier` has valid arms `Condition` and `LookupRows`; `MethodArguments`, `Pagination`, `Ordering`, `InsertRows`, `UpdateRows` each have a single valid arm (`Of`) plus the two flag arms. R164's `MethodArgumentBinding` family and R122's `InsertRows.Of` tree land in their respective items; R222 ships `MethodArguments.Of(List<MethodArgumentBinding.Pending>)` as the placeholder valid arm.
- Add `interface Walker<C>` and `record WalkerResult<C>(C carrier, List<ClassifierEvent> events)`. Define the `ClassifierEvent` sealed family.
- Add `BackingClass` family (`Pojo`, `JavaRecord`, `JooqTableRecord`) with `@LoadBearingPlaceholder("R164 method-argument-binding arms")` so the audit doesn't flag the dead carrier.
- **Rename R94's `InputRecordShape` → `ValidationShape`** and the slot `recordShape` → `validationShape` on every input variant. The capability marker renames from `HasInputRecordShape` → `HasValidationShape` in lockstep (it retires entirely in Phase 5).
- Add a `Map<String, Input>` slot on `GraphitronSchema` populated from the existing types map.
- Add a non-Optional slot for each carrier family on the existing Output record, each defaulting to its `No<Family>` arm. Compiler-visible, no consumer behavior change.
- No walker ships yet; no consumer reads the new slots beyond the defaults.

Acceptance: model additions compile; every existing input today produces an equivalent `Input` from the type map; the per-input emit is unchanged.

### Phase 2: ship `ConditionWalker` and `MethodArgumentsWalker` with unit tests

- Implement `ConditionWalker`: walks args + nested inputs, accumulates predicates, bails to `LookupRowsAccumulator` on `@lookupKey`. The bailout target is in-tree even though `LookupRowsWalker` ships as a sibling item — the accumulator's class is the bailout's product, not a separate walker call.
- Implement `MethodArgumentsWalker`: walks args, produces `MethodArguments.Of(List<MethodArgumentBinding.Pending>)`. The per-param specialization (decoded NodeID, BackingClass-populated, scalar passthrough) is R164's; R222's body produces uniform `Pending` bindings.
- Unit-test surface (the load-bearing demonstration of the pivot): each walker has a test class with one test per SDL shape variation. Tests are pure-graphql-java: parse a fragment, run the walker, assert on the returned `WalkerResult` (carrier arm + events list). No graphitron classification context is constructed.
- Add walker-dispatch logic to the existing classification pass: SQL-form read fields elect `ConditionWalker`; `@service` / `@externalField` fields elect `MethodArgumentsWalker` exclusively. Other walkers are not yet elected (slots stay on their `No<Family>` default arm).
- Three of the four load-bearing keys land here: `walker.produces-only-its-own-family` on each walker; `output-field.carrier-slot-always-populated-with-arm` and `walker-result.invalid-arm-paired-with-author-error` on the dispatch / orchestration site. The fourth (`per-type-emit-ignores-walker-output`) lands in Phase 3 once the per-type generators migrate.
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

Likely scope: 2-3 weeks of focused work. The walker abstraction and the `WalkerResult` / `ClassifierEvent` scaffolding are new; the two example walkers + their unit tests are the main lift. The migration phases are mechanical.

## Vocabulary

- **`Input`** — the SDL declaration: name, location, assembled-schema form, ValidationShape, `List<InputFieldDecl>`. Carries no backing class, no table, no classified state. Read by per-type emitters (Jakarta validation) and by walkers as their substrate.
- **`InputFieldDecl`** — the SDL field declaration on `Input`, pre-binding. Wraps `GraphQLInputObjectField` for applied-directive access.
- **`Walker<C>`** — a pure total function over the SDL returning `WalkerResult<C>`: a carrier of type C (always one arm of its sealed family, including `No<C>` and `Invalid<C>`) bundled with the diagnostic events the walker accumulated. Built on graphql-java primitives. Independently unit-testable. Stateless across invocations; no shared mutable sink.
- **`WalkerResult<C>`** — `record WalkerResult<C>(C carrier, List<ClassifierEvent> events)`. The carrier arm is always populated; the events list explains the walk and may be empty. The two stay in lockstep: `Invalid<C>` arms are paired with at least one `AuthorError` event from the same walker.
- **Carriers** — `Pagination`, `Ordering`, `PredicateCarrier`, `MethodArguments`, `InsertRows`, `UpdateRows`. Each is a sealed family with valid arms plus an explicit absent arm (`No<Family>`) and explicit invalid arm (`Invalid<Family>`). Each is produced by exactly one walker; each lives on the output field as a non-Optional slot whose value is always one arm of the family.
- **`No<Family>` / `Invalid<Family>`** — universal flag arms. `No<Family>` is "happy zero" (walker ran, nothing meaningful to produce, no error). `Invalid<Family>` is "broken zero" (walker ran, structurally broken input, paired with an `AuthorError`). Pattern matches at use sites are exhaustive against the full family; consumers cannot silently coalesce the two.
- **`BackingClass`** — three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) attached per `MethodArgumentBinding` variant by R164. R222 ships the family as vocabulary; not a slot on any R222-introduced model record.
- **`ClassifierEvent`** — sealed family of structured diagnostic event types carried inside `WalkerResult`. R226-compatible substitute for the legacy `Unclassified*` carriers.
- **No "table-bound input"** — the predicate retires. Inputs are SDL declarations; tables enter the picture at walker time via the consumer's `@table` return.

## Tests

The pivot's load-bearing test claim is *unit-tier coverage of the walker abstraction*. Pipeline coverage falls out as a by-product when walker output is consumed by downstream emitters; pipeline tests are not the primary contract.

- **Unit-tier (new, primary):** `ConditionWalker` tests. One test per SDL shape variation: `@condition` on input type, `@condition` on input field, `@condition` on arg, nested-input `@condition`, plain unresolved column → `NoPredicates` carrier + `CarrierProducedNothing` event, `@lookupKey` bailout (each nesting depth) → `LookupRows` carrier + `PredicateRoleSwitched` event, `@condition + @lookupKey` on the same field → `InvalidPredicates` carrier + `AuthorError` event.
- **Unit-tier (new, primary):** `MethodArgumentsWalker` tests. One test per SDL shape: scalar arg → `MethodArguments.Of`, nested-input arg, mixed args, `@nodeId`-decorated arg, no args → `NoMethodArguments`, `@condition` on a `@service` field's arg → `InvalidMethodArguments` + `AuthorError`. Pair-up assertion (`Invalid` ↔ `AuthorError`) is itself a test, pinning invariant #3.
- **Unit-tier (new):** dispatch logic tests. Field with `@service` → only `MethodArgumentsWalker` elected. Plain query field → `ConditionWalker` elected (plus future walkers as they ship). Mutation field → `PredicateWalker` defaults to `LookupRows`. `@lookupKey` on a read field → walker dispatches with `defaultIsLookupRows=false` and bails on first encounter (testable via the walker, but the dispatch logic itself doesn't pre-scan).
- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `DirectiveDeprecated` warnings for `@table`-decorated and `@record(class:)`-decorated SDL inputs).
- **Pipeline-tier (new):** `@table` on input emits `DirectiveDeprecated`. Fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer surfaces a `BuildWarning` at the directive's `SourceLocation`; carrier output is identical to the directive-absent case.
- **Pipeline-tier (new):** `@record(class:)` on input emits `DirectiveDeprecated`.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two consumers with different return tables produces distinct walker carriers per-output-field, each with its own table fact. Today's `InputType` collapse becomes per-output-field success.
- **Pipeline-tier (new):** `@condition` on a `@service` field's arg surfaces `Rejection.AuthorError` via the orchestrator's drained `WalkerResult.events()`, with the field's `methodArguments` slot holding `InvalidMethodArguments`. Pins the principle that `@condition` is interior to SQL emission, not a separate consumer.
- **Pipeline-tier (new):** `@service` fields produce no `PredicateCarrier`. A fixture with a `@service`-decorated output field whose arg is an SDL input type produces an `OutputField` with `methodArguments == MethodArguments.Of(...)` and `predicate == NoPredicates`. Pins the totalising-directive principle and the walker-dispatch invariant.
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required.

## Risk

- **Walker-per-output-field multiplies work for inputs reused across many consumers.** Each output field that elects a walker pays per-field walker invocation cost; an input reused across ten fields runs each elected walker ten times. Mitigation 1: the walker walk is `List<InputFieldDecl>` against graphql-java's reflective accessors; typically low tens of fields, microseconds per invocation. Profile before optimising. Mitigation 2: if profiling shows a hotspot, walkers can deduplicate per `(Input, Consumer)` pair via a side cache. The cache lives outside the model; structural equality is the contract.
- **Phases 1-3 keep both models alive simultaneously.** Adapter overhead during the additive migration window. Mitigation: Phase 4 deletes legacy with the same urgency as the rest of the pivot.
- **R164 dependency for `MethodArgumentsWalker`'s binding variants.** R222 ships `MethodArgumentBinding.Pending` as a placeholder; downstream emitters that need the specialised variant must wait for R164. Mitigation: `Pending` is a single-arm carrier that delegates to today's reflection-based mapping at consumer time; the swap to R164's variants is a sealed-family extension, not a record-shape change.
- **`ClassifierEvent` aggregation is a new diagnostic path.** Drift between walker-emitted events and BuildContext warning surfacing is possible. Mitigation: aggregation is one site (the orchestrator drains every `WalkerResult.events()` post-walk); the `walker-result.invalid-arm-paired-with-author-error` invariant pins the carrier-vs-event consistency contract.
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

The cure this draft commits to: separate the *consumer concerns* into independent *carriers*; produce each by an independent *total walker* over the SDL; let the output field carry the carriers as fixed-meaning non-Optional slots whose arms — including the explicit `No<Family>` and `Invalid<Family>` arms — are part of each family's domain model. No top-level sealed hierarchy; impossible combinations are excluded by dispatch-time election rather than by type. Walkers are pure functions over graphql-java primitives returning `WalkerResult` records; the carrier arm pins the structural outcome (valid, absent, invalid) while events bundled in the same result explain the prose. Each axis is independently unit-testable.

The principle is not "minimise the model"; it is "make the model honest about what each consumer needs, including failure." The cross-product encoding hides axes; the per-carrier encoding surfaces them. Optional encodings hide failure as nullability; the `No<Family>` / `Invalid<Family>` encoding surfaces it as structure. The walker abstraction makes each axis individually testable, individually evolvable, individually replaceable. Hidden axes drift; surfaced axes get the compiler's help — and the unit test's.
