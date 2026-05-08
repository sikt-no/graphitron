---
id: R75
title: "Synthesize payload carrier for canonical data+errors shapes"
status: Backlog
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [error-handling-parity]
---

# Synthesize payload carrier for canonical data+errors shapes

R12 establishes a positional canonical-constructor contract on `@record` payload classes: SDL field index *i* binds to ctor parameter *i*, with the errors-shaped field producing the slot graphitron writes into from the catch arm (`FieldBuilder.findCanonicalCtor`, `FieldBuilder.resolveErrorChannel`). The contract is load-bearing for the error channel, but its current shape leaks framework concerns into the consumer's source tree: the consumer has to author a Java class whose fields include `errors`, even though `errors` is never theirs to populate. The transport shape and the domain shape are entangled in their code.

Legacy graphitron didn't have this entanglement. `TypeDTOGenerator` (`graphitron-codegen-parent/.../dto/DTOGenerator.java`) generated payload POJOs from the SDL by default, with `@record(className: ...)` reserved as the opt-out for consumers who needed to author a custom carrier. graphitron-rewrite has accidentally inverted the default: `@record(className)` is now effectively mandatory and synthesis is unavailable. Restoring synthesis as the default is parity-restoration as much as ergonomic improvement.

This item picks up that thread, but with a stronger architectural shape than legacy had. The consumer's service should never write or even mention the payload class. Their domain layer returns a domain object; graphitron synthesizes the transport carrier and **flattens the domain object's fields onto the carrier's non-errors slots**. The errors slot is filled by graphitron's catch-arm machinery, exactly as R12 already specifies.

## Concrete friction

The smallest reproducible case is a passthrough `@service` mutation whose payload SDL is canonical "data + errors":

```graphql
type LagreKvotesporsmalSvarPayload
    @record(record: { className: "no.sikt.fs.opptak.service.LagreKvotesporsmalSvarPayload" }) {
    svar: [KvoteSporsmalSvar!]!
    errors: [LagreKvotesporsmalSvarError]
}
```

Today the consumer must author `LagreKvotesporsmalSvarPayload` with an all-fields canonical constructor `(List<KvoteSporsmalSvar>, List<LagreKvotesporsmalSvarError>)` so `findCanonicalCtor` can locate the errors slot. If they write the obvious shape (a no-arg constructor plus a `(List)` data-only constructor, leaving errors-population to graphitron), the field is rejected with "payload class … has 2 declared constructors but none has parameter count 2 matching the SDL field count". The rejection is mechanically correct given R12's contract, but it's the wrong *shape* of constraint to place on a consumer: their service's domain output has nothing to do with `errors`, yet they're forced to author a class that mentions both.

## What legacy does today

Two reference points worth carrying forward:

- `TypeDTOGenerator` synthesizes payload POJOs from the SDL by default. The consumer authors nothing for the canonical "data + errors" case; `@record(className: ...)` is the escape hatch for custom carriers.
- Errors are injected via setter (`payload.setErrors(errors)`) by `ExceptionStrategyConfiguration.PayloadCreator`. The all-fields constructor is generated but is not load-bearing for error injection.

We keep R12's canonical-constructor contract instead of going back to setter injection. Immutability and Java records are non-negotiable, and mutating an object returned by consumer code is a category of API graphitron should not expose. So this item adopts legacy's "synthesize by default" stance but binds the synthesized carrier through R12's constructor contract.

## What R12 already provides

Most of the carrier-side machinery this item once proposed has shipped under R12.
Code-verified, in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`:

- **`model/ErrorChannel.java`** — typed record carrying `payloadClass`, `errorsSlotIndex`,
  `defaultedSlots` (each slot pre-resolved to a `defaultLiteral`), and `mappingsConstantName`.
  The errors slot is identified by SDL field declaration index, exactly as the synthesis
  trigger here would want.
- **`model/ResultAssembly.java`** — the success-arm counterpart for service-backed fetchers.
  Holds `payloadClass`, `resultSlotIndex`, `resultSlotType`, `defaultedSlots`. Populated by
  `FieldBuilder.resolveServiceResultAssembly`, which walks the developer-supplied payload
  class's canonical constructor looking for a parameter whose `TypeName` matches the
  service method's reflected return type and binds that one parameter as the result slot.
- **`FieldBuilder.resolveErrorChannel`** — full canonical-ctor reflection: identifies the
  errors slot by SDL index, captures every other slot's language default literal, resolves
  `mappingsConstantName` (with classifier-side cross-field hash-suffix dedup in
  `MappingsConstantNameDedup`).
- **Catch-arm dispatch** — `buildServiceFetcherCommon` emits a `payloadFactory` lambda that
  walks the captured slots positionally: errors slot ← lambda parameter, every other slot
  ← its `defaultLiteral`. Live for service-backed fetchers; DML side has its own
  `PayloadAssembly`/`buildMutationDeleteFetcher` pair with the same shape.
- **`@record` already optional at SDL** — `TypeBuilder.buildResultType:528-562` returns
  `PojoResultType(name, location, null)` when either `@record` is absent or its `className`
  is missing. The downstream resolvers short-circuit on `fqClassName == null`, so a
  null-className object is currently a no-op carrier; the carrier infrastructure itself
  doesn't need a code change to admit synthesized classes — it needs the synthesized class
  on the classpath and `fqClassName` populated.

**Bare-value shorthand falls out for free.** When the synthesized payload has a single
non-errors slot and the service returns that slot's type, R12's existing
`resolveServiceResultAssembly` walks all canonical-ctor parameters looking for a `TypeName`
match and binds the single matching parameter. No new resolver shape is needed for that case.

## Proposed direction

R75 adds three additive pieces to the R12 foundation:

1. **Payload-record synthesis.** Make `@record(className: ...)` optional on payload types
   whose SDL shape is "data fields + at most one errors-shaped field". When omitted,
   emit a Java record matching the SDL field declaration order under the configured
   synthesis package, then populate the carrier's `ResultReturnType.fqClassName()` with
   the synthesized FQN so every downstream R12 resolver picks it up unchanged. The
   synthesized record declares exactly one canonical constructor, so `findCanonicalCtor`
   matches trivially.

2. **Classifier reroute.** Today a plain SDL object type (no `@record`, no `@table`,
   not interface/union) lands as `ReturnTypeRef.ScalarReturnType`, and
   `MutationInputResolver.validateReturnType:174` rejects it with
   *"return type 'X' is not yet supported; use ID or a @table type"*. The reroute either
   reclassifies synthesizable payloads as `ResultReturnType` directly or runs synthesis
   early enough that the synthesized class is on the classpath when classification looks.
   Spec needs to pick a side; the second is cheaper (one synthesis pass, no classifier
   change) and probably correct.

3. **Multi-slot component flattening on `ResultAssembly`.** Today
   `resolveServiceResultAssembly` is a *single-slot* binding: it walks ctor parameters
   for a `TypeName` match against the service method's return type and binds one
   parameter. R75's flattening shape is *multi-slot*: a service return record whose
   components map by name (and assignable type) to multiple non-errors slots on the
   payload's canonical constructor. Two architectural options:

   - **Extend `ResultAssembly`** with a sealed split (`SingleSlot | Flattened`); both
     arms reuse `defaultedSlots` and the catch-arm payload-factory walk.
   - **Sibling resolution** (`FlattenedResultAssembly`) carried as a separate
     `Optional<>` slot on the field variants.

   Spec phase picks one. The first keeps `WithErrorChannel`-style consumers symmetric;
   the second avoids overloading an existing record. Lean toward the sealed split.

For the running example (assuming R75 lands), the consumer's payload SDL drops the
`@record` directive entirely:

```graphql
type LagreKvotesporsmalSvarPayload {
    svar: [KvoteSporsmalSvar!]!
    errors: [LagreKvotesporsmalSvarError]
}
```

…and the consumer's service collapses to either:

```java
public record LagreOgBeregnResultat(List<KvoteSporsmalSvar> svar) {}

public LagreOgBeregnResultat lagreOgBeregnKvoteplassering(
        List<KvotesporsmalSvarInput> svarListe) { ... }
```

(flattening: payload's `svar` slot ← `serviceReturn.svar()`) or, since the payload has
exactly one non-errors field, the bare-value form:

```java
public List<KvoteSporsmalSvar> lagreOgBeregnKvoteplassering(
        List<KvotesporsmalSvarInput> svarListe) { ... }
```

(R12's existing single-slot `ResultAssembly` binds the bare return directly; no R75-side
flattening needed for this case). graphitron synthesizes
`LagreKvotesporsmalSvarPayload` as a record with the canonical 2-arg constructor, and
the success arm assembles `(svar, errors=List.of())` from whichever shape applied;
the catch arm fills `(default-for-svar, errors)` via the existing `payloadFactory` lambda.

This is the "service returns the domain object" promise from R12 §2c followed all the
way through. R12 ships it for the single-slot case (one ctor parameter matching the
service return type); R75 adds the multi-slot generalisation and the synthesis trigger
that makes the consumer's authored carrier disappear entirely.

## Spec questions

Concretely the spec needs to settle:

- **Trigger: locked to implicit.** No `@record(className)` on the payload type triggers
  synthesis. Matches legacy and keeps the directive surface small. No `synthesize: true`
  flag, no classpath-fallback ambiguity. `TypeBuilder.buildResultType:528-562` already
  produces the no-className state today (returning `PojoResultType(_, _, null)`); the
  synthesis pass keys off that.
- **Reroute vs early-emit.** Today a plain object type (no `@record`/`@table`) lands as
  `ReturnTypeRef.ScalarReturnType` and the mutation rejects it. Decide between
  (a) extending the classifier to recognise synthesizable payloads as `ResultReturnType`,
  or (b) running synthesis as a pre-classification pass that emits the Java record and
  attaches a `@record(record: {className: ...})` to the SDL view the classifier sees.
  Lean toward (b) — same classifier surface, minimal code-path change.
- **Generated package and naming.** Mirror the SDL type name into a configured base
  package, with the same `toScreamingSnake`-style derivation R12 already uses for
  `mappingsConstantName` so two synthesized carriers in different schemas don't collide.
- **`ResultAssembly` shape choice.** Sealed split (`ResultAssembly.SingleSlot |
  ResultAssembly.Flattened`) vs sibling resolution (`FlattenedResultAssembly` as a
  separate `Optional<>` slot on the field variants). Lean toward the sealed split:
  `WithErrorChannel`-style consumers stay symmetric and the existing catch-arm
  `payloadFactory` walk extends naturally.
- **Flatten matching rules** (the new resolver arm). Component-by-component by name and
  assignable type. Spec out the rejection wording for each failure mode: payload
  non-errors field with no matching component on the service-return record; name match
  but type mismatch; extra components on the service-return record that the payload
  doesn't declare (probably accept and ignore, but call it out). Mirror the existing
  `resolveServiceResultAssembly` rejection style at `FieldBuilder.java:1860-1916`.
- **Bare-value shorthand.** Already supported as a side-effect of R12's single-slot
  `ResultAssembly`: when the synthesized payload has one non-errors slot and the service
  returns that slot's type, the existing walk binds it. Spec phase needs to confirm this
  reaches the synthesized payload too once (a)/(b) above is settled, but no new resolver
  code is needed.
- **List-cardinality service fields.** R12's `resolveServiceResultAssembly:1861-1868`
  already rejects list-cardinality with a specific message. Flattening doesn't apply at
  list cardinality (no obvious answer to "which element gets the errors?"), so this
  stays as-is: the consumer authors the payload class for `List<Payload>` returns; the
  rejection wording is unchanged.
- **Reference resolution.** R12 already wires `ResultReturnType.fqClassName()` through
  every downstream resolver and emitter. Synthesis populates that slot from a generated
  source file rather than from an authored one; no further plumbing.
- **Co-existence with authored carriers.** Authored payloads with custom fields (counts,
  timestamps, summary slots) keep working unchanged; the synthesis trigger is purely the
  absence of `className` *and* the SDL shape matching "data fields + at most one
  errors-shaped field". Off-shape object types (extras the synthesis arm can't populate)
  fall back to today's rejection.

## Non-goals

- **Setter-based errors injection.** Rejected explicitly: violates immutability, blocks Java records, and would have graphitron mutate objects produced by consumer code. Legacy's `payload.setErrors(...)` shape is not coming back, even though it would have made the friction case "just work".
- **Authored payloads with custom fields.** Anything beyond the synthesizable shape stays in the consumer's source tree. The escape hatch must remain trivial, not an anti-pattern.
- **Flattening at list cardinality.** Returning `List<DomainRecord>` to flatten onto `List<Payload>` is not supported; list cardinality keeps its R12 §2c rejection (the catch arm has nowhere to put per-element errors).
- **Synthesizing input types.** This item is about output payloads; `@record` on inputs has different ergonomics (deserialization, partial updates) and a different cost/benefit story.
- **Removing `@record` entirely from payload types.** The directive still has a job (declaring that the SDL type is record-shaped, distinguishing it from `@table`). Only `className` becomes optional.

## Relationship to R12

R12 has shipped the foundation R75 binds to (`ErrorChannel`, `ResultAssembly`,
canonical-ctor reflection, errors-slot-by-SDL-index, default-literal capture, catch-arm
`payloadFactory` dispatch, mappings-constant dedup). R75 stays additive: it does not
modify any of those types' shapes — it adds a synthesis pass that populates
`ResultReturnType.fqClassName()`, a (possibly sealed) extension on `ResultAssembly` for
multi-slot flattening, and (depending on the spec choice above) either a classifier
reroute or a pre-classification synthesis pass. The bare-value shorthand falls out of
R12's existing single-slot match for free.

The remaining hard dependency on R12 is structural rather than schedule-bound: R75 needs
the types R12 has shipped to remain in place. R12's open Remaining-work bullets
(rule-6 relaxation, accessor reflection check, `extensions.constraint`,
validator-integration fixture) are all `@error`-side, not payload-side; none of them
gate R75. R75 can be promoted to Spec without waiting for R12 to reach Done, provided
the spec acknowledges that R12's payload-side surface is treated as fixed.

A forward-reference from R12's §2c to R75 (acknowledging that the authoring requirement
is the planned ergonomic exit) is worth landing on R12 directly so future readers of R12
see the trajectory.

## Success criteria (placeholder, to be sharpened in Spec)

- A canonical-shape payload type with no authored class compiles and serves errors and data correctly through the same `ErrorRouter` path as today, with zero `@record(className)` declarations on the SDL.
- The consumer's `@service` method can return either a domain record (whose components map by name to the payload's non-errors fields) or, when there is a single non-errors field, the bare value of that field's type. Neither shape mentions the synthesized payload class.
- The author-error rejection that motivated this item ("payload class … has N declared constructors but none has parameter count …") fires only on authored payload types that actively chose to declare `className`.
- Authored payloads with custom fields are unaffected: same generation, same rejection messages, same fixture coverage as after R12.
