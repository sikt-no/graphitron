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

## Proposed direction

Make `@record(className: ...)` optional on payload types whose SDL shape is "data fields + at most one errors-shaped field". When omitted, graphitron synthesizes a record matching the SDL field declaration order. The synthesized record satisfies `findCanonicalCtor` trivially (records declare exactly one canonical constructor) and `resolveErrorChannel` finds the errors slot positionally as before.

The new piece, beyond synthesis, is **service-return flattening**:

- The consumer's `@service` method returns a *domain* object: a record whose components correspond by name (and assignable type) to the payload's non-errors SDL fields. As a shorthand, when the payload has exactly one non-errors field, the service may return the bare value of that field's type instead of wrapping it in a singleton record.
- graphitron's success-arm assembler extracts each named component from the service return value and binds it to the matching parameter on the synthesized payload's canonical constructor. The errors slot is filled by graphitron's catch-arm, unchanged.
- The consumer's source tree never mentions `LagreKvotesporsmalSvarPayload` (or `errors`).

For the running example, the consumer's service collapses to:

```java
public record LagreOgBeregnResultat(List<KvoteSporsmalSvar> svar) {}

public LagreOgBeregnResultat lagreOgBeregnKvoteplassering(
        List<KvotesporsmalSvarInput> svarListe) { ... }
```

…or, since the payload has exactly one data field, even just:

```java
public List<KvoteSporsmalSvar> lagreOgBeregnKvoteplassering(
        List<KvotesporsmalSvarInput> svarListe) { ... }
```

graphitron synthesizes `LagreKvotesporsmalSvarPayload` as a record with the canonical 2-arg constructor, and the success arm assembles `(serviceReturn.svar(), errors=List.of())` (or `(serviceReturn, List.of())` in the bare-value case). The catch arm assembles `(default-for-svar, errors)`.

This is the "service returns the domain object" promise from R12 §2c followed all the way through. Today that promise is half-kept: the service can return a domain object, but the consumer must still author the carrier. Flattening completes it.

## Spec questions

Concretely the spec needs to settle:

- **Trigger: locked to implicit.** No `@record(className)` on the payload type triggers synthesis. Matches legacy and keeps the directive surface small. No `synthesize: true` flag, no classpath-fallback ambiguity.
- **Generated package and naming.** Mirror the SDL type name into a configured base package, with the same `toScreamingSnake`-style derivation R12 already uses for `mappingsConstantName` so two synthesized carriers in different schemas don't collide.
- **Flatten matching rules.** Component-by-component by name and assignable type. Spec out the rejection wording for each failure mode: payload non-errors field with no matching component on the service-return record; name match but type mismatch; extra components on the service-return record that the payload doesn't declare (probably accept and ignore, but call it out).
- **Bare-value shorthand.** When the payload has exactly one non-errors field, accept the service returning that field's type directly. Worth supporting because it avoids forcing single-field domain wrappers; spec needs to call out the cardinality rules cleanly.
- **List-cardinality service fields.** R12 §2c rejects `List<Payload>` returns with a specific message. Flattening doesn't apply at list cardinality (no obvious answer to "which element gets the errors?"), so list cardinality stays as today: the consumer authors the payload class, the rejection wording is unchanged.
- **Reference resolution.** Other generated code (assemblers, fetchers, `ErrorMappings`) needs to point at the synthesized FQN. The classifier already carries `ResultReturnType.fqClassName()`; the synthesis pass populates that from a generated source file rather than from an authored one.
- **Co-existence with authored carriers.** Authored payloads with custom fields (counts, timestamps, summary slots) keep working unchanged; the synthesis trigger is purely the absence of `className`.

## Non-goals

- **Setter-based errors injection.** Rejected explicitly: violates immutability, blocks Java records, and would have graphitron mutate objects produced by consumer code. Legacy's `payload.setErrors(...)` shape is not coming back, even though it would have made the friction case "just work".
- **Authored payloads with custom fields.** Anything beyond the synthesizable shape stays in the consumer's source tree. The escape hatch must remain trivial, not an anti-pattern.
- **Flattening at list cardinality.** Returning `List<DomainRecord>` to flatten onto `List<Payload>` is not supported; list cardinality keeps its R12 §2c rejection (the catch arm has nowhere to put per-element errors).
- **Synthesizing input types.** This item is about output payloads; `@record` on inputs has different ergonomics (deserialization, partial updates) and a different cost/benefit story.
- **Removing `@record` entirely from payload types.** The directive still has a job (declaring that the SDL type is record-shaped, distinguishing it from `@table`). Only `className` becomes optional.

## Relationship to R12

R12 is the prerequisite: this item only makes sense once the canonical-constructor contract and the error channel exist. We keep R12's contract verbatim; it is exactly what makes synthesized records work cleanly, and the flattening assembler binds to the same canonical constructor R12 specifies. Splitting keeps R12 shippable on its current spec while letting this item earn its own spec/review pass on the merits (flattening rules, package conventions, co-existence) rather than being smuggled into §2c.

After R12 lands, walk it once for content that arguably belongs here instead. Candidates: anything whose subject is "the consumer must author the payload" rather than "graphitron classifies the channel". A forward-reference from R12's "Direction §2c" to this item, acknowledging that the authoring requirement is the planned ergonomic exit, is probably worth landing on R12 directly rather than waiting for this item to ship.

## Success criteria (placeholder, to be sharpened in Spec)

- A canonical-shape payload type with no authored class compiles and serves errors and data correctly through the same `ErrorRouter` path as today, with zero `@record(className)` declarations on the SDL.
- The consumer's `@service` method can return either a domain record (whose components map by name to the payload's non-errors fields) or, when there is a single non-errors field, the bare value of that field's type. Neither shape mentions the synthesized payload class.
- The author-error rejection that motivated this item ("payload class … has N declared constructors but none has parameter count …") fires only on authored payload types that actively chose to declare `className`.
- Authored payloads with custom fields are unaffected: same generation, same rejection messages, same fixture coverage as after R12.
