---
id: R453
title: "Reject sort-enum values missing @order/@index instead of silently skipping (empty ORDER BY breaks keyset pagination)"
status: Spec
bucket: bug
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Reject sort-enum values missing @order/@index instead of silently skipping (empty ORDER BY breaks keyset pagination)

## Symptom

A sort enum with a value that carries **neither `@order` nor `@index`** builds cleanly, even though the documentation promises a per-value build failure (`docs/manual/how-to/sort-results.adoc:135`, `docs/manual/reference/directives/order.adoc:83`, `orderBy.adoc:48-49` all state that a missing value "fails the build with a per-value diagnostic"). At runtime the unannotated value is silently skipped. On a plain field the rows come back in default/PK order; **on a paginated connection the result is nondeterministic** — see below.

## Mechanics

- `OrderByResolver.java:312` `continue`s past any sort-enum value lacking `@order`/`@index`: no error, no `NamedOrder` produced.
- `FieldBuilder.java:1437-1438` classifies an enum as a sort enum via `anyMatch`, so an enum where only *some* values are annotated passes classification — the unannotated values then fall into the skip above.
- The two generated dispatch arms diverge. Single-arg dispatch (`TypeFetcherGenerator.java:5087`) has `default -> baseExpr` (silent default-order fallback). The list-arg dispatch switch (`:5127-5150`) has **no default arm**; when every supplied entry is an unannotated value the generated helper returns an `OrderByResult` with empty sort-field and column lists.
- `buildConnectionOrderingBlock` (`:4914-4924`) feeds those possibly-empty lists straight into the connection as both the ORDER BY and the keyset cursor columns. Empty means no ORDER BY and no cursor columns, so keyset pagination slices a nondeterministic set: rows duplicate or vanish across pages.

`GraphitronSchemaValidator`'s ordering checks (`:247-287`) only fire on `OrderBySpec.None`; an `Argument`-shaped spec passes untouched. No lint rule covers it; no test exercises a partially annotated sort enum.

## Design decisions

Settled at Spec, with a principles-architect consultation on the fork points:

- **Home of the rejection: `OrderByResolver.resolveOrderByArgSpec`**, not the classifier and not the validator. The resolver already iterates every enum value and already owns a rejection channel (`Resolved.Rejected` → `TableFieldComponents.Rejected` → standard validation errors, used today for catalog-lookup failures). `FieldBuilder.classifyOrderByArg`'s `anyMatch` stays as-is: it answers *detection* (which input field is the sort enum), while completeness (every value annotated) is a distinct fact; flipping to `allMatch` would collapse the two and degrade the diagnostic to "no sort enum field found". A *fully* unannotated enum never reaches the resolver; `anyMatch` fails and the argument is already rejected as unclassified at detection. Only partial annotation slips through, which is exactly the case this item closes.
- **No validator mirror.** The "validator mirrors classifier" pattern targets generator branches that would otherwise fail at runtime; here the rejection fires at the parse boundary and makes the bad state unrepresentable in the model (`namedOrders` is complete by construction). The model does not carry the enum's full value list, so a mirror would need schema access the validator arm doesn't have; re-deriving it there would be a consumer-side shadow taxonomy.
- **No generated-code guard.** An earlier draft added `if (sortParts.isEmpty()) return baseExpr;` to the list-arg helper as defence-in-depth. Dropped: the invariant's single enforcer is the resolver rejection, pinned by the pipeline-tier test below, and emitted code carries no defensive guards for shapes the classifier guarantees. The single-arg `default -> baseExpr` arm is not precedent for one; it is a switch-*expression* exhaustiveness obligation the compiler forces, while the list-arg body is a switch-*statement* that asks for nothing.
- **Typed rejection arm, not prose.** The detection site accumulates structured facts (the sort enum's type name plus the set of unannotated value names); flattening them into a `Rejection.structural` string would be prose composed at the detection site. A new `Rejection.AuthorError` arm carries the data, following `RecordBindingMultiProducer` / `TypeConflict` (name + typed list, per-item lines in `message()`, resolve hint at the end), so downstream tooling (LSP fix-its) can read the missing-value list off the arm.
- **Accumulate all missing values, not fail-fast.** Consistent with the list-carrying arms above. Note the deliberate mix of gathering styles inside `resolveOrderByArgSpec` afterwards: missing-directive values accumulate into one rejection, while catalog-lookup failures on *annotated* values keep the existing fail-fast. They are different failure classes; do not "align" one to the other.

## Implementation

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java`: new `AuthorError` arm, shape `SortEnumMissingOrder(String enumTypeName, List<String> missingValues)` (plus the prefix-accumulation `prefixedWith` treatment; follow `RecordBindingMultiProducer`). `message()` wording aligned with the docs' promise, one line per missing value, e.g.: `sort enum 'ActorOrderField' is used with @orderBy but these values declare no ordering directive:` / `  - LAST_NAME` / `  Every value of an @orderBy-bound enum must declare exactly one of index, fields, or primaryKey via @order.` Add a factory in the `===== Factories =====` block.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/OrderByResolver.java` (`resolveOrderByArgSpec`, the `continue` at line 312): collect every value with neither `@order` nor `@index` while iterating; after the loop (or before resolving annotated values, implementer's choice), if the collection is non-empty return `Resolved.Rejected` with the new arm. Annotated values keep their existing resolution and fail-fast catalog path untouched.
- `docs/architecture/explanation/typed-rejection.adoc`: the one-line note the taxonomy requires for a new arm.
- No user-manual changes: `docs/manual/how-to/sort-results.adoc`, `reference/directives/order.adoc`, and `orderBy.adoc` already promise this exact per-value build failure; the change aligns code with the existing promise. (First-client docs-draft requirement is satisfied vacuously for the same reason.)

## Tests

Pipeline tier only (`GraphitronSchemaBuilderTest` style: SDL in, classification verdict / build error out):

- Partially annotated sort enum (two values, one carrying `@order`) used via `@orderBy` → build error naming the unannotated value.
- Two or more unannotated values → single rejection listing every missing value (pins accumulate-all).
- Guard the existing admit: fully annotated sort enum still classifies to `OrderBySpec.Argument` (already covered around `GraphitronSchemaBuilderTest:3747`; extend only if the new rejection path could plausibly regress it).

No generation-tier assertion (code-string assertions on generated bodies are banned, and the guard it would have pinned is dropped) and no execution-tier test (the nondeterministic-pagination state becomes unbuildable, so there is no runtime surface to drive).

## Non-goals

- Empty `@order` and `@order`+`@index` coexistence: R181. The new arm is named and shaped so R181 can fold its rejections into a shared order-directive family later if that proves natural.
- Runtime handling of a null sort-field entry in the list-arg helper (a nullable input field lets a client send `{direction: DESC}` without a field; the generated switch-statement on a null `String` throws NPE). Adjacent but a different, runtime-shaped gap; file separately if it bites.

## Relationship to R181

R181 (`validate-order-directive-args`) covers a *different* gap: an **empty** `@order` directive and `@order`+`@index` **coexistence** on one value. This item covers a value with **no ordering directive at all**, plus the divergent generated dispatch arms and the empty-ORDER-BY keyset-pagination consequence R181 does not touch. The two are siblings and a combined fix may be natural, but the shapes and the pagination-correctness blast radius are distinct.

Confirmed high severity by the architecture-trap audit (adversarially verified against code and docs; R181 checked and found not to cover this case).
