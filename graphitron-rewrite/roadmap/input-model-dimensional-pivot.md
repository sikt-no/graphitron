---
id: R222
title: "Input model: walker-driven carriers on output fields"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-25
---

# Input model: walker-driven carriers on output fields

The input-type classification surface has been the most-churned model area in the rewrite over the last quarter: R94, R96, R150, R155, R178, R191, R205, R210, R211, R215 all landed surgical patches on it, and Backlog still carries R171, R97, R213, R209, R200, R195, R98, R220, R193, R172 on the same surface. R164 (`field-model-two-axis-pivot`) names the underlying disease one layer over: a sealed hierarchy that tries to encode a multi-dimensional space as a one-dimensional permit set.

Each consumer concern is its own *carrier*; each carrier is a sealed family with valid arms plus an explicit absent arm (`No<Family>`); each is produced by an independent *walker* over an SDL substrate that returns a `WalkerResult` — a sealed `Ok(carrier, diagnostics)` / `Err(errors, diagnostics)` — so validity is the wrapper's concern and the carriers themselves are never invalid. Classification runs to completion across every output field regardless of how many walkers return `Err`; downstream *generation* (validator cross-checks, generators, per-type emitters) is what gets blocked when any walker errored. All carriers attach as slots on `OutputField` (existing sealed interface; its permit records `RootField` / `ChildField` already carry carrier-independent state like `DomainReturnType`). Every slot is non-Optional and always populated with a valid arm or `No<Family>`. No top-level sealed hierarchy. No shared recursive tree. No common classification carrier. No shared mutable diagnostic sink. No per-input substrate, no per-input map on the classification artifact: validation is per-output-field like every other consumer concern, because the validation event fires at the resolver method-arg boundary that the output field owns.

## What is

`GraphitronType` permits two sibling input-like roots: `InputType` (four leaves at `GraphitronType.java:323-385`) and `TableInputType` (one record at `GraphitronType.java:400-407`). The five permits encode three independent axes (backing-class kind, table-binding and where it came from, eager column classification) onto one identity slot. The cross product is sparse: the table-bound × non-jOOQ-TableRecord-backed × eager-classified cell is `TableInputType`; the table-bound × jOOQ-TableRecord-backed cell is `JooqTableRecordInputType`; the unbound row has four permits that differ only on backing-class kind.

`TypeBuilder.buildInputType` (`TypeBuilder.java:1000-1026`) already classifies a directiveless input as `TableInputType` when exactly one consumer's return type is a `@table` type, via `findReturnTablesForInput` — an O(N) back-scan over all schema fields. Whether an SDL input is "table-bound" is *already* a property of the consumer, not the input declaration. R215's lift (Done) admits `InputField.UnboundField` into `TableInputType.inputFields()`, collapsing the "eager classification" axis.

Nine consumer sites discriminate on the split today: `GraphitronSchemaValidator.java:80-81`, `MutationInputResolver.java:368`, `EnumMappingResolver.java:303`, `CatalogBuilder.java:206 / 498 / 570 / 639`, `FieldBuilder.java:967`, `TypeBuilder.java:209-210`. Each asks "does the input have a table?" or "what's its backing class?" by switching on permit identity, then proceeds.

R171 (Backlog) proposes a sealed `InputLikeType` parent. That fix is structural and leaves the cross-product encoding intact. The deeper issue: classification produces one heterogeneous output that every consumer then re-discriminates by role. The cure is to surface the roles directly and have classification produce them in parallel.

## What's to be: walkers populate carriers; carriers live where the emit reads them

- **Substrate-parametric walkers.** Each walker is `Walker<S, C>` where `S` is the SDL substrate it walks and `C` is the carrier family it produces. R222 ships only `S = GraphQLFieldDefinition` walkers; the generic signature is forward-compat for R226's type-level classification and any future axes. The walker returns `WalkerResult<C>` — a sealed `Ok(carrier, diagnostics)` / `Err(errors, diagnostics)`. Walkers are pure functions over `graphql.schema.*` primitives; they do not consult a shared graphitron-internal classification model and do not depend on a shared mutable sink.
- **All carriers live on `OutputField`.** Every carrier attaches as a non-Optional slot on `OutputField`, the existing sealed interface (`extends GraphitronField permits RootField, ChildField`); the carrier-getters land as abstract methods, and the permit records carry the storage as record components. The permits already host carrier-independent identity (`DomainReturnType`, the producer-of-source story), so the addition is purely additive. No per-input map, no per-input model record. Every slot is populated with exactly one arm — a valid arm or the explicit absent arm (`No<Family>`); consumers pattern-match the arm at use time; absence is a first-class domain state, not a present/missing flag. There is no `Invalid<Family>` arm: structural failure is the wrapper's concern, not the carrier's, and consumers never see one.
- **Each output field elects its walkers.** During classification, the field's directive set + return-type shape drive walker dispatch procedurally (described in "Walker dispatch" below). No top-level sealed family on the field; impossible combinations are excluded at dispatch. Slots whose walker was not elected default to the family's `No<Family>` arm.
- **Walkers are independently unit-testable.** Parse a small SDL fragment, pull the `GraphQLFieldDefinition`, run the walker, assert on the returned `WalkerResult` (the `Ok`/`Err` arm, the carrier on `Ok`, the errors on `Err`, the diagnostics list on both). Pure function in, pure sealed-result out; no mocks required. Pipeline coverage comes as a by-product when downstream emitters consume the carriers.
- **Schema diagnostics ride with the walker result.** The carrier shape is no longer 1-1 with the SDL. The orchestrator collects every `WalkerResult` post-walk; non-error diagnostics (severity Warning, Information, Hint — from both `Ok` and `Err` results) flow to `BuildContext` and to the LSP wire surface; errors (only ever on `Err` results) accumulate and block downstream generation (validator cross-checks, generators, per-type emitters), surfacing all `AuthorError`s in one pass rather than aborting on the first. Classification itself runs to completion regardless — every walker is invoked on every output field, and every `WalkerResult` is collected — so the LSP can consume populated carrier slots and diagnostics for the parts of the SDL that classified cleanly even when other parts errored. Downstream consumers run only when every `WalkerResult` was `Ok`, so they never observe a failure state. The wrapper's `Ok`/`Err` arms make this an enforced contract: an `Err` walker cannot produce a carrier to install, and the slot stays in `No<Family>` for the rest of the classification pass.

### The carrier vocabulary

| Carrier family | Substrate | Purpose | Valid-arm shape |
|---|---|---|---|
| `Pagination` | `GraphQLFieldDefinition` | first/after/last/before (keyset); consumer emits ORDER BY + LIMIT + keyset predicate | single valid arm (`Pagination.Of`), flat |
| `Ordering` | `GraphQLFieldDefinition` | `@orderBy`-family directives; consumer emits ORDER BY | single valid arm (`Ordering.Of`), flat |
| `PredicateCarrier` | `GraphQLFieldDefinition` | predicates that filter (`Condition`) or identify rows to act on (`LookupRows`) | two valid arms (`Condition`, `LookupRows`), both flat |
| `MethodArguments` | `GraphQLFieldDefinition` | param bindings for a Java method call (`@service`, `@externalField`, `@tableMethod`'s table-returning call) | single valid arm (`MethodArguments.Of`) holding a list of per-param `MethodArgumentBinding`; R164 carries the binding-variant family |
| `InsertRows` | `GraphQLFieldDefinition` | row plans to INSERT; compound (R122) carries parent + FK-threaded children | single valid arm (`InsertRows.Of`), tree-shaped, R122 carries internal shape |
| `UpdateRows` | `GraphQLFieldDefinition` | column-value bindings to write | single valid arm (`UpdateRows.Of`), flat |
| `ValidationShape` | `GraphQLFieldDefinition` | Jakarta-validation type shape for this output field's args (R94 successor, formerly `InputRecordShape`); consumer is `InputRecordGenerator`, which emits one validation POJO per output-field × input-type-typed-arg | single valid arm (`ValidationShape.Of`), holds per-arg validation-target metadata |

Each carrier family is a sealed interface. The valid arms above are the family's "produced something useful" cases. Every family also carries an explicit `No<Family>` arm — "happy zero" — recording that the walker ran and found nothing meaningful (no condition args, no ordering directives, etc.); `No<Family>` is a domain state, not a nullability concern, and consumers pattern-match it exhaustively. `ValidationShape`'s `NoValidationShape` arm is scaffolding for R98's eventual "this input opts out of Jakarta-validation emit" case; today's walker always produces `Of(...)` because every SDL input has at least nullability-derived constraints. There is no `Invalid<Family>` arm on any family: when a walker hits structurally broken input, the wrapper turns `Err`, the orchestrator collects the errors, and downstream generation is blocked before any consumer reads the slot. Carrier arms are exclusively "happy" — valid or `No`.

Every carrier in R222 walks `GraphQLFieldDefinition`. The substrate column stays in the table because `Walker<S, C>` is substrate-parametric by signature, and future walkers (R226's type-level classification, a hypothetical `EnumValidationWalker`) target different substrates; R222 itself uses one. The orchestrator iterates output fields once and runs every elected walker against each; consumers read the resulting carrier slots on `OutputField`.

**PredicateCarrier's two valid arms** share predicate shape but differ in semantic role. `Condition` is the default for SQL-emitting *read* fields; `LookupRows` is the default for *mutation* fields. The walker's bailout-restart pattern handles role-discovery lazily: start with the default arm's accumulator; if a sentinel directive shows up anywhere in the walk, discard and restart with the other arm. Sentinels:

- `@lookupKey` on a read field (or on a nested input field used by a read) → flip to `LookupRows`
- `@multirows` (working name) on a mutation field → flip to `Condition`

The valid-arm choice lives inside the predicate-binding axis, not at the field level. The walker chooses the arm by construction; consumers pattern-match the arm at use time (`Condition`, `LookupRows`, or `NoPredicates`).

**MethodArguments' internal shape.** R164 territory. The carrier holds an ordered list of per-param bindings; each binding is one of: scalar passthrough, decoded NodeID, `BackingClass`-populated. R222 reserves the carrier; R164 ships the binding-variant sealed family and the `BackingClass` attachment.

**InsertRows' internal shape.** R122 territory. The simple INSERT is the one-node degenerate case of a tree; compound INSERTs (parent + FK-threaded children) carry the full tree with FK descriptors. R222 reserves the carrier; R122 ships the tree and the FK threading.

### The walker abstraction

Each walker returns a sealed `WalkerResult<C>` that splits success from failure at the wrapper, not inside the carrier. The walker abstraction is parametric on its substrate `S` and carrier `C`:

```java
sealed interface WalkerResult<C> {
    List<Diagnostic> diagnostics();

    record Ok<C>(C carrier, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Ok {
            // Ok cannot carry Error-severity diagnostics; Error severity is AuthorError's
            // structural channel, surfaced via the Err arm only.
            if (diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.Error))
                throw new IllegalArgumentException("Ok cannot carry Error-severity diagnostics");
        }
    }
    record Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Err {
            if (errors.isEmpty()) throw new IllegalArgumentException("Err must carry at least one error");
        }
    }
}

interface Walker<S, C> {
    /**
     * Reduce an SDL substrate (an output field, an input type, ...) into a WalkerResult.
     * On success the result is Ok and the carrier is one arm of C's sealed family —
     * a valid arm if the walk yielded one, No<C> if the walk found nothing meaningful.
     * On structural failure the result is Err carrying one or more AuthorErrors;
     * the orchestrator drains them post-walk and blocks downstream generation
     * (classification itself runs to completion regardless). Non-error diagnostics
     * (Warning / Information / Hint severity) ride on either arm. The walker has no
     * shared mutable state and no injected sink.
     */
    WalkerResult<C> walk(S substrate);
}
```

Every R222 walker is `Walker<GraphQLFieldDefinition, ?>`. The `Walker<S, C>` signature is substrate-parametric for forward-compat with R226's type-level classification, but R222 itself uses one substrate. A walker that surfaces multiple distinct errors in one pass (e.g. two conflicting directives at different SDL locations) returns a single `Err` whose `errors` list contains every one; the orchestrator surfaces them all before blocking generation. `Diagnostic` is an LSP-aligned sealed family carrying non-error events (see "Schema diagnostics" below); `AuthorError` is the existing `Rejection.AuthorError` sealed family from the codebase, surfaced to the LSP wire as severity=Error diagnostics by the wire-format adapter.

The carrier type `C` is the walker's product. Walkers are built on `graphql.schema.GraphQLArgument` / `GraphQLInputObjectField` / `GraphQLInputType` directly; the walk reads `getDirectives()`, `getType()`, and field-type-introspection methods. The walker's logic is "look at each piece of the substrate, accumulate into the typed accumulator, build the carrier arm at the end." No graphitron-internal recursive model is required.

**Bailout-restart.** The predicate walker illustrates:

```java
class ConditionWalker implements Walker<GraphQLFieldDefinition, PredicateCarrier> {
    private final boolean defaultIsLookupRows;  // true for mutations, false for reads

    public WalkerResult<PredicateCarrier> walk(GraphQLFieldDefinition field) {
        var diagnostics = new ArrayList<Diagnostic>();
        var errors = new ArrayList<AuthorError>();
        var primary = defaultIsLookupRows ? new LookupRowsAccumulator() : new ConditionAccumulator();
        var bailout = walkInto(field, primary, diagnostics, errors);
        if (bailout.isEmpty()) return finish(primary, diagnostics, errors);

        diagnostics.add(new PredicateRoleSwitched(bailout.get().location(), primary.armName(), ...));
        var secondary = defaultIsLookupRows ? new ConditionAccumulator() : new LookupRowsAccumulator();
        walkInto(field, secondary, diagnostics, errors);
        return finish(secondary, diagnostics, errors);
    }

    private WalkerResult<PredicateCarrier> finish(PredicateAccumulator acc, List<Diagnostic> diagnostics, List<AuthorError> errors) {
        return errors.isEmpty()
            ? new WalkerResult.Ok<>(acc.build(), diagnostics)
            : new WalkerResult.Err<>(List.copyOf(errors), diagnostics);
    }
}
```

Each accumulator's `build()` returns the carrier in its happy-path shape: a valid arm if predicates accrued, `NoPredicates` if nothing did. The accumulator does not return `Invalid` — it cannot, because the family has no such arm. Structural failure during the walk pushes an `AuthorError` onto the error list, and `finish` lifts that into an `Err` wrapper. The bailout signal is the sentinel directive; the walker's outer loop doesn't pre-scan. Role discovery is structural — sentinel triggers restart, no separate role-decision pass.

**Unit-testability is load-bearing.** Each walker is a pure function: graphql-java primitives in, `WalkerResult` out. No mocks, no shared sink, no graphitron classification context required. Tests assert against the sealed result:

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

    assertThat(result).isInstanceOfSatisfying(WalkerResult.Ok.class, ok -> {
        assertThat(ok.carrier()).isEqualTo(new Condition(List.of(
            new MethodSuppliedPredicate("film_in_genre", List.of("genre"))
        )));
        assertThat(ok.diagnostics()).isEmpty();
    });
}
```

Failure paths assert `WalkerResult.Err` and inspect the `errors` and `diagnostics` lists. Pipeline-tier tests follow as by-product; the walker's own tests do not depend on downstream emitters.

### Walker dispatch

The output field decides which walkers to run from its own properties:

| Field shape | Walkers elected |
|---|---|
| Plain read (`Query.x`, no `@service`) | ValidationShape, Pagination?, Ordering?, PredicateCarrier? |
| Read with `@service` | ValidationShape, MethodArguments (total — short-circuits SQL-side walkers) |
| Read with `@externalField` | ValidationShape, MethodArguments (total) |
| Read with `@tableMethod` | ValidationShape, MethodArguments, Pagination?, Ordering? |
| INSERT mutation | ValidationShape, InsertRows |
| UPDATE mutation | ValidationShape, PredicateCarrier, UpdateRows |
| DELETE mutation | ValidationShape, PredicateCarrier |
| UPSERT mutation | ValidationShape, InsertRows, PredicateCarrier |
| `@service` mutation | ValidationShape, MethodArguments (total) |

ValidationShape runs on every field with at least one input-type-typed arg; it produces `NoValidationShape` on fields whose args carry no constraint directives at all. The SQL-side walkers (`PredicateCarrier`, `Pagination`, `Ordering`) and `MethodArguments` short-circuit each other per the totalising rules; `ValidationShape` does not, because validation runs alongside whatever method path the resolver takes.

Dispatch is a procedural decision: read the field's directive set + return-type shape, decide which walkers to invoke, pass them the field, collect carriers into the output field's slots. `@service` and `@externalField` are *total* for the SQL-side carriers: they short-circuit to MethodArguments + ValidationShape only, regardless of position. Constraints between walkers live in the dispatch logic, not in a sealed type.

**Three options were considered for the dispatch rule itself.** *(a) A sealed type at the field level* — each combination of electable walkers as a permit. Rejected: this re-introduces the cross-product encoding the pivot exists to dissolve; adding a walker multiplies the permits. *(b) Capability-marker-driven dispatch* — read the field's existing capability markers (`SqlGeneratingField`, `MethodBackedField`) and map each capability to a `Set<Walker<?, ?>>`. Rejected: walker election isn't a pure capability mapping. It involves negation (`@service` *excludes* every other SQL-side walker, totalising), conditional fanout (`@tableMethod` elects MethodArguments AND Pagination AND Ordering), and ordering semantics (PredicateCarrier sentinel directives trigger arm flips that no capability marker carries). A capability-to-walker-set map flattens these decisions; the procedural function keeps them readable in one place. *(c) Procedural dispatch* (chosen) — a single function reads the field's properties and decides which walkers to invoke. The constraint rules live in code that reads top-to-bottom, are individually testable, and don't pretend to be type-enforced when they're not.

Adding a new carrier (Aggregation, GroupBy, future axes) is additive: new walker, new slot on `OutputField`, new entry in the dispatch table. No enclosing taxonomy churns.

### Schema diagnostics: LSP-aligned, split at the wrapper

The walker model breaks SDL-shape 1-1 correspondence in several places:

- A walker drops a directive that is no longer load-bearing (`@table` on input, `@record(class:)` on input, `@value`).
- A walker bails and restarts; the bailout is worth surfacing when its trigger is subtle (e.g. `@lookupKey` deep in a nested input).
- A walker resolves a binding (input field → column, `@nodeId` → target type, `@condition` → method) at a specific SDL location, and surfacing the resolution makes the editor experience richer (hover info, jump-to-definition).
- Multiple walkers may surface findings at the same SDL location.
- A walker produces a `No<C>` arm with a non-empty diagnostic trail (e.g. dispatch elected the walker but the field had no relevant args).
- A walker hits structurally broken input, accumulates one or more `AuthorError`s, and returns `Err` instead of `Ok`.

`WalkerResult<C>` separates halt-grade errors from non-halt diagnostics at the wrapper, not inside the carrier. The carrier family contains only "happy" arms (valid or `No<C>`). On `Err`, halt-grade errors live in `errors: List<AuthorError>` (the existing rejection channel) alongside `diagnostics: List<Diagnostic>` (non-error events surfaced before the failure). On `Ok`, diagnostics ride with the carrier; the wrapper invariant forbids Error-severity diagnostics on the `Ok` arm because Error is structurally an `AuthorError` halt.

`Diagnostic` is LSP-aligned by design: each arm exposes the fields LSP clients consume directly (`severity`, `code`, `message`, `tags`, `relatedInformation`), so the wire-format adapter is a mechanical projection rather than a translation layer.

```java
sealed interface Diagnostic {
    SourceLocation location();           // converted to LSP Range at the wire boundary
    Severity severity();
    String code();                       // stable id, e.g. "graphitron.predicate-role-switched"
    default String source() { return "graphitron"; }
    String message();
    default List<Tag> tags() { return List.of(); }
    default List<Related> relatedInformation() { return List.of(); }

    enum Severity { Error, Warning, Information, Hint }   // mirrors LSP DiagnosticSeverity
    enum Tag { Unnecessary, Deprecated }                  // mirrors LSP DiagnosticTag
    record Related(SourceLocation location, String message) {}
}

// Arms keep type-safe pattern matching; each pins its LSP shape:

record DirectiveDeprecated(SourceLocation location, String directive, String reason) implements Diagnostic {
    public Severity severity() { return Severity.Warning; }
    public String code() { return "graphitron.directive-deprecated"; }
    public String message() { return "@" + directive + " is no longer load-bearing here: " + reason; }
    public List<Tag> tags() { return List.of(Tag.Deprecated); }
}
record DirectiveDropped(SourceLocation location, String directive, String reason) implements Diagnostic {
    public Severity severity() { return Severity.Information; }
    public String code() { return "graphitron.directive-dropped"; }
    public String message() { return "@" + directive + " ignored: " + reason; }
    public List<Tag> tags() { return List.of(Tag.Unnecessary); }
}
record PredicateRoleSwitched(SourceLocation location, String fromArm, String toArm, String trigger, SourceLocation triggerLoc) implements Diagnostic {
    public Severity severity() { return Severity.Information; }
    public String code() { return "graphitron.predicate-role-switched"; }
    public String message() { return "Predicate role switched from " + fromArm + " to " + toArm + " (triggered by " + trigger + ")"; }
    public List<Related> relatedInformation() { return List.of(new Related(triggerLoc, "trigger: " + trigger)); }
}
record CarrierProducedNothing(SourceLocation location, String walker, String reason) implements Diagnostic {
    public Severity severity() { return Severity.Hint; }
    public String code() { return "graphitron.carrier-produced-nothing"; }
    public String message() { return walker + " produced No<Family>: " + reason; }
}

// Future arms (resolution events worth surfacing to the LSP):
//   record BindingResolved(loc, sdlField, targetColumn) implements Diagnostic { severity=Hint; ... }
//   record WalkerElected(loc, walkerName, reason) implements Diagnostic { severity=Hint; ... }
//   record DirectiveResolved(loc, directive, target) implements Diagnostic { severity=Hint; ... }

// AuthorError is the existing Rejection.AuthorError sealed family from the codebase
// (UnknownName, Structural, AccessorMismatch, ...). The walker carries the leaf relevant
// to its failure mode. The wire-format adapter projects each AuthorError into an LSP
// Diagnostic with severity=Error so editors see one unified stream.
```

The arms keep type-safe pattern matching on the walker side; the LSP wire-format adapter reads the LSP fields (`severity`, `code`, ...) and emits a wire-shape Diagnostic without inventing translation rules.

**Classification runs to completion; errors block generation.** This distinction is load-bearing for the LSP. An `Err` from one walker does not halt classification, does not skip subsequent walkers, and does not abandon the substrate it belongs to. Every walker is invoked on every substrate instance regardless of what other walkers returned, and every `WalkerResult` is collected. What changes when any walker returned `Err` is that the *downstream generation* phase (validator cross-checks, generators, per-type emitters) is not scheduled — the build exits non-zero with every accumulated `AuthorError` and `Diagnostic` reported, but the classification output it produced is still observable. The LSP consumes that output: it sees populated carrier slots for substrates that classified cleanly, plus the diagnostic stream for the parts that didn't, and renders hover / inlay hints / squiggles uniformly without needing a "stop at first error" mode. The Maven build sees the same output and stops at the generation phase boundary instead of dropping the classifier's work on the floor.

**Aggregation.**

- Classification iterates substrates (output fields and inputs), dispatches walkers, and collects each `WalkerResult` per slot. Every substrate instance is walked by every elected walker; an `Err` on one walk doesn't short-circuit the others.
- For every `Ok` result, the walker's `carrier` installs into the corresponding slot; `diagnostics` concatenate into a flat per-build diagnostic stream.
- For every `Err` result, the slot stays at `No<Family>` (the orchestrator's default), the walker's `errors` accumulate into the build's error list, and `diagnostics` concatenate as above.
- Post-walk, if any walker returned `Err`, the orchestrator emits every accumulated `AuthorError` (via the existing `Rejection.AuthorError` shape) along with every accumulated `Diagnostic`, and blocks the downstream generation phase from running. The build surfaces all errors in one pass rather than aborting on the first; the LSP receives the full diagnostic stream plus whatever carrier slots did populate.
- If every walker returned `Ok`, the orchestrator routes the diagnostic stream by severity: `Warning`-grade diagnostics (`DirectiveDeprecated`, ...) → `BuildContext` via `TypeBuilder.emitDirectiveIgnoredWarnings`'s existing channel; `Information` and `Hint`-grade diagnostics → the LSP wire surface (consumed by `graphitron-lsp` for hover, inlay hints, decorations); the build-side console silences them by default. Downstream consumers proceed.
- The LSP wire-format adapter projects both channels into LSP `Diagnostic` records: each `AuthorError` maps to severity=Error with a derived `code` per leaf type; each `Diagnostic` carries its LSP-shape fields through unchanged.

Downstream consumers (validator cross-checks, generators, per-type emitters) only ever observe `Ok` carriers because the orchestrator never schedules the generation phase when any `WalkerResult` was `Err`. The "downstream code never sees a failure state" invariant is enforced by the `Ok`/`Err` wrapper plus the classification/generation phase split, not by an `Invalid` arm in the carrier family. Introspection consumers (the LSP) read the classification output directly, after the classification phase but independent of whether generation ran.

**LSP delivery from Phase 1.** The wire is already wired: `Workspace.setBuildOutput(BuildArtifacts, ValidationReport)` in `graphitron-lsp` triggers `recalculateListener` → `Diagnostics.compute` per URI → `LanguageClient.publishDiagnostics`. Today `Diagnostics.validatorDiagnostics` projects `ValidationError` and `BuildWarning` from `ValidationReport` to LSP `Diagnostic` records. R222 extends the existing channel rather than building a new one:

- `ValidationReport` (existing public artifact) gains a `List<Diagnostic> walkerDiagnostics` slot, additive — `errors` and `warnings` slots unchanged. R226's `BuildWarning`-producer migration is what later collapses the three slots into one.
- `Diagnostics.validatorDiagnostics` gains an arm projecting the walker `Diagnostic` family. `code` → LSP `Diagnostic.code`, `tags` → LSP `tags`, `relatedInformation` → LSP `relatedInformation`, `severity` → LSP `DiagnosticSeverity`. `AuthorError` continues to project to severity=Error with `code` derived per leaf type (`AuthorError.UnknownName` → `"graphitron.unknown-name"`, etc.).
- Source attribution: `"graphitron"` for the walker-output arm (existing arms keep `"graphitron-lsp"` and `"graphitron-validator"`); pin in the Phase 1 PR rather than letting it accrete.
- Position conversion reuses the existing `parsing/Positions.java` `SourceLocation` → LSP `Position` helper.

`ValidationShapeWalker`'s output is the smoke test: when a Phase 1 build flows through the LSP, the editor sees walker-emitted `Diagnostic`s in `publishDiagnostics` alongside the validator-projected ones. Definition / hover / inlay-hint handlers continue to read `LspSchemaSnapshot` for now; extending them to read walker carriers (`MethodArguments.Of.methodRef`, etc.) is a follow-up item, not Phase 1 scope.

R226 (classification dimensional pivot: diagnostics off the model) is forward-compatible by design: the `Diagnostic` family R222 ships *is* the unified surface R226 needs. R226 retires the `UnclassifiedType` / `UnclassifiedField` permits in favour of the same wrapper pattern — failure becomes an `Err` carrying `AuthorError`s, non-error events carry `Diagnostic`s, and the LSP wire format consumes both. No "eventual Diagnostic family substitution" intermediary is needed; the shape lands correctly from Phase 1.

### Carrier storage

All carriers attach as slots on the existing `OutputField` sealed interface. No new model record. No per-input map on the classification artifact. No new identity wrapper.

`OutputField` pre-exists the pivot (`public sealed interface OutputField extends GraphitronField permits RootField, ChildField`); the permits already host carrier-independent identity (`DomainReturnType`, the producer-of-source story). Carrier-getters land as abstract methods on the interface; each permit record (`RootField`, `ChildField`) declares the matching record components and implements the getters. Consumers pattern-match through the interface (`field.predicate()`, etc.):

```java
// Shape sketch; the slots land on each permit record as the corresponding
// walker ships, not all at once.
sealed interface OutputField extends GraphitronField permits RootField, ChildField {
    // ... existing methods ...
    ValidationShape validation();
    Pagination pagination();
    Ordering ordering();
    PredicateCarrier predicate();
    MethodArguments methodArguments();
    InsertRows insertRows();
    UpdateRows updateRows();
}
// RootField and ChildField each declare the slots as record components and implement the interface methods.
```

`InputRecordGenerator` (the validation POJO emitter) walks output fields, reads each field's `validation()` slot, and emits one POJO per input-type-typed arg the carrier covers. POJO names take the form `{ParentType}_{Field}_{InputType}Validation` (mechanical; user code never sees these names — the POJO is internal scaffolding handed to `jakarta.validation.Validator#validate` from the resolver pre-step). No cross-consumer dedup: two consumers of the same input type each get their own POJO. R98's multi-source-validation case is the default behaviour, not a special path.

`InputTypeGenerator` (schema-rebuild emitter, one `<TypeName>Type.type()` class per SDL input declaration) reads no classification carrier. Today its data source is the legacy `InputType` / `TableInputType` permits' cached `schemaType()`; Phase 4 deletes those permits, so the generator switches to iterating `schema.getInputObjectTypes()` directly via graphql-java. The schema-rebuild concern stays per-input by construction (one input type, one runtime graphql-java object, identical across consumers); dedup is natural and correct here, unlike the validation POJO case.

Where a walker or diagnostic needs to address a *field* on an input — e.g. the source location attached to a `Diagnostic` — graphql-java's `FieldCoordinates` is the rewrite-wide key for type+field identity (already in use across `GraphitronSchema.fields`, `FieldRegistry`, `MappingsConstantNameDedup`). R222 doesn't ship a map of that shape, but the pattern is the one to reach for.

Each slot is a sealed family; the record holds exactly one arm. Slots are always populated; the arm answers what the field actually does on that dimension:

- Walker was not elected for this field → the slot defaults to the family's `No<Family>` arm.
- Walker was elected and returned `Ok` with a valid result → the slot holds the relevant valid arm (e.g. `Condition`, `MethodArguments.Of`).
- Walker was elected and returned `Ok` with nothing meaningful → the slot holds `No<Family>` (the diagnostic stream may carry a `CarrierProducedNothing` event).
- Walker was elected and returned `Err` → the slot stays at `No<Family>` for the rest of classification. The wrapper's errors join the build's accumulated error list; downstream generation is blocked, so no per-type generator or validator consumer observes the `No<Family>` placeholder. Introspection consumers (the LSP) may read the slot directly and render the error diagnostic alongside.

Pattern matches at use sites are exhaustive against the full family — there is no "did the walker run" question to ask, and there is no `Invalid` arm to forget. The compiler's exhaustiveness check is the safety net for every consumer migration.

The carrier types:

```java
sealed interface PredicateCarrier {
    record Condition(List<Predicate> predicates) implements PredicateCarrier {}
    record LookupRows(List<KeyPredicate> keys) implements PredicateCarrier {}
    record NoPredicates() implements PredicateCarrier {}
}

sealed interface MethodArguments {
    record Of(List<MethodArgumentBinding> paramBindings) implements MethodArguments {}
    record NoMethodArguments() implements MethodArguments {}
}
// MethodArgumentBinding is R164's sealed family with arms for scalar passthrough,
// NodeID-decoded, BackingClass-populated, etc.

sealed interface Pagination {
    record Of(...) implements Pagination {}
    record NoPagination() implements Pagination {}
}

sealed interface Ordering {
    record Of(...) implements Ordering {}
    record NoOrdering() implements Ordering {}
}

sealed interface InsertRows {              // R122 carries the valid-arm tree shape
    record Of(...) implements InsertRows {}
    record NoInsertRows() implements InsertRows {}
}

sealed interface UpdateRows {
    record Of(List<ColumnValueBinding> columns) implements UpdateRows {}
    record NoUpdateRows() implements UpdateRows {}
}

sealed interface ValidationShape {              // per-output-field carrier; R94's POJO target
    record Of(...) implements ValidationShape {}
    record NoValidationShape() implements ValidationShape {}
}
```

Every family is two-arm: a valid arm (or two, for `PredicateCarrier`) and the explicit `No<Family>` arm. No family carries an `Invalid` arm. Structural failure is the `WalkerResult.Err` wrapper's concern, not the carrier's; the wrapper guarantees that downstream generators reading the slot only see valid or `No`.

`BackingClass` stays in vocabulary as a three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) attached per `MethodArgumentBinding` variant by R164. R222 ships the `BackingClass` family declaration as vocabulary; R164 wires it onto MethodArguments' internal binding arms. `LoadBearingGuaranteeAuditTest` recognises the no-producer state as "awaiting R164" via a `@LoadBearingPlaceholder("R164 method-argument-binding arms")` annotation.

### `@table` and `@record(class:)` on inputs are dropped

The pivot drops two directives as binding sources on `INPUT_OBJECT`. Each is read once at classification, surfaced as a `DirectiveDeprecated` event, then ignored for binding purposes. Phase 6 narrows both SDL directive declarations to drop `INPUT_OBJECT` and sweeps every decorated SDL input across fixtures.

**`@table` on input.** Table-binding sources collapse to one: the enclosing field's `@table` return (consumer-derivation). The SQL-emitting walkers read the consumer's return-type-bound table at the visit site; no input-side directive participates. Sakila's 16 `@table`-decorated inputs all consume at `@table`-returning SQL-form consumers; the Phase 6 sweep confirms no orphans.

**`@record(class:)` on input.** The directive's "type the deserialization target" function collapses entirely. The materialization target is the user's declared service-method param type (`BackingClass`), read by reflection at the MethodArgumentsWalker site — not an input-side directive. `InputRecordGenerator`'s per-output-field POJOs are for Jakarta-validation use only, not DTO targets.

### Directives the new model carries

| Directive | Pre-pivot home | Post-pivot home |
|---|---|---|
| `@record(class: ...)` on input type | identity of four `*InputType` permits | **ignored**; `DirectiveDeprecated` event at the directive's `SourceLocation`. The materialization target lives on R164's `MethodArgumentBinding` arm. Per-output-field validation POJOs are emitted from the SDL shape regardless |
| `@table` on input type | `TableInputType.table` / `JooqTableRecordInputType.table` | **ignored**; `DirectiveDeprecated` event at the directive's `SourceLocation`. Binding resolves from the enclosing field's `@table` return at the walker's visit site |
| `@condition` on input *type* | read from the assembled `GraphQLInputObjectType` | unchanged: read by `ConditionWalker` from the `GraphQLInputObjectType` substrate; contributes a method-supplied predicate to the `Condition` accumulator |
| `@condition` on input *field* / arg | per-arg / per-field condition | read by `ConditionWalker`; contributes a predicate scoped to that arg/field |
| `@lookupKey` on input field / arg | n/a (new sentinel) | sentinel directive triggering `ConditionWalker`'s bailout from `Condition` to `LookupRows` |
| `@multirows` (working name) on field | n/a (new sentinel) | sentinel directive triggering `ConditionWalker`'s bailout from `LookupRows` to `Condition` on mutation fields |
| `@nodeId(typename: ...)` on input field | read by `NodeIdLeafResolver` on the SQL-side path | universal: `ConditionWalker` / `LookupRowsWalker` read it for FK-target resolution; R164's `MethodArgumentBinding` decoded-NodeID arm reads it for domain-method calls |
| `@reference(path:)` on input field | read by `NodeIdLeafResolver` for `@nodeId` leaves | unchanged on the `@nodeId` path; **R122 extends the directive's reach to nested-input slots** (the path's terminal element drives the FK threading in `InsertRowsWalker`). R222 preserves the directive's existing applicability and does not touch the `@nodeId`-path resolver |
| `@inputBean` / `@enumMap` / `@field(name:)` on input *fields* | read by `InputBeanResolver`, `EnumMappingResolver` | unchanged: those resolvers continue to consult the assembled-schema; R200 / R195 / R98 stay scoped where they are |
| `@value` on input field | R144's classifier-side partition reads it as a manual marker for SET-clause columns | **removed as redundant scaffolding**; the partition is fully derivable from catalog PK membership inside `UpdateRowsWalker`. `@value` carries no information today that the walker cannot compute from the catalog, so removing it is mechanical and changes no behaviour |

### What collapses

| Pre-pivot | Post-pivot |
|---|---|
| `GraphitronType.InputType` 4-arm sealed root | retires entirely. No replacement input-side model record; `BackingClass` surfaces on R164's `MethodArgumentBinding` arm |
| `GraphitronType.TableInputType` sibling root | retires entirely. Table fact lives inside SQL-emitting walkers' carriers (`Condition`, `LookupRows`, `UpdateRows`, etc.), sourced from the consumer's `@table` return at walker-invocation time |
| `JooqTableRecordInputType.table` slot, `TableInputType.table` slot | Both retire. Table for SQL emission lives inside the carrier produced by each walker, derived from the consumer at walker time. Table for MethodArguments lives on R164's per-param `BackingClass.JooqTableRecord.table` (the user's declared param type) |
| `TableInputType.inputFields` (stored, eager-classified) | retires. Classification happens per-walker, per-output-field, at dispatch time. The classified output lives on the walker's carrier, not on any per-input record |
| `HasInputRecordShape` capability marker (5 permits) | retires. `ValidationShape` is a per-output-field slot on `OutputField`; `InputRecordGenerator` walks output fields and emits one POJO per input-type-typed arg |
| `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg` | retire. Args no longer carry classified state; the output field carries the carriers |
| `TableInputArg.lookupKeyFields` / `setFields` slots (R144 DmlKind-driven partition) | retired from the model. `PredicateCarrier` is `LookupRows` for UPDATE/DELETE by default (the partition arm choice); `UpdateRows` carries the SET columns; PK-vs-non-PK derivation lives inside `UpdateRowsWalker` |
| 9 consumer-side discriminations on `TableInputType` (instanceof gates and switch arms) | retire; consumers read the relevant carrier slot on `OutputField` directly |
| `TypeBuilder.findReturnTablesForInput` back-scan | retires; the field's `@table` return is available at walker-invocation time per-field |
| `InputField.NestingField`, `InputField.UnboundField`, `InputField` family | refactored. The classification-output `InputField` hierarchy retires entirely; each walker carries its own typed accumulator. Where the same fact is needed by multiple walkers (e.g. `@nodeId @reference` decoded once), it is computed inside each walker — graphql-java is the shared substrate, not a graphitron-internal classification model. Walker-internal "column-bound" vs "unresolved" carriers stay typed and sealed inside each walker as needed |

The cross-cutting `GraphitronSchemaValidator.validateTableInputType` arm becomes "validate each carrier the field produced"; per-permit-identity dispatch retires entirely. Walkers surface structural errors as `WalkerResult.Err` and the orchestrator blocks the validator (a downstream-generation consumer) from running, so the validator's job narrows to cross-carrier consistency checks (e.g. `@service` field with a non-`NoPredicates` predicate slot is a producer bug, not an author error) rather than per-carrier structural validation.

## Three example walkers shipped in R222

R222 ships three walkers in tree as exemplars. All three are `Walker<GraphQLFieldDefinition, ?>`; together they cover the carrier-shape range:

- `ValidationShapeWalker` (Phase 1) — single-arm valid carrier, no bailout, no totalising directive. The simplest carrier shape; lights up the orchestrator-aggregation contract on one walker before `ConditionWalker` / `MethodArgumentsWalker` ship in Phase 2.
- `ConditionWalker` (Phase 2) — demonstrates the bailout/restart pattern on `PredicateCarrier`.
- `MethodArgumentsWalker` (Phase 2) — demonstrates the totalising directive on `MethodArguments`.

All three are independently unit-testable; `ValidationShapeWalker`'s downstream consumer (`InputRecordGenerator`, switching to per-output-field POJO emit) migrates alongside it in Phase 1.

### `ConditionWalker`

Produces a `PredicateCarrier.Condition` (or, on `@lookupKey` bailout, a `LookupRows`) for SQL-emitting read fields. Walks the field's args; for each arg, examines its directives (`@condition`, `@lookupKey`) and, if the arg is an INPUT_OBJECT, recurses into its fields. Accumulates predicates into the carrier.

Bailout: any `@lookupKey` encountered (on an arg or on a nested input field) triggers a restart with `LookupRowsAccumulator`. The result's diagnostics list records a `PredicateRoleSwitched` event (severity `Information`) with the trigger source location attached as `relatedInformation`.

```java
public WalkerResult<PredicateCarrier> walk(GraphQLFieldDefinition field) {
    var diagnostics = new ArrayList<Diagnostic>();
    var errors = new ArrayList<AuthorError>();
    var primary = new ConditionAccumulator();
    var bailout = walkInto(field, primary, diagnostics, errors);
    if (bailout.isEmpty()) return finish(primary, diagnostics, errors);

    diagnostics.add(new PredicateRoleSwitched(field.getDefinition().getSourceLocation(),
        "Condition", "LookupRows", "@lookupKey", bailout.get().location()));
    var secondary = new LookupRowsAccumulator();
    walkInto(field, secondary, diagnostics, errors);
    return finish(secondary, diagnostics, errors);
}
```

Each accumulator's `build()` returns a `PredicateCarrier` arm directly: a valid arm if predicates accrued, `NoPredicates` if nothing did. Structural failures push `AuthorError`s onto the error list; `finish` returns `Ok(build(), diagnostics)` if the list is empty, else `Err(errors, diagnostics)`.

Unit-test surface: `@condition` on input type / input field / arg, plain input fields, nested-input recursion, `@lookupKey` bailout (at each nesting depth), unresolved column → `Ok(NoPredicates, [CarrierProducedNothing])`, `@condition + @lookupKey` on the same field → `Err([AuthorError], diagnostics)`. Each case is an SDL fragment + walker invocation + `WalkerResult` assertion against the `Ok` / `Err` arm.

### `MethodArgumentsWalker`

Produces a `MethodArguments` arm for fields carrying `@service`, `@externalField`, or `@tableMethod`. Walks the field's args; for each arg, produces a `MethodArgumentBinding` placeholder (R164 lands the binding-variant family; R222 produces a single uniform `MethodArgumentBinding.Pending` arm or equivalent).

The walker is *total* for its electing directives: when invoked, it consumes all the field's args. The dispatch logic ensures it is the only walker invoked on `@service` / `@externalField` fields.

```java
public WalkerResult<MethodArguments> walk(GraphQLFieldDefinition field) {
    var diagnostics = new ArrayList<Diagnostic>();
    var errors = new ArrayList<AuthorError>();
    var bindings = new ArrayList<MethodArgumentBinding>();
    for (var arg : field.getArguments()) {
        var bound = bindArg(arg);     // R164 specialises the binding kind
        if (bound.isOk()) {
            bindings.add(bound.value());
        } else {
            errors.add(bound.error());   // collect every failure, not just the first
        }
    }
    if (!errors.isEmpty()) {
        return new WalkerResult.Err<>(List.copyOf(errors), diagnostics);
    }
    MethodArguments carrier = field.getArguments().isEmpty()
        ? new MethodArguments.NoMethodArguments()
        : new MethodArguments.Of(bindings);
    return new WalkerResult.Ok<>(carrier, diagnostics);
}
```

`NoMethodArguments` covers "field declares zero arguments" — a structural fact about the SDL substrate, framed uniformly with every other family's `No<Family>` arm as a domain state walker surfaces rather than a search-yielded-nothing outcome. `Of(emptyList)` would only arise in a hypothetical "all args were filtered out" path that R222 does not exercise. `bindArg` failures push `AuthorError`s onto the error list; the walker accumulates every failure rather than aborting on the first, then returns `Err` once the loop finishes. The structural contract is at the wrapper: the walker can't return `Ok` while carrying any errors, and the only way to build `Err` is to provide a non-empty list of `AuthorError`s (the record's compact constructor enforces this).

Unit-test surface: scalar arg, nested-input arg, mixed scalar+nested args, `@nodeId`-decorated arg, conflicting directives (e.g. `@condition` on an arg of a `@service` field) → `Err([AuthorError], diagnostics)`; two conflicting args at distinct locations → `Err` carrying both `AuthorError`s; no args → `Ok(NoMethodArguments, [])`. The R164 contract is exercised by extension tests R164 will add; R222's tests pin the walker's structural correctness against today's `MethodArgumentBinding.Pending` arm.

## Walkers not shipped in R222 (sibling items)

| Walker | Sibling item | Internal shape |
|---|---|---|
| `LookupRowsWalker` (paired with `ConditionWalker`) | new Backlog item | flat predicate list, `@lookupKey`/`@nodeId` triggered |
| `PaginationWalker` | new Backlog item | first/after/last/before slots |
| `OrderingWalker` | new Backlog item | `@orderBy`-family directive readout |
| `InsertRowsWalker` | **R122** (`compound-entity-mutations`) | tree of row plans + FK threading |
| `UpdateRowsWalker` | **R144 continuation** | column-value bindings; PK-derivation for the partition arm |

R222 reserves the carrier slot on `OutputField` for each but does not populate. Migration is walker-by-walker: each sibling item ships its walker + unit tests + the corresponding consumer migration, independently.

## What this absorbs from the open roadmap

| Item | Absorption mode |
|---|---|
| R171 (sealed `InputLikeType` parent) | Dissolves: no per-input model record survives the pivot, and no per-input map either; all carriers live as slots on `OutputField`. The parent-root fix becomes moot |
| R97 (deprecate `@table` on input types) | Phase 2 / 3 fall out structurally; Phase 6's directive narrowing + fixture sweep closes the item. `argMapping` grouping (R97 Phase 1) remains separable |
| R213 (rejections at consumer field) | Walker-time `SourceLocation` is the consumer field's own SDL location (`GraphQLFieldDefinition.getDefinition().getSourceLocation()`, descending into args / nested-input fields as the walk visits them), paired with the relevant `FieldCoordinates` for the SDL location |
| R209 (FieldRegistry classify-input trace) | Typed rejection at walker time; `Rejection.AuthorError.UnknownName` rides in the walker's `WalkerResult.Err.errors` list and surfaces through the orchestrator's error-collection pass before downstream generation is blocked |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves: the validator walks each output field's carriers uniformly; no per-permit dispatch survives |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker) | Two reversals. (1) The partition lives in `PredicateCarrier`'s `Condition`/`LookupRows` arm choice (UPDATE/DELETE default to `LookupRows`; `@multirows` flips to `Condition`). (2) `@value` is dropped; `UpdateRowsWalker` derives the SET columns by subtracting the PK column set from the column-bound carriers. R144's cardinality-safety surface (PK-coverage check, `multiRow` opt-in) survives unchanged in shape |
| R215 (column-binding at classification, not usage) | Subsumed: column binding happens inside each SQL-emitting walker (`ConditionWalker`, `LookupRowsWalker`, `UpdateRowsWalker`) at the walker's leaf-resolution step. R215's `UnboundField` admit set translates per-walker into the walker's own "unresolved" arm |

Items adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease (one-dimensional encoding of multi-dimensional space), different file. The pivot primes the pattern; those items apply it on the consumer-side surface independently.
- **R164**: a contract partner, not just downstream. R222 ships `MethodArgumentsWalker` with a placeholder binding arm; R164 ships the `MethodArgumentBinding` sealed family (scalar passthrough, decoded NodeID, `BackingClass`-populated, etc.) plus the per-param attachment. R222 reserves the slot; R164 populates it.
- **R98** (multi-source input validation): dissolves structurally. Per-output-field `ValidationShape` means every consumer of the same input type already produces its own carrier (and its own POJO); consumers that want different constraints just get different POJOs. R98's "multi-source" question is the default behaviour, not a structural extension. Any remaining R98 surface (e.g. additional constraint vocabulary) becomes a walker-internal extension to `ValidationShapeWalker`, not a new carrier family.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): naming binding between SDL fields and Java members on a backing class. The walker model doesn't change the naming resolution; both items stay scoped where they are.
- **R122** (compound-entity-mutations): structurally enabling. R122 ships `InsertRowsWalker` whose internal tree carries the parent + FK-threaded children. R222 reserves the carrier; R122 owns the walker and the emitter.
- **R226** (classification dimensional pivot: diagnostics off the model): R222 ships the unified `Diagnostic` family R226 needs. R226 retires the `UnclassifiedType` / `UnclassifiedField` permits in favour of `WalkerResult.Err`-carried `AuthorError`s, with `Diagnostic` covering the non-error events on both arms. No intermediate substitution step; the shape lands correctly in Phase 1 of R222 and R226 extends it to type-level classification.

## Why R144's partition reverses (under the walker model)

R144 committed the WHERE-vs-SET partition at classify time on the legacy carrier (`TableInputArg.lookupKeyFields` / `setFields`). Two problems show up under the walker pivot.

First, the partition is a property of the *consumer*, not of the input. UPDATE's "WHERE" is `LookupRows`; UPDATE's "SET" is `UpdateRows`. Each is its own walker, producing its own carrier. The consumer reads the slot it needs; nothing classifies "WHERE-vs-SET" on the input side.

Second, the `@value` marker is redundant scaffolding rather than load-bearing semantics. R144 introduced it as a manual hint for SET-clause columns because the legacy carrier had no catalog access at classify time. `UpdateRowsWalker` walks the field's args; for each column-bound input field, the walker checks whether the column is in the target table's PK column set (sourced from `JooqCatalog`). Non-PK columns go into `UpdateRows`; PK columns are claimed by `LookupRowsWalker` for the same field. The two walkers split the SDL columns by PK-membership, derived from the catalog. No per-input-field marker needed, and no behaviour change for any fixture: the catalog-derived partition produces identical results to the `@value`-marked partition for every reachable case.

R144's cardinality-safety surface (`multiRow: true` opt-in, the PK-coverage check on UPDATE / DELETE) survives unchanged in shape: the trigger is the catalog's PK column set examined by `UpdateRowsWalker` / `LookupRowsWalker`, not the `@value` complement.

**Open fork (deferred to sibling items).** Two walkers each reading the same catalog PK set and filtering the same SDL substrate by complementary predicates is structurally the "two consumers evaluate the same predicate over a model field" smell. The alternative is one upstream walker that produces both carriers in one pass, fanning the PK / non-PK split into the two slots. The fork lives entirely inside `UpdateRowsWalker` / `LookupRowsWalker` (Phase 5 sibling items) — both shapes satisfy R222's load-bearing key (`mutation-input.update-partition-by-pk-membership`), which commits to *where the partition is decided* (catalog PK membership), not to *how many walkers read the catalog*. R222 reserves both carrier slots; the sibling items pick the implementation shape.

Sakila has two `@value` annotations (`FilmUpdateInput.title`, `FilmUpdateInput.description`); both are non-PK columns, so the partition outcome is identical. Tests under `graphitron/src/test` carry many more `@value` decorations exercising R144's classifier rejections (DELETE + `@value`, `@value + @condition` mutual exclusivity); under the walker model those rejections move into walker-level structural checks against the catalog, and the test fixtures' `@value` decorations become orphaned scaffolding. The Phase 6 sweep drops them in one step. Since `@value` carries no information the walker can't derive, the removal is not a deprecation that needs a warning window — it is a no-op cleanup of unused scaffolding.

## Cross-axis invariants

The dimensional split admits states the old model could not. Two load-bearing invariants pin the states the type system doesn't already enforce. Each is carried as a `@LoadBearingClassifierCheck` key.

1. **`carrier-slot-always-populated-with-arm`**: every carrier slot on `OutputField` is non-null and holds exactly one arm of its sealed family. When a walker is not elected for the field, the slot is initialised to the family's `No<Family>` arm. When an elected walker returns `Ok`, the slot is set to `result.carrier()`. When an elected walker returns `Err`, the slot stays at `No<Family>` for the rest of classification; downstream generation is blocked, so no generator or validator consumer reads the placeholder. The LSP may read the slot directly (it consumes classification output independent of whether generation ran). Pinned at the orchestrator; the audit walker catches null or uninitialised slots.

2. **`emit-reads-only-via-carrier-slots`**: every consumer of classification output (validator, fetcher generators, query-conditions emitters, `InputRecordGenerator`) reads through `OutputField` carrier slots, never by re-walking the SDL behind a carrier. `InputTypeGenerator` is exempt: it builds schema-rebuild scaffolding from graphql-java's runtime types directly and reads no carrier. Pinned with `@DependsOnClassifierCheck` on each emit entry point.

**Carried by the type system, not the audit:**

- A walker `Walker<S, C>` returns `WalkerResult<C>`. The compiler enforces that an `Ok` result's carrier is one arm of `C`'s family.
- The `WalkerResult.Err<C>` record's compact constructor rejects an empty `errors` list. A walker cannot return `Err` without producing at least one `AuthorError`, and cannot return `Ok` while carrying any.
- Downstream consumers (validator, generators, per-type emitters) only ever see `Ok` results because the orchestrator never schedules them when any walker returned `Err`. The "consumers never observe failure" property is enforced by the wrapper and the orchestrator's halt rule, not by the carrier's arm set.

**Retirements:**

- The legacy `input.record-shape-derived-from-backing` key retires entirely with the removal of the per-input backing-class slot. `ValidationShape` is purely SDL-derived now and produced by `ValidationShapeWalker` like every other carrier.

## Phasing

Six phases, each independently shippable and individually reversible. The change is *additive* through Phase 4; legacy code retires in Phase 5 once all consumers have migrated.

### Phase 1: introduce the carrier vocabulary, the `Walker<S, C>` / `WalkerResult` abstraction, `ValidationShapeWalker`, and the per-output-field POJO emit migration in `InputRecordGenerator`

- No new identity wrapper is introduced. All carriers attach as slots on `OutputField`; no per-input map is added to the classification artifact. Where a future walker or diagnostic needs to address a field on an input, graphql-java's `FieldCoordinates` (already in use across `GraphitronSchema.fields`, `FieldRegistry`, `MappingsConstantNameDedup`) is the type+field key; R222 ships no map of that shape, but the pattern is the one to reach for.
- Add the carrier vocabulary: each carrier is a two-arm sealed family with valid arm(s) plus an explicit `No<Family>` arm; no family carries an `Invalid` arm. `PredicateCarrier` has valid arms `Condition` and `LookupRows`; `MethodArguments`, `Pagination`, `Ordering`, `InsertRows`, `UpdateRows`, `ValidationShape` each have a single valid arm (`Of`). R164's `MethodArgumentBinding` family and R122's `InsertRows.Of` tree land in their respective items; R222 ships `MethodArguments.Of(List<MethodArgumentBinding.Pending>)` as the placeholder valid arm.
- Add `sealed interface WalkerResult<C>` with `Ok<C>(C carrier, List<Diagnostic> diagnostics)` (compact-ctor rejects Error-severity diagnostics) and `Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics)` (compact-ctor rejects empty `errors`). Add `interface Walker<S, C>` parametric on substrate `S` and carrier `C`. Define the `Diagnostic` sealed family with LSP-aligned shape — `severity` (`Error` / `Warning` / `Information` / `Hint`, mirroring LSP `DiagnosticSeverity`), `code` (stable string id, e.g. `"graphitron.predicate-role-switched"`), `source` (`"graphitron"`), `message`, `tags` (`Unnecessary` / `Deprecated`, mirroring LSP `DiagnosticTag`), `relatedInformation`. Phase 1 arms: `DirectiveDeprecated`, `DirectiveDropped`, `PredicateRoleSwitched`, `CarrierProducedNothing`. `AuthorError` is the existing `Rejection.AuthorError` sealed family from the codebase; the LSP wire-format adapter projects each `AuthorError` to severity=Error LSP `Diagnostic` records, so editors see one unified diagnostic stream. No new error vocabulary is introduced.
- Add `BackingClass` family (`Pojo`, `JavaRecord`, `JooqTableRecord`) — Phase 1 introduces a new `@LoadBearingPlaceholder("R164 method-argument-binding arms")` annotation and extends `LoadBearingGuaranteeAuditTest` to honour it so the audit doesn't flag the dead carrier.
- **R94's `InputRecordShape` becomes the `ValidationShape` carrier family.** Rename the type to `ValidationShape`. The R94 capability marker `HasInputRecordShape` stays in source during the Phase 1-3 additive migration window (it continues to mark the legacy `*InputType` permits while they exist); the new carrier lives as a slot on `OutputField`, not through the marker. The marker class deletes in Phase 4 with the legacy permits. `ValidationShape` is sealed with `Of(...)` and `NoValidationShape` arms; today's walker produces `Of(...)` whenever the field has at least one input-type-typed arg.
- Implement `ValidationShapeWalker implements Walker<GraphQLFieldDefinition, ValidationShape>`. The walker iterates the field's args, descends into nested-input args, and accumulates the per-arg validation-target metadata R94's classifier currently derives from the per-input record-shape. Unit tests are pure-graphql-java fragments parsed and walked; the orchestrator-aggregation contract is exercised on this walker before `ConditionWalker` / `MethodArgumentsWalker` ship in Phase 2.
- Add non-Optional getters for each carrier family on the `OutputField` sealed interface (`validation()`, `pagination()`, `ordering()`, `predicate()`, `methodArguments()`, `insertRows()`, `updateRows()`); add the matching record components to each permit (`RootField`, `ChildField`), each defaulting to its `No<Family>` arm. Only `validation()` is populated by a live walker in Phase 1; the other six default to their `No<Family>` arm until Phase 2 / sibling items.
- **Migrate `InputRecordGenerator` to per-output-field POJO emit.** `InputRecordGenerator` walks output fields, reads each field's `validation()` carrier, and emits one POJO per input-type-typed arg the carrier covers. POJO names are `{ParentType}_{Field}_{InputType}Validation`. No cross-consumer dedup. Two consumers of the same SDL input type each get their own POJO; the `fromMap` factory + Jakarta annotations are otherwise unchanged. `TypeFetcherGenerator.validatorPreStep` continues to invoke the validator on the bound POJO; only the POJO's identity changes per consumer. The reachable-closure logic on `InputRecordGenerator` retires: the walk-from-output-fields model only emits POJOs for actually-reached args, so no separate reachability pass is needed.
- Land the orchestrator scaffolding that iterates output fields, dispatches walkers, aggregates `WalkerResult`s, and surfaces errors before blocking downstream generation. Classification runs to completion regardless of errors; the orchestrator's contract is the classification/generation phase split. Phase 1's only live producer is `ValidationShapeWalker`; the orchestrator's contract is the same once Phase 2's walkers join.
- **Extend `ValidationReport` with `List<Diagnostic> walkerDiagnostics`** (additive; existing `errors` + `warnings` slots unchanged). Extend `graphitron-lsp`'s `Diagnostics.validatorDiagnostics` with an arm projecting the walker `Diagnostic` family to LSP `Diagnostic` records — `code`, `tags`, `relatedInformation`, `severity` flow through; source attribution is `"graphitron"`; the existing `parsing/Positions.java` helper does the `SourceLocation` → LSP `Position` conversion. The seam is `Workspace.setBuildOutput(BuildArtifacts, ValidationReport)`; the rest of the wire (recalculate listener → `Diagnostics.compute` → `publishDiagnostics`) is already live. From this commit forward, walker output reaches the editor through the same channel today's `ValidationError` + `BuildWarning` do.
- The first load-bearing key lands here: `carrier-slot-always-populated-with-arm`, pinned on the orchestration site. The second (`emit-reads-only-via-carrier-slots`) lands in Phase 3 once the remaining consumers migrate.

Acceptance: model additions compile; every output field has a `ValidationShape` slot populated by `ValidationShapeWalker`; `InputRecordGenerator` emits per-output-field POJOs and every Sakila / `graphitron-fixtures-codegen` fixture's runtime validation still fails on exactly the inputs it failed on against trunk (same constraints, same conditions; only POJO identities and names change); a fixture that triggers a `DirectiveDeprecated` walker diagnostic (e.g. `@table` on input) surfaces it in the LSP's `publishDiagnostics` stream with code `"graphitron.directive-deprecated"` and `tags: [Deprecated]`.

### Phase 2: ship `ConditionWalker` and `MethodArgumentsWalker` with unit tests

- Implement `ConditionWalker implements Walker<GraphQLFieldDefinition, PredicateCarrier>`: walks args + nested inputs, accumulates predicates, bails to `LookupRowsAccumulator` on `@lookupKey`. The bailout target is in-tree even though `LookupRowsWalker` ships as a sibling item — the accumulator's class is the bailout's product, not a separate walker call.
- Implement `MethodArgumentsWalker implements Walker<GraphQLFieldDefinition, MethodArguments>`: walks args, produces `MethodArguments.Of(List<MethodArgumentBinding.Pending>)`. The per-param specialization (decoded NodeID, BackingClass-populated, scalar passthrough) is R164's; R222's body produces uniform `Pending` bindings.
- Unit-test surface (the load-bearing demonstration of the pivot): each walker has a test class with one test per SDL shape variation. Tests are pure-graphql-java: parse a fragment, run the walker, assert against the returned `WalkerResult` (the `Ok`/`Err` arm, the carrier on `Ok`, the errors on `Err`, the diagnostics list on both). No graphitron classification context is constructed.
- Add walker-dispatch logic to the existing classification pass: SQL-form read fields elect `ConditionWalker`; `@service` / `@externalField` fields elect `MethodArgumentsWalker` exclusively. Other walkers are not yet elected (slots stay on their `No<Family>` default arm).
- The orchestrator lit up in Phase 1 with `ValidationShapeWalker`; this phase adds two more walkers to the same dispatch table. The `carrier-slot-always-populated-with-arm` key still covers every `OutputField` slot.
- **Anchor one consumer in the same phase** so the producer-consumer chain is live before the phase boundary. Chosen anchor: `EnumMappingResolver.buildLookupBindings` migrating to read `OutputField.methodArguments()` for `@service` fields and `OutputField.predicate()` for SQL-form fields. Two legacy callers (`MutationInputResolver.java:433`, `FieldBuilder.java:974`) keep their existing call sites on the legacy walk; the anchor consumer reads the new slots through a sibling overload. Both overloads delegate into a shared private body so the binding logic lives in one place.
- `@table` and `@record(class:)` on input emit `DirectiveDeprecated` events at this phase; they're already ignored for binding (no walker reads them).

Acceptance: every Sakila and `graphitron-fixtures-codegen` fixture compiles unchanged. Cross-consumer divergence fixture (same input used at two consumers with different return tables) works because each walker invocation is per-output-field, not per-input-type. Walker unit tests are green and demonstrate the pivot's testability claim.

### Phase 3: migrate remaining consumers to read carrier slots

Move each remaining consumer off legacy permit discrimination onto the new carrier slots. Order chosen to keep blast radius small per PR:

- `MutationInputResolver`: reads `field.predicate()` for the WHERE arm and (once `UpdateRowsWalker` ships as a sibling item) `field.updateRows()` for the SET arm. **R144 partition relocated + `@value` retired:** the DmlKind-aware partition is the `PredicateCarrier` variant (`LookupRows` for UPDATE/DELETE default, `Condition` after `@multirows`). Two load-bearing keys swap on this site: `mutation-input.update-set-fields-equal-value-marked` retires, `mutation-input.update-partition-by-pk-membership` replaces it (PK-column carriers in `LookupRows`, non-PK in `UpdateRows`, derived inside the walkers). R144's `mutation-input.where-columns-cover-pk` key stays.
- `FieldBuilder.classifyInputFieldOnArg`: drops the `instanceof TableInputType` arm; reads relevant carrier slot per consumer site.
- `GraphitronSchemaValidator.validateTableInputType`: becomes `validateOutputFieldCarriers`; receives `OutputField` and walks each populated slot. **R221 absorbed**: the validator walks carriers uniformly; the legacy permit-identity dispatch retires.
- `CatalogBuilder` four sites: read carrier slots per site.

Compiler exhaustiveness on each carrier's sealed family is the safety net for every consumer migration.

Acceptance: no consumer references `GraphitronType.InputType`, `TableInputType`, or the four `*InputType` permits directly.

### Phase 4: delete the legacy model

- Remove `InputType`, `TableInputType`, `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType` from `GraphitronType.permits`.
- Delete the `HasInputRecordShape` capability marker class — orphaned once the legacy `*InputType` permits delete above (the new carrier lives as a slot on `OutputField`, not via the marker).
- Delete `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg`. Args no longer carry classified state.
- Delete the Phase 1-2 adapter shims.
- Delete `TypeBuilder.findReturnTablesForInput` and the `Map<String, TableRef>` cache it builds.
- Switch `InputTypeGenerator`'s data source from `schema.types()` + `schemaType()` lookup on the deleted permits to direct iteration over `GraphQLSchema.getInputObjectTypes()` (graphql-java's runtime input-type registry). The generator still emits one `<TypeName>Type.type()` class per input type; only the lookup path changes.

Acceptance: build green; nothing references the old permits; the legacy classification surface is gone.

### Phase 5: sibling walkers ship (out of R222's scope, but R222 reserves the slots)

`LookupRowsWalker`, `PaginationWalker`, `OrderingWalker`, `UpdateRowsWalker`, `InsertRowsWalker` (R122) each ship in their own item. Each follows the same shape: walker + unit tests + consumer migration. R222 has reserved the carrier slot on `OutputField`; the sibling item populates it.

### Phase 6: directive narrowing + fixture sweep

Three per-input directives whose semantics belong elsewhere come out in one sweep:

- Migrate every `@table`-decorated SDL input across Sakila, `graphitron-fixtures-codegen`, and LSP fixtures to drop the directive.
- Narrow the SDL `@table` directive's scope from `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`.
- Closes R97.
- Migrate every `@record(class:)`-decorated SDL input type across Sakila, fixtures, and LSP fixtures to drop the directive.
- Narrow the SDL `@record` directive's scope to exclude `INPUT_OBJECT`.
- Strip every `@value`-decorated SDL input field across Sakila, `graphitron/src/test` fixtures, and any other schema. The directive is redundant scaffolding under the walker model (the partition is catalog-derived); no behaviour change.
- Delete the `@value` directive declaration; delete `BuildContext.DIR_VALUE` / `ARG_VALUE` and the registration in `GraphitronSchemaBuilder`. Delete `docs/manual/reference/directives/value.adoc`. No staged deprecation window is required: `@value` carries no information the walker can't derive from the catalog, so external consumers (if any) lose nothing by the removal.

Can land independently after Phase 4; not a blocker for the structural pivot.

## Dependencies and sequencing

- **R215** (Done): the `UnboundField` deferral generalises to the walker model — each SQL-emitting walker's leaf-resolution step admits an "unresolved" arm with the same semantics. The two existing `@LoadBearingClassifierCheck` keys move into the walker bodies (`ConditionWalker.resolveLeaf` for `input-field.unbound-implies-no-column`; `MutationInputResolver` keeps `input-field.unbound-with-override-condition-admits-on-mutation-update-delete` on the verb-admission decision). No further build-order concern.
- **R94** (shipped): `HasInputRecordShape` is the existing R94 marker; under this pivot the validation-shape carrier becomes a slot on `OutputField`, populated by `ValidationShapeWalker` per-output-field, with `InputRecordGenerator` emitting one POJO per consumer site. The R94 invariant (validationShape derived from backing class) retires because no backing class is carried; the shape is purely SDL-derived and now produced by the same walker pattern as every other carrier.
- **R166 Phase 1** (reachability slot): orthogonal. The walker invocations are output-field-driven; reachability can layer over without coupling.
- **R164** (field-model dimensional pivot): contract partner. R222 ships `MethodArgumentsWalker` with `MethodArgumentBinding.Pending`; R164 ships the sealed `MethodArgumentBinding` family (with `BackingClass`-populated, decoded-NodeID, scalar-passthrough arms). R164's authors model on R222's walker + carrier vocabulary; what does **not** transfer is the input-side slot list, which is specific to input-arg classification.

Likely scope: 2-3 weeks of focused work. The walker abstraction and the `WalkerResult` / `Diagnostic` scaffolding are new; the example walkers (`ValidationShapeWalker` in Phase 1; `ConditionWalker` / `MethodArgumentsWalker` in Phase 2) plus their unit tests are the main lift. The migration phases are mechanical.

## Vocabulary

- **Identity.** Every carrier addresses an `OutputField` directly through its slot; no separate identity wrapper. For type+field identity used outside carrier storage (a `Diagnostic.location` companion, future walkers that need to label a sub-arg), graphql-java's `graphql.schema.FieldCoordinates` is the established rewrite-wide key (in use across `GraphitronSchema.fields`, `FieldRegistry`, `MappingsConstantNameDedup`).
- **No per-input model record, no per-input map.** Per-input walkers don't exist in R222. The validation POJO emit (`InputRecordGenerator`) is consumer-driven: it walks output fields and emits one POJO per input-type-typed arg. `InputTypeGenerator` (schema-rebuild emitter) reads no carrier and iterates `GraphQLSchema.getInputObjectTypes()` directly post-Phase-4; its concern is per-input by construction, and dedup is natural at that layer.
- **`Walker<S, C>`** — a pure function over an SDL substrate `S` returning `WalkerResult<C>`. Built on graphql-java primitives. Independently unit-testable. Stateless across invocations; no shared mutable sink. R222 ships only `Walker<GraphQLFieldDefinition, ?>` walkers; the generic signature is forward-compat for R226.
- **`WalkerResult<C>`** — sealed `Ok<C>(C carrier, List<Diagnostic> diagnostics)` / `Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok.carrier` is one arm of `C`'s family (valid or `No<C>`). `Ok` rejects Error-severity diagnostics by compact-ctor; `Err.errors` is non-empty by compact-ctor invariant. The orchestrator surfaces every error before blocking downstream generation. Classification runs to completion regardless of how many walkers returned `Err`. Non-error diagnostics ride on either arm.
- **Carriers** — `ValidationShape`, `Pagination`, `Ordering`, `PredicateCarrier`, `MethodArguments`, `InsertRows`, `UpdateRows`. All attach as slots on `OutputField`. Each is a two-arm sealed family with valid arm(s) plus the explicit absent arm (`No<Family>`). No family carries an `Invalid` arm; structural failure rides on `WalkerResult.Err`, not inside the carrier. Each carrier is produced by exactly one walker; every slot holds a valid arm or `No<Family>`.
- **`No<Family>`** — the domain arm naming "the substrate carries no actionable signal for this family" (walker ran, no error, nothing to encode), and exists on every family. Concrete shapes vary per family but the framing is uniform — a structural fact about the substrate, not a search outcome: `NoPredicates` for a read field with no condition-args, `NoMethodArguments` for a `@service` field that declares zero arguments (the SDL fact, not "we looked and failed to find"), `NoValidationShape` for R98's future opt-out. Consumers observe the arm exhaustively (no walker elected, or walker elected and surfaced the structural fact); structural failure does not produce `No<Family>` from a consumer's perspective because consumers never run when any walker returned `Err`.
- **`BackingClass`** — three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`) attached per `MethodArgumentBinding` variant by R164. R222 ships the family as vocabulary; not a slot on any R222-introduced model record.
- **`Diagnostic`** — LSP-aligned sealed family of structured walker events. Each arm exposes `location` (`SourceLocation`, converted to LSP `Range` at the wire), `severity` (`Error` / `Warning` / `Information` / `Hint`), `code` (stable string id), `source` (`"graphitron"`), `message`, `tags` (`Unnecessary`, `Deprecated`), and `relatedInformation` (`List<Related>`). The arms keep type-safe pattern matching on the walker side; the LSP wire-format adapter reads the LSP fields and emits a wire-shape `Diagnostic` without inventing translation rules. Phase 1 arms: `DirectiveDeprecated` (Warning), `DirectiveDropped` (Information), `PredicateRoleSwitched` (Information), `CarrierProducedNothing` (Hint). Future arms surface walker resolution events to the LSP: `BindingResolved`, `WalkerElected`, `DirectiveResolved`. Carried in both `WalkerResult.Ok.diagnostics` and `WalkerResult.Err.diagnostics`. R226 extends the family rather than replacing it.
- **`ValidationReport`** (existing) — gains a `walkerDiagnostics: List<Diagnostic>` slot in Phase 1. Additive; existing `errors: List<ValidationError>` and `warnings: List<BuildWarning>` slots unchanged. R226's `BuildWarning`-producer migration is what later collapses the three slots into one unified stream.
- **`AuthorError`** — the existing `Rejection.AuthorError` sealed family from the codebase. The walker carries the leaf relevant to its failure mode; the orchestrator surfaces it through the existing rejection channel. R222 introduces no new error vocabulary.
- **No "table-bound input"** — the predicate retires. Inputs are SDL declarations; tables enter the picture at walker time via the consumer's `@table` return.

## Tests

The pivot's load-bearing test claim is *unit-tier coverage of the walker abstraction*. Pipeline coverage falls out as a by-product when walker output is consumed by downstream emitters; pipeline tests are not the primary contract.

- **Unit-tier (new, primary):** `ValidationShapeWalker` tests. One test per SDL shape variation that R94's existing classifier-side derivation covers, asserted now against the walker's `WalkerResult.Ok(ValidationShape.Of(...))` produced from a `GraphQLFieldDefinition` substrate. Plus one test per cross-consumer case: the same SDL input type consumed by two output fields produces two independent carriers, and `InputRecordGenerator` emits two POJOs whose names are `{ParentType}_{Field}_{InputType}Validation`. Exercises the simplest carrier shape — single-arm valid carrier, no bailout, no totalising directive — and pins the per-consumer POJO contract before the more complex per-field walkers land in Phase 2.
- **Unit-tier (new, primary):** `ConditionWalker` tests. One test per SDL shape variation: `@condition` on input type, `@condition` on input field, `@condition` on arg, nested-input `@condition`, plain unresolved column → `Ok(NoPredicates, [CarrierProducedNothing])`, `@lookupKey` bailout (each nesting depth) → `Ok(LookupRows(...), [PredicateRoleSwitched])`, `@condition + @lookupKey` on the same field → `Err([AuthorError], diagnostics)`. The `PredicateRoleSwitched` assertion verifies the LSP-shape fields: severity=Information, code="graphitron.predicate-role-switched", relatedInformation pointing at the trigger location.
- **Unit-tier (new, primary):** `MethodArgumentsWalker` tests. One test per SDL shape: scalar arg → `Ok(MethodArguments.Of(...), [])`, nested-input arg, mixed args, `@nodeId`-decorated arg, no args → `Ok(NoMethodArguments, [])`, `@condition` on a `@service` field's arg → `Err([AuthorError], diagnostics)`; two conflicting args at distinct locations → `Err` carrying both `AuthorError`s in one result, pinning the "accumulate then halt" contract.
- **Unit-tier (new):** dispatch logic tests. Field with `@service` → only `MethodArgumentsWalker` elected. Plain query field → `ConditionWalker` elected (plus future walkers as they ship). Mutation field → `ConditionWalker` defaults to `LookupRows` (the walker carries `PredicateCarrier` for both reads and mutations; "Condition" names the read-default arm, not the carrier). `@lookupKey` on a read field → walker dispatches with `defaultIsLookupRows=false` and bails on first encounter (testable via the walker, but the dispatch logic itself doesn't pre-scan).
- **Unit-tier (new):** orchestrator aggregation. Build with three walkers (`ValidationShapeWalker`, `ConditionWalker`, `MethodArgumentsWalker`), one returning `Err`, the others returning `Ok` → orchestrator collects every walker's result, surfaces every `AuthorError`, blocks downstream generation; carrier slots remain at their `No<Family>` defaults on the `Err` branches but the other walkers' `Ok` slots are populated (introspection consumers like the LSP can read them). Build with N `Err` walkers → all N error sets are surfaced in one pass, not just the first; classification completes through every walker regardless. Pins the "classification runs to completion; generation blocks" contract.
- **Unit-tier (new):** LSP wire-format projection. Each `Diagnostic` arm's LSP-shape (severity, code, message, tags, relatedInformation) is asserted directly; the wire-format adapter projects `AuthorError` leaves to severity=Error LSP `Diagnostic` records with a code derived from the AuthorError sub-type (e.g. `AuthorError.UnknownName` → `"graphitron.unknown-name"`). Pins the "one diagnostic stream at the LSP boundary" contract.
- **Pipeline-tier (regression):** every existing `graphitron-fixtures-codegen` fixture and Sakila fixture compiles unchanged through the pivot. Output diffs against trunk must be empty (modulo new `DirectiveDeprecated` diagnostics for `@table`-decorated and `@record(class:)`-decorated SDL inputs).
- **Pipeline-tier (new):** `@table` on input emits `DirectiveDeprecated`. Fixture with `input X @table(name: "x") { ... }` used by a `@table`-returning consumer surfaces a `BuildWarning` at the directive's `SourceLocation`; carrier output is identical to the directive-absent case.
- **Pipeline-tier (new):** `@record(class:)` on input emits `DirectiveDeprecated`.
- **Pipeline-tier (new):** cross-consumer divergence. One input used by two consumers with different return tables produces distinct walker carriers per-output-field, each with its own table fact. Today's `InputType` collapse becomes per-output-field success.
- **Pipeline-tier (new):** `@condition` on a `@service` field's arg surfaces `Rejection.AuthorError` via the orchestrator's collected errors; the field's `methodArguments` slot remains at `NoMethodArguments` because the walker returned `Err`, and downstream generation is blocked before any per-type generator reads the slot (the LSP may read it directly). Pins the principle that `@condition` is interior to SQL emission, not a separate consumer.
- **Pipeline-tier (new):** `@service` fields produce no `PredicateCarrier`. A fixture with a `@service`-decorated output field whose arg is an SDL input type produces an `OutputField` with `methodArguments == MethodArguments.Of(...)` and `predicate == NoPredicates`. Pins the totalising-directive principle and the walker-dispatch invariant.
- **Pipeline-tier (new):** typed rejection on column-miss carries `Rejection.AuthorError.UnknownName` with the input field's source location (R209 lands here).
- **Compilation-tier:** every `graphitron-sakila-example` compile target stays green.
- **Execution-tier:** every existing execution test passes unchanged. No new execute-tier fixtures are required.

## Risk

- **Walker-per-output-field multiplies work for inputs reused across many consumers.** An input reused across ten fields runs each elected walker ten times, and `InputRecordGenerator` emits ten validation POJOs instead of one. Walker invocation: graphql-java reflective accessors on `GraphQLInputObjectField` lists are microseconds per invocation; profile before optimising. POJO emit: each POJO is ~50 lines of generated code; ten of them is 500 lines per shared input. Across a realistic schema the absolute count is bounded (low thousands of generated files in the worst case). Generated code is cheap; the trade we made for skipping cross-consumer dedup was deliberate.
- **Phases 1-3 keep both models alive simultaneously.** Adapter overhead during the additive migration window. Mitigation: Phase 4 deletes legacy with the same urgency as the rest of the pivot.
- **R164 dependency for `MethodArgumentsWalker`'s binding variants.** R222 ships `MethodArgumentBinding.Pending` as a placeholder; downstream emitters that need the specialised variant must wait for R164. Mitigation: `Pending` is a single-arm carrier that delegates to today's reflection-based mapping at consumer time; the swap to R164's variants is a sealed-family extension, not a record-shape change.
- **`WalkerResult` aggregation is a new diagnostic path.** Drift between walker-emitted diagnostics/errors and BuildContext/Rejection/LSP-wire surfacing is possible. Mitigation: aggregation is one site (the orchestrator iterates every `WalkerResult` post-walk, fanning errors to the rejection channel and diagnostics to BuildContext + LSP wire); the `Ok`/`Err` split makes "did this walker block generation" a tag-check, the compact-constructor invariant on `Err` prevents a "stub Err with no errors" mistake at construction time, and the compact-ctor on `Ok` prevents Error-severity diagnostics smuggling through the non-blocking arm. The graphitron-lsp projection extension is small (one arm on `Diagnostics.validatorDiagnostics`) and mechanical, but the source-attribution convention (`"graphitron"` for the walker arm vs `"graphitron-validator"` / `"graphitron-lsp"` for existing arms) is a one-time decision worth pinning in the Phase 1 PR rather than letting it accrete.
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

The cure: separate the *consumer concerns* into independent *carriers*; produce each by an independent *walker* over an SDL substrate; store the carriers where the substrate's own emit can read them, with arms — valid or the explicit `No<Family>` — that are part of each family's domain model. No top-level sealed hierarchy on any substrate; impossible combinations are excluded by dispatch-time election rather than by type. Walkers are pure functions over graphql-java primitives returning a sealed `WalkerResult<C>`: `Ok(carrier, diagnostics)` on success, `Err(errors, diagnostics)` on structural failure. Diagnostics are LSP-aligned by construction (severity, code, source, message, tags, relatedInformation), so the editor experience — hover, inlay hints, decorations, jump-to-cause — falls out of the walker's structured output without a parallel translation layer. Classification runs to completion across every substrate regardless of how many walkers errored; the orchestrator collects every walker's result, surfaces every error, and blocks the downstream generation phase from running when any `Err` is present. Downstream generators never observe a failure state — the wrapper plus the phase split handles it. Introspection consumers (the LSP) read classification output directly and so see populated carriers for the parts that classified cleanly even when others didn't. Each axis is independently unit-testable.

All consumer concerns attach as slots on the existing `OutputField` sealed interface — its permit records (`RootField` / `ChildField`) already host carrier-independent identity, and the output field is exactly where every consumer concern lives, because the output field's resolver method is where validation fires, where SQL emits, where method-arguments bind. Validation in particular: even when the validated *artefact* is a per-input POJO today (R94), the validation *event* is per-resolver-call, not per-input-type, and trying to encode global per-input validation shapes was a model-side fiction the actual runtime never needed. Per-consumer POJO emit makes the lie evaporate: each consumer site gets its own POJO, two consumers of the same SDL input that need different constraints just get different POJOs, R98's multi-source case becomes the default behaviour rather than a structural extension. The `Walker<S, C>` signature stays substrate-parametric so R226's type-level classification can ride the same shape; R222 itself uses one substrate.

The principle is not "minimise the model"; it is "make the model honest about what each consumer needs, and put failure where the consumer doesn't have to look at it." The cross-product encoding hides axes; the per-carrier encoding surfaces them. Optional encodings hide absence as nullability; the `No<Family>` encoding surfaces it as structure. Substrate-coupling hides which derivations are walker outputs; the substrate-parametric `Walker<S, C>` surfaces it. Structural-invalidity is the wrapper's job, not the carrier's — because no downstream generator actually consults invalidity at the slot (generation is blocked before they run, even as classification itself completes), encoding it inside the carrier family would be decorative scaffolding the pivot exists to dissolve. The walker abstraction makes each axis individually testable, individually evolvable, individually replaceable. Hidden axes drift; surfaced axes get the compiler's help — and the unit test's.

## Previous design attempts

Rejected alternatives from earlier R222 drafts, recorded so reviewers don't re-derive the dead ends.

- **Recursive `InputUsage` carrier scoped to SQL emission.** First pivot folded WHERE construction, DML row-shaping, lookup-key identification, method-param binding, pagination, and ordering into one classified output that consumers re-discriminated by role. Rejected as wrong granularity; the current design surfaces each concern as its own carrier produced by an independent walker. Retired the `input-usage.*` audit keys and the `r222-stand-in-form-from-directive-set` field-level form discriminator with it.
- **`InvalidPredicates` / `InvalidMethodArguments` arms inside the carrier families.** Encoded structural failure inside two of the seven families. Rejected because no downstream generator inspects an `Invalid` arm: generation is blocked before any generator runs in either failure mode, so the asymmetry encoded an invariant the `WalkerResult.Ok` / `Err` wrapper plus the classification/generation phase split already enforce. Retired the `walker-result.invalid-arm-paired-with-author-error` audit key with the arms.
- **`Input` / `InputFieldDecl` per-input wrapper records.** Pass-through wrappers over `GraphQLInputObjectType` / `GraphQLInputObjectField`. Rejected because per-input identity has no carrier-independent state worth a record, and graphql-java already provides every accessor the wrappers would have exposed.
- **`SchemaCoordinate(String)` identity wrapper.** Stringly-typed wrapper conflating `"FilmInput"` and `"FilmInput.title"`. Rejected because plain `String` covers the type-name case and graphql-java's `FieldCoordinates` covers the type+field case, and the rewrite already uses both idioms.
- **Optional carrier slots on `OutputField` (`Optional<Pagination>`, ...).** Presence-vs-absence at the storage layer. Rejected because the `No<Family>` arm makes absence a first-class domain state consumers pattern-match exhaustively; Optional re-introduces a present/missing flag the sealed family already encodes. Retired the `output-field.carrier-slot-presence-iff-walker-elected` audit key with the Optional encoding.
- **A `walker.produces-only-its-own-family` audit key.** Tautological under the `Walker<S, C>` generic signature; the compiler carries the invariant.
- **`ValidationShape` as a per-input carrier (`Map<String, ValidationShape>` on the classification artifact).** Two-substrate variant of the walker model: per-output-field carriers on `OutputField`, per-input carriers in a name-keyed map. The walker for `ValidationShape` was `Walker<GraphQLInputObjectType, ValidationShape>`, and `InputRecordGenerator` emitted one Jakarta-validation POJO per SDL input type. Rejected because validation fires at the resolver method-arg boundary, which is the output field's seat; the "global common shape across consumers" framing tried to reuse a per-type POJO, but the consumer is the unit of validation. R94's per-input POJO worked only by accident — no real consumer varied the constraints. R98 (multi-source input validation) is exactly the case where the per-input shape can't satisfy two consumers at once. Replaced by per-output-field `ValidationShape` slots plus per-consumer POJO emit in `InputRecordGenerator`; the two-substrate framing, the per-input map on the classification artifact, the substrate-keyed orchestrator aggregation, and the storage-shape-asymmetry justification in the architectural principle all retired with it.
