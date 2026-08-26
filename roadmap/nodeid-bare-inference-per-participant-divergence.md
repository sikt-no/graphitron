---
id: R726
title: "The @nodeId instruction population reaches the multitable coordinate but cannot key it by participant: state the limit and pin it"
status: In Progress
bucket: architecture
priority: 4
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-26
---

# Bare @nodeId inference on a multitable filter can answer differently per participant with no diagnostic

A `@nodeId` without `typeName:` on a filter input field or argument infers its node type from the containing table (`NodeIdLeafResolver.inferTypeName`, backed by `ctx.nodes.forTable`). On a query returning a multitable interface, classification re-runs once per participant with that participant's table, so the inference re-runs too: each branch can infer a different node type, or one branch can reject as ambiguous while its siblings resolve, and nothing tells the author the leaf means different things on different branches. The decoded filter then compares against differently-typed keys per branch, silently. The likely shape of the fix is a consistency check at the consuming field (all participants must infer the same node type, otherwise demand an explicit `typeName:`), which matches how `inferTypeName` already prefers rejection with a "specify explicitly" message over guessing at a single coordinate. Surfaced while speccing the per-participant join-path item for the same coordinate; the participant-identity threading that item builds gives this check its natural seam.

## The instruction relation cannot state this question yet (2026-08-20)

The `@nodeId` instruction population became a store relation on 2026-08-20, which changes where
this bug should be detected and adds a prerequisite the original filing did not know about.

`intent_node_id_instruction` resolves a bare directive's node type in two arms, and both reach the
slot's table through `intent_argument_scope_table`. That relation demands an unambiguous binding
and says so in its own comment: two candidate tables are two different predicates, so a pair that
is not certain is not the pair the classifier would have had in hand. A field returning a
multitable interface or union has no such binding, so **the coordinate this item is about
currently produces no instruction row at all**: not a divergent one, and not an ambiguous one.
The `TARGET_TABLE_NODE_TYPE` arm additionally demands `candidates = 1`, and the name-carried
`TARGET_ID_NAME` arm joins the field's return type to `intent_node_type`, which a multitable
interface is not.

So the relation has no participant dimension at any grain, and nothing pins the silence:
`NodeIdInstructionTest` has no interface or union case. Its nearest case,
`oneInputFieldConsumedTwiceIsTwoRowsThatCanDisagree`, establishes disagreement across two
*consuming fields*, which is the use-site grain rather than the participant grain this item needs.

Two consequences for this item's shape. The fix's natural home moves: a consistency check stated
over the population is a query, not a pass over `inferTypeName`'s call sites, and it lands beside
the defect stratum rather than inside the resolver. But it cannot be written until the population
reaches the coordinate, so **the prerequisite is a participant-keyed arm on the instruction
relation** (or an explicit decision that the multitable coordinate is out of the population, in
which case this item becomes that rejection instead). That prerequisite is shared with R673 and
R676, and it is also a hole in the relation-move item's own claim that the resolution relations
and its defect view partition the instruction population: this coordinate falls in neither.
Raised there rather than duplicated here.

Detail and the verification behind it: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`,
Finding 1.

## The consuming-field seam now exists (2026-08-21)

R673 landed per-participant dispatch for a bare `@nodeId` **argument** on a multitable root, and with
it the seam this item's consistency check wants: one producer in `FieldBuilder` that takes the field
definition plus the table-bound participant set and answers, per `@nodeId` leaf, whether the
participants agree on the node type (`SharedTarget`) or disagree (`PerParticipant`). The divergence
this item reports is that same computation read as a diagnostic rather than as a dispatch fact, so
the question is no longer where to detect it: it is what the right answer is per shape. R673 answers
two of them already, dispatch for a top-level argument and a build-time rejection naming the
participants for a nested-input leaf, so what remains here is the shapes R673 left out of scope.

---

## Decision: the multitable coordinate is deliberately out of the instruction population, stated and pinned (2026-08-25)

### Rescope

The bug this item was filed as is fixed. R673 shipped the cross-participant computation
(`FieldBuilder.resolveNodeIdArgTargets`, one producer over the table-bound participant set), and
with it neither authored shape can silently mean a different node type per branch: a divergent
bare `@nodeId` top-level argument dispatches per branch with a matches-none client error, and a
divergent bare nested filter-input leaf rejects at build time naming the leaf, the participants
and their node types. Both are pinned in `MultiTableFilterLoweringTest`, along with the enforcer
for the single-base-table arms.

What survives the fix is the fact-store hole the 2026-08-20 audit recorded as Finding 1
(`roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`) and assigned to the relation-move
item's implementer. That item has since shipped without answering it, so the finding is inherited
here explicitly: this item's Done gate closes Finding 1, and the next sweep should not re-derive
it. The item is retitled and rebucketed in this pass for the same reason; the filed title asserted
a live silent divergence the tree has since falsified, and the README roll-up should not report an
open bug here.

Two terms, used throughout. The **multitable coordinate**: a `@nodeId` leaf (top-level argument or
nested filter-input field) whose consuming field returns an interface or union that binds no table
of its own, each participant binding its own. The **instruction population**:
`intent_node_id_instruction`, the store relation stating every authored `@nodeId` form and the
node type each resolves to.

### The state of the tree, precisely

At the multitable coordinate a bare `@nodeId` leaf produces no instruction row and no defect row.
The mechanism matters for how the boundary is worded. The consuming field's return type binds no
table, so `intent_field_scope_table` has no row for the field, which is the absence that
relation's comment already calls the ordinary case for a field that reads no table at all; the
fan-out into `intent_argument_scope_table` is therefore empty, and both bare arms that serve
arguments and input fields (`TARGET_TABLE_NODE_TYPE`, and `TARGET_ID_NAME`, whose argument arm
instead joins the consuming field's named type to `intent_node_type`, which a multitable interface
is not) answer nothing. This is *not* the certainty guard declining an ambiguity: `candidates` is
zero, not two, and a future contributor should be pointed at a new key column, never at relaxing
`candidates = 1` (the demand `intent_field_scope_table` and `intent_node_id_decode_endpoint` both
argue for at length).

Two shapes sit outside the boundary and stay in the population. An explicit
`@nodeId(typeName:)` leaf resolves on the `EXPLICIT_TYPE_NAME` arm, which joins only the written
type name, at every coordinate. A single-table discriminated interface binds a table and is in the
population; the boundary is the multitable shape alone, and every sentence written for this item
must scope itself that way.

### The decision

The population's grain is one certain scope table per use site. The multitable coordinate's
answer has a different grain, use site times table-bound participant, which the relation has no
column for. The classifier owns that grain today through R673's producer, and the store
deliberately does not, until a second reader asks for the fact. The boundary becomes the
relation's third stated population boundary, alongside the two its comment already carries (the
no-node-type exclusion, the input-`@reference` not-yet), which makes this a third instance of a
shipped pattern rather than a new principle.

The alternative, widening the population with a participant-keyed arm now, is declined rather
than deferred, on two principled grounds. First, the two-reader rule: `FieldBuilder`'s producer is
the single reader of the cross-participant question, and R676's spec keeps it single (it threads
`ParticipantRef.TableBound` through the same producer). Second, registration: a new relation needs
an agreement anchor under `FactCaptureAgreementTest`, and with no second reader the only available
anchor is a `walk_`-family shadow of the very producer it would mirror, while the `walk_` family
exists to be diffed against a replacement during a live migration; a shadow with no migration
behind it is the shape the fact model declines. The arm becomes live the day a second reader
arrives (an LSP surface, a defect view, or R676's route selection if it turns store-ward), and the
key spelling below is its ready-made spec.

### Deliverables

1. **The boundary statement.** `intent_node_id_instruction`'s table comment gains the third
   boundary paragraph, in the voice of the two it already has. Content: the relation's grain is
   the use site and the one table the site's content binds against; a use site whose consuming
   field returns a multitable interface or union binds no single table, so its bare forms are not
   rows here, on either bare arm; the answer at that coordinate has grain
   (graph, site, type, field, argument-or-path, participant type name), and closing the boundary
   wants a participant-keyed arm carrying exactly that key. Store terms only: no Java class name
   (the walk drains with the strangler, and the sentence must not evaporate with it) and no
   roadmap id. The key spelling is stated once, here and in the comment, so a later widening and
   R676's implementer cannot mint two spellings of one key.

2. **Store-tier pins** (`NodeIdInstructionTest`, graphitron-model): the relation's first
   interface/union coverage, extending the test's existing negative-pin idiom. One fixture, one
   filter input type consumed twice, so the controls isolate the boundary:
   - a bare `@nodeId` argument on a field returning a multitable interface: zero instruction rows;
   - the same bare leaf as a nested filter-input field under the same consumer: zero rows;
   - the union-returning variant of the argument case: zero rows;
   - control one: an explicit `typeName:` sibling at the same multitable coordinate is a row on
     `EXPLICIT_TYPE_NAME`, so the exclusion is the bare forms' alone;
   - control two: the same bare input field consumed by a single-table field in the same fixture
     is a row on its bare arm, so the exclusion is the multitable consumer's alone.
   The delta between the two controls and the empty cases is exactly the boundary. Also pin that
   `intent_node_id_decode_defect` holds no row for the excluded coordinate: the silence is a
   stated boundary, not a defect.

3. **Pipeline-tier pin** (beside the R673 cases in `MultiTableFilterLoweringTest`): the mixed
   shape, the one divergence shape R673 left unpinned. One participant's table backs a node type
   and its sibling's backs none; the build fails through the sibling participant's classification.
   The pin asserts the failure and its coordinate as a typed rejection, and must not assert the
   message string. The wording decision is recorded here rather than left implicit: the
   single-table message from `BuildContext.inferNodeTypeOverTable` is accepted as sufficient at
   this coordinate, because it names the participant's own table (which identifies the
   participant) and its remedy, an explicit `typeName:`, is exactly the fix; accepting it without
   pinning its prose keeps it revisable.

4. **The scaffolding sentence.** R673 added `NodeIdArgTarget` as a sealed hierarchy inside the
   walk, and the pipeline window rule says a capability adds no walk-side leaf type; the standing
   defense is that gathering scaffolding discarded before the model is blessed, and
   `NodeIdArgTarget` is discarded (only `NodeIdArgDispatch` survives into the plan). This item
   ratifies that out loud: `resolveNodeIdArgTargets`'s javadoc states that it owns the
   use-site-times-participant grain that the `intent_node_id_instruction` population deliberately
   excludes for its bare forms, naming the relation with `{@code}` (a SQL relation is not a
   linkable symbol). The boundary is then stated on both sides of it.

### Out of scope

- The participant-keyed arm itself; declined above, key spelling recorded for whoever adds it.
- Lifting the nested-input divergence rejection to dispatch (R673's stated later change, waiting
  on a consumer asking for it).
- Rewording `inferNodeTypeOverTable`'s messages with participant context (accepted as sufficient
  in deliverable 3; R676's participant-aware wording deliverable covers the FK-path arm, which is
  a different arm of the resolver).
- Everything in R676: `@referenceFor` widening, per-participant route selection, the override
  escape.

### Coordination with R676

R676 reached Ready on 2026-08-25, reconciled with the tree, and its reconciliation carries two
claims about this item that were true of the item as filed and are settled differently by this
spec. It calls the missing participant dimension "a blocker for R726" and says "R726 needs the
arm outright", both read off the pre-spec body, which assumed the fix would be a consistency
check stated as a store query. The re-scope above removes that need: the consistency question is
answered by the classifier's shipped producer, and the store-side deliverable is a stated
boundary, not a query, so nothing blocks. R676 also left the arm's ownership question open on
purpose ("worth settling before this item reaches Ready, but it is not a dependency of this plan
as scoped"); this decision settles it: nobody owns widening until a second reader exists, and
R676's route selection threads participants Java-side as its plan already does. Nothing lands in
R676's file from this item; its implementer reads the settled answer here, and the key spelling
in deliverable 1 is the one to reuse if the route fact ever turns store-ward. The deliverables
are disjoint.

### Acceptance

- The boundary is stated on `intent_node_id_instruction` in store terms, readable without this
  item or any roadmap context.
- The store-tier pins and both controls are green, including the defect-view emptiness pin.
- The pipeline pin on the mixed shape is green and asserts no message prose.
- Full `mvn install -Plocal-db` green (the DDL comment edit puts graphitron-model in the change
  set, so the scoped inner loop does not cover it).
- The Done-gate changelog entry records that audit Finding 1 is closed by this item.

Nothing is retired; no retirement sweep needed at the Done gate.

---

## Amendment: the exclusion the spec was signed off on no longer holds (2026-08-26)

### What changed under the spec

The decision above rests on one factual claim about the tree: "at the multitable coordinate a bare
`@nodeId` leaf produces no instruction row and no defect row", because the consuming field's return
type binds no table, so `intent_field_scope_table` has no row for it and the fan-out into
`intent_argument_scope_table` is empty. That was true when the spec was written and when it was
signed off. It is not true now.

The participant fan-out landed on trunk the same day this item reached Ready and is not an ancestor
of the sign-off commit, so neither the author nor the reviewer could have read it. It gives
`intent_field_scope_table` a third arm, the distinct tables of the new
`intent_field_participant_scope_table` under a `PARTICIPANT_TABLE` basis, unioned in outside the two
ranked rungs. `intent_argument_scope_table` is a pure fan-out of that relation, so the departure at
the multitable coordinate is now one table per table-bound participant rather than nothing.

Measured rather than reasoned. A probe fixture, two node types over their own tables under an
interface binding none, consumed by one root field, returns:

```
ARGUMENT    Query.media(filterId)   TARGET_TABLE_NODE_TYPE Actor
ARGUMENT    Query.media(filterId)   TARGET_TABLE_NODE_TYPE Film
INPUT_FIELD Query.media(where)/someId TARGET_TABLE_NODE_TYPE Actor
INPUT_FIELD Query.media(where)/someId TARGET_TABLE_NODE_TYPE Film
```

Two rows per use site, on the bare arm, at both bare sites. The relation's own table comment already
says so: the fan-out commit added a paragraph stating that an inferred instruction at such a field
names one node type per branch, and that the two rows carrying two node types are what make the
classifier's nested-leaf rejection a detection over this relation. So the population does not exclude
the coordinate, and deliverables 1, 2 and 4 as written would have stated and pinned the opposite of
what the tree does.

### What the item becomes

The intent survives unchanged: state what the population does at this coordinate in the relation's
own terms, pin it, and record the key spelling once so a later widening cannot mint two. Only the
polarity of the fact flips, from an exclusion to a limit.

The limit is that the rows arrive without the participant. The relation is keyed on the use site and
the one table the site's content binds against, so a use site with two table-bound participants is
two rows differing in `node_type_name` and in nothing else, and no column says which participant
resolved which. The pairing can be recovered by joining a row's node type back to its table and that
table to the participant binding it, and that recovery is unsound in general: two participants over
one table, or a node type that is not the participant type itself, each break it. A reader can
therefore see that the branches disagree, which is exactly what the fan-out commit claimed for it,
and cannot assemble one branch's decode from these rows.

Everything the decision section argued for still holds against the corrected fact, and none of it
needed the exclusion. The grain the answer has is still use site times participant, the relation
still has no column for it, `FieldBuilder.resolveNodeIdArgTargets` is still its single reader, and
the two-reader rule and the registration argument still decline the participant-keyed arm rather
than defer it. The key spelling is unchanged. The coordination note to the per-participant path item
is unchanged: nothing blocks it, and the arm stays unowned until a second reader asks.

### Revised deliverables

1. **The limit statement**, in place of the boundary statement. The relation's multiplicity paragraph
   already says the rows come out; what it does not say is that nothing identifies the branch. That
   sentence, the unsound recovery, the two shapes the fan-out does not reach (a written `typeName:`,
   a discriminated interface binding one table), the key spelling, and the warning against relaxing
   the single-candidate demand are appended to it. Store terms only, no Java class name and no
   roadmap id, as before.

2. **Store-tier pins**, positive where the spec asked for empty. Six cases in `NodeIdInstructionTest`:
   the bare argument at an interface coordinate is one row per participant with an explicit
   `typeName:` sibling as the control; the two rows agree on every column but the node type, which is
   the limit made a test; the union spelling reads the same; one bare input field consumed by the
   multitable field and by a single-table field is two rows against one, which is the fan-out
   isolated as a delta at one instruction; a discriminated interface binding one table is one row;
   and `intent_node_id_decode_defect` holds no row for the coordinate, which the spec asked for and
   which survives the flip unchanged, the disagreement being the classifier's verdict rather than a
   refused decode.

3. **Pipeline-tier pin**: unchanged from the spec, the mixed shape being independent of the store
   question.

4. **The scaffolding sentence**: unchanged in purpose, corrected in content. It now states that the
   producer owns the use-site-times-participant grain that the population reaches without keying,
   rather than one the population excludes.

### Acceptance, revised

- The limit is stated on `intent_node_id_instruction` in store terms, readable without this item.
- The six store-tier pins are green.
- The pipeline pin on the mixed shape is green and asserts no message prose.
- Full `mvn install` green.
- The Done-gate changelog entry records both that audit Finding 1 is closed and that the finding's
  own premise was overtaken by the fan-out, so a later sweep does not re-derive the exclusion.

### One thing this amendment does not settle

Whether an author-side amendment is the right instrument. The workflow's own answer for a spec
needing substantive redesign is a Ready → Spec reopen and a second review, and the redesign here is
substantive in its facts even though its decision and its key spelling are untouched. The
implementation proceeds because the pins record what the tree does either way and the alternative
delivers nothing, but the In Review gate should read this section first and send the item back to
Spec if the reviewer wants the flipped premise re-reviewed rather than merely approved.
