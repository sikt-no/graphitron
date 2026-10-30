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

The fixed form answers the same as B does today: each film's own title, and `null` for a nullable `names` when every field of `FilmNames` would be `null`. The primary-key element is for a single-valued field only; a list over it is rejected, since it could only ever hold the one row.

Element-less discovery stays as it is: `BuildContext.parsePath` infers a foreign key only between two different tables, so a same-table field with no `@reference` never has a self-referencing key (such as `category`'s parent key) picked for it silently. The author states the join, and an unstated one is the rejection above.

Out of scope: a same-table field narrowed by its own arguments, `@condition` or `@lookupKey` (`sameLength(length: Int @field(name: "length")): [Film!]`, which today reads the films of that length under every film, independent of the parent). R876 decides whether that read stays. This item leaves its code untouched and adds no documentation or rejection-message remedy that presents it as settled behaviour.

## Background

R1045 found that shape B read one arbitrary row for every parent, which leaked one person's data to every `FeideBruker` in sis through `_entities`. It fixed that by giving the single, argument-free form Graphitron 9's reading. That needed a new leaf: a classified field kind in the model, here `ChildField.SameRowTableField`, projected as a single-row multiset with no `FROM` that reads the parent's alias and calls the return type's own `$project`. R1045 kept the parent-independent read for a field narrowed by arguments, `@condition` or `@lookupKey`, and rejected the rest (`FieldBuilder.unnarrowedSameTableMessage`). It chose not to reject shape B outright because four sis production fields use it and their return types stay `@table`: `FeideBruker.personProfil`, `Resultat.kandidatinformasjon`, `Karakter.navnAlleSprak`, `StudieprogramUtvekslingsavtaleKobling.gyldighetsperiode`. R1069 then moved the null rule into `rewrite.model.Presence`, which the leaf shares with nested objects and `@table` children.

This item reverses the single-row reading on the grounds stated in the goal, and gives those four fields a migration that needs no Java: the primary-key `@reference`.

R876's bullet "A table-bound child is a join, and `SameRowTableField` goes" (added 2026-10-28) covers the same ground with a different remedy: it reads a same-row field as "the author's condition on the primary key", and it also reverts the narrowed read. The user ruled on 2026-10-30 that this item carries the rejection of shape B and the retirement of `SameRowTableField`, with the primary-key `key:` element as the remedy: it is cheaper for sis's four fields than four hand-written condition methods, adds no directive surface, and a breaking author-facing change is better carried by an item of its own. Once this item lands, R876's bullet keeps only the narrowed read. That bullet is Alf's text, so its edit waits on his agreement; until then the two items overlap on the leaf's removal, and whoever was planning to remove `SameRowTableField` under R876 needs to agree that this item does it.

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
> `film { summary { title } }` returns each film's own title. A nullable `summary` resolves to `null` when the object would hold nothing; see <<single-object-null,When a single object resolves to `null`>>. The primary key is named like a foreign key, by its constraint name or its jOOQ `Keys` constant. It must be the whole path, and the field must be single-valued.
>
> Without the `@reference`, a single field returning a `@table` type on its parent's own table and taking no arguments is a build error: two `@table` types on one table are a join, and Graphitron does not guess which rows it pairs, nor does it infer a self-referencing foreign key. To reach other rows of the table related to the parent, name a self-referencing foreign key with `@reference(path: [{key: ...}])`.

`docs/manual/reference/directives/reference.adoc`: the `key` row of the `ReferenceElement` table reads "The foreign-key constraint name (matching jOOQ's `Keys` class), or the primary key of the table the path starts on, which joins each row to itself (see <<Joining a row to itself>>). ...", and a short section `== Joining a row to itself` carries the rule: the element must be the path's only element, on a single-valued field whose parent and return type are bound to that table, and the result is the parent's own row.

`docs/manual/how-to/migrating-from-legacy.adoc`: the "Things that didn't change" bullet saying a same-table `@table` child still reads the parent's own row moves to the list of changes: "A field returning a `@table` type bound to the parent's own table, with no `@reference`, read the parent's own row in Graphitron 9. It is now a build error. Drop `@table` from the return type if nothing else returns it as a table type, or add `@reference(path: [{key: "<the table's primary key>"}])`; either way the field answers as before."

`docs/manual/reference/directives/splitQuery.adoc`: the same-table bullet's remedies become "to return the parent's own row, name the table's primary key with `@reference(path: [{key: ...}])`, which gives the batch the parent's key to batch on" and "state the correlation with `@reference(path: [{condition: ...}])`". Its "drop `@splitQuery` to read the parent's own row" remedy goes, and the narrowed-read remedy goes with it (out of scope, see Goal).

## Plan

The work splits in two at a real seam. Part 1 adds the primary-key self-join and changes nothing that exists, so it can land and be verified on its own. Within Part 1 the classifier arm and the store arm land together: the store otherwise records an `ELEMENT_UNRESOLVED` defect on every primary-key element, which `FactExpectationTest.everyFolderHoldsWhatItClaims` refuses for the new scenario and which R876's "Entry defects fail validate" would turn into a build failure. Part 2 turns shape B into a rejection, migrates every fixture on it, and retires the same-row leaf. Part 2 depends on Part 1, because the rejection names Part 1 as the remedy and the four sis fields need it in the same release. The narrowed same-table read is untouched by both parts.

### Part 1: a `@reference` element naming the table's own primary key

**Rule.** A `key:` value may name the primary key of the table the path starts on, by SQL constraint name or jOOQ `Keys` constant, case-insensitively, with the optional schema qualifier the foreign-key form accepts. It is admitted only as the only element of a field-site `@reference` path (a field of a `@table` type returning a table-bound type) on a single-valued field whose parent table and return table are that table. Everywhere else it is a rejection naming the rule: as one element among several, at an argument or input-field site, in a `@referenceFor` chain, on a field whose return table differs, naming another table's primary key, or on a list or connection field (whose message says the identity join reads one row, so the field should be single-valued). Admitting it only as the whole path keeps it to the one question it answers, the parent's own row, and keeps it out of every multi-hop reader (fan-out, routine re-reads, the nodeId decode chain). A name that resolves both to a foreign key touching the table and to the table's primary key is an ambiguity rejection naming both, never a silent pick.

**Model.** `On.Keying` gains a third arm, `On.Keying.PrimaryKey(String keyName)`: the pairs pair every primary-key column with itself, an identity self-join. `describe()` reads "primary key 'film_pkey'". `On`'s javadoc names it beside the foreign key and the name-matched key. The hop is `new JoinStep.Hop(new TableExpr.Catalog(table), new On.ColumnPairs(new On.Keying.PrimaryKey(pk), slots), table, whereFilter, alias)` with one `JoinSlot.FkSlot(col, col)` per primary-key column, in key order. Separate arm rather than reusing `NameMatchedKey`: that arm's only producer is `BuildContext.synthesizeNameMatchedJoin`, the hop from a function result that declares no constraints to a table by its key's columns matched by name, which the store labels `NAME_MATCH`. An identity join on a declared primary key is not that, and reusing the arm would make every reader of it mean two things. The only `switch` over `On.Keying` in main code is `JoinFragments.emitKeyedJoin` (`RoutineWriteQuery` has its own `Keying`, built from the store's step label).

**Classifier.** `BuildContext.parsePathElement`'s `key` branch resolves a primary key through a new `JooqCatalog` lookup beside `findForeignKey` (same dual namespace, same qualifier handling, a sealed result with `Resolved` / `NotInCatalog` / `Ambiguous`). Admission needs the site, which the element walk cannot see: `resolvePathElements` is shared by `parsePath`, `parseExplicitPath` and `parseChainSegment`, and `parsePath` alone has callers at argument sites, interface and participant fields, node-id leaves and `@orderBy` paths. So `parsePath` gains an explicit opt-in (an overload or a boolean, defaulting to off), set only by `FieldBuilder.classifyObjectReturnChildField`'s `TableBoundReturnType` arm. `resolvePathElements` threads it to `parsePathElement`, which already receives the current source, the terminal target and `isList`, and already counts the elements. The `key` branch admits a primary-key resolution when the opt-in is on, the element is the only one, the field is not a list, and the current source, the terminal target and the start table are one table; otherwise it records the rule's rejection. The existing `key could not be resolved` message's candidate hint adds the start table's primary key name where the start and return tables are the same, so an author who misspells it is pointed at the right name. Downstream nothing new is needed:

- `BuildContext.buildParentCorrelation` mirrors a filter-less `On.ColumnPairs` head to `ParentCorrelation.OnFkSlots`, so the child read is correlated `child.pk = parent.pk`.
- `computeTerminalTargetVerdict` matches, since the hop's target is the return table.
- `JoinFragments.emitKeyedJoin` renders the explicit column-equality conjunction for both non-foreign-key arms. The new arm joins the `NameMatchedKey` case, so the switch stays exhaustive and the output is unchanged for existing arms.
- `FieldBuilder.classifyObjectReturnChildField` mints an ordinary `TableField` (inline) or `BatchedTableField` (`@splitQuery`, keyed on the parent's primary key through `deriveSplitQuerySource`) from the non-empty path, so the same-table branch that mints `SameRowTableField` never sees it.
- The null contract is `GraphitronSchema.nullContractOf`'s `TableField` / `BatchedTableField` arm: `Presence.NullContract.of(wrapper, presenceOf(returnType))`, the same fold the same-row leaf uses. The joined row always exists while the parent does, so a nullable field resolves to `null` exactly when every presence leaf of the return type is absent, as shape B does today.

One visible difference, deliberate: inside an enclosing type, `Presence.SubtreePresence.over` treats a `SameRowTableField` as blocking, so an enclosing nullable object holding one never collapses to `null`. A `TableField` over the primary-key hop is a `PresenceLeaf.Probe` (`Presence.tableProbe`): an `EXISTS` over the identity join reading the target type's column leaves. An enclosing nullable object whose other leaves are all absent therefore resolves to `null` when the child's columns are also all null. That is the rule R1069 states for every other single reference, so it is the consistent answer. The `migrating-from-legacy.adoc` entry does not need to mention it, since sis's four fields sit directly on their table types.

Cost: the child read becomes `select ... from film as names_0 where names_0.film_id = film.film_id`, one primary-key lookup per parent row, where the same-row leaf read the outer alias with no `FROM`. This is the cost of any single `@reference` child and needs no special casing.

**Fact store.** The store resolves `key:` independently of the classifier, and today a primary-key name falls through every arm. The capture keeps it (`graphitron_ast_reference_key_step_entry`), but `graphitron_path_element_key` has a foreign key to `sql_referential_constraint`, so the walk in `graphitron_field_path_rule` makes zero moves and `graphitron_entry_defect_rule` records `ELEMENT_UNRESOLVED`. The LSP shows that as an error, and its diagnostics flag the spelling as an unknown foreign key. Following the modeling discipline (`docs/architecture/explanation/modeling-discipline.adoc`):

- A new relation `graphitron_path_element_primary_key`. Grain: "one row per path element whose `key:` spelling names a table's primary key, per candidate key". It is captured from the AST entry and `sql_primary_key`, with a foreign key to each, written by `derive/PathElements` beside `graphitron_path_element_key` from a `graphitron_path_element_primary_key_rule` view with the same `SQL_NAME` / `JOOQ_NAME` arms. It is a sibling rather than a widening because `graphitron_path_element_key`'s foreign key to `sql_referential_constraint` is the integrity rule that makes its rows foreign keys; a nullable kind column would trade that rule away. It owes `meta_relation` rows like `graphitron_path_element_key`'s and a mark and sweep. It needs no new `meta_gatherer_dependency` edge: the table already holds `('graphitron', 'jooq')`, and `sql_primary_key` is in the `jooq` family.
- `graphitron_field_path_rule`'s `step` CTE gains a `sole_element` flag, true where the walk stands at position 0 from a `RESULT`, `BINDING` or `LIFTED` start and the field's path has no element at position 1 (a `ROUTINE` start begins after the routine, so its steps are never sole). The `move` CTE gains a primary-key arm, taken when the element has a `graphitron_path_element_primary_key` row for the table the walk stands at, `sole_element` and `single_valued` hold: it moves from `from_table` to the same table and leaves `constraint_*` and `fk_on_from` null. That matters because `FieldPaths.keys()` writes every move with a non-null `CONSTRAINT_NAME` into `graphitron_field_path_key`, whose foreign key to `sql_referential_constraint` would reject `film_pkey`. A sibling child `graphitron_field_path_primary_key` records the key ridden instead, written by a new `FieldPaths` method beside `keys()`. `FieldPaths.columns()` gains the arm pairing each primary-key column with itself from `sql_constraint_column`.
- `graphitron_entry_defect_rule`'s `ELEMENT_UNRESOLVED` / `NO_ROUTE_FROM_DEPARTURE` test counts a `graphitron_path_element_primary_key` row as a resolution, so a resolvable primary key at a position the rule refuses reports `NO_ROUTE_FROM_DEPARTURE`, not "names something the catalog does not have".
- Step labels. A step with column pairs and no `graphitron_field_path_key` row reads `NAME_MATCH` in `graphitron_field_path_fanout` and in `RoutineWriteQuery`'s step label, so a primary-key step would read `NAME_MATCH` if either ever saw one. Neither does, and neither changes: the fanout view labels the two steps around an intermediate table, and a primary-key step is the path's only step, so it is never an intermediate's enter or leave; `RoutineWriteQuery` reads only paths with a `ROUTINE` start, where the step is never sole. The one reader that does see it is MCP, below.
- The deprecated coordinate family (`graphitron_field_reference_step_hop` / `_step_target`, `FieldReferenceStepHops`, `ReferenceStepWalk`) is left as it is. A primary-key element writes no hop there, as an unresolved key does today. Its `via` check constraints would otherwise need a fifth value with a meaningless `fk_on_from`, and the field-site step target has no reader outside its writer. The argument and input-field families never see the element, since the rule refuses it at those sites. No gate asserts that the walk and that family agree element for element (`ReferenceStepRecombinationTest` compares the step entries with the AST recombination), so nothing there needs an exception.

**LSP and MCP.**
- `lsp/facts/CatalogKeys` (`touching`, `named`, `spelledBy`) and `diagnostics/DiagnosticFacts.foreignKeySpellings` widen from `sql_referential_constraint` to also recognize the start table's primary key. A correct spelling is then not flagged, hover and go-to-definition land on the primary key, and `BindingUsages.hopSites` finds it.
- `completions/ReferenceCompletions` offers the primary key in a `key:` position when the field's parent and return types are bound to the same table and the path is otherwise empty.
- `mcp/SchemaQueries.joinPath` gains a `PRIMARY_KEY` arm ahead of `CONDITION`, reading `graphitron_field_path_primary_key`, so the step is not labelled `TABLE` by the fall-through; its `matched_by` column reads the new relation's for that step.

### Part 2: reject shape B and retire the same-row leaf

**Classifier.** In `FieldBuilder.classifyObjectReturnChildField`'s same-table branch (empty path, null correlation, not narrowed), the `FieldWrapper.Single && argumentFree` case returns a structural rejection instead of minting `ChildField.SameRowTableField`. One message helper (a revision of `unnarrowedSameTableMessage`) covers all three unnarrowed shapes: single and argument-free, list, and single with arguments that filter nothing. It names both types and the table, says the field has no `@reference` and that two `@table` types on one table are a join, and gives the remedies that apply:

- for the two single-valued shapes: drop `@table` from the return type if nothing else returns it as a table type, which reads the parent's own row; or `@reference(path: [{key: "<pk>"}])` with the table's real primary-key name read from the catalog, or `@reference(path: [{condition: ...}])` when the table has none;
- for the list shape: state the related rows with `@reference(path: [{key: ...}])` naming a self-referencing foreign key, or with `@reference(path: [{condition: ...}])`. The primary key is not offered, since the rule refuses it on a list.

No message offers narrowing by an argument, `@condition` or `@lookupKey` as a remedy any more; today's `unnarrowedSameTableMessage` does, and that clause goes (out of scope, see Goal). The validator prefixes the field coordinate as it does today. `uncorrelatedSameTableSplitMessage` has three remedies today. Its "drop the split marker from a single-cardinality field with no arguments, which reads the parent's own row" becomes the primary-key `@reference` for a single-cardinality field, its `condition:` remedy stays, and its narrowed-read remedy ("drop the split marker" for a field narrowed by `@condition` or `@lookupKey`) goes.

**Retirement.** Nothing mints `ChildField.SameRowTableField` any more, so it goes with every arm that exists only for it:
- `CallWrap.SameRow` and `ProjectionUnitRenderer.sameRowArmBody`;
- in `ProjectionCommands`: the `contributionFor` arm, the `CONTRIBUTION_MINTING_LEAVES` membership, the `readsSessionGlue` arm over `CallWrap.SameRow` and the `projectionIdentity` arm;
- `ChildField`'s two output-shape switches (the `SourceShape` one and the `OutputField.wrap` one) and the record itself;
- `FetcherEmitter.sameRowBody` and its dispatch;
- the arms in `FetcherEdgeCommands`, `OperationMembers`, `DeliveryFact`, `ParentSourceBinding`, `TypeFetcherGenerator`, `PlanCompileGraph`, `GraphitronSchema.nullContractOf` and `GraphitronSchemaValidator` (both the child switch and `isNestedWireableLeaf`);
- the `SameRowTableField` clause in `Presence`'s javadoc. `Presence` has no explicit arm for the leaf (it falls to the `default` blocking arm), so only the prose changes; a same-table cycle now terminates through `Presence.targetColumnLeaves`, which never recurses into a probe's target;
- the comments describing the leaf in `PresenceProbes` and `FetcherRegistrationsEmitter`.

Ratchet pins move with history lines: `LeafRatchetTest` child leaves down by one, the `LeafReconstructionKeyTest` triple removed, and the `CommandSeamRatchetTest` counts as measured. The `ChildField` sealed `permits` loses the leaf.

**Fixtures.** Every schema on shape B gains the primary-key reference, so the read and the tests' expectations are unchanged:
- In `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: `Film.sameRow` and `FilmSameRow.film` (`film_pkey`), `FilmSameRowNest.row` (`film_pkey`), and `PivotNestingHost.labelsRow` / `.labelsWithFilms` / `.labelsComputedRow` (`pivot_nesting_host_pkey`).
- `federated-schema.graphqls`'s `Film.sameRow` (`film_pkey`).
- The `graphitron-model` corpus fixture `same-row-table.graphqls` (`city_pkey`).
- The inline schema in `graphitron`'s `ProjectionMembershipTest` (`Film.sameRow: FilmSameRow`, `film_pkey`); `FilmSameRow` stays in its pinned unit set, now reached through a `TableField` instead of the same-row leaf.
- Any further hit the full build reports.

Their comments stop saying "has no `@reference`, so it reads this film's own row", and so does the comment in `NestingNullContractExecutionTest` describing the same-row twin.

## Tests

Pipeline tier, `graphitron` (`SameTableInlineChildPipelineTest` reshaped):
- Part 1 classification: `Film.names: FilmNames @reference(path: [{key: "film_pkey"}])` classifies as `TableField` whose one hop is `On.ColumnPairs` with `On.Keying.PrimaryKey("film_pkey")` and slots `film_id -> film_id`. The parent correlation is `OnFkSlots`. The same holds by jOOQ constant (`FILM_PKEY`), at nested depth, and under a `@key` parent.
- With `@splitQuery` it is a `BatchedTableField` keyed on `film_id`.
- A composite key, `film_actor_pkey` on a `film_actor` pair, yields two slots in key order.
- `category_pkey` on `category`, which also has the self-referencing `category_parent_category_id_fkey`, resolves to the primary key, and the foreign-key name still resolves to the foreign key.
- Rejections, each through validator output with the coordinate: the primary key with a second element; another table's primary key; a parent and return table that differ; the element at an argument site; the element on a list field (`[FilmNames] @reference(path: [{key: "film_pkey"}])`); and a name matching both a foreign key and the primary key, if the test catalog can host one (otherwise pinned at the `JooqCatalog` lookup's unit level).
- Without a primary key: an inline `@reference(path: [{condition: ...}])` on a same-table single field classifies as `TableField`, the inline sibling of `SplitQuerySameTableRejectionPipelineTest.conditionReferenceRemedy_classifiesAsParentAnchoredBatch`.
- Null contract: the verdicts `NullContractOverTheReturnType` pins today, re-pinned on the primary-key form, and a same-table cycle over two primary-key references terminates.
- Part 2: shape B is a structural rejection whose message names `Film.names`, both types, `film` and `film_pkey`. A table with no primary key (`role_assignment`, which has no key at all) gets the `condition:` remedy instead. The list and filter-nothing shapes keep their rejection with the revised remedies, and no message mentions narrowing. The narrowed forms (`Narrowed`) are unchanged.
- `FetcherRegistrationsEmitterTest.nestedSameRowChild_registersUnderTheNestedType` becomes the primary-key form at nested depth, or is removed if the generic `TableField` nested registration already pins it.
- `SplitQuerySameTableRejectionPipelineTest`: the message's revised remedies (primary key and `condition:`, no narrowed read), and the primary-key form classifying instead of rejecting.

Store, `graphitron` derive tier (`PrimaryKeySelfJoinPathTest`, on `CapturedStore.ofCatalog` like `NameMatchedStepHopTest`):
- `graphitron_path_element_primary_key` holds the element by SQL name and by jOOQ name.
- The walk records one move from `film` to `film`, a `graphitron_field_path_primary_key` row and the pairs `film_id -> film_id` (two pairs for `film_actor_pkey`), and no entry defect.
- A primary key as a second element, or on a list field, records `NO_ROUTE_FROM_DEPARTURE`, and no `graphitron_field_path_key` row is written for any primary-key step.
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

**Finding 1 (question one: the goal). The item conflicts with a later R876 ruling on the narrowed read.**

Four hours after this Spec landed, Alf's rulings of 2026-10-28 (`e7d39bd`) added R876's bullet "A table-bound child is a join, and `SameRowTableField` goes": a same-table bound child joins as any bound child does, its key inferred or written and its absence a defect, "so a same-row read is the author's condition on the primary key; this reverts R1045's leaf and its uncorrelated narrowed read". This item does not mention R876 and differs on two points: it keeps the narrowed uncorrelated read as out of scope, and it rejects the `condition:` remedy in favour of a primary-key `key:` element.

The user's ruling (2026-10-30): this item keeps both parts. On the remedy, the primary-key `key:` element stands over a hand-written `condition:`: it is cheaper for sis's four fields than four condition methods and adds no directive surface. Rejecting shape B and retiring `SameRowTableField` stay here, since Part 2 depends on Part 1 and sis needs both in one release, and a breaking author-facing change is better carried by an item of its own than by one R876 bullet. Only the narrowed read leaves: R876 decides whether it goes. The revision therefore:
- marks the narrowed uncorrelated read as out of scope and owned by R876's bullet, leaving its code untouched, and drops the manual prose that documents it as settled behaviour (the "With arguments, a `@condition` or a `@lookupKey`" paragraph and the narrowed remedies in the rejection messages and `splitQuery.adoc`), so this item does not ship documentation R876 may retract;
- states that discovery infers no key on a same-table hop, so R876's "key inferred or written" can never pick a self-referencing foreign key silently;
- cites R876 in Background and says that R876's bullet keeps only the narrowed read once this item lands; that bullet is Alf's text, so its edit waits on his agreement;
- folds findings 9 and 10 into the retirement and fixture lists.

A colleague planned to remove `SameRowTableField` in another change; that removal belongs to this item, so the two need to agree who implements it.

**Finding 2 (question two). The walk writes every named constraint as a foreign key.** `FieldPaths.keys()` (`FieldPaths.java:163-169`) writes every counted row with a non-null `CONSTRAINT_NAME` into `graphitron_field_path_key`, whose foreign key to `sql_referential_constraint` rejects `film_pkey`. The primary-key move arm must leave `constraint_*` null and record the key only in `graphitron_field_path_primary_key`. The step CTE has no "only element" flag today; the plan should say it adds one.

**Finding 3 (question two). Step labels.** A step with column pairs and no `graphitron_field_path_key` row is labelled `NAME_MATCH` by `graphitron_field_path_fanout` and by `RoutineWriteQuery`'s `stepVia`, and MCP `SchemaQueries.joinPath` falls to `TABLE`. The plan covers MCP only; it should say what the fanout view labels a primary-key step.

**Finding 4 (question two, factual). `RoutineWriteQuery` does not read `On.Keying`.** It has its own `RoutineWriteQuery.Keying` built from the store's `via` string. The reason given for a separate `On.Keying.PrimaryKey` arm ("the store and `RoutineWriteQuery` read it that way") should cite `synthesizeNameMatchedJoin`, the only producer of `NameMatchedKey`, and the store's `NAME_MATCH`; the conclusion stands. The only `switch` over `On.Keying` in main code is `JoinFragments.emitKeyedJoin`.

**Finding 5 (factual). The recombination contingency is moot.** `ReferenceStepRecombinationTest` compares `graphitron_field_reference_step_entry` with the AST recombination, not the walk with the step-hop family; no gate asserts they agree element for element. Drop the "if a gate asserts" clause.

**Finding 6 (question two). The store arm is required, not an editor nicety.** Entry defects reach only the LSP and MCP today, but R876's "Entry defects fail validate" makes them build failures, and `FactExpectationTest.everyFolderHoldsWhatItClaims` already requires the new scenario to store no defect. The plan should say the classifier and store arms land together.

**Finding 7 (factual). No new gatherer edge.** `meta_gatherer_dependency` already holds `('graphitron','jooq')` and `sql_primary_key` is in the `jooq` family; the new relation owes `meta_relation` rows like `graphitron_path_element_key`'s and nothing more.

**Finding 8 (question two). Cardinality and the inline remedy.** The admission rule does not say whether a list field over the primary-key element (`[FilmNames] @reference(path: [{key: "film_pkey"}])`, a one-element list) is admitted or rejected; say which. The `condition:` remedy has a `@splitQuery` test (`SplitQuerySameTableRejectionPipelineTest.conditionReferenceRemedy_classifiesAsParentAnchoredBatch`) but no inline one. For a table with no primary key, sakila's `role_assignment` has no key at all (`stint` and `stint_single` have unique keys only).

**Finding 9 (question two). The retirement list is incomplete.** Besides the arms named, `SameRowTableField` / `CallWrap.SameRow` appear in `ProjectionCommands`' `readsSessionGlue` (`:177`) and `projectionIdentity` (`:841`) arms and `ChildField`'s two output-shape switches (`:76`, `:115`); comments in `PresenceProbes.java:19` and `FetcherRegistrationsEmitter.java:166` describe the leaf. `Presence` has no explicit arm (the leaf falls to `default -> Blocked`), so only its javadoc changes. `uncorrelatedSameTableSplitMessage` has three remedies today, not two.

**Finding 10 (question two). Missed shape-B sites.** `PivotNestingHost.labelsComputedRow: HostLabelsComputedRow` (`schema.graphqls:6422`, the type `@table(name: "pivot_nesting_host")`) is shape B and not in the fixture list. `ProjectionMembershipTest` (`:55-59`, `:107`) declares `Film.sameRow: FilmSameRow` with no `@reference`, and the comment in `NestingNullContractExecutionTest:27` describes the same-row twin.

### Round 2 (2026-10-30, Spec -> Ready, reviewer session 01FY5UvsRpCgjZP9EjNRiV71)

Verdict: request revisions. The plan body is the one round 1 reviewed: the two commits since (`a829e30`, `f2ed0fd`) touch only the front matter and this section, so every revision round 1 asks for is still outstanding. Against trunk `f2ed0fd` its factual findings still hold: `FieldPaths.keys()` writes any non-null `CONSTRAINT_NAME` into `graphitron_field_path_key` (finding 2), `PivotNestingHost.labelsComputedRow` is still shape B and missing from the fixture list (finding 10), and every class, test, relation and manual section the plan names exists under that name. No new findings of my own: the architecture fit round 1 describes holds, and the remaining work is the author's revision.

**Question one fails.** The goal still says a same-table field narrowed by arguments, `@condition` or `@lookupKey` "keeps its current reading", and the manual text in "User documentation" and the `splitQuery.adoc` and Part 2 remedies still document that read as settled behaviour, while R876's bullet on trunk ("A table-bound child is a join, and `SameRowTableField` goes") still says that read reverts. Background does not mention R876. A reader cannot tell from the item which of the two owns the narrowed read, so the consumer-visible outcome of landing this item is not yet stated. Satisfied by the revision round 1's finding 1 lists: the narrowed read out of scope and owned by R876, its manual prose and narrowed remedies dropped, R876 cited in Background, and the statement that discovery infers no key on a same-table hop.

**Question two fails on the points that change what gets built.** Findings 2 (the step CTE's only-element flag, and the primary-key move leaving `constraint_*` null), 3 (what `graphitron_field_path_fanout` and `RoutineWriteQuery.stepVia` label a primary-key step), 6 (the classifier and store arms land together), 8 (whether a list field over the primary-key element is admitted, and an inline `condition:` remedy test) and 9 and 10 (the retirement and fixture lists) each leave the implementer a decision or an omission the plan should settle. Findings 4, 5 and 7 are factual corrections to the prose. Folding these into the body satisfies it.
