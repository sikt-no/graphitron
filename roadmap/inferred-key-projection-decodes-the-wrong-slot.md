---
id: R884
title: "An argMapping binding that names a node id without naming a key column emits a decode of the wrong slot"
status: In Progress
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-09-03
---

# An argMapping binding that names a node id without naming a key column emits a decode of the wrong slot

## What this is about

Three terms first, because the defect is entirely in how they meet.

An **`argMapping`** is the right-hand side of a directive entry that says where a Java or routine
parameter gets its value: `argMapping: "pInventoryId: input.inventoryId"` binds the parameter
`pInventoryId` to the input field `inventoryId` inside the argument `input`. A **node id** is the
opaque base64 string a client sends for a slot carrying `@nodeId(typeName: "X")`; the generated code
must decode it into the key columns of `X` before anything else touches it. A **key projection** is
the mechanism that does that for an `argMapping`: the store resolves which key column the binding
wants, and the emitter renders `decodeXRecord(<the wire id>).get(Tables.X.THE_COLUMN)`.

A key projection resolves in two ways, and the difference between them is the whole item. In the
**authored** form the author spells the column past the node id, `"p: input.inventoryId.inventory_id"`,
so the path has one segment beyond the node id. In the **inferred** form the author stops on the node
id, `"p: input.inventoryId"`, and the node type's key is a single column, so there is exactly one
thing the binding could mean and the store resolves it without the author spelling it. The store side
of the inferred form is built and pinned (`ResolvedNodeKeyProjectionTest.aBindingWithNoTrailingSegmentResolvesTheSoleKeyColumn`,
and the `BARE_NODE_ID` verdict is deliberately withheld where an inference exists).

The emitter side never learned about it. `ProjectedKeyReads` was written when every projection was
the authored form, and it still assumes so in three places: it declines any binding whose path is a
single segment, and where the path is dotted it takes the node id to sit one segment short of the
path's end. Under the inferred form that arithmetic is off by one segment, so what the generated code
hands the decode helper is the slot *above* the node id.

## What actually happens today

Verified on the current tree by rendering each shape through the plan and reading the emitted method.
All three are compile-clean, so nothing short of running a request says a word.

**A dotted binding at a `@routine`.** `argMapping: "pInventoryId: input.inventoryId"` emits

```java
InventoryRecord keyInput = decodeInventoryRecord(env.getArgument("input"));
... Routines.rentFilm(keyInput.get(Tables.INVENTORY.INVENTORY_ID), ...)
```

`env.getArgument("input")` is the whole `RentFilmInput` map, not the node id inside it. The helper
guards its input with `wire instanceof String` and returns null for anything else, so every request
against this coordinate throws a `NullPointerException` on the following line, whatever id the client
sent.

**A dotted binding at a field-level `@condition`.** `argMapping: "filmId: in.filmId"` emits the same
mis-aimed decode into the conditions glue, `decodeFilmRecord(args.get("in"))`. It also captures the
input field's *own* implicit predicate at that coordinate, because the projection is looked up by the
written path alone and both bindings spell the same one, so one bad `argMapping` breaks a predicate
the author did not write.

**A bare binding at a `@routine`.** `argMapping: "pInventoryId: inventoryId"` against a `@nodeId`
argument emits

```java
Routines.rentFilm(env.<Integer>getArgument("inventoryId"), ...)
```

The projection row exists and is ignored, because the sink declines single-segment paths outright.
The base64 string is read off the wire and handed to a parameter typed `Integer`. This is the
undecoded-wire-value escape the whole projection family exists to close, arriving at the one shape
nobody re-checked after the inferred arm landed.

The equivalent bare binding at a `@condition` is safe today, and only by accident: the whole-slot rule
(`ConditionResolver.installNodeIdDecode`) claims it first and the projection sink's refusal of single-segment paths keeps the two from meeting. Fixing that refusal removes the accident, which is why the
precedence between the two rails has to become explicit rather than emergent.

Two neighbouring shapes are already correct and stay correct. A node type with a composite key has
nothing to infer, so the store raises `BARE_NODE_ID` and the build stops with a message naming the
key columns. A parameter whose declared Java type disagrees with the inferred column's raises
`KEY_COLUMN_TYPE_MISMATCH`, also a build error. Neither is silent, and neither is this item's to
change.

### What this item is not

This item was filed as a gap: a field-level `@condition` descending to a `@nodeId` input field was
believed to hand its parameter the raw wire string with no rail claiming the shape. That reading is
wrong on the current tree. The inferred arm does claim the shape, at the column grain, for every node
type with a single-column key; what it then emits is aimed at the wrong slot. The item keeps its
subject and its coordinate and changes its diagnosis, and it grows the `@routine` site, where the same
root cause produces the same two failures.

Three prose sites state the stale reading and are corrected here as part of the work:
`ConditionResolver.installNodeIdDecode`'s javadoc, the closing bullet of
`docs/manual/reference/directives/condition.adoc`, and this item's own former title.

## What changes when this lands

An author who binds a parameter to a node id without naming a key column gets the key column's value,
which is what `docs/manual/reference/directives/routine.adoc` already promises them ("The parameter
receives a key column's own value either way, never the encoded id"). Today that promise is kept by
the store and broken by the emitter. Three coordinates stop emitting code that cannot work: a dotted
inferred binding at a `@routine`, the same at a field-level `@condition`, and a bare inferred binding
at a `@routine`. Nothing changes for an authored projection, which is every projection the tree
currently has a fixture for, and no new diagnostic fires.

## Implementation

The root cause is one assumption held in three spellings, so the fix is to give the emitter the fact
it is currently guessing at and delete all three.

**Carry the provenance the store already holds.** `intent_argmapping_key_column_candidate` has
`trailing_segment_name`, non-null on the authored arm and null on the inferred one, and its own column
comment says it is the only thing that tells the arms apart. `intent_resolved_node_key_projection`
drops it. Add it to that view's select list and column comments, saying what an emitter needs it for:
the wire id sits at the written path minus its trailing segment where one was spelled, and at the
whole written path where none was.

**Carry it to the emitter.** `ResolvedKeyProjections.Projection` and
`no.sikt.graphitron.command.KeyProjection` each gain the field, and
`ResolvedKeyProjections.read` selects it. `KeyProjection`'s javadoc argues that every component of a
row is a captured fact rather than half an emission decision; the trailing segment is a captured fact
and the leaf path derived from it is the emission decision, so the row carries the former.

**Make the leaf one derivation instead of three.** `ProjectedKeyReads` becomes the single site that
turns a written path plus its projection into the path the wire id sits at. That means:

* `ProjectedKeyReads.leafOf` stops dropping the last segment unconditionally and consults the row.
* The single-segment early return in `readFor(PathExpr, ...)` goes. Its stated premise, that a
  projection names a key column past a node id and therefore has at least two segments, is what the
  inferred arm falsified. What must replace it is the precedence rule below, not a different arity
  test.
* `ConditionGlueRenderer.nestedExtraction`'s `written.substring(0, written.lastIndexOf('.'))` goes,
  along with the `nif.path().size() == 1` fork in the wire-read supplier beside it. Both are that same
  arithmetic spelled a third time. The supplier stays with the caller, because how a site reaches its
  wire value differs per site, but the *path* it reaches along comes from the sink.

**State the precedence between the two rails.** With the single-segment refusal gone, a bare binding
at a `@condition` has both an installed decode (the whole-slot rule) and a resolved projection. The
install rail wins: it is stated at the slot, it is uniform across key arity (a scalar at arity one, a
jOOQ `Row` above it), and the manual documents it as the contract for a whole-slot binding. The check
is local and available at both render sites: a binding whose leaf extraction is already a
`CallSiteExtraction.NodeIdDecodeKeys` is the install rail's, and the sink stands aside. Put it in
render rather than in the store, because the store has no way to know which coordinates the install
rail reached.

### The composite-key asymmetry, kept deliberately

After this lands, a bare binding and a dotted-to-the-node-id binding still differ at a composite key.
The bare one at a `@condition` gets the whole key as a `Row`; the dotted one gets `BARE_NODE_ID` and
a build error telling the author to name a key column. That is not an oversight to close in a later
item. A projection is per-column by construction and has no way to name a `Row`, and building a third
rail so that one path shape can reach the install rail's contract is exactly the two-mechanisms-racing-for-one-parameter shape `installNodeIdDecode`'s javadoc exists to prevent. The remedy is prose: the
`BARE_NODE_ID` message already tells the author to name a column, and `condition.adoc` gains the
second remedy, moving the `@condition` onto the `@nodeId` input field, where the whole-slot contract
applies.

## Tests

The failing shapes are all emitter-tier, and each currently emits compile-clean code, so a pipeline
test that reads the rendered method is the tier where they can fail.

* `ArgmappingKeyProjectionEmissionPipelineTest` gains an inferred-arm case per broken shape, beside
  the authored-arm cases it already holds: the dotted `@routine` binding, the dotted field-level
  `@condition` binding, and the bare `@routine` binding. Each asserts the decode's argument is the
  node id's own slot, through the existing `TypeSpecAssertions` helpers rather than as code strings.
* A case pinning that an authored projection beside an inferred one at the same coordinate still
  reads its own leaf, so the derivation is per row and not per method.
* A case at a `@condition` bare binding asserting the install rail's decode is what appears and the
  projection did not also fire, which is the precedence rule made falsifiable.
* `ResolvedNodeKeyProjectionTest` gains an assertion that the projection view carries
  `trailing_segment_name` and that it is null on the inferred arm and the author's own spelling on
  the authored one.
* `KeyProjectionRelationTest` follows the carrier's new component.
* One execution-tier case in `graphitron-sakila-example`, an inferred binding sent a real encoded id
  end to end, since every failure above is a request-time failure and the execution tier is where
  "it actually runs" is asserted.

## Documentation

* `docs/manual/reference/directives/condition.adoc`: rewrite the closing bullet. A dotted path
  stopping on a `@nodeId` input field is a key projection like any other, resolving the sole key
  column where the node type has one and failing the build where it has more, with the two remedies
  named.
* `docs/manual/reference/directives/routine.adoc`: no factual change; re-read the inferred-form
  paragraph once the emitter matches it, since it is currently the promise this item makes true.
* `ConditionResolver.installNodeIdDecode`: drop the paragraph describing the uncovered shape and
  replace it with the precedence rule, which is the fact a reader of that method now needs.

## Implementation notes

What the delivery did beyond what the plan spelled out, disclosed here rather than left in the diff.
The delivery landed at `1ac2f89`, already on trunk and so a citation a later reader can resolve; the
rework round answering the findings below is the commit carrying this sentence.

**The precedence rule became a named predicate, `ProjectedKeyReads.installRailOwns`.** The plan said
the check is local and available at both render sites. It is, but written twice it would have been the
same two-spellings-of-one-assumption shape this item exists to remove, so the rule is stated once as a
static on the sink. The routine site asks it of the binding's own extraction, which is where the
install left its mark: at an argument the decode is the extraction itself, and at an input field
`ConditionResolver.rewrapForNested` carries it as the leaf of the descent. The condition glue states
the same precedence by arm order, its whole-slot arm sitting above the projection lookup and citing
the predicate for the rule rather than calling it, because that arm destructures the
`NodeIdDecodeKeys` it matched and needs it to compose the decode, which a boolean predicate discards.

**Where the precedence is actually reachable, stated rather than assumed.** At the `@condition` site
the install rail and the projection sink do not meet on any SDL today, and the reason is not the
arity refusal the plan named: the store keys an input-field `@condition`'s projection at the input
type's own coordinate while the glue that rewrap produces looks the projection up by the consuming
field's, so the lookup misses. The check is therefore a stated rule rather than a fix for a live
defect, and its test spells the racing row at the glue's own key deliberately, so the rule is asserted
where it can be made to fail rather than where it currently cannot. The test's javadoc says so.

**Two execution-tier cases, not one.** The plan asked for one, an inferred binding sent a real
encoded id end to end. The dotted-inferred shape got that. The bare shape got its own, because it is
the one that shipped the base64 string to the database rather than merely failing, and it fails
differently: an integer routine parameter rejects the wire form at the call, so a green round trip is
the decode working. Two mutations in the example schema, `rentFilmPayloadInferred` and
`rentFilmPayloadBareNodeId`, beside the authored `rentFilmPayloadProjected` already there.

**`KeyProjection` refuses a blank trailing segment.** Null and non-null are the two readings an
emitter derives a leaf path from, and blank is neither. The carrier already refuses a blank
`argumentPath` and a blank `typeId` for the same reason, so this is that rule applied to the component
whose absence now carries meaning.

**`ProjectedKeyReads` lost its `PathExpr` overload and its own wire read.** The plan's rule that the
supplier stays with the caller left the sink with two ways to be asked and one of them composing a
wire read it should not own. Collapsing to a single `readFor(writtenSegments, wireRead)` also removed
the third spelling of the args-descent, the routine site's two arms now sharing
`RoutineCallEmitter.descentRead`.

**`routine.adoc` gained one clarifying sentence** where the plan said no factual change. The closed
form now holds wherever the `@nodeId` sits, an argument of the field as much as an input field below
one, and that is the half a reader of the "leave the leaf closed" paragraph could otherwise doubt.

**`TypeSpecAssertions` gained descent assertions asked without naming the root.** The condition glue
reaches an argument either as `args.get("x")` or through a lifted `xMap` local depending on whether
the row lifts it, which is a separate decision from which slot the decode descends to, so the two new
helpers ask about the slot alone.

## Retired vocabulary

* "the one shape neither rail covers", and any prose saying a dotted descent to a `@nodeId` input
  field is unclaimed or receives the wire string
* "a projection names a key column past a node id, so it has at least two segments"

## Reviewer findings

### Round 1, In Review -> Done gate

Not approved. The change itself is the change the spec approved, and it is complete against every
piece of evidence the spec named. The one thing missing is the item's own `## Retired vocabulary`
declaration: two sentences that state the retired claim in the present tense are still in the tree,
and one of them is in a file this delivery edited, contradicted by the fixture this delivery added to
that same file.

**1. `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`, the comment above
`input RentFilmProjectedInput`.** It reads "Without the trailing segment the base64 string would
reach `p_customer_id` verbatim, which is the silence the projection rules close". That is the first
retired term verbatim, and it is now false: `rentFilmPayloadInferred`, added by this delivery further
down the same file, binds `pCustomerId: input.customerId` against this exact input type with no
trailing segment, and `rentFilmPayloadInferred_aClosedLeafReachesTheRoutineAsAKey` asserts
`customerId` arrives as `3`. This is the site the sweep most needed to catch: the comment renders into
the example project's generated `schema.graphqls`, so it is prose a consumer reads, and it now
describes behaviour the delivery removed. Rewrite it to say what the two spellings now mean (the
column spelled, or the one-column key naming it), so the three sibling mutations below read as the
three arms of one rule rather than one working shape and two the comment says cannot work.

*Author's response.* Rewritten. The comment names both spellings that reach the column, the one
`rentFilmPayloadProjected` spells past the node id and the one `rentFilmPayloadInferred` leaves to
Customer's one-column key, says the base64 string reaches `p_customer_id` under neither, and points
at `rentFilmPayloadBareNodeId` as the same closed form spelled at the field's own argument, so the
three mutations read as three spellings of one rule.

**2. `graphitron-model/src/test/java/no/sikt/graphitron/model/intent/ArgmappingMatchTest.java`,
the javadoc on `aBareNodeIdArgumentHeadIsTheLeafWithNothingTrailing`.** It reads "This is the arm the
silently-wrong case runs through: today such a binding hands a routine parameter the base64 wire id
and nothing says a word". That is the bare-`@nodeId`-argument-at-a-`@routine` shape this item names as
its third broken coordinate and fixes; the sentence's "today" is now wrong. The store fact the test
pins (zero trailing segments on the leaf) is unaffected and the test itself stays as it is; only the
javadoc's account of what that fact leads to needs to catch up with the emitter.

*Author's response.* Rewritten, with the test and its assertions untouched. The javadoc now says what
the absent trailing segment leads to: the projection inferred from the leaf alone where the node
type's key is one column, and `BARE_NODE_ID` where it is wider.

Both are one- or two-sentence edits. Nothing else was found, and nothing in the code needs to move.

#### What was checked and held

Recorded so the next pass does not repeat it.

* Full reactor green on the delivered tree: `mvn install -Plocal-db`, `BUILD SUCCESS`, fourteen
  modules, zero failures across 742 test totals.
* Completeness was measured rather than read off the build, because every one of these shapes emitted
  compile-clean code before the fix and so was green then too. Neutering `ProjectedKeyReads.leafOf`
  back to the unconditional drop turns all four inferred-arm emission cases red, each on the assertion
  whose description names the defect: the two dotted cases and the mixed-arms case fail on "the
  decode's argument is the node id's own slot", and the bare case cannot render at all. Separately,
  moving the install-rail arm in `ConditionGlueRenderer.nestedExtraction` back below the projection
  lookup turns `aWholeSlotBindingKeepsItsInstalledDecodeWhenAProjectionAlsoResolves` red on its own
  claim. So the precedence rule is falsifiable where the implementation notes say it is, and none of
  the new pins is vacuous.
* No code-string assertions on generated method bodies. The three new helpers go into
  `TypeSpecAssertions`, whose whole stated purpose is to keep body scanning in one file behind typed
  questions, and the call sites ask about a slot and a decode rather than matching rendered text.
* User-facing-doc check clean: the two `docs/` pages carry no roadmap-internal markers.
* The derivation swap is behaviour-preserving on the authored arm at both sites, which is what lets
  the existing fixtures stand as the regression pin. At the condition glue `leaf.size() == 1` is
  reachable on exactly the paths `nif.path().size() == 1` used to be, and `leaf.subList(1, ...)` is
  the old `path.subList(0, size - 1)`; at the routine site `String.join(".", segmentNames(path))`
  is `PathExpr.asString()`, so the relation is looked up by the same key.

#### Noted, not blocking

* `TypeSpecAssertions.materialisationDecodesUndescended` has no caller anywhere in the reactor. The
  implementation notes describe *two* new descent helpers and two is what the tests use; this is a
  third that shipped unused. Delete it or give it the case its javadoc argues for ("worth asking
  negatively as well as positively") while the file is open.

  *Author's response.* Deleted. The case its javadoc argues for is already asked, and asked more
  precisely, by the two helpers that name the slot: `materialisationDecodesWireSlot` at the bare
  binding, where the decode takes the argument read whole, and `materialisationDecodesWireDescent` /
  `materialisationDecodesDescentTo` at the two dotted ones, where they name the slot descended to.
  Each of those fails on the wrong-slot emission this item removed, so a negative arity test beside
  them could not fail where they pass.
* `ProjectedKeyReads.installRailOwns` is called at one render site, not two. The condition glue
  states the same precedence by arm order instead, at the top-level switch and again as the `if`
  ahead of the projection lookup. That is defensible, the nested arm needing the destructured `nidk`
  the predicate throws away, but the implementation notes' "asked at both sites" is not what shipped
  and the next reader will go looking for the second call.

  *Author's response.* The implementation note now says what shipped, and says why: the sink states
  the rule once, the routine site asks it, and the glue states the same precedence by arm order for
  the reason this finding names. No code moved.
* The spec body was never annotated with a landing SHA before the flip to In Review, the same slip
  the last two Done-gate reviews recorded. It cost nothing here, the item having shipped in one
  commit.

  *Author's response.* Annotated at the head of `## Implementation notes`, where the delivery's own
  SHA is now cited. It is a resolvable citation rather than the pre-rebase kind the roadmap's own
  landed-note item warns about, the commit being on trunk already.
