---
id: R473
title: "Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution"
status: Spec
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-13
last-updated: 2026-08-04
---

# Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution

An `ID`-typed field can still acquire node semantics implicitly, with the node identity derived from *table* facts (the catalog's `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants) rather than from what the schema author declared. Three sites still carry that inversion, each firing a per-site deprecation WARN and each answering "which node is this" from the backing table: the two input-side arms in `BuildContext.classifyInputField` (the qualifier-reverse-map arm and the bare same-table arm), and the surviving half of one output-side site in `FieldBuilder`.

That output-side site (a scalar `ID` on a `NodeType` parent with no directive at all) used to be a whole shim. R580 split it in two. The `Node.id` half was adopted as rule 1's permanent carrier: it resolves ahead of the column lookup, carries no deprecation, and is documented behaviour rather than a shim awaiting deletion. What remains a shim there is the *non-`id`* half, a bare `ID` field on a node type that is not the one satisfying the `Node` interface (`externalId: ID`), which still synthesises a nodeId from table facts and still warns. That half is rule 4's business and is still in this item's scope; the `Node.id` half is not.

Note the two halves now sit on opposite sides of the column lookup: the `Node.id` arm resolves *before* it and wins against a same-named column, the non-`id` arm fires only on a column *miss*. So deleting the non-`id` arm cannot flip a working field silently, it turns a column miss into the `unknownColumn` rejection rule 4 already prescribes.

R581 has since fixed the half of this that was actively breaking builds: the call sites holding an authoritative type name no longer route their decode resolution through the table. What is left is the inversion itself. As long as a directive-less `ID` can mean "node identity, resolved from the table", the schema does not say what it means, an author cannot tell the two readings apart by looking, and the table-first helper cannot be deleted.

The grammar below closes that by making the implicit reading available in exactly one place, where it cannot be ambiguous.

## The grammar

1. **`Node.id` is the only implicit nodeId.** The `id` field satisfying the `Node` interface on a type classified as a `NodeType`, however it got there, is obviously a nodeId and obviously of the enclosing type. The directive is redundant there (existing `id: ID! @nodeId` fixtures stay legal); `typeName:` is rejected there as contradiction-prone noise. **This rule is already live at the output-side `FieldBuilder` site** (see the intro), including the precedence over a same-named column that the antecedent's "however it got there" wording is there to cover: nodehood can be inferred from `implements Node` plus catalog metadata, so it is not `@node` presence that makes rule 1 apply. What this item still owes rule 1 is the *rejection* half, `typeName:` on that field, and the input-side generalisation in rule 2.
2. **Bare `@nodeId` (directive without `typeName:`) is legal only on output fields of a type that is a node type**, however it got there, where "current type" is well-defined; it is a generalization of rule 1. Stated over node-type membership rather than `@node` presence for the same reason rule 1 is: since R580 a type can be a node by declaring `implements Node` over a metadata-carrying table, and phrasing this rule over the directive would reject bare `@nodeId` on exactly the types inference exists to enable.
3. **Everywhere else (input fields, arguments, anything crossing to another type), node semantics require `@nodeId(typeName: T)`.** An `ID`-typed field without the directive has no node interpretation, full stop.
4. **`ID` without `@nodeId` is an ordinary scalar.** With `@reference` it is a regular column-mapped field at the end of the reference path, validated against the matching column exactly like any other scalar routed through `@reference`; without `@reference` it is a regular column-mapped field on the enclosing type's table, same validation, unless it is `Node.id` (rule 1). No node interpretation, no rejection: `ID` in SDL means an opaque identifier, not necessarily a graphitron NodeId, and the column's own value is a legitimate id surface. The current `@reference` branch (plain single-column `InputField.ColumnBackedReferenceField` with `CallSiteExtraction.Direct()`) is already the right semantics for that shape and stays; what changes is the directive-less non-reference case, which today gets shim-synthesized node semantics from table facts instead of plain column mapping.
5. **Decode resolution becomes typeName-first everywhere**: `NodeIndex.byName.get(typeName).decodeMethod()` (the by-name view already exists on `record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName)`). `resolveDecodeHelperForTable`, its multi-`@node`-per-table ambiguity arm, and its typeId-suffix fallback are deleted rather than guarded.

## Phase 1: shipped as R581

The type-bearing callers (`BuildContext.buildInputNodeIdReference`, `NodeIdLeafResolver.resolve`) route through `BuildContext.resolveDecodeHelperForType`, and `resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the table's metadata. That was carved out and shipped ahead of this plan because a field report made it urgent: a second `@node` over one table broke every already-declared `@nodeId(typeName:)` leaf on that table. `resolveDecodeHelperForTable` survives in the synthesis shims and in the bare-`@nodeId` argument arm of `FieldBuilder.classifyArgument`, which holds no type name to key on.

What remains for this item is the grammar itself: rules 1-4 as build behavior, and the deletion of the table-first helper.

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

* `graphitron-sakila-example`: no migration needed. All 16 bare `@nodeId` sites across the five `.graphqls` files are `Node.id` on a node type, which rules 1 and 2 keep legal. **The claim in an earlier draft of this item that the sakila INSERT-input fixture writes the bare form on an input is stale and was the main reason this plan looked more expensive than it is**; `CreateKeyedNodeInput` does write `@nodeId(typeName: "KeyedNode")`.
* Sakila's `FilmActor` in `schema.graphqls` is now the *fully* implicit rule-1 shape: `implements Node @table(name: "film_actor")` with a bare `id: ID!`, no `@node` and no `@nodeId`. `GraphQLQueryTest`'s `filmActorByNodeId` round-trip is therefore this item's execution-tier proof that rule 1 holds end to end, and it must stay green through the flip. The declared spelling over the same table is still covered by `federated-schema.graphqls`.
* `graphitron/src/test`: 23 input-field sites and 2 argument sites carry bare `@nodeId` and need `typeName:` added. ~157 output-field sites are `Node.id`-shaped and stay as they are.
* The two argument sites are `NodeIdPipelineTest`'s `barByIds(ids: [ID!]! @nodeId)` and `RejectNonIdNodeIdPipelineTest`'s `search(term: String @nodeId)`; the latter is a non-`ID` rejection fixture whose verdict must not change, so check which rejection wins and pin the order deliberately.

Rule 4 has no fixture-migration cost of its own but does have a verdict cost: the cases that pin shim-synthesized node semantics on a directive-less `ID` are the ones whose expected outcome inverts. `NodeIdPipelineTest.InputCase.IMPLICIT_ID`, `EXPLICIT_PERSON_ID`, `R377_ORPHAN_INPUT_TYPEID_FALLBACK` and `R377_MULTI_NODE_REJECTS` all encode the old semantics; each either flips to plain column mapping or moves to a `@nodeId(typeName:)` spelling that keeps testing what it was there to test. Read each one's stated intent before rewriting it, and where the intent was the shim itself, delete rather than translate.

### Flip and delete

Turn the opt-in on by default and delete it, then delete in one commit:

* The surviving synthesis shims: `FieldBuilder`'s output-side bare-scalar-`ID`-on-a-`NodeType` branch, now narrowed to fields *other* than `Node.id` (the `Node.id` arm above it is permanent and must be left alone), and `BuildContext.classifyInputField`'s two input-side sites (the qualifier-reverse-map arm feeding `buildInputNodeIdReference`, and the bare same-table arm guarded by `catalog.nodeIdMetadata`).
* `BuildContext.resolveDecodeHelperForTable` with them, which leaves `resolveDecodeHelperForType`'s fallback arm dead; collapse that method to the `NodeIndex.forName` lookup and let it return the `Optional` rather than `null`.
* `NodeIdLeafResolver.inferTypeName`'s table-lookup arm and `BuildContext.findGraphQLTypeForTable` (the singular, shim-only helper). `findGraphQLTypesForTable` (plural) has other callers; check before touching it.
* The three deprecation WARNs at those sites: `FieldBuilder`'s "synthesizes an `@nodeId` carrier without the directive", and `BuildContext`'s two (`ID_REF_SHIM_LOGGER`, `NODE_ID_SHIM_LOGGER`). **Leave `warnShadowedIdColumn` alone.** It sits in the same method, immediately above the surviving shim arm, and it is not a deprecation: it is the permanent `LintRule.NODE_ID_SHADOWS_COLUMN` finding that rule 1's column precedence requires. A sweep of "the warnings at this site" would take it out.
* `IdReferenceShimWarnFormatTest`, which pins the qualifier-arm WARN's text. It is the only warn-format test in scope. **`AsConnectionSameTableWarnFormatTest` is not**, despite the similar name: it covers the `@asConnection` same-table warning and has nothing to do with these shims. No test asserts the `FieldBuilder` WARN's text at all, so that one needs no test change.

R27 (`retire-synthesis-shims`) is the item that has carried the shim deletion; it should be discarded into this one at that point rather than left to delete an empty set. Confirm with whoever picks R27 up first.

## Tests

Per-rule rejection cases in the validator's own test class, each pinning the source location as well as the message; the LSP reads both. Beyond that the work is mostly the fixture migration above, and the honest test of this change is that the migrated suite stays green with the shims gone.

One case worth adding that does not exist today: an input field whose `ID` column name collides with a node-bearing FK qualifier, under rule 4, pinning that it maps to the plain column rather than decoding. That is the transition hazard named below, and it is the one shape where a silent semantic flip is possible rather than a build error.

## Consumer migration

Per user confirmation on 2026-07-13, no consumer relies on the shim behavior today, so R27's written gate (sis migrated, plus one external-consumer release window) is more conservative than reality and this does not need to wait it out. **That confirmation is now over a year old at the time of writing and predates the utdanningsregisteret federation work; re-confirm before flipping, because the cost of being wrong is a consumer build that fails with no migration path staged.**

The hazard is a legacy column-named `ID` field (`customerId: ID`) on an *input* type flipping from shim-synthesized node decode to raw column mapping, at the qualifier-reverse-map arm. That arm runs *ahead* of the column lookup, so a field whose name hits both the qualifier map and a real column changes meaning with no build error. It is the only silent change left in this item's set: every other rule turns a working build into a failing one with a message naming the fix, and the surviving output-side arm fires only on a column miss (see the intro), so deleting it rejects rather than flips. If re-confirmation turns up any consumer at all, stage that arm behind a WARN for one release before it changes semantics, and leave rules 1-3 as errors immediately since they cannot flip anything silently.

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

## Provenance

Supersedes, in stronger form, the discarded R263 (`decode-helper-typename-first-resolution`, see the 2026-07-13 changelog entry and its re-open trigger): R263 proposed a typeName-first *sibling* entry point for hypothetical future callers; the finding here is that existing callers already hold the type name and the resolution polarity is simply backwards. Grammar shape settled in design discussion on 2026-07-13.

Two carve-outs have shipped ahead of this item and narrowed it. R581 took the decode-resolution polarity fix at the call sites holding an authoritative type name (see Phase 1 above). R580 (`infer-node-from-implements-node-and-metadata`) took type-level nodehood inference and, with it, rule 1's carrier at the output-side `FieldBuilder` site; the division of labour that settles is that R580 owns which *types* are nodes and what `Node.id` means, while this item owns what a directive-less or bare-directive `ID` means at every other coordinate. What this item still owes rule 1 is the `typeName:` rejection on `Node.id` and the input-side generalisation in rule 2.
