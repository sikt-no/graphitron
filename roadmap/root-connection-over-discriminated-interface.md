---
id: R650
title: "Support @asConnection on a field returning a discriminated table interface"
status: Ready
bucket: feature
priority: 3
theme: interface-union
depends-on: [batched-discriminated-interface-child]
created: 2026-08-13
last-updated: 2026-08-14
---

# Support @asConnection on a field returning a discriminated table interface

## Scope: both coordinates, one route

Covers the root field and the child field. The child half was
`child-connection-over-discriminated-interface` (R651), discarded into this item: once the root's
route became the same statement-composition question the child was already blocked on, they are one
design with two coordinates. This body is the respec the reopen asked for; the superseded plan (the
`targetAtMostOnePerSource` cardinality invariant on the hop) is retired wholesale and survives only
in Considered-and-rejected below and in git history at the reopen commit.

Depends on `roadmap/batched-discriminated-interface-child.md` (R661), which gives the child arm the
batched delivery its pagination rides. Only the child half depends on it; the root half is
implementable first.

## Problem

A field returning a discriminated table interface (`@table` + `@discriminate`, implementers pinned
by `@discriminator(value:)`) cannot be paginated at either coordinate. The root classifier's
`TableInterfaceType` arm rejects the pair with a typed deferral ("`@asConnection` on a root field
returning a single-table discriminated interface ('X') is not yet supported; return the list shape
instead"), and the `TableInterfaceType` arm of `classifyObjectReturnChildField` opens with the same
deferral minus "root". A consumer wanting a paginated `ContentConnection` falls back to an
unbounded `[Content!]!`. Reported by a consumer.

The scope is the whole `TableInterfaceType` arm at both coordinates, covering all three sakila
roots and their child twins: `allContent` (participants share the base table, one cross-table
`@reference` field), `allParties` (joined detail tables on a single-column shared primary key) and
`allSubjects` (joined detail tables on a composite shared key).

Pinned state:

* `GraphitronSchemaBuilderTest`'s `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED` enum row pins the root
  deferral. Per the `classified-corpus` skill it is a pure-verdict row subsumed by the new corpus
  example, so it is deleted rather than rewritten.
* `RootLauncherRendererTest.discriminatedSource_connectionAndFannedPairsAreUnrepresentable` pins
  the `LauncherCommand` constructor's "never paginates" throw on the `(DiscriminatedTable,
  Connection)` pair, alongside a "runs single-tenant" assertion that survives unchanged.
* The child deferral is unpinned: no test asserts its message.

## Why the pair was deferred, and what the reopen settled

`DiscriminatedTableFragments.assembly` is the shared body of every query over a discriminated
table interface (root launcher, child twin, service fetcher, the two DML re-fetch follow-ups). It
populates a `LinkedHashSet<Field<?>> fields` (the `__discriminator__` routing alias, each
single-table branch's `$project`, the base slice, then the selection-gated cross-table and
joined-detail aliases) and, in the same fragment, declares `SelectJoinStep<Record> step =
dsl.select(new ArrayList<>(fields)).from(base)` before chaining the gated LEFT JOINs; the caller
finishes the chain. Two things block a connection arm:

* Mechanically, `ConnectionHelper.pageRequest(...)` takes the selection as an argument and returns
  the merged `selectFields()` that `dsl.select(...)` must receive, so the page request has to be
  emitted *between* the field-list population and the `step` declaration, and those two are welded
  into one fragment.
* Substantively, the assembly emits two join families and only one of them may share a statement
  with `.limit()`. `.limit()` slices rows, not entities, so every join in the paginating statement
  must be proven single-valued, or the page contents, `hasNextPage` (a `pageSize + 1` over-fetch
  that counts rows) and `totalCount` (a base-only count) all go silently wrong at once.

The two families are not peers:

**Joined detail (`ParticipantRef.JoinedTableBound`): proven, part of the pattern.**
`TypeBuilder.resolveJoinedTableParticipant` requires the detail table's foreign-key columns to the
base to *be* the detail's own primary key and rejects otherwise ("the base->detail join is not
single-valued", pinned by `JoinedTableInheritancePipelineTest.detailJoinNotPkEqFk_rejected`).
`@discriminate` is an opt-in to subtyping where one base row is one entity; this join is the
pattern's own 1:0..1 edge and may ride the paginating statement.

**Cross-table (`ParticipantRef.TableBound.crossTableFields()`): unproven, and not part of the
pattern.** A cross-table field is a participant scalar whose `@reference` resolves one FK-derived
hop off the base (sakila: `FilmContent.rating`, hop `content.film_id -> film`). Nothing checks the
hop's target-side uniqueness, and slot orientation is deliberately direction-blind
(`On.ColumnPairs` documents its readers as direction-blind), so a reverse-orientation hop onto a
non-unique FK classifies green and fans out. Today that is a latent defect on every shape:
duplicate entities under the list, `TooManyRowsException` under the single fetch, last-row-wins
under the service arm's by-PK re-map.

The reopen settled how *not* to resolve this: not by enforcing the cross-table join's cardinality.
The cross-table `@reference` is ordinary navigation this arm happens to emit as a gated LEFT JOIN;
defending a misplaced join with a new invariant treats the accident as a contract, and would leave
the discriminated coordinates strictly more permissive than the plain table child on the same
authoring surface, which the codebase already names as unprincipled ("@asConnection on inline
(non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics").

## The route: cross-table fields become the standing scalar-reference shape

The generator already has exactly one answer for "a scalar field whose value lives one `@reference`
hop away", and it is not a join. `ChildField.ColumnBackedReferenceField` lowers to
`SelectTerm.ScalarSubselect`, rendered by `ProjectionUnitRenderer.scalarInnerSelect` as a
correlated scalar subquery in the parent's select list, capped `.limit(1)` (pinned structurally by
`ColumnReferenceFieldPipelineTest`, behaviourally by
`GraphQLQueryTest.films_languageName_resolvesViaScalarReference`). The subselect cannot multiply
outer rows, whatever the hop's cardinality. The discriminated assembly's gated cross-table LEFT
JOIN is the one place in the generator where an unproven-cardinality traversal is spliced into the
row-producing statement itself; the classification fork (`lookupParticipantCrossTableField` ->
`ChildField.ParticipantColumnReferenceField`) exists so the interface fetcher, not a per-field
method, materialises the value; it does not require the SQL shape to differ.

**Resolution: emit cross-table participant fields as the same capped correlated subselect, at every
coordinate.** Concretely:

* **Lower at capture, not in the render shell.** `LauncherCommands.discriminatedBranches` lowers
  each `CrossTableField` into the command vocabulary's existing subselect arm: a
  `SelectTerm.ScalarSubselect` over the one hop, carried on `Branch.SingleTable`, plus the two
  payloads the arm does not carry today, handled as slots rather than a new arm (per `SelectTerm`'s
  own rule that arms are SQL shapes, never reasons): the fixed participant alias
  (`FilmContent_rating`; `SelectTerm.Aggregate.asName` is the precedent for a subselect-shaped term
  with a fixed projected name) and the branch's discriminator-gate predicate
  (`base.<discCol> = '<value>'`), one extra predicate beside the hop filters `appendHopFilters`
  already folds into the subselect's WHERE. The gate preserves today's exact per-row values: a
  non-matching row projects NULL, as the LEFT JOIN's ON gate makes it do now.
* **One renderer for one SQL shape.** Lift `scalarInnerSelect` to a fragment both renderers call,
  parameterized on the parent table local (`ProjectionUnitRenderer` passes its own local, the
  discriminated arm passes `tableLocal`), rather than hand-building a twin inside
  `DiscriminatedTableFragments`. `crossTableAliasDeclarations` keeps its selection gate but its
  body becomes `fields.add(<subselect>.as("<alias>"))`; `crossTableJoinChain` and the
  null-defaulted alias-variable pattern (`CrossTableField.aliasVarName()`) retire.
* **Everything downstream is untouched.** The classification fork, the
  `ParticipantColumnReferenceField` leaf, the per-participant alias regime and the
  `FetcherEmitter` by-alias read all survive; only what the alias names changes (a subselect
  column instead of a joined column). `TypeBuilder.extractCrossTableFields` survives too, including
  its base-resident-column contradiction rejection, which is about authoring sense, not
  cardinality.

The conversion is unconditional rather than pagination-scoped, because `assembly` is shared: the
list, single, service and DML shapes all change SQL in the same commit, and the latent fan-out
defect dissolves everywhere at once by inheriting the plain shape's semantics. Under fan-out the
subselect picks one row, exactly as a plain-table scalar `@reference` does; if that semantics ever
tightens, it tightens for both in `scalarInnerSelect`, one place.

**No cardinality invariant, no schema newly rejected.** With the join gone there is nothing left to
enforce; an invariant here alone would make this arm *stricter* than the plain one on the same
authoring surface, the mirror image of the permissiveness argument that reopened the item. The old
plan's `targetAtMostOnePerSource` fact on `On.ColumnPairs` leaves with it, together with its two
recorded precision defects (equality-versus-containment over `candidateKeys`, and uniqueness backed
by a bare unique index being invisible to `candidateKeys`), which are now moot. Every schema that
classifies today keeps classifying;
`DiscriminatorReferenceContradictionPipelineTest.referenceOnDetailOnlyColumn_staysValid` stays
green untouched. The enforcer that replaces the invariant is an execution-tier pin over a genuinely
fanning shape (Coverage), since only that tier can observe row semantics.

**Why the paginating statement is then sound.** After the conversion it contains the base table,
the joined-detail LEFT JOINs (proven 1:0..1 at build time) and select-list subselects (row-neutral
by construction): one row is one entity, the seek runs over base-table ordering columns, and
`totalCount`'s base-only count (`dsl.selectCount().from(cr.table()).where(cr.condition())`, with
the discriminator `IN` ANDed into the condition before it reaches the carrier) agrees with the page
by construction. Nor is the statement shape novel: the plain table root connection's selection *is*
`$project`, and `$project` already renders scalar references as capped subselects, so "correlated
subselect inside a paginating statement" is the standing emission there.

## The seam

`DiscriminatedTableFragments` splits on the fact boundary, with `assembly` kept as the composition
so its existing call sites (`RootLauncherRenderer`, `ReentryRowsFragments` twice, and
`TypeFetcherGenerator.buildTableInterfaceReprojection` serving the child twin and the service
fetcher) are untouched, which also keeps `TypeFetcherGeneratorTest`'s shared-assembly pin
meaningful:

* `projection(source, alwaysProject, tableLocal)`: the discriminator filter through the alias
  declarations, everything that populates `fields`, subselect terms included.
* `joinedStep(source, tableLocal, CodeBlock selectExpression)`: the `step` declaration over the
  caller's select expression plus the joined-detail join chain, which after the conversion is the
  fragment's *only* join chain. Its javadoc carries the pagination-safety argument as a `{@link}`
  to `TypeBuilder#resolveJoinedTableParticipant` (the PK=FK check), so the reference gate keeps
  "every join here is proven single-valued" auditable rather than restated.
* `assembly` becomes the two composed with `new ArrayList<>(fields)`, with `{@link}`s to both
  halves.

Two named fragments beat one method with an optional select-expression parameter because the
paginating callers differ in *order*, not in a value: the page request must observe the populated
`fields` local before `select(...)` is composed. The `fields` local's name crosses the seam, so it
is minted once, as a constant on `DiscriminatedTableFragments` that both halves and the paginating
callers read.

The old plan left open whether the child shares this seam; the answer is yes. Both paginating
callers consume `projection` -> `pageRequest(...)` -> `joinedStep(...)`; the child then chains its
VALUES-table join onto the returned step (jOOQ's `SelectOnConditionStep` extends `SelectJoinStep`,
and the gated chains already re-assign `step`, so the chaining composes).

## Root connection emission

`RootLauncherRenderer`'s `DiscriminatedTable` arm replaces its `ResultShape.Connection` throw with
a body mirroring `connectionBody` over the seam: `OrderingBlock.declareBothViews`, the four fixed
argument reads, `pageRequest(first, last, after, before, <defaultPageSize>, orderBy, extraFields,
new ArrayList<>(fields))`, then `joinedStep(source, tableLocal, page.selectFields())`, then
`step.where(condition).orderBy(page.effectiveOrderBy()).seek(page.seekFields())
.limit(page.limit()).fetch()`, then the carrier construction `(result, page, <tableLocal>,
condition[, dsl])`.

## Classifier, command and renderer lifts (root)

* `FieldBuilder`: delete the `FieldWrapper.Connection` deferral in the root `TableInterfaceType`
  arm; the coordinate classifies as `QueryTableInterfaceField` carrying the `Connection` wrapper.
* `LauncherCommands`: extract `resultShapeOf`'s Connection derivation (the ordering lookup, the
  no-resolvable-ordering `IllegalStateException`, `defaultPageSize`, the helper and carrier unit
  refs) into one private helper both root arms call, rather than duplicating it into
  `interfaceRow`; the interface arm supplies `orderingOf(qtif.orderBy(), ...)` and a `null` facet
  plan. The `null` is legitimate for the same reason `batchedResultOf` hard-codes it, and
  additionally because `GraphitronSchemaBuilder.unsupportedFacetCarrierReason` rejects
  interface-element carriers at the SDL boundary; carry that producer-consumer fact as a `{@link}`
  to the guard. Update `interfaceRow`'s javadoc claim "Never `ResultShape.Connection`".
* `LauncherCommand`: delete the `(DiscriminatedTable, Connection)` half of the constructor backstop
  outright. The backstop's own comment says each half mirrors a parse-boundary rejection; after the
  lift there is no rejection to mirror on this axis, and restating a new invariant at the
  constructor would invent one with no parse-boundary owner. The single-tenant half keeps its
  mirror and survives untouched. The residual invariants on the pair live where they are enforced:
  pagination-requires-ordering in `validatePaginationRequiresOrdering` (keyed on
  `SqlGeneratingField`, which covers this leaf) and facet rejection in
  `unsupportedFacetCarrierReason`.

## The child half (rides R661)

R661 gives the child arm batched delivery: list cardinality mints a batched leaf with a
`LoaderRegistration`, keyed on the FK hop's source side, its rows method the unbatched fetcher's
statement with the correlation re-keyed onto the loader's key set. Coordination, settled here and
mirrored by an edit to R661's spec in this commit: R661 mints on `wrapper.isList()` alone, and
*this* item adds the `FieldWrapper.Connection` half of the fork in the same commit that lifts the
child deferral, so the connection branch is never present-but-unreachable. This item plans against
R661's batched leaf being a separate sealed record (the sibling family's precedent, and what its
component list implies); if its implementer picks a delivery slot on the existing leaf instead,
this item's child fork reads one leaf plus a fact and moves accordingly.

R661 has landed, and it settled the two forks this section was written before. Both went the way
this section assumed, so nothing above changes, but knowing the shapes by name saves a re-derivation:

* **The leaf is a separate record,** `ChildField.BatchedTableInterfaceField`, in the
  `TableTargetField` seal and implementing `BatchKeyField`. Its compact constructor pins
  `returnType().wrapper().isList()`, which is true for `FieldWrapper.Connection` too, so the
  connection lift needs no constructor edit: the mint gate to widen is the classifier's, where the
  `Rejection.deferred` at the arm's head still rejects `Connection` before the fork is reached.
* **The launch source is `LaunchSource.DiscriminatedCorrelatedChain`,** a third arm of the
  `Correlated` capability rather than a reuse of `CorrelatedChain`: the topology is shared verbatim
  (prelude, parent-input attach, WHERE fold) and only the select list forks, which is why
  `Correlated` no longer declares `projection()` — that member moved onto the
  `Correlated.Projected` sub-seal the two `$project`-ing arms implement. So the shared scatter is
  the machinery, as this section assumed: the gate to widen for the connection half is
  `TypeFetcherGenerator`'s `hasConnectionSplitField`, beside the `hasListSplitField` gate R661
  already widened.
* The rows-method body is `BatchedRowsFragments.discriminatedBody`, which composes
  `DiscriminatedTableFragments.projection` + a `step` declaration over
  `fromBridgeAndParentJoin` + the newly-extracted `joinedDetailJoins`. The `connectionTail`
  extraction this section calls for is the remaining work, and `discriminatedBody` is the arm that
  binds it.

The connection tail: `BatchedRowsFragments.connectionTail` already owns the per-key windowing
protocol for the plain batched child, five coupled facts (`rowNumber() over
(partitionBy(__idx__).orderBy(page.effectiveOrderBy()))`, seek applied pre-rank on the inner
select, `.asTable("ranked")`, the outer `__rn__ <= page.limit()` filter, and the cursor-independent
count source feeding `scatterConnectionByIdx`). Do not mirror it; extract it the way `joinedStep`
is extracted: one fragment taking the inner select's field list, the idx field and the page local,
with the FROM topology supplied by the caller. The plain arm binds it over its bridge-and-parent
join; the discriminated arm binds it over `projection` + `joinedStep` + the chained VALUES-table
join. The multi-table polymorphic child
(`MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod`) is deliberately *not* the model:
that shape stages a `UNION ALL` and synthesises `__sort__` / `__typename` because it has no base
table; here one base table exists, ordering comes from it, and `__discriminator__` rides the select
list, not the ordering.

Downstream of the rows method, the loader plumbing is R661's, unchanged: `scatterConnectionByIdx`'s
per-parent `ConnectionResult` carries the count source and the per-idx condition, and the emission
gate for the scatter helper widens from "any `BatchedTableField` with a Connection wrapper" to
include the interface leaf. `DeliveryFactRelation`: R661 owns the two-site delivery agreement for
the list coordinate; this item verifies both sites answer `Batched` for the *connection* coordinate
once the deferral lifts, and adds the corpus coordinate that makes `DeliveryFactPinTest` a gate
over it.

## Considered and rejected

* **The invariant-defended join** (the plan this respec replaces): keep the cross-table LEFT JOIN
  and land `targetAtMostOnePerSource` on the hop, enforced in `extractCrossTableFields`. Rejected
  by the Spec review: it defends a join the generator's own vocabulary says should not exist, and
  makes the discriminated coordinates more permissive than the plain child on the same authoring
  surface. Its verified mechanism analysis fed this body; the rest is in git history.
* **The two-stage shape.** `MultiTablePolymorphicEmitter.buildStage1ConnectionBlock` paginates over
  a manufactured `UNION ALL` relation because the multi-table interface has no base table. Here the
  pagination relation exists by construction; importing a two-round-trip emission to defend at
  runtime against a state the emission no longer produces is the wrong trade. Recorded as closed
  rather than deferred: the carrier's `table()` slot already accepts any `Table<?>` (the
  multi-table sibling binds its derived table there), so the carrier contract would not move if a
  two-stage ever became necessary.
* **Rejecting `@asConnection` when a participant carries cross-table fields.** Leaves the latent
  fan-out defect on the other shapes and makes the connection arm gratuitously narrower than the
  list arm over the same schema.
* **Full unification**: reclassify cross-table fields as ordinary `ColumnBackedReferenceField` so
  they ride the participant's `$project` and the whole cross-table capture machinery retires. The
  capture-time lowering above takes most of this idea's value; the remainder trips an
  alias-collision hazard (BY_RESULT_KEY aliasing collides when two participants declare same-named
  fields over different hops, where today's TypeName-prefixed aliases keep them apart) and is its
  own item if anyone wants it.

## Implementation (ordered; each numbered group is a commit)

1. **The subselect conversion.**
   * `LauncherCommands.discriminatedBranches`: lower `CrossTableField` to the subselect term with
     the fixed alias and the gate predicate, carried on `Branch.SingleTable`.
   * `ProjectionUnitRenderer.scalarInnerSelect` lifted to a shared fragment parameterized on the
     parent table local; `DiscriminatedTableFragments`'s cross-table emission renders through it;
     `crossTableJoinChain` and `CrossTableField.aliasVarName()` retire.
   * Re-freeze
     `RootLauncherSqlBaselineTest.interfaceRoot_crossTableParticipantField_gatedLeftJoinArm` to the
     subselect shape and rename it; the existing `allContent` execution assertions stay green.
   * The execution-tier fan-out pin (Coverage).
   * Javadoc sweep of the LEFT JOIN vocabulary: `ParticipantRef.TableBound.crossTableFields` /
     `CrossTableField`, `ChildField.ParticipantColumnReferenceField`, `LaunchSource`'s
     `Branch.SingleTable`, the `DiscriminatedTableFragments` class javadoc, and any
     `docs/architecture` row naming conditional LEFT JOINs for this leaf.
   * While in the area: `JoinStep`'s class javadoc claims "the validator rejects one-to-many
     navigation on a single-value field"; no such validator exists, and this item makes the claim
     permanently false rather than aspirational, so delete or restate it.
     `ParticipantRef.JoinedTableBound`'s "checked by the validator" misattribution points at
     `TypeBuilder.resolveJoinedTableParticipant` instead.
     `TypeFetcherGenerator.buildTableInterfaceReprojection`'s javadoc claims four callers; it has
     two.
2. **The seam split.** Pure refactor: `projection` / `joinedStep`, `assembly` as the composition,
   the fields-local name constant, the `{@link}`s. No call site changes; `TypeFetcherGeneratorTest`
   and `PolymorphicProjectionFilterPinTest` stay green as-is.
3. **The root lift.** The classifier, `LauncherCommands` and `LauncherCommand` edits above; the
   renderer connection body; the corpus example plus the deleted enum row; the SQL baselines and
   execution page walks (Coverage).
4. **The child lift** (after R661 lands). The child deferral deletes; `Connection` joins the
   batched fork; the windowing-tail extraction and the discriminated binder; the scatter emission
   gate; the delivery-fact verification; the child fixture, baseline and page walks (Coverage).

## What needs no work, with the evidence

Checked, so the implementer does not re-derive it:

* **Connection type synthesis.** `ConnectionPromoter.promotionFor`'s directive arm gates only on
  `@asConnection` plus a list return and reads the element type name whatever its kind, so
  `[Content!]! @asConnection` already mints `ContentConnection` / `ContentEdge` with `node:
  Content`.
* **The entry point.** `TypeFetcherGenerator.buildQueryTableFetcher` reads
  `RootLauncherRenderer.valueTypeOf(row)`, whose `Connection` arm already yields the carrier ref;
  both root arms route through it.
* **Field registration.** `FetcherRegistrationsEmitter` binds `edges` / `nodes` / `pageInfo` /
  `totalCount` per connection *type*, and `FetcherEdgeCommands.rowOf` returns null for
  `QueryTableInterfaceField` exactly as it does for `QueryTableField`.
* **Ordering.** The base table's primary key gives `OrderBySpec.Fixed` by default, `@orderBy` on
  this coordinate already lowers, and `TypeFetcherGenerator` already emits the order-by helper for
  `QueryTableInterfaceField` into the same generated class the launcher lands in (it closed that
  gap when the launcher migrated), so `declareBothViews`'s unqualified helper call resolves.
  `validatePaginationRequiresOrdering` covers the leaf through `SqlGeneratingField`.
* **Type resolution.** The existing `TypeResolver` routes off `__discriminator__`, which the
  assembly projects unconditionally; no new resolver, no selection-set dependency.
* **Facets.** Already rejected, not silently ignored: `unsupportedFacetCarrierReason` requires the
  carrier's element be a `@table`-backed object type, keyed on SDL rather than classification, so
  it fires today and keeps firing after the lift. Optional nicety: its trailing "or move the
  connection to the root" reads oddly for a carrier already at the root.
* **Corpus exemptions.** `ExemptionRegistry.MEMBER_KNOWN_GAPS` is keyed per `OperationMember`, and
  the launcher obligation covers `LaunchSource` and `ResultShape` arms individually, both already
  reached. Nothing to remove.

## Coverage

* **Corpus.** One `ClassifiedCorpus` example crossing `joined-table-interface`'s root row with
  `catalog`'s `films`: `@asConnection` with `@classified(source: Query, operations: [OrderBy,
  Paginate, Select], target: Single, targetShape: Connection)` and `@commits(source:
  DiscriminatedTable, result: Connection)`, a pair no example carries today. Give it a `query` so
  it renders, which puts the drift guard on
  `docs/architecture/reference/code-generation-triggers.adoc`. Delete the subsumed
  `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED` enum row in the same commit.
  `docs/manual/_generated/supported-schema-shapes.adoc` is keyed per sealed leaf and already lists
  `QueryTableInterfaceField` unmarked, so the root does not change it; the child's batched leaf
  arrives via R661.
* **The lowering.** The capture-time subselect term is pinned where it is decided: an assertion
  that `Branch.SingleTable` carries the subselect term with the participant alias and the gate
  predicate, at whatever tier pins `discriminatedBranches`'s output today.
* **The fan-out pin (the invariant's replacement).** An execution-tier fixture with a genuinely
  fanning cross-table hop: a small discriminated interface over `film` whose participant carries
  `@reference` through `film_actor_film_id_fkey` (`film_actor.film_id` is non-unique, so every
  film multiplies by its cast size), or an equivalent dedicated fixture-table pair per the
  `paged_a` / `paged_b` precedent. Assert one entity per base row under the list shape and that the
  single fetch no longer throws. Only this tier can observe row semantics; the code-string ban
  means no other tier should try.
* **Exact SQL.** The re-frozen cross-table baseline (commit 1). New `RootLauncherSqlBaselineTest`
  cases modelled on the mechanical cross of
  `interfaceRoot_singleTable_projectsTheDiscriminatorRoutingAlias` with
  `connectionRootPageQuery_seekLimitChainWithCursorKeyInSelectList`: one page query over
  `allContent`, one `totalCount` twin asserting the second statement counts over `content` with the
  discriminator `IN` predicate, and one over `allParties` or `allSubjects` pinning that the gated
  detail LEFT JOIN composes with seek and limit. The class javadoc's shape enumeration extends. For
  the child, a `BatchedChildSqlBaselineTest` case beside
  `connectionBatchedChild_rowNumberPartitionedByIdx` pinning the discriminated windowed shape,
  which also pins that the extracted windowing fragment serves both binders.
* **The backstop test.** `discriminatedSource_connectionAndFannedPairsAreUnrepresentable` loses the
  "never paginates" assertion and keeps the single-tenant one; rename to match what it still pins.
* **Execution tier, root.** Page walks in `GraphQLQueryTest` beside the existing `allContent_*` /
  `allParties_*` / `allSubjects_*` cases. Seed volume is thin (`content` 4 rows, `jti_subject` 4,
  `party` 3): either seed more rows the way the multi-table connection seeded dedicated `paged_a` /
  `paged_b` tables, or accept `first: 1` / `first: 2` walks. Prefer seeding; a backward (`before`)
  page and a page boundary landing between rows of the same participant type are the two cases most
  likely to expose a cursor bug. The `party` fixture's detached base row (no detail row) should
  appear mid-walk, pinning that LEFT JOIN NULL-through survives pagination.
* **Execution tier, child.** A parent-to-interface connection coordinate (for example a `Film`
  field returning `[Content!]! @asConnection` over the reversed `content_film_id_fkey` hop; R661's
  execution coverage wants the same fixture shape at list cardinality, so coordinate rather than
  duplicate). Assert the page walk and the statement count (one child statement for all parents,
  via the `SQL_LOG` idiom).
* **Delivery-fact agreement.** The connection coordinate joins the corpus / marker fixtures so
  `DeliveryFactPinTest` gates the leaf-versus-relation answer for it (R661 does the same for the
  list coordinate).

## Retired vocabulary

* The root deferral message "@asConnection on a root field returning a single-table discriminated
  interface ... is not yet supported; return the list shape instead".
* The child deferral message, same text minus "root". Unpinned, so nothing asserts it; it still
  needs the sweep.
* The `LauncherCommand` backstop message "a discriminated-interface launcher never paginates" and
  its "the classifier defers @asConnection on the single-table-interface root" comment; this time
  the check itself goes, not just the wording.
* The `RootLauncherRenderer` throw "a discriminated-interface launcher never paginates; the command
  constructor rejects the pair before rendering".
* `LauncherCommands.interfaceRow`'s javadoc sentence "Never `ResultShape.Connection`".
* The enum constant name `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED`.
* `crossTableJoinChain`, `crossTableAliasDeclarations` (as a join-arm concept; the projection-side
  gate survives under whatever name the subselect emission takes) and
  `CrossTableField.aliasVarName()`.
* "Gated LEFT JOIN" / "conditional LEFT JOIN" as the description of cross-table participant fields,
  wherever it appears: model javadocs, fragment javadocs, the baseline test name
  `interfaceRoot_crossTableParticipantField_gatedLeftJoinArm`, docs rows.
* `JoinStep`'s javadoc paragraph claiming "the validator rejects one-to-many navigation on a
  single-value field".
* Prose asserting the `(DiscriminatedTable, Connection)` pair is unrepresentable, that no
  paginating emission exists over the discriminated re-projection, or that the discriminated child
  never paginates.
* `targetAtMostOnePerSource`: never landed; this file was its only habitat and this rewrite is the
  sweep.

## What shipped, and what is left

The root half is implemented; the child half is not, and cannot be until R661 lands. Four commits:

1. **The subselect conversion.** Cross-table participant fields lower at capture into
   `SelectTerm.ScalarSubselect` (fixed alias, discriminator gate), rendered through the fragment
   lifted out of `ProjectionUnitRenderer` into `PathFragments` and parameterized on the parent
   table local. `crossTableJoinChain` and `CrossTableField.aliasVarName()` are gone. Coverage: the
   `fan_base` / `fan_detail` fixture pair (a participant reference landing on the many side of its
   FK) with execution-tier pins on both the list and single shapes, plus the re-frozen cross-table
   SQL baseline.
2. **The seam split**, exactly as specified, plus the lowering pin at the capture tier.
3. **The root lift.** Classifier, producer, command backstop, renderer, the
   `paginated-joined-table-interface` corpus example (rendered into the page) replacing the deleted
   enum row, three SQL baselines and four execution page walks.

Two things the plan did not anticipate, both settled in commit 3:

* **The type-conditioned selection gates were depth-blind.** `env.getSelectionSet().contains(
  "<Type>.<field>")` matches only at the top of the selection, so under a connection (where the
  coordinate sits below `edges/node`) a participant's detail column and its cross-table field were
  silently dropped from the page. The gate now offers both the bare pattern and its `**/` form.
  This was latent for every non-root coordinate, not only the connection.
* **Backward pagination needs author-declared arguments.** `@asConnection` synthesises `first`
  carrying the default page size, and the helper rejects a request specifying both ends, so a
  `last:` page over an `@asConnection` shorthand coordinate requires `first: null` unless the
  author declares the argument themselves. Not a defect of this item; recorded because the
  execution fixture had to work around it.

The child half (implementation group 4) is untouched and stays specified above. Nothing in the
root work forecloses it: the seam both paginating callers were designed to share is in place, and
`BatchedRowsFragments.connectionTail` is still the extraction the child arm needs.

## Review feedback (In Review -> Ready, first cycle)

Independent review of the root half. The build is green (full reactor under `-Plocal-db`), the
delivery matches the spec's mechanism at every load-bearing point (capture-time lowering with the
alias invariant on `CrossTableTerm`, one shared `PathFragments.scalarInnerSelect`, the seam split
with untouched call sites, the shared `connectionShape` helper, the backstop halved with its
rationale, the corpus example replacing the enum row), and the coverage design is strong: the
lowering pin sits at the tier that decides it, the totalCount baseline has a twin, and the fan-out
fixture pins the invariant's replacement where only the execution tier can see it. The root half
is in good shape; the item stays open because its contract is both coordinates and the child half
waits on R661 (still Spec). Fix the following in the next pass, alongside or before the child
work:

1. **Code-string body assertions were re-authored rather than retired.** Four
   `TypeFetcherGeneratorTest` tests (`..._selectionGateMatchesAtAnyDepth`'s gate assertion,
   `queryTableInterfaceField_crossTableField_emitsGatedCorrelatedSubselect`, the projection-alias
   sibling, and `tableInterfaceField_crossTableField_emitsGatedSubselectAtChildSite`) assert
   `method(spec, ...).code().toString().contains(...)` on generated method bodies.
   `docs/architecture/how-to/testing.adoc` bans code-string body matching in exactly this file's
   family, and the delivery's own SQL baselines and execution walks already pin every fact these
   strings pin. Updating the legacy strings kept the build green but re-authored the violation;
   retire these assertions (or reduce them to sanctioned signature/shape assertions) in favor of
   the baselines.
2. **The declared retirement of "gated/conditional LEFT JOIN" as the cross-table description is
   incomplete.** Surviving habitats, all describing the cross-table mechanism (the joined-detail
   join legitimately keeps the phrase): `TypeFetcherGenerator` (the
   `ParticipantColumnReferenceField` arm comment, near line 670), `TypeBuilder`
   (`buildParticipantList`'s `interfaceTable` param javadoc, near line 825), `FieldBuilder` (the
   `lookupParticipantCrossTableField` comment, near line 7607), `TypeFetcherGeneratorTest`
   (section comment near line 1218), `GraphitronSchemaBuilderTest` (section comment near line
   458), `ClassifiedCorpus` (the `participant-reference` example comment),
   `QualifiedParticipantCrossTableReferencePipelineTest` (class javadoc), and
   `DmlTableInterfaceReturnExecutionTest` (the rating comment, near line 119). Historical
   `changelog.md` entries stay as they are.
3. **Collapse the shipped groups to "shipped at <sha>" one-liners** per the Done-gate
   precondition: group 1 is `348f914`, group 2 is `0c55288` plus the lowering pin at `cd46fe9`,
   group 3 is `3201386`.

Improvements, not gate-blocking, take them if cheap while in the area:

* `fanItems_fanningCrossTableHop_oneEntityPerBaseRow` pins `note == "alpha-note-1"`, but the
  capped subselect carries no ORDER BY, so which detail row it returns is not SQL-determined; the
  pin rides Postgres heap order. Loosen to membership in the seeded set (or non-null) unless the
  subselect gains deterministic pick semantics.
* The backward-page walk uses `last: 2` with no `before` cursor; the Coverage section named the
  before-cursor page one of the two most cursor-bug-prone cases. Add a `before` walk when the
  child work touches the same fixtures.

## Out of scope

* **Batched delivery for the child arm.** R661 owns it; this item consumes it and adds only the
  connection tail.
* **The full cross-table reclassification** (Considered and rejected, last bullet).
* **A schema-quality lint on fan-out-able scalar `@reference` hops.** After the conversion the
  question belongs to the plain shape and this arm equally; if wanted, it is one lint over
  `ScalarSubselect` producers, not a discriminated-interface concern.
* **Facets on these coordinates.** Already rejected with a message naming it a follow-up; lifting
  that is a separate item if a consumer asks.
* **Multi-table interface and union ordering.** R382's axis, unrelated.

## Provenance

Both R405 (root `@service` return) and R406 (DML return) recorded `@asConnection` as out of scope
for this interface family; this item picks up the read half at both coordinates. Distinct axis from
R382. The child half arrived from the discarded `child-connection-over-discriminated-interface`
(R651); the statement-composition framing that merged the two came out of this item's first Spec
review, which reopened a signed-off plan (the cardinality-invariant route). This body is the
respec, drafted with the architecture consult; the superseded analysis survives in git history at
the reopen commit.


