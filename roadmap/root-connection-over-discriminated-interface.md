---
id: R650
title: "Support @asConnection on a root field returning a single-table discriminated interface"
status: Backlog
bucket: feature
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Support @asConnection on a root field returning a discriminated table interface

## Problem

A root query field returning a discriminated table interface (`@table` + `@discriminate`, with
implementer types pinned by `@discriminator(value:)`) cannot be paginated. The `TableInterfaceType`
arm of `FieldBuilder`'s root classifier rejects the pair with a typed deferral, "`@asConnection` on
a root field returning a single-table discriminated interface ('X') is not yet supported; return the
list shape instead", so a consumer wanting a paginated `ContentConnection` has to fall back to an
unbounded `[Content!]!`. Reported by a consumer.

The list shape works, so the deferral is a missing emission rather than a missing design. But it is
not merely a missing emission: pagination is the first thing in this codebase that would apply
`.limit()` to a query whose FROM clause carries JOINs, and one of the two join families the
assembly emits is not currently guaranteed single-valued. That is the substance of the item, and it
is settled below.

**Scope: the whole `TableInterfaceType` arm, not just the single-table subset.** The rejection fires
for every discriminated table interface, and both participant shapes turn out to be paginable (see
the fan-out section), so day one covers all three sakila roots: `allContent` (participants share the
base table, one cross-table LEFT JOIN arm), `allParties` (joined detail tables on a single-column
shared primary key) and `allSubjects` (joined detail tables on a composite shared key). The title
keeps the narrower wording only because the rejection message does; the message is retired here.

**The rejection is pinned, contrary to this item's earlier draft.** Two tests assert it and both
must change:

* `GraphitronSchemaBuilderTest`'s `InterfaceUnionFieldCase.TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED`
  enum row asserts an `UnclassifiedField` carrying a `Rejection.Deferred` whose reason contains
  "@asConnection on a root field", "single-table discriminated interface" and "not yet supported".
  Per the `classified-corpus` skill this is a pure-verdict row subsumed by the new corpus example,
  so it is deleted rather than rewritten.
* `RootLauncherRendererTest.discriminatedSource_connectionAndFannedPairsAreUnrepresentable` asserts
  the `LauncherCommand` constructor throws "never paginates" on the `(DiscriminatedTable,
  Connection)` pair. Narrow the method to its fanned half, which pins "runs single-tenant" and
  survives unchanged.

The unpinned message is the *child* one (the `TableInterfaceType` arm of
`classifyObjectReturnChildField`, no "root" in the text). That belongs to
`roadmap/child-connection-over-discriminated-interface.md`, which states it correctly.

## Why the emission is missing

`DiscriminatedTableFragments.assembly` populates a `LinkedHashSet<Field<?>> fields` (the
`__discriminator__` routing alias, each single-table branch's `$project`, the joined-table
participants' base slice, then the selection-gated cross-table and joined-detail aliases, which also
append to `fields`) and then, in the same fragment, declares `SelectJoinStep<Record> step =
dsl.select(new ArrayList<>(fields)).from(base)` before chaining the discriminator-gated LEFT JOINs.
The caller finishes the chain.

The connection arm cannot reuse that as-is. `ConnectionHelper.pageRequest(Integer first, Integer
last, String after, String before, int defaultPageSize, List<SortField<?>> orderBy, List<Field<?>>
extraFields, List<Field<?>> selection)` takes the selection list as an argument and returns a
`PageRequest` whose `selectFields()` is `selection` plus any `extraFields` (the cursor columns) whose
`getName()` is not already present. That merged list is what `dsl.select(...)` must receive, so the
page request has to be emitted *between* the field-list population and the `step` declaration. Those
two are currently welded into one fragment.

Note the split point is after `crossTableAliasDeclarations` and `joinedDetailAliasDeclarations`, not
merely after `fieldsList`: both of those emit selection-gated `fields.add(...)` calls.

## The fan-out question, settled

`.limit()` slices rows, not entities, so a gated LEFT JOIN that can match more than one row per base
row corrupts page boundaries and `hasNextPage`. The two families differ:

**Joined detail (`ParticipantRef.JoinedTableBound`): provably single-valued, already enforced.**
`TypeBuilder.resolveJoinedTableParticipant` requires the detail table's foreign-key columns to the
base to *be* the detail's own primary key, and rejects otherwise with "joined-table participant 'X':
the base->detail join is not single-valued." `JoinedTableInheritancePipelineTest`'s
`detailJoinNotPkEqFk_rejected` pins it. `joinedDetailJoinChain` emits the ON clause over exactly
those slots, so the relation is 1:0..1 by build-time construction. `allParties` and `allSubjects`
paginate soundly with no new work.

**Cross-table (`ParticipantRef.TableBound.crossTableFields()`): not guaranteed, and this is a
latent defect today.** `TypeBuilder.extractCrossTableFields` checks that the `@reference` path is a
single FK-derived hop leaving the base, and that the resolved column lives only on the target. It
checks nothing about direction or key uniqueness, and slot orientation is deliberately
direction-blind (`BuildContext.resolveFkSlots` swaps the slot when the FK is not on the source, and
`On.ColumnPairs` documents readers as direction-blind). So a reverse-orientation hop, where the
target holds a non-unique FK back to the base, classifies green and reaches `crossTableJoinChain`.
`DiscriminatorReferenceContradictionPipelineTest.referenceOnDetailOnlyColumn_staysValid` pins that
orientation as valid over a live catalog FK; it happens not to fan out only because that detail
table's FK columns are its composite primary key, which nothing on this path checks.

A schema that does fan out: base `film` with `@discriminate(on: "rating")`, a participant
`@table(name: "film") @discriminator(value: "PG")` carrying `actorId: Int @reference(path: [{key:
"film_actor_film_id_fkey"}]) @field(name: "actor_id")`. `film_actor`'s primary key is `(actor_id,
film_id)`, so its `film_id` is non-unique and every PG film multiplies by its cast size. Today that
surfaces as duplicate entities under the list shape, a `TooManyRowsException` at request time under
the single shape (`.fetchOne()`), and last-row-wins under the service arm's by-PK re-map. Note that
`JoinStep`'s class javadoc claims "the validator rejects one-to-many navigation on a single-value
field"; no such validator exists, so that paragraph is aspirational and must not be leaned on.

**Resolution: state the missing invariant, do not gate pagination.** Mirror the joined-detail check
in `extractCrossTableFields`: admit the hop when its target-side columns are a candidate key of the
target table, that is when the column set equals some entry of
`ctx.catalog.candidateKeys(targetTable)` (which returns the primary key plus every unique key), and
otherwise surface a rejection through `ctx.addDiagnostic`, as that method already does for the
base-resident-column contradiction.

State it as *uniqueness*, not as direction. The two forms differ on exactly one reachable shape: the
base-holds-FK orientation always passes either form (SQL requires the referenced side be primary or
unique), but the reverse orientation passes the uniqueness form when the target's FK columns happen
to be its own key. That shape is `referenceOnDetailOnlyColumn_staysValid`, which stays green under
the uniqueness form and would break gratuitously under a direction check. The uniqueness form
therefore rejects exactly the schemas that are already silently broken and no others, which is what
makes it an invariant rather than a pagination gate.

With both families enforced single-valued, one base row is one entity, the seek is over base-table
key columns, and the connection arm needs no floor of its own.

**Reviewer decision to make:** whether the cross-table invariant lands inside this item or as its
own. It is independently valuable (it fixes today's list-shape duplication and single-shape runtime
failure) and it is a build-acceptance change: a schema carrying a reverse-orientation non-unique
cross-table hop builds today and would newly fail. The recommendation is to keep it here, as its own
commit, because without it this item is silently wrong and a dependency hop buys only latency for
roughly fifteen lines mirroring an existing check in the same file. Splitting it out is a reasonable
call to make instead.

## Implementation

* `TypeBuilder.extractCrossTableFields`: the candidate-key floor above, with a diagnostic naming the
  participant field, the target table, the hop's target-side columns and the target's candidate
  keys, in the style of the sibling messages in `resolveJoinedTableParticipant`.
* `DiscriminatedTableFragments`: add a two-part seam and keep `assembly` as the composed default, so
  none of its call sites change. `projection(source, alwaysProject, tableLocal)` emits the
  discriminator filter through the alias declarations; `joinedStep(source, tableLocal, CodeBlock
  selectExpression)` emits the `step` declaration over the caller's select expression plus both join
  chains; `assembly` becomes the two composed with `new ArrayList<>(fields)`. The four existing call
  sites (`RootLauncherRenderer`, `ReentryRowsFragments` twice for the DML follow-ups, and
  `TypeFetcherGenerator.buildTableInterfaceReprojection` serving the child twin and the service
  fetcher) are untouched, which also keeps `TypeFetcherGeneratorTest`'s shared-assembly assertions
  meaningful.
* `FieldBuilder`: delete the `FieldWrapper.Connection` deferral in the root `TableInterfaceType` arm
  and let the coordinate classify as a `QueryTableInterfaceField` carrying the `Connection` wrapper.
* `LauncherCommands.interfaceRow`: add the `FieldWrapper.Connection` fork `resultShapeOf` already
  has for the plain table root, including its no-resolvable-ordering `IllegalStateException`. The
  ordering comes from the same `orderingOf(qtif.orderBy(), ...)` call the list arm already makes.
  Update the javadoc's "Never `ResultShape.Connection`" claim.
* `LauncherCommand`: drop the `(DiscriminatedTable, Connection)` half of the constructor backstop
  and its comment. The single-tenant half stays.
* `RootLauncherRenderer`: replace the `DiscriminatedTable` arm's `ResultShape.Connection` throw with
  a connection body. It mirrors `connectionBody` but over the split seam:
  `OrderingBlock.declareBothViews`, the four fixed argument reads, `pageRequest(..., orderBy,
  extraFields, new ArrayList<>(fields))`, then `joinedStep(source, tableLocal,
  page.selectFields())`, then `step.where(condition).orderBy(page.effectiveOrderBy())
  .seek(page.seekFields()).limit(page.limit()).fetch()`, then the carrier construction
  `(result, page, tableLocal, condition[, dsl])`.

## What needs no work, with the evidence

Checked, so the implementer does not re-derive it:

* **Connection type synthesis.** `ConnectionPromoter.promotionFor`'s directive arm gates only on
  `@asConnection` plus a list return and reads the element type name whatever its kind, so
  `[Content!]! @asConnection` already mints `ContentConnection` / `ContentEdge` with `node: Content`.
* **The entry point.** `TypeFetcherGenerator.buildQueryTableFetcher` reads
  `RootLauncherRenderer.valueTypeOf(row)`, whose `Connection` arm already yields the carrier ref.
  Both root arms already route through it.
* **Field registration.** `FetcherRegistrationsEmitter` binds `edges` / `nodes` / `pageInfo` /
  `totalCount` per connection *type*, and `FetcherEdgeCommands.rowOf` returns null for
  `QueryTableInterfaceField` exactly as it does for `QueryTableField`.
* **Ordering.** The base table's primary key gives `OrderBySpec.Fixed` by default, `@orderBy` on
  this coordinate already lowers, and `TypeFetcherGenerator` already emits the order-by helper for
  `QueryTableInterfaceField` into the same generated class the launcher lands in, so
  `declareBothViews`'s unqualified helper call resolves. `validatePaginationRequiresOrdering` covers
  the leaf through `SqlGeneratingField`.
* **Type resolution.** The existing `TypeResolver` routes off `__discriminator__`, which the
  assembly projects unconditionally, so no new resolver and no selection-set dependency.
* **`totalCount`.** The generated resolver runs `dsl.selectCount().from(cr.table())
  .where(cr.condition())`: base table, no joins. That is correct here because the condition glue's
  signature is `Conditions.<method>(baseTable, env.getArguments()[, env])`, so a condition cannot
  reference a joined alias, and the assembly ANDs the discriminator `IN` into `condition` before the
  caller binds it onto the carrier. Worth restating in the implementation commit, since the
  correctness of the count rests on that signature.
* **Facets.** Already rejected, not silently ignored. `GraphitronSchemaBuilder`'s
  `unsupportedFacetCarrierReason` requires the carrier's element be a `@table`-backed
  `GraphQLObjectType`, so an interface element trips `facetMisuseReason` with a message naming
  interface/union connections as a follow-up. That guard is keyed on SDL, not on classification, so
  it fires today and keeps firing after the deferral lifts. Optional nicety: its trailing "or move
  the connection to the root" reads oddly for a carrier that is already at the root.
* **Corpus exemptions.** `ExemptionRegistry.MEMBER_KNOWN_GAPS` is keyed per `OperationMember`, and
  the launcher obligation covers `LaunchSource` and `ResultShape` arms individually, both of which
  are already reached. Nothing to remove.

## Coverage

* **Corpus.** One `ClassifiedCorpus` example crossing `joined-table-interface`'s root row with
  `catalog`'s `films`: `@asConnection` with `@classified(source: Query, operations: [OrderBy,
  Paginate, Select], target: Single, targetShape: Connection)` and `@commits(source:
  DiscriminatedTable, result: Connection)`, a pair no example carries today because the
  `LauncherCommand` constructor currently makes it unrepresentable. Give it a `query` so it renders,
  which puts the drift guard on `docs/architecture/reference/code-generation-triggers.adoc`. Delete
  the subsumed `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED` enum row in the same commit.
  `docs/manual/_generated/supported-schema-shapes.adoc` is keyed per sealed leaf and already lists
  `QueryTableInterfaceField` unmarked, so it does not change.
* **Cross-table invariant.** A pipeline-tier rejection test on the `film` / `film_actor` shape above,
  plus a green case pinning that `referenceOnDetailOnlyColumn_staysValid`'s orientation still
  classifies. The rejection test is the one that would have caught today's defect.
* **Exact SQL.** New `RootLauncherSqlBaselineTest` cases, modelled on the mechanical cross of
  `interfaceRoot_singleTable_projectsTheDiscriminatorRoutingAlias` with
  `connectionRootPageQuery_seekLimitChainWithCursorKeyInSelectList`: one page query over
  `allContent`, one `totalCount` twin asserting the second statement counts over `content` with the
  discriminator `IN` predicate, and one over `allParties` or `allSubjects` pinning that the gated
  detail LEFT JOIN composes with seek and limit. The class javadoc's shape enumeration needs
  extending.
* **Execution tier.** Page walks in `GraphQLQueryTest` beside the existing `allContent_*` /
  `allParties_*` / `allSubjects_*` cases. Seed volume is thin: `content` has 4 rows, `jti_subject` 4,
  `party` 3. Either seed more rows the way the multi-table polymorphic connection fixture seeded
  dedicated `paged_a` / `paged_b` tables, or accept `first: 1` / `first: 2` walks and skip asserting
  a page boundary landing mid-participant-group. Prefer seeding; a backward (`before`) page and a
  boundary inside a participant group are the two cases most likely to expose a cursor bug.

## Retired vocabulary

* The root deferral message "@asConnection on a root field returning a single-table discriminated
  interface ... is not yet supported; return the list shape instead" (the child twin's near-identical
  message survives and is R651's).
* The `LauncherCommand` backstop message "a discriminated-interface launcher never paginates" and
  its "the classifier defers @asConnection on the single-table-interface root" comment.
* The `RootLauncherRenderer` throw "a discriminated-interface launcher never paginates; the command
  constructor rejects the pair before rendering".
* `LauncherCommands.interfaceRow`'s javadoc sentence "Never `ResultShape.Connection`".
* The enum constant name `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED`.
* Prose asserting that no paginating emission exists over the discriminated re-projection.

## Out of scope

* **The child field.** `roadmap/child-connection-over-discriminated-interface.md` owns it, along
  with the `@splitQuery` design question it depends on. It can consume this item's `projection` /
  `joinedStep` seam directly.
* **Facets on this coordinate.** Already rejected with a message naming it a follow-up; lifting that
  is a separate item if a consumer asks.
* **Multi-table interface and union ordering.** `R382`'s axis, unrelated.

## Provenance

Both `R405` (root `@service` return) and `R406` (DML return) recorded `@asConnection` as out of scope
for this interface family; this item picks up the root read half. Sibling to
`roadmap/child-connection-over-discriminated-interface.md`. Distinct axis from `R382`.
