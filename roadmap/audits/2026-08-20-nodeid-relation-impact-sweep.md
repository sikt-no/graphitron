# @nodeId relation move: which roadmap items the change touches

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it,
and it is Markdown so the `check-adoc-tables` build step leaves it alone.

It sweeps the roadmap against the `@nodeId` work that landed on trunk on 2026-08-20: the
directive's instruction population and its encode/decode resolution becoming store relations.
This is the architecture-drift complement to the same-day symbol-drift audit
(`2026-08-20-roadmap-staleness-audit.md`), on the model of
`2026-08-06-fact-base-impact-sweep.md`, and it exists because that audit's window closes just
before this change.

**Why a separate sweep.** The staleness audit states its baseline as HEAD `7c07683`, committed
2026-08-19 22:29. Every commit of the relation move is dated 2026-08-20 (`705f96b` stage 2a
through `07820d5`). So the nodeId relations are outside that audit's window entirely, and its
nodeId findings (R34, R135, R615, R273, R588, R724, R730, R731, R735) were reached against a
tree that did not yet contain them. Nothing in it is wrong; it simply could not see this.

## What actually landed, and what did not

Verified against the tree rather than read off the plan, because the distinction decides every
finding below.

**Landed.** Six relations in `graphitron-model.sql`: `intent_node_id_instruction` (the
instruction population, all three forms), `intent_node_id_decode_endpoint`,
`intent_node_id_decode_hop`, `intent_node_id_decode_hop_column`, `intent_node_id_decode_column`,
and `intent_node_id_encode`. Plus stage 1's argument-site reference-step sibling views and
`intent_argument_scope_table`. Store-tier coverage in `graphitron-model`:
`NodeIdInstructionTest`, `NodeIdEncodeTest`, `NodeIdDecodeColumnTest`, `NodeIdDecodeReachTest`.

**Not landed.** Any behaviour change in `graphitron`. `NodeIdLeafResolver` still owns resolution
and still carries `LIFT_FAILURE_MARKER`, `CONDITION_STEP_MARKER`, `JoinPathResult`,
`validateLift`, and both `Resolved.FkTarget` arms. `CallSiteExtraction.NodeIdDecodeKeys` still
reads `permits ThrowOnMismatch` and nothing else. `TypeFetcherGenerator.STUBBED_VARIANTS` is
still `Map.of()`. The `BARE_NODE_ID` verdict text in `intent_argmapping_projection_defect` is
still the pre-change wording.

**So the relations are additive facts today, and no active item is falsified by the landed
code.** The drift this sweep records is of two kinds: prospective (a premise the remaining
stages remove) and newly visible (a gap the relations make legible that no item had named).
Both are worth recording now rather than at each stage's gate, because three items in Spec are
being drafted against the pre-change mechanism this week.

## Finding 1: the instruction population is silent at the multitable coordinate

This is the one finding that is not bookkeeping, and it lands on an item in progress.

`intent_node_id_instruction`'s two bare-`@nodeId` inference arms reach the slot's table through
`intent_argument_scope_table`. That relation's own comment states the constraint plainly: both
rungs "demand an unambiguous binding ... a table this argument's content binds against is a
table a predicate is emitted on, and two candidate tables are two different predicates, so a
pair that is not certain is not the pair the classifier would have had in hand."

A field returning a multitable interface or union has no such binding. So a bare `@nodeId` on
an argument or filter leaf of such a field produces **no instruction row** at all, on either the
`TARGET_TABLE_NODE_TYPE` arm (which additionally demands `candidates = 1`) or the name-carried
`TARGET_ID_NAME` arm (which joins the field's return type to `intent_node_type`, and a
multitable interface is not one).

`NodeIdInstructionTest`'s nineteen cases contain no interface or union case, so nothing pins the
behaviour in either direction. The nearest case,
`oneInputFieldConsumedTwiceIsTwoRowsThatCanDisagree`, establishes that the use-site grain lets
one input field yield two disagreeing rows across two *consuming fields*; it says nothing about
one consuming field with N participants, which is a grain the relation has no column for.

Three items sit exactly there, and one of them is the item that built the relation:

- **R673** (`nodeid-arg-dispatches-on-typeid`, Spec). Its reported repro is
  `applikasjon(id: ID! @nodeId): Applikasjon` over three `@table` implementations: a bare
  `@nodeId` argument on a multitable-returning field, which is precisely the shape with no row.
  Its whole analysis rests on classification re-running once per participant
  (`FieldBuilder.lowerParticipantFilters` handing `NodeIdLeafResolver.inferTypeName` each
  participant's own table). When stage 2 makes the resolver a reader of these rows, the
  per-participant re-run has nothing to read.
- **R676** (`nodeid-filter-per-participant-paths`, Spec). Its deliverable 3 threads
  `ParticipantRef.TableBound` through to the resolver so a route can be selected per
  participant. The relations carry no participant dimension at any grain.
- **R726** (`nodeid-bare-inference-per-participant-divergence`, Backlog) *is* this divergence,
  filed as a bug. Its natural home is now this relation, and the relation currently cannot
  express the question.

And it bears on R728's own stage-5 exit condition, which claims "the resolution relations and
this view partition the instruction population, so no instruction falls in neither." A bare
`@nodeId` under a multitable consumer falls in neither: no resolution row, and no defect row
either, since the defect view is over the instruction population. Under the item's stated total
rule ("there is no coordinate where a dropped instruction is wanted") that is the exact silence
the rule exists to remove.

This wants R728's implementer, not an edit here. It is either a widening of the population (a
participant-keyed arm, which is also what R673 and R676 need) or an explicit statement that the
multitable coordinate is out of the population and why, with a test case pinning the choice
either way. What it should not be is the current state, where the answer is a consequence of
`intent_argument_scope_table`'s certainty demand rather than a decision anybody made.

## Finding 2: R152 is obsolete, its fix landed by other means

Not in the staleness audit, and a clean win rather than drift.

R152 (`lsp-nodetype-hover-column-scoping`, Backlog, filed 2026-05-13) asks that the
`@nodeId(typeName: "X")` hover stop resolving key-column types through a catalog-wide linear
scan and scope the lookup to X's own `@table`. Every symbol its diagnosis and its prescription
name is gone from the tree: `columnGraphqlType` (0 hits), `CompletionData.NodeMetadata` in the
LSP main sources (0), `CatalogBuilder.buildNodeMetadata` as the hover's source (the hover no
longer reads it), and `TypeContext.enclosingTypeDefinition`, the sibling shape it said to mirror
(0). The LSP's move to the fact store took them.

The asked-for behaviour is in the tree. `Hovers.nodeColumns` reads the node type's own binding
from `graphitron_table` and returns that table's columns, and its javadoc states R152's bug in
the past tense as the reason it exists: "The projection looked a key column up by name across
every table in the catalog and took the first hit, which on a name as common as `id` answered
from whichever table came first; a key column of a node is a column of that node's own table or
of nothing."

One thing R152 asked for is genuinely absent: the test it wanted, two tables sharing a column
name with diverging `graphqlType`, pinning the scoping. Nothing in `graphitron-lsp`'s tests
pins it. So the item is re-scoped down to that residual rather than discarded outright; the
discard is the reasonable alternative if the pin is judged not worth an item.

## Finding 3: R34's re-spec now has a target

The staleness audit already calls R34 (`nodeid-migration-quickfix`) "carried,
self-contradictory" and asks for a re-spec, having confirmed its three WARN sites were deleted.
That is right, and re-confirmed here independently: `BuildContext.classifyInputFieldInternal`
survives but neither the NodeId-scalar arm nor the FK-qualifier arm does, and
`SkipMismatchedElement` is 0 hits.

What this sweep adds is that the audit left the re-derivation target open ("R473's landed
grammar rejections or the R589 claim relation"), and there is now a third option that fits the
deliverable better than either. `intent_node_id_instruction` carries a `basis` column whose
vocabulary is exactly which of the three forms carried the instruction at that coordinate
(`EXPLICIT_TYPE_NAME`, `CONTAINING_NODE_TYPE`, `TARGET_TABLE_NODE_TYPE`, `OWN_ID_FIELD`,
`TARGET_ID_NAME`), keyed by use site and carrying a source position. A migration quick fix's
whole job is "this coordinate means a node id, and here is the directive text that says so
explicitly", which is that column plus `node_type_name`. Re-spec against the relation rather
than re-deriving from rejections.

## Finding 4: R135's out-of-scope sentence goes stale at stage 3

A second, independent drift on an item the staleness audit already flagged for a different one.

The audit's finding on R135 (`multi-hop-nodeid-fk-permutation-test`) is a carrier re-anchor:
`InputField.ColumnBackedReferenceField.liftedSourceColumns()` is gone and the tuple is now
`FilterBinding.Local`. Confirmed, `liftedSourceColumns` survives only on
`NodeIdLeafResolver.Resolved.FkTarget.DirectFk`.

The drift this sweep adds is to the item's reasoning, not its symbols. R135 rests twice on
`validateLift` being a rejection that holds positional alignment at every intermediate hop:
once in the body ("the per-hop `validateLift` invariant still requires positional alignment at
each intermediate step") and once in Out of scope ("Relaxing the per-hop `validateLift`
predicate ... today the invariant is positional at every intermediate step"). R728 stage 3
removes that rejection: an absent lift becomes absent local columns and the chain binds
remotely. R135's own fixture is designed to *satisfy* the lift, so its acceptance criterion
survives intact; it is the framing and the out-of-scope carve-out that stop being true.

R136 (`nodeid-fk-permutation-execution-tier`), the execution-tier sibling, is unaffected in
substance.

## Finding 5: R673 and R728 rewrite the same javadoc and the same seal

R728 does not name R673 anywhere, and both edit `NodeIdLeafResolver`'s class javadoc.

R673's D6 rewrites its "Failure mode is fixed at `ThrowOnMismatch`" sentence and
`CallSiteExtraction`'s sealed-to-one-arm statement, because D1 adds a second arm
(`PruneOnMismatch`). R728's Retired vocabulary rewrites three *other* statements in the same
javadoc (the one-conjunct discriminator, in the `FkTarget` seal's arm list, in `TranslatedFk`'s
record javadoc, and in its `@param joinPath`) plus `resolveFkJoinPath`'s
identity-carrying-lift paragraph.

Both claims are true of the tree today: the seal does read `permits ThrowOnMismatch`, and
`PruneOnMismatch` is 0 hits. So neither item is wrong; they are two items restructuring one
file's contract from different directions with no coordination note in either. A sequencing
sentence in whichever reaches Ready second is enough.

## Finding 6: R676's decode-rail grammar loses half its content, and its deliverable 2 collides

R728 names R676 once, for `LIFT_FAILURE_MARKER`: "its author has to be told the constraint moved
rather than disappeared." That is the smaller half.

R676's path-grammar bullet reads "hops on column pairs, the identity-carrying lift validation,
no `{condition:}` steps (the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER` and
`CONDITION_STEP_MARKER`)". After stage 3 the lift conjunct is gone and only
`CONDITION_STEP_MARKER` remains, which R728 states it deliberately does not retire. So the
bullet keeps one of its two named constraints.

The larger half R728 does not name: R676's deliverable 2 is a `NodeIdLeafResolver` rework,
"sealed route-selection outcome in `resolveFkJoinPath`; participant-aware rejection wording;
predicate-ownership parameter; fourth `Resolved` arm (author-owned predicate)". R728 stage 2
retires `JoinPathResult`, which is what `resolveFkJoinPath` returns, and makes the class a
reader of relation rows. Two items restructure the same method from opposite directions: one
replacing its return shape with a sealed outcome, the other dissolving the resolution it
performs into SQL. Add Finding 1's participant grain and R676 has three reasons to re-read
R728's stage 2 before its next Spec pass.

## Finding 7: R702's `NodeIdLeafResolver` census entry moves into SQL

R702 (`exact-catalog-name-comparisons`, Backlog) censuses case-folding comparison sites and
lists "`NodeIdLeafResolver` (three key-column alignment loops)". Accurate today, and the count
is exact: three `equalsIgnoreCase` comparisons on `ColumnRef.sqlName()`, at
`NodeIdLeafResolver.java:357`, `:522` and `:561`.

Stage 2 moves that alignment into the decode relations, where the fold happens in SQL. R728
names the same crossing from the other side, in its R731 paragraph: "every reader that has to
match against it folds case at the crossing." So R702's entry does not disappear, it changes
habitat, and a census keyed to Java call sites will silently lose it. Worth a line in R702
naming the relations as the successor habitat.

## Items whose nodeId cites are confirmed current

Checked and left alone. Recorded so the next sweep does not re-derive them.

| Item | Claim checked | Verdict |
|---|---|---|
| **R24** `nodeidreferencefield-join-projection-form` | "the map is empty today" of `TypeFetcherGenerator.STUBBED_VARIANTS` | `Map.of()`. Current. See the note below. |
| **R267** `nodeid-encoder-deprecated-convert` | `getDataType().convert(values[i])` and the class-wide `@SuppressWarnings({"deprecation","removal"})` in `NodeIdEncoderClassGenerator` | Both present, `:247` and `:143`. Current. |
| **R588** `node-without-metadata-diagnostics` | the rejection text "`@nodeId` requires the containing type to be a node type" | Verbatim in the tree. Current. |
| **R615** `idreffixture-purpose-comment-stale` | its three-way grading, in particular that `NodeDeclaration.isNodeType` and `NodeProvenance.Origin.METADATA` are live | Both live. The grading holds. |
| **R419** / **R420** list-valued `@nodeId` on INSERT | `MutationInputResolver.admitMutationInputFields`, `TypeFetcherGenerator.buildInsertDecodeLocals` / `buildPerCellValueList` | All present. R728's write-rail work is about remote binding, a different axis; no interaction. |
| **R692** `inert-element-less-reference-rejection` | that the silent acceptance is "deliberate as of the FK-target `@nodeId` translated-filter work" | Precedent intact. |
| **R735** `projected-key-column-across-a-node-id-list` | that the list shape is rejected as a scope limit rather than a rule | Current, and `intent_argmapping_projection_defect`'s comment now states the same thing from the store side ("a list-shaped node id with one trailing segment is not a defect ... what stands between it and emission is that no emitter builds that shape yet"). |
| **R136**, **R66**, **R92**, **R122**, **R257**, **R333**, **R397**, **R462**, **R521**, **R561**, **R577**, **R626**, **R675**, **R705**, **R713**, **R724**, **R730**, **R753** | incidental `@nodeId` mentions: fixture names, test-class names, census rows, worked SDL examples | No premise touched. |

One note on R24 rather than a finding. Its deferred shape, rooted-at-parent
`ColumnBackedReferenceField` with `NodeIdEncodeKeys`, now has a resolution row:
`intent_node_id_encode` reports `PROJECTED_COLUMNS` for it, that source covering key columns
"whether they are the row's own or reached through an authored path". So R24's shape becomes an
instruction with a resolution row and no emitter, which is the deferral-versus-defect
distinction R728 stage 5 has to keep sharp; its exit condition says the defect view "strictly
adds refusals and removes no emission", and R24's shape is already a refusal
(`Rejection.Deferred` keyed by `StubKey.VariantClass`). Nothing to change in either item; worth
R728's implementer having the case in hand when stage 5 partitions the population.

## What this sweep did not do

- It did not edit R728. The item is In Progress and Finding 1 belongs to its implementer as a
  design question, not to a passing session as a spec edit.
- It did not re-audit symbol drift. That is the same-day staleness audit's job and it was done
  eight hours earlier against a tree that differs from this one only in these relations.
- It did not check the two earlier nodeId changes (the explicit-grammar flip and node
  inference, shipped 2026-08-09/10) beyond what the items themselves already reconcile. The
  staleness audit's window covers them.
