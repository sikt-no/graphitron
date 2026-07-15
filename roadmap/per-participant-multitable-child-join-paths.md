---
id: R458
title: "Per-participant explicit join paths on multi-table interface/union child fields"
status: In Progress
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-09
last-updated: 2026-07-14
---

# Per-participant explicit join paths on multi-table interface/union child fields

Single-cardinality multi-table polymorphic child fields (a field returning an interface or union backed by several participant tables) only support one join shape today: a single foreign key auto-discovered per participant from the participant's table back to the parent table. R452 closes the silent-wrong-data hole by *rejecting* every richer shape at build time. This item is the deferred capability R452's rejection points at: letting an author state a join path *per participant*, so multi-table child fields can serve the shapes auto-discovery cannot.

Four cases need it:

- **Multi-FK disambiguation.** A participant table with more than one FK back to the parent (auto-discovery finds two and fails; the author must pick which FK correlates the child).
- **Condition joins.** A participant correlated to the parent by a non-FK predicate (`@reference(path: [{condition: ...}])` on a single-table field today).
- **Multi-hop key chains.** A participant reached through an intermediate join table rather than a direct FK.
- **Same-table self-FK participants.** A participant whose table equals the parent/hub table, where `parsePath` derives no correlation (R452 rejects this with a cause-specific deferred message keyed to this item).

## Syntax decision: a new repeatable directive, `@referenceFor`

A field-level `@reference` cannot express this: `FieldBuilder.resolveChildPolymorphicJoinPaths` (`FieldBuilder.java:6765-6779`) parses the field's single `@reference` path once per participant, applying the same stated path against each participant's own table, so one stated path can be terminal-correct for at most one participant. Per-participant correlation needs a construct that binds a distinct path to each participant type.

```graphql
directive @referenceFor(
    "Name of the participant type this path applies to. Case sensitive."
    type: String!

    "Join path from the parent type's table to this participant's table."
    path: [ReferenceElement!]!
) repeatable on FIELD_DEFINITION
```

```graphql
type Address {
    occupant: AddressOccupant
        @referenceFor(type: "Customer", path: [{key: "customer_address_id_fkey"}])
        @referenceFor(type: "Staff",    path: [{condition: {className: "...", methodName: "..."}}])
}
```

One application states one fact at its natural grain: "for participant `type`, the path from the parent is `path`". Both arguments are flat (a scalar and the existing `ReferenceElement` list); no input wrapper. `path` reuses the `ReferenceElement` grammar (`{table:}`, `{key:}`, `{condition:}`) and `BuildContext.parsePath`'s element-resolution machinery (`BuildContext.java:1329-1350`) unchanged.

Alternatives considered and rejected (principles consult, 2026-07-09):

- **Revive `@multitableReference(routes: [ReferencesForType!])`.** The prior art (`directives.graphqls:158-160`, `:185-191`, still declared-but-rejected; retirement was R44, `changelog.md:534`). Rejected: the `routes:` input-wrapper list splices the `typeName` and `path` axes into one entry, the exact smell the "Directives carry only what the SDL author needs to say" principle names; and reviving a hard-removed name whose live rejection message says "no longer supported" muddies legacy migration. The name stays retired; its rejection text gets updated to point at `@referenceFor` (see Implementation).
- **Extend `@reference` with an optional `for:` argument.** Rejected: widens `@reference`'s contract at every location it applies (arguments, input fields) where `for:` is meaningless, the "argument most callsites never fill" smell; and it would entangle with R435's repeated-application concatenation semantics (see next paragraph).
- **Extend `@discriminate`/`@discriminator`.** Rejected on grain: the correlation is a fact of the (field, participant) pair, not of the participant type. The same object type can participate in several polymorphic fields with different parents, so object-level placement states the fact at the wrong grain. `@discriminate` is also the single-table mechanism; multi-table dispatch is defined by its absence.

**Repetition-semantics contrast (must be explicit in the docs).** Repeated `@reference` applications concatenate into one running chain in authored order (R435, `reference.adoc:88-104`). Repeated `@referenceFor` applications are keyed by `type:` and are independent; each application's `path:` list is the complete path for that participant, so there is no concatenation axis. Two directives on the same location sharing the `ReferenceElement` payload but with opposite repetition semantics is a real author-model hazard; the reference page carries a contrast note, and duplicate `type:` values across applications are rejected rather than merged.

## Semantics

- Legal only on child fields returning a multi-table interface or union (table-backed and record-backed parents, all four producer arms: `FieldBuilder.java:980`, `:1004`, `:6134`). Any other placement is a structural rejection: single-table (discriminated) polymorphic fields, non-polymorphic fields, root query fields.
- `type:` must name a table-bound participant (`ParticipantRef.TableBound`) of the field's return type, case-sensitively. Unknown names, non-member types, and `Unbound` participants reject with a message listing the valid participant names.
- **Override-merge with auto-discovery.** Participants without an application keep single-FK auto-discovery; an application overrides only its named participant. Multi-FK disambiguation therefore needs a route on the ambiguous participant only.
- **Path direction.** Source is the parent's table, the same source `resolveChildPolymorphicJoinPaths` passes to `parsePath` today (`parentTable.tableName()`; for record-backed parents this is the parent key owner table). Terminal must be the participant's table: the per-route terminal-target verdict (Check 1, `BuildContext.computeTerminalTargetVerdict`) is enforced with `Mismatch` rejecting and naming the participant. This is per-route, unlike today's multi-table arm which ignores the verdict entirely.
- **Same-table self-FK participants.** Auto-discovery's same-table skip (`BuildContext.java:1271-1274`) stays; an explicit `{key: "<self-FK>"}` route resolves through the normal FK lookup and produces a single-hop correlation. The slot orientation of a self-FK hop (which side correlates the parent, which the child) must be pinned by an execution test; both columns live on the same table and a flipped orientation is silently wrong data.

## Model: sealed per-participant correlation carrier

R452's type lift carries resolved parent-FK slot pairs per participant. This item generalizes that carrier into a two-arm sealed type on `ChildField.InterfaceField` / `UnionField` (`ChildField.java:702`/`:733`), decided once at classification:

- **`KeyTupleWhere(On.ColumnPairs slots)`**: the branch joins nothing; correlation is a key-tuple WHERE against the parent's bound key values (single form) or the `VALUES` join predicate (batched forms). Auto-discovered routes, multi-FK-disambiguated routes, and same-table self-FK routes all lower here. This is R452's carrier arm, renamed at most.
- **`JoinedCorrelation(List<JoinStep> hops)`**: the branch joins real tables. Each hop's `On` already distinguishes FK bridge (`On.ColumnPairs`) from authored predicate (`On.Predicate`); multi-hop is list length greater than one, condition correlation is a hop carrying `On.Predicate`. Non-empty enforced at construction.

The consult specifically steered away from a three-arm `FkSlots`/`Condition`/`Chain` seal: that shape splices two orthogonal axes (correlation kind and hop count) the resolved `JoinStep`/`On` model already carries, and duplicates the `ColumnPairs`/`Predicate` fork inside the chain arm. `participantJoinPaths` becomes `Map<String, ParticipantCorrelation>`; `TypeFetcherGenerator` (`:587-614`) threads it unchanged; the three emitter forms dispatch exhaustively on the seal.

**Deferred-rejection discipline across slices.** `ReferenceElement` admits conditions and multi-element lists from the moment the directive is declared, but emitters gain arms slice by slice. Each slice's classifier rejects, with a DEFERRED message keyed to the pending slice, every route shape whose emitter arm has not shipped; the classifier physically cannot construct `JoinedCorrelation` before slice 2. This extends R452's pattern forward and keeps every intermediate trunk state green against arbitrary schemas.

## Implementation

Sliced because the seams are real: each slice lands green on trunk and ships an author-usable capability.

### Slice 1: directive, classification, carrier generalization

- Declare `@referenceFor` in `directives.graphqls`; register in `SchemaDirectiveRegistry`; constants in `BuildContext` (`DIR_REFERENCE_FOR`, `ARG_TYPE`).
- New `BuildContext` entry point that resolves an explicit element list for a stated (source, target) pair, factored from `parsePath`'s element-resolution loop, returning `ParsedPath` with terminal verdict. `parsePath` itself is unchanged.
- `resolveChildPolymorphicJoinPaths`: read applications; validate placement, membership, duplicate `type:`; merge explicit routes over auto-discovery; produce the sealed carrier. Aggregate per-participant errors rather than short-circuiting on the first (today's `fail` on first participant hides later ones).
- Update R452's two entry-point messages: the same-table deferred rejection (rule 1b) and the FK-count context wrapper (rule 1c) both become live steers to `@referenceFor` with a one-line usage sketch. Update the `@multitableReference` rejection text (`FieldBuilder.java:2144`) and `multitableReference.adoc`'s migration section to name `@referenceFor` as the successor for per-route paths.
- Structural rejections: directive on any non-multi-table-polymorphic field or non-field location.
- DEFERRED rejections: routes resolving to more than one hop (keyed to slice 2) or to a predicate hop (keyed to slice 3).
- **Ships:** multi-FK disambiguation and same-table self-FK. Both lower to `KeyTupleWhere`; `branchParentFkWhere` (`MultiTablePolymorphicEmitter.java:1154`) and `batchedBranchJoinPredicate` (`:1743`) are untouched beyond consuming the new carrier type, since the parent side in all three forms is bound values, not a joined alias.

### Slice 2: multi-hop chains

`JoinedCorrelation` with all-FK hops, in all three cardinality forms (single `buildStage1Block`, batched list, batched connection). Each branch gains bridging joins from the participant table back toward the parent, reusing `JoinPathEmitter.generateAliases` / `emitBridgingJoin` (`JoinPathEmitter.java:44-149`), which the polymorphic emitter does not invoke today. Correlation to the parent stays value-bound: the hop-0 source columns (parent-table side) are compared against `parentRecord` values (single) or joined to `parentInput` (batched). Retires the slice-2 deferral.

### Slice 3: condition correlation

A hop carrying `On.Predicate` needs the parent table in scope as a SQL alias, which no form has today. Each such branch joins the parent table (aliased) bound to the parent key values (`parentRecord` / `parentInput`), then applies the two-arg condition method between parent alias and participant alias via `JoinPathEmitter.emitTwoArgMethodCall` (`:194-198`). Also covers `filter:`-carrying FK hops if stated. Retires the slice-3 deferral, at which point every shape `@referenceFor` can state is emittable.

## User documentation (first-client check)

New reference page `docs/manual/reference/directives/referenceFor.adoc`, draft skeleton:

> `@referenceFor` declares an explicit join path for one participant of a multi-table interface or union child field. By default the rewrite auto-discovers each participant's join as the single foreign key from the participant's table to the parent's table; `@referenceFor` replaces that discovery for the named participant only. Use it when a participant has more than one foreign key to the parent (pick one with `{key:}`), is reached through an intermediate table (chain multiple elements), correlates by a non-FK predicate (`{condition:}`), or shares the parent's own table (state the self-referencing `{key:}`).
>
> Signature: `directive @referenceFor(type: String!, path: [ReferenceElement!]!) repeatable on FIELD_DEFINITION`. `type` names the participant, case-sensitively. `path` is the same element grammar as `@reference`, read from the parent's table toward the participant's table.
>
> Contrast with `@reference`: repeated `@reference` applications concatenate into one chain; repeated `@referenceFor` applications are independent, one per participant, and each `path:` is that participant's complete path. Declaring the same `type` twice is a build error. Participants you do not name keep automatic discovery.

Plus: rewrite the constraints bullet in `polymorphic-types.adoc` (~line 122) from "auto-discovered per-branch FK paths are the supported multi-table-child idiom" to "auto-discovery is the default; `@referenceFor` is the explicit per-participant surface", with the four cases; extend the "Polymorphic child fields" how-to section with one worked `@referenceFor` example; cross-link from `reference.adoc` and `multitableReference.adoc` "See also". Doc slices ship with the code slice that makes them true (slice 1 documents the FK cases and names the deferred ones as pending).

## Tests

Pipeline tier (`RecordParentMultiTablePolymorphicPipelineTest` style, inline SDL fixtures, assert on the field record or rejection):

- Multi-FK disambiguation: participant with two FKs to the parent plus `@referenceFor {key:}` classifies to `KeyTupleWhere` with the chosen FK's slots; without the directive, the FK-count rejection now steers to `@referenceFor`.
- Same-table self-FK route classifies; unknown `type:`, duplicate `type:`, non-participant `type:`, directive on a single-table polymorphic field and on a plain field all reject structurally.
- Per-route terminal mismatch rejects naming the participant.
- Slice-1 deferrals: condition route and multi-hop route produce DEFERRED messages keyed to slices 3 and 2.
- Override-merge: one participant explicit, the other auto-discovered; both present in the carrier.
- Producer-arm coverage: interface and union, table-backed and record-backed parent (four arms) across the above.

Execution tier (sakila): `film` has two FKs to `language` (`language_id`, `original_language_id`), a real multi-FK fixture for slice 1: a `Language` parent with a polymorphic child whose `Film` participant is disambiguated by `@referenceFor`. Assert per-parent row correctness in all three forms (single, batched list, connection); the R452 symptom (arbitrary row per parent) is the regression being guarded. Sakila has no self-referencing FK; the self-FK execution case uses the fixtures-codegen schema (add one if absent). Slice 2 adds a multi-hop route through a join table (`film_actor` shape); slice 3 a condition route; each asserts correct correlated rows, not just green classification.

## Slice-1 review (2026-07-14)

Independent review of the slice-1 landing (`cbb6eb1`) plus follow-up:

- **Orientation bug fixed.** `resolveChildPolymorphicJoinPaths` resolved every `@referenceFor` route with a hardcoded `isList=false` orientation hint, so a list/connection child field with a same-table self-FK route got the single-valued slot orientation (`selfRefFkOnSource = !isList` decides a self-referential FK's direction; `JooqCatalog.foreignKeyOnSource` returns it verbatim for same-table FKs), silently returning the wrong rows. The field's cardinality is now threaded into both `parseExplicitPath` and the auto-discovery `parsePath`. Pinned at the pipeline tier (single vs list orientation flip) and, for the list direction, at the execution tier: a new `category`-hierarchy fixture (`Category.childRefs`, sibling `category_label` table added to `init.sql` for a uniform-1-PK multi-table pairing) with `MultiTablePolymorphicSelfFkOrientationExecutionTest` asserting children-vs-parent rows and multi-table dispatch.
- **Discovered latent crash (out of scope here; filed as R481).** A *single-cardinality* multi-table polymorphic child field whose **parent** table holds the FK to the participant correlates on a non-key parent column (parent side = the FK column, not the parent PK). `KeyTupleWhere` binds the parent's key values and the parent record projects only its key columns, so the emitted `parentRecord.get("<fk-col>")` reads an unprojected column and throws at runtime. This is not self-FK-specific: the `Customer.address: Named` shape in the R281 classified corpus is the same pattern (parent `customer` holds `address_id`), classification-only so never executed. A classification-time guard is the wrong fix (it cannot know the runtime projection, and over-rejects the corpus case); the correct fix is projecting the parent-side correlation column onto the parent record. The self-FK single "navigate to parent" case (`Category.parentRef`) is the same gap, which is why the slice-1 execution fixture covers only the list direction.

## Out of scope

- Root-level (query) multi-table polymorphic fields: R382 / R76 territory.
- Ordering and pagination semantics: the connection `__sort__` contract is unchanged; routes affect only the parent correlation.
- Per-participant `fieldsJoin` / `orderBy` emission (R76).
- Reworking `fkCountMessage` wording for non-polymorphic call sites (unchanged from R452's scoping).

## Relationship to R452

R452 (the build-time gate plus type lift) is the predecessor and a hard dependency; it must land first. Its rule 1b (same-table participant) uses the deferred-rejection arm carrying this item's slug, and its rule 1c wraps auto-discovery FK-count failures on these fields with a pointer here. Slice 1 turns both sites into live steers to `@referenceFor`. R452's rule 1a (reject field-level `@reference` on these fields) stays permanently: `@referenceFor` is the sanctioned surface, and bare `@reference` on a multi-table child field remains a rejection whose message names the replacement. The carrier generalization in this item builds directly on R452's type lift; if R452 lands the lift as `On.ColumnPairs`, slice 1 wraps it as the `KeyTupleWhere` arm.

