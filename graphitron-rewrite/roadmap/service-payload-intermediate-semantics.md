---
id: R159
title: "Path expressions on @field(name:): $source / $errors sigils for carrier-payload sourcing"
status: Backlog
bucket: structural
priority: 2
theme: service
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Path expressions on `@field(name:)`: `$source` / `$errors` sigils for carrier-payload sourcing

## What this item does

Extends `@field(name:)` to accept R84-style path expressions rooted at sigil-named upstream values. The bare-name form preserves its current behaviour: depth-N inference against the default upstream (which this item names `$source`). Bare-name `"a.b.c"` and sigil-prefixed `"$source.a.b.c"` resolve identically; same rules, same diagnostics, same emitted code. The sigil grammar adds three narrow affordances bare-name structurally cannot express:

- `$source` alone, with no step, binding to the whole upstream value. Today this is reachable only by accident when type-match fallback catches it; the sigil gives the schema author a way to name it.
- `$errors` and `$errors.X`, for error-channel routing. R12 owns the runtime population of the slot; this item pins the source-side reference.
- Reserved `$context`, the grammar slot for the future consumer item (path-form `contextArguments` unified with today's list-form shorthand). Parser accepts it; classifier rejects with "reserved, not yet implemented" until the consumer item lands.

That is the whole schema-author-visible payoff for this item. The motivating gap (the OpprettRegelverksamlingPayload reproducer, R158) is resolved by today's inference being made contractual and the new `$source` spelling letting the author write the binding explicitly when they want to; no new structural admission rule is needed.

This is the output-side dual of R84's input-side `argMapping` path expressions and reuses the same `PathExpr.{Head | Step}` machinery and `selection.parseEntries` syntax extraction. R69 (`@experimental_constructType`) and R97 (`argMapping` grouping) are the two other open consumers of the same parser; this item pins the sigil grammar so all three converge on a shared mental model: paths over named upstream roots, walked by typed step chains, list-aware via `liftsList`.

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

A carrier-payload field carrying `@field(name: "<path>")` declares where its value comes from. The path is rooted at a sigil (`$source`, `$errors`, reserved `$context`) and walked through the upstream Java value using R84's `PathExpr` machinery. Bare-name forms are implicit `$source`-rooted paths; when `@field(name:)` is omitted entirely, graphitron infers a bare-name path from the SDL field name and falls back to type-match when name-match fails (see below).

### Sigil grammar

| Sigil       | Status   | Binds to                                                                                          |
|-------------|----------|---------------------------------------------------------------------------------------------------|
| `$source`   | Active   | The upstream value at this field's site. For a carrier-payload field on a `@service`-backed producer, the producer's reflected return value. For a child field on a `@table`-backed parent, the bound jOOQ record. At every site with a well-defined `$source`, bare-name `"X"` and sigil-prefixed `"$source.X"` are equivalent paths. |
| `$errors`   | Active   | The error-channel slot. R12 owns the slot's runtime population; this item pins the source-side reference. |
| `$context`  | Reserved | Future binding to `GraphitronContext` values, symmetric to today's `contextArguments` list-form. Parser accepts the sigil but the classifier emits a typed rejection ("`$context` is reserved; not yet implemented") until the consumer item lands. Reserving it now keeps the grammar stable across the eventual rollout. |

Bare names continue to mean what they mean today: depth-N inference against `$source`. A bare path `"a.b.c"` is the same path as `"$source.a.b.c"`. The sigil form is the explicit spelling of what bare-name infers; it is not a different resolver, does not bypass inference rules, and does not produce different diagnostics. The net-new sigil affordances are narrow and listed above.

At sites where `$source` is not defined (Query roots, sites whose upstream cannot be classified), `$source`-prefixed paths surface a typed rejection ("`$source` is not defined at this site"); bare-name continues to follow whatever site-specific rule applies today.

R84's existing `input` root on `argMapping` is unchanged; it sits on the input side of the dual and is not affected by this item.

### Invariant: `$source` is the internal representation

`$source` paths always walk the internal Java value of the upstream: jOOQ records, domain beans, jOOQ tables and keys, generated record subclasses, primitive column values. Border translators (`@nodeId` encode, enum textmap encode, custom scalar binding) run between `$source` and the GraphQL wire, transparent to path resolution. The schema author writes `$source.<column-or-accessor>` and gets the internal value; the wire-shape (base64 nodeId string, enum text label, custom scalar serialization) materialises only at fetcher emission.

This is symmetric with R84's input side. There, `input` paths walk the wire-form arguments and border translators run at the path leaf where the directive lives: an `argMapping: "filmId: input.id"` whose `id` carries `@nodeId(typeName: "Film")` decodes at the leaf into the internal column tuple, then binds to `filmId`. The sigil-rooted path never sees the encoded form on the internal side, nor the decoded form on the wire side.

The canonical example where the duality matters most is `ChildField.CompositeColumnField`. The field's internal representation is a tuple of jOOQ columns; the wire representation is a base64-encoded nodeId. A carrier field `@field(name: "$source.id")` where the source's `id` is composite-backed yields the internal tuple, and if the SDL field carries `@nodeId(typeName: "X")` the encoder runs on the tuple at wire emission. Schema authors do not write `$source.encodedId` because there is no internal accessor by that name; the path-form keeps everything inside the Java boundary.

A path-leaf type mismatch surfaces at classify time: SDL field declares `@nodeId(typeName: "Film")` (expects internal column tuple from `$source`), the source accessor yields `String` instead, classifier rejects with a typed message naming the translator mismatch. The same pattern applies to enum textmap and custom scalar boundaries.

### Path walk

Path walk reuses R84's `PathExpr.{Head | Step}` chain. The head fixes the sigil-named root's Java type; each step resolves to an accessor on the prior step's type. List-shaped intermediates lift element-wise per `liftsList`, matching R84's flat-path-with-intermediate-list semantics. The structural rules from R84's `Result.PathRejected` (walk-through scalar/enum/union/interface, unknown segment with closest-match hint) carry over verbatim; the Spec re-uses the rejection carrier rather than introducing a parallel one.

### Inference rules

The same inference rules apply to bare-name and to `$source`-prefixed forms. (At a `$source`-prefixed form's leaf step, "inference" is just step resolution; the framing below uses "inference" for the bare-name case because that's where it matters most.)

When a carrier-payload field has no `@field(name:)`, or has `@field(name: "X")` with a single bare segment, graphitron runs the existing inference rules with the order and behaviour made contractual:

1. **Name-match.** A top-level accessor on `$source` whose name matches the SDL field's name (for the omitted-directive case) or the named segment (for the explicit case) resolves to a path `$source.<name>`. Existing R94 Layer 2 by-name convention applied here.
2. **Type-match fallback.** When the omitted-directive case finds no name-match, graphitron searches `$source`'s top-level accessors for one whose Java type matches the SDL field's element type structurally (the type-at-path rule below). If exactly one match exists, it resolves; if zero or two-or-more match, inference fails. (The fallback applies only when the directive is omitted; an explicit `@field(name: "X")` whose segment doesn't name-match an accessor rejects without searching by type.)
3. **Inference failure.** Surfaces as a typed `Rejection` naming the SDL field, the candidate `$source` accessors, and a concrete `@field(name: "$source.<candidate>")` example the user can paste. The user-facing message and LSP suggestion is the load-bearing surface for "how do I influence graphitron's choice."

The OpprettRegelverksamlingPayload schema works under the rules above without changes: the single `regelverksamling: [Regelverksamling!]` field type-matches the service's `List<RegelverksamlingRecord>` return (rule 2), since `RegelverksamlingRecord` is the bound record class for `Regelverksamling`. The new affordance R159 unlocks for this case is the spelling `regelverksamling: [Regelverksamling!] @field(name: "$source")`, which names the whole producer return as the source. The schema author can use it where they want the binding visible; bare-name keeps working unchanged.

### Type-at-path matching

When a path resolves to Java type T and the SDL field's element type is U:

- **`@table`-backed U** (the bound jOOQ record class is the structural target): require exact equality of T with the bound record class. This rejects subtype surprises and is trivially decidable; we do not expect services to return record subtypes today.
- **Domain-object U** (no `@table`; the SDL Object's classifier picks up a Java class through `MutationServiceRecordField`'s accessor walk or future R96-aligned signals): require Java assignability of T to U's backing class.

The two rules share one classifier check; they differ only on the relationship operator. The matching is symmetric to R84's leaf-type rule on the input side.

## What this replaces

R159's prior draft picked interpretation (A) "virtual wrapper, identity passthrough" with a structural admission predicate ("exactly one data-channel field of compatible element shape") as the only intent-communication mechanism. This rewrite supersedes that draft: today's inference rules, made contractual and given an explicit spelling for the root-value case, are the mechanism. The "exactly one data-channel field" constraint disappears (multiple data fields with distinct paths are admissible); the `CarrierFieldRole` partition collapses into "every carrier field's source is a path (often inferred); the `$errors` sigil names the error-channel slot directly rather than through a permit role." The net-new schema-author surface beyond today's behaviour is narrow: `$source` alone, `$errors`, reserved `$context`.

## Rejection messaging

Schema authors who hit a failure case need typed, actionable messages naming the structural divergence and a concrete fix the user can paste. The classifier surfaces:

- **Inference failure (no name-match, no type-match):** "Carrier field `X.y` has no `@field(name:)` and graphitron could not infer its source. `$source` has accessors `[a, b, c]`; none match the SDL field's name `y`, and none have an element type matching `Y`. Declare the source explicitly with `@field(name: "$source.<accessor>")`."
- **Inference ambiguity (no name-match, two-or-more type-matches):** "Carrier field `X.y` has no `@field(name:)` and the type-match fallback found multiple candidates: `[a, c]` (both yield `Y`). Declare the source explicitly with `@field(name: "$source.<accessor>")`."
- **Path resolution failure:** R84's `Result.PathRejected` rejection chain, reused verbatim. Walk-through scalar/enum/union/interface; unknown segment with closest-match hint.
- **Type-at-path mismatch:** "Carrier field `X.y` declares `@field(name: "$source.a")`; path resolves to Java type `T`, SDL element type is `U`. Match the source accessor's type to the SDL field's element type, or change the SDL element type to match the source."
- **Reserved-sigil use:** "`$context` is reserved; not yet implemented. Remove the path or use a supported sigil (`$source`, `$errors`)."

## Relationship to other roadmap items

- **R158** (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) is the consumer-side mechanism for this item's admission predicate. Once R159 lands, R158's reader-variant work pins to "path resolved by this item's classifier" rather than to "structural-admission predicate verdict." R158 stays blocked on this item.
- **R84** (`argMapping` path expressions) is the precedent and the syntactic-machinery source. This item reuses `PathExpr.{Head | Step}`, `selection.parseEntries`, `ArgBindingMap.of`-style step chains, list-aware traversal, and `Result.PathRejected`. The Spec should pin the exact module-boundary for reuse (likely: lift the path parser to a shared location consumed by both `argMapping` and `@field(name:)` classifiers).
- **R69** (`@experimental_constructType`) becomes the second consumer of the same path mechanism. R69's current Spec body uses a comma-separated `graphqlField: SQL_COLUMN` mini-language; alignment moves R69's sub-fields to per-field `@field(name:)` paths with `$source` rooting at the bound jOOQ record. Today's `@field(name: "FILM_ID")` syntax already works under R159's bare-name rules and resolves identically to `@field(name: "$source.FILM_ID")`; the R69 migration is a documentation refresh and a directive-shape simplification, not a syntax break for existing schemas. R69's Spec body should be updated when this item ships; cross-reference noted at both sites.
- **R97** (`argMapping` grouping) is the input-side sibling extension and stays on its own track; the grouping syntax does not need to change. R97's path expressions stay rooted at `input` (R84's existing root); this item's `$source` is the output-side root. Both grammars share `PathExpr` and the `liftsList` model.
- **R96** (deprecate `@record`) owns the directive-removal story for `@record` on payload types. This item does not carry a defensive `@record`-on-payload rejection; R96's Phase 3 narrows the directive scope and any cleanup belongs there.
- **R12** (typed errors) owns the runtime population of the `$errors` slot. This item only pins the source-side reference; the catch-arm wiring, error-shape contract, and partition rules for non-data fields stay with R12.

## Apollo Connectors as precedent

Apollo's `@connect(selection: ...)` mapping language is the closest external precedent. Its sigil model (`$` = response root, `$this` = parent, `$args` = field args, `@` = method receiver) and grammar (alias-and-path with GraphQL-shaped sub-selection) inform this item's design. We deliberately take less than Apollo offers: per-field `@field(name:)` placement instead of a whole-payload `selection` string, no method-chain transforms, no nested `{ ... }` sub-selection in this item's scope. The convergence point is the "named root, dotted path, list-lifting" core; the divergence is the surrounding syntax shell, dictated by graphitron's per-field directive idiom.

## Out of scope

- **DML-producer carrier walk (R75, R141).** Different producer, different upstream shape; the DML path continues to use R141's PK-keyed-map for now. A future item may unify the DML path under `$source` once the migration cost is paid.
- **Path-form coverage of FK-traversal use cases via jOOQ path-based joins.** The grammar is designed to accept FK chains as path steps, which would let path-form `@field(name:)` express some of what `@reference` expresses today. This is overlap, not replacement: `@reference` stays as a first-class directive for the foreseeable future, and the two mechanisms coexist on the same surface. The consumer-side work (lifting `FkJoin` / `ColumnReferenceField` to walk path expressions where the schema author opts in) is its own follow-up item. Motivation only here.
- **`argMapping` extension for `$source` on the input side** (parent-PK threading into a child `@service` resolver, currently done by implicit type-matching). Sibling Backlog item to be filed once this item's syntax is pinned; dataloader source-binding implications called out at that site, not here.
- **`$context` sigil consumer.** Reserved in the grammar; its consumer item (path-form contextArguments, unifying with today's list-form shorthand) is not yet filed.
- **Transforms / method-chain (`@`, `->name(args)`).** Apollo precedent exists; no graphitron use case yet. Defer.
- **Nested `{ ... }` sub-selection.** Would let one `@field(name:)` construct a sub-object from multiple paths. Useful for the eventual R69 successor but not needed for the carrier-payload case. Defer.
- **Query-side payload-shape semantics.** Mutations are the natural place for payload shapes (per common GraphQL convention); the inference rules above apply uniformly, but the only fixture we have is mutation-side. Query-side fixture coverage is a follow-up.
- **`@record`-on-payload defensive rejection.** R96 owns; this item does not carry a belt.

## Spec must address

- The exact module-boundary for `PathExpr` reuse: lift the parser to a shared location (e.g. `selection/` or a new `path/` package) consumed by both `argMapping` and `@field(name:)` classifiers, or keep R84's home and parameterise the root-binding step.
- The classifier-check keys this item introduces: one for the name-match rule, one for the type-match fallback rule, one for the type-at-path matching rule, one for the reserved-`$context` rejection, one for the "`$source` not defined at this site" rejection. Each is load-bearing and gets a `@LoadBearingClassifierCheck` key.
- Pinning the set of sites where `$source` is defined (carrier-payload field, child field of a `@table`-backed parent, child field of a `@record`-backed parent, others) and the set where it is not (Query roots, unbacked interfaces). The Spec should enumerate exhaustively; the Spec-must-address list above on `Path walk` and the new "$source not defined" rejection both consume this enumeration.
- Whether `@field(name: "X")` (explicit, single bare segment) runs strict name-match on `$source` or inherits the omitted-directive's type-match fallback. Recommended: strict name-match (the asymmetry above) on the grounds that an explicit name should not silently bind to a differently-named accessor; the Spec confirms or overturns.
- Exact rejection-message wording, including the closest-match hint format (reuse R84's hint formatter; do not reimplement).
- Test surface: pipeline-tier cases for name-match, type-match, ambiguity, inference-failure, type-mismatch, reserved-sigil rejection, `$errors` source path, `$source`-undefined rejection, `$source`-alone (root-value) binding; execution-tier case against the OpprettRegelverksamlingPayload reproducer (or sakila equivalent if we add one).
- LSP-tier coverage: `@field(name:)` autocomplete for `$source.<accessor>` paths against the producer's reflected return type, similar to R84's `argMapping` path completion. Picks up R157's `typesByName` projection for step resolution; the new arms are root selection (`$source` / `$errors` / reserved `$context`) and the root-value `$source` suggestion.
- The classifier-check key for the `$source`-is-internal invariant: when a path-leaf SDL field carries a border translator (`@nodeId`, enum textmap, custom scalar binding) the classifier requires the source accessor's Java type to match the translator's internal-side expectation. One load-bearing key per translator family or one shared key with per-translator rejection messages; the Spec should pick.
