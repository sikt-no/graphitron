---
id: R1115
title: "A child returning a @table type on its parent's own table needs @reference, and @reference can name the table's own primary key"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-10-28
last-updated: 2026-10-30
---

# A child returning a @table type on its parent's own table needs @reference, and @reference can name the table's own primary key

## Goal

A single-valued, argument-free field whose return type is a `@table` type bound to the same table as its parent, with no `@reference`, becomes a build error asking for a `@reference` that says how the two rows are related. Putting `@table` on the return type means "a row of that table, reached by a join", so two `@table` types on one table describe a self-join, and a self-join has no default. A return type without `@table`, a plain nested object, keeps reading the parent's own row. An author who wants the parent's own row and needs the return type to stay `@table` (because a root field or a field on another table also returns it) names the table's own primary key in `@reference`. That joins each row to itself, with no hand-written code.

```graphql
# A: unchanged. FilmNames is a plain nested object, so its fields are
# columns of the parent's own film row.
type Film @table(name: "film") { names: FilmNames }
type FilmNames { nb: String @field(name: "title") }

# B: today reads the parent's own row, exactly like A. After this item,
# a build error naming Film.names and the two remedies.
type Film @table(name: "film") { names: FilmNames }
type FilmNames @table(name: "film") { nb: String @field(name: "title") }

# B, fixed: the join is stated, on film's own primary key.
type Film @table(name: "film") {
  names: FilmNames @reference(path: [{key: "film_pkey"}])
}
type FilmNames @table(name: "film") { nb: String @field(name: "title") }
```

The fixed form answers the same as B does today: each film's own title, and `null` for a nullable `names` when every field of `FilmNames` would be `null`. A same-table field narrowed by its own arguments, `@condition` or `@lookupKey` (`sameLength(length: Int @field(name: "length")): [Film!]`, the films of that length under every film) joins nothing and keeps its current reading.

## Background

R1045 found that shape B read one arbitrary row for every parent, which leaked one person's data to every `FeideBruker` in sis through `_entities`. It fixed that by giving the single, argument-free form Graphitron 9's reading. That needed a new leaf: a classified field kind in the model, here `ChildField.SameRowTableField`, projected as a single-row multiset with no `FROM` that reads the parent's alias and calls the return type's own `$project`. R1045 kept the parent-independent read for a field narrowed by arguments, `@condition` or `@lookupKey`, and rejected the rest (`FieldBuilder.unnarrowedSameTableMessage`). It chose not to reject shape B outright because four sis production fields use it and their return types stay `@table`: `FeideBruker.personProfil`, `Resultat.kandidatinformasjon`, `Karakter.navnAlleSprak`, `StudieprogramUtvekslingsavtaleKobling.gyldighetsperiode`. R1069 then moved the null rule into `rewrite.model.Presence`, which the leaf shares with nested objects and `@table` children.

This item reverses the single-row reading on the grounds stated in the goal, and gives those four fields a migration that needs no Java: the primary-key `@reference`.

## User documentation (first-client check)

`docs/manual/how-to/map-types-to-tables.adoc` replaces "Returning the parent's own row as a table type" with:

> == Returning the parent's own row
>
> A field whose return type has no `@table` reads its fields from the parent's own row (see "Grouping columns into a nested object" above). When the return type must be a `@table` type, because a root field or a field on another table also returns it, say that the field joins the parent's row to itself by naming the table's primary key:
>
> ```graphql
> type Film @table {
>     summary: FilmSummary @reference(path: [{key: "film_pkey"}])
> }
> type FilmSummary @table(name: "film") {
>     title: String @field(name: "TITLE")
>     length: Int @field(name: "LENGTH")
> }
> ```
>
> `film { summary { title } }` returns each film's own title. A nullable `summary` resolves to `null` when the object would hold nothing; see <<single-object-null,When a single object resolves to `null`>>. The primary key is named like a foreign key, by its constraint name or its jOOQ `Keys` constant, and it must be the whole path.
>
> Without the `@reference`, a single field returning a `@table` type on its parent's own table and taking no arguments is a build error: two `@table` types on one table are a join, and Graphitron does not guess which rows it pairs. With arguments, a `@condition` or a `@lookupKey`, the field joins nothing: it reads the table narrowed by them, the same rows under every parent. `related(filter: FilmFilter): [Film!]` on `Film` returns the films matching the filter, whichever film it is selected under. A same-table list, or a single field whose arguments filter nothing, would read every row of the table under every parent and is rejected. To reach other rows of the table related to the parent, name a self-referencing foreign key with `@reference(path: [{key: ...}])`.

`docs/manual/reference/directives/reference.adoc`: the `key` row of the `ReferenceElement` table reads "The foreign-key constraint name (matching jOOQ's `Keys` class), or the primary key of the table the path starts on, which joins each row to itself (see <<Joining a row to itself>>). ...", and a short section `== Joining a row to itself` carries the rule: the element must be the path's only element, the field's parent and return type must be bound to that table, and the result is the parent's own row.

`docs/manual/how-to/migrating-from-legacy.adoc`: the "Things that didn't change" bullet saying a same-table `@table` child still reads the parent's own row moves to the list of changes: "A field returning a `@table` type bound to the parent's own table, with no `@reference`, read the parent's own row in Graphitron 9. It is now a build error. Drop `@table` from the return type if nothing else returns it as a table type, or add `@reference(path: [{key: "<the table's primary key>"}])`; either way the field answers as before."

`docs/manual/reference/directives/splitQuery.adoc`: the same-table bullet's remedies become "name the table's primary key with `@reference(path: [{key: ...}])`, which gives the batch the parent's key to batch on" and "drop `@splitQuery` from a field narrowed by its own arguments, `@condition` or `@lookupKey`, which reads the table independent of the parent".

## Plan

The work splits in two at a real seam. Part 1 adds the primary-key self-join and changes nothing that exists, so it can land and be verified on its own. Part 2 turns shape B into a rejection, migrates every fixture on it, and retires the same-row leaf. Part 2 depends on Part 1, because the rejection names Part 1 as the remedy and the four sis fields need it in the same release.

### Part 1: a `@reference` element naming the table's own primary key

**Rule.** A `key:` value may name the primary key of the table the path starts on, by SQL constraint name or jOOQ `Keys` constant, case-insensitively, with the optional schema qualifier the foreign-key form accepts. It is admitted only as the only element of a field-site `@reference` path (a field of a `@table` type returning a table-bound type) whose parent table and return table are that table. Everywhere else it is a rejection naming the rule: as one element among several, at an argument or input-field site, in a `@referenceFor` chain, on a field whose return table differs, or naming another table's primary key. Admitting it only as the whole path keeps it to the one question it answers, the parent's own row, and keeps it out of every multi-hop reader (fan-out, routine re-reads, the nodeId decode chain). A name that resolves both to a foreign key touching the table and to the table's primary key is an ambiguity rejection naming both, never a silent pick.

**Model.** `On.Keying` gains a third arm, `On.Keying.PrimaryKey(String keyName)`: the pairs pair every primary-key column with itself, an identity self-join. `describe()` reads "primary key 'film_pkey'". `On`'s javadoc names it beside the foreign key and the name-matched key. The hop is `new JoinStep.Hop(new TableExpr.Catalog(table), new On.ColumnPairs(new On.Keying.PrimaryKey(pk), slots), table, whereFilter, alias)` with one `JoinSlot.FkSlot(col, col)` per primary-key column, in key order. Separate arm rather than reusing `NameMatchedKey`: that arm's meaning is "the previous node has no foreign-key metadata", and the store and `RoutineWriteQuery` read it that way.

**Classifier.** `BuildContext.parsePathElement`'s `key` branch resolves a primary key through a new `JooqCatalog` lookup beside `findForeignKey` (same dual namespace, same qualifier handling, a sealed result with `Resolved` / `NotInCatalog` / `Ambiguous`). The element-walk core passes what the rule needs to decide admission: the site (field path, not `parseChainSegment` or an argument site), whether the element is the only one, and the declared return table. The existing `key could not be resolved` message's candidate hint adds the start table's primary key name where the start and return tables are the same, so an author who misspells it is pointed at the right name. Downstream nothing new is needed:

- `BuildContext.buildParentCorrelation` mirrors a filter-less `On.ColumnPairs` head to `ParentCorrelation.OnFkSlots`, so the child read is correlated `child.pk = parent.pk`.
- `computeTerminalTargetVerdict` matches, since the hop's target is the return table.
- `JoinFragments.emitKeyedJoin` renders the explicit column-equality conjunction for both non-foreign-key arms. The new arm joins the `NameMatchedKey` case, so the switch stays exhaustive and the output is unchanged for existing arms.
- `FieldBuilder.classifyObjectReturnChildField` mints an ordinary `TableField` (inline) or `BatchedTableField` (`@splitQuery`, keyed on the parent's primary key through `deriveSplitQuerySource`) from the non-empty path, so the same-table branch that mints `SameRowTableField` never sees it.
- The null contract is `GraphitronSchema.nullContractOf`'s `TableField` / `BatchedTableField` arm: `Presence.NullContract.of(wrapper, presenceOf(returnType))`, the same fold the same-row leaf uses. The joined row always exists while the parent does, so a nullable field resolves to `null` exactly when every presence leaf of the return type is absent, as shape B does today.

One visible difference, deliberate: inside an enclosing type, `Presence.SubtreePresence.over` treats a `SameRowTableField` as blocking, so an enclosing nullable object holding one never collapses to `null`. A `TableField` over the primary-key hop is a `PresenceLeaf.Probe` (`Presence.tableProbe`): an `EXISTS` over the identity join reading the target type's column leaves. An enclosing nullable object whose other leaves are all absent therefore resolves to `null` when the child's columns are also all null. That is the rule R1069 states for every other single reference, so it is the consistent answer. The `migrating-from-legacy.adoc` entry does not need to mention it, since sis's four fields sit directly on their table types.

Cost: the child read becomes `select ... from film as names_0 where names_0.film_id = film.film_id`, one primary-key lookup per parent row, where the same-row leaf read the outer alias with no `FROM`. This is the cost of any single `@reference` child and needs no special casing.

**Fact store.** The store resolves `key:` independently of the classifier, and today a primary-key name falls through every arm. The capture keeps it (`graphitron_ast_reference_key_step_entry`), but `graphitron_path_element_key` has a foreign key to `sql_referential_constraint`, so the walk in `graphitron_field_path_rule` makes zero moves and `graphitron_entry_defect_rule` records `ELEMENT_UNRESOLVED`. The LSP shows that as an error, and its diagnostics flag the spelling as an unknown foreign key. Following the modeling discipline (`docs/architecture/explanation/modeling-discipline.adoc`):

- A new relation `graphitron_path_element_primary_key`. Grain: "one row per path element whose `key:` spelling names a table's primary key, per candidate key". It is captured from the AST entry and `sql_primary_key`, with a foreign key to each, written by `derive/PathElements` beside `graphitron_path_element_key` from a `graphitron_path_element_primary_key_rule` view with the same `SQL_NAME` / `JOOQ_NAME` arms. It is a sibling rather than a widening because `graphitron_path_element_key`'s foreign key to `sql_referential_constraint` is the integrity rule that makes its rows foreign keys; a nullable kind column would trade that rule away. It owes an owner in `meta_relation`, a mark and sweep, and a `meta_gatherer_dependency` edge on whatever upstream families it reads.
- `graphitron_field_path_rule`'s `move` CTE gains a primary-key arm: from `from_table` to the same table when the element resolves to that table's primary key and is the path's only element. A sibling child `graphitron_field_path_primary_key` records the key ridden (the foreign-key child `graphitron_field_path_key` cannot hold it). `FieldPaths.columns()` gains the arm pairing each primary-key column with itself from `sql_constraint_column`.
- `graphitron_entry_defect_rule`'s `ELEMENT_UNRESOLVED` / `NO_ROUTE_FROM_DEPARTURE` test counts a `graphitron_path_element_primary_key` row as a resolution, so a resolvable primary key at a position the rule refuses reports `NO_ROUTE_FROM_DEPARTURE`, not "names something the catalog does not have".
- `graphitron_field_path_fanout` needs no change: a sole-element path has no intermediate step. `RoutineWriteQuery` needs none either: a root mutation path starts at a `@routine` node, so the rule never admits the element there, and its `keying()` default arm stays unreachable.
- The deprecated coordinate family (`graphitron_field_reference_step_hop` / `_step_target`, `FieldReferenceStepHops`, `ReferenceStepWalk`) is left as it is. A primary-key element writes no hop there, as an unresolved key does today. Its `via` check constraints would otherwise need a fifth value with a meaningless `fk_on_from`, and the field-site step target has no reader outside its writer. The argument and input-field families never see the element, since the rule refuses it at those sites. If `ReferenceStepRecombinationTest` or another gate asserts the walk and the coordinate family agree element for element, the implementer records the primary-key arm there as an expected difference rather than extending the deprecated family.

**LSP and MCP.**
- `lsp/facts/CatalogKeys` (`touching`, `named`, `spelledBy`) and `diagnostics/DiagnosticFacts.foreignKeySpellings` widen from `sql_referential_constraint` to also recognize the start table's primary key. A correct spelling is then not flagged, hover and go-to-definition land on the primary key, and `BindingUsages.hopSites` finds it.
- `completions/ReferenceCompletions` offers the primary key in a `key:` position when the field's parent and return types are bound to the same table and the path is otherwise empty.
- `mcp/SchemaQueries.joinPath` labels the step `PRIMARY_KEY` rather than falling back to `TABLE`.

### Part 2: reject shape B and retire the same-row leaf

**Classifier.** In `FieldBuilder.classifyObjectReturnChildField`'s same-table branch (empty path, null correlation, not narrowed), the `FieldWrapper.Single && argumentFree` case returns a structural rejection instead of minting `ChildField.SameRowTableField`. One message helper (a revision of `unnarrowedSameTableMessage`) covers all three unnarrowed shapes: single and argument-free, list, and single with arguments that filter nothing. It names both types and the table, says the field has no `@reference` and that two `@table` types on one table are a join, and gives the remedies that apply:

- drop `@table` from the return type if nothing else returns it as a table type, which reads the parent's own row;
- `@reference(path: [{key: "<pk>"}])` with the table's real primary-key name read from the catalog, or `@reference(path: [{condition: ...}])` when the table has none;
- for the list and argument shapes only, narrow the field with an argument, `@condition` or `@lookupKey`.

The validator prefixes the field coordinate as it does today. `uncorrelatedSameTableSplitMessage` swaps its "drop `@splitQuery` to read the parent's own row" remedy for the primary-key `@reference`, and keeps its narrowed-read remedy.

**Retirement.** Nothing mints `ChildField.SameRowTableField` any more, so it goes with every arm that exists only for it:
- `CallWrap.SameRow` and `ProjectionUnitRenderer.sameRowArmBody`;
- the `ProjectionCommands` `contributionFor` arm and its `CONTRIBUTION_MINTING_LEAVES` membership;
- `FetcherEmitter.sameRowBody` and its dispatch;
- the arms in `FetcherEdgeCommands`, `OperationMembers`, `DeliveryFact`, `ParentSourceBinding`, `TypeFetcherGenerator`, `PlanCompileGraph`, `GraphitronSchema.nullContractOf` and `GraphitronSchemaValidator` (both the child switch and `isNestedWireableLeaf`);
- the `SameRowTableField` clause in `Presence`'s javadoc and blocking arm, since a same-table cycle now terminates through `Presence.targetColumnLeaves`, which never recurses into a probe's target.

Ratchet pins move with history lines: `LeafRatchetTest` child leaves down by one, the `LeafReconstructionKeyTest` triple removed, and the `CommandSeamRatchetTest` counts as measured. The `ChildField` sealed `permits` loses the leaf.

**Fixtures.** Every schema on shape B gains the primary-key reference, so the read and the tests' expectations are unchanged:
- In `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: `Film.sameRow` and `FilmSameRow.film` (`film_pkey`), `FilmSameRowNest.row` (`film_pkey`), and `PivotNestingHost.labelsRow` / `.labelsWithFilms` (`pivot_nesting_host_pkey`).
- `federated-schema.graphqls`'s `Film.sameRow` (`film_pkey`).
- The `graphitron-model` corpus fixture `same-row-table.graphqls` (`city_pkey`).
- Any further hit the full build reports.

Their comments stop saying "has no `@reference`, so it reads this film's own row".

## Tests

Pipeline tier, `graphitron` (`SameTableInlineChildPipelineTest` reshaped):
- Part 1 classification: `Film.names: FilmNames @reference(path: [{key: "film_pkey"}])` classifies as `TableField` whose one hop is `On.ColumnPairs` with `On.Keying.PrimaryKey("film_pkey")` and slots `film_id -> film_id`. The parent correlation is `OnFkSlots`. The same holds by jOOQ constant (`FILM_PKEY`), at nested depth, and under a `@key` parent.
- With `@splitQuery` it is a `BatchedTableField` keyed on `film_id`.
- A composite key, `film_actor_pkey` on a `film_actor` pair, yields two slots in key order.
- `category_pkey` on `category`, which also has the self-referencing `category_parent_category_id_fkey`, resolves to the primary key, and the foreign-key name still resolves to the foreign key.
- Rejections, each through validator output with the coordinate: the primary key with a second element; another table's primary key; a parent and return table that differ; the element at an argument site; and a name matching both a foreign key and the primary key, if the test catalog can host one (otherwise pinned at the `JooqCatalog` lookup's unit level).
- Null contract: the verdicts `NullContractOverTheReturnType` pins today, re-pinned on the primary-key form, and a same-table cycle over two primary-key references terminates.
- Part 2: shape B is a structural rejection whose message names `Film.names`, both types, `film` and `film_pkey`. A table with no primary key gets the `condition:` remedy instead. The list and filter-nothing shapes keep their rejection with the revised remedies. The narrowed forms (`Narrowed`) are unchanged.
- `FetcherRegistrationsEmitterTest.nestedSameRowChild_registersUnderTheNestedType` becomes the primary-key form at nested depth, or is removed if the generic `TableField` nested registration already pins it.
- `SplitQuerySameTableRejectionPipelineTest`: the message's revised remedies, and the primary-key form classifying instead of rejecting.

Store, `graphitron` derive tier (`PrimaryKeySelfJoinPathTest`, on `CapturedStore.ofCatalog` like `NameMatchedStepHopTest`):
- `graphitron_path_element_primary_key` holds the element by SQL name and by jOOQ name.
- The walk records one move from `film` to `film`, a `graphitron_field_path_primary_key` row and the pairs `film_id -> film_id` (two pairs for `film_actor_pkey`), and no entry defect.
- A primary key as a second element records `NO_ROUTE_FROM_DEPARTURE`.
- A foreign-key element is untouched.
- A fact scenario under `graphitron-model/src/test/resources/facts/scenarios/` pins `graphitron_field_path` for the primary-key element.

LSP (`graphitron-lsp` tests beside the existing `key:` diagnostics and completions): `film_pkey` in a same-table field's path is not flagged, is offered as a completion there, and hover names it as `film`'s primary key. A misspelling is still flagged.

Execution tier, `graphitron-sakila-example`, on the migrated fixtures:
- `SameRowTableChildExecutionTest` keeps every assertion: each film its own row, the foreign-key child at both levels with shared result keys, the two-level same-table cycle, the null rule over the host labels, the narrowed `sameTableById`, and the nested-depth read.
- `FederationEntitiesDispatchTest.entities_sameRowChild_readsEachEntitysOwnRow` still returns each entity's own title.
- One new case: a `@splitQuery` field over the primary-key reference returns each film's own row in a second statement.

No assertions on generated method bodies.

## Retired vocabulary

- `SameRowTableField` (`ChildField.SameRowTableField`)
- `CallWrap.SameRow`, `sameRowArmBody`, `sameRowBody`
- "Returning the parent's own row as a table type" (manual section title)
- Prose saying a same-table `@table` field with no `@reference` "reads the parent's own row", in javadoc, fixture comments, corpus descriptions and the manual. Grep "parent's own row" and read each hit: the phrase stays correct for nested objects and the primary-key form.

## Other solutions we've considered

- **Keep R1045's reading.** Two `@table` types on one table read as an implicit self-join on the primary key. Rejected with the user: `@table` on the return type says the row is reached by a join, and that join should be stated.
- **A `condition:` hop.** Each of the four sis fields would carry a hand-written `(parent, child) -> parent.pk.eq(child.pk)` method. That works today but is boilerplate for a join the catalog already states.
- **`{table: "film"}` meaning the identity join on a same-table hop.** `{table:}` already means "the one foreign key between these tables", and a table with a self-referencing foreign key (`category`) would make that element mean two things.
- **A dedicated element field (`{self: true}` or similar).** It avoids spelling the constraint name, but it adds directive surface the LSP, store capture and docs would all learn, for a case `key:` expresses with an existing, completable, greppable name.

## Reviewer findings

### Round 1 (2026-10-30, Spec -> Ready, reviewer session 01FY5Uvv7jMh7m1yJqxsD43t)

Verdict: withhold. One blocking finding on question one, which the user has ruled on, and a set of factual findings on question two against trunk `8bd6160`. The architecture fit is good: nearly every claim the plan makes about the classifier, the model and the store checks out, and the primary-key arm extends shapes already in the tree.

**Finding 1 (question one: the goal). The item conflicts with a later R876 ruling, and is narrowed to Part 1.**

Four hours after this Spec landed, Alf's rulings of 2026-10-28 (`e7d39bd`) added R876's bullet "A table-bound child is a join, and `SameRowTableField` goes": a same-table bound child joins as any bound child does, its key inferred or written and its absence a defect, "so a same-row read is the author's condition on the primary key; this reverts R1045's leaf and its uncorrelated narrowed read". This item does not mention R876 and differs on two points: it keeps the narrowed uncorrelated read as out of scope, and it rejects the `condition:` remedy in favour of a primary-key `key:` element.

The user's ruling (2026-10-30): this item keeps Part 1 only, the primary-key `key:` element, as a small item of its own; it is cheaper for sis's four fields than four hand-written condition methods and adds no directive surface. Part 2 (rejecting shape B and retiring the leaf) and the fate of the narrowed read belong to R876's bullet, which a colleague is taking on. The revision therefore:
- rewrites the goal around the primary-key element, so a reader can judge it without Part 2;
- drops Part 2, its fixture migration, its tests and the retired vocabulary, or keeps them only as a hand-off note to R876 (findings 9 and 10 below are written for that note);
- states that discovery infers no key on a same-table hop, so R876's "key inferred or written" can never pick a self-referencing foreign key silently;
- drops the manual prose documenting the narrowed read as settled behaviour.

**Finding 2 (question two). The walk writes every named constraint as a foreign key.** `FieldPaths.keys()` (`FieldPaths.java:163-169`) writes every counted row with a non-null `CONSTRAINT_NAME` into `graphitron_field_path_key`, whose foreign key to `sql_referential_constraint` rejects `film_pkey`. The primary-key move arm must leave `constraint_*` null and record the key only in `graphitron_field_path_primary_key`. The step CTE has no "only element" flag today; the plan should say it adds one.

**Finding 3 (question two). Step labels.** A step with column pairs and no `graphitron_field_path_key` row is labelled `NAME_MATCH` by `graphitron_field_path_fanout` and by `RoutineWriteQuery`'s `stepVia`, and MCP `SchemaQueries.joinPath` falls to `TABLE`. The plan covers MCP only; it should say what the fanout view labels a primary-key step.

**Finding 4 (question two, factual). `RoutineWriteQuery` does not read `On.Keying`.** It has its own `RoutineWriteQuery.Keying` built from the store's `via` string. The reason given for a separate `On.Keying.PrimaryKey` arm ("the store and `RoutineWriteQuery` read it that way") should cite `synthesizeNameMatchedJoin`, the only producer of `NameMatchedKey`, and the store's `NAME_MATCH`; the conclusion stands. The only `switch` over `On.Keying` in main code is `JoinFragments.emitKeyedJoin`.

**Finding 5 (factual). The recombination contingency is moot.** `ReferenceStepRecombinationTest` compares `graphitron_field_reference_step_entry` with the AST recombination, not the walk with the step-hop family; no gate asserts they agree element for element. Drop the "if a gate asserts" clause.

**Finding 6 (question two). The store arm is required, not an editor nicety.** Entry defects reach only the LSP and MCP today, but R876's "Entry defects fail validate" makes them build failures, and `FactExpectationTest.everyFolderHoldsWhatItClaims` already requires the new scenario to store no defect. The plan should say the classifier and store arms land together.

**Finding 7 (factual). No new gatherer edge.** `meta_gatherer_dependency` already holds `('graphitron','jooq')` and `sql_primary_key` is in the `jooq` family; the new relation owes `meta_relation` rows like `graphitron_path_element_key`'s and nothing more.

**Finding 8 (question two). Cardinality and the inline remedy.** The admission rule does not say whether a list field over the primary-key element (`[FilmNames] @reference(path: [{key: "film_pkey"}])`, a one-element list) is admitted or rejected; say which. The `condition:` remedy has a `@splitQuery` test (`SplitQuerySameTableRejectionPipelineTest.conditionReferenceRemedy_classifiesAsParentAnchoredBatch`) but no inline one. For a table with no primary key, sakila's `role_assignment` has no key at all (`stint` and `stint_single` have unique keys only).

**Finding 9 (for the R876 hand-off). The retirement list is incomplete.** Besides the arms named, `SameRowTableField` / `CallWrap.SameRow` appear in `ProjectionCommands`' `readsSessionGlue` (`:177`) and `projectionIdentity` (`:841`) arms and `ChildField`'s two output-shape switches (`:76`, `:115`); comments in `PresenceProbes.java:19` and `FetcherRegistrationsEmitter.java:166` describe the leaf. `Presence` has no explicit arm (the leaf falls to `default -> Blocked`), so only its javadoc changes. `uncorrelatedSameTableSplitMessage` has three remedies today, not two.

**Finding 10 (for the R876 hand-off). Missed shape-B sites.** `PivotNestingHost.labelsComputedRow: HostLabelsComputedRow` (`schema.graphqls:6422`, the type `@table(name: "pivot_nesting_host")`) is shape B and not in the fixture list. `ProjectionMembershipTest` (`:55-59`, `:107`) declares `Film.sameRow: FilmSameRow` with no `@reference`, and the comment in `NestingNullContractExecutionTest:27` describes the same-row twin.
