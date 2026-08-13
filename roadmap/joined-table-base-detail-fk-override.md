---
id: R393
title: "Declare the joined-table base-to-detail join on @discriminator(reference:)"
status: Spec
bucket: feature
priority: 7
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-08-13
---

# Declare the joined-table base-to-detail join on @discriminator(reference:)

## Problem

A joined-table inheritance participant (a `@discriminator` implementer whose own `@table` is a detail
table distinct from the discriminated base) has its identity join to the base *inferred from a field*,
not declared. `TypeBuilder.resolveJoinedTableParticipant` walks the participant type's field
definitions looking for one carrying `@reference` whose single-hop FK resolves to the base, takes the
first such hop as the identity join, and rejects the participant when it finds none:

```
Type 'MaskinportenApplikasjon': joined-table participant 'MaskinportenApplikasjon' (detail table
'maskinporten_applikasjon') has no base-resident field carrying @reference to name the base->detail
join; declare one inherited field with @reference back to 'subjekt'. Candidate foreign keys between
the tables: fk_maskinporten_applikasjon_subjekt, fk_maskinporten_applikasjon_endret_av,
fk_maskinporten_applikasjon_ansvarlig, fk_maskinporten_applikasjon_opprettet_av
```

Reported by a consumer, and the report is the item: the mechanism is wrong, not just incomplete.

**The field-scan carries a fact that is not a field's to carry.** A parent-`@reference` on an inherited
field declares *that field's residence*: the column lives on the base, resolve it over there. Which
foreign key makes this type's rows *be* specialisations of base rows is a fact about the participant,
declared once. Today it is a side effect of a field-grained declaration, so an author whose detail type
exposes no base-resident field has to invent one purely to name the join. Nothing is wrong with the
consumer's schema except that the mechanism has nowhere to put the fact.

**The inference is ambiguous exactly where real catalogs are, and it fails in the silent direction.**
All four candidate FKs in the message above run from `maskinporten_applikasjon` to `subjekt`: the
identity FK plus three audit and ownership references (`endret_av`, `ansvarlig`, `opprettet_av`).
Audit columns pointing back at the same base are ordinary, so multiple FKs between detail and base is
the common case rather than the corner. That makes today's resolution unsound in the direction that
does *not* produce an error. `sawNonBaseReference` fires only for a `@reference` resolving to some
*other* table, so an author who declares `ansvarlig: Subjekt @reference(path: [{key:
"fk_maskinporten_applikasjon_ansvarlig"}])` hands the resolver a hop that does resolve to the base,
first-wins picks it as the identity join, and the PK=FK check then rejects the schema for a join that
is "not single-valued". The author's actual mistake in that scenario is nothing at all; the resolver
guessed one of four and blamed the schema for the guess. Field declaration order decides which of the
four wins, so the same schema with its fields reordered classifies differently.

This is the item R393 was filed for. Its earlier body framed the work as *disambiguating* `@reference`
and left the surface as an open question, naming "a path argument on `@table` / `@discriminator`" as one
candidate. That question is now answered, and the answer moves the fact off `@reference` rather than
teaching `@reference` to disambiguate.

## Directive surface

Add a `reference` argument to `@discriminator`, reusing the `ReferenceElement` grammar unchanged:

```graphql
directive @discriminator(
  value: String!
  reference: [ReferenceElement!]
) on OBJECT
```

```graphql
type MaskinportenApplikasjon implements Applikasjon
        @table(name: "maskinporten_applikasjon")
        @discriminator(value: "MASKINPORTEN",
                       reference: [{key: "fk_maskinporten_applikasjon_subjekt"}]) {
    ...
}
```

**Why `@discriminator` and not `@referenceFor`.** R458 already decided a per-participant FK-picker for
the *multitable* model, and R393's earlier body requires this item to reuse it or justify not doing so.
The justification is grain. `@referenceFor(type:, path:)` sits on the FIELD_DEFINITION that returns the
polymorphic type and names a participant by `type:`, because there the correlation genuinely is a fact
of the `(field, participant)` pair: each field owner has its own table to correlate from. A joined-table
participant's identity join has no field in it. The same `maskinporten_applikasjon`-to-`subjekt` FK is
the join for every root and child field that ever returns `Applikasjon`, so hosting it on the returning
field would duplicate one fact per coordinate and let two coordinates disagree about what a type *is*.
`@discriminator` is where the participant already declares its half of the discriminated contract, and
one more argument there keeps the participant's facts in one place.

**Why not `@table(reference:)`.** `@table` says which relation backs a type, on every table-backed type
in the schema. The identity join exists only for a participant of a discriminated interface, so the
argument would be inert on the overwhelming majority of its carriers and would need its own
"meaningless here" rejection. `@discriminator` is already exactly the population that needs it.

**Argument name and shape.** `reference:` rather than `path:` (which both `@reference` and
`@referenceFor` use for their element lists): on this directive `path` alone does not say what the path
connects, while `reference` reads as the participant referencing the base row it specialises. The value
stays the shared element grammar rather than a bare `key: String`, so the schema-qualified
`schema.constraint` form that `ReferenceElement.key` already documents keeps working, and there is one
element grammar in the language rather than one plus an exception.

**Restricted to one FK element.** An identity join is a single foreign-key hop by construction: the
PK=FK invariant says the detail's FK columns to the base *are* the detail's own primary key, which is
a statement about one constraint and cannot be made about a chain or about a `{condition:}` predicate.
So `reference:` accepts exactly one element and that element must be FK-derived, with multi-element and
condition-only forms rejected structurally, naming the participant. That is a narrower surface than
`@referenceFor(path:)` on purpose: the wider one has no meaning to lower here.

Authoring direction is detail to base, matching the parent-`@reference` it replaces, so
`ParticipantRef.JoinedTableBound.childToParent` keeps its current orientation
(`originTable` = detail, `targetTable` = base) and none of its readers move. With `{key:}` the author
never states a direction anyway, the FK carries it.

## What survives untouched

Checked, so the implementer does not re-derive it:

* **Field residence.** A participant field's base-resident / detail-resident split stays declared by
  parent-`@reference` per field and classified field-locally in `FieldBuilder`
  (`ChildField.ColumnBackedReferenceField` versus `ChildField.ColumnBackedField`). Only the *second*
  job of that directive, being scanned to discover the type's identity hop, moves. The
  `@reference`-on-inherited-field mechanism does not go away.
* **The three hop readers.** `DiscriminatedTableFragments.joinedDetailJoinChain` (the gated
  `base LEFT JOIN detail` ON clause), `JoinedTableReprojection.sharedKeyBaseColumn` (which pairs a
  participant column against the hop's slots to decide shared-key versus detail-exclusive), and
  `ParticipantRef.JoinedTableBound`'s own `On.ColumnPairs` invariant all read the resolved hop and
  never ask where it was named. They are unchanged.
* **The parse machinery.** `BuildContext.parseExplicitPath(elements, name, startSqlTableName,
  targetSqlTableName, returnTableRef, isList)` is already the entry point for an
  externally-supplied element list (it is `@referenceFor`'s), and it applies no empty-path FK
  auto-discovery, which is what this surface wants. No new parsing.
* **The PK=FK single-valued check.** It tests the resolved hop's source-side columns against the
  detail's primary key, so it moves with the hop and keeps its message. This is what lets
  `roadmap/root-connection-over-discriminated-interface.md` treat the joined-detail family as provably
  single-valued regardless of which surface named the join.

## Implementation

* `directives.graphqls`: the `reference` argument on `@discriminator`, with a description carrying the
  one-element / FK-only constraint and the detail-to-base direction.
* `TypeBuilder.resolveJoinedTableParticipant`: read `@discriminator(reference:)` first and resolve it
  through `parseExplicitPath` with `(detailTable, baseTable)` as the endpoints; fall back to the field
  scan only when the argument is absent. Reject a declared `reference:` that resolves to a table other
  than the base with a message about the identity join, distinct from the field-scan's
  `sawNonBaseReference` wording, since a declared wrong FK and a stray field reference are different
  author mistakes.
* `TypeBuilder`: the structural rejections for a multi-element and for a non-FK (`{condition:}`)
  `reference:`, both naming the participant and the argument.
* `ParticipantRef.JoinedTableBound` javadoc: it currently says the PK=FK invariant "is checked by the
  validator", which was never true (the check lives in `resolveJoinedTableParticipant` and surfaces
  through the diagnostic channel). Correct it while restating where the hop comes from.
* Fixtures: convert one of the two existing joined-table families in
  `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` (`allSubjects` over
  `jti_subject`, `allParties` over `party`) to the declared form and leave the other on the field-scan
  route, so both paths carry execution-tier coverage during the additive phase.
* `graphitron-sakila-db/src/main/resources/init.sql`: the ambiguous shape has no fixture today. The
  test catalog's only table pair with two FKs between them is `film` to `language`, which cannot serve
  because `film.language_id` is not `film`'s primary key, so it trips PK=FK before ambiguity is
  reached. Add the consumer's shape: a detail table whose identity FK columns are its own primary key
  plus a second, non-identity FK to the same base (an `endret_av`-style audit column). That fixture is
  what makes the whole item testable, and it is currently unrepresentable in the test catalog.

## Rejections that change

The item is a build-acceptance change in both directions, which the Spec reviewer should weigh
deliberately:

* `joinedTableParticipant_withNoInheritedReference_rejectedWithCandidateFkHint` becomes reachable only
  when *neither* surface names the join. Its message should name `@discriminator(reference:)` as the
  remedy, with the candidate-FK hint kept: the hint is exactly what an author needs to fill the new
  argument in, and the consumer's four-FK message shows it doing its job already.
* `parentReferenceToNonBaseTable_rejected` (the `sawNonBaseReference` rung) has to narrow or retire.
  It exists only because the resolver cannot otherwise tell an identity bridge from an ordinary
  reference, so once the join is declared, a detail field referencing some third table is legitimate
  cross-table navigation and rejecting it is wrong. Under the additive phase it must stay for the
  field-scan route and must *not* fire when `reference:` is declared. That per-route split is the one
  genuinely awkward consequence of staying additive, and it is an argument the reviewer may weigh
  against cutting over in one step.
* A schema that today classifies by first-wins over several base-resolving `@reference` fields will
  keep classifying the same way while the fallback lives. Whether that silent guess should start
  warning during the additive phase is a reviewer call; it is the shape most likely to be wrong today.

## Additive, then cutover

Per the structural-pivot technique in `roadmap/workflow.adoc`: the argument lands alongside the field
scan, the sakila fixtures dual-source it, and the field scan's identity-join role is removed in a
second slice once the fixtures and the manual have moved. The end state is one home for the fact, with
`@reference` back to declaring residence only. Splitting the cutover into its own item is reasonable if
the first slice runs long; what must not happen is the two routes living on indefinitely, because two
ways to name one fact is the defect this item exists to remove.

## User documentation (first-client check)

User-visible authoring, so the docs draft is part of the design.

`docs/manual/reference/directives/discriminator.adoc` gains the argument in its Parameters table and a
second canonical example, the joined-table one, beside the existing single-table `Content` family. Its
Constraints list currently states "The implementer's `@table` must match the interface's `@table`.
Mixing tables breaks single-table polymorphism", which R389 already made false when it shipped
joined-table inheritance; this item is where that sentence gets corrected rather than left to rot.

`docs/manual/how-to/polymorphic-types.adoc` carries the prose: when a detail table has more than one
foreign key to the base, name the identity one, and audit or ownership columns pointing back at the
base are the ordinary reason to need it.

The draft has to read simply enough that the four-FK case is obvious from the example alone. If it does
not, the surface is wrong and changes before implementation.

## Retired vocabulary

* The `resolveJoinedTableParticipant` diagnostic wording "has no base-resident field carrying
  @reference to name the base->detail join; declare one inherited field with @reference back to", once
  the remedy names the directive argument.
* "the join cannot be pinned in the unambiguous joined-table shape (disambiguation of the ambiguous
  shapes is a separate concern)", the deferral phrasing in the method's javadoc and in
  `JoinedTableInheritancePipelineTest`, which this item settles.
* Prose describing the identity join as inferred from, or riding on, an inherited field's
  `@reference`, in `ParticipantRef.JoinedTableBound`, `TypeBuilder`, the sakila fixture comments and
  the architecture docs.
* At cutover only: `sawNonBaseReference` and its message.

## Out of scope

* **Multi-hop or predicate identity joins.** Rejected structurally, with the reason stated above.
* **The multitable model.** `@referenceFor` owns it; the grain argument above is why the two surfaces
  stay distinct.
* **`@discriminate`-side changes.** The interface declares the column; nothing about this fact is the
  interface's.

## Provenance

R389 shipped joined-table inheritance handling the unambiguous shape only and rejecting the rest. R458
decided the multitable per-participant FK-picker (`@referenceFor`) that this item reconciles against
and declines to reuse, on grain. The surface question R393 left open is answered by the consumer report
quoted above. Sibling to `roadmap/root-connection-over-discriminated-interface.md`, which depends on
the PK=FK invariant this item relocates but not on where the join is declared.
