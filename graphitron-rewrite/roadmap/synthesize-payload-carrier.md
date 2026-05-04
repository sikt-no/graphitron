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

R12 establishes a positional canonical-constructor contract on `@record` payload classes: SDL field index *i* binds to ctor parameter *i*, with the errors-shaped field producing the slot graphitron writes into from the catch arm (`FieldBuilder.findCanonicalCtor`, `FieldBuilder.resolveErrorChannel`). The contract is load-bearing for the error channel, but it forces the consumer to author a Java class whose only job is to carry framework-shaped slots, even when their domain has nothing to add beyond `data + errors`. The class is transport, not domain, and it leaks the framework's transport shape into the consumer's source tree.

## Concrete friction

The smallest reproducible case is a passthrough `@service` mutation whose payload SDL is canonical "data + errors":

```graphql
type LagreKvotesporsmalSvarPayload
    @record(record: { className: "no.sikt.fs.opptak.service.LagreKvotesporsmalSvarPayload" }) {
    svar: [KvoteSporsmalSvar!]!
    errors: [LagreKvotesporsmalSvarError]
}
```

Today the consumer has to author `LagreKvotesporsmalSvarPayload` themselves with an all-fields canonical constructor `(List<KvoteSporsmalSvar>, List<LagreKvotesporsmalSvarError>)` so `findCanonicalCtor` can locate the errors slot. If they write the obvious shape (a no-arg constructor plus a `(List)` data-only constructor, leaving errors-population to graphitron), the field is rejected with "payload class … has 2 declared constructors but none has parameter count 2 matching the SDL field count". The rejection is correct given R12's contract, but the consumer's domain has no reason to mention this class at all: the service can return `List<KvoteSporsmalSvar>` directly (graphitron-rewrite already supports that shape via `resolveServiceResultAssembly`'s "Assembly" arm), and the errors come from the framework. The transport class is pure boilerplate.

## Proposed direction

Make `@record(className: ...)` optional on payload types whose SDL shape is canonical "data + errors" (one or more non-error fields plus exactly one errors-shaped field, no other authored slots). When the consumer omits `className` (or names a class that doesn't exist on the classpath; exact trigger is part of spec), graphitron synthesizes a record matching the SDL declaration order and uses it as the carrier. The synthesized record satisfies `findCanonicalCtor` trivially (records always declare exactly one canonical constructor) and `resolveErrorChannel` finds the errors slot positionally as before.

Concretely the spec needs to settle:

- **Trigger.** Implicit (no `className` directive) vs. explicit (`@record(synthesize: true)`) vs. fallback (named class missing). Implicit is most ergonomic; explicit is most discoverable; fallback is most backward-compatible. Pick one or layer them.
- **Generated package and name.** Mirror the SDL type name into a configured base package, with the same `toScreamingSnake`-style derivation R12 already uses for `mappingsConstantName` so two synthesized carriers with the same SDL name in different schemas don't collide.
- **Reference resolution.** Other generated code (assemblers, fetchers, `ErrorMappings`) needs to point at the synthesized FQN. The classifier already carries `ResultReturnType.fqClassName()`; the synthesis pass populates that from a generated source file rather than from an authored one.
- **Co-existence with authored carriers.** Authored payloads with custom fields (counts, timestamps, summary slots) keep working unchanged; the synthesis trigger is purely opt-in/fallback for the canonical shape.

## Non-goals

- **Authored payloads with custom fields.** Anything beyond "data fields + one errors field" stays in the consumer's source tree. The escape hatch must remain trivial, not an anti-pattern.
- **Synthesizing input types.** This item is about output payloads; `@record` on inputs has different ergonomics (deserialization, partial updates) and a different cost/benefit story.
- **Removing `@record` entirely from payload types.** The directive still has a job (declaring that the SDL type is record-shaped, distinguishing it from `@table`). Only `className` becomes optional.

## Relationship to R12

R12 is the prerequisite: this item only makes sense once the canonical-constructor contract and the error channel exist. It also fits R12's review-once shape: that item is already at `Ready` with a sizeable scope, and the synthesis pass is a cohesive follow-on rather than an implementation detail of the contract. Splitting keeps R12 shippable on its current spec while letting this item earn its own spec/review pass on the merits (trigger choice, package conventions, co-existence rules) rather than being smuggled into §2c.

After R12 lands, walk it once more for content that arguably belongs here instead: anything in R12's spec that is about *removing the consumer authoring requirement* rather than *defining the contract* is a candidate to relocate.

## Success criteria (placeholder, to be sharpened in Spec)

- A canonical-shape payload type with no authored class compiles and serves errors and data correctly through the same `ErrorRouter` path as an authored payload.
- The author-error rejection that motivated this item ("payload class … has N declared constructors but none has parameter count …") fires only on payload types that opted out of synthesis.
- Authored payloads with custom fields are unaffected: same generation, same rejection messages, same fixture coverage as after R12.
