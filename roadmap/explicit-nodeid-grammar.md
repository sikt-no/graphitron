---
id: R473
title: "Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution"
status: Ready
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-13
last-updated: 2026-08-09
---

# Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution

An `ID`-typed field can still acquire node semantics implicitly, with the node identity derived from *table* facts (the catalog's `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants) rather than from what the schema author declared. Three sites still carry that inversion, each firing a per-site deprecation WARN and each answering "which node is this" from the backing table: the two input-side arms in `BuildContext.classifyInputField` (the qualifier-reverse-map arm and the bare same-table arm), and the surviving half of one output-side site in `FieldBuilder`.

A fourth site carries the same inversion and **is in scope**, absorbed from R273 at the pass-3 gate: the directive-less implicit scalar-`ID` **argument** arm in `FieldBuilder.classifyArgument` (`FieldBuilder.java:1580-1609`, the block its own comment calls "the implicit scalar-ID arm below, which owns synthesised paths (no @nodeId declared, parent table has nodeId metadata)"). Unlike the other three it fires no deprecation WARN, and like the qualifier arm it runs *ahead* of column resolution. R273 (`bare-scalar-id-arm-modernisation`) offered the collapse and is narrowed to its unrelated second deliverable, R265's deferred compile-tier guard. The arm is not merely deleted here: rule 6 replaces it with a narrower, SDL-derived arm.

That output-side site (a scalar `ID` on a `NodeType` parent with no directive at all) used to be a whole shim. R580 split it in two. The `Node.id` half was adopted as rule 1's permanent carrier: it resolves ahead of the column lookup, carries no deprecation, and is documented behaviour rather than a shim awaiting deletion. What remains a shim there is the *non-`id`* half, a bare `ID` field on a node type that is not the one satisfying the `Node` interface (`externalId: ID`), which still synthesises a nodeId from table facts and still warns. That half is rule 4's business and is still in this item's scope; the `Node.id` half is not.

Note the two halves sit on opposite sides of the column lookup: the `Node.id` arm resolves *before* it and, since R580's rework, rejects rather than resolving when a column of that name exists (`FieldBuilder.rejectShadowedIdColumn`), while the non-`id` arm fires only on a column *miss*. So deleting the non-`id` arm cannot flip a working field silently, it turns a column miss into the `unknownColumn` rejection rule 4 already prescribes.

R581 has since fixed the half of this that was actively breaking builds: the call sites holding an authoritative type name no longer route their decode resolution through the table. What is left is the inversion itself. As long as a directive-less `ID` can mean "node identity, resolved from the table", the schema does not say what it means, an author cannot tell the two readings apart by looking, and the table-first helper cannot be deleted.

The grammar below closes that by making the implicit reading available in exactly one place, where it cannot be ambiguous.

## The grammar

1. **`Node.id` is the only implicit nodeId.** The `id` field satisfying the `Node` interface on a type classified as a `NodeType`, however it got there, is obviously a nodeId and obviously of the enclosing type. The directive is redundant there (existing `id: ID! @nodeId` fixtures stay legal); `typeName:` is rejected there as contradiction-prone noise. **This rule is already live at the output-side `FieldBuilder` site** (see the intro). The antecedent's "however it got there" wording is there because nodehood can be inferred from `implements Node` plus catalog metadata, so it is not `@node` presence that makes rule 1 apply. Note that R580's rework already settled shadowing at this coordinate: the arm resolves the column itself and refuses when one exists, so rule 1's implicit reading applies only where no column of that name exists. What this item still owes rule 1 is one rejection, `typeName:` on that field, plus the generalisation in rules 2 and 6; the shadowing half is shipped at output and this item carries it to the other two coordinates.
2. **Bare `@nodeId` (directive without `typeName:`) means "node id, target inherited", and is legal at every coordinate.** It is the grammar's middle tier: the author states that the field *is* a node id without restating *which* node, and the target supplies the answer. On an output field the target is the enclosing type; on an input field or argument it is the consuming site's target, resolved as rule 6 describes. Stated over node-type membership rather than `@node` presence, for the same reason rule 1 is: since R580 a type can be a node by declaring `implements Node` over a metadata-carrying table, and phrasing this rule over the directive would reject bare `@nodeId` on exactly the types inference exists to enable. Where the inherited target is absent or ambiguous, `typeName:` is required (rule 3).

   **An earlier draft restricted this to output fields, which was a regression rather than a scoping choice.** The shipped `@nodeId` documentation (`graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls:473`) already commits to the wider contract: `typeName:` "is optional - if omitted, Graphitron will deduce the node type as follows: For object fields without the `@reference` directive, uses the containing type (if it is a node type); **For fields with the `@reference` directive or jOOQ record input fields, uses the node type with the same table (if unambiguous)**". Restricting bare `@nodeId` to output would migrate authors away from behaviour the directive's own docs tell them to rely on, and would cost ~198 in-tree rewrites for no gain. The inheritance is also forced rather than merely convenient: `@node` is declared `on OBJECT`, so it cannot be written on an input type at all, while `@table` is `on OBJECT | INPUT_OBJECT | INTERFACE` and is therefore only *optionally* explicit. Nodehood has no explicit input-side form to prefer over inheritance.
3. **`typeName:` is required wherever the inherited target cannot answer "which node".** That is: the target resolves to no node type, or to more than one (a table backing several `@node` types), or the coordinate crosses to a node other than its own target. Everywhere else rule 2's bare form suffices. An earlier draft stated this as "everywhere except output fields requires `typeName:`", which rule 2's generalisation retires; the residual requirement is about *ambiguity*, not about coordinate kind. A directive-less `ID` remains an ordinary scalar throughout (rule 4), except where rule 1 or rule 6 gives it a node reading.
4. **`ID` without `@nodeId` is an ordinary scalar.** With `@reference` it is a regular column-mapped field at the end of the reference path, validated against the matching column exactly like any other scalar routed through `@reference`; without `@reference` it is a regular column-mapped field on the enclosing type's table, same validation, unless it is `Node.id` (rule 1). No node interpretation, no rejection: `ID` in SDL means an opaque identifier, not necessarily a graphitron NodeId, and the column's own value is a legitimate id surface. The current `@reference` branch (plain single-column `InputField.ColumnBackedReferenceField` with `CallSiteExtraction.Direct()`) is already the right semantics for that shape and stays; what changes is the directive-less non-reference case, which today gets shim-synthesized node semantics from table facts instead of plain column mapping.
5. **Decode resolution becomes typeName-first wherever a type name exists**: `NodeIndex.byName.get(typeName).decodeMethod()` (the by-name view already exists on `record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName)`). `resolveDecodeHelperForTable` is deleted rather than guarded, but its three arms split rather than all dying, which rule 6's input coordinate forces: the typeId-suffix fallback goes (that is the orphan/shim case this item retires), the singleton arm survives as rule 6's table-derived resolution, and the multi-`@node`-per-table ambiguity arm stops returning `null` and becomes rule 6's rejection naming `typeName:` as the fix. `NodeIndex.forTable` is not at risk either way; it has four other callers in `FieldBuilder` independent of this item.
6. **An argument or input field naming its target's `Node.id` is that nodeId, implicitly.** This follows from rule 1 rather than diluting it. A target classified as a `NodeType` has exactly one field satisfying the `Node` interface, so a coordinate carrying that field's name is naming it. Numbered last because it was coined at the pass-3 gate; logically it sits next to rule 1. Boundaries, each load-bearing:
   * **The node comes from the target, never from a metadata read at the use site.** Where the target is a type (an argument on a field returning a `NodeType`) it resolves through `NodeIndex.forName`; where it is only a table (any input field, and every mutation coordinate) through `NodeIndex.forTable`, singleton or reject. Either way it is never `catalog.nodeIdMetadata`, which is what distinguishes rule 6 from the arm it replaces.
   * **If the name does not match, the directive is required** (rules 2 and 3): bare `@nodeId` where the target is unambiguous, `@nodeId(typeName: T)` where it is not. A mismatch means the coordinate is not that node's identity and nothing else in the SDL says which node it is. This is the clause that settles the plural case: `ids` does not match `id`, so `barsByIds(ids: [ID!] @lookupKey)` carries a directive. Do not fold `ids` onto `id`; a pluralisation heuristic is exactly the implicit magic this item removes.
   * **`ID`-typed only.** An `id: Int!` is not a node id and must not decode. Rules 3 and 4 make this implicit, but at a coordinate this subtle it is worth an explicit guard rather than an inference.
   * **Scalar and list alike**, unlike the output-side rule 1 arm, whose `isScalarId` guard is deliberate and must not be copied across. The motivating case is a list.
   * **`@field(name:)` defeats it**, pinning the column instead, mirroring the `!hasFieldDirective` guard at `FieldBuilder.java:7273`.
   * **It resolves ahead of column resolution, and a same-named column is a rejection rather than a contest it wins**, exactly as rule 1 is since R580's rework. See "One shadowing rule" below; the placement is not rule 6's own choice to make.

### One shadowing rule, at every coordinate

**A directive-less node-id reading that collides with a real column of the same name is an error. The author MUST disambiguate: `@field(name:)` selects the column, `@nodeId` selects the node.** This holds identically for output fields, input fields and arguments. The rule is deliberately uniform: shadowing is one question, and an author who learns the answer at one coordinate must not guess wrong at the next.

Rejecting rather than warning is what makes this the *only* coordinate in the item with no silent or semi-silent transition. Everywhere a node-id reading collides with a column, the two readings are both plausible and the SDL does not say which is meant, so guessing either way is the inversion this item exists to remove; a warning still guesses, it just narrates the guess. Erring instead puts the shadowing case on the same footing as rules 1-3: a working build becomes a failing one with a message naming both fixes.

**The output coordinate is already shipped; this item extends it to the other two.** R580's rework (`47d11a4`, in trunk) converted the shadowed-`id` warning to a rejection at the output coordinate, so the rule below is not a conversion this item performs but a shape it mirrors:

* **`FieldBuilder.rejectShadowedIdColumn` is the shipped implementation and the template.** It sits in the `Node.id` arm above the column lookup, resolves the column itself, and returns `Rejection.structural` naming both remedies. Its javadoc also carries the reason no structured repair is attached: both remedies are directive insertions after the field's type, and graphql-java records a type node's start location but not its end, so the insertion point for `id: ID!` is not derivable. The two siblings this item adds, at the input-field and argument coordinates, should read as the same rule at a different site rather than as independent verdicts, and should reuse its message shape verbatim.
* **`LintRule.NODE_ID_SHADOWS_COLUMN` is already retired**, removed rather than kept for the `Source.CLASSIFIER` census (`LintRule.ids()` is the namespace a consumer's `<lint>` config validates against, so a rule that can never fire is not configurable). Nothing here re-opens that; the `CLASSIFIER` partition still holds three rules.
* **The output-coordinate assertions are already rejections.** `NodeInferencePipelineTest` now carries a five-row "The shadowed `id` column" block over the metadata-carrying fixture tables. This item adds the input-field and argument rows; it does not flip the output ones.
* **The rejection is classifier-side, not validator-side.** This is the one rule in the item that fails the "Rejections, off by default" placement test below: it needs the catalog to answer "does a column of this name exist", so it cannot be an SDL-shape verdict in `GraphitronSchemaValidator`. It belongs where the column lookup already happens, with the validator mirroring it per `docs/architecture/explanation/development-principles.adoc` § "Rejections: validator mirrors classifier invariants". The shipped output arm is classifier-side for exactly this reason, so the placement is settled by precedent rather than argued here.

**R580 owns the decision; this item generalises it.** The shadowing question arose inside R580 (its "The shadowed `id` column" section), the rejection is stated there, and R580's rework delivered it: the doc drafts were rewritten from warning text to error text, the glossary gap became a glossary entry, `migrating-from-legacy.adoc` and `global-id.adoc` were corrected, and roughly 37 `graphitron/src/test` sites gained an explicit `@nodeId` or `@field`. None of that is this item's work and no `graphitron-sakila-example` site was affected. **If either item revisits this decision, both change.** What this item adds is the two further coordinates, input fields and arguments, on identical terms.

`baz` is the shape that can be exposed at the two new coordinates: its single column is literally `ID` **and** is also its node key, so a directive-less `id` on a baz-backed input resolves to the raw column today (`BuildContext.classifyInputField`'s column arm, `CallSiteExtraction.Direct`) and after this item would fail the build until the author states which reading they meant. `@field(name: "id")` restores today's behaviour verbatim. Elsewhere there is nothing to shadow: `film_actor` has `actor_id`, `film_id`, `last_update` and `bar` has `ID_1`, `ID_2`, `NAME`, so neither sakila's motivating field nor rule 6's own proof case can collide.

**Re-measured 2026-08-08: the in-tree fixture count at those two coordinates is currently zero.** Every baz-backed input in the suite already writes the directive (`MutationDmlNodeIdClassificationTest` and `MutationTableArgClassificationTest` each spell `DeleteBazInput.id` as bare `@nodeId` or `@nodeId(typeName: "Baz")`), which silences the rule, and no baz-backed argument exists. So the shadowing rejection currently costs no migration at all and all of its cases below are additions rather than edits. Re-measure at pickup; a single new baz-backed input written without a directive changes this.

An earlier draft of this item avoided the `baz` case by resolving rule 6 *after* column resolution, on the argument that the Relay contract attaches to the field on the type rather than to a same-named argument. That is true as far as it goes, but it bought one coordinate's stability at the price of two different shadowing answers in one grammar. Recorded so the placement is not re-litigated on the same reasoning.

   Rule 6 does not reach the Relay root fields. `node(id: ID!): Node` and `nodes(ids: [ID!]!)` return the `Node` *interface*, not a `NodeType`, and classify as `NodeResolve` coordinates with no backing table, so they never enter the table-typed argument path. Worth pinning in a test, since they are the most `id`-named arguments in every schema.

   One residue stays open rather than being closed by the rule, and should be named rather than papered over: an argument genuinely meaning something else but named `id`, on a field whose target is node-backed (`filmsByStore(id: ID): [Film!]!` if `film` were node-backed), decodes as the target's node. `@field(name:)` or a better argument name is the author's remedy. This is not anomalous, which is the point of the uniform shadowing rule: rule 1 carries the identical residue at output, where a field named `id` on a node type is the node id whatever the author meant. Today's arm decodes it too, on looser grounds, so rule 6 narrows the aperture rather than widening it.

   **Input-object fields are covered too**, on the same terms. They bind against the consuming site's target exactly as a scalar argument does, and carry the same `@field(name:)` override, so the coordinate is equally well-defined. But the target reached there is a *table*, not a type: no return type name is threaded into `BuildContext.classifyInputField(field, parentTypeName, resolvedTable, ...)` or `InputFieldResolver.resolve(String typeName, TableRef rt, ...)` (both `typeName` parameters are the *input* type's own name), and for a mutation input there is no node type in the SDL at all, as in `deleteFilmActorByNodeId(in: DeleteFilmActorByNodeIdInput!): ID @mutation(typeName: DELETE, table: "film_actor")`. So rule 6's input arm resolves through `NodeIndex.forTable`: a singleton is the node, and an ambiguous table requires `@nodeId(typeName: T)`.

   That is not a regression against R581, and the distinction is worth keeping because it will otherwise be re-litigated. R581's guarantee is that a site which *names* its type stops having that answer discarded by a table reverse-lookup. Rule 6's input arm holds no name to discard, so the reverse lookup is its only source rather than a thrown-away one, and ambiguity becomes a rejection naming the fix instead of a silent pick.

   Consequently **rule 6 does not reject `typeName:` the way rule 1 does.** Rule 1 rejects it because the enclosing type already answers "which node" unambiguously, making the argument noise. At a table-derived coordinate the argument is load-bearing whenever the table carries several node types, so it stays legal and is the disambiguator. Existing explicit spellings such as `DeleteFilmActorByNodeIdInput.id` and `FilmActorCompositeNodeIdFilter.ids` stay valid as written; there is no removal migration.

   **Rule 6's "required" half adds no new rejection.** It is descriptive, not a verdict: a non-matching argument name is rule 4 falling through to ordinary column resolution, which either resolves or produces the existing `unknownColumn` rejection. The rejection rule 6 *does* mint is the shadowing collision, one sibling per new coordinate, and that verdict belongs to "One shadowing rule" rather than to rule 6 itself. Both land in the flip-and-delete commit alongside the arm, since the shadowing rejection is classifier-side and cannot sit behind the validator's opt-in; the rejections commit still covers rules 1 and 2 only. The one discretionary addition is naming `@nodeId` as a candidate fix in that column-miss message when the enclosing field's target is node-backed.

## Phase 1: shipped as R581

The type-bearing callers (`BuildContext.buildInputNodeIdReference`, `NodeIdLeafResolver.resolve`) route through `BuildContext.resolveDecodeHelperForType`, and `resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the table's metadata. That was carved out and shipped ahead of this plan because a field report made it urgent: a second `@node` over one table broke every already-declared `@nodeId(typeName:)` leaf on that table. `resolveDecodeHelperForTable` survives in the synthesis shims and in the *directive-less* implicit scalar-`ID` argument arm of `FieldBuilder.classifyArgument` (`FieldBuilder.java:1580-1609`), which holds no type name to key on. Note that arm is reached only when the argument carries **no** `@nodeId`: a bare `@nodeId` argument is routed at `FieldBuilder.java:1508` through `NodeIdLeafResolver.resolve`, which reaches `inferTypeName`, not `resolveDecodeHelperForTable`. An earlier draft called this "the bare-`@nodeId` argument arm", which pointed an implementer at the wrong block. That arm is now this item's (see the intro and rule 6), so nothing outside it keeps `resolveDecodeHelperForTable` alive.

What remains for this item is the grammar itself: rules 1-4 and 6 as build behavior, and the deletion of the table-first helper.

## Implementation

Ordering is a real seam here, so the sections below land as three commits, each independently green. The rejections must exist and be quiet before they can be turned on, and the fixture migration must be complete before the shims can go.

### Rejections, off by default

Rules 1 and 2 are the rejections this commit carries. An earlier draft counted three here and folded rule 3 in; rule 3 turned out not to be a rejection at all once bare `@nodeId` generalised (see its bullet below), so re-read that bullet before sizing this commit. Both go in `GraphitronSchemaValidator`, not in the classifier: they are SDL-shape verdicts, they need no catalog or index state, and the validator's report already carries a source location per error, which is what makes the diagnostic actionable in the LSP. (Note the neighbour to reason from is *not* `validateNodeTypeIdUniqueness`, which lives in `TypeBuilder` and runs as part of classification. The validator's own SDL-shape precedent is `validateConnectionType`, which reads `type.schemaType()` and falls back to the type location when the AST node is absent.)

The facts these rejections need are all reachable there. `GraphitronSchemaValidator.validate` takes the classified `GraphitronSchema`; `GraphitronType.InputType` and the object types expose `schemaType()` for the directive read, and `GraphitronField` carries `definition` for the argument walk. "Is the enclosing type a node type" is answerable straight off the classified model (a `GraphitronType.NodeType` arm), which is the post-classification form of the predicate `NodeDeclaration.isNodeType` answers for the pre-classification consumers; do not re-read `@node` at either site.

* Rule 1: `typeName:` on the `id` field satisfying `Node` on a node type. The named type either agrees with the enclosing type (redundant) or contradicts it (a bug the author cannot have meant), so the argument is rejected rather than checked.
* Rule 2: bare `@nodeId` on an output field of a type that is not a node type. **This one already rejects**, structurally, in `FieldBuilder.classifyChildFieldOnTableType`: "@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)", a message that became true as written once R580 landed the metadata path. So the work here is not a new verdict, it is giving the existing one a source location and a message that names `typeName:` as the fix. Decide explicitly whether the classifier rejection stays as the mirror that "Rejections: validator mirrors classifier invariants" prescribes (it should) or moves; and note the classifier arm is reached only for a `@table`-backed parent, so a non-table-backed parent is a separate coordinate to check.
* Rule 3 is **not** a third rejection, and an earlier draft had this backwards. It read "bare `@nodeId` on an input field or an argument" as a new verdict subsuming the two friendly diagnostics `BARE_NODE_ID_NO_OBJECT_TYPE` and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES`, on the grounds that it would "fire on the unique-match case too, which is the whole point". Under rule 2's generalisation the unique-match case is exactly what bare `@nodeId` is *for*, so `NodeIdLeafResolver.inferTypeName`'s table-lookup arm is kept rather than deleted, and those two diagnostics are its permanent absence and ambiguity rejections rather than casualties. What changes is the domain it resolves over: re-point it from `findGraphQLTypesForTable` (all `@table` types) to `NodeIndex.forTable` (node types only), so it answers "which node backs this table" instead of "which object type maps to it". That re-pointing is the substance of rule 3's implementation, and it belongs with rule 6's arm in the flip-and-delete commit rather than behind the rejection opt-in. It carries a small tail: both diagnostics describe the domain they used to resolve over ("no @table-annotated object type maps to table", "multiple object types map to table"), and the two `NodeIdPipelineTest` cases pin those substrings, so the message text and the assertions move with the domain. Neither fixture changes verdict under the narrowing: `BARE_NODE_ID_NO_OBJECT_TYPE`'s table has no object type at all, and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES`'s two types are both `@node`, so each still lands on the arm it was written for.

  So the rejections commit covers rules 1 and 2 only. Keep the section's name or rename it; do not let the count "three distinct rejections" survive unexamined into the implementation.

Land them behind a single build-level opt-in so the flip is one switch rather than three, and so the fixture migration below can proceed against a build that fails loudly for a session and quietly for everyone else. The opt-in mechanism is the implementer's call; a Mojo parameter is the obvious candidate, but if that adds a user-facing surface we would then have to retire, a package-private constant flipped in the fixture-migration commit is the cheaper answer. Decide before writing the rejection code, and say which in the commit.

One constraint on that choice, because it decides where the rejection tests can live: a `static final` constant defaulting to false cannot be toggled per test, so the per-rule rejection cases cannot land in the same commit as the rejection code, and that commit ships branches nothing exercises. A test-settable seam (a package-private non-final field, or a value threaded through the build context) keeps rejection code and its tests in one commit, which is what "each independently green" should mean here. If the constant wins anyway, say in the commit message that the cases arrive with the fixture migration, so the gap is a recorded decision rather than an oversight.

### Fixture migration

Re-measured against the post-R580 tree on 2026-08-04 (re-measure again at pickup; the counts move, and a single-line grep undercounts because several fixture declarations span lines):

* `graphitron-sakila-example`: no migration needed, but for two reasons rather than one. All 16 bare `@nodeId` sites across the five `.graphqls` files are `Node.id` on a node type, which rules 1 and 2 keep legal. **The claim in an earlier draft of this item that the sakila INSERT-input fixture writes the bare form on an input is stale and was the main reason this plan looked more expensive than it is**; `CreateKeyedNodeInput` does write `@nodeId(typeName: "KeyedNode")`. Separately, `schema.graphqls:163` carries the one directive-*less* site, `filmActorByNodeId(id: [ID!]! @lookupKey): [FilmActor!]!`, whose argument routes through the arm rule 6 replaces. It survives untouched precisely because rule 6 covers it: the argument is named `id`, `FilmActor` is a node type, so the node resolves off the return type. Under rule 3 alone it would have needed `@nodeId(typeName: "FilmActor")` added, and since `film_actor` has no `id` column it would otherwise have become an `unknownColumn` rejection. This is the coordinate that motivated rule 6.
* Sakila's `FilmActor` in `schema.graphqls` is now the *fully* implicit rule-1 shape: `implements Node @table(name: "film_actor")` with a bare `id: ID!`, no `@node` and no `@nodeId`. `GraphQLQueryTest`'s `filmActorByNodeId` round-trip is therefore this item's execution-tier proof that rule 1 holds end to end, and it must stay green through the flip. The declared spelling over the same table is still covered by `federated-schema.graphqls`.
* `graphitron/src/test`: **the 23 input-field and 2 argument bare-`@nodeId` sites no longer migrate at all.** Rule 2's generalisation makes bare `@nodeId` legal at those coordinates, inheriting the target, so they stay as written; only sites whose inherited target is absent or ambiguous need `typeName:`, and those are already the deliberately-failing fixtures that pin `BARE_NODE_ID_NO_OBJECT_TYPE` and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES`. The ~157 output-field sites are `Node.id`-shaped and stay as they are. This is the largest single change the pass-3 discussion made to the item's cost: the fixture migration was the bulk of the work and is now close to empty. **Re-measure at pickup rather than trusting this sentence**, and in particular re-check each of the 23 for an ambiguous target, since a shared table is what turns a legal bare form into a required `typeName:`.
* **Rule 1's `typeName:` rejection has no in-tree migration cost either, measured 2026-08-08.** No site in the tree writes `@nodeId(typeName:)` on the `id` field of a type that is a node, by declaration or by inference; every explicit spelling sits on an input field or an argument, where rule 6 keeps it legal (`DeleteFilmActorByNodeIdInput`, `CreateKeyedNodeInput`, `UpdateEmailReplyInput`, `ModifyFilmActorRecordInput`, `FilmActorSingularNodeIdFilter` in sakila, and the `graphitron/src/test` sites alongside them). The census above counts the bare form, so this is the half it does not otherwise cover. Re-measure with the same predicate at pickup.
* Of the two argument sites, `NodeIdPipelineTest`'s `barByIds(ids: [ID!]! @nodeId)` stays legal under rule 2 (`bar` carries one node type). `RejectNonIdNodeIdPipelineTest`'s `search(term: String @nodeId)` is a non-`ID` rejection fixture whose verdict must not change; check which rejection wins and pin the order deliberately, since rule 2 widening where bare `@nodeId` is *legal* must not weaken the non-`ID` rejection.

Rule 6's own verdict cost sits in `NodeIdPipelineTest.LookupKeyCase`, whose three cases all reach the replaced arm with no `@nodeId` anywhere in the fixture. Behaviour decides each, not emit equality:

* `SCALAR_NODEID_LOOKUP_COMPOSITE_PK` (`barById(id: ID @lookupKey): Bar`, `Bar implements Node @table @node`): argument named `id`, return type is a node type, so rule 6 covers it and the case stays green unchanged. This is rule 6's pipeline-tier proof.
* `LIST_NODEID_LOOKUP_COMPOSITE_PK` (`barsByIds(ids: [ID!] @lookupKey)`): the argument is plural, so rule 6's name match misses and the directive is required. Migrate to `@nodeId(typeName: "Bar")` rather than renaming the argument; the case exists to cover the list shape, and the explicit spelling keeps it covering that.
* `SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` (`bar(id: ID): Bar`, `type Bar @table(name: "bar")` with `id: ID! @field(name: "ID_1")`): `Bar` is *not* a node type, so neither rule 6 nor the old arm's premise applies and today's "composite-PK NodeType is only wired for @lookupKey" verdict is no longer reachable by this fixture. Re-derive the new verdict against the fixture catalog rather than assuming which rejection replaces it, and check whether the deferred-composite gap it was pinning still has a fixture that reaches it. If not, that coverage needs re-siting rather than deleting.

Rule 4 has no fixture-migration cost of its own but does have a verdict cost: the cases that pin shim-synthesized node semantics on a directive-less `ID` are the ones whose expected outcome inverts. `NodeIdPipelineTest.InputCase.IMPLICIT_ID`, `EXPLICIT_PERSON_ID`, `R377_ORPHAN_INPUT_TYPEID_FALLBACK` and `R377_MULTI_NODE_REJECTS` all encode the old semantics; each either flips to plain column mapping or moves to a `@nodeId(typeName:)` spelling that keeps testing what it was there to test. Read each one's stated intent before rewriting it, and where the intent was the shim itself, delete rather than translate.

### Flip and delete

Turn the opt-in on by default and delete it, then delete in one commit:

* The surviving synthesis shims: `FieldBuilder`'s output-side bare-scalar-`ID`-on-a-`NodeType` branch, now narrowed to fields *other* than `Node.id` (the `Node.id` arm above it is permanent and must be left alone), and `BuildContext.classifyInputField`'s two input-side sites (the qualifier-reverse-map arm feeding `buildInputNodeIdReference`, and the bare same-table arm guarded by `catalog.nodeIdMetadata`).
* The directive-less implicit scalar-`ID` argument arm (`FieldBuilder.java:1580-1609`), **replaced rather than merely deleted**: rule 6's arm takes its place, keyed on the argument name matching the return `NodeType`'s `Node.id` field and resolving the decode through `NodeIndex.forName`. Shape it as the mirror of the `Node.id` output half at `FieldBuilder.java:7270`: it sits **ahead** of the argument's column resolution at `FieldBuilder.java:1664` (`argString(arg, DIR_FIELD, ARG_NAME).orElse(name)`) and, where a column of that name exists, rejects with both silencers named rather than resolving, per "One shadowing rule". The arm it replaces sits on the same side of that lookup, so the placement is not itself a change; what narrows is the predicate, from "any `ID` argument whose table carries metadata" to "an `ID` argument named for the target's `Node.id`". There is no existing notion of an argument or input field mapping to a *field* of the target type, so that name-match predicate is new code; today's binding is name-to-column only.
* `BuildContext.resolveDecodeHelperForTable` with them, which leaves `resolveDecodeHelperForType`'s fallback arm dead; collapse that method to the `NodeIndex.forName` lookup and let it return the `Optional` rather than `null`.
* `BuildContext.findGraphQLTypeForTable` (the singular, shim-only helper). **`NodeIdLeafResolver.inferTypeName`'s table-lookup arm is kept, not deleted**, per rule 3 above: it is generalized bare `@nodeId`'s resolution, re-pointed from `findGraphQLTypesForTable` onto `NodeIndex.forTable`. `findGraphQLTypesForTable` (plural) has exactly two callers today, the singular helper and that arm; the singular helper goes and the arm stops calling it, so the plural helper goes dead in the same motion and should be deleted with them.
* The three deprecation WARNs at those sites: `FieldBuilder`'s "synthesizes an `@nodeId` carrier without the directive", and `BuildContext`'s two (`ID_REF_SHIM_LOGGER`, `NODE_ID_SHIM_LOGGER`). **`FieldBuilder.rejectShadowedIdColumn` must be left alone.** It sits in the same method, on the `Node.id` arm immediately above the surviving shim arm, so a sweep of "the diagnostics at this site" would wrongly take it out. It is not a deprecation and not a warning: it is the shipped output-coordinate half of "One shadowing rule", and this item's job is to grow it two siblings at the input and argument coordinates, not to touch it.
* `IdReferenceShimWarnFormatTest`, which pins the qualifier-arm WARN's text. It is the only warn-format test in scope. **`AsConnectionSameTableWarnFormatTest` is not**, despite the similar name: it covers the `@asConnection` same-table warning and has nothing to do with these shims. No test asserts the `FieldBuilder` WARN's text at all, so that one needs no test change.

R27 (`retire-synthesis-shims`) is the item that has carried the shim deletion; it should be discarded into this one rather than left to delete an empty set. **Done: R27 was discarded on 2026-08-09**, with its gate and its WARN-to-error flip both dissolved by this item and its migration recipe living on in the user manual. See the changelog entry.

## Tests

Per-rule rejection cases in the validator's own test class, each pinning the source location as well as the message; the LSP reads both. Beyond that the work is mostly the fixture migration above, and the honest test of this change is that the migrated suite stays green with the shims gone.

Two cases worth adding that do not exist today, one per silent transition hazard named below, since those are the shapes where a semantic flip is possible rather than a build error:

* An input field whose `ID` column name collides with a node-bearing FK qualifier, under rule 4, pinning that it maps to the plain column rather than decoding.
* An `ID` argument on a field returning a node type, named for a real column rather than for `Node.id`, pinning that it maps to that column rather than decoding. Its sibling (an argument named `id` on the same shape) is `LookupKeyCase.SCALAR_NODEID_LOOKUP_COMPOSITE_PK`, which already exists, so the pair reads as one contrast.

The shadowing rule needs a case per coordinate, and `baz` is the fixture for it: a node-backed table whose single column is literally `ID` and is also its node key. At **each** of the three coordinates (output field, input field, argument) pin the same triple: a directive-less `id` **rejects** with a message naming both fixes, `@field(name: "id")` resolves to the raw column, and `@nodeId` resolves to the node. Asserting all three coordinates together is what pins the uniformity; a test covering only one will not catch a later coordinate-specific divergence, which is the failure mode this rule exists to prevent. The output coordinate is already covered by `NodeInferencePipelineTest`'s five-row "The shadowed `id` column" block, so this item adds the input-field and argument rows and sites them so the three read as one rule; extending that block is the obvious home unless the two new rows want the input-side fixture context instead.

Assert the message text, not just the rejection. It is the sole instruction an author gets at a build break they did not previously have, and the two silencers are not guessable from the failure alone.

Pin the Relay root fields too: `node(id: ID!): Node` and `nodes(ids: [ID!]!)` must stay `NodeResolve` coordinates untouched by rule 6. They are the most `id`-named arguments in any schema, and a regression there would be both wide and quiet.

Rule 6's input coordinate needs three of its own, since it resolves table-derived rather than type-derived and none of the argument cases exercise that path:

* A directive-less `id` field on an input consumed by a field returning a node type, pinning that it decodes.
* The same shape on a *mutation* input, where the SDL names only `@mutation(table:)` and there is no node type at the coordinate at all. `deleteFilmActorByNodeId` is the live shape to model it on.
* A directive-less `id` field on an input whose target table carries **two** `@node` types, pinning the ambiguity rejection and that its message names `typeName:` as the fix. This is the case that keeps rule 6 honest about not being an R581 regression, so it should assert the message, not just the rejection.

## Consumer migration

Per user confirmation on 2026-07-13, no consumer relies on the shim behavior today, so R27's written gate (sis migrated, plus one external-consumer release window) is more conservative than reality and this does not need to wait it out. The item then asked for that confirmation to be re-taken before the flip, on the grounds that it predated the utdanningsregisteret federation work.

**Re-confirmed by the user on 2026-08-09, after the flip landed and before any release carries it.** sis is the only touched subgraph, and the user is doing that migration themselves. So the two silent arms below need no WARN staging, and the utdanningsregisteret concern is answered: it is not a consumer of either arm. Nothing in this item now waits on a consumer.

(The re-confirmation instruction also claimed the original was "over a year old", while dating it 2026-07-13, under a month before the re-confirmation. One of the two was wrong. Recorded so a later reader does not reconstruct a staleness argument from the arithmetic; the substantive reason to re-ask was the federation work, not elapsed time.)

Two silent changes remain in this item's set, both on directive-less coordinates that sit *ahead* of column resolution. Every other rule turns a working build into a failing one with a message naming the fix, and the surviving output-side arm fires only on a column miss (see the intro), so deleting it rejects rather than flips.

The first is a legacy column-named `ID` field (`customerId: ID`) on an *input* type flipping from shim-synthesized node decode to raw column mapping, at the qualifier-reverse-map arm. That arm runs ahead of the column lookup, so a field whose name hits both the qualifier map and a real column changes meaning with no build error.

The second is an `ID` **argument or input field** whose target is node-backed and whose name does *not* match the target's `Node.id`. Today's arm decodes it, consulting only `catalog.nodeIdMetadata` and the `ID` type and never the name; under rule 6's narrower predicate it becomes ordinary column mapping. The narrowing is the point, but it is silent, so it belongs in the same re-confirmation as the first.

A third change is *not* in this set and is worth stating, because an earlier draft of this item had it here as a warned wire-format flip. A coordinate whose name matches `Node.id` over a table carrying a column of that name **fails the build** rather than flipping, per "One shadowing rule". `baz` is the shape, though no in-tree fixture currently hits it at either new coordinate. That is a consumer-visible break, but a loud one naming both fixes, and `@field(name: "id")` restores the prior behaviour verbatim, so it needs no staging and no wire-format re-confirmation. Erring here is what keeps the consumer-facing exposure of this item down to the two arms above.

If re-confirmation turns up any consumer at all, stage *both* silent arms behind a WARN for one release before they change semantics, and leave rules 1 and 2 as errors immediately since they cannot flip anything silently.

Rule 1's own change at that coordinate is not this item's exposure at all. It shipped with R580 as a warned flip and R580's own rework then replaced the flip with a rejection, so at the output coordinate the author already states the reading. What "One shadowing rule" adds here is that the same answer holds at the input-field and argument coordinates, which is a build break rather than a flip and therefore needs no staging.

R34 (`nodeid-migration-quickfix`) ships LSP quick fixes that derive the `typeName:` value from the shim's own facts. It is Backlog and unstarted, and this item does not depend on it: the migration is ~25 fixture sites in-tree and, per the above, nothing out of tree. If a consumer does turn up, R34 becomes the humane path and the dependency turns real.

## Spec review notes

**Pass 1, 2026-08-04 (revisions requested).** Independent Spec -> Ready review. The grammar read
well and rules 3 and 5 checked out against the tree. Four blocking findings: a collision with R580
on three points (rule 1's antecedent, ownership of the output-side `FieldBuilder` site, and rule
1's precedence against column resolution), plus three code claims that would have sent an
implementer to the wrong place (`validateNodeTypeIdUniqueness`'s class, rule 2's "today" behaviour,
and the warn-format test names).

**Pass 2, 2026-08-04 (revisions applied by the reviewer).** R580 shipped in the interim and its
reconciliation commit fixed the rule 1 half of finding 1. This pass folded in the rest and
re-measured everything against the post-R580 tree:

* Finished the reconciliation R580 started. Rule 1's antecedent had been restated over node-type
  membership; rule 2 and its rejection bullet still read `@node`, which after inference would
  reject bare `@nodeId` on exactly the types R580 exists to enable. Both now read over node-type
  membership.
* Made the shim count internally consistent. The intro said two sites, the deletion list named
  three. It is three: two input-side arms plus the non-`id` half of the output-side site. Added the
  precedence asymmetry between the two output halves, which is what makes the surviving arm's
  deletion loud rather than silent.
* Repointed the validator placement off `validateNodeTypeIdUniqueness` (it is in `TypeBuilder`, and
  runs during classification) onto `validateConnectionType`, and recorded that the facts rules 1-3
  need are reachable from the classified model, with node-type membership read off the model rather
  than by re-reading the directive.
* Corrected rule 2's premise: it already rejects in `FieldBuilder.classifyChildFieldOnTableType`,
  and R580 made that rejection's message true as written. The work is a source location and a
  better message, not a new verdict.
* Corrected the WARN and test claims, and fenced off `warnShadowedIdColumn` /
  `LintRule.NODE_ID_SHADOWS_COLUMN`, which R580 added immediately above the surviving shim arm and
  which a sweep of "the warnings at this site" would delete.
* Re-anchored the silent-change analysis on the input-side qualifier arm, since rule 1's own silent
  change already shipped with R580 behind a warning.
* Re-measured the census (16 sakila sites, 23 input-field, 2 argument, ~157 output-field) and
  recorded sakila's `FilmActor` as the new fully-implicit rule-1 execution-tier fixture. The
  earlier suspicion that the input-field count read low was wrong; 23 is right, and the first scan
  that suggested otherwise was matching prose.
* Added the testability constraint on the opt-in mechanism the spec delegates to the implementer.

No design disagreement between this item and R580 survives the reconciliation: R580 owns type-level
nodehood and the `Node.id` carrier, this item owns field-level node semantics everywhere else, and
the two now agree on the vocabulary. The reviewer who takes the next pass is looking at a plan whose
claims were checked against the tree at this commit, not at an open question list.

Because this pass edited the item, the Spec -> Ready sign-off needs a third session: not the
original author, and not this reviewer.

**Pass 3, 2026-08-06 (revisions requested).** Independent Spec -> Ready review by a third session.
Everything pass 2 re-measured still holds: the sakila census is exactly 16 bare `@nodeId` sites
across the five `.graphqls` files and every one is `Node.id`-shaped; the output-side precedence
asymmetry is real (`FieldBuilder.java:7270` resolves `Node.id` before the column lookup at 7279,
the non-`id` shim at 7286 fires only on a column miss); the input-side qualifier arm does run ahead
of its column lookup (`BuildContext.java:2617-2665` vs 2669) and the same-table arm fires on a miss
(2692); rule 2's existing rejection message is verbatim as quoted at `FieldBuilder.java:7210`;
`validateNodeTypeIdUniqueness` is in `TypeBuilder` and `validateConnectionType` in
`GraphitronSchemaValidator`; `warnShadowedIdColumn` / `LintRule.NODE_ID_SHADOWS_COLUMN` sit exactly
where the fence says; `IdReferenceShimWarnFormatTest` and `AsConnectionSameTableWarnFormatTest` are
correctly characterised; the `NodeIndex` record shape, `forName`, the two `NodeIdLeafResolver`
diagnostics, all four named `NodeIdPipelineTest.InputCase` members, both argument fixtures, and
sakila's `FilmActor` / `CreateKeyedNodeInput` / `GraphQLQueryTest.filmActorByNodeId` all exist as
named. Two straight factual corrections were applied above (the Phase 1 arm misnomer, and
`findGraphQLTypesForTable`'s caller set).

One blocking finding remains, and it is a scope decision the author has to make rather than
something a reviewer should settle unilaterally.

**The directive-less implicit scalar-`ID` argument arm is unresolved, and the plan is internally
inconsistent without it.** `FieldBuilder.java:1580-1609` synthesises node-decode semantics for an
`ID`-typed argument carrying no `@nodeId` at all, keyed off `catalog.nodeIdMetadata(rt.tableName())`,
and calls `resolveDecodeHelperForTable`. It is a fourth implicit-nodeId coordinate: it fires no
deprecation WARN, and it runs ahead of column-name resolution (the `list && arity-1 && !@lookupKey`
guard at 1594 explicitly falls through to it). Three consequences:

* **The deletion commit will not compile as written.** Rule 5 deletes `resolveDecodeHelperForTable`
  outright, but this arm is a live caller that the "Flip and delete" list never mentions. Either
  R473 absorbs the arm (R273 explicitly offers that: "may collapse into R473's implementation if the
  reviewer prefers one motion") or the helper cannot be deleted in this item, which would gut rule
  5. Pick one and write it down.
* **"`graphitron-sakila-example`: no migration needed" is false.**
  `schema.graphqls:163`, `filmActorByNodeId(id: [ID!]! @lookupKey): [FilmActor!]!`, carries no
  `@nodeId` on the argument. `film_actor` publishes `__NODE_TYPE_ID`/`__NODE_KEY_COLUMNS` with a
  two-column key, so the argument routes straight through this arm to a `ThrowOnMismatch` decode.
  Under rule 3 as stated ("input fields, arguments, anything crossing to another type") it needs
  `@nodeId(typeName: "FilmActor")` added, and `film_actor` has no `id` column, so leaving it alone
  turns the field into an `unknownColumn` rejection. This is the same `GraphQLQueryTest` round-trip
  the fixture-migration section names as rule 1's execution-tier proof that "must stay green through
  the flip", so the item currently both depends on it and would break it.
* **The silent-change analysis is incomplete.** "It is the only silent change left in this item's
  set", said of the input-side qualifier arm, does not hold: because this arm also precedes column
  resolution, a directive-less `ID` argument whose name matches a real column flips from decode to
  plain column mapping with no build error. Whether that matters depends on the scope call above.

Coverage the census does not count, for whichever item takes the arm: `NodeIdPipelineTest`'s
`LookupKeyCase.SCALAR_NODEID_LOOKUP_COMPOSITE_PK` (`barById(id: ID @lookupKey)`),
`LIST_NODEID_LOOKUP_COMPOSITE_PK` (`barsByIds(ids: [ID!] @lookupKey)`) and
`SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` (`bar(id: ID)`) all reach it with no `@nodeId`
anywhere in the fixture, and all three have verdicts rule 4 inverts. The fixture-migration section
counts only sites that carry the directive, so the directive-less inversion set is currently
measured at four `InputCase` members and nothing else.

Two non-blocking notes. R580 is `status: Ready`, not Done: it was bounced from In Review over a
single user-manual finding, so its *code* is in trunk and every claim this item makes about live
behaviour was verified directly against the tree; "R580 shipped" is loose but not misleading. And
rule 5 writes `NodeIndex.byName.get(typeName)` where the accessor is `forName`; the deletion bullet
already uses the right name.

Status stays Spec. This pass edited the item, so the sign-off needs a session that is neither the
original author, nor the pass-2 reviewer, nor this one.

**Forks settled with the author, 2026-08-06.** The pass-3 finding was a scope question, not a
defect, and the author settled all three in discussion. Recorded here because the resolution changed
the grammar rather than just the plan:

* **The argument arm is absorbed** from R273, which narrows to its unrelated second deliverable
  (R265's deferred compile-tier guard). The case is the item's own thesis: while a directive-less
  `ID` can mean "node identity, resolved from the table", the table-first helper cannot be deleted,
  and that sentence was true of four sites rather than three.
* **The arm's retirement is a deletion, not a rejection**, so it rides the flip-and-delete commit
  rather than the rejections commit. Under rule 4 a directive-less `ID` argument is an ordinary
  scalar, so removing the arm lets column resolution take over; no new verdict is minted. Its hazard
  profile therefore matches the qualifier arm (silent, because it precedes column resolution) rather
  than rules 1-3 (loud).
* **Rule 6 was coined rather than widening rule 1.** The reviewer's first proposal was to migrate
  sakila's `filmActorByNodeId` to an explicit `@nodeId(typeName: "FilmActor")` and accept a
  fixture-migration cost, with byte-identical generated source as the acceptance proof. The author
  rejected both halves: emit equality is a change-detector rather than a behaviour test, and the
  argument does not need the directive at all, because the field returns `FilmActor`, `FilmActor`
  implements `Node`, and the argument is named for that interface's `id`. Rule 6 states that as its
  own rule following from rule 1, which removes the sakila migration entirely and dissolves the
  plural-argument wrinkle into the rule's own mismatch clause instead of a pluralisation heuristic.

Rule 6 is numbered last deliberately. It belongs next to rule 1 by reading order, but the
Implementation section groups "rules 1-3 are three distinct rejections" and the pass-1 and pass-2
notes above cite rule numbers as a historical record; renumbering would silently falsify them.

**Rule 6 extended to input-object fields, 2026-08-06.** The reviewer's first draft carved input
fields out on the grounds that an input type carries no return type, so "which node" would have to
come from the consuming field's table. The author corrected the premise: input objects bind against
the target table exactly as arguments do, so the coordinate is equally well-defined and an input
field named for `Node.id` matches. What the correction surfaced is that the input coordinate is
*table*-derived rather than type-derived (no return type name is threaded into
`classifyInputField` / `InputFieldResolver.resolve`, and a mutation input has no node type in its
SDL at all), which is why rule 5's "deleted rather than guarded" had to be restated as a three-way
split rather than a wholesale deletion, and why rule 6 does not inherit rule 1's `typeName:`
rejection.

**Bare `@nodeId` generalized to every coordinate, 2026-08-06.** The author asked when a bare
`@nodeId` on output makes sense and whether support should be dropped, then answered it: the
output-only limitation is an artifact of earlier scoping, and bare `@nodeId` should inherit its
target the same way rule 6 does, because `@node` is `on OBJECT` and cannot be written on an input at
all. Checking the directive definition settled it decisively in that direction. The shipped
`@nodeId` documentation already promises table-based inference on input fields, so rule 2's
output-only restriction was a regression against a documented contract rather than a scoping choice,
and the migration it prescribed would have moved authors off behaviour the docs tell them to rely
on. Dropping bare `@nodeId` was the other option considered and rejected: ~198 in-tree sites carry
it, and removing it would leave the grammar with only an implicit tier and a fully-explicit
`typeName:` tier, with no way to say "node id, target inherited".

Three knock-ons, each reversing something the item previously said. The 23 input-field and 2
argument migrations drop to zero, which was the bulk of the fixture work.
`BARE_NODE_ID_NO_OBJECT_TYPE` and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES` are kept as bare
`@nodeId`'s permanent absence and ambiguity rejections, rather than being subsumed by a new
rejection as an earlier draft had it. And `NodeIdLeafResolver.inferTypeName`'s table-lookup arm is
kept and re-pointed onto `NodeIndex.forTable` rather than deleted, which removes rule 3 from the
rejections commit entirely.

**Shadowing unified across coordinates, 2026-08-06.** The author flagged that a name-matching rule
layered over a binding mechanism that is itself name-to-column risks opening a can of worms, and the
fixture catalog produced one: `baz`'s single column is literally `ID` and is also its node key, so a
directive-less `id` on a baz-backed input maps the raw column today and decodes after this item.

The reviewer's first remedy was to resolve rule 6 *after* column resolution, so the column would keep
winning at that coordinate, reasoning that the Relay contract attaches to the field on the type
rather than to an argument sharing its name. The author overruled it: shadowing is one question, the
answer must not depend on coordinate, and the author disambiguates with `@field` or `@nodeId`. That
is the better trade. The reviewer's placement bought one coordinate's stability at the price of two
shadowing dialects in one grammar, and R580 had already accepted the identical warned wire-format
flip at output. Rule 6 therefore resolves ahead of the column lookup like rule 1, the
`NODE_ID_SHADOWS_COLUMN` symmetry the reviewer had retracted is restored, and `@field(name:)` is an
explicit guard again rather than a side effect of placement. The `baz` flip is real, warned, and
revertible with `@field(name: "id")`; it is recorded under Consumer migration rather than avoided.

**Shadowing made an error rather than a warning, 2026-08-06.** The author's follow-up: the user MUST
disambiguate. This resolves the one hazard the reviewer had said should hold the gate, the `baz`
wire-format flip, by removing the flip rather than warning about it, and it makes the shadowing case
consistent with the rest of the item's posture (a working build fails with a message naming the
fix). It is also the item's only place where a shipped diagnostic gets *stricter*: R580's
`NODE_ID_SHADOWS_COLUMN` warning becomes a rejection, its two assertions flip, the `LintRule` entry
retires, and R580's undelivered user-manual drafts, which describe a warning an author may ignore,
have to be rewritten before either item lands. R580 is still at `Ready` over a docs finding, so that
reconciliation is live rather than retrospective. One placement consequence: this is the single rule
in the item that cannot sit in `GraphitronSchemaValidator`, since answering "is there a column of
this name" needs the catalog, so it stays classifier-side with the validator mirroring it.

The unification also settled a question left open one round earlier. With shadowing uniform, the
case for dropping rule 6 in favour of generalized bare `@nodeId` weakens: of the four costs the
reviewer had charged against it, the `baz` hazard becomes a warning, the placement subtlety
dissolves because there is now one placement, and the `filmsByStore` residue stops being anomalous
because rule 1 carries the same one at output. Only "the name-match predicate is new code" survives,
which does not justify dropping a rule. Rule 6 stays.

**Pass 4, 2026-08-08 (revisions applied by the reviewer).** Independent Spec -> Ready review by a
fourth session. The grammar itself needed nothing: rules 1-6 are coherent, the three-commit ordering
holds, and every other symbol the item names was checked against the tree and exists as named. One
blocking finding, applied above rather than handed back, because it was a reconciliation against
shipped code rather than a design question.

**R580's rework overtook the "One shadowing rule" section.** `47d11a4` is in trunk and already
delivered the output-coordinate half: `warnShadowedIdColumn` became `rejectShadowedIdColumn`
returning `Rejection.structural`, `LintRule.NODE_ID_SHADOWS_COLUMN` was removed outright rather than
kept for the `Source.CLASSIFIER` census, `NodeInferencePipelineTest`'s two lint-finding assertions
became a five-row rejection block, 37 fixture sites gained an explicit `@nodeId` or `@field`, and
the user-manual drafts (`node.adoc`, `nodeId.adoc`, `global-id.adoc`, `migrating-from-legacy.adoc`,
the diagnostics glossary) were rewritten for the rejection. The item still framed all of that as its
own future work, and three of the symbols it instructed an implementer to edit
(`warnShadowedIdColumn`, `LintRule.NODE_ID_SHADOWS_COLUMN`, `BuildWarning.LintFinding` at that site)
now grep to zero. Corrected at seven sites: the intro's precedence note, rule 1's "two rejections",
the shadowing section's four bullets and its R580 paragraph, the exposure paragraph, the flip-and-delete
fence, the Tests section, and both Consumer-migration paragraphs. The net effect is that this item's
shadowing scope shrinks to two coordinates and gains a shipped template to mirror, which makes it
cheaper and better specified rather than either.

Four non-blocking notes, left for the implementer:

* **Line numbers have drifted and should not be trusted.** Every named block exists, but
  `FieldBuilder.java:1580-1609` is now ~1583-1611, `:1508` is 1511, `:1594` is 1597, `:1664` is 1667,
  `:7210` is 7231, and the `Node.id` arm cited at `:7270`/`:7273`/`:7279`/`:7286` sits at ~7295-7306.
  `BuildContext.java`'s three citations (`2617-2665`, `2669`, `2692`) have moved ~80 lines down.
  `LintRule.java:39` is now a different rule entirely, and `directives.graphqls:473` is ~476.
* **"whose three cases all reach the replaced arm with no `@nodeId` anywhere in the fixture"** is
  loose: two of the three `LookupKeyCase` fixtures do carry `id: ID! @nodeId` on `Bar`. The
  load-bearing claim, that no `@nodeId` sits on the *argument*, is correct, which is what routes them
  through the implicit arm.
* **`SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED`'s bullet reads as though its verdict is already
  unreachable.** It is green today: `bar` publishes `__NODE_TYPE_ID`/`__NODE_KEY_COLUMNS` regardless
  of whether the GraphQL type carries `@node`, so the old arm's premise does fire. It stops firing
  after this item, which is what the bullet means and what its instruction to re-derive covers.
* The census figures (23 input-field, 2 argument, ~157 output-field) predate R580's 37-site
  migration and read low. The item already says re-measure at pickup; that instruction now binds.

Passes 2 and 3 above name `warnShadowedIdColumn` and `LintRule.NODE_ID_SHADOWS_COLUMN` as they stood
at those passes and are left as dated records, as the rule numbering is. Because this pass edited the
item, the Spec -> Ready sign-off needs a fifth session: not the original author, and none of the
three prior reviewers.

**Pass 5, 2026-08-08 (revisions applied by the reviewer).** Independent Spec -> Ready review by a
fifth session. Pass 4's reconciliation holds and nothing in the tree has moved under it: the six
commits since that pass touch only the dependency-warning module. Everything re-checked exists as
named. `warnShadowedIdColumn`, `LintRule.NODE_ID_SHADOWS_COLUMN` and `BuildWarning.LintFinding` grep
to zero in source (the surviving hits are stale `docs/target/staging` render output);
`rejectShadowedIdColumn` sits on the `Node.id` arm above the column lookup and returns
`Rejection.structural`; `NodeInferencePipelineTest`'s "The shadowed `id` column" block is five rows
over `baz`; the `CLASSIFIER` partition holds three `LintRule` entries; `NodeIndex`'s record shape
and both accessors, `resolveDecodeHelperForTable`'s three surviving callers,
`findGraphQLTypesForTable`'s two, rule 2's rejection message verbatim, the `directives.graphqls`
inference contract, `validateConnectionType` in the validator against `validateNodeTypeIdUniqueness`
in `TypeBuilder`, `InputFieldResolver.resolve`'s `typeName` being the input type's own name, all
seven named `NodeIdPipelineTest` cases, and sakila's `FilmActor` / `CreateKeyedNodeInput` /
`schema.graphqls:163` / `GraphQLQueryTest.filmActorByNodeId` all check out. The fixture DDL backs the
shadowing argument exactly: `nodeidfixture.baz` is a single `id varchar(50) PRIMARY KEY`, `bar` is
`id_1` / `id_2` / `name`, and `film_actor` has no `id` column. R273 is fully reconciled with the
pass-3 scope call and no longer claims the argument arm.

One blocking finding, applied above rather than handed back, because it was residue from the item's
own settled decision rather than a design question. **Two summary statements did not survive the
"shadowing is an error" decision**, and both sit where an implementer sizes the work. Rule 6's
placement bullet said the rule "wins against a same-named column", which is what the rule did in the
draft where shadowing warned; since that decision, and since R580's rework at output, a same-named
column is a rejection and nothing wins. And "Rule 6 adds no new rejection" is false as a headline:
its "required" half adds none, which is what the paragraph's own grounds argue, but the shadowing
collision at rule 6's two new coordinates is exactly a new verdict, one the Tests section requires be
asserted by message. The paragraph's operative claims about commit placement were already correct, so
this was imprecision rather than misrouting, but a spec whose subject is which verdicts land where
should not say a rule mints none while a section below adds two. Both are now scoped, and the
shadowing siblings' commit placement is stated where the count is.

Two measurement gaps were filled in the same pass, both things an implementer would otherwise
re-derive. Rule 1's `typeName:` rejection was never counted: it measures zero in-tree, because every
explicit spelling in the tree sits on an input field or argument where rule 6 keeps it legal, which
means the rejections commit has no fixture migration at all. And rule 3's re-pointing was described
as the whole of its implementation when it also drags two diagnostic messages that name the old
domain plus the two assertions pinning them; neither fixture changes verdict under the narrowing,
which is worth stating so the implementer does not go looking.

One non-blocking opportunity, left for the author because it is a scoping call rather than a defect.
**The three-commit opt-in seam may now be over-built for what it protects.** Its stated justification
is that the fixture migration proceeds "against a build that fails loudly for a session and quietly
for everyone else", but the migration is now measured at zero on every axis: rules 1 and 2 have no
in-tree sites, the shadowing rejection has none at either new coordinate, and Consumer migration
already says rules 1 and 2 ship as errors immediately since they cannot flip anything silently. The
design budget spent choosing between a Mojo parameter and a test-settable constant, and the recorded
constraint about where rejection tests can then live, is buying staging for a migration that does not
exist. Collapsing to two commits (rejections plus their cases, then flip and delete) would be
simpler, and the opt-in's real remaining value is out-of-tree exposure, which the Consumer-migration
re-confirmation gates separately. Worth a deliberate keep-or-drop rather than inheriting it.

Because this pass edited the item, the Spec -> Ready sign-off needs a sixth session: not the original
author, and none of the four prior reviewers.

**Pass 6, 2026-08-08 (signed off, Spec -> Ready).** Independent Spec -> Ready review by a sixth
session. No blocking findings. The plan is implementable as written and the gate flips.

The code tree has not moved since pass 5; the commits in between touch `roadmap/` only. Everything
below was re-checked directly against the tree rather than inherited from the prior passes.
`resolveDecodeHelperForTable`'s three arms are exactly as rule 5 describes them, and the helper is
already `NodeIndex.forTable`-backed internally, so "the singleton arm survives as rule 6's
table-derived resolution" is a re-siting rather than a rewrite; `resolveDecodeHelperForType`'s only
non-`forName` path is that fallback, so the collapse the deletion bullet prescribes is exact. The
helper's three live callers are the two the item names plus that fallback. `rejectShadowedIdColumn`
sits on the `Node.id` arm above the column lookup and names both remedies; the non-`id` shim below it
fires only on a column miss; the input-side qualifier arm precedes its column lookup and the
same-table arm follows it. Rule 2's rejection message, both `NodeIdLeafResolver.inferTypeName`
diagnostics, the `directives.graphqls` inference contract, the `CLASSIFIER` partition's three rules,
`NODE_INTERFACE_ID_FIELD`, `QueryNodeField` / `QueryNodesField` mapping to `OperationMember.NodeResolve`,
and every named test class and case member exist as named. `InputFieldResolver.resolve(String typeName, ...)`
does pass the input type's own name through to `classifyInputField`'s `parentTypeName`, with the
table as the only target fact, which is the premise rule 6's input arm rests on.

Both 2026-08-08 measurements reproduce. Every baz-backed input in the suite carries a directive
(`DeleteBazInput` in three spellings across the two mutation classification tests, `BazSelector` and
`BazFilterInput` in `NodeIdPipelineTest`), and no baz-backed argument exists, so the shadowing
rejection still costs no migration. Every `@nodeId(typeName:)` on an `id`-named field in the tree sits
on an input type (five in sakila, five in `graphitron/src/test`), never on a node type's `Node.id`, so
rule 1's rejection still costs none either. `IdReferenceShimWarnFormatTest` is the only test attached
to a shim WARN logger, and no test asserts either of the other two WARN texts.

Two non-blocking observations, neither affecting what an implementer builds:

* **R580 is now `In Review`, not `Ready`.** Two places say `Ready`, both inside dated records, and the
  move is toward Done rather than away, so nothing depends on it. The live claim in that paragraph,
  that R580's user-manual drafts need rewriting for the rejection, was already overtaken by R580's
  rework and is recorded correctly in the "One shadowing rule" section above.
* **Rule 6's input arm also re-sites an ambiguity rejection**, which the "adds no new rejection"
  paragraph does not count alongside the shadowing sibling. It is defensible to leave it uncounted:
  the verdict exists today at both coordinates and already names `typeName:` as the fix, so rule 6
  moves it rather than minting it. What is genuinely new is that moving it ahead of column resolution
  makes it reachable where a column of that name exists, and there the shadowing rejection and the
  ambiguity rejection both apply. Pick the order deliberately and pin it, the way the item already
  instructs for `RejectNonIdNodeIdPipelineTest`'s non-`ID` fixture. Rules 5 and 6 and the Tests
  section each specify the ambiguity case fully, so this is a precision note, not missing work.

Pass 5's opportunity stays open and passes to the implementer rather than lapsing: the three-commit
opt-in seam is staging a fixture migration that now measures zero on every axis, and collapsing to
two commits (rejections plus their cases, then flip and delete) may be the simpler shape. It is a
keep-or-drop call to make at pickup, with the reasoning recorded in pass 5 above, and it does not need
another Spec pass either way.

The next gate is In Review -> Done, which needs a reviewer different from the implementer.

## Implementation notes, 2026-08-09

Landed in two commits, not three. Full reactor green under `-Plocal-db`, 13/13 modules, including
the `graphitron-sakila-example` compile and execution tiers.

**The opt-in seam was dropped**, taking the option pass 5 left open. It was staging a fixture
migration that measured zero on every axis, `Consumer migration` already ships rules 1 and 2 as
immediate errors because neither can flip anything silently, and out-of-tree exposure is gated
separately by the re-confirmation below. So: rejections plus their cases, then flip and delete.

**Rules 1 and 2 landed classifier-side, not in `GraphitronSchemaValidator`.** The plan's premise
there does not hold against the tree: `GraphitronType.NodeType` and `TableType` carry only
`name`/`location`/`table` with no `schemaType()`, and `definition` sits on the `UnclassifiedField`
arm alone, so a validator walk cannot see a directive on a field that classifies today. The reason
the plan wanted the validator was the source location the LSP reads, and the classifier route
already delivers it: `UnclassifiedField` carries a location and `validateUnclassifiedField` drains
it into exactly that located `ValidationError`. Pinned by a test rather than left as prose. This
also gives the grammar one placement instead of two, since the shadowing rejection has to be
classifier-side regardless.

Rule 2's "separate coordinate to check" needed no work: `FieldBuilder.resolveCarrierIdEncoder`
already resolves a bare `@nodeId` on a `ResultType` carrier through `NodeIndex.forTable`,
singleton-or-diagnose, which is rule 2 plus rule 3's ambiguity requirement already shipped.

**Shadowing became one method rather than three siblings**, `BuildContext.rejectShadowedNodeId`,
with the shipped output-side rejection delegating to it. Keeping three wordings for one rule was
the first step towards three semantics. `NODE_INTERFACE_ID_FIELD` moved to `BuildContext` for the
same reason, being read at all three coordinates now.

**Rule 6's key columns come off the `NodeType`, not the table metadata.** A classified `NodeType`
always carries a non-empty `nodeKeyColumns` (the `@node`-without-`keyColumns` path falls back to
the primary key and rejects when there is none), so this is both safe and the right polarity: SDL
keeps winning on `typeId` outright and on key order.

**Two fixtures were leaning on the retired orphan fallback with broken node declarations.**
`MultiTableFilterLoweringTest`'s `Address` and `ReferenceFilterRemoteColumnPipelineTest`'s `Baz`
carried `@node` without `implements Node`, which `TypeBuilder` rejects, so neither was ever a
`NodeType`; the typeId-suffix fallback resolved their decode helpers anyway. Both now declare the
interface. Worth knowing that the fallback was masking malformed declarations, not just serving
orphans.

Deletions ran wider than the plan listed, all of it dead in the same motion:
`buildInputNodeIdReference` (the qualifier shim was its only caller) and the whole
`typeNamesByTableKey` index behind `findGraphQLTypesForTable`. `IdReferenceShimClassificationTest`
is a second shim-intent test class the plan's census did not name, deleted alongside
`IdReferenceShimWarnFormatTest`.

Fixture verdicts moved by stated intent. `IMPLICIT_ID` keeps its reading re-grounded on the SDL and
now throws rather than skips, since an implicit reading of the grammar is worth the same as an
authored `@nodeId`. `EXPLICIT_PERSON_ID` inverts into the `@field` silencer case.
`R377_ORPHAN_INPUT_TYPEID_FALLBACK` and the qualifier-shim case set are deleted, their subject
being the shim itself. `SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` was re-sited onto a
node-typed `Bar` so the deferred-composite gap still has a fixture reaching it.

Sakila needed no migration, exactly as the plan predicted: `filmActorByNodeId(id: [ID!]!)` is
covered by rule 6 and its `GraphQLQueryTest` round-trip stays green through the flip.

**The Consumer-migration gate is closed.** Re-confirmed by the user on 2026-08-09: sis is the only
touched subgraph and the user is doing that migration themselves. Neither silent arm needs the WARN
staging the plan held in reserve, and no release will carry the flip ahead of that migration. See
`Consumer migration` above for the recorded confirmation.

**R27 (`retire-synthesis-shims`) is discarded**, on the user's instruction of 2026-08-09. Its
deletion set was empty (all three shim sites went with this item's flip), its gate dissolved with the
consumer re-confirmation, and there was no WARN left to flip because the grammar replaced the warned
readings with rules. Inbound references in R34, R273 and the `@error`-on-query-fields item were
repointed; the residuals item's finding about the manual naming that plan by slug is resolved, this
item's flip having already restated the sentence. The changelog carries the reasoning.

**Nothing is left open.** The item is ready for its In Review -> Done gate. (Superseded by the gate
review below, which found four items still open; the two commits themselves stand.)

## In Review -> Done gate, 2026-08-09 (rework requested)

Independent gate review by a session that authored neither implementation commit. `mvn install
-Plocal-db` is green, 14/14 modules, including the `graphitron-sakila-example` compile and execution
tiers, so the build precondition holds. No code-string assertion on a generated method body appears
anywhere in the delivered tests; the new cases assert model variants and message text. Everything the
Tests section required is present: the shadowing triple at all three coordinates sited together, the
Relay root-field pin, both named silent narrowings, and all three of rule 6's input-coordinate cases
with the ambiguity case asserting its message.

Several judgement calls read better than the plan they replaced, and should survive the next pass
unchanged: one `BuildContext.rejectShadowedNodeId` instead of three sibling wordings; rule 6's key
columns off the classified `NodeType` rather than the table's raw metadata; the classifier-side
placement, argued from the model's actual shape and pinned by a location test rather than left as
prose; and the two fixtures found carrying `@node` without `implements Node`, which was a latent
malformed declaration the retired fallback had been masking.

Four findings. The first is an invariant break and is the reason the gate does not flip.

**1. The uniform shadowing rule does not hold at the argument coordinate when the field returns a
connection.** `FieldBuilder.java:1586` derives rule 6's target from `fieldDef.getType()`, but every
caller of `resolveTableFieldComponents` passes the connection-*unwrapped* `elementTypeName` and an
`rt` to match (`FieldBuilder.java:869`, `:4626`, `:6131`, `:6223`, all guarded by
`ctx.isConnectionType`). So the arm reads an unwrapped table and a wrapped type name, and
`nodes.forName("BazConnection")` misses. Measured against the fixture catalog, the same shape gives
two different answers to the one shadowing question:

* `bazes(id: ID): [Baz!]!` rejects with the shadowing message, which is correct.
* `bazes(id: ID, first: Int, after: String): BazConnection @asConnection` resolves, silently binding
  the raw `id` column.

This is the coordinate-specific divergence the Tests section says asserting all three coordinates
together exists to prevent, arriving through the return-type wrapper rather than through the
coordinate. It is also a third silent narrowing: before the flip that argument decoded (the retired
arm keyed on `catalog.nodeIdMetadata(rt.tableName())` and read no type name at all, and `baz` is
single-key, non-list, non-`@lookupKey`, so it took the decode branch). `Consumer migration`'s
re-confirmation was taken over two silent arms, not three. The fix is to resolve the target off the
same unwrapped element type name the rest of the classifier already computed rather than off
`fieldDef.getType()`; thread it into `classifyArgument` alongside `rt`, since the two have to agree.
Pin the connection shape at the argument coordinate either way, whichever answer is chosen.

**2. The user manual still documents the deleted synthesis shims as live behaviour**, in the same
file the flip partially rewrote. `docs/manual/how-to/migrating-from-legacy.adoc`:

* `:253`, migration step 3: "Audit the build log for `synthesizes an `@nodeId` carrier without the
  directive` WARNs". That string greps to zero in main sources after the flip, so a consumer
  following the step finds nothing and concludes there is no work, which is the opposite of true.
* `:264`, pitfall: "Periodically grep your build logs for `synthesizes an` to catch shim-bearing
  fields", permanently useless advice under the heading "Synthesis shims run silently in IDE /
  language server contexts".
* `:242`: "the synthesis shims (above) are the only migration work", pointing at a section the flip
  deleted.
* `:259`: "Steps 3-4 ... will become non-optional when the corresponding retirement gates fire".
  Step 3's gate has fired, and step 4's inference was removed outright, which the same commit
  documents as a hard removal ~130 lines earlier.
* `:5` lists "the synthesis shims (build succeeds with a WARN today, will fail later)" as one of the
  four things the recipe covers, and `:277` offers `global-id.adoc` as covering "the declaration
  shapes the synthesis shims migrate to".
* `:156` cites "the synthesis shim plan", which is the item discarded at `54540b6`.

The flip's commit message says the manual was "updated where it had gone false"; this is the half
that was missed. `Consumer migration` records that the sis migration is in progress, so this recipe
is being read now.

**3. Retired vocabulary left across main sources and tests, describing mechanisms this item
deleted.** All of it was accurate before the flip, so the flip is what turned it stale. Declare a
`Retired vocabulary` section (`synthesis shim`, `IdReference synthesis shim`,
`node-reference synthesis shim`, `SkipMismatchedElement`) so the workflow's Retirement sweep runs at
the next gate instead of being skipped:

* `FieldBuilder.java:1502-1503`, routing note: "the implicit scalar-ID arm below, which owns
  synthesised paths (no `@nodeId` declared, parent table has nodeId metadata)". That arm reads no
  table metadata now and synthesises nothing.
* `FieldBuilder.java:7329-7332`: "deliberately narrower than the deprecated synthesis shim below:
  that one fires for *any* bare `ID` field on a node type". There is nothing below.
* `GraphitronSchemaBuilder.java:1004-1007` and its mirror `FacetedConnectionPipelineTest.java:165`
  both state the `@asFacet`-on-`ID` rejection's rationale as closing "the node-reference synthesis
  shim". The rationale needs restating on grounds that still exist.
* `BuildContext.java:1230` and `:1257`, the latter an explicit "Reached only from ... the IdReference
  synthesis shim" enumeration that is now wrong about its own caller set.
* `BuildContext.java:2771`: "the legacy synthesis-shim arms (not reachable from here) keep
  `SkipMismatchedElement`."
* `NodeIdLeafResolver.java:64-68` and `CallSiteExtraction.java:143-145`.
* `InputFieldFanInDiagnosticsTest.java:222`, a census naming "the id-reference synthesis shim's four
  arms" among producers not reachable from the default catalog; those arms are not producers
  anywhere now.
* `NodeIdPipelineTest.java:695`, an orphan `// ===== FK-qualifier synthesis shim =====` header over
  the deleted enum.

**4. `CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement` has no producer left in main
sources** (zero constructions; the only two are hand-rolled in `DeleteRowsWalkerTest` and
`UpdateRowsWalkerTest`), while `LookupRows.java:257` and `:285` still switch on it and three of the
new assertions pin its absence. Whether the variant goes with the shims is a scoping call this plan
never made, so decide it here rather than leaving a sealed arm the classifier cannot reach: either
delete it and collapse the renderer's switch, or state why it stays. Its javadoc's "Produced only by
the legacy `__NODE_*` synthesis shims" is false as written either way. Note the sakila comment at
`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:290`, which describes
`filmsByNodeIdArg` as decoding "via `SkipMismatchedElement` (malformed ids drop silently)", is
pre-existing rot rather than this item's; it is worth fixing in the same sweep but does not belong to
this item's scope claim.

Two non-blocking notes. The new argument rejection reaches the author with a doubled prefix,
`argument 'id': argument 'bazes(id:)': ...`, because the caller already prefixes with the argument
name; `argument_bazBareIdIsAmbiguousAndRejects` passes anyway since it asserts with `contains`. And
`Implementation notes` and both commit messages say "13/13 modules" where the reactor is 14.

One documentation-hygiene point for whoever takes the next pass, since the gate asks that the body
reflect what shipped: the three `Implementation` subsections above still read as forward-looking
instructions, two of which the delivery deliberately contradicted (rejections in
`GraphitronSchemaValidator`, and the build-level opt-in seam), with the reconciliation only in
`Implementation notes` at the bottom. Collapse each to a one-line landed note naming the commit
(`b48b0f8` for the rejections, `7eb474f` for the flip and delete) so the body is not read as an
instruction to redo work that has landed.

## Retired vocabulary

Declared at the gate review above, so the Retirement sweep runs at the next In Review -> Done rather
than being skipped a second time. Each names a mechanism the flip deleted:

* `synthesis shim` (and `shim` used of any of the three deleted arms)
* `IdReference synthesis shim` / `id-reference synthesis shim`
* `node-reference synthesis shim`
* `resolveDecodeHelperForTable`, `buildInputNodeIdReference`, `findGraphQLTypeForTable`,
  `findGraphQLTypesForTable`, `typeNamesByTableKey`
* `SkipMismatchedElement`, conditional on finding 4's keep-or-delete call

## Provenance

Supersedes, in stronger form, the discarded R263 (`decode-helper-typename-first-resolution`, see the 2026-07-13 changelog entry and its re-open trigger): R263 proposed a typeName-first *sibling* entry point for hypothetical future callers; the finding here is that existing callers already hold the type name and the resolution polarity is simply backwards. Grammar shape settled in design discussion on 2026-07-13.

Two carve-outs have shipped ahead of this item and narrowed it. R581 took the decode-resolution polarity fix at the call sites holding an authoritative type name (see Phase 1 above). R580 (shipped, see `roadmap/changelog.md`) took type-level nodehood inference and, with it, rule 1's carrier at the output-side `FieldBuilder` site; the division of labour that settles is that R580 owns which *types* are nodes and what `Node.id` means, while this item owns what a directive-less or bare-directive `ID` means at every other coordinate. What this item still owes rule 1 is the `typeName:` rejection on `Node.id` and the input-side generalisation in rule 2.
