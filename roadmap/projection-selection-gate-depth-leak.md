---
id: R708
title: "The $project selection gate fires on names selected at any depth below its unit"
status: Spec
bucket: correctness
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-18
last-updated: 2026-10-19
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

`ProjectionCall` gets a matching `fromConnection` shape, and the connection launchers switch
to it. These are the only call sites whose unit fields are not the fetched field's direct
children:

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
`DiscriminatedTableFragments.fieldsList` hosts, including
`TypeFetcherGenerator.buildTableInterfaceReprojection`. The renderer arms in
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
occurrence-list semantics, and keeps the selective form's rule: an exempt name passes through
whole, and the predicate reads `SelectedField.getName()`, never the client-minted key. The
proxy class and its generator are deleted. Its javadoc said a map argument "would not survive
the nested-projection recursion". That is not true of the `$project` contract, which only ever
takes a map. Callers: `MultiTablePolymorphicEmitter.buildPerTypenameSelect`,
`DiscriminatedTableFragments.fieldsList`, `LaunchSource.DiscriminatedTable.SelectionRestriction`'s
helper reference.

**Federation and `Query.node(s)` dispatch is restricted too.** `SelectMethodBody.body`
projects `<Type>` from the env of `_entities`, `Query.node` or `Query.nodes`, whose immediate
fields include every concrete type's fragment fields. With no restriction, `... on Customer
{ k: firstName } ... on Film { k: title }` puts both into one bucket and `canonical` rejects
a valid document. This is the same defect, scoped by type instead of depth. The site applies
the two-argument restriction for its concrete type. That makes the rule hold for every
launcher: a unit's map contains only its own type's fields at its own level.

**The discriminated fold's other two depth-unbounded gates.** `DiscriminatedTableFragments`
reads the selection twice outside `$project`. Both reads switch to the scoped map. The fold
computes the scoped map once into a local (`ownFields` or `connectionNodes`, depending on the
host), and every read in the fold uses it:

* the `InheritedRef` base-slice loop (`for (rkEntry : env.getSelectionSet().getFieldsGroupedByResultKey()...)`);
* `typeConditionedGate`, today `containsAnyOf("<Type>.<f>", "**/<Type>.<f>")`. The `**/`
  exists to reach `edges/node/<Type>.<f>`, and it also matches the same type-conditioned field
  anywhere deeper. It becomes a membership test on the scoped map: some occurrence has name
  `<f>` and object type names containing `<Type>`.

**Guards keep their checks and narrow their premise.** After scoping, a bucket holds more than
one occurrence only when several parent occurrences were merged: the connection's
`edges.node` and `nodes` paths, or a child bucket of such a merge. `canonical` and
`requireConsistentArguments` keep their checks and messages, and their javadoc now describes
the buckets they actually receive. One case stays rejected, and the tests pin it: one key for
two different fields under `edges { node }` and `nodes` of the *same* connection
(`ConnectionSharedResultKeyProjectionTest.nameDivergence_onNonArgConsumingArm_failsLoudAsFieldError`).
Both paths project from one SQL row and one node reader, so that shape cannot be represented,
and the message already names it. It is the one narrow exception to "reusing a key under a
different parent is accepted", and graphql-java gives these two paths no sibling-merge check.

### Alias namespace: path-qualify values written onto a shared row

A *nested unit* (`ProjectionCommand.NestedUnit`) is the projection unit of a nesting type
under one anchor. It is reached only by splicing (`CallWrap.Splice`) into its anchor's SELECT
list, so it writes into the anchor's row. Every selection set spliced into one row must get
its own alias space.

* **Write side.** A nested unit's `$project` takes one more parameter, `rkPath`: the result
  keys from the row owner down to this nesting occurrence, each followed by a path delimiter.
  An anchor's Splice arm passes `entry.getKey() + <delim>`, and a nested unit's Splice arm
  passes `rkPath + entry.getKey() + <delim>`. Every result-key-aliased term the nested unit
  writes uses `<prefix> + rkPath + entry.getKey()` where it now uses `<prefix> +
  entry.getKey()`. Those terms are `SelectTerm.Column` with `BY_RESULT_KEY`, `HelperCall`,
  unnamed `ScalarSubselect`, and the Multiset, LookupMultiset and PivotMultiset envelopes.
  Multiset arms open a new row, so the callee they call stays an anchor unit and takes no
  path. Anchor and pivot unit signatures do not change. Neither do `BY_COLUMN_IDENTITY`
  columns, because every selection set that reads a plain column reads the same value.
* **Read side.** Readers declared on a nesting type (the `FetcherEmitter` arms for
  `TableField` single and list, `PivotField`, `ComputedField`, and direct-compaction
  `ColumnBackedReferenceField`) compose the same path at runtime. They walk
  `env.getExecutionStepInfo()` up through the ancestor steps whose declaring object type is a
  nesting type, prepending each step's result key. The walk is parent-agnostic, which a shared
  nesting type needs because its `Fetchers` class is registered once (first-parent-wins, see
  R323's changelog entry). The set of nesting type names comes from the model's
  `NestingField` return types. It is emitted as a constant on one generated per-schema runtime
  helper, and the helper also holds the walk. Readers on table-backed types are unchanged: the
  path is empty for them, and the emitter knows that statically.
* **Delimiter and composition** belong to `ReservedAliases`, next to `resultKeyPrefix`, so the
  write and read sides spell one decision. The composed alias is `__rk_` + optional `<owner>$`
  + path segments + key. The delimiter must be outside the GraphQL name grammar, different
  from the owner delimiter `$`, and not `.`. The `Record.get(String, Class)` reads in
  `FetcherEmitter` take a plain field name, and a dot risks being read as a qualifier. The
  recommendation is `/`, the separator graphql-java uses for selection paths. It is injective
  for the same reason the owner qualifier is: no client-minted key can spell it.

The path walk and the threaded `rkPath` must agree for every inline chain. The test that pins
this is the shared-nesting-type case below, read through both parents.

### Fixture prose

The `OccupantLocation` comment in `graphitron-sakila-example/.../schema.graphqls` says that
naming around the gate's recursion "is a defect in the gate ... filed on its own". That
sentence goes. The type gains a `district` leaf (same column and path as `occupantDistrict`),
which is exactly the collision R323 avoided, now pinned rather than dodged.

## Tests

Each goal bullet gets its own execution-tier test in `graphitron-sakila-example`, in a new
class next to `ConnectionSharedResultKeyProjectionTest` (or extending it, at the
implementer's judgment). SQL is captured with the `ExecuteListener` pattern
`ProjectionShapeExecutionTest` already uses.

* **No over-projection.** `{ customers(active: true) { location { address { district } } } }`
  with the new `OccupantLocation.district` leaf returns correct data, and the captured SQL
  carries no `__rk_district` term at the `location` level. A second case selects both
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

Pipeline tier: `SelectionOccurrencesClassEmitTest` covers the three adapters and the
restriction filter. `OccurrenceArgumentGuardPipelineTest` stays green. Unit and pipeline tests
that assert on emitted `__rk_` aliases or on `PolymorphicSelectionSet` get updated for the new
shapes (`ProjectionUnitRendererTest`, `RootLauncherRendererTest`,
`PolymorphicSelectionSetClassEmitTest`, `ParticipantAliasOwnerPipelineTest`, and any captured
SQL in `GraphQLQueryTest`). These are expected changes in generated names. They are not
behaviour changes and are called out in the In Review notes.

## Retired vocabulary

* `PolymorphicSelectionSet` (the generated class) and `PolymorphicSelectionSetClassGenerator`.
* The emitted `getFieldsGroupedByResultKey()` call: after this item, no emitted source calls
  it.
* The `**/<Type>.<field>` glob in `typeConditionedGate`.
* Prose describing `getFieldsGroupedByResultKey()` as "flattening the subtree" *as the
  mechanism the connection merge relies on*: `SelectionOccurrencesClassGenerator`'s class
  javadoc, `ProjectionCall`'s class javadoc, and `ConnectionSharedResultKeyProjectionTest`'s
  class javadoc.

## Other solutions we've considered

* **Restrict the bucket to immediate children, with no connection adapter.** This is the
  Backlog body's first sketch. It breaks every connection, because the node fields sit under
  `edges/node` and `nodes` and are found today only through flattening.
* **Glob patterns (`edges/node/*`, `nodes/*`) for the connection level.** These work, but they
  match field names through a file-system path matcher and return an unordered map. Walking
  `getImmediateFields()` does the same with no new matching semantics.
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

## Reviewer findings
