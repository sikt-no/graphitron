---
id: R580
title: "Infer @node from `implements Node` + __NODE_* metadata"
status: In Review
bucket: architecture
priority: 8
theme: nodeid
depends-on: []
created: 2026-08-03
last-updated: 2026-08-06
---

# Infer @node from `implements Node` + __NODE_* metadata

A type declared `implements Node @table(name: "X")` whose backing jOOQ class publishes
`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` should classify as a `NodeType` without the author
restating those values in `@node`. Decision taken 2026-08-04: this is the correct behaviour, and
the current opt-in-on-`@node` rule is the defect.

Today `TypeBuilder.buildTableType` reads the metadata, runs the malformed-metadata diagnostic, and
then returns a plain `TableType` when `@node` is absent. What happens to the type's `id: ID!` then
splits on a detail of the backing table, and **both halves matter for scoping this item**:

- **No column literally named `id`** (`film_actor`, `bar`): the field falls through to ordinary
  column mapping and is rejected as `UnclassifiedField("column 'id' could not be resolved in the
  jOOQ table; did you mean: ...")`. The rejection is accurate about the mechanism and useless about
  the cause: the author is not misspelling a column, and the did-you-mean list actively pushes them
  toward `@field(name: "...")`, which would satisfy the classifier while publishing a broken `Node`
  contract. This is the reported symptom.
- **A column literally named `id` exists** (`baz`, whose PK is `id varchar(50)`): the column lookup
  succeeds, and the schema **builds green today** as `TableType` + `ColumnBackedField(Direct)`.
  Nothing rejects `implements Node` without `@node`; the only rule is the converse.

So this item does not only repair already-failing builds. It promotes a currently-green population,
and the shape of that population is a surrogate-`id`-PK table carrying node metadata, which is
plausibly the shape of the incident types described below. Scope the risk against that reading, not
against the first bullet alone.

## The rule

When a `@table` object type declares `implements Node`, carries no `@node`, and
`JooqCatalog.nodeIdMetadata(table)` is `Present`, promote it to `NodeType` with `typeId` and
`keyColumns` taken from the metadata. This is exactly the values-resolution the existing
`@node`-plus-metadata path computes when the author declares `@node` with no arguments, so the
implementation is a reachability change at the `hasNode` gate rather than a new resolution path.

Unchanged on every other axis:

- **`@table` + metadata without `implements Node` stays a `TableType`.** Publishing the Relay
  contract is the author's SDL-level opt-in and remains load-bearing. This is what keeps the
  `shared_node` fixture honest: `SharedNodeProjection @table(name: "shared_node")` is a nesting
  projection over a node-bearing table and must not become a second node.
- **Explicit `@node` still wins.** The SDL-versus-metadata merge rules (typeId overrides silently,
  key-column set disagreement is a hard error, order disagreement warns) are untouched. Inference
  fires only in the directive's absence, so an author who needs a different wire typeId writes it.
- **`@node` without `implements Node` is still rejected**, and the unconditional malformed-metadata
  diagnostic still runs ahead of everything.

## The collision hazard, and why `implements Node` is load-bearing

`TypeBuilder.buildTableType`'s comment justifies opt-in promotion on the grounds that inferring from
metadata "would silently collide typeIds across types whose backing tables share `__NODE_TYPE_ID`,
with no SDL-side opt-out". That is not a hypothetical: `R27`'s History section records the incident
behind it. A metadata-only auto-promotion shim lived at this exact site and was retired on consumer
feedback after roughly 200 sis event types, all backed by tables sharing
`__NODE_TYPE_ID = "195"`, promoted in lockstep and were then symmetrically demoted to
`UnclassifiedType` by `TypeBuilder.validateNodeTypeIdUniqueness`. A working build became hundreds of
simultaneous errors.

Two things separate that shim from this proposal, and the item stands or falls on the first:

1. **The retired shim promoted on metadata alone. This one requires `implements Node`.** A `@table`
   event type that never published the Relay contract does not promote here, so the lockstep
   promotion that caused the incident does not occur for those ~200 types unless they also declare
   `implements Node`. This is the central safety claim and it is **unverified against the real sis
   schema**; see Evidence required below. If a meaningful number of sis types do pair
   `implements Node` with tables sharing a typeId, this item reproduces the incident in miniature
   and the design needs a per-collision-group fallback rather than a mass rejection.

   Note what the gate does *not* do. It is a filter on SDL declarations, not on table shapes, so it
   carries no structural argument that the incident population is excluded. Combined with the
   green-build finding above, the honest statement is: the gate excludes exactly those types that
   never wrote `implements Node`, and whether that is most of the ~200, all of them, or none is an
   empirical question about a schema in another repository. Do not let the gate's existence stand in
   for the census.
2. **The collision is diagnosed, not silent, and has a stated opt-out.**
   `validateNodeTypeIdUniqueness` registers a build-time diagnostic naming every member of a
   colliding group, and its message already names the escape hatch: "pick one via `@node(typeId:)`".
   Explicit `@node` continues to override inference. This is real mitigation but it does not scale
   to a 200-type lockstep group, which is precisely what the incident demonstrated: a diagnostic per
   colliding type is not a usable migration when the group is that large. Treat this as a reason the
   failure is *legible*, not a reason it is *acceptable*.

Spec should also confirm the collision message reads sensibly when one or both sides are inferred
(it currently says "is declared on multiple types", which is imprecise for an inferred typeId) and
add a pipeline case for the inferred-collision shape.

**A second collision axis the uniqueness check does not cover.**
`BuildContext.resolveDecodeHelperForTable` forks on `nodes.forTable(t).size()`: one node resolves to
`decode<TypeName>`, two or more returns null and the caller rejects with "zero or multiple GraphQL
types map to it", zero falls back to a typeId-suffixed helper name. Widening the node population
moves tables across both boundaries. One to two turns a previously-fine input-shim site into a build
failure whose message blames the input field for a change made on an unrelated output type. Zero to
one silently stops the typeId-suffix fallback firing and changes the emitted helper name, which is a
codegen change on schemas that touch nothing this item owns. The typeId-uniqueness argument above
says nothing about this axis; Spec needs a pipeline case for the one-to-two transition.

## Consumers of the "is a node" predicate

Today `@node` presence and `NodeType` membership are the same set, so several sites read the
directive straight off SDL and stay consistent by coincidence. Inference splits the two, and each of
these is a place where an inferred node and an explicit one would behave differently:

- **`SchemaReachability` seeds on `getAppliedDirectives("node")`.** A `@node` type self-seeds
  reachability; an inferred one would not. `NodeIndex`'s own javadoc leans on this ("a `@node`
  self-seeds reachability, so the index and the pruned registry agree on the consulted domain"). An
  inferred node reachable only through `Query.node` or `@nodeId(typeName:)` would be pruned from the
  registry while remaining in the index, which puts a hole in `validateNodeTypeIdUniqueness` itself,
  since that check iterates `ctx.typeRegistry.entries()`. The collision argument above depends on
  this not happening.
- **`ArrivalIndex` uses the same seed predicate**, so arrival folding diverges the same way.
- **`KeyNodeSynthesiser.apply` runs pre-classification on the raw `TypeDefinitionRegistry`** and
  gates on `hasNodeDirective(obj)`. An inferred node silently drops out of the federation `_Entity`
  union while an otherwise identical explicit one is an entity. This one cannot be fixed by reading
  the classifier, because it runs before classification: either it performs the same metadata probe,
  or the Spec states federation-invisibility for inferred nodes as an accepted limitation.
- **`CatalogBuilder.buildNodeMetadata`** (the LSP node view) reads the directive, so the
  `@nodeId(typeName:)` LSP arms would not see inferred nodes.
- **`BuildContext.resolveTargetKeys`' SDL fallback arm**, probably unreachable for inferred nodes
  since the metadata arm fires first, but the Spec should say so rather than leave it.

The stronger shape, and what "a reachability change at the `hasNode` gate" hides: name the predicate
once as a single classifier-side function and have the post-classification consumers call it instead
of re-reading the directive. The two that genuinely cannot (raw-registry stage) get an explicit
accepted-limitation note. Treat the count of these sites as the real scope of Phase 1.

## Relationship to the nodeId grammar track

`R273` records this deliverable as "contradicted, not pending", citing `R473`, `R34` and `R27`. That
reading conflates two axes, and the distinction is what makes this change safe to take:

- `R473` is about a **field** acquiring node semantics from table facts: a bare `ID` field or
  argument that has declared nothing gets an implicit decode because its table happens to carry
  `__NODE_*`. Its grammar closes that off, correctly, and this item does not reopen it. Inputs,
  arguments, and cross-type references still require `@nodeId(typeName: T)`.
- This item is about a **type** that has already published nodehood in SDL getting its two identity
  parameters filled in from the catalog. The declaration of nodehood stays in SDL, where `R473`
  wants it; only `typeId` and `keyColumns` are sourced from the generator that owns them.

`R473` rule 1 is the direct precedent for the shape of the argument: "`Node.id` is the only implicit
nodeId. The `id` field satisfying the `Node` interface on a type declared `implements Node @node` is
obviously a nodeId and obviously of the enclosing type. The directive is redundant there." This item
extends the same "obviously" one step outward, from the `id` field to the `@node` directive itself:
where the jOOQ generator has published the node identity and the author has published the Node
contract, restating the two values is the same redundancy `R473` removes at the field level.

**But that behaviour is a deprecated shim today, and this item cannot ship on top of it unchanged.**
Bare `Node.id` does encode without `@nodeId` (confirmed against `bar`: `implements Node @table @node`
with a bare `id: ID!` classifies as `ColumnBackedField` with
`compaction=NodeIdEncodeKeys(encodeFoo)` over both key columns). It does so through `R27` Shim 1
site A, the branch at `FieldBuilder.java:7255-7265`, which fires a per-occurrence deprecation WARN
("declare `@nodeId` explicitly. The synthesis shim will be removed in a future release") and which
`R27` is chartered to delete and flip to a terminal classifier error. So on current trunk an
inferred node type would infer its own `@node`, then warn on its own `id` field that the author
should have written a directive, and would hard-fail once `R27` lands.

That is incoherent, so this item owns the reconciliation: adopt `R473` rule 1 at that site, making
the implicit `Node.id` carrier permanent for `NodeType` parents and dropping its deprecation WARN,
while leaving the other two shim sites (the input-scalar arm and the FK-qualifier arm) untouched for
`R27` to retire. The alternative, sequencing strictly behind `R473`, is discussed under Sequencing.
No test asserts the WARN's text (the two `*WarnFormatTest` classes cover the `@asConnection`
same-table and id-reference warnings, not this one), so dropping it is contained.

Reconciliation work this item owns at Spec time:

- **Rewrite `R473` rule 1's statement of scope.** This is the reconciliation most likely to drift,
  because Phase 2's whole justification is "adopt rule 1". Rule 1 is currently stated over "a type
  declared `implements Node @node`", and `R473`'s intro enumerates the output-side `FieldBuilder`
  site as one of three shims. Phase 2 makes `@node` non-necessary for that antecedent and converts
  the site from shim to permanent carrier. Restate the antecedent as "a type classified as a
  `NodeType`, however it got there" and move the site out of the shim inventory in the same edit, or
  the two specs will disagree about what rule 1 covers.
- Rewrite `R273`'s second bullet. The deliverable is live, not dropped, and `R273`'s surviving scope
  (the bare scalar-`ID` argument arm) is unaffected by it.
- Re-scope `R34` step 3. Its type-level hint offering `implements Node @node` is moot for the
  metadata-present case once inference lands; what survives is the narrower hint offering
  `implements Node` on a `@table` type whose backing class carries metadata but which has not
  published the interface. Steps 1 and 2 (the field-level shim quick fixes) are untouched.
- Note the reversal in `R27`, which records the earlier removal as deliberate.
- Rewrite the `TypeBuilder.buildTableType` comment; it states the retired policy and the stale
  rationale above.

**Reconciliation added 2026-08-06, after `R473`'s pass-3 gate.** `R473` coined a rule covering the
directive-less `ID` coordinate at arguments and input fields, resolved off the target rather than
off a metadata read at the use site, and generalized bare `@nodeId` to inherit its target at every
coordinate. Two consequences land on this item and the two must not ship disagreeing:

- **Shadowing is one rule across all three coordinates, and it is an error.** This item owns the
  decision and states it (see "The shadowed `id` column"); `R473` generalises it to input fields and
  arguments without restating it. If either item is revised on this point, both change.
- **This item's `id: ID! @nodeId` spelling stays exactly as documented.** `R473` rule 1 keeps the
  directive redundant-but-legal on `Node.id`, and its rule 2 makes the bare form legal everywhere,
  so nothing this item ships needs a `typeName:` migration afterwards.

## The shadowed `id` column

A table can carry `__NODE_*` metadata *and* a column literally named `id`.

**Decided 2026-08-04, revised 2026-08-06: the build fails and the author must disambiguate.**
`@nodeId` on the field pins the node interpretation; `@field` pins the column. The original decision
was that the node metadata wins and the build emits a *warning* naming those two silencers, and that
is what shipped (see "What shipped"). It is superseded: a warning still picks one of two plausible
readings the SDL does not choose between, it only narrates the pick, and the reading it picks
changes the Relay global id on the wire. R473's grammar work made the same call at the input and
argument coordinates and the two items must agree, so the rejection is stated here, at the item that
owns the shadowing decision, and R473 generalises it outward rather than restating it.

**This revision has an in-tree cost the warning did not, and it is worth seeing before implementing.**
`baz` is the suite's generic node fixture table and is degenerate in exactly the shadowing way: one
column, `ID`, which is also its node key. So `type Baz implements Node @table(name: "baz") @node
{ id: ID! }` is a shadowing coordinate, and that spelling appears roughly 34 times across
`graphitron/src/test`, mostly in classes with nothing to do with node ids
(`InlineFilterArgumentSourcePipelineTest`, `ReferenceFilterRemoteColumnPipelineTest`,
`AsConnectionSameTableWarnFormatTest`, many `NodeIdPipelineTest` cases). Three more sit on the
inference path over `shared_node` and `keyed_elsewhere` in `NodeInferencePipelineTest`. Each is a
one-word edit, adding `@nodeId` where the fixture wants the node or `@field(name: "id")` where it
wants the column, and **no `graphitron-sakila-example` site is affected**: `film_actor` is the only
sakila table publishing metadata and it has no `id` column. Re-measure at pickup.

The residual concern is friction rather than effort: this makes the obvious spelling of the most
reused node fixture in the suite a build error, so fixture authors will meet this message often.
That is an argument for the message being excellent, not for softening the rule. If it proves
intolerable, the fix is to move the generic node fixture onto a non-shadowing table such as `bar`,
not to reintroduce the guess.

This is not only a migration concern for inferred nodes. It is a live defect on the **explicit**
`@node` path, which makes it a correctness fix this item carries rather than a compatibility rider.
Observed against the `baz` fixture (metadata `typeId = "Baz"`, `__NODE_KEY_COLUMNS = { BAZ.ID }`, and
the key column is itself named `id`):

| SDL | Type verdict | `id` compaction |
|---|---|---|
| `baz`, `implements Node @table @node`, bare `id: ID!` | `NodeType` | `Direct` (raw column) |
| `baz`, same plus `id: ID! @nodeId` | `NodeType` | `NodeIdEncodeKeys(encodeFoo)` |
| `baz`, same plus `id: ID! @field(name: "id")` | `NodeType` | `Direct` |
| `shared_node`, `implements Node @table @node(typeId: "10154")`, bare `id: ID!` | `NodeType` | `Direct` (raw column) |
| `bar` (no literal `id` column), `implements Node @table @node`, bare `id: ID!` | `NodeType` | `NodeIdEncodeKeys(encodeFoo)` |

Row 4 is the row to reason from, not row 1. `baz` is convenient but degenerate: its typeId is the
literal string `"Baz"`, so nothing distinguishes the encoded form from the type name.
`nodeidfixture.shared_node` (`init.sql`, `id varchar(50) PRIMARY KEY`, metadata
`("10154", ["ID"])`) is the same shadowing shape with a *numeric* typeId that differs from the type
name, which is exactly the axis `BuildContext.resolveDecodeHelperForTable`'s typeId-suffix fallback
turns on. It is also already written down: `NodeIdPipelineTest`'s
`R377_DECODE_VIA_NODE_INDEX_NOT_TYPEID` declares
`type SharedNode implements Node @table(name: "shared_node") @node(typeId: "10154") { id: ID! }` and
asserts on the decode helper's name. So the shadowing rule changes the wire value under a case whose
whole point is decode-helper resolution, and `baz` is not the only affected fixture table.

So a type that declares `@node` today, and whose table happens to have an `id` column, silently
publishes the raw column value as its Relay global id, while the identical declaration over a table
without that column collision publishes an encoded one. Nothing warns. The rule above surfaces the
case instead of picking a default for it.

Note the table's bare rows (1 and 4) describe the *pre-revision* verdicts. Under the revised rule
neither resolves at all: both reject until the author adds `@nodeId` or `@field`. Rows 2, 3 and 5
are unchanged, and rows 2 and 3 are exactly the two silencers, so both remedies are live today and
need no new machinery. The variant the original spec asked for has since gained a fixture:
`keyed_elsewhere` (`NodeIdFixtureGenerator`, metadata keyed on `KEY_X`) has columns `KEY_X`, `ID`,
`NAME`, so the shadowed `id` column is *not* a key column and the two readings are different
columns rather than two encodings of one. That shape rejects on the same terms.

The single implementation site is the column-lookup-first ordering at `FieldBuilder.java:7247-7271`:
`svc.resolveColumn(columnName, tableType)` runs before the `NodeType` arm, so a successful lookup
short-circuits to `Direct` and the node interpretation never gets a turn. `hasFieldDirective`
(line 7208) is already the `@field` opt-out. The shipped fix hoisted the `NodeType` +
Node-interface-`id` + no-field-directive case above the column hit and warned when the column
existed; the revision replaces that hoist with a rejection, so the arm no longer needs to outrank
the column lookup at all, it needs to detect the collision and refuse. Keep the message, which
already names both silencers, and move it from `BuildWarning.LintFinding` to the rejection channel.
`LintRule.NODE_ID_SHADOWS_COLUMN` (`LintRule.java:39`, `Source.CLASSIFIER`) retires with it; check
whether the enum entry is removed or kept for the `Source.CLASSIFIER` census, and note the MCP
`diagnostics` tool projects that closed rule set onto the wire.

**The implementation trap, and the easiest thing to get wrong here.** The arm being hoisted is the
deprecated shim, and its predicate is broader than the rule being adopted:

- shim predicate: `parent instanceof NodeType && "ID".equals(typeName) && !isList && !hasFieldDirective`,
  which fires for *any* bare `ID` field on a node type, `externalId: ID` included.
- the rule: the `id` field satisfying the `Node` interface.

Hoisting the shim's predicate above column resolution would flip `externalId: ID` on a node type
over a table with an `external_id` column from column-mapped to nodeId-encoded, which is far outside
the intended blast radius. Pin the hoisted arm to the Node-interface `id` field specifically, and
leave every other bare-`ID` field in the fall-through arm with its deprecation WARN intact for `R27`.

**Hard error instead of a warning? Settled 2026-08-04 as warn-and-flip, reversed 2026-08-06.** The
question was raised because `buildTableType`, in the same file, faces a neighbouring ambiguity (SDL
and metadata disagreeing about which columns identify the row) and refuses to pick a winner,
rejecting with "the column sets are different; one side is wrong about the schema", whereas
warn-and-flip picked a winner and changed the wire value of `Node.id` under existing green builds.
The argument that carried the original decision was that the two cases differ in kind: in
`buildTableType` both sides make a positive claim about row identity and either can be wrong, while
here the metadata is the positive assertion and the identically-named column looked like an accident
of the table's shape, so there was a right answer to pick.

That is the premise the revision rejects. The column is not an accident from the author's side: a
schema exposing `id` over a table with an `id` column may well mean that column, and the encoded
form and the raw form are simply two different values on the wire. Nothing in the SDL distinguishes
them, so "there is a right answer" was a claim about the metadata's intent rather than about the
author's. The other two reasons on record survive the reversal and now argue for it. The
additive-then-cutover discipline was never engaged (this is a value change at one field, not a
widely-pinned seam), and both silencers already work today, which is exactly what makes rejection
cheap: the remedy is one directive away and no migration window is needed to make it available.

**Measured blast radius.** Counts below are from a scan on 2026-08-04; re-measure at pickup with the
script under Test surface rather than trusting the numbers.

*Inference itself flips nothing in-repo, but not for the reason the `.graphqls` files suggest.* The
schema fixtures are overwhelmingly Java-inline: roughly 318 `implements Node @table` declarations
across `.java` and `.graphqls` sources, of which the four `.graphqls` files hold 17. Exactly five of
the 318 omit `@node`: `NestedConnectionElementRetentionPipelineTest` (a `Customer`/`Payment` pair,
twice, across two schemas) and `ClassifiedCorpus`'s `relay-node` `Film`. All five are safe because
`customer`, `payment` and `film` carry no `__NODE_*` metadata, not because every declaration carries
the directive. Those five are the canaries: adding metadata to a sakila table would flip them. The
`Customer`/`Payment` pair is also worth reading directly, since it is the non-goal shape
(`implements Node @table` with no metadata) written with `id: ID! @nodeId` over a `TableType`, which
is what the open non-goal question below is really about.

*The shadowing rule is the opposite, and it is bigger than first measured.* Twenty-nine sites across
six test classes write a `baz`-backed node type with a bare `id: ID!`, which is row 1:
`NodeIdPipelineTest` (20), `NodeIdReferenceFilterPipelineTest` (3), `NodeIdLeafResolverTest` (2),
`InlineFilterArgumentSourcePipelineTest` (2), `AsConnectionSameTableWarnFormatTest` (1),
`NodeIdOverrideConditionFkTargetPipelineTest` (1). Several are multi-line declarations, so a
single-line grep undercounts. Add `R377_DECODE_VIA_NODE_INDEX_NOT_TYPEID` for `shared_node` (row 4).
Every one of them flips its `id` from raw column to encoded and changes generated output.

Thirteen more already write `id: ID! @nodeId` on the same `baz` shape
(`MutationDmlNodeIdClassificationTest` 10, `MutationTableArgClassificationTest` 2,
`NodeIdPipelineTest` 1). That is fixture authors reaching for the silencer unprompted on the exact
shape where the default is wrong, and it is the strongest in-repo evidence that this item corrects
rather than breaks.

## User documentation (first-client check)

Required by the Plan-quality rule in `roadmap/workflow.adoc`: an item with a user-visible surface
drafts the docs first, and if they do not read simply the design is wrong. This item changes what
`@node` is *for* and changes the wire value of `Node.id` under existing green builds, so the draft
is load-bearing rather than a formality. The manual currently states the opposite in two places, so
neither page can be left alone:

- `docs/manual/reference/directives/node.adoc`, Constraints: "The type must declare an `id: ID!`
  field decorated with `@nodeId`. The directive on the type alone does not produce the ID."
  Phase 2 falsifies this.
- `docs/manual/reference/directives/nodeId.adoc`, Constraints: "The named or inferred type must
  carry `@node`." Phase 1 falsifies this.

**The question the draft has to answer plainly: after this item, is `@node` still how you declare a
node?** No, and saying so is what makes the page read simply:

> `implements Node` declares nodehood. `@node` supplies or overrides the two identity parameters,
> `typeId` and `keyColumns`. When the jOOQ generator has already published them for the bound table,
> `@node` is optional.

Everything else follows from that one sentence, which is the sign the design is the right shape.

### Draft: `node.adoc` Constraints

* The decorated type must also carry `@table`; the bound table supplies the columns to embed.
* The type must implement the `Node` interface (`type X implements Node ...`). Without the
  interface, `Query.node(id:)` cannot return the type, and `@node` alone is rejected.
* `@node` itself is **optional** when the bound jOOQ class publishes `__NODE_TYPE_ID` and
  `__NODE_KEY_COLUMNS`: `implements Node @table(name: "x")` is then a complete node declaration and
  takes both values from the catalog. Write `@node` when the generator has published nothing, or to
  override either value.
* The node's own `id: ID!` field does not need `@nodeId`. The `id` field satisfying the `Node`
  interface on a node type is a node ID by construction. Other `ID` slots still require the
  directive.
* `keyColumns` must form a primary key or another unique key on the bound table.
* `keyColumns` ordering matters and is part of the ID's wire format.
* `typeId` collisions across types are rejected at build time, whether the `typeId` is written,
  defaulted, or taken from catalog metadata. (The current page says "at startup"; `global-id.adoc`
  already says "at build time" for the same fact, so this edit settles a pre-existing inconsistency
  in the bullet it was rewriting anyway.)

### Draft: `node.adoc`, new subsection "When the table has its own `id` column"

> A table can publish node metadata *and* have a column literally named `id`. Graphitron will not
> choose for you: `id` could mean the encoded global ID or the column's own value, both are
> legitimate, and the two are different values on the wire. The build fails until you say which:
>
> [source,graphql]
> ----
> type Doc implements Node @table(name: "doc") { id: ID! @nodeId }             # the global ID
> type Doc implements Node @table(name: "doc") { id: ID! @field(name: "id") }  # the raw column
> ----
>
> This applies wherever an `ID` is named for a node's `id`, on output fields, input fields and
> arguments alike. Note that a type whose `Node.id` is a raw column cannot round-trip through
> `Query.node(id:)`.

Neither spelling is presented as "the default". Under the shipped warning one of them was, and
naming a default is what invites an author to skip the decision, which is the behaviour being
retired.

### Draft: the error text

> `Doc.id`: the table `doc` has a column named `id` and also publishes node metadata, so `id` is
> ambiguous. Add `@nodeId` to publish the global ID, or `@field(name: "id")` to expose the raw
> column.

Naming both remedies in the message is what makes the rejection actionable without the manual, and
it matters more here than it did for the warning: fixture and schema authors now cannot proceed
without reading it. Keep the source location, which the LSP surfaces. The `LintFix` reasoning on
`warnShadowedIdColumn` (no fix attached, because graphql-java records a type node's start but not
its end, so the insertion point after `id: ID!` is not derivable) survives the move to the rejection
channel and should travel with the message rather than being dropped as lint-specific.

### Draft: `nodeId.adoc` Constraints, the one bullet that changes

* The named or inferred type must be a node type: either it carries `@node`, or it declares
  `implements Node` over a table whose jOOQ class publishes node metadata. The build fails when
  `typeName:` resolves to a non-node type.

### The glossary, and a gap worth naming rather than absorbing

`docs/manual/reference/diagnostics-glossary.adoc` is error-only by construction: it opens on the
three error prefixes (`[author-error]`, `[invalid-schema]`, `[deferred]`) and enumerates the
`unknown-name` attempt kinds. It has no warnings section, and no page in the manual documents the
`LintRule` ids at all, even though the enum is a closed set with stable kebab-case ids that the MCP
`diagnostics` tool already projects onto the wire.

**The 2026-08-06 revision closes this gap rather than recording it.** The original reasoning was
that the shadowing *warning* had no glossary-shaped home, because the glossary admits errors only.
As a rejection it has one, so this item now **adds a glossary entry** for the shadowing error
alongside the `node.adoc` subsection. Pick the prefix deliberately: the schema is well-formed and
the author's intent is genuinely undetermined, so `[author-error]` fits and `[invalid-schema]` does
not.

What stays out of scope is unchanged: this item does **not** grow a lint-rule inventory page, which
is a separate deliverable covering the existing rules (9 `ENGINE`, 3 `CLASSIFIER` before this item's
retirement of `NODE_ID_SHADOWS_COLUMN`, 2 `CODEGEN`) and should be its own Backlog item. Note the
`CLASSIFIER` count moves when this rule retires, so whoever writes that page reads the enum rather
than this sentence.

## Review round 1: rework, one finding

In Review → Ready on 2026-08-06, independent reviewer session. Everything below the finding is
clean, and the next pass should be short: the delivery is otherwise exactly what this plan asked
for, the reactor is green under `mvn install -Plocal-db` (13 modules, 537 test classes, zero
failures, including the sakila PostgreSQL execution tier), and no delivered test asserts on a
generated method body.

**The rework is no longer docs-only.** The shadowing decision was revised on 2026-08-06 (see "The
shadowed `id` column"), after this review closed, so the next pass carries a behaviour change as
well as the doc finding below: the shipped warning becomes a rejection, `LintRule.NODE_ID_SHADOWS_COLUMN`
retires, the two assertions at `NodeInferencePipelineTest.java:352` and `:504` flip from a lint
finding to a rejection, and roughly 37 fixture sites gain an explicit `@nodeId` or `@field`. Size
the pass against both. The doc drafts above were rewritten for the rejection in the same revision,
so the finding below and the revision land together rather than in sequence.

**The finding: the user manual still states the retired policy as current, on a third page the
plan's docs census missed.** The User documentation section above says "the manual currently states
the opposite in two places" and names `node.adoc` and `nodeId.adoc`. That census was incomplete.
The implementation correctly fixed both, and found `global-id.adoc` on its own, but
`docs/manual/how-to/migrating-from-legacy.adoc` still carries the pre-inference rule in two places:

- **Line 164** asserts it as the live rule: "The current rule: a `@table` type without
  `implements Node @node` is a regular `TableType`, regardless of catalog metadata. If a schema
  relied on the auto-promotion ..., add the explicit `implements Node @node` declaration." The
  first sentence is now the negation of what shipped, and the migration advice is over-prescriptive
  (`implements Node` alone is the fix when the table publishes metadata).
- **Line 267**, the section's summary checklist: "add `implements Node @node` to opt in explicitly."

The blast radius is the worst available for this particular falsehood. That page is the migration
guide, and its audience is precisely the legacy schema population whose jOOQ classes carry
`__NODE_*` metadata, which is the population inference exists for. Line 162's historical paragraph
is still accurate and should stay (metadata alone still promotes nothing); what needs rewriting is
the "current rule" claim and the checklist entry, plus the section heading's implicit scope. The
`docs/` module deploys to the public site on trunk push, so this ships as written.

Secondary, same class and lower stakes, worth folding into the same pass:
`docs/architecture/reference/code-generation-triggers.adoc` line 142 ("A type carrying
`@table(name:)` (without `@node` or `@discriminate`) classifies as `TableType`") and line 169
(the curated variant table's `` `@table` + `@node` | `NodeType` `` row) both enumerate the trigger
as `@node` presence. These are hand-written prose and a curated table, not corpus-rendered, so the
corpus drift gate does not catch them. Contributor-facing rather than author-facing, so imprecise
rather than misleading, but it is the same edit.

Nothing else in the delivery is held against this gate. Reviewed and found correct: the five
predicate consumers (`NodeDeclaration` names the predicate once; reachability, the arrival fold,
federation synthesis and the LSP node view all call it; `NodeIndex` needed only the javadoc rewrite
the plan predicted; `resolveTargetKeys`' third arm is documented as unreachable-for-inferred rather
than left open, and its javadoc's stale "prefers catalog metadata" ordering was corrected against
the code while it was being touched); the per-axis `NodeProvenance` slot and its two live consumers;
the `Node.id` hoist pinned to the interface field with the `externalId` trap pinned open in both
directions; the shadowing rule's five rows with warning presence and absence asserted in the same
case as the flip; both collision axes, including the zero-to-one decode-helper direction the plan
did not require; the reachability hole closed and pinned by a case where no field returns either
colliding type; and the whole roadmap reconciliation set. The execution-tier proof is better than
the plan asked for: converting the sakila example's `FilmActor` to the inferred spelling, rather
than adding a variant, makes the existing round-trip the proof.

One non-blocking note on spec hygiene for the next pass: the Implementation plan's four phases are
still written in the imperative present as if unexecuted, and no landing SHAs appear anywhere in the
body. `roadmap/workflow.adoc` § Item file conventions asks a shipped phase to collapse to a one-line
"shipped at `<sha>`" note. The "What shipped" section below carries the substance, so this is
presentation, not a missing fact.

## Review round 1 rework, shipped at `4065942`

Both halves landed together: the shadowing revision and the doc finding.

**The rejection.** `FieldBuilder`'s Node-interface-`id` arm still sits above the column lookup, but
it now resolves the column itself and refuses when one exists, as an `AuthorError.Structural` naming
both remedies. The arm did not need to stop outranking the column lookup, only to stop assuming a
miss: keeping it in place means one site decides both readings, which is easier to read than a
rejection bolted onto the `Direct` fall-through. `Rejection.structural` puts it under
`[author-error]`, as the glossary draft asked. `LintRule.NODE_ID_SHADOWS_COLUMN` was **removed**
rather than kept for the `Source.CLASSIFIER` census, which was the open question: `LintRule.ids()`
is the namespace a consumer's `<lint>` config validates against, so keeping a rule that can never
fire would let a config name a suppression that suppresses nothing. The census keeps three
`CLASSIFIER` rules, so the partition stays non-empty and the coverage test still has something to
assert over.

**The fixture cost, re-measured at pickup as the plan asked.** 37 sites across 7 test classes, which
is what the revision predicted. Enumerated by walking type declarations over the six fixture tables
that have a literal `id` column, rather than grepping for `baz`, which is what turned up the two
`InlineFilterArgumentSourcePipelineTest` sites and three in `NodeIdReferenceFilterPipelineTest` that
a single-line grep had missed. Every edit is behaviour-preserving against the shipped warning: those
sites already published the encoded form, so adding `@nodeId` states what was already happening. No
`graphitron-sakila-example` site was affected, as predicted.

**Docs.** `migrating-from-legacy.adoc` is the finding proper, and its section heading was wrong too,
not just the rule under it (`@table` alone is what stopped auto-promoting). `global-id.adoc` also
carried the warning text and needed the same edit; it was not in the review's list because the
review found it already fixed for inference, and the shadowing sentence was added by that fix. That
is the doc census being incomplete twice, in the same place, for the same reason: the census was
written by grepping for `@node` rather than for the behaviour.

The glossary entry needed a home the page did not have. It is error-only by construction but
organised entirely around closed sets, and this is a structural message, which the page explicitly
says it does not enumerate. Rather than force it into the attempt-kind list, it opens a
`Named structural errors` section, with the drift-protection paragraph amended to say that section
is outside `DiagnosticsDocCoverageTest`'s scope. That keeps the test's bidirectional guarantee
honest and gives the next stable structural message somewhere to go.

## What shipped, and where it departed from this plan

Implemented 2026-08-04, at `cefb16a` (classifier, predicate, fixtures, tests) and `8cf7766` (manual,
sakila example, roadmap reconciliation). The four phases landed as one change; the plan's own
recommendation on splitting is under Sequencing and is unchanged by the outcome.

Two departures worth reading, both narrower than the plan expected:

1. **One lint finding, not two.** Phase 3 called for two `BuildWarning.LintFinding`s at the shadowed
   field so each silencer could carry its own `LintFix`. Neither fix is attachable: both are directive
   insertions *after* the field's type, and graphql-java records a type node's start location but not
   its end, so the insertion point for `id: ID!` (where the `!` may be separated by whitespace) is not
   derivable from source locations. `LintFix`'s own rule is that a finding whose edit cannot be
   computed safely carries `Optional.empty()`. With no fix to attach, the fork the plan was resolving
   does not arise, and one finding naming both silencers beats two identical lines in the report. The
   quick-fix surface is `R34`'s to build, and it will have to solve directive-insertion positioning
   for its own hints anyway.
2. **The decode-helper one-to-two transition is only half reachable, and the plan named the wrong
   half as urgent.** The transition itself is real and now pinned, but not from the site the plan
   pointed at: a call site holding an authoritative type name resolves its decode helper by name since
   `R581`, so the input leaf in the `shared_node` fixture is unaffected by how many nodes back the
   table. What remains reachable is the shape where the table has no column matching the input field,
   which is what `NodeIdPipelineTest`'s existing multi-node rejection case already used. The
   *zero-to-one* direction turned out to be the quieter and more interesting one and is pinned too:
   adding `implements Node` to an output type changes an input leaf's emitted helper from the
   typeId-suffixed fallback (`decodeBar`) to the type-name-keyed one (`decodeFoo`), which is a codegen
   change at a coordinate naming nothing this item touches.

Deliberately not done, and not an oversight:

- **No `ClassifiedCorpus` sibling.** The Test surface listed it as "consider". The verdicts are pinned
  by `NodeInferencePipelineTest` instead; adding a corpus example means authoring a dimension tuple
  and satisfying the corpus's own coverage and doc-render gates, which is a documentation deliverable
  rather than coverage this change is missing. The existing `relay-node` example is unaffected either
  way, since `film` publishes no metadata.
- **No lint-rule inventory page.** Filed as `R592` (`roadmap/lint-rule-reference-page.md`), as the
  User documentation section said it should be. The shadowing warning is documented where an author
  hits it, in `node.adoc`'s shadowed-column subsection.

The three fixture tables the plan predicted would be needed all were: `collide_a` / `collide_b`
publish a shared `__NODE_TYPE_ID` (without them an inferred collision cannot be written at all, since
every other fixture typeId is distinct), and `plain_id` / `keyed_elsewhere` cover the shadowing rule's
two remaining shapes, a node whose key columns came from the primary key rather than the catalog, and
a shadowed column that is not a key column. The census re-measured at pickup matches the figures
below exactly: 29 bare-`id` sites over `baz`, 13 already `@nodeId`-pinned, one over `shared_node`.

**The sis census under Evidence required was not performed.** It cannot be answered from this
repository, and the user directed implementation with that open. It remains the item's one unverified
safety claim: if a meaningful number of sis types pair `implements Node` with tables sharing a
`__NODE_TYPE_ID`, this change produces a diagnostic per member of each colliding group. The gate now
reads as a pre-rollout check rather than a pre-implementation one, and the mitigation is unchanged:
`@node(typeId:)` on one side of each group, which the collision message names.

## Implementation plan

All four phases shipped at `cefb16a` / `8cf7766`, and Phase 3 was re-landed as a rejection at
`4065942` after the 2026-08-06 revision. The phase text below is kept as authored, unexecuted
tense and all, because the departures under "What shipped" and the revision under "The shadowed
`id` column" are stated against it and would not read without it. Where a phase and one of those
sections disagree, the later section is what happened.

**Phase 1: inference, and the predicate it splits.** In `TypeBuilder.buildTableType`, replace the
`hasNode` early return (`TypeBuilder.java:1308-1311`) with a gate that also admits
`implementsNode(objType) && metadata.isPresent()`, routing that case to `buildNodeType` with the
metadata's `typeId` and `keyColumns`. The existing SDL-versus-metadata merge below stays reachable
only when `@node` is present, unchanged. Rewrite the comment at `:1294-1299`, which states the
retired policy and the rationale this item revises.

The gate edit is the small half. The real work is the five directive readers listed under Consumers
of the "is a node" predicate: name the predicate once, repoint the post-classification consumers at
it, and decide explicitly what `KeyNodeSynthesiser` does, since it runs before classification and
cannot read the classifier. Landing the gate without this is what would make an inferred node and an
explicit node differ in reachability seeding, arrival folding, federation entity membership, and LSP
visibility.

One consumer needs no work: `TypeBuilder.buildClassificationIndices` populates `NodeIndex` by
pattern-matching classified types (`if (tbt instanceof NodeType nt)`), never by reading the
directive, so inferred nodes enter the index for free. What that site does need is a javadoc
rewrite, since `NodeIndex`'s invariant is currently stated in terms of `@node` self-seeding
reachability. That is the same fact as the `SchemaReachability` finding above, seen from the index
side.

`NodeType` should also gain a narrow provenance slot (declared versus inferred, per axis, since
`@node(typeId:)` plus metadata keyColumns is a mixed case). Three consumers need it, and each would
otherwise re-derive it by reading SDL below the classifier boundary: the collision message, the
shadowing warning, and `R34`'s hint. One record component removes three re-derivations.

**Phase 2: adopt the `Node.id` rule.** Both this phase and Phase 3 edit the same method,
`FieldBuilder.classifyChildFieldOnTableType` (declared at `FieldBuilder.java:7072` at the time of
writing; the file is 7k+ lines and actively edited, so navigate by symbol). The site is the
`instanceof NodeType` arm inside the `column.isEmpty()` branch, greppable by its WARN text
"synthesizes an `@nodeId` carrier without the directive". Drop the deprecation WARN and restate the
branch as the permanent `R473` rule-1 carrier rather than a shim awaiting deletion, narrowing its
predicate to the Node-interface `id` field per the implementation trap above. Leave the other two
`R27` shim sites alone, and leave non-`id` bare-`ID` fields in the deprecated arm. Coordinate the
wording so `R27`'s inventory stays accurate.

**Phase 3: the shadowing rule.** In the same method, the ordering to invert is the
`svc.resolveColumn(columnName, tableType)` call and the `NodeType` arm below it; `hasFieldDirective`
in the same scope is already the `@field` opt-out. Add the warning for the shadowed column and
migrate the affected fixtures (29 bare-`id` sites over `baz`, plus the `shared_node` case; see
Measured blast radius). The warning names the column, the winning interpretation, and both
silencers.

Carry it as a `BuildWarning.LintFinding` rather than `NoRule` so it reaches
`ValidationReport.warnings()` and the LSP replay with a fix attached, which is the same surface
`R34` builds on. That needs a new `LintRule` constant with `Source.CLASSIFIER` (the same arm as
`ASCONNECTION_SAME_TABLE_PK_IN`, tagged at the classifier emit site, no engine visitor, so the
registry coverage test stays satisfied).

**The two-fix shape does not currently render, and the answer is already determined.**
`BuildWarning.LintFinding` carries `Optional<LintFix>`, singular, and `LintQuickFixes.compute` emits
exactly one code action per finding via `finding.fix().get()`. Two silencers therefore require
either two findings at one location or widening `LintFinding` to a list. Emit two findings: it needs
no change to the sealed type or to the quick-fix renderer, and the two fixes are genuinely different
advice rather than two spellings of one repair. The cost is a duplicated message line in the
non-LSP report, which the message wording can absorb by differing per fix ("confirm with `@nodeId`"
/ "expose the column with `@field(name: \"id\")`").

**Phase 4: docs and reconciliation.** The manual edits are the ones with a consumer, so they ship
with the phase that changes the behaviour rather than trailing it: `node.adoc`'s Constraints list
and its new shadowed-column subsection with Phase 1 and 3, `nodeId.adoc`'s `@node`-required bullet
with Phase 1. Drafts are under User documentation above; moving them into place is the whole of the
docs work, which is the point of drafting them first.

Then rewrite `R473` rule 1's antecedent and shim inventory (see Reconciliation work above, and note
this is the reconciliation Phase 2 most depends on), rewrite `R273`'s second bullet (the deliverable
is live, not dropped), note the reversal in `R27`, and update `METADATA_ONLY_NO_PROMOTION`'s
description in
`NodeIdPipelineTest`, which currently reads "without `implements Node @node`" and cites the stale
collision rationale. That fixture survives unchanged, since `@table` without `implements Node` still
stays a `TableType`.

`R34` step 3 needs more than a re-scope. Narrowed, its hint offers bare `implements Node` on any
`@table` type whose backing class carries metadata, and `R34` step 4 plans *workspace-scoped bulk
application* across roughly 250 sites. Bulk-applying that hint over a schema whose metadata-carrying
tables share a `__NODE_TYPE_ID` mass-promotes the incident population in one click, through the
tooling. The re-scope must therefore carry a gate: the hint is not offered where the resulting
typeId would collide with an existing or sibling-inferred node, which means the hint computation
reads the uniqueness reduction and not just the metadata probe. That stays inside `R34`'s own
"generator computes, LSP renders" discipline.

## Sequencing

Phase 2 is the reason this item cannot simply queue behind `R473`. `R473` is in **Spec** (not
Backlog, as an earlier draft of this item said), and its Phase 1 has already shipped as `R581`: the
call sites holding an authoritative type name no longer route decode resolution through the table.
What remains in `R473` is the inversion itself, spanning the input-side shims, decode-resolution
polarity, and a breaking SDL migration. That is still a substantially larger surface than this item,
so the disproportion argument survives the corrected premise, and it now has a precedent: `R581`
carved a self-contained half out of `R473` and shipped it ahead of the rest. Phase 2 is the same
move at a smaller scale. Taking only rule 1's
adoption at one site is the minimum needed for Phase 1 to be coherent, and it moves in `R473`'s own
direction rather than against it. Reviewer should confirm that reading and that `R473`'s remaining
scope survives intact.

**A gap this item deliberately leaves open.** With all three no-metadata spellings settled (see
Non-goals), `implements Node @table` over a table with no `__NODE_*` metadata is a trap in every
spelling except explicit `@node`, and neither failing message names `@node` as the fix. Spelling
(1)'s "column 'id' could not be resolved; did you mean: ..." actively misdirects toward
`@field(name:)`, which is the same misdiagnosis this item's opening complains about, just on the
metadata-absent branch instead of the metadata-present one. This item fixes the metadata-present
branch and leaves the other alone, so the trap survives in narrower form. Filed as `R588`
(`roadmap/node-without-metadata-diagnostics.md`), Backlog, diagnostics only. It is not a blocker
here, and folding it in would mix a classification change with a message change. It gets easier
after this item lands, since the message can then contrast against an inference path that exists.

**On splitting Phase 3 out.** The case for it is that Phase 3 changes behaviour on the existing
explicit `@node` path and carries the entire measured blast radius, so it reviews and migrates
separately. The case against, which currently looks stronger: Phases 1 and 3 are one precedence
rule, not two. Shipping inference alone would leave `implements Node @table` and
`implements Node @table @node` disagreeing about what `id` means over an `id`-column table for a
release, an inconsistency worse than either end state and one that would itself need documenting.
The asymmetry that motivated the split also weakened once the green-build finding landed, since
Phase 1 changes green builds too. Recommendation is to keep one item, split the commits, and gate
the Phase 3 commit on its own pipeline cases plus an execution-tier round-trip, since it is the half
that changes wire output. Reviewer's call.

## Evidence required before this leaves Ready

1. **The sis census.** Enumerate the object types matching `implements Node` and `@table` and no
   `@node`, grouped by the backing table's `__NODE_TYPE_ID`, recording for each member whether its
   `id` field is bare, `@field`-pinned, or `@nodeId`-pinned. Pass condition: every group is a
   singleton, *and* no member's `id` is `@field`-pinned to a non-key column (such a type would
   promote to a `NodeType` whose `Node.id` is a raw column and therefore cannot round-trip through
   `Query.node`). This is the direct test of the safety claim and cannot be answered from this
   repository. If any group has more than one member, bring the count and the shape back to Spec
   before implementing; the design would then need a per-collision-group fallback rather than a mass
   rejection.
2. **A shadowing sweep of the consumer schemas** for types over tables that carry metadata and also
   have a column named `id`, since those are the sites whose wire values change.

## Non-goals

- **`implements Node` + `@table` with no metadata: no change.** Decided 2026-08-04. Inference does
  not extend to deriving node identity from a primary key; metadata is a positive assertion by the
  jOOQ generator that this table has a published node identity and what its wire typeId is, whereas
  a primary key is not.

  The question of *which* reading this non-goal preserves is settled by classifying the three
  spellings of "no metadata" directly. Only one of them works:

  1. `implements Node @table` + bare `id: ID!` over a table with no metadata and no literal `id`
     column (the `qux` fixture): `TableType`, and `id` is rejected as
     `UnclassifiedField("column 'id' could not be resolved ...")`.
  2. The same plus explicit `@node`: well-formed, resolving against the `@node`-only defaults (type
     name for `typeId`, PK for `keyColumns`). **This is the only working spelling.**
  3. The same as (1) but with `id: ID! @nodeId` written: also **rejects**, from `FieldBuilder`'s
     `@nodeId`-on-non-`NodeType` arm, with
     `Structural("@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)")`.

  A caution about (3), because an earlier revision of this item got it wrong in a way worth not
  repeating. `NestedConnectionElementRetentionPipelineTest` carries exactly shape (3)
  (`type Customer implements Node @table(name: "customer") { id: ID! @nodeId }` plus a `Payment`
  sibling, over metadata-free sakila tables) and is green on trunk. That is not evidence the shape
  is well-formed: the test never asserts the `id` field, and `TestSchemaHelper.buildBundle` does not
  run the validator, so a rejected field rides through a passing test silently. Classify the shape,
  do not run a test that happens to contain it.

  So the non-goal freezes all three exactly as they are: inference does not fire without metadata,
  and (1) and (3) keep rejecting. The "metadata is a positive assertion by the jOOQ generator, a
  primary key is not" argument carries this on its own, and does not need a second working spelling
  to lean on. What it costs is real and should be stated plainly: over a metadata-free table,
  explicit `@node` is the only way to declare a node, and the two other spellings an author would
  naturally try both fail. See the diagnostic-quality note under Sequencing.

  One detail worth carrying into implementation: (3)'s message already reads
  "via `@node` **or KjerneJooqGenerator metadata**". It was written for the semantics this item
  introduces and is currently accurate about a path that does not exist yet. After Phase 1 it
  becomes true as written, which is a small piece of evidence that inference is the behaviour the
  codebase already expected.
- Wire format, encode/decode emission, and the `Query.node` dispatch surface are untouched.

## Test surface

- **A new fixture-table pair sharing a `__NODE_TYPE_ID` is a prerequisite, not a test case.** Every
  typeId in `NodeIdFixtureGenerator.METADATA` is distinct (`Bar`, `Baz`, `10154`, `FilmActor`, ...),
  so an *inferred* collision cannot be written against the current fixtures at all; only an explicit
  `@node(typeId:)` collision can, and that is already covered by `TYPE_ID_COLLISION_DEMOTES_BOTH`.
  Add the shared-typeId pair first, then the inferred-collision case.
- **`METADATA_ONLY_TYPES_DO_NOT_COLLIDE` needs rewriting, and it is the one people will point at.**
  It is the named regression test for the sis incident, but its `bar`/`baz` pair does not actually
  share a typeId, so it pins non-promotion only, not collision-avoidance. After this item it would
  be pinning a rule (no `implements Node`, no promotion) that is not the rule preventing the
  incident. Rewrite the description and add the true-collision sibling in the same commit.
- `NodeIdPipelineTest`: inferred promotion on `implements Node @table` over `bar` (the headline
  case, `compaction=NodeIdEncodeKeys` with no directive written); `@table` + metadata without
  `implements Node` still `TableType`; explicit `@node(typeId:)` still overrides an inferred
  sibling; the `resolveDecodeHelperForTable` one-to-two transition. The existing
  `NO_METADATA_NO_NODE` case stays green unchanged. `METADATA_ONLY_NO_PROMOTION`'s description also
  needs rewriting: it reads "without `implements Node @node`" and cites the stale rationale.
- The five predicate consumers each need a case proving an inferred node behaves like an explicit
  one: reachability seeding (an inferred node reachable only via `Query.node` survives registry
  pruning), arrival folding, federation `_Entity` membership or its documented absence, and the LSP
  node view.
- The shadowing rule's five rows above, pinned on `baz` (three), `shared_node` (one) and `bar`
  (one). Rows 1 and 4 are the behaviour change, so they are the load-bearing assertions. Under the
  2026-08-06 revision they assert the rejection and its message rather than a flipped compaction,
  and they run the validator, since a rejected field rides through a green pipeline-bundle build
  silently otherwise. Row 5 is what keeps the rejection scoped to genuine ambiguity. Row 4 still has
  to prove the decode-helper name is unaffected (`R377_DECODE_VIA_NODE_INDEX_NOT_TYPEID` asserts
  `decodeSharedNode`, not `decode10154`), which now needs the disambiguated spelling, since the bare
  one no longer resolves to a helper at all.
- Re-measure the fixture census at pickup rather than trusting the counts above. The scan that
  produced them walks `.java` and `.graphqls` sources for
  `type X implements Node @table(name: "baz") ... { ... }`, classifies the `id` field as bare,
  `@nodeId`-pinned or `@field`-pinned, and must handle multi-line declarations, which a single-line
  grep silently drops (that undercount is what produced the earlier figures of 22 and 3).
- **The five no-`@node` canaries: pin the rejection, not the green build.** Four of the five
  (`NestedConnectionElementRetentionPipelineTest`'s two `Customer`/`Payment` schemas) carry an `id`
  field that is *already rejected* today, and their host test is green only because it never asserts
  that field and `TestSchemaHelper.buildBundle` skips the validator. Writing green-at-field-level
  assertions there will fail. The correct pin is that this item changes nothing at that shape: the
  type stays `TableType` and `id` stays an `UnclassifiedField`, with the two distinct messages kept
  apart, `@nodeId requires the containing type to be a node type` for the `Customer`/`Payment` pair
  and `column 'id' could not be resolved` for spelling (1) over `qux`. Assert through a helper that
  runs the validator, since the pipeline helper these tests use does not.
- `ClassifiedCorpus`'s `relay-node` `Film` is the fifth canary and the only one whose `id` is bare
  over a metadata-free table. Unaffected either way. Consider a sibling over a metadata-bearing
  table pinning the inferred verdict, which is the only way that corpus exercises this item at all.
- Execution tier: the `film_actor` NodeId round-trip already exists (`GraphQLQueryTest`'s
  `filmActorByNodeId`); a variant with `@node` dropped from the SDL proves inference end to end.

## Reproduction

```graphql
type Baz implements Node @table(name: "film_actor") { id: ID! }
type Query { baz: Baz }
```

`schema.type("Baz")` is a `TableType`; `schema.field("Baz", "id")` is an `UnclassifiedField` whose
reason names the column lookup. With `@node` added, `NodeType` + `ColumnBackedField`.
