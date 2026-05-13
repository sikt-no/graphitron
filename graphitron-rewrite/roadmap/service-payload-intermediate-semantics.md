---
id: R159
title: "Root-value sigils on @field(name:): $source / $errors for carrier-payload sourcing"
status: Backlog
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Root-value sigils on `@field(name:)`: `$source` / `$errors` for carrier-payload sourcing

## What this item does

Adds two literal sigil values to `@field(name:)`:

- `@field(name: "$source")` — bind the SDL field to the upstream value at this field's site, taken as a whole. This is what R158's OpprettRegelverksamlingPayload reproducer needs to unambiguously route the carrier-payload data field at the producer's reflected return.
- `@field(name: "$errors")` — bind the SDL field to the error-channel slot. R12 owns the runtime population of the slot; this item pins the source-side reference.

Reserves `$context` as a parser-accepted, classifier-rejected sigil ("`$context` is reserved; not yet implemented") so its eventual consumer item doesn't have to grow the grammar.

That is the whole surface R159 v1 ships. Bare-name `@field(name: "X")` continues to mean what it means today (single-segment name match on the upstream, with the existing type-match fallback when name-match fails); `@field(name:)` does not accept dotted paths today and R159 v1 does not lift that restriction.

## Motivating gap

Today's classifier admits an interposed SDL Object between a `@service` field and the structural Java analogue of its return type without an explicit mechanism for what the interposition means. The reproducer:

```graphql
extend type Mutation {
    opprettRegelverksamling(input: [OpprettRegelverksamlingInput!]!): OpprettRegelverksamlingPayload! @service(...)
}

type OpprettRegelverksamlingPayload {
    regelverksamling: [Regelverksamling!]
}
```

```java
public List<RegelverksamlingRecord> opprettRegelverksamling(List<OpprettRegelverksamlingInput> inputs);
```

The structural Java analogue of the service return is `List<RegelverksamlingRecord>`, which maps cleanly onto `[Regelverksamling!]`. The schema author has interposed `OpprettRegelverksamlingPayload`, adding a level of SDL with no corresponding level in the Java tree. The DML-side carrier walk (R75, R141) opportunistically admits this shape and threads the service return through as "passthrough," but the magic-passthrough intuition was designed around DML's well-defined upstream (`Result<RecordN<PK>>` from `.returningResult(PK)`); when the producer is `@service` returning the full records, the intuition is doing structural work the model has not specified, and surfaces today as the runtime `ClassCastException` reported in R158.

The underlying problem is upstream of the cast: graphitron has no mechanism for the schema author to communicate intent. The fix is to give them one.

## The clean correspondence (today's baseline)

The original `@service`-side taxonomy holds two clean correspondences between the developer's service method and the SDL field's structural Java analogue:

- **`MutationServiceTableField` / `QueryServiceTableField`.** Service returns a `@table`-backed jOOQ record (possibly collection-wrapped). The SDL field's return type *is* that record's structural projection. No level of SDL has an absent Java analogue.
- **`MutationServiceRecordField` / `QueryServiceRecordField`.** Service returns a domain object (possibly collection-wrapped). The domain object's accessors may yield `@table`-backed records or other domain objects, classified normally via the per-field pass.

Both modes share one property: the developer's Java type tree and the SDL type tree are structurally congruent at every level. The interposed-payload case breaks the congruence at one level by introducing an SDL Object the Java side does not have.

## The mechanism

| Sigil       | Status   | Binds to                                                                                          |
|-------------|----------|---------------------------------------------------------------------------------------------------|
| `$source`   | Active   | The upstream value at this field's site, taken as a whole. For a carrier-payload field on a `@service`-backed producer, the producer's reflected return value. For a child field on a `@table`-backed parent, the bound jOOQ record. |
| `$errors`   | Active   | The error-channel slot. R12 owns the slot's runtime population; this item pins the source-side reference. |
| `$context`  | Reserved | Parser accepts; classifier rejects with "`$context` is reserved; not yet implemented." |

At sites where `$source` is not defined (Query roots, sites whose upstream cannot be classified), `@field(name: "$source")` surfaces a typed rejection ("`$source` is not defined at this site").

### Type matching for `$source`

`@field(name: "$source")` binds the upstream Java value to the SDL field. The classifier checks the source's Java type against the SDL field's element type:

- **`@table`-backed SDL element type** (the bound jOOQ record class is the structural target): require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (a `List<XRecord>` source matches a `[X!]` SDL element-of-list). This is the OpprettRegelverksamlingPayload reproducer's case.
- **Domain-object SDL element type** (no `@table`; the SDL Object's classifier picks up a Java class through `MutationServiceRecordField`'s accessor walk or future R96-aligned signals): require Java assignability of the source type to the backing class.

This is the same type-matching predicate bare-name's type-match fallback uses today; R159 v1 makes it nameable via `$source`.

`@field(name: "$errors")` binds to the error-channel slot; the source-side type is whatever R12 pins for the slot. R12 owns the matching contract.

## What this replaces

R159's prior draft picked interpretation (A) "virtual wrapper, identity passthrough" with a structural admission predicate ("exactly one data-channel field of compatible element shape") as the only intent-communication mechanism. This rewrite supersedes that draft: bare-name's existing inference handles the depth-1 case unchanged, and the new `$source` literal lets the schema author name the binding explicitly when inference is insufficient or when they want it visible. The `CarrierFieldRole` partition collapses; `$errors` names the error-channel slot directly rather than through a permit role.

## Rejection messaging

The classifier surfaces typed, actionable messages with a concrete fix the user can paste:

- **`$source` type mismatch:** "Carrier field `X.y` declares `@field(name: "$source")`; source resolves to Java type `T`, SDL element type is `U`. Match the source's type to the SDL field's element type, or change the SDL element type to match the source."
- **`$source` not defined at this site:** "`@field(name: "$source")` is not valid here; this site has no defined upstream value."
- **Reserved-sigil use:** "`$context` is reserved; not yet implemented. Use `$source`, `$errors`, or a bare name."

## Relationship to other roadmap items

- **R158** is the consumer-side mechanism R159 v1 unblocks. R158 needs `@field(name: "$source")` at the carrier-payload data field site to reference the producer's reflected return; R159 v1 was scoped to exactly that need. R158 stays blocked on this item.
- **R12** owns the runtime population of the `$errors` slot. This item only pins the source-side reference; the catch-arm wiring, error-shape contract, and partition rules for non-data fields stay with R12.
- **R96** (deprecate `@record`) owns any `@record`-on-payload cleanup. This item does not carry a defensive rejection.

## Out of scope

- **Dotted paths in `@field(name:)`.** `@field(name:)` is single-segment today; R159 v1 does not lift that restriction. Sigil-prefixed steps (`$source.X`, `$errors.X`), bare-name multi-segment (`"a.b.c"`), R84 `PathExpr` machinery import, and R69's per-column migration are all deferred to a future item if and when the need arises.
- **`$context` consumer.** Reserved in the grammar only; its consumer item is not filed.
- **DML-producer carrier walk (R75, R141).** Different producer, different upstream shape; the DML path continues to use R141's PK-keyed-map for now.
- **`argMapping` extension for `$source` on the input side** (parent-PK threading into a child `@service` resolver, currently done by implicit type-matching). Sibling Backlog item if the need arises.
- **Query-side payload-shape semantics.** Mutations are the only fixture; query-side coverage is a follow-up if it ever matters.

## Spec must address

- The parser surface: literal `$source` / `$errors` / `$context` sigil values on `@field(name:)`. A `@field(name:)` value starting with `$` parses as a sigil; anything else parses as today's bare name.
- The classifier-check keys this item introduces: one for `$source` type-matching, one for the reserved-`$context` rejection, one for the "`$source` not defined at this site" rejection. Each is load-bearing and gets a `@LoadBearingClassifierCheck` key.
- Where `$source` is defined vs. not (carrier-payload field, child field of a `@table`-backed parent, child field of a `@record`-backed parent, vs. Query roots and unbacked interfaces). The Spec enumerates exhaustively; the "$source not defined" rejection consumes this enumeration.
- The `$errors` source-side reference: confirm R12's slot type and access pattern, and pin the classifier check that admits `@field(name: "$errors")` only on error-channel fields.
- Test surface: pipeline-tier cases for `$source` binding (OpprettRegelverksamlingPayload reproducer), `$source` type-mismatch, `$source`-undefined rejection, `$errors` reference, reserved-`$context` rejection; execution-tier case against the reproducer.
- LSP-tier coverage: `@field(name:)` autocomplete suggests `$source`, `$errors`, and (with reserved-status indication) `$context` on sites that have a defined upstream.
