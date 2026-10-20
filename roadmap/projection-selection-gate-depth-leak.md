---
id: R708
title: "The $project selection gate fires on names selected at any depth below its unit"
status: In Progress
bucket: correctness
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-18
last-updated: 2026-10-20
---

# The $project selection gate fires on names selected at any depth below its unit

## Goal

A GraphQL response key names a field only within its own selection set, and Graphitron should
read it that way. Today a generated query treats a key selected anywhere below a field as if it
were selected on the field itself. When this item lands, three things a consumer sees today
stop happening:

* **Valid documents are rejected.** A key reused for different fields under different parents
  fails with "Result key 'name' selects two different fields ...":
  `{ customers(active: true) { k: firstName address { k: district } } }`. Graphitron 9 answered
  every such document, and the sis production log holds dozens of them.
* **Wrong data is returned without an error.** One key selected under two sibling nesting
  fields serves one sibling's value for both: `minimumsvekting { vektingstype { kode } }`
  next to `maksimumsvekting { vektingstype { kode } }` returns the maximum's null type for the
  minimum, where the database says `SP`.
* **Unselected columns are projected.** `{ customers(active: true) { location { address
  { district } } } }` makes the SQL compute `OccupantLocation.district`, because the
  `district` inside `address` leaks up to `location`.

A *nesting field* is a field whose type has no table of its own (a plain object type such as
`OccupantLocation`); its children are read from the parent's SQL row. The rule this item makes
true: **each generated projection sees exactly the fields selected on its own type at its own
level, and every value it puts on a shared row is named by its full path from that row's
owner.** Nothing changes in schemas, directives, or results that are correct today, except the
names Graphitron gives its own generated SQL columns.

The same holds across type conditions. `{ allContent { ... on FilmContent { k: length } ... on
ShortContent { k: description } } }` resolves, as does the same reuse under `Query.nodes` and
`_entities`. The fold that merges single-table discriminated participants into one statement is
included.

Two shapes stay rejected, deliberately. Both are one key for two *different* fields whose values
would have to share one SQL alias in one row, and the existing message names the merge:

* Under `edges { node { ... } }` and `nodes { ... }` of the *same* connection (`edges { node
  { x: title } } nodes { x: releaseYear }`). Both paths are served from one SQL row by one node
  reader, so the shape is unrepresentable without treating the connection wrapper as a path
  segment.
* Under two participants' fragments in a single-table discriminated fold, when each of the two
  fields writes a *shared* alias, meaning an interface-declared aliased field (a reference, a
  computed field) or a nesting field. Every participant's terms land in one select
  list, and a shared alias is not qualified by participant, so the two would collapse to one
  term. Qualifying those namespaces per participant is the deferral `siblingProjectionConflicts`
  already documents for the build-time half of the same collision.

Every production shape the sis scans reported reuses a key under different parents, not across
these two merges. A consumer report of either shape gets its own item.

## Evidence

Every table-backed type gets a generated `<Type>.$project(grouped, table, env)` method, called
a *projection unit*. It builds the SELECT list for one type. `grouped` maps each response key
to its `SelectedField` occurrences, and the body loops `grouped.entrySet()`, calls
`SelectionOccurrences.canonical`, and switches on `sf.getName()`, one arm per field the type
can project. A key with no matching arm projects nothing.

`grouped` is built with graphql-java's `DataFetchingFieldSelectionSet.getFieldsGroupedByResultKey()`.
Checked against the 25.0 source and a probe, that method is
`getFields().stream().collect(groupingBy(SelectedField::getResultKey))`, and `getFields()`
covers **every depth** of the subtree. For `{ film { name: title language { name } } }` it
gives `name=[title, language/name]`. It also returns an unordered `HashMap`.
`getImmediateFields()` covers one level and keeps query order. A nested
`SelectedField.getSelectionSet()` is rooted at that field and again covers every depth.

Three consumer-visible forms follow from this. The second one has a second cause as well.

* **Over-projection** (the original finding, from R323's `OccupantLocation` fixture work). With
  a nesting-type leaf named `district`, `{ customers(active: true) { location { address
  { district } } } }` emitted the correlated subselect for `OccupantLocation.district` aliased
  `__rk_district` next to the selected `__rk_address` multiset. R323 renamed its leaves
  `occupantDistrict` / `occupantAddressId` to avoid the collision. Two same-named arms on
  different types can also fire from one selection: `Customer.address` and
  `OccupantLocation.address` happen to render identical SQL under one alias today, so the
  `LinkedHashSet` dedupes them. Arms whose SQL differed would put two terms under one alias.
* **Rejection** (sis v9 against v10 differential test on Oracle, 2026-10-19). `canonical`
  throws when a bucket's occurrences name different fields. It was written for the Relay case
  (`edges { node { x } }` against `nodes { x }`), but with depth flattening, keys under
  different parents land in one bucket too. Minimal repro: `{ emnerV2(filter:
  {eierOrganisasjonskode: "1234", emnekoder: ["INF1000"]}, first: 1) { nodes { personroller
  { fsRolle { name: navnAlleSprak { nb } } personProfil { name: navn { fornavn } } } } } }`.
  No alias is needed: an unaliased `kode` on an outer type plus `kode: navnAlleSprak` on a
  child fails as well. On a root typed `Connection!` the whole `data` becomes null. The
  consumer's scans count about 38 distinct documents in the emne area and 26 operation and
  root-field pairs in the student area (among them `StudenterGittFeideBrukere`,
  `n: nodes` against `n: navn`, hit in 59 of 60 sampled windows), plus
  `GetProgramDescriptionQuery` and `GetStudyProgramForSemester`.
  `requireConsistentArguments` reads the same buckets, so its argument check can also fire on
  occurrences from a depth it was never meant to compare.
* **Silent cross-wiring** (sis studieprogram area, same day). `{
  studieoppbygningsdelerGittStudieoppbygningsdelkoder(...) { kode vektingskrav {
  minimumsvekting { verdi vektingstype { kode } } maksimumsvekting { verdi vektingstype
  { kode } } } } }` returns `minimumsvekting { verdi: 120, vektingstype: null }`, where v9 and
  the database say `{ kode: "SP" }`. It gives the right answer if only one `vektingstype` is
  selected, if one is aliased, or if the two values happen to agree. A high-volume production
  operation hits this (2 to 6 wrong rows per call on replay). **This form is not caused by
  depth.** A nesting field's fetcher passes the parent record through
  (`FetcherEmitter.nestingBody`), so both siblings' children are written into one SQL row. A
  result-key-aliased child (a reference multiset, a scalar `@reference` subselect, a
  `@pivot`, a computed field) is written as `__rk_<resultKey>` and read back as
  `__rk_` + `env.getField().getResultKey()`. Two selection sets that share one row can
  therefore write the same alias with different SQL, and both readers read whichever term
  wins. The same thing happens with one nesting field selected twice under two aliases whose
  sub-selections differ.

## Implementation

The work has two halves. The first scopes each unit's selection to its own level. The second
makes the `__rk_` alias unique across the selection sets that share a row. Both are needed for
the goal. Each half could land as its own commit, and the execution tests below hold at each
one.

### Scoping: one entry point for "this unit's own fields"

All grouping moves into the generated `SelectionOccurrences` class
(`SelectionOccurrencesClassGenerator`). Emitted code never calls
`getFieldsGroupedByResultKey()` again. The class gets three adapters. Each returns an
insertion-ordered `Map<String, List<SelectedField>>` built from `getImmediateFields()` only.

* `ownFields(DataFetchingFieldSelectionSet sel)`: the field's immediate children grouped by
  result key. This replaces `getFieldsGroupedByResultKey()` in
  `ProjectionCall.fromSelectionSet` / `fromEnvSelection`, which covers every launcher whose
  unit fields are the fetched field's direct children (inventory below).
* `connectionNodes(DataFetchingFieldSelectionSet connectionSel)`: the node level of a Relay
  connection. It collects every immediate `nodes` occurrence and every `node` occurrence under
  an immediate `edges`, then merges their immediate children the way `mergeByResultKey`
  does. It matches on `SelectedField.getName()`. These are the Relay field names
  `ConnectionPromoter` synthesises, and an alias on `edges` or `nodes` does not change them.
  It walks immediate fields and does not use globs, because graphql-java globs are file-system
  path matchers over field names. This adapter is the deliberate replacement for the one
  merge depth flattening did that callers relied on.
* `mergeByResultKey(List<SelectedField>)` keeps its name and its union semantics. It now
  merges each occurrence's *immediate* children instead of its whole subtree.

**The scope is derived, not chosen per call site.** Today a connection launcher calls the same
`fromEnvSelection` as a list launcher, and nothing but the command says which it is. A call
site left on `ownFields` under a connection would see only `edges` / `nodes` / `pageInfo`,
match no arm, and silently project the `__row_present__` sentinel. So `ProjectionCall` gains
one launcher entry that takes the command's `ResultShape` and picks `connectionNodes` for
`ResultShape.Connection` and `ownFields` otherwise; the launcher sites call that entry. The
legacy emitters that hold no `ResultShape` (`MultiTablePolymorphicEmitter`,
`TypeFetcherGenerator.buildTableInterfaceReprojection`) pass the connection fact they already
branch on through the same entry. The sites whose unit fields are not the fetched field's
direct children, all of which take the connection arm:

* `RootLauncherRenderer.connectionBody` (the root anchor and routine connections);
* `BatchedRowsFragments.connectionTail`;
* the root and batched connection entry points of
  `MultiTablePolymorphicEmitter.buildPerTypenameSelect`;
* the connection paths into `DiscriminatedTableFragments.fieldsList`
  (`RootLauncherRenderer.discriminatedConnectionBody` and
  `BatchedRowsFragments.discriminatedConnectionTail`).

These sites do not change and use `ownFields`: `RootLauncherRenderer` `selectChain`,
`fannedBody`, `lookupBody` and `routineBody`; `BatchedRowsFragments` `body` and
`pivotBody`; `ReentryRowsFragments.projectedBody`; `ServiceRowsFragments.liftBody`;
`RoutineWriteEmitter.chainReread`; `SelectMethodBody.body`; and the non-connection
`DiscriminatedTableFragments.fieldsList` hosts, including the `buildTableInterfaceReprojection`
methods on `TypeFetcherGenerator` and `MultiTablePolymorphicEmitter`. This inventory is a
starting point, not the guarantee; the guarantee is the lint enforcer under Tests, which fails
on any emitted `getFieldsGroupedByResultKey(` left behind. The renderer arms in
`ProjectionUnitRenderer` (Splice, Multiset, LookupMultiset, PivotMultiset) all reach their
callee through `mergeByResultKey` and change only through it. The pivot body's
`grouped.values()` loop needs no change once its input is scoped.

**Type restriction becomes a map filter.** `PolymorphicSelectionSet.restrictTo` is a
delegating proxy over `DataFetchingFieldSelectionSet`. Its only real override is a filter over
the all-depth map, keeping entries whose `getObjectTypeNames()` contain the concrete type. With
`connectionNodes` in place, the filter has to run *after* the descent to the node level:
filtering the connection's own `edges` / `nodes` fields by participant type would drop them. So
the restriction becomes a static `Map -> Map` filter over the scoped map, in a two-argument
form and a selective form. It lives on `SelectionOccurrences`, the class that owns
occurrence-list semantics. The predicate still reads `SelectedField.getName()`, never the
client-minted key. The proxy class and its generator are deleted.

**The selective form keeps whole only what a shared alias needs.** The fold restricts
selectively so that every arm writing one shared alias sees one occurrence set ("a shared alias
requires a shared occurrence set", `LaunchSource.DiscriminatedTable.SelectionRestriction`).
Today it gets there by inversion: it filters only the participant-qualified names
(`perTypeFieldNames`) and passes everything else through whole. That includes plain columns,
which write no alias at all, because they are read by column identity. So `... on FilmContent
{ k: length } ... on ShortContent { k: description }` reaches both arms as one mixed bucket, and
`canonical` rejects it. The set is turned the right way round. `LauncherCommands.selectionRestriction`
collects `sharedAliasFieldNames` instead: the names of every `ResultKeyAliasedField` whose
`AliasOwner` is `QualifiedBy` the interface, plus every `NestingField` name on any participant,
because a nesting field's spliced leaves write unqualified aliases into the fold's one row. Per
bucket and per participant, the filter works in two steps:

1. It keeps the occurrences whose `getObjectTypeNames()` contain the participant.
2. If any kept occurrence names a shared-alias field, it also keeps every occurrence in the bucket
   that names a shared-alias field, whatever its type.

Step 2 is today's union, scoped to the occurrences that actually share the alias. Two fragments
selecting one interface field with different sub-selections still merge into one term that
serves both. Two *different* shared-alias fields under one key meet in one arm, and `canonical`
rejects them loudly instead of letting the fold's set collapse them silently; that is the Goal's
second carve-out. A participant-local field and a shared-alias field under one key do not meet,
because they write different aliases. The test reads every occurrence, not the first, so bucket
order cannot change the verdict.

This is also what keeps the census change below sound. Two participants' nesting fields under one
client key always reach `canonical` together (step 2), so a client alias on a nesting field cannot make
two participants compose one spliced alias over divergent SQL. The census only has to judge
default keys. A later item that narrows this exemption must revisit the census in the same
change. Its javadoc said a map argument "would not survive
the nested-projection recursion". That is not true of the `$project` contract, which only ever
takes a map. Callers: `MultiTablePolymorphicEmitter.buildPerTypenameSelect`,
`DiscriminatedTableFragments.fieldsList`, `LaunchSource.DiscriminatedTable.SelectionRestriction`'s
helper reference.

**Federation and `Query.node(s)` dispatch is restricted too.** `SelectMethodBody.body`
projects `<Type>` from the env of `_entities`, `Query.node` or `Query.nodes`, whose immediate
fields include every concrete type's fragment fields. With no restriction, `... on Customer
{ k: firstName } ... on Film { k: title }` puts both into one bucket and `canonical` rejects
a valid document. This is the same defect, scoped by type instead of depth. The site applies
the two-argument restriction for its concrete type. With the selective form above, the rule
holds for every launcher: a unit's map contains only its own type's fields at its own level,
apart from the shared-alias buckets the fold keeps whole on purpose.

**The discriminated fold's other two depth-unbounded gates.** `DiscriminatedTableFragments`
reads the selection twice outside `$project`. Both reads switch to the scoped map. The fold
computes the scoped map once into a local (`ownFields` or `connectionNodes`, depending on the
host), and every read in the fold uses it:

* the `InheritedRef` base-slice loop (`for (rkEntry : env.getSelectionSet().getFieldsGroupedByResultKey()...)`).
  Its bucket test also moves from the first occurrence's name to *any* occurrence's name. Once
  the arms stop rejecting mixed buckets, a bucket can carry another participant's field first,
  and a first-occurrence test would silently drop the base term;
* `typeConditionedGate`, today `containsAnyOf("<Type>.<f>", "**/<Type>.<f>")`. The `**/`
  exists to reach `edges/node/<Type>.<f>`, and it also matches the same type-conditioned field
  anywhere deeper. It becomes a membership test on the scoped map: some occurrence has name
  `<f>` and object type names containing `<Type>`.

The same fold also routes its `InheritedRef` alias through `ReservedAliases.resultKeyPrefix`
instead of spelling `RESULT_KEY_PREFIX` raw, so every `__rk_` writer goes through the one
composition point.

**Guards keep their checks and narrow their premise.** After scoping, a bucket holds more than
one occurrence only when several parent occurrences were merged: the connection's
`edges.node` and `nodes` paths, or a child bucket of such a merge. `canonical` and
`requireConsistentArguments` keep their checks and messages, and their javadoc now describes
the buckets they actually receive. The Goal's one carved-out rejection is `canonical` firing on
that merge, pinned by
`ConnectionSharedResultKeyProjectionTest.nameDivergence_onNonArgConsumingArm_failsLoudAsFieldError`.

### Alias namespace: path-qualify values written onto a shared row

A *nested unit* (`ProjectionCommand.NestedUnit`) is the projection unit of a nesting type
under one anchor. It is reached only by splicing (`CallWrap.Splice`) into its anchor's SELECT
list, so it writes into the anchor's row. Every selection set spliced into one row must get
its own alias space.

**Decided once, at capture.** Whether a result-key alias is path-qualified is a fact about the
field, and it is stamped next to the one that already decides qualification. `AliasOwner` says
*who* owns the namespace and stays as it is; a second, independent axis on
`ResultKeyAliasedField` says *where the row starts*. Working name `AliasScope`, with two arms:
`RowOwner` (the field's declaring type owns the row it is written into) and `Spliced` (the
declaring type is a nesting type, so the field is written into an ancestor's row). It is minted
at classification off the declaring type, the same place `AliasOwner` is minted, and copied by
every downstream carrier: `Contribution` carries it beside `aliasOwner()`, and the read arms in
`FetcherEmitter` read it off the field. Neither side re-derives it. A separate axis rather than
new `AliasOwner` arms, because the two facts vary independently (a participant can own a
nesting field), and folding them would multiply arms into Shared/Qualified by owner/spliced.
`NestedUnit`'s constructor asserts that every result-key-aliased contribution it holds is
`Spliced` and `AnchorUnit`'s that none is, so the stamp and the unit kind cannot disagree.

* **Write side.** A nested unit's `$project` takes one more parameter, `rkPath`: the result
  keys from the row owner down to this nesting occurrence, each followed by the path
  delimiter. An anchor's Splice arm passes `entry.getKey() + <delim>`, and a nested unit's
  Splice arm passes `rkPath + entry.getKey() + <delim>`. Every `Spliced` term the nested unit
  writes uses `<prefix> + rkPath + entry.getKey()` where it now uses `<prefix> +
  entry.getKey()`. Those terms are `SelectTerm.Column` with `BY_RESULT_KEY`, `HelperCall`,
  unnamed `ScalarSubselect`, and the Multiset, LookupMultiset and PivotMultiset envelopes.
  Multiset arms open a new row, so the callee they call stays an anchor unit and takes no
  path. Anchor and pivot unit signatures do not change. Neither do `BY_COLUMN_IDENTITY`
  columns, because every selection set that reads a plain column reads the same value.
* **Read side.** A `Spliced` reader (the `FetcherEmitter` arms for `TableField` single and
  list, `PivotField`, `ComputedField`, and direct-compaction `ColumnBackedReferenceField`)
  composes the same path at runtime. Starting from its own step's parent, it walks
  `env.getExecutionStepInfo()` upward, and for each ancestor step whose *field coordinate*
  (`getObjectType().getName()` + `.` + the field name) is a `NestingField`, prepends that
  step's result key; it stops at the first ancestor step that is not. The rule is keyed on the
  field, because nesting is a field-level classification: the immediate nesting step's
  declaring type is the anchor, so a rule keyed on declaring types would drop the first
  segment. `RowOwner` readers are unchanged and do no walk.
* **The walk's preconditions are validator facts, linked from the walk's javadoc.** A nesting
  field is never list-wrapped (the list-wrapper rejection on `NestingField` in
  `GraphitronSchemaValidator`), so no list-element step sits mid-chain. Nesting under a
  `JOOQ_RECORD_CARRIER` source is rejected (`ReachableSourceShape.REJECTED`), and in the
  `ReachableSourceShape.DISPATCHED` mix the class-backed arm never takes the `Record` read.
  Federation `_entities`, `Query.node(s)`, interface parents and the connection `node` /
  `nodes` steps stop the walk on their own, since none of them is a nesting coordinate.
* **Where the coordinate set lives.** A new per-schema generated runtime class,
  `NestingCoordinates` in `<outputPackage>.util` (emitted by a
  `NestingCoordinatesClassGenerator`, name minted beside the other util class names), holds the
  set and the walk. It cannot live on `SelectionOccurrences`, whose statics are identical for
  every schema by contract. The set comes from the existing nesting reach fold, not from a
  fourth scan of the model. `NestingReach.compute` already visits every `NestingField`, but it
  keeps only one representative per nesting type and drops the parent coordinate. So the fold
  is extended to also collect `(parent type, field name)` for every nesting edge it visits: the
  table-backed type for a root-level edge, and the enclosing nesting type for an inner one. The
  collected set excludes the pivot projection wirings the fold also carries, or the walk would
  cross a pivot row boundary.
* **There is no static alternative.** Fetchers are registered per nesting *type*
  (first-parent-wins, see R323's changelog entry) while a nested unit is per *(anchor, type)*,
  so one reader serves chains of different depth; only the runtime path tells them apart.
* **Delimiter and composition** belong to `ReservedAliases`, next to `resultKeyPrefix`, so the
  write and read sides spell one decision. The composed alias is `__rk_` + optional `<owner>$`
  + path segments + key. The delimiter must be outside the GraphQL name grammar, different
  from the owner delimiter `$`, and not `.`: the `Record.get(String, Class)` reads in
  `FetcherEmitter` take a plain field name, and a dot risks being read as a qualifier. The
  recommendation is `/`, the separator graphql-java uses for selection paths. It is injective
  for the same reason the owner qualifier is: no client-minted key can spell it.
* **The discriminated-participant census follows the alias.** A single-table discriminated
  fold puts every participant's splices into one select list, so two participants can still
  write one composed alias. That collision is already a build-time rejection:
  `ProjectionCommands.siblingProjectionConflicts` claims every result-key alias a participant's
  spliced nesting subtree contributes (`collectSplicedClaims`), and `GraphitronSchemaValidator`
  reports the divergent ones. Today it claims by leaf field name alone. It now claims by the
  splice chain of nesting field names plus the leaf name, which is the composed alias at the
  default (unaliased) result keys, so it stays exactly as strict as what the fold still shares
  and stops rejecting participants whose leaves no longer collide.

### Fixture prose

The `OccupantLocation` comment in `graphitron-sakila-example/.../schema.graphqls` says that
naming around the gate's recursion "is a defect in the gate ... filed on its own". That
sentence goes. The type gains a `district` leaf (same column and path as `occupantDistrict`),
which is exactly the collision R323 avoided, now pinned rather than dodged.

## Tests

Each goal bullet gets its own execution-tier test in `graphitron-sakila-example`, in a new
class next to `ConnectionSharedResultKeyProjectionTest` (or extending it, at the
implementer's judgment). None of them asserts on SQL text.

* **No over-projection.** `{ customers(active: true) { location { address { district } } } }`
  with the new `OccupantLocation.district` leaf returns correct data, and the statement's
  *result shape* carries no term for `location`'s own `district`: an `ExecuteListener` (the
  hook `ProjectionShapeExecutionTest` already installs to count statements) records the field
  names of each returned result (`ctx.result().fields()`), and the test asserts on those. That
  observes what the database was asked to return, not how the generator spelled it. A second
  case selects both
  `location { district }` and `location { address { district } }` and asserts that each
  resolves its own value.
* **No rejection.** All of these resolve with no errors and with the values v9 semantics give:
  * plain list: `{ customers(active: true) { k: firstName address { k: district } } }`;
  * connection, same shape one level under `nodes`, on `filmsFaceted` or `stores`;
  * a list child: a key reused between a parent field and a field of a list-valued child;
  * the unaliased form: an outer field name reused as the alias of a child's different field;
  * type-scoped: `{ nodes(ids: [<customer id>, <film id>]) { ... on Customer { k: firstName }
    ... on Film { k: title } } }`.
* **No cross-wiring.** A new fixture: two nesting fields on `Film` whose (distinct) nesting
  types each declare `language: Language`, one over `film_language_id_fkey` and one over
  `film_original_language_id_fkey`. Every seeded film has `original_language_id` NULL, so the
  correct answer is English on one side and null on the other, the same shape as the sis
  repro. Pin `spoken { language { name } } original { language { name } }`, and also one
  nesting field selected twice under two aliases with divergent sub-selections
  (`a: x { language { name } } b: x { language { languageId } }`).
* **Shared nesting type, both parents.** The cross-wiring shape through `OccupantLocation`
  under both `Customer` and `Store` (one leaf, two anchors), to pin the agreement between the
  write-side `rkPath` and the read-side walk.
* **Existing pins hold.** `ConnectionSharedResultKeyProjectionTest` stays green unchanged: the
  four divergence directions, deep nesting, the polymorphic connection, both guards, and the
  non-connection control. That shows `connectionNodes` reproduces the one merge the flattening
  provided.

* **A participant splice.** If the sakila schema has a single-table discriminated participant
  with a nesting field (or one is cheap to add), a selection under the interface that reaches a
  nesting leaf through two participants resolves each participant's own value. The census
  change gets a `GraphitronSchemaValidator` test: two participants whose nesting fields have
  different names now validate clean, and two with the same nesting field name and divergent
  leaves are still rejected.
* **Type-scoped reuse in the fold.** On `Content`,
  `{ allContent { ... on FilmContent { k: length } ... on ShortContent { k: description } } }`
  resolves each row's own value; today it is rejected. A connection variant on
  `allContentConnection` covers the fold's `connectionNodes` host. The second carve-out is pinned
  in the same class: two different shared-alias fields under one key across two participants'
  fragments still fail with `canonical`'s message. `FanItem` declares one interface-level
  aliased field today (`details`), so the pin either adds a second one there (for example a
  scalar `@reference` off `fan_base`) or uses two participants' nesting fields if the
  participant-splice fixture above lands, at the implementer's judgment.

Enforcers: `GeneratedSourcesLintTest` gains a rule that fails on any emitted
`getFieldsGroupedByResultKey(` and on any `"**/` glob literal in emitted sources, which is what
makes the retired vocabulary below stay retired. `NestedUnit` / `AnchorUnit` constructor
assertions on `AliasScope` get unit tests. Adapter behaviour is pinned at the execution tier
above; `SelectionOccurrencesClassEmitTest` stays structural (the methods exist with the right
signatures), and a matching emit test covers `NestingCoordinates`.
`OccurrenceArgumentGuardPipelineTest` stays green. Unit and pipeline tests
that assert on emitted `__rk_` aliases or on `PolymorphicSelectionSet` get updated for the new
shapes (`ProjectionUnitRendererTest`, `RootLauncherRendererTest`,
`PolymorphicSelectionSetClassEmitTest`, `ParticipantAliasOwnerPipelineTest`, and any captured
SQL in `GraphQLQueryTest`). These are expected changes in generated names. They are not
behaviour changes and are called out in the In Review notes.

## Retired vocabulary

* `PolymorphicSelectionSet` (the generated class) and `PolymorphicSelectionSetClassGenerator`.
* `perTypeFieldNames` on `LaunchSource.DiscriminatedTable.SelectionRestriction` (and the emitted
  local that carries it): replaced by `sharedAliasFieldNames`, the inverted set.
* The emitted `getFieldsGroupedByResultKey()` call: after this item, no emitted source calls
  it.
* The `**/<Type>.<field>` glob in `typeConditionedGate`.
* Prose describing `getFieldsGroupedByResultKey()` as "flattening the subtree" *as the
  mechanism the connection merge relies on*: `SelectionOccurrencesClassGenerator`'s class
  javadoc, `ProjectionCall`'s class javadoc, and `ConnectionSharedResultKeyProjectionTest`'s
  class javadoc.

## Other solutions we've considered

* **Leave the discriminated fold's selective restriction as it is, and carve the fold out
  wholesale.** This was the smallest change. But it would keep rejecting the commonest
  type-scoped reuse, different plain columns under one key in two fragments, whose terms never
  share an alias. Narrowing the exemption to shared-alias names costs one inverted set and a
  two-step filter.
* **Qualify spliced and interface-owned aliases per participant, so the fold needs no
  exemption.** This would close the second carve-out too. But the nesting type sits under
  several anchors with one registered reader, so its alias would need a participant segment
  composed at runtime, and interface-owned aliases would stop collapsing to one term. That is
  the redesign `siblingProjectionConflicts` already defers; it gets its own item if a consumer
  hits the shape.
* **Restrict the bucket to immediate children, with no connection adapter.** This is the
  Backlog body's first sketch. It breaks every connection, because the node fields sit under
  `edges/node` and `nodes` and are found today only through flattening.
* **Glob patterns (`edges/node/*`, `nodes/*`) for the connection level.** These work, but they
  match field names through a file-system path matcher and return an unordered map. Walking
  `getImmediateFields()` does the same with no new matching semantics.
* **Decide path qualification by unit kind on the write side and by declaring type on the read
  side.** Each side can compute the answer from what it holds, but that re-derives one
  predicate in two places, which is the drift `AliasOwner` exists to prevent.
* **Key the whole map by path instead of by result key.** This would change the `$project`
  contract and every arm, only to encode what the immediate-children grouping already gives.
* **Carry the inline path in graphql-java's `localContext`.** The slot is owned by the tenant
  hand-down (`TenantHandDown`, guarded by the generator test tier's ownership guard), so
  sharing it means a composite context type threaded through every fetcher. The step-info
  walk needs no new runtime carrier.
* **Have nesting fetchers return a wrapper (record plus path) instead of passing the record
  through.** That changes `env.getSource()` for every reader under a nesting type, and
  breaks the dual-shape dispatch and presence checks that read the raw `Record`.
* **Fix the silent form in a separate item.** It was considered because its cause is
  different. It stays here because the goal names it, both halves touch the same projection
  renderer arms, and the sis replay counts it as the most damaging of the three forms.

Known exposure, not addressed here: PostgreSQL truncates identifiers at 63 bytes and does not
error. Path qualification lengthens the aliases nesting types write. That widens an existing
exposure (`ReservedAliases` already notes the 63-byte budget for owner-qualified aliases), but
does not create a new kind of risk. Neighbor, not a dependency: R752
(`JoinedTableReprojection` first-wins drop) is another same-alias collision, in the
discriminated fold rather than across nesting siblings.

## Implementation notes

Landed in two commits, one per half, each with its execution tests green:
`R708: scope every projection to its own level of the selection` and
`R708: path-qualify the result-key aliases spliced into a shared row`.

Where the code departs from the plan, and why:

* **The scaffold rides the command.** `render` may not see `plan` and every generated unit name
  enters through `GeneratedUnits` (`PackageImportDirectionTest`), so the launcher renderers
  cannot mint `SelectionOccurrences` themselves. `LauncherCommand` gains a
  `selectionOccurrences` unit ref, and the one launcher entry is
  `ProjectionCall.fromLauncher(unit, table, row)`, which reads the ref and derives
  `ProjectionCall.SelectionScope` from `row.result()`. The legacy emitters hold the output
  package and pass `SelectionScope.of(<connection fact>)` (`MultiTablePolymorphicEmitter` reads
  `includeKey`, which is exactly its connection arm). `RoutineWriteEmitter.render` takes the
  scaffold's class from its caller like its other runtime units. The discriminated fold reads
  the scaffold off `SelectionRestriction.helper`, which now names `SelectionOccurrences`.
* **`sharedAliasFieldNames` is "not qualified by the participant", not "qualified by the
  interface".** Same set on every schema the stamp produces today, and safe if a shared-owner
  aliased field ever reaches a participant.
* **The restriction always runs in the fold**, also when the shared set is empty: that is what
  makes the type-scoped reuse of plain columns resolve.
* **`AliasScope` is minted off `TypeBuilder.isNestingEdgeTarget(declaring type)`**, the same
  classification that builds the `NestingField`. The `NestedUnit` / `AnchorUnit` constructor
  checks apply to contributions that write a result-key alias
  (`Contribution.writesResultKeyAlias()`); a plain column or a splice carries no path.
* **`NestingCoordinates` is a frozen scaffold kind** in `PlanCompileGraph`: its coordinate set is
  a private field behind one fixed public method, so its ABI is the same for every schema.
* **Test queries changed shape, not intent.** GraphQL field-merging validation rejects one key
  for two fields of different types or nullability, so the plan's `k: length` against
  `k: description` is invalid as written. The fold tests reuse `k` for `FilmContent.heading` (a
  new nullable String leaf over the title column) against `ShortContent.description`.
* **Fixtures added:** `OccupantLocation.district`; `Film.spoken` / `Film.original` over two
  nesting types that both declare `language`; `FanAlpha.alphaPlacement` / `FanBeta.betaPlacement`,
  two participants' nesting fields splicing the same leaf name over different FKs (the old census
  would have rejected this schema). The carve-out pin uses those two nesting fields under one key.

* **A fifth alias-minting family.** R1045's `SameRowTableField` (a same-table child read off the
  parent's own row, landed on trunk while this item was in progress) is a `ResultKeyAliasedField`
  too; it carries the `AliasScope` stamp, and its same-row arm and fetcher compose the alias through
  the same two points as the other four. The case R1045 dropped pending this item,
  `SameRowTableChildExecutionTest.resultKeys_sharedBetweenHostAndChild_stayApart`, is restored and
  green.

Tests: `ResultKeyScopeExecutionTest` (15 cases, every goal bullet plus both carve-outs' rejection
and the shared-nesting-type agreement under `Customer` and `Store`);
`ConnectionSharedResultKeyProjectionTest` unchanged and green; the new lint rule
`GeneratedSourcesLintTest.emittedSourcesReadNoSelectionAtUnboundedDepth`; unit and pipeline pins
in `RootLauncherRendererTest`, `ProjectionUnitRendererTest`, `SelectionOccurrencesClassEmitTest`,
`NestingCoordinatesClassEmitTest` and `ParticipantAliasOwnerPipelineTest` (census both ways,
both halves of the spliced alias).

Expected generated-name changes, not behaviour changes: every launcher now calls
`SelectionOccurrences.ownFields` / `connectionNodes` where it called
`getFieldsGroupedByResultKey()`; nested units' `$project` take a trailing `String rkPath`; spliced
`__rk_` aliases gain the path (`__rk_location/address`); the fold declares `foldSelection` and
`sharedAliasFields` locals; `PolymorphicSelectionSet` is no longer emitted and
`NestingCoordinates` is.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions, session_01WLbf1Fz1xSvu9nEhVNYhsG, 2026-10-19

**Question 1 fails on one claim: the rule does not hold for every launcher.** The plan says the
`SelectMethodBody` restriction "makes the rule hold for every launcher: a unit's map contains only
its own type's fields at its own level". The single-table discriminated fold is the exception.
`DiscriminatedTableFragments.fieldsList` uses the selective restriction, and that form passes an
exempt entry through whole: every name not in `perTypeFieldNames`, which holds only
participant-qualified `ResultKeyAliasedField`s. Plain columns and nesting fields are therefore
exempt. The plan keeps this rule as it is ("an exempt name passes through whole"), and
`ProjectionUnitRenderer` calls `canonical` on every entry before it switches. So
`{ allContent { ... on FilmContent { k: length } ... on ShortContent { k: description } } }` still
reaches `FilmContent.$project` with `k = [length, description]` and is still rejected after this
item lands. That is the type-scoped form of the rejection that the plan calls "the same defect" at
the node dispatch and fixes there. The Goal's rule and its "Valid documents are rejected" bullet
claim coverage the plan does not deliver, and no test pins the fold.

What would satisfy it is the author's choice:

* **Carve it out.** Name the fold case in the Goal as a deliberate remaining rejection, next to the
  connection one, give the reason, and pin it with a test.
* **Close it.** Have the selective form also drop exempt occurrences that belong to another
  participant. If you choose this, the census has to be revisited in the same change. The plan's
  census relaxation (claiming by the nesting-name chain plus the leaf) is sound today only because
  the exemption keeps a client-aliased bucket whole. For example, `... on A { l: locA { x } } ... on
  B { l: locB { x } }` lands both occurrences in one bucket, and `canonical` rejects it loudly. If
  the exemption narrows, those two participants compose one alias, `__rk_l/x`, at runtime over
  divergent SQL. The fold's set then drops one term, and that is silent wrong data the default-key
  census cannot see. Interface-owned aliased names have the same exposure, which is why the
  exemption exists.

Question 2 passes. Scoping in `SelectionOccurrences`, the derived connection arm, and an
`AliasScope` axis stamped next to `AliasOwner` all extend shapes already in the tree.

Non-blocking: `NestingReach.compute` visits every `NestingField`, but it keeps only one
representative per nesting type and drops the parent coordinate. Reading `NestingCoordinates` "off
the existing fold" therefore means extending the fold to collect `(parent type, field)` pairs. A
sentence saying so would spare the implementer the surprise.

### Round 2 (In Review → Done): request rework, session_01Su4ZZxie8o3AYWe5DL6npX, 2026-10-20

**Question 2 fails on one approval precondition: the delivery adds new code-string assertions on
generated method bodies.** The execution evidence is sound and complete: `ResultKeyScopeExecutionTest`
(15 cases) covers every Goal bullet, both carve-outs and the shared nesting type under `Customer`
and `Store`; `ConnectionSharedResultKeyProjectionTest` holds with only its javadoc changed;
`SameRowTableChildExecutionTest`'s shared-key case is restored; the verification build is green.
The code matches the approved plan, and the departures are recorded under Implementation notes.
The retirement sweep comes up clean in code and prose.

The precondition is "no code-string assertions on generated method bodies anywhere in the delivered
tests" (development-principles.adoc: "Asserting on generated Java or SQL text is banned"). The spec
approved *updating* existing body assertions for the new names ("Tests", last paragraph). It did not
approve new ones, and it kept the new emit tests structural on purpose. These are new:

* `ProjectionUnitRendererTest`: `nestedUnit_takesThePathOfTheRowItWritesIntoAndQualifiesItsSplicedAliases`,
  `anchorUnit_takesNoPathAndKeepsTheUnqualifiedAlias` and
  `splice_passesThePathFromTheRowsOwnerDownToThisOccurrence` match `$project` body text
  (`table.TITLE.as("__rk_" + rkPath + ...)`, `entry.getKey() + "/"`).
* `ParticipantAliasOwnerPipelineTest.aSplicedLeafIsStampedSplicedAndBothHalvesComposeItsPath`
  matches `"+ rkPath + entry.getKey()"` and `"NestingCoordinates.rkPath(env)"` in a projection
  `TypeSpec` and a fetcher `MethodSpec`. That is pipeline tier, where testing.adoc lists body
  matching as banned by name.
* `RootLauncherRendererTest.connectionShape_projectsTheNodeLevelOfTheSelection`, plus the new
  `containsOnlyOnce("foldSelection = ...ownFields(...)")` and
  `for (...Map.Entry<...> rkEntry : foldSelection.entrySet())` lines added to the existing fold
  tests.

What would satisfy it: remove these body matches, or replace them with structural or data
assertions. Every behaviour they pin is already pinned at the execution tier. Some cases can be
kept in a structural form. The `rkPath` parameter list is structural, and its assertions are fine
as they stand. `unitKindAndAliasScopeCannotDisagree` asserts on constructor exceptions, which is
fine too. The scope derivation can be asserted as data:
`ProjectionCall.SelectionScope.of(ResultShape)` returns `CONNECTION_NODES` for a connection. The
model-stamp half of the `ParticipantAliasOwnerPipelineTest` case (`aliasScope()` on the leaf and
on the row-owned fields) is data, so it stays. Updates to body assertions that existed before this
item stay as the spec allowed.

Non-blocking:

* Implementation notes name the two landing commits by subject. Give their SHAs (`3f498d3`,
  `c239d5f`) and the follow-ups (`2a6c588`, `45fc6af`, `820a223`), so the changelog line at Done
  can cite them directly.
* `GeneratorUtils`'s javadoc on `RESULT_KEY_PREFIX` still says result keys are "unique per
  flattened selection map". That is stale now that the maps are per level. Reword it if you touch
  the file.
* Build note for the next reviewer: a trunk sync that moves `init.sql` leaves the sandbox DB stale.
  `MultiTableOrderingExecutionTest` and the `pagedItemsConnection_*` cases in `GraphQLQueryTest` then
  fail on a missing `B-1-10` row. Re-seed as `.claude/web-environment.md` describes. These failures
  are not R708's.
