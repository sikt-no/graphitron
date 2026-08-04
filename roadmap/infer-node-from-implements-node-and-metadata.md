---
id: R580
title: "Infer @node from `implements Node` + __NODE_* metadata"
status: Spec
bucket: architecture
priority: 8
theme: nodeid
depends-on: []
created: 2026-08-03
last-updated: 2026-08-04
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

## The shadowed `id` column

A table can carry `__NODE_*` metadata *and* a column literally named `id`. Decided 2026-08-04: the
node metadata wins, and the build emits a warning that the `id` column is being shadowed, naming the
two ways the author silences it by stating the choice explicitly. `@nodeId` on the field pins the
node interpretation; `@field` pins the column.

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
without that column collision publishes an encoded one. Nothing warns. The rule above corrects the
default and surfaces the case.

The precedence the rule needs already exists and needs no new machinery: both silencers are live
today (rows 2 and 3 above), so the work is flipping the default when metadata is present and adding
the warning. Spec should also cover the variant with no fixture yet, a table whose metadata keys on
columns *other* than a literal `id` column that also exists, where the same shadowing applies.

The single implementation site is the column-lookup-first ordering at `FieldBuilder.java:7247-7271`:
`svc.resolveColumn(columnName, tableType)` runs before the `NodeType` arm, so a successful lookup
short-circuits to `Direct` and the node interpretation never gets a turn. The fix is to give the
`NodeType` + Node-interface-`id` + no-field-directive case precedence over the column hit, and warn
when the column existed. `hasFieldDirective` (line 7208) is already the `@field` opt-out.

**The implementation trap, and the easiest thing to get wrong here.** The arm being hoisted is the
deprecated shim, and its predicate is broader than the rule being adopted:

- shim predicate: `parent instanceof NodeType && "ID".equals(typeName) && !isList && !hasFieldDirective`,
  which fires for *any* bare `ID` field on a node type, `externalId: ID` included.
- the rule: the `id` field satisfying the `Node` interface.

Hoisting the shim's predicate above column resolution would flip `externalId: ID` on a node type
over a table with an `external_id` column from column-mapped to nodeId-encoded, which is far outside
the intended blast radius. Pin the hoisted arm to the Node-interface `id` field specifically, and
leave every other bare-`ID` field in the fall-through arm with its deprecation WARN intact for `R27`.

**Hard error instead of a warning? Settled: warn-and-flip.** The question was raised because
`buildTableType`, in the same file, faces a neighbouring ambiguity (SDL and metadata disagreeing
about which columns identify the row) and refuses to pick a winner, rejecting with "the column sets
are different; one side is wrong about the schema", whereas this rule picks a winner and changes the
wire value of `Node.id` under existing green builds. Both the decision on record (2026-08-04) and
the Spec review land on warn, for three reasons worth keeping:

- The additive-then-cutover discipline in `roadmap/workflow.adoc` is scoped to "a widely-pinned type
  or seam" and says outright that it is a technique, not a prescription. This is a value change at
  one field.
- The two cases are different in kind. In `buildTableType`, both sides make a positive claim about
  row identity and either can be wrong. Here, the metadata is the positive assertion and the
  identically-named column is an accident of the table's shape; there is a right answer.
- Both silencers already work today, so an author who disagrees with the default is one directive
  away, with no migration window needed to make that possible.

Pin the warning's presence and the flipped compaction in the *same* pipeline case, so the migration
reads as one fact rather than two coincidental assertions.

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

> A table can publish node metadata *and* have a column literally named `id`. The node ID wins: the
> field publishes the encoded global ID, not the raw column value, and the build warns that the
> column is shadowed. Silence it by stating the choice:
>
> [source,graphql]
> ----
> type Doc implements Node @table(name: "doc") { id: ID! @nodeId }        # the global ID (default)
> type Doc implements Node @table(name: "doc") { id: ID! @field(name: "id") }  # the raw column
> ----
>
> `@field` is the escape hatch for exposing the column itself. Note that a type whose `Node.id` is
> a raw column cannot round-trip through `Query.node(id:)`.

### Draft: the warning text

> `Doc.id`: the table `doc` has a column named `id` and also publishes node metadata. Graphitron is
> using the node ID. Add `@nodeId` to confirm, or `@field(name: "id")` to expose the raw column
> instead.

Naming both silencers in the message is what makes the finding actionable without the manual.

### Draft: `nodeId.adoc` Constraints, the one bullet that changes

* The named or inferred type must be a node type: either it carries `@node`, or it declares
  `implements Node` over a table whose jOOQ class publishes node metadata. The build fails when
  `typeName:` resolves to a non-node type.

### The glossary, and a gap worth naming rather than absorbing

`docs/manual/reference/diagnostics-glossary.adoc` is error-only by construction: it opens on the
three error prefixes (`[author-error]`, `[invalid-schema]`, `[deferred]`) and enumerates the
`unknown-name` attempt kinds. It has no warnings section, and no page in the manual documents the
`LintRule` ids at all, even though the enum is a closed set with stable kebab-case ids that the MCP
`diagnostics` tool already projects onto the wire. The shadowing warning has no glossary-shaped home
to go into. This item documents the warning where an author will actually hit it (the `node.adoc`
subsection above) and does **not** grow a lint-rule inventory page, which is a separate deliverable
covering the fourteen existing rules (9 `ENGINE`, 3 `CLASSIFIER`, 2 `CODEGEN`) and should be its own
Backlog item. Flagged here so the omission is
a decision rather than an oversight.

## Implementation plan

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
  (one), plus the warning's presence and absence. Rows 1 and 4 are the behaviour change, so they are
  the load-bearing assertions, and row 4 is the one that also proves the decode-helper name is
  unaffected (`R377_DECODE_VIA_NODE_INDEX_NOT_TYPEID` already asserts `decodeSharedNode`, not
  `decode10154`; that assertion must still hold after the flip).
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
