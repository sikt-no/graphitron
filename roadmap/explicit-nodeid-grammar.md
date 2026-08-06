---
id: R473
title: "Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution"
status: Spec
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-13
last-updated: 2026-08-06
---

# Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution

An `ID`-typed field can still acquire node semantics implicitly, with the node identity derived from *table* facts (the catalog's `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants) rather than from what the schema author declared. Three sites still carry that inversion, each firing a per-site deprecation WARN and each answering "which node is this" from the backing table: the two input-side arms in `BuildContext.classifyInputField` (the qualifier-reverse-map arm and the bare same-table arm), and the surviving half of one output-side site in `FieldBuilder`.

A fourth site carries the same inversion and **is in scope**, absorbed from R273 at the pass-3 gate: the directive-less implicit scalar-`ID` **argument** arm in `FieldBuilder.classifyArgument` (`FieldBuilder.java:1580-1609`, the block its own comment calls "the implicit scalar-ID arm below, which owns synthesised paths (no @nodeId declared, parent table has nodeId metadata)"). Unlike the other three it fires no deprecation WARN, and like the qualifier arm it runs *ahead* of column resolution. R273 (`bare-scalar-id-arm-modernisation`) offered the collapse and is narrowed to its unrelated second deliverable, R265's deferred compile-tier guard. The arm is not merely deleted here: rule 6 replaces it with a narrower, SDL-derived arm.

That output-side site (a scalar `ID` on a `NodeType` parent with no directive at all) used to be a whole shim. R580 split it in two. The `Node.id` half was adopted as rule 1's permanent carrier: it resolves ahead of the column lookup, carries no deprecation, and is documented behaviour rather than a shim awaiting deletion. What remains a shim there is the *non-`id`* half, a bare `ID` field on a node type that is not the one satisfying the `Node` interface (`externalId: ID`), which still synthesises a nodeId from table facts and still warns. That half is rule 4's business and is still in this item's scope; the `Node.id` half is not.

Note the two halves now sit on opposite sides of the column lookup: the `Node.id` arm resolves *before* it and wins against a same-named column, the non-`id` arm fires only on a column *miss*. So deleting the non-`id` arm cannot flip a working field silently, it turns a column miss into the `unknownColumn` rejection rule 4 already prescribes.

R581 has since fixed the half of this that was actively breaking builds: the call sites holding an authoritative type name no longer route their decode resolution through the table. What is left is the inversion itself. As long as a directive-less `ID` can mean "node identity, resolved from the table", the schema does not say what it means, an author cannot tell the two readings apart by looking, and the table-first helper cannot be deleted.

The grammar below closes that by making the implicit reading available in exactly one place, where it cannot be ambiguous.

## The grammar

1. **`Node.id` is the only implicit nodeId.** The `id` field satisfying the `Node` interface on a type classified as a `NodeType`, however it got there, is obviously a nodeId and obviously of the enclosing type. The directive is redundant there (existing `id: ID! @nodeId` fixtures stay legal); `typeName:` is rejected there as contradiction-prone noise. **This rule is already live at the output-side `FieldBuilder` site** (see the intro), including the precedence over a same-named column that the antecedent's "however it got there" wording is there to cover: nodehood can be inferred from `implements Node` plus catalog metadata, so it is not `@node` presence that makes rule 1 apply. What this item still owes rule 1 is the *rejection* half, `typeName:` on that field, and the input-side generalisation in rule 2.
2. **Bare `@nodeId` (directive without `typeName:`) is legal only on output fields of a type that is a node type**, however it got there, where "current type" is well-defined; it is a generalization of rule 1. Stated over node-type membership rather than `@node` presence for the same reason rule 1 is: since R580 a type can be a node by declaring `implements Node` over a metadata-carrying table, and phrasing this rule over the directive would reject bare `@nodeId` on exactly the types inference exists to enable.
3. **Everywhere else (input fields, arguments, anything crossing to another type), node semantics require `@nodeId(typeName: T)`.** An `ID`-typed field without the directive has no node interpretation, full stop. Read this together with rule 6, which carves out the one coordinate where a directive-less name is unambiguous: an input field or argument named for the target's `Node.id`. "Everywhere else" means every coordinate rule 6 does not reach, including any name that does not match and any target that is not a node type.
4. **`ID` without `@nodeId` is an ordinary scalar.** With `@reference` it is a regular column-mapped field at the end of the reference path, validated against the matching column exactly like any other scalar routed through `@reference`; without `@reference` it is a regular column-mapped field on the enclosing type's table, same validation, unless it is `Node.id` (rule 1). No node interpretation, no rejection: `ID` in SDL means an opaque identifier, not necessarily a graphitron NodeId, and the column's own value is a legitimate id surface. The current `@reference` branch (plain single-column `InputField.ColumnBackedReferenceField` with `CallSiteExtraction.Direct()`) is already the right semantics for that shape and stays; what changes is the directive-less non-reference case, which today gets shim-synthesized node semantics from table facts instead of plain column mapping.
5. **Decode resolution becomes typeName-first wherever a type name exists**: `NodeIndex.byName.get(typeName).decodeMethod()` (the by-name view already exists on `record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName)`). `resolveDecodeHelperForTable` is deleted rather than guarded, but its three arms split rather than all dying, which rule 6's input coordinate forces: the typeId-suffix fallback goes (that is the orphan/shim case this item retires), the singleton arm survives as rule 6's table-derived resolution, and the multi-`@node`-per-table ambiguity arm stops returning `null` and becomes rule 6's rejection naming `typeName:` as the fix. `NodeIndex.forTable` is not at risk either way; it has four other callers in `FieldBuilder` independent of this item.
6. **An argument naming the return type's `Node.id` is that nodeId, implicitly.** This follows from rule 1 rather than diluting it. Where a field returns a type classified as a `NodeType`, that type has exactly one field satisfying the `Node` interface, so an argument carrying that field's name is naming it, and "which node" is answered by the return type declared on the same field in SDL. Numbered last because it was coined at the pass-3 gate; logically it sits next to rule 1. Four boundaries, each load-bearing:
   * **The node is read off the SDL return type, never the backing table.** That is what distinguishes rule 6 from the arm it replaces, and it makes the rule typeName-first by construction (rule 5): the decode resolves through `NodeIndex.forName`, never `catalog.nodeIdMetadata`.
   * **If the argument name does not match, `@nodeId(typeName: T)` is required** (rule 3). A mismatch means the argument is not that node's identity and nothing else in the SDL says which node it is. This is the clause that settles the plural case: `ids` does not match `id`, so `barsByIds(ids: [ID!] @lookupKey)` takes the directive. Do not fold `ids` onto `id`; a pluralisation heuristic is exactly the implicit magic this item removes.
   * **Scalar and list arguments alike**, unlike the output-side rule 1 arm, whose `isScalarId` guard is deliberate and must not be copied across. The motivating case is a list.
   * **`@field(name:)` on the argument defeats it**, pinning a column instead, mirroring the `!hasFieldDirective` guard at `FieldBuilder.java:7273`.

   **Input-object fields are covered too**, on the same terms. They bind against the consuming site's target exactly as a scalar argument does, and carry the same `@field(name:)` override, so the coordinate is equally well-defined. But the target reached there is a *table*, not a type: no return type name is threaded into `BuildContext.classifyInputField(field, parentTypeName, resolvedTable, ...)` or `InputFieldResolver.resolve(String typeName, TableRef rt, ...)` (both `typeName` parameters are the *input* type's own name), and for a mutation input there is no node type in the SDL at all, as in `deleteFilmActorByNodeId(in: DeleteFilmActorByNodeIdInput!): ID @mutation(typeName: DELETE, table: "film_actor")`. So rule 6's input arm resolves through `NodeIndex.forTable`: a singleton is the node, and an ambiguous table requires `@nodeId(typeName: T)`.

   That is not a regression against R581, and the distinction is worth keeping because it will otherwise be re-litigated. R581's guarantee is that a site which *names* its type stops having that answer discarded by a table reverse-lookup. Rule 6's input arm holds no name to discard, so the reverse lookup is its only source rather than a thrown-away one, and ambiguity becomes a rejection naming the fix instead of a silent pick.

   Consequently **rule 6 does not reject `typeName:` the way rule 1 does.** Rule 1 rejects it because the enclosing type already answers "which node" unambiguously, making the argument noise. At a table-derived coordinate the argument is load-bearing whenever the table carries several node types, so it stays legal and is the disambiguator. Existing explicit spellings such as `DeleteFilmActorByNodeIdInput.id` and `FilmActorCompositeNodeIdFilter.ids` stay valid as written; there is no removal migration.

   **Rule 6 adds no new rejection.** The "required" half is descriptive, not a verdict: a non-matching argument name is rule 4 falling through to ordinary column resolution, which either resolves or produces the existing `unknownColumn` rejection. So rule 6 lands wholly in the flip-and-delete commit, and the rejections commit still covers exactly rules 1-3. The one discretionary addition is naming `@nodeId(typeName:)` as a candidate fix in that column-miss message when the enclosing field returns a node type.

## Phase 1: shipped as R581

The type-bearing callers (`BuildContext.buildInputNodeIdReference`, `NodeIdLeafResolver.resolve`) route through `BuildContext.resolveDecodeHelperForType`, and `resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the table's metadata. That was carved out and shipped ahead of this plan because a field report made it urgent: a second `@node` over one table broke every already-declared `@nodeId(typeName:)` leaf on that table. `resolveDecodeHelperForTable` survives in the synthesis shims and in the *directive-less* implicit scalar-`ID` argument arm of `FieldBuilder.classifyArgument` (`FieldBuilder.java:1580-1609`), which holds no type name to key on. Note that arm is reached only when the argument carries **no** `@nodeId`: a bare `@nodeId` argument is routed at `FieldBuilder.java:1508` through `NodeIdLeafResolver.resolve`, which reaches `inferTypeName`, not `resolveDecodeHelperForTable`. An earlier draft called this "the bare-`@nodeId` argument arm", which pointed an implementer at the wrong block. That arm is now this item's (see the intro and rule 6), so nothing outside it keeps `resolveDecodeHelperForTable` alive.

What remains for this item is the grammar itself: rules 1-4 and 6 as build behavior, and the deletion of the table-first helper.

## Implementation

Ordering is a real seam here, so the sections below land as three commits, each independently green. The rejections must exist and be quiet before they can be turned on, and the fixture migration must be complete before the shims can go.

### Rejections, off by default

Rules 1-3 are three distinct rejections. All three go in `GraphitronSchemaValidator`, not in the classifier: they are SDL-shape verdicts, they need no catalog or index state, and the validator's report already carries a source location per error, which is what makes the diagnostic actionable in the LSP. (Note the neighbour to reason from is *not* `validateNodeTypeIdUniqueness`, which lives in `TypeBuilder` and runs as part of classification. The validator's own SDL-shape precedent is `validateConnectionType`, which reads `type.schemaType()` and falls back to the type location when the AST node is absent.)

The facts these rejections need are all reachable there. `GraphitronSchemaValidator.validate` takes the classified `GraphitronSchema`; `GraphitronType.InputType` and the object types expose `schemaType()` for the directive read, and `GraphitronField` carries `definition` for the argument walk. "Is the enclosing type a node type" is answerable straight off the classified model (a `GraphitronType.NodeType` arm), which is the post-classification form of the predicate `NodeDeclaration.isNodeType` answers for the pre-classification consumers; do not re-read `@node` at either site.

* Rule 1: `typeName:` on the `id` field satisfying `Node` on a node type. The named type either agrees with the enclosing type (redundant) or contradicts it (a bug the author cannot have meant), so the argument is rejected rather than checked.
* Rule 2: bare `@nodeId` on an output field of a type that is not a node type. **This one already rejects**, structurally, in `FieldBuilder.classifyChildFieldOnTableType`: "@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)", a message that became true as written once R580 landed the metadata path. So the work here is not a new verdict, it is giving the existing one a source location and a message that names `typeName:` as the fix. Decide explicitly whether the classifier rejection stays as the mirror that "Rejections: validator mirrors classifier invariants" prescribes (it should) or moves; and note the classifier arm is reached only for a `@table`-backed parent, so a non-table-backed parent is a separate coordinate to check.
* Rule 3: bare `@nodeId` on an input field or an argument. The current inference (`NodeIdLeafResolver.inferTypeName`, the `findGraphQLTypesForTable` arm) is what this replaces, and it is the arm carrying the two friendly diagnostics `BARE_NODE_ID_NO_OBJECT_TYPE` and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES` already pin. The new rejection subsumes both: it fires on the unique-match case too, which is the whole point.

Land them behind a single build-level opt-in so the flip is one switch rather than three, and so the fixture migration below can proceed against a build that fails loudly for a session and quietly for everyone else. The opt-in mechanism is the implementer's call; a Mojo parameter is the obvious candidate, but if that adds a user-facing surface we would then have to retire, a package-private constant flipped in the fixture-migration commit is the cheaper answer. Decide before writing the rejection code, and say which in the commit.

One constraint on that choice, because it decides where the rejection tests can live: a `static final` constant defaulting to false cannot be toggled per test, so the per-rule rejection cases cannot land in the same commit as the rejection code, and that commit ships branches nothing exercises. A test-settable seam (a package-private non-final field, or a value threaded through the build context) keeps rejection code and its tests in one commit, which is what "each independently green" should mean here. If the constant wins anyway, say in the commit message that the cases arrive with the fixture migration, so the gap is a recorded decision rather than an oversight.

### Fixture migration

Re-measured against the post-R580 tree on 2026-08-04 (re-measure again at pickup; the counts move, and a single-line grep undercounts because several fixture declarations span lines):

* `graphitron-sakila-example`: no migration needed, but for two reasons rather than one. All 16 bare `@nodeId` sites across the five `.graphqls` files are `Node.id` on a node type, which rules 1 and 2 keep legal. **The claim in an earlier draft of this item that the sakila INSERT-input fixture writes the bare form on an input is stale and was the main reason this plan looked more expensive than it is**; `CreateKeyedNodeInput` does write `@nodeId(typeName: "KeyedNode")`. Separately, `schema.graphqls:163` carries the one directive-*less* site, `filmActorByNodeId(id: [ID!]! @lookupKey): [FilmActor!]!`, whose argument routes through the arm rule 6 replaces. It survives untouched precisely because rule 6 covers it: the argument is named `id`, `FilmActor` is a node type, so the node resolves off the return type. Under rule 3 alone it would have needed `@nodeId(typeName: "FilmActor")` added, and since `film_actor` has no `id` column it would otherwise have become an `unknownColumn` rejection. This is the coordinate that motivated rule 6.
* Sakila's `FilmActor` in `schema.graphqls` is now the *fully* implicit rule-1 shape: `implements Node @table(name: "film_actor")` with a bare `id: ID!`, no `@node` and no `@nodeId`. `GraphQLQueryTest`'s `filmActorByNodeId` round-trip is therefore this item's execution-tier proof that rule 1 holds end to end, and it must stay green through the flip. The declared spelling over the same table is still covered by `federated-schema.graphqls`.
* `graphitron/src/test`: 23 input-field sites and 2 argument sites carry bare `@nodeId` and need `typeName:` added. ~157 output-field sites are `Node.id`-shaped and stay as they are.
* The two argument sites are `NodeIdPipelineTest`'s `barByIds(ids: [ID!]! @nodeId)` and `RejectNonIdNodeIdPipelineTest`'s `search(term: String @nodeId)`; the latter is a non-`ID` rejection fixture whose verdict must not change, so check which rejection wins and pin the order deliberately.

Rule 6's own verdict cost sits in `NodeIdPipelineTest.LookupKeyCase`, whose three cases all reach the replaced arm with no `@nodeId` anywhere in the fixture. Behaviour decides each, not emit equality:

* `SCALAR_NODEID_LOOKUP_COMPOSITE_PK` (`barById(id: ID @lookupKey): Bar`, `Bar implements Node @table @node`): argument named `id`, return type is a node type, so rule 6 covers it and the case stays green unchanged. This is rule 6's pipeline-tier proof.
* `LIST_NODEID_LOOKUP_COMPOSITE_PK` (`barsByIds(ids: [ID!] @lookupKey)`): the argument is plural, so rule 6's name match misses and the directive is required. Migrate to `@nodeId(typeName: "Bar")` rather than renaming the argument; the case exists to cover the list shape, and the explicit spelling keeps it covering that.
* `SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` (`bar(id: ID): Bar`, `type Bar @table(name: "bar")` with `id: ID! @field(name: "ID_1")`): `Bar` is *not* a node type, so neither rule 6 nor the old arm's premise applies and today's "composite-PK NodeType is only wired for @lookupKey" verdict is no longer reachable by this fixture. Re-derive the new verdict against the fixture catalog rather than assuming which rejection replaces it, and check whether the deferred-composite gap it was pinning still has a fixture that reaches it. If not, that coverage needs re-siting rather than deleting.

Rule 4 has no fixture-migration cost of its own but does have a verdict cost: the cases that pin shim-synthesized node semantics on a directive-less `ID` are the ones whose expected outcome inverts. `NodeIdPipelineTest.InputCase.IMPLICIT_ID`, `EXPLICIT_PERSON_ID`, `R377_ORPHAN_INPUT_TYPEID_FALLBACK` and `R377_MULTI_NODE_REJECTS` all encode the old semantics; each either flips to plain column mapping or moves to a `@nodeId(typeName:)` spelling that keeps testing what it was there to test. Read each one's stated intent before rewriting it, and where the intent was the shim itself, delete rather than translate.

### Flip and delete

Turn the opt-in on by default and delete it, then delete in one commit:

* The surviving synthesis shims: `FieldBuilder`'s output-side bare-scalar-`ID`-on-a-`NodeType` branch, now narrowed to fields *other* than `Node.id` (the `Node.id` arm above it is permanent and must be left alone), and `BuildContext.classifyInputField`'s two input-side sites (the qualifier-reverse-map arm feeding `buildInputNodeIdReference`, and the bare same-table arm guarded by `catalog.nodeIdMetadata`).
* The directive-less implicit scalar-`ID` argument arm (`FieldBuilder.java:1580-1609`), **replaced rather than merely deleted**: rule 6's arm takes its place, keyed on the argument name matching the return `NodeType`'s `Node.id` field and resolving the decode through `NodeIndex.forName`. Shape it as the mirror of the output-side arm at `FieldBuilder.java:7270`: same predicate position, ahead of the argument's column resolution at `FieldBuilder.java:1664` (`argString(arg, DIR_FIELD, ARG_NAME).orElse(name)`), so it wins against a same-named column exactly as rule 1 does. There is no existing notion of an argument mapping to a *field* of the return type, so that predicate is new code; today's binding is argument-name-to-column only. Give it the same `LintRule.NODE_ID_SHADOWS_COLUMN` finding rule 1 carries, for the same reason and with the same two silencers: the exposure (a node-backed table with a literal `id` column) is identical, and the two coordinates should tell authors the same story.
* `BuildContext.resolveDecodeHelperForTable` with them, which leaves `resolveDecodeHelperForType`'s fallback arm dead; collapse that method to the `NodeIndex.forName` lookup and let it return the `Optional` rather than `null`.
* `NodeIdLeafResolver.inferTypeName`'s table-lookup arm and `BuildContext.findGraphQLTypeForTable` (the singular, shim-only helper). `findGraphQLTypesForTable` (plural) has exactly two callers today: the singular helper above, and `NodeIdLeafResolver.inferTypeName`'s table-lookup arm. This commit deletes both, so the plural helper goes dead in the same motion and should be deleted with them rather than preserved.
* The three deprecation WARNs at those sites: `FieldBuilder`'s "synthesizes an `@nodeId` carrier without the directive", and `BuildContext`'s two (`ID_REF_SHIM_LOGGER`, `NODE_ID_SHIM_LOGGER`). **Leave `warnShadowedIdColumn` alone.** It sits in the same method, immediately above the surviving shim arm, and it is not a deprecation: it is the permanent `LintRule.NODE_ID_SHADOWS_COLUMN` finding that rule 1's column precedence requires. A sweep of "the warnings at this site" would take it out.
* `IdReferenceShimWarnFormatTest`, which pins the qualifier-arm WARN's text. It is the only warn-format test in scope. **`AsConnectionSameTableWarnFormatTest` is not**, despite the similar name: it covers the `@asConnection` same-table warning and has nothing to do with these shims. No test asserts the `FieldBuilder` WARN's text at all, so that one needs no test change.

R27 (`retire-synthesis-shims`) is the item that has carried the shim deletion; it should be discarded into this one at that point rather than left to delete an empty set. Confirm with whoever picks R27 up first.

## Tests

Per-rule rejection cases in the validator's own test class, each pinning the source location as well as the message; the LSP reads both. Beyond that the work is mostly the fixture migration above, and the honest test of this change is that the migrated suite stays green with the shims gone.

Two cases worth adding that do not exist today, one per silent transition hazard named below, since those are the shapes where a semantic flip is possible rather than a build error:

* An input field whose `ID` column name collides with a node-bearing FK qualifier, under rule 4, pinning that it maps to the plain column rather than decoding.
* An `ID` argument on a field returning a node type, named for a real column rather than for `Node.id`, pinning that it maps to that column rather than decoding. Its sibling (an argument named `id` on the same shape) is `LookupKeyCase.SCALAR_NODEID_LOOKUP_COMPOSITE_PK`, which already exists, so the pair reads as one contrast.

Rule 6's shadowing arm needs its own case too: a node-backed table carrying a literal `id` column, with an `id` argument, pinning that the node wins and that `LintRule.NODE_ID_SHADOWS_COLUMN` fires. The output-side equivalent shipped with R580; this is the argument-side mirror.

Rule 6's input coordinate needs three of its own, since it resolves table-derived rather than type-derived and none of the argument cases exercise that path:

* A directive-less `id` field on an input consumed by a field returning a node type, pinning that it decodes.
* The same shape on a *mutation* input, where the SDL names only `@mutation(table:)` and there is no node type at the coordinate at all. `deleteFilmActorByNodeId` is the live shape to model it on.
* A directive-less `id` field on an input whose target table carries **two** `@node` types, pinning the ambiguity rejection and that its message names `typeName:` as the fix. This is the case that keeps rule 6 honest about not being an R581 regression, so it should assert the message, not just the rejection.

## Consumer migration

Per user confirmation on 2026-07-13, no consumer relies on the shim behavior today, so R27's written gate (sis migrated, plus one external-consumer release window) is more conservative than reality and this does not need to wait it out. **That confirmation is now over a year old at the time of writing and predates the utdanningsregisteret federation work; re-confirm before flipping, because the cost of being wrong is a consumer build that fails with no migration path staged.**

Two silent changes remain in this item's set, both on directive-less coordinates that sit *ahead* of column resolution. Every other rule turns a working build into a failing one with a message naming the fix, and the surviving output-side arm fires only on a column miss (see the intro), so deleting it rejects rather than flips.

The first is a legacy column-named `ID` field (`customerId: ID`) on an *input* type flipping from shim-synthesized node decode to raw column mapping, at the qualifier-reverse-map arm. That arm runs ahead of the column lookup, so a field whose name hits both the qualifier map and a real column changes meaning with no build error.

The second is an `ID` **argument** on a field returning a node type, where the argument name does not match `Node.id` but does match a real column. Today's arm decodes it (it consults only `catalog.nodeIdMetadata` and the argument's `ID` type, never the name); under rule 6 it falls through to plain column mapping. The narrowing is the point, but it is silent, so it belongs in the same re-confirmation as the first. Note the converse is *not* a flip: an argument that does match `Node.id` keeps decoding, which is why sakila needs no migration. If re-confirmation turns up any consumer at all, stage *both* arms behind a WARN for one release before they change semantics, and leave rules 1-3 as errors immediately since they cannot flip anything silently.

Rule 1's own silent change, a `Node.id` field over a table with a same-named column flipping from the raw column to the encoded global id, already shipped with R580, with the `NODE_ID_SHADOWS_COLUMN` warning naming both silencers. It is not this item's exposure and does not need re-staging here.

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

## Provenance

Supersedes, in stronger form, the discarded R263 (`decode-helper-typename-first-resolution`, see the 2026-07-13 changelog entry and its re-open trigger): R263 proposed a typeName-first *sibling* entry point for hypothetical future callers; the finding here is that existing callers already hold the type name and the resolution polarity is simply backwards. Grammar shape settled in design discussion on 2026-07-13.

Two carve-outs have shipped ahead of this item and narrowed it. R581 took the decode-resolution polarity fix at the call sites holding an authoritative type name (see Phase 1 above). R580 (`infer-node-from-implements-node-and-metadata`) took type-level nodehood inference and, with it, rule 1's carrier at the output-side `FieldBuilder` site; the division of labour that settles is that R580 owns which *types* are nodes and what `Node.id` means, while this item owns what a directive-less or bare-directive `ID` means at every other coordinate. What this item still owes rule 1 is the `typeName:` rejection on `Node.id` and the input-side generalisation in rule 2.
