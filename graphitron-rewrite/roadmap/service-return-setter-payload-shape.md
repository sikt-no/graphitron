---
id: R154
title: Admit setter-based mutable payload shape on @service returns
status: Ready
bucket: architecture
priority: 6
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Admit setter-based mutable payload shape on @service returns

Today the rewrite admits exactly one construction shape for a `@service`-backed (or, post-R96, introspected) payload class: a **canonical all-fields constructor**, one parameter per SDL field, in SDL declaration order. The rule is pinned in `FieldBuilder.findCanonicalCtor` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:430-460`) and consumed by three sibling resolvers: `resolveErrorChannel` (errors slot is a positional ctor parameter, `:1654-1759`), `resolveServiceResultAssembly` (result slot is a positional ctor parameter matched by `TypeName`, `:1909-2000`), and `resolveDmlPayloadAssembly` (row slot is a positional ctor parameter matched by jOOQ-record class). All three find the slot by **constructor parameter index**; the emitter (catch arm's payload-factory lambda, `TypeFetcherGenerator.java`) builds the payload with one `new Payload(...)` call passing positional arguments.

Legacy graphitron supported a second shape: a no-arg constructor plus per-SDL-field setter methods (`setXxx(value)`), with the mapper code (`JavaRecordMapperMethodGenerator.java:198,218` in `graphitron-codegen-parent`) populating the payload object after construction. `ReflectionHelpers.setterAcceptsOptional` (`:60`) is the surviving hint that this shape also handled `Optional<T>` setters. Consumers carrying that convention today cannot migrate to the rewrite without converting every payload class to a Java record (or hand-adding an all-fields ctor that doesn't fit the rest of their domain layer). The shape is structurally fine ; the rewrite simply doesn't see it.

## Architectural principle

**Variant identity tracks construction shape.** Per the rule applied four times already (R61 / R70 / R71 / R74), the right shape for "two structurally distinct ways of building the same artifact" is a sealed sub-taxonomy where the variant identifier carries the shape, not a flag or a nullable component. The all-fields-ctor path and the mutable-bean path have different mechanical contracts (`new Payload(...)` vs. `var p = new Payload(); p.setX(...); ... return p;`), different slot-identification rules (parameter index vs. setter method by SDL field name), and different ambiguity modes; each emitter that forks on shape reads its arm directly, no per-instance branching.

```java
sealed interface PayloadConstructionShape
    permits AllFieldsCtor, MutableBean {

    /** Canonical all-fields constructor; today's contract. */
    record AllFieldsCtor(
        java.lang.reflect.Constructor<?> ctor      // parameters[] aligned positionally to SDL field order
    ) implements PayloadConstructionShape {}

    /** Java-bean shape: no-arg constructor plus per-SDL-field setter; legacy bridge. */
    record MutableBean(
        java.lang.reflect.Constructor<?> noArgCtor,
        java.util.List<SetterBinding> bindings    // one per SDL field, in SDL declaration order
    ) implements PayloadConstructionShape {}

    record SetterBinding(
        String sdlFieldName,          // e.g. "rating"
        java.lang.reflect.Method setter,  // e.g. setRating(Integer); resolved by Java-bean name match
        boolean acceptsOptional       // setter parameter is Optional<T>; mirrors legacy setterAcceptsOptional
    ) {}
}
```

`findCanonicalCtor` returns `PayloadConstructionShape` instead of a raw `Constructor<?>`. The three sibling resolvers (`resolveErrorChannel`, `resolveServiceResultAssembly`, `resolveDmlPayloadAssembly`) consume the sealed shape and produce slot identifiers that are themselves sealed:

```java
sealed interface ErrorsSlot {
    /** All-fields-ctor shape: the errors parameter is at this index in the ctor's parameter list. */
    record CtorParameterIndex(int index) implements ErrorsSlot {}
    /** Setter shape: the catch arm's payload-factory lambda calls this setter with the errors list. */
    record SetterMethod(java.lang.reflect.Method setter) implements ErrorsSlot {}
}

sealed interface ResultSlot {
    record CtorParameterIndex(int index) implements ResultSlot {}
    record SetterMethod(java.lang.reflect.Method setter) implements ResultSlot {}
}

sealed interface RowSlot {
    record CtorParameterIndex(int index) implements RowSlot {}
    record SetterMethod(java.lang.reflect.Method setter) implements RowSlot {}
}
```

These three are siblings in form but live in their own sealed hierarchies (per *Narrow component types over broad interfaces*) ; folding them onto one `Slot { Ctor | Setter }` interface would force every consumer to widen and reach back through `instanceof` for the role-specific data. `ErrorChannel`, `ResultAssembly`, and `PayloadAssembly` each carry their own slot type.

`resolvePayloadConstructionShape` returns a sealed result analogous to today's `CanonicalCtorResult.Found | .Reject` (`FieldBuilder.java:414`): a `Resolved(PayloadConstructionShape)` arm wrapping the success cases above, and a `Rejected(Rejection)` arm whose typed rejection captures the failure mode ("neither shape matches: missing structural element X"). Consumers `switch` on the result the same way they switch on `CanonicalCtorResult` today; the rejection threads onto `UnclassifiedField` per the existing pattern.

## Classifier widening

`findCanonicalCtor` (or its successor `resolvePayloadConstructionShape`) walks the payload class with two predicates, in order:

1. **All-fields-ctor predicate** (today's rule). Records always hit this. Hand-rolled POJOs hit it when the canonical ctor is unambiguous. If matched, return `AllFieldsCtor`.
2. **Mutable-bean predicate** (new). The class declares a public no-arg constructor *and* a Java-bean setter (`setXxx`, case-insensitive on the first letter after `set`) for every SDL field on the payload type, with parameter assignability matching the SDL-derived Java type (or `Optional<T>` of it; the legacy `setterAcceptsOptional` rule lifts unchanged). If matched, return `MutableBean`.
3. **Both predicates match.** The class supports both shapes (common when a hand-rolled POJO carries setters for non-graphitron framework use while also declaring an all-fields ctor for use elsewhere). Predicate 1's match short-circuits the walk: `AllFieldsCtor` wins because it ran first, and the canonical shape is the preferred path (records always present it; setters are the legacy bridge). Both shapes yield equivalent payload instances ; there's no construction drift to surface. Consumers who want the setter shape exclusively drop the all-fields ctor from their class.
4. **Neither predicate matches.** Today's `CanonicalCtorResult.Reject` message extends to enumerate both shapes the classifier accepted and which structural element each missed (e.g. *"no all-fields ctor matching SDL field count 4; mutable-bean shape rejected: missing setter for SDL field 'rating'"*). LSP / IDE consumers read the structured rejection per existing rendering rules.

The setter shape's "for every SDL field" predicate uses **SDL field name** as the join key, not constructor parameter order. SDL field order is irrelevant on this arm because emit walks the SDL field list and looks up each binding by name. This means the setter shape is robust against SDL field reordering in a way the all-fields-ctor shape isn't, a property worth documenting.

## Errors-slot rule under the setter shape

Today's positional-index errors slot generalises to `ErrorsSlot.SetterMethod` straightforwardly: the structural detection in `detectErrorsFieldShape` (channel detection by SDL field shape) is unchanged ; the *binding* of the detected SDL field to a Java location flips from "ctor parameter at this index" to "setter method `setErrors` (or whatever the SDL field name lowercases to)". The catch arm's payload-factory emit produces:

```java
// AllFieldsCtor (today)
errors -> new Payload(arg0, arg1, errors, arg3);

// MutableBean (new)
errors -> { var p = new Payload(); p.setA(arg0); p.setB(arg1); p.setErrors(errors); p.setD(arg3); return p; };
```

The defaulted-slots collection (`collectDefaultedSlots`, `FieldBuilder.java:` adjacent to `resolveErrorChannel`) lifts unchanged in *meaning* (the per-non-errors-slot default literal extracted from the consumer's payload class) ; under the setter shape it's keyed on `SetterBinding` rather than ctor parameter index. The emitter that consumes the defaulted-slot map (catch-arm payload factory) reads off the same structured information either way.

## Result-slot and row-slot rules under the setter shape

`resolveServiceResultAssembly`'s "find the ctor parameter typed `T` matching the service return type" generalises to "find the `SetterBinding` whose setter accepts `T`". Same uniqueness rule ; same rejection wording adapted ("multiple setters typed as T" instead of "multiple parameters typed as T").

`resolveDmlPayloadAssembly`'s "find the ctor parameter typed `Result<X extends Record>` matching the input table's jOOQ record class" generalises identically. The row slot is identified by setter accepting the jOOQ-record shape ; the existing table-equality check is unchanged.

For both, the emit forks on the producing `PayloadConstructionShape`: `new Payload(...)` with the slot at its ctor index, vs. `var p = new Payload(); p.setRow(...); ...; return p;`. The success arm of the service-fetcher emit walks the same dispatch.

## Interplay with neighbouring items

- **R96 (`deprecate-record-directive.md`, Spec).** R96 migrates `@record`-driven backing-class resolution to introspection signals (`@service` return type, `@table` record-class derivation, parent-accessor return type). Whichever signal R96 settles on still produces a `Class<?>`; R154 widens the *shape* contract on that class. Add one row to R96's Phase 3 audit table (`Classifier-site → replacement-signal audit`) noting that `findCanonicalCtor` becomes `resolvePayloadConstructionShape` and the load-bearing key `result-type.backing-class-from-producer-signal` widens to admit both shapes. The two items are sequentially independent: R154 can ship before R96 (the widening lives inside `FieldBuilder`'s reflection helpers regardless of which producer signal feeds it) or after (the widened helper is consumed by whichever signal R96 leaves standing).
- **R75 (shipped, `changelog.md:33`).** R75's `PojoResultType.NoBacking` admits payloads with no authored class at all (identity passthrough). R154's widening doesn't touch `PojoResultType.Backed` itself; it lands at the assembly carriers (`ErrorChannel`, `ResultAssembly`, `PayloadAssembly`), which gain sealed slot types resolved against the `Backed.fqClassName` class via reflection. The two arms of `PojoResultType` remain orthogonal: `NoBacking` skips construction entirely, `Backed` names the class the shape resolver walks.
- **R137 (`service-wrapper-composition.md`, Backlog).** R137 peels `Optional` / `CompletableFuture` / `Mono` / `DataFetcherResult` off the service return type. R154's widening is independent: once R137 unwraps the wrapper layer, the unwrapped return type binds against either construction shape per R154's rules. No coordination needed beyond R137's 8-case matrix gaining a "× setter-shape backing class" row if the matrix is rerun after R154.
- **Error-handling parity (`error-handling-parity.md`).** The `ErrorChannel` carrier's `errorsSlotIndex: int` field becomes `errorsSlot: ErrorsSlot` (sealed). Mechanical: every producer (`resolveErrorChannel`) and every consumer (`TypeFetcherGenerator`'s catch-arm payload-factory emit) walks the new sealed shape. The seal makes `javac` enumerate the consumers.

## Migration intent

Parallel-supported, no deprecation pressure. The setter shape exists as a legacy bridge for consumers carrying that convention from legacy graphitron; records (which always declare a canonical all-fields ctor) remain the recommended shape for new payload types. The two shapes coexist indefinitely because:

- The setter shape doesn't structurally conflict with anything: it's a different way of populating the same fields, dispatch is by sealed variant, no per-instance branching downstream.
- Some consumers (Sikt projects whose payload POJOs are also used in non-graphql layers of their app) may want to keep the setter shape regardless of legacy-migration status.
- Forcing record migration on the all-fields-ctor side would be free, but forcing it on the setter side erodes the principle that "graphitron does not impose its construction conventions on developer-owned classes" (the same principle that motivates R94's "graphitron-internal records never escape the fetcher boundary").

A diagnostic (*info*, not *warn*) could mention the existence of the all-fields-ctor shape when classifying a setter-shape payload, similar to R54's parallel-support window for `@externalField`. Not in scope for v1; the seam this item ships is enough on its own.

## Classifier invariants (`@LoadBearingClassifierCheck` keys)

Per *Validator mirrors classifier invariants* (`rewrite-design-principles.adoc:103`), the classifier publishes:

- `payload-construction.shape-resolved`: every `ErrorChannel.Channel`, every `ResultAssembly`, and every `PayloadAssembly` carries a `PayloadConstructionShape` arm that the emitter dispatches on. Producer: `FieldBuilder.resolvePayloadConstructionShape`. Consumers: `TypeFetcherGenerator`'s three payload-factory emit sites (catch-arm, service-result-assembly, DML-row-assembly), each wearing `@DependsOnClassifierCheck` against this key.
- `payload-construction.setter-name-matches-sdl-field`: every `SetterBinding`'s `setter` method matches the SDL field name under Java-bean conversion (`xRating` → `setXRating`; first letter case-flexible). Producer: setter-shape predicate in `resolvePayloadConstructionShape`. Consumer: catch-arm payload-factory emit, which calls `setter.getName()` directly into the generated source, wearing `@DependsOnClassifierCheck` against this key.

Producer- and consumer-side annotations are not substitutes for pipeline-test pinning (per R125's framing); both ship. `LoadBearingGuaranteeAuditTest` picks up orphans automatically when a consumer is missing its `@DependsOnClassifierCheck` annotation.

## Phasing

Two phases, independently shippable. Phase 1 lifts the model; Phase 2 lifts the emitters and the consumer fixtures.

### Phase 1: model lift

- Introduce `PayloadConstructionShape` sealed interface and its two permits.
- Replace `findCanonicalCtor`'s return type with `PayloadConstructionShape | Reject`. Today's `AllFieldsCtor` arm is what every existing call site already gets; the seal lets `javac` flag every consumer that needs to fork.
- Introduce sealed `ErrorsSlot` / `ResultSlot` / `RowSlot` on the three assembly carriers (`ErrorChannel`, `ResultAssembly`, `PayloadAssembly`). Today's `int errorsSlotIndex` etc. flip to the `CtorParameterIndex` arm of the sealed type.
- Classifier produces `AllFieldsCtor` on every existing fixture; no consumer behaviour changes yet. The seal is unblocked.

Acceptance: full build green, every classification yields `AllFieldsCtor`, the sealed switch in every emit site reachable arm passes through the new `CtorParameterIndex` slot type and emits the same source as before.

Phase 1 is value-additive on its own even if Phase 2 stalls: the seal carries the construction contract explicitly (rather than implicitly inside `findCanonicalCtor`'s `Constructor<?>` return), and the typed slots replace the raw `int` indices on the three assembly carriers. A sealed-with-one-permit shape is unusual but not wrong; the seal already documents the intent for the second arm and gives `javac` the lever to enforce exhaustiveness when it lands.

### Phase 2: setter-shape admission + emit

- Implement the setter-shape predicate in `resolvePayloadConstructionShape`. The predicates run in order; when both match, predicate 1 short-circuits and `AllFieldsCtor` wins (canonical over legacy bridge). Only the neither-shape case rejects.
- Implement the three emit-site forks: catch-arm payload-factory lambda (`errors -> { var p = new P(); p.setX(...); p.setErrors(errors); ...; return p; }`), service-result-assembly success arm, DML-row-assembly success arm.
- Pin the rejection shape for "neither shape matches" under the existing typed-rejection patterns; `UnclassifiedField` carries a structured rejection enumerating both shapes and the structural element each missed.
- Sakila fixture: one `@service`-backed mutation whose payload class is hand-rolled with no-arg ctor + setters (e.g. `SetterShapeReviewPayload` next to the existing `FilmReviewPayload`, which is the record-based regression cover for the all-fields-ctor path), plus the matching execution-tier round-trip.

Acceptance: setter-shape payload round-trips through `@service` mutation; a payload class supporting both shapes classifies as `AllFieldsCtor` (canonical wins); the all-fields-ctor path is unchanged for every existing fixture.

## Tests

Per *Pipeline tests are the primary behavioural tier* (`rewrite-design-principles.adoc:126`).

### Unit-tier (L1, structural invariants)

- `PayloadConstructionShapeTest` (new): parameterised over hand-rolled fixture classes covering each shape and each rejection. Records produce `AllFieldsCtor`; mutable-bean classes (no-arg ctor + setters) produce `MutableBean`; classes supporting both shapes produce `AllFieldsCtor` (canonical wins, the setter-shape predicate is unreached); classes with neither produce `Reject`. Pins setter-name resolution under Java-bean naming (`rating` → `setRating`, `xRating` → `setXRating`). Pins the `Optional<T>` setter case (`void setRating(Optional<Integer>)` sets `SetterBinding.acceptsOptional = true`). Pins the parameter-type-mismatch rejection: a setter whose parameter erasure is neither the SDL-derived `T` nor `Optional<T>` rejects with a structured rejection naming the offending setter and the expected type.

### Pipeline-tier (L4, primary signal)

- `FetcherPipelineTest` adds:
  - `serviceMutation_setterShapePayload_emitsSetterFactory`: SDL `@service` mutation whose payload class uses the setter shape; emit walks `setterBinding.setter().getName()` per SDL field; the errors-slot setter call appears in the catch-arm payload-factory.
  - `serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged`: regression cover that the today path produces the same emit shape as before.
  - `serviceMutation_bothShapesPresent_prefersCtorFactory`: payload class with both an all-fields ctor *and* a no-arg ctor + setters; classifier yields `AllFieldsCtor`; emit uses the positional `new Payload(...)` form.
  - `dmlMutation_setterShapePayload_emitsSetterFactory`: DML payload whose class uses the setter shape; row-slot setter call appears.

Assertions are structural per `rewrite-design-principles.adoc:130`; use `TypeSpecAssertions.wiringFor(...)` or token-kind walks.

### Compilation-tier (L5)

`graphitron-sakila-example` adds a hand-rolled `SetterShapeReviewPayload` (no-arg ctor + setters) and a `@service` mutation backed by it. `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes ; the generated fetcher compiles against the hand-rolled class.

### Execution-tier (L6)

`GraphQLQueryTest` (or the closest existing mutation-execution test) gains one case: the setter-shape `@service` mutation round-trips end-to-end through Sakila Postgres ; success-arm produces the expected payload shape, error-arm routes through the `setErrors` setter and surfaces the typed error in `payload.errors`.

## Acceptance criteria

- `PayloadConstructionShape` exists as a sealed interface with `AllFieldsCtor` and `MutableBean` permits ; `findCanonicalCtor` (or successor) returns the sealed shape. Every existing fixture's payload class still classifies (as `AllFieldsCtor`); no consumer behaviour changes in Phase 1.
- `ErrorChannel`, `ResultAssembly`, and `PayloadAssembly` carry sealed slot types (`ErrorsSlot`, `ResultSlot`, `RowSlot`) replacing the existing positional ints. Every `switch(shape)` in the emitter sites is `javac`-checked exhaustive.
- The setter-shape predicate admits no-arg-ctor + Java-bean-setter classes; a class supporting both shapes classifies as `AllFieldsCtor` (canonical wins); the neither-shape rejection fires at classify time, surfaces as `UnclassifiedField`, and carries structured rejection per the existing `Rejection` patterns.
- The three emit sites (catch-arm payload factory, service-result-assembly, DML-row-assembly) dispatch on `PayloadConstructionShape` and emit the matching source (positional `new Payload(...)` vs. `var p = new P(); p.setX(...); return p;`).
- `LoadBearingGuaranteeAuditTest` green: `payload-construction.shape-resolved` and `payload-construction.setter-name-matches-sdl-field` keys are paired across producers / consumers.
- Sakila execution-tier round-trip passes for at least one setter-shape `@service` mutation, including the error-arm path.

## Forks open at Spec stage

The implementer can pin these during In Progress; choices land in the In Review diff for the reviewer.

- **`Optional<T>` setter parameters.** The legacy `setterAcceptsOptional` rule admits `void setRating(Optional<Integer> r)` and translates `null → Optional.empty()` at the call site. Phase 2 carries this forward unchanged; the structural assertion is that the setter parameter's erasure is either `T` (the SDL-derived Java type) or `Optional<T>`. Recommended: lift `setterAcceptsOptional` verbatim from `graphitron-codegen-parent`, no new logic.
- **Setter visibility.** Public is the default expectation. Package-private setters surface as a setter-shape mismatch if the generated fetcher lives in a different package (the common case). Recommended: require public; reject non-public setters at classify time with a structured rejection naming the offending setter. Defer the package-bridge case until a real schema needs it.
- **Setter return type.** Builder-style `return this` (i.e. `Payload setRating(...)`) is a common idiom. The emit calls the setter as a statement (`p.setRating(...);`) and ignores any return value, so void and self-returning setters both work without special handling. Recommended: accept either; the structural predicate keys on the method *name* and *parameter list*, not the return type.

## Non-goals

- Builder-pattern (fluent immutable) payload classes (`Payload.builder().rating(...).build()`). Different construction shape from both `AllFieldsCtor` and `MutableBean`; if it surfaces in a real schema, file a sibling item that adds a `BuilderPattern` permit to `PayloadConstructionShape`. The seal makes this strictly additive.
- Lombok-`@Data`-generated setters. Lombok bytecode injects standard setters at compile time; the reflection-based predicate sees them no differently from hand-written setters. Nothing special to do.
- Replacing the all-fields-ctor shape with the setter shape. The two coexist; records remain the recommended shape; setters are a parallel-supported migration bridge.
- Designing a `@constructionShape(setter)` SDL directive to disambiguate. Per *configuration drift* reasoning (R96 §"Misconfiguration risk"), opt-in directives that duplicate a structural signal the classifier can already see are themselves drift sources. The classifier walks the class and prefers `AllFieldsCtor` when both shapes are present; consumers who want the setter shape exclusively drop the all-fields ctor from their class.

## Implementation surface (file-by-file, indicative)

**New files:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/PayloadConstructionShape.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorsSlot.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ResultSlot.java`
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RowSlot.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/PayloadConstructionShapeTest.java`

**Files modified:**

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`: `findCanonicalCtor` → `resolvePayloadConstructionShape`; `resolveErrorChannel`, `resolveServiceResultAssembly`, `resolveDmlPayloadAssembly` consume the sealed shape.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannel.java`: `int errorsSlotIndex` → `ErrorsSlot errorsSlot`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ResultAssembly.java`: corresponding `ResultSlot` lift.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/PayloadAssembly.java`: corresponding `RowSlot` lift.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`: three catch-arm / service-result / DML-row payload-factory emit sites switch on `PayloadConstructionShape`.
- `graphitron-sakila-example/...`: new hand-rolled setter-shape payload class plus the matching `@service` mutation fixture; the existing `FilmReviewPayload` stays as the all-fields-ctor regression cover.
- `graphitron-fixtures-codegen/...`: pipeline-tier fixtures covering the four phase-2 cases above.
