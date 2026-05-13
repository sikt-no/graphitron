---
id: R159
title: "Intermediate SDL types between @service return and structural analogue: admission, semantics, intent"
status: Backlog
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Intermediate SDL types between @service return and structural analogue: admission, semantics, intent

## The clean correspondence (today's baseline)

The original `@service`-side taxonomy holds two clean correspondences between the developer's service method and the SDL field's structural Java analogue:

- **`MutationServiceTableField` / `QueryServiceTableField`.** Service returns a `@table`-backed jOOQ record (possibly collection-wrapped). The SDL field's return type *is* that record's structural projection. No level of SDL has an absent Java analogue; sub-field traversal walks the record's columns + accessors directly. Producer-kind discrimination is invisible because there is no gap.
- **`MutationServiceRecordField` / `QueryServiceRecordField`.** Service returns a domain object (possibly collection-wrapped). The domain object's accessors may yield `@table`-backed records or other domain objects, which classify normally via the per-field pass (`RecordTableField` etc.). Again, every level of SDL has a Java analogue.

Both modes share one property: the developer's Java type tree and the SDL type tree are structurally congruent at every level. Graphitron's job is to walk both together and emit fetchers per coordinate.

## The gap

A schema author may write an SDL Object that sits *between* the `@service` field and the structural Java analogue of the service's return type. The shipping example:

```graphql
extend type Mutation {
    opprettRegelverksamling(input: [OpprettRegelverksamlingInput!]!): OpprettRegelverksamlingPayload! @service(...)
}

type OpprettRegelverksamlingPayload {
    regelverksamling: [Regelverksamling!]
}
```

Service signature:

```java
public List<RegelverksamlingRecord> opprettRegelverksamling(List<OpprettRegelverksamlingInput> inputs);
```

The structural Java analogue of the service's return is `List<RegelverksamlingRecord>`, which maps cleanly onto SDL `[Regelverksamling!]`. The schema author has interposed `OpprettRegelverksamlingPayload` between the service field and that analogue: adding a level of SDL with no corresponding level in the Java tree. The carrier walk introduced for DML mutations (R75, R141) opportunistically admits this shape and threads the service return through to the inner data field as "passthrough." That informal treatment was designed around DML's well-defined upstream (`Result<RecordN<PK>>` from `.returningResult(PK)`); when the producer is `@service` returning the full records, the magic-passthrough intuition is doing structural work the model has not specified.

The gap surfaces today as a runtime `ClassCastException` (R158's symptom) but the underlying problem is upstream of the cast: **graphitron does not have an intuition or a mechanism for what an interposed SDL type means when the producer is `@service`, and the schema author has no way to communicate intent**.

## Three possible interpretations

A schema author writing an interposed payload type could plausibly mean any of:

- **(A) Virtual wrapper, identity passthrough.** "I want graphql-java to render a payload shape around the records the service returned. The payload type has no Java existence; field traversal threads the service return through to the inner data field; graphitron rejects any payload-type field that has no corresponding shape in the service return." This is the natural extension of R75's DML-side carrier walk. Mechanism: structural admission predicate on the payload type; reject ambiguous shapes.
- **(B) Authored Java carrier.** "The payload has its own Java class which my service instantiates directly; graphitron does not synthesize it." Today's signal is `@record(record: {className: ...})`. R96 deprecates `@record`; the replacement signal under R96 for `@service`-return payloads is "the `@service` method's reflected return type is the backing class," which only works if the service returns the payload type, not the inner data shape. So (B) under R96 requires the service to return `OpprettRegelverksamlingPayload`, not `List<RegelverksamlingRecord>`.
- **(C) Transform contract.** "The payload type has a defined shape; graphitron instantiates it via some per-field assembly from the service return." This would require a per-field contract (each payload field declares how it derives from the service return). No mechanism for this exists; this interpretation effectively asks for a new directive surface.

The schema author's SDL today does not distinguish (A), (B), or (C). Graphitron's current behaviour is to silently assume (A), via the carrier walk, with the structural admission predicate "exactly one data-channel field of compatible element shape." (A) is the only interpretation that lets the schema in the gap example work without changes to the developer's service or SDL.

## Spec direction (this item picks (A) as the baseline; questions for the user are flagged inline)

The proposed direction is to make (A) the explicit, contractual semantics: schema authors interpose a payload-shaped SDL type, graphitron treats it as virtual, and the structural admission predicate is the user-facing mechanism for communicating intent. (B) collapses to "use a `@service` method that returns the payload type directly; no interposition" under R96. (C) is out of scope here and would be its own future item if a real use-case emerged.

### Admission predicate (Spec must pin)

The payload type admits as a virtual `@service` carrier when *all* of:

1. The producing mutation/query field is `@service`-backed. (DML producers route through the existing R75/R141 carrier; that path stays separate.)
2. The payload type is a plain SDL Object with no `@record` directive (R96-aligned; rejected at validation regardless of this rule's verdict).
3. The payload type's fields partition exhaustively into known `CarrierFieldRole` permits (today: `DataChannel`; R12 adds `ErrorChannelRole`; future permits expand the partition). An unrecognised field name/shape rejects the carrier with a typed `Rejection` naming the offending field.
4. There is *exactly one* `DataChannel` field on the payload type, and its element type (after wrapper unwrapping) matches the `@service` method's reflected return element type structurally.

The structural-match rule in (4) is the load-bearing question for Spec. Candidates:

- **(4a) Exact element-type equality.** The data field's `@table`-backed element type's jOOQ record class equals the service method's reflected return element class. Strict; trivially decidable; rejects the case where the developer's service returns a subtype of the data field's record class. *(User direction needed: do we ever expect a service to return a record subtype? If not, (4a) is the right shape.)*
- **(4b) Element-type assignability.** The service's return element class is assignable to the data field's record class. Admits subtypes; loosens the contract; requires reflective `isAssignableFrom` at classify time.

The Spec should pick one and pin it via a load-bearing classifier check.

### Cardinality matching (Spec must pin)

Independent of the element-type match, the wrapper shape must align:

- Service returns `Collection<X>` (any ordered collection per R158's reader-variant work) ↔ data field is list-shaped (`[X!]` / `[X]` / etc.).
- Service returns `X` (singular) ↔ data field is single-shaped (`X` / `X!`).

A mismatch (service returns `List<X>`, data field is single-shaped) rejects the carrier at classify time. *(User direction needed: is there a case where the service returns a collection and the data field is intentionally single-shaped, for example "first match" semantics? If so, that's an explicit transform contract (C), not a passthrough.)*

### Non-data field handling (Spec must pin: user direction expected here)

If the partition in (3) admits non-data fields (today only `errors` via R12; future permits might add `affectedRowCount`, `clientMutationId`, etc.), this item must specify how each non-data field is populated for a `@service` producer:

- `errors`: populated by the catch arm (R12 wiring); the `@service` happy path leaves it `List.of()` / null per R12's contract.
- *Future non-data fields*: deferred to their own permit-introducing items.

Spec must pin: when R12 lands, does the payload's `errors` field interact with this item's admission predicate, or is the interaction subsumed by R12's own classifier checks? *(User direction: does this item's admission predicate need to know about `errors` at all, or is it strictly the `DataChannel` part, with `ErrorChannelRole` handled at the R12 site?)*

### Rejection messaging (Spec must pin)

Schema authors who write an interposed payload type that fails the admission predicate need a typed, actionable rejection message that names the structural divergence:

- "Payload type `X` has N data-channel fields; require exactly 1 for `@service`-backed admission."
- "Payload type `X`'s data-channel field `Y` has element type `A`; `@service` method `M` returns element type `B`. Match the data field's element type to the service's return element, remove the payload type and have the service field return `[B!]` directly, or refactor the service to return the payload type."
- "Payload type `X` carries `@record(record: ...)`; `@service` producers do not synthesize the payload class. Return the payload type from the service method directly, or remove `@record` and let graphitron synthesize the virtual carrier."

The third bullet's relationship to R96 should be checked: by the time R96's Phase 3 lands, the `@record`-on-`OBJECT`-with-`className` path has been narrowed, so this rejection message may be redundant with R96's validator. *(User direction: should this item assume R96 has shipped, or carry its own `@record`-on-payload rejection as a defensive belt?)*

### Relationship to `MutationServiceRecordField`

`MutationServiceRecordField` admits domain objects that may *themselves* have `@table`-backed sub-fields. The distinction from this item's interposed-payload case:

- Domain object: has a real Java class with accessors; sub-fields traverse those accessors; classifier sees a structural Java analogue at every level. Mechanism: per-field classification via `RecordField`, `RecordTableField`, etc.
- Interposed payload: no Java class; the data field's value is the service return *itself*, not an accessor on it. Mechanism: virtual carrier admission (this item).

Spec must explain how the classifier distinguishes the two at admission time. Heuristic: a service returning `OpprettRegelverksamlingPayload` (the payload type's own Java class) routes through `MutationServiceRecordField` and walks accessors; a service returning `List<RegelverksamlingRecord>` (the inner data element type) routes through `MutationServiceTableField` and admits the interposed payload via this item's predicate. The distinguishing signal is the `@service` method's reflected return type; the SDL alone does not say which.

*(User direction needed: is the distinguishing signal really just "what the service returns," or does this item want a more explicit per-payload signal, for example an opt-in directive on the payload type, to communicate virtual-carrier semantics? My current read is that the reflected-return-type signal is enough: the structural admission predicate above resolves to virtual when the service returns the inner type, and to `MutationServiceRecordField` accessor walk when the service returns the payload type. The Spec body should explicitly note this either way.)*

## What this unblocks

- **R158** (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) becomes the *mechanism* for the admission predicate (4)'s consumer side, once (4)'s exact shape is pinned by this item. R158 is blocked on this item and depends on its resolution.
- Future error-channel work for `@service` payloads (R12-adjacent) inherits this item's partition (3) as its substrate; no separate admission story for `@service`-producers needed.

## Out of scope

- DML-producer carrier walk (R75, R141). Different producer, different admission contract, already specified.
- The (C) transform-contract interpretation. If a real use-case for per-field assembly emerges, file a separate Backlog item.
- Query-side payload-shape semantics. Mutations are the natural place for payload shapes (per common GraphQL convention); queries might inherit this contract or might have their own. Defer.
- `@record` removal mechanics (R96 owns).

## Spec must address (open items, not yet answered)

- The exact admission predicate (4a vs 4b).
- Cardinality-mismatch handling: hard reject or admit-with-default-collapse semantics.
- Whether `errors`-field handling lives here or in R12.
- Whether to require an explicit opt-in directive on the payload type for virtual-carrier semantics, or rely purely on the reflected-return-type signal.
- The relationship between this item's admission predicate and the existing R75/R141 DML carrier-walk predicate: shared classifier code or parallel walks with different admission rules.
- The exact load-bearing classifier-check keys this item introduces (one per admission rule; one per rejection class).
- Whether `MutationServiceRecordField` returning a domain object whose accessors *also* hit `@table`-backed shapes triggers any interaction with this item's predicate, or whether the two paths are cleanly orthogonal.
