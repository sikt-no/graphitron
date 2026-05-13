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

Introduces root-value sigils on `@field(name:)`: `$source` (the upstream value at this field's site) and `$errors` (the error-channel slot). Reserves `$context` for a future consumer item. The bare-name form is unchanged: depth-N inference against the (implicit) default upstream, as today.

The two new schema-author affordances are:

- `@field(name: "$source")` — bind to the whole upstream value, with no step. Today reachable only by accident when bare-name's type-match fallback catches it; the sigil gives the schema author a way to name it directly. This is what R158's OpprettRegelverksamlingPayload reproducer needs to unambiguously route the carrier-payload data field at the producer's reflected return.
- `@field(name: "$errors")` — bind to the error-channel slot. R12 owns the runtime population of the slot; this item pins the source-side reference.

Reserved `$context` enters the grammar so the parser accepts it; the classifier rejects with "reserved, not yet implemented" until its consumer item lands. Reserving it now keeps the grammar stable across the eventual rollout.

That is the whole grammar for R159 v1. Sigil-prefixed step forms (`$source.X`, `$errors.X`, multi-step sigil chains) are deferred to a follow-up item; bare-name continues to handle the depth-N case unchanged. R69's per-column migration (today's `@field(name: "FILM_ID")` already works bare; the sigil-step form arrives with the follow-up) and the eventual `$context` consumer land together with the step grammar.

This is the output-side dual of R84's input-side `argMapping` path expressions in framing. The shared `PathExpr.{Head | Step}` machinery is the natural home for the eventual sigil-step grammar, but R159 v1 only needs a sigil-root recogniser (a single-token parse, no step chain). When the step grammar lands, R69 and R97 converge with R159 on the shared mental model: paths over named upstream roots, walked by typed step chains, list-aware via `liftsList`. For now, R159 v1 ships only the root-binding piece.

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

A carrier-payload field carrying `@field(name: "$source")` or `@field(name: "$errors")` binds to the named root value with no step. Bare-name forms continue to mean what they mean today: depth-N inference against the default upstream, with the existing name-match-then-type-match fallback when `@field(name:)` is omitted.

### Sigil grammar

| Sigil       | Status   | Binds to                                                                                          |
|-------------|----------|---------------------------------------------------------------------------------------------------|
| `$source`   | Active (root-only) | The upstream value at this field's site, taken as a whole. For a carrier-payload field on a `@service`-backed producer, the producer's reflected return value. For a child field on a `@table`-backed parent, the bound jOOQ record. Step suffixes (`$source.X`) are reserved for the follow-up item and rejected by the classifier in R159 v1. |
| `$errors`   | Active (root-only) | The error-channel slot. R12 owns the slot's runtime population; this item pins the source-side reference. Step suffixes (`$errors.X`) are reserved for the follow-up item and rejected by the classifier in R159 v1. |
| `$context`  | Reserved | Future binding to `GraphitronContext` values, symmetric to today's `contextArguments` list-form. Parser accepts the sigil but the classifier emits a typed rejection ("`$context` is reserved; not yet implemented") until the consumer item lands. Reserving it now keeps the grammar stable across the eventual rollout. |

Bare names continue to mean what they mean today: depth-N inference against the default upstream. A bare path `"a.b.c"` keeps its current resolution rules. The two new sigil forms (`$source`, `$errors`) are root-only in R159 v1; sigil-prefixed step forms land with the follow-up.

At sites where `$source` is not defined (Query roots, sites whose upstream cannot be classified), `@field(name: "$source")` surfaces a typed rejection ("`$source` is not defined at this site"); bare-name continues to follow whatever site-specific rule applies today.

R84's existing `input` root on `argMapping` is unchanged; it sits on the input side of the dual and is not affected by this item.

### Root-value type matching

`@field(name: "$source")` binds the whole upstream Java value to the SDL field. The classifier checks the source's Java type against the SDL field's element type:

- **`@table`-backed SDL element type** (the bound jOOQ record class is the structural target): require exact equality of the source type with the bound record class. List-wrapping is preserved on both sides (a `List<XRecord>` source matches a `[X!]` SDL element-of-list). This is the OpprettRegelverksamlingPayload reproducer's case.
- **Domain-object SDL element type** (no `@table`; the SDL Object's classifier picks up a Java class through `MutationServiceRecordField`'s accessor walk or future R96-aligned signals): require Java assignability of the source type to the backing class.

The two rules share one classifier check; they differ only on the relationship operator. This is the same type-matching predicate that bare-name's type-match fallback uses today; R159 v1 makes it nameable via `$source`.

`@field(name: "$errors")` binds to the error-channel slot; the source-side type is whatever R12 pins for the slot. R12 owns the matching contract.

### Bare-name behaviour (unchanged)

Bare-name `@field(name: "X")` and the omitted-directive case continue today's behaviour: depth-N inference with name-match against the default upstream's accessors, falling back to type-match when no name-match exists. R159 v1 does not touch the bare-name rules; making them contractual under a `$source`-rooted model is part of the follow-up that adds sigil-step grammar.

### Invariant: `$source` is the internal representation (forward-looking)

The eventual sigil-step grammar walks the internal Java value of the upstream: jOOQ records, domain beans, jOOQ tables and keys, generated record subclasses, primitive column values. Border translators (`@nodeId` encode, enum textmap encode, custom scalar binding) run between the internal value and the GraphQL wire, transparent to path resolution. The schema author writes `$source.<column-or-accessor>` and gets the internal value; the wire-shape (base64 nodeId string, enum text label, custom scalar serialization) materialises only at fetcher emission. This is symmetric with R84's input side, where `input` paths walk the wire-form arguments and translators run at the path leaf.

R159 v1 doesn't fire the translator check (root-only sigils don't have a step leaf), but the design constraint is recorded here so the follow-up's step grammar inherits it. The canonical example is `ChildField.CompositeColumnField`: internally a tuple of jOOQ columns, on the wire a base64 nodeId; the path form must stay inside the Java boundary.

## What this replaces

R159's prior draft picked interpretation (A) "virtual wrapper, identity passthrough" with a structural admission predicate ("exactly one data-channel field of compatible element shape") as the only intent-communication mechanism. This rewrite supersedes that draft: bare-name's existing inference is the mechanism for the depth-N case, and the new `$source` root-value spelling lets the schema author name the binding explicitly when they want it visible. The "exactly one data-channel field" constraint disappears (carrier-payload fields are admissible whenever the source root's type matches, by inference or by explicit `$source`); the `CarrierFieldRole` partition collapses into "every carrier field's source is either an inferred bare-name path or a root-value sigil; the `$errors` sigil names the error-channel slot directly rather than through a permit role." The net-new schema-author surface beyond today's behaviour is narrow: `$source` (root only), `$errors` (root only), reserved `$context`.

## Rejection messaging

The classifier surfaces typed, actionable messages naming the structural divergence and a concrete fix the user can paste:

- **Root-value type mismatch on `$source`:** "Carrier field `X.y` declares `@field(name: "$source")`; source root resolves to Java type `T`, SDL element type is `U`. Match the source root's type to the SDL field's element type, or change the SDL element type to match the source."
- **`$source` not defined at this site:** "`@field(name: "$source")` is not valid here; this site has no defined upstream value. Use bare-name with the column or accessor you want."
- **Reserved-sigil use:** "`$context` is reserved; not yet implemented. Use a supported sigil (`$source`, `$errors`) or a bare name."
- **Sigil-step out of scope:** "`@field(name: "$source.X")` is not yet supported; sigil-prefixed steps land with the follow-up item. Use a bare name (`@field(name: "X")`) or `$source` alone."

Bare-name inference rejection messages (no name-match, no type-match; ambiguity) are produced by the existing classifier and are unchanged by R159 v1.

## Relationship to other roadmap items

- **R158** (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) is the consumer-side mechanism R159 v1 unblocks. R158 needs to reference the producer's reflected return value at the carrier-payload data field site; `@field(name: "$source")` is exactly that reference. R159 v1 was scoped to the minimum surface that supplies R158's prerequisite. R158 stays blocked on this item.
- **R84** (`argMapping` path expressions) is the precedent for the eventual sigil-step grammar. R159 v1 does not yet reuse R84's `PathExpr.{Head | Step}` chain; it only needs a single-token sigil-root recogniser. The follow-up item that adds sigil-step support is where the parser-sharing question lands.
- **R69** (`@experimental_constructType`) is unaffected by R159 v1. R69's existing comma-separated `graphqlField: SQL_COLUMN` mini-language, and the equivalent per-field `@field(name: "FILM_ID")` form, both work bare under today's inference. R69's migration to a sigil-step `$source.FILM_ID` form is a documentation refresh that lands with the follow-up item.
- **R97** (`argMapping` grouping) is the input-side sibling extension and stays on its own track. R97's path expressions stay rooted at `input` (R84's existing root); this item's `$source` is the output-side root. Convergence on the shared mental model lands with the sigil-step follow-up.
- **R96** (deprecate `@record`) owns the directive-removal story for `@record` on payload types. This item does not carry a defensive `@record`-on-payload rejection; R96's Phase 3 narrows the directive scope and any cleanup belongs there.
- **R12** (typed errors) owns the runtime population of the `$errors` slot. This item only pins the source-side reference; the catch-arm wiring, error-shape contract, and partition rules for non-data fields stay with R12.

## Apollo Connectors as precedent

Apollo's `@connect(selection: ...)` mapping language is the closest external precedent. Its sigil model (`$` = response root, `$this` = parent, `$args` = field args, `@` = method receiver) and grammar (alias-and-path with GraphQL-shaped sub-selection) inform this item's design. R159 v1 takes much less than Apollo offers: only the named-root piece (sigil-root with no step), no path walks, no method-chain transforms, no nested `{ ... }` sub-selection. The convergence point with Apollo is "named upstream root"; everything beyond that (dotted paths, list-lifting, transforms) lands incrementally in follow-ups.

## Out of scope (follow-up items)

The largest scope-out, and the reason R159 v1 is small enough to land cleanly:

- **Sigil-step grammar (`$source.X`, `$errors.X`, multi-step sigil chains).** R159 v1 ships root-only sigils. The step grammar lands in a dedicated follow-up that imports R84's `PathExpr.{Head | Step}` machinery, the `Result.PathRejected` rejection chain, and the list-lifting model. The follow-up is also where R69's per-column `@field(name: "$source.FILM_ID")` migration lands and where `$context`'s consumer item slots in. Filed as a sibling Backlog item once R159 v1 ships.

Other follow-ups:

- **DML-producer carrier walk (R75, R141).** Different producer, different upstream shape; the DML path continues to use R141's PK-keyed-map for now. A future item may unify the DML path under `$source` once the migration cost is paid.
- **Path-form coverage of FK-traversal use cases via jOOQ path-based joins.** The eventual sigil-step grammar accepts FK chains as path steps, which would let path-form `@field(name:)` express some of what `@reference` expresses today. This is overlap, not replacement: `@reference` stays as a first-class directive for the foreseeable future, and the two mechanisms coexist on the same surface. The consumer-side work (lifting `FkJoin` / `ColumnReferenceField` to walk path expressions where the schema author opts in) is its own follow-up item. Motivation only here.
- **`argMapping` extension for `$source` on the input side** (parent-PK threading into a child `@service` resolver, currently done by implicit type-matching). Sibling Backlog item to be filed once this item's syntax is pinned; dataloader source-binding implications called out at that site, not here.
- **`$context` sigil consumer.** Reserved in the grammar; its consumer item (path-form contextArguments, unifying with today's list-form shorthand) is not yet filed.
- **Transforms / method-chain (`@`, `->name(args)`).** Apollo precedent exists; no graphitron use case yet. Defer.
- **Nested `{ ... }` sub-selection.** Would let one `@field(name:)` construct a sub-object from multiple paths. Useful for the eventual R69 successor but not needed for the carrier-payload case. Defer.
- **Query-side payload-shape semantics.** Mutations are the natural place for payload shapes (per common GraphQL convention); the bare-name rules apply uniformly, but the only fixture we have is mutation-side. Query-side fixture coverage is a follow-up.
- **`@record`-on-payload defensive rejection.** R96 owns; this item does not carry a belt.

## Spec must address

- The parser surface: single-token sigil-root recogniser (`$source`, `$errors`, reserved `$context`) on `@field(name:)`. Live alongside today's bare-name parse path with a one-character lookahead on `$`. Module-boundary question for `PathExpr` reuse is deferred to the sigil-step follow-up.
- The classifier-check keys this item introduces: one for the `$source` root-value type-match, one for the reserved-`$context` rejection, one for the "`$source` not defined at this site" rejection, one for the "sigil-step out of scope" rejection. Each is load-bearing and gets a `@LoadBearingClassifierCheck` key.
- Pinning the set of sites where `$source` is defined (carrier-payload field, child field of a `@table`-backed parent, child field of a `@record`-backed parent, others) and the set where it is not (Query roots, unbacked interfaces). The Spec enumerates exhaustively; the "$source not defined" rejection consumes this enumeration.
- The `$errors` source-side reference: confirm R12's slot type and access pattern (likely a `List<GraphQLError>` or similar), and pin the classifier check that admits `@field(name: "$errors")` only on error-channel fields (per R12's partition).
- Exact rejection-message wording.
- Test surface: pipeline-tier cases for `$source` root-value binding (OpprettRegelverksamlingPayload reproducer), `$source` type-mismatch, `$source`-undefined rejection, `$errors` reference, reserved-`$context` rejection, sigil-step-out-of-scope rejection; execution-tier case against the reproducer (or sakila equivalent if we add one). Bare-name behaviour is already covered by existing tests; no new bare-name cases.
- LSP-tier coverage: `@field(name:)` autocomplete suggests the three sigil roots (`$source`, `$errors`, reserved `$context`) on sites that have a defined upstream. Step-suffix completion is deferred to the sigil-step follow-up.
