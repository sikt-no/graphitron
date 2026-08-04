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

An `ID`-typed field can still acquire node semantics implicitly, with the node identity derived from *table* facts (the catalog's `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants) rather than from what the schema author declared via `@node`. Three synthesis shims carry that inversion: two input-side sites in `BuildContext.classifyInputField` (the qualifier-reverse-map arm and the bare same-table arm) and one output-side site in `FieldBuilder` (a scalar `ID` on a `NodeType` parent with no directive at all). Each fires a per-site deprecation WARN and each answers "which node is this" from the backing table.

R581 has since fixed the half of this that was actively breaking builds: the call sites holding an authoritative type name no longer route their decode resolution through the table. What is left is the inversion itself. As long as a directive-less `ID` can mean "node identity, resolved from the table", the schema does not say what it means, an author cannot tell the two readings apart by looking, and the table-first helper cannot be deleted.

The grammar below closes that by making the implicit reading available in exactly one place, where it cannot be ambiguous.

## The grammar

1. **`Node.id` is the only implicit nodeId.** The `id` field satisfying the `Node` interface on a type declared `implements Node @node` is obviously a nodeId and obviously of the enclosing type. The directive is redundant there (existing `id: ID! @nodeId` fixtures stay legal); `typeName:` is rejected there as contradiction-prone noise.
2. **Bare `@nodeId` (directive without `typeName:`) is legal only on output fields of the enclosing `@node` type**, where "current type" is well-defined; it is a generalization of rule 1.
3. **Everywhere else (input fields, arguments, anything crossing to another type), node semantics require `@nodeId(typeName: T)`.** An `ID`-typed field without the directive has no node interpretation, full stop.
4. **`ID` without `@nodeId` is an ordinary scalar.** With `@reference` it is a regular column-mapped field at the end of the reference path, validated against the matching column exactly like any other scalar routed through `@reference`; without `@reference` it is a regular column-mapped field on the enclosing type's table, same validation, unless it is `Node.id` (rule 1). No node interpretation, no rejection: `ID` in SDL means an opaque identifier, not necessarily a graphitron NodeId, and the column's own value is a legitimate id surface. The current `@reference` branch (plain single-column `InputField.ColumnBackedReferenceField` with `CallSiteExtraction.Direct()`) is already the right semantics for that shape and stays; what changes is the directive-less non-reference case, which today gets shim-synthesized node semantics from table facts instead of plain column mapping.
5. **Decode resolution becomes typeName-first everywhere**: `NodeIndex.byName.get(typeName).decodeMethod()` (the by-name view already exists on `record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName)`). `resolveDecodeHelperForTable`, its multi-`@node`-per-table ambiguity arm, and its typeId-suffix fallback are deleted rather than guarded.

## Phase 1: shipped as R581

The type-bearing callers (`BuildContext.buildInputNodeIdReference`, `NodeIdLeafResolver.resolve`) route through `BuildContext.resolveDecodeHelperForType`, and `resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the table's metadata. That was carved out and shipped ahead of this plan because a field report made it urgent: a second `@node` over one table broke every already-declared `@nodeId(typeName:)` leaf on that table. `resolveDecodeHelperForTable` survives in the synthesis shims and in the bare-`@nodeId` argument arm of `FieldBuilder.classifyArgument`, which holds no type name to key on.

What remains for this item is the grammar itself: rules 1-4 as build behavior, and the deletion of the table-first helper.

## Implementation

Ordering is a real seam here, so the sections below land as three commits, each independently green. The rejections must exist and be quiet before they can be turned on, and the fixture migration must be complete before the shims can go.

### Rejections, off by default

Rules 1-3 are three distinct rejections. All three go in `GraphitronSchemaValidator` alongside `validateNodeTypeIdUniqueness`, not in the classifier: they are SDL-shape verdicts, they need no catalog or index state, and the validator's report already carries a source location per error, which is what makes the diagnostic actionable in the LSP.

* Rule 1: `typeName:` on the `id` field satisfying `Node` on a `@node` type. The named type either agrees with the enclosing type (redundant) or contradicts it (a bug the author cannot have meant), so the argument is rejected rather than checked.
* Rule 2: bare `@nodeId` on an output field of a type that is not a `@node`. Today this reaches `FieldBuilder.classifyArgument`'s inference or falls through to a column miss; the verdict moves to a named rejection that says to add `typeName:`.
* Rule 3: bare `@nodeId` on an input field or an argument. The current inference (`NodeIdLeafResolver.inferTypeName`, the `findGraphQLTypesForTable` arm) is what this replaces, and it is the arm carrying the two friendly diagnostics `BARE_NODE_ID_NO_OBJECT_TYPE` and `BARE_NODE_ID_AMBIGUOUS_OBJECT_TYPES` already pin. The new rejection subsumes both: it fires on the unique-match case too, which is the whole point.

Land them behind a single build-level opt-in so the flip is one switch rather than three, and so the fixture migration below can proceed against a build that fails loudly for a session and quietly for everyone else. The opt-in mechanism is the implementer's call; a Mojo parameter is the obvious candidate, but if that adds a user-facing surface we would then have to retire, a package-private constant flipped in the fixture-migration commit is the cheaper answer. Decide before writing the rejection code, and say which in the commit.

### Fixture migration

The measured surface as of 2026-08-04 (re-measure at pickup; the counts move):

* `graphitron-sakila-example`: no migration needed. Every bare `@nodeId` in the four `.graphqls` files is `Node.id` on a `@node` type, which rules 1 and 2 keep legal. **The claim in the previous draft of this item that the sakila INSERT-input fixture writes the bare form on an input is stale and was the main reason this plan looked more expensive than it is.**
* `graphitron/src/test`: roughly 23 input-field sites and 2 argument sites carry bare `@nodeId` and need `typeName:` added. ~149 output-field sites are `Node.id`-shaped and stay as they are.
* The two argument sites are `NodeIdPipelineTest`'s `barByIds(ids: [ID!]! @nodeId)` and `RejectNonIdNodeIdPipelineTest`'s `search(term: String @nodeId)`; the latter is a non-`ID` rejection fixture whose verdict must not change, so check which rejection wins and pin the order deliberately.

Rule 4 has no fixture-migration cost of its own but does have a verdict cost: the cases that pin shim-synthesized node semantics on a directive-less `ID` are the ones whose expected outcome inverts. `NodeIdPipelineTest.InputCase.IMPLICIT_ID`, `EXPLICIT_PERSON_ID`, `R377_ORPHAN_INPUT_TYPEID_FALLBACK` and `R377_MULTI_NODE_REJECTS` all encode the old semantics; each either flips to plain column mapping or moves to a `@nodeId(typeName:)` spelling that keeps testing what it was there to test. Read each one's stated intent before rewriting it, and where the intent was the shim itself, delete rather than translate.

### Flip and delete

Turn the opt-in on by default and delete it, then delete in one commit:

* The three synthesis shims: `FieldBuilder`'s output-side bare-scalar-`ID`-on-a-`NodeType` branch (the `buildNodeIdOutputCarrier` call guarded by `hasFieldDirective`), and `BuildContext.classifyInputField`'s two input-side sites (the qualifier-reverse-map arm feeding `buildInputNodeIdReference`, and the bare same-table arm guarded by `catalog.nodeIdMetadata`).
* `BuildContext.resolveDecodeHelperForTable` with them, which leaves `resolveDecodeHelperForType`'s fallback arm dead; collapse that method to the `NodeIndex.forName` lookup and let it return the `Optional` rather than `null`.
* `NodeIdLeafResolver.inferTypeName`'s table-lookup arm and `BuildContext.findGraphQLTypeForTable` (the singular, shim-only helper). `findGraphQLTypesForTable` (plural) has other callers; check before touching it.
* The three deprecation WARNs and the two `*ShimWarnFormatTest` classes.

R27 (`retire-synthesis-shims`) is the item that has carried the shim deletion; it should be discarded into this one at that point rather than left to delete an empty set. Confirm with whoever picks R27 up first.

## Tests

Per-rule rejection cases in the validator's own test class, each pinning the source location as well as the message; the LSP reads both. Beyond that the work is mostly the fixture migration above, and the honest test of this change is that the migrated suite stays green with the shims gone.

One case worth adding that does not exist today: an input field whose `ID` column name collides with a node-bearing FK qualifier, under rule 4, pinning that it maps to the plain column rather than decoding. That is the transition hazard named below, and it is the one shape where a silent semantic flip is possible rather than a build error.

## Consumer migration

Per user confirmation on 2026-07-13, no consumer relies on the shim behavior today, so R27's written gate (sis migrated, plus one external-consumer release window) is more conservative than reality and this does not need to wait it out. **That confirmation is now over a year old at the time of writing and predates the utdanningsregisteret federation work; re-confirm before flipping, because the cost of being wrong is a consumer build that fails with no migration path staged.**

The theoretical hazard is a legacy column-named `ID` field (`customerId: ID`) flipping from shim-synthesized node decode to raw column mapping. That is the only silent change in the set: every other rule turns a working build into a failing one with a message naming the fix. If re-confirmation turns up any consumer at all, stage rule 4 behind a WARN for one release before it changes semantics, and leave rules 1-3 as errors immediately since they cannot flip anything silently.

R34 (`nodeid-migration-quickfix`) ships LSP quick fixes that derive the `typeName:` value from the shim's own facts. It is Backlog and unstarted, and this item does not depend on it: the migration is ~25 fixture sites in-tree and, per the above, nothing out of tree. If a consumer does turn up, R34 becomes the humane path and the dependency turns real.

## Spec review notes, 2026-08-04 (revisions requested, status stays Spec)

Independent Spec -> Ready review. The grammar itself reads well and rules 3 and 5 check out
against the tree; the blocking findings are all collisions with work that has moved since this
body was written, plus three code claims that would send an implementer to the wrong place.

**1. R580 is `Ready` and owns two of this item's implementation instructions.**
`roadmap/infer-node-from-implements-node-and-metadata.md` (`Ready`, same `nodeid` theme, decision
recorded 2026-08-04) makes `implements Node` + `__NODE_*` metadata mint a `NodeType` without
`@node`, and its Phase 2 takes over the output-side `FieldBuilder` site. Neither item declares a
dependency on the other, so the ordering is undeclared anywhere. Three concrete conflicts:

* Rule 1's antecedent, "a type declared `implements Node @node`", stops being the right
  condition once `@node` is no longer necessary to be a node type. R580's Reconciliation
  section asks for exactly this edit: restate it as a type classified as a `NodeType`, however it
  got there.
* The "Flip and delete" list says to delete `FieldBuilder`'s output-side
  bare-scalar-`ID`-on-a-`NodeType` branch. R580 Phase 2 says to *keep* that branch, drop its
  WARN, narrow its predicate to the `Node`-interface `id` field, and make it the permanent rule-1
  carrier. One of the two has to give; whichever way it lands, this item's shim inventory needs to
  stop counting that site as one of three.
* Rule 1's precedence against column resolution is unstated here and settled there. Today
  `FieldBuilder.classifyChildFieldOnTableType` resolves the column before the `NodeType` arm, so
  on a `@node` type over a table with a literal `id` column a bare `id: ID!` maps to the raw
  column. Rule 4's "unless it is `Node.id` (rule 1)" reads as rule 1 pre-empting that, which is a
  silent wire-format change and contradicts this item's "That is the only silent change in the
  set". R580 Phase 3 owns that flip (warn-and-flip, 29 measured fixture sites). Either defer to
  it explicitly or say rule 1 fires only on a column miss.

**2. `validateNodeTypeIdUniqueness` is not in `GraphitronSchemaValidator`.** It is
`TypeBuilder.validateNodeTypeIdUniqueness` (`TypeBuilder.java:475`), so the "alongside" anchor
for placing rules 1-3 points at the wrong class. The placement conclusion still holds and is
worth keeping: `GraphitronSchemaValidator.validate` takes the classified `GraphitronSchema`, and
`GraphitronType.InputType` / `ObjectType` expose `schemaType()` while `GraphitronField` carries
`definition`, so the SDL directive facts rules 1-3 need are all reachable without catalog or
index state. Repoint the sentence at a real neighbour.

**3. Rule 2 already exists as build behavior.** Bare `@nodeId` on an output field whose parent is
not a `NodeType` is rejected today at `FieldBuilder.classifyChildFieldOnTableType`
(`FieldBuilder.java:7201-7202`) with "@nodeId requires the containing type to be a node type (via
@node or KjerneJooqGenerator metadata)". The spec's "today this reaches
`FieldBuilder.classifyArgument`'s inference or falls through to a column miss" names the argument
path, not the output path, and neither alternative is what happens. So rule 2's work is
relocating an existing rejection for a source location and a better message, not adding a
verdict. Say which, and say whether the classifier rejection stays as the mirror that
"Rejections: validator mirrors classifier invariants" prescribes.

**4. "The three deprecation WARNs and the two `*ShimWarnFormatTest` classes" is wrong on all
three counts.** Exactly one class matches that pattern (`IdReferenceShimWarnFormatTest`); the
other warn-format test is `AsConnectionSameTableWarnFormatTest`, which covers the
`@asConnection` same-table warning and must not be deleted with these shims; and the output-side
WARN (`FieldBuilder.java:7260`, "synthesizes an `@nodeId` carrier without the directive") has no
test asserting its text at all. R580 states the same finding independently.

Verified clean, for the record: the sakila-example de-scoping claim holds (all 17 bare `@nodeId`
sites across the five `.graphqls` files are `id: ID!` on `type X implements Node ... @node`;
`CreateKeyedNodeInput` does write `@nodeId(typeName: "KeyedNode")`, so the previous draft's
INSERT-input claim was indeed stale). Both named argument fixtures exist as written, and
`search(term: String @nodeId)` is the only non-`ID` one. All four `NodeIdPipelineTest.InputCase`
names exist. Rule 4's `@reference` claim matches `BuildContext.java:2525-2528`
(`InputField.ColumnBackedReferenceField` + `CallSiteExtraction.Direct()`). Rule 5's by-name view
exists as `NodeIndex.forName` returning `Optional<NodeType>`. `@node` plus `implements Node` is
today the only way to mint a `NodeType` (`TypeBuilder.java:1308-1311`), so rule 2's "not a
`@node`" phrasing is precise on current trunk, which is exactly what finding 1 changes.

Opportunities, author's call, not blocking:

* The opt-in mechanism menu and the rejection tests interact. A package-private constant flipped
  in the fixture-migration commit cannot be toggled per test, so the per-rule rejection cases
  cannot land in the same commit as the rejection code, and commit 1 ships unreachable
  unexercised branches. Worth naming as a constraint on the choice the spec asks the implementer
  to make up front.
* The existing rejection message at `FieldBuilder.java:7202` says "via @node or
  KjerneJooqGenerator metadata", which is false on current trunk and becomes true only after
  R580 Phase 1. This item rewrites that exact rejection, so it is well placed to leave the
  message accurate for whichever ordering wins.
* The LSP is a named beneficiary of the source locations but no LSP surface is listed.
  `Diagnostics.validateNodeType` and `LspVocabulary`'s `@nodeId(typeName:)` completion binding
  both assume the current grammar; worth a sentence on whether they need anything.
* Input-field rejections reached through the registry walk (`validateInputType`) versus per
  consuming field (`validateInputFieldRecursive`) differ in coverage for an input type that is
  declared but never consumed. Probably fine, worth deciding rather than discovering.
* The measured input-field count reads low. A rough re-scan puts bare `@nodeId` input-field sites
  in `graphitron/src/test` above the stated ~23, and R580 records a comparable undercount from
  single-line greps missing multi-line declarations. The spec already says to re-measure at
  pickup, so this is a nudge to use a multi-line-aware scan when doing so.

## Provenance

Supersedes, in stronger form, the discarded R263 (`decode-helper-typename-first-resolution`, see the 2026-07-13 changelog entry and its re-open trigger): R263 proposed a typeName-first *sibling* entry point for hypothetical future callers; the finding here is that existing callers already hold the type name and the resolution polarity is simply backwards. Grammar shape settled in design discussion on 2026-07-13.
