---
id: R563
title: "Operations as a relation: the leaf cross-product dissolves additively"
status: Backlog
bucket: architecture
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-01
last-updated: 2026-08-01
---

# Operations as a relation: the leaf cross-product dissolves additively

This item is a **programme** in the sense R549 (Done 2026-08-01; see `roadmap/changelog.md`) used the
word: it frames a direction, states the invariants that make it falsifiable, and lists slices that
each ship to trunk on their own. R549 built the back half of the model: the emit as coordinate-keyed
command relations (launcher, condition, projection, fetcher-edge) the shell folds over, with
membership declared as producer data. This programme executes the front half R333 resolved and left
waiting: `coordinate -> operation` is a 0..N relation, "the one genuinely multi-valued relation in
the model", and storing it as a single-valued arm plus leaf identity is "the 1NF fault that
multiplies the leaf cross-product in the first place" (R333, the normalized schema). The deliverable
is that relation materialized in the fact base, every operation payload homed on its member row
exactly once, the classifier's operation-encoding leaf distinctions dissolved additively, and the
corpus asserting a coordinate's operation set on declared-equals-produced agreement.

R333 governs. This item consumes its resolutions and does not re-litigate them: the entity is the
coordinate; `source` and `target` stay single-valued facts (their separation was fact-independence,
never a normal-form fix); only `operation` splits out because it must; members are minted by
independent walkable trigger facts and union additively; each member realizes as exactly one
back-half seam (R333's member-to-seam crosswalk); the corpus verdict generalizes to a source fact, a
target fact, and a set of operation rows, each independently assertable.

## The reframing in one paragraph

Today `Operation` is a 17-arm sealed summary column: `OutputField.operation()` computes one arm per
coordinate by a switch on leaf identity, the arm payloads duplicate facts that live on the leaf
records, and only two structural consumers in the whole tree read the arm at all
(`TenantBindingIndex.directSlots`, `TenantDslEmitter.slotReads`); every real consumer reads the
payload slots off the leaves directly. The operation axis is therefore not where the cost looks like
it is. The cost is upstream, in what the single-slot assumption forces the *classifier* to do: mint
a leaf per operation-combination (the `@splitQuery` x `@lookupKey` 2x2 in
`FieldBuilder.classifyObjectReturnChildField` is the cross-product in source form), copy the four
payload components (`filters`, `orderBy`, `pagination`, `lookupMapping`) onto every table-bound leaf,
and then discard the ones the chosen arm cannot carry. Under the relation, a coordinate carries a
set of operation member rows, each minted by its own trigger fact, each carrying its own payload
once; leaf identity stops encoding operations and shrinks to source, delivery and target grain; and
"more facts trigger more operations" becomes more rows, never a new leaf.

## The fault, evidenced in the live tree (census 2026-08-01)

Concrete places where one slot demonstrably cannot hold what a coordinate does. These are the
programme's falsifiable motivation; each names main-source code that exists today.

- **A filtered lookup silently drops three facts from the operation axis.** The three lookup leaves
  (`ChildField.LookupTableField`, `ChildField.BatchedLookupTableField`,
  `QueryField.QueryLookupTableField`) all carry `filters`, `orderBy` and `pagination` as record
  components, and all three `operation()` switches map them to `Operation.Lookup(lookupMapping)`,
  which has no slot for the other three. Meanwhile `ConditionCommands` reads `filters` off the field
  capability directly and mints a WHERE row for the same coordinate, and a producer backstop
  (`ConditionCommands.requireNoGeneratedFilterOnLookup`) exists precisely to guard the conflict. A
  lookup coordinate performs `lookup` and `condition` and `orderBy`; one slot holds the first.
- **`Paginate` is three verbs in one record.** `Operation.Paginate(filters, orderBy, pagination)`
  fuses what R333's crosswalk separates into `condition`, `orderBy` and `paginate` members composed
  into the same launch.
- **A DML reentry coordinate already has two rows.** `LauncherCommands.dmlRowOf` mints a reentry
  companion launcher row per projected/discriminated mutation coordinate: the write stays with the
  mutation entry point, the re-select is a second operation on the same coordinate.
  `LauncherRelation`'s javadoc still claims the family is single-operation; the claim is already
  strained and retires with this programme.
- **Count and facets ride another coordinate's result shape.** `ResultShape.Connection` carries the
  totalCount helper, carrier and facet plan on the *paginating* coordinate's launcher row because
  the synthesised connection fields are not classified coordinates (R562 owns that model question).
  Two `Operation` arms (`Count`, `Facet`) are unpopulated for exactly this reason.
- **The polymorphic filter surface does not fit one arm.** Multi-table polymorphic roots carry
  filters per participant (`participantFilters()`), and both structural `Operation` consumers carry
  an explicit fallback around the arm for exactly this case. The one-arm assumption fails in main
  source today, twice, with a comment saying so.

## What is (the census, dated 2026-08-01; re-measure at pickup)

- `Operation`: 17 arms, 144 LOC, labelled COMMAND in the hierarchy-kind registry. Single-valued via
  `OutputField.operation()`, computed by three leaf-identity switches (`QueryField` 12 arms,
  `MutationField` 15, `ChildField` 24). Structural readers: 2 (`TenantBindingIndex`,
  `TenantDslEmitter`); display readers: 2 (LSP hover, MCP schema view); prose: 1 (validator
  diagnostic). The plan package reads `.operation()` zero times.
- The leaf zoo: `OutputField` 51 leaves (`QueryField` 12, `MutationField` 15, `ChildField` 24),
  `InputField` 4, `GraphitronField` 56 total. Operation-grain distinctions that dissolve here: the
  Fetch-vs-Lookup pairs (three of them), the pivot pair's operation half, `DmlTableField`'s four-verb
  seal, the payload arms' verb half (`MutationUpdatePayloadField` vs `MutationDeletePayloadField`
  and their Bulk twins' verb component), the routine leaves' operation half. Source/delivery/target
  distinctions that survive: root vs child placement, inline vs DataLoader delivery
  (`BatchKeyField`), sourceShape Table vs Record (already a component, the R432 precedent), target
  shapes (Table/Record/Column/Field/Interface/Union), the service call-carrier split, transport.
- Dispatch surface (the counting rules are `CommandSeamRatchetTest`'s): `generators/` 71 instanceof
  + 76 case; `plan/` 156 (of which `LauncherCommands` 59, `FetcherEdgeCommands` 54,
  `ProjectionCommands` 34, `TypeUnitCommands` 31, `ConditionCommands` **4**); validator 114; lint 0;
  LSP 0. `ConditionCommands`' 4 is the shape this programme drives toward: its membership is one
  capability read (`SqlGeneratingField` with non-empty filters), not a leaf enumeration.
- The classifier: `FieldBuilder` 7,879 LOC, ~49 qualified leaf minting sites plus ~24 import-short.
  The operation decision points: the `@splitQuery` x `@lookupKey` 2x2 at
  `classifyObjectReturnChildField`, the per-verb `classify<Verb>*Field` router split, and
  `OutputField.readOperation`, which already derives Fetch-vs-Paginate from the wrapper rather than
  minting a leaf for it: the existing proof that the additive dissolution works.
- Payload facts ride one resolver (`resolveTableFieldComponents` returning the four components) and
  are copied onto every table-bound leaf. Participant filters are already finer-grain than the
  coordinate (`List<ParticipantFilters>`).
- The corpus: `@classified(operation: Operation!)` is a single non-list enum arg; `DimensionTuple`
  carries one `Class<? extends Operation>` token; the axis-pair census keys one row per coordinate;
  `OPERATION_ARMS` (domain = the 17 sealed arms, covered = corpus-observed single tokens) carries
  six exemptions (`EntityResolve`, `Count`, `Facet`, `UpdateMatching`, `DeleteMatching`, `Upsert`).
  The truth table holds 374 `ClassificationCase` constants across 35 enums (the last published
  bucketing, 2026-07-25: SLOT 170, REJECTION 178, INPUT 24, PURE 0).
- Prior art for the relation shape: `ConditionRelation` is the only landed multi-row-per-coordinate
  relation (keyed `(coordinate, resolvedTable)`, absent row composes neutrally, capability-grain
  membership); `ConnectionSynthesisRelation` is the precedent for a classify-walk-minted relation
  homed on `GraphitronSchema` (labelled RESOLVED_VIEW), with shared schema-grain rows not replicated
  per coordinate and typed accessors instead of restated facts; `EmitPlan` aggregates six back-half
  relations with conditions produced first.

## The relation

**One model-side relation, homed on `GraphitronSchema` beside `connectionSynthesis()`,** populated
during the classify walk, following `ConnectionSynthesisRelation`'s homing precedent and
`ConditionRelation`'s multi-row key discipline. It is a front-half fact relation: the back-half
command relations in `plan/` are its realizing views (R333: the seam worklist is the back-half view
of the operation relation) and keep their own keys and payloads; what they stop doing is deriving
*membership* from leaf identity.

- **Row grain.** One row per `(coordinate, member)`. A member is one arm of a sealed
  `OperationMember` family whose vocabulary is fixed at slice 1 by writing the missing crosswalk
  (see the design debt below): the shipped 17-arm summary vocabulary and R333's seven-kind seam
  vocabulary (`select`, `join`, `paginate`, `condition`, `orderBy`, `serviceCall`, DML) have no
  written mapping today, and this programme owes it as code, since slice 1's derivation *is* the
  mapping.
- **Payload homed once.** Each member arm carries the payload its trigger fact produces: the
  condition member carries the filter surface (participant-expanded where the coordinate is
  polymorphic), the orderBy member the `OrderBySpec`, the paginate member the window, the lookup
  member the `LookupMapping`, the serviceCall member the call carrier, the write member the verb
  and its input payload. The leaf records shed those components as their families migrate
  (`TableTargetField` slims; the lookup leaves stop carrying facts their arm drops).
- **0..N, honestly.** A record-read or nesting coordinate has an empty member set; the DataFetcher's
  existence is the fact (R333). Empty is a value; no `NoOperation` arm.
- **Anchor address.** R333's address column (`address in {self, enclosing anchor}`) joins the
  relation when the first member that needs it migrates (the split key-projection member); the
  grandchild-through-inline-ancestor threading residue R333 names stays open until that slice and
  is decided there, not here.
- **Membership is trigger-fact presence.** A member row exists because its independent walkable
  trigger fact exists (a table-bound return mints select, pagination args mint paginate, `@condition`
  or filter inputs mint condition, `@orderBy` mints orderBy, `@service` mints serviceCall, the
  reference fact mints join, `@mutation` mints the write verb). The union is the set. Membership
  censuses become fact-grain, the `ConditionCommands` shape, and the per-leaf declared sets the
  R549 window landed (`MINTING_KINDS`, `CONTRIBUTION_MINTING_LEAVES`) are the transitional form
  this replaces family by family.

## Production path and coexistence

The same discipline as R549's cutovers: additive, equality-pinned, then destructive.

1. **The relation lands first as a projection over the current leaves** (slice 1): one total switch
   from the 51-leaf model to member sets, in the `PlanCompileGraph` precedent (a projection built
   beside the thing it replaces, population-pinned, zero emit change). The switch is compile-total
   with no default, so a new leaf fails compilation until the projection covers it.
2. **The keystone flips minting to trigger facts** (slice 3): the classifier's component resolver
   produces member rows directly; a derived-equals-minted equality pin holds over the corpus and the
   pipeline fixtures for the window; the leaf-to-member projection then retires. From this point the
   relation is the source and the leaves' operation content is derived or gone.
3. **Consumers re-source, then leaves dissolve, family by family.** Each dissolution slice retires
   leaf names through `RetiredVocabularyGuardTest`'s registry (the R432 entries are the precedent),
   updates the coverage obligations whose domains are leaf sets, and keeps `sourceShape` arms the
   parent-backing derivation independently agrees with.

Emit-neutrality is the default for every slice: this is model-side work, the landed SQL baselines
(`ConditionSqlBaselineTest`, `RootLauncherSqlBaselineTest`) and the sakila expectations stay
byte-identical, and a slice that cannot hold that names the exception in its design record before
any code (none is currently foreseen). Dissolving a leaf constraint does not silently enable
unimplemented emit: where a leaf's absence was doing rejection work (the filtered-lookup guard),
the rejection moves to the fact grain (a condition member co-present with a lookup member on one
coordinate) and stays a build error until an emit slice deliberately implements the combination,
per validator-mirrors-classifier.

## Invariants

1. **The equality pin during the keystone window**: derived-from-leaves equals minted-from-triggers,
   per coordinate, over the corpus and the pipeline fixtures; deleted with the projection when the
   window closes, like R549's migration dials.
2. **Population censuses per member family**, in the `ConditionMembershipTest` shape: the member
   rows for a family equal an independently derived covered set from the trigger facts.
3. **The leaf ratchet**: a per-hierarchy leaf count (`GeneratorCoverageTest.sealedLeaves` is the
   counting rule) pinned in `CommandSeamRatchetTest`'s style, moving only downward, one dissolution
   slice at a time, with a history line per move. Installed at 12/15/24/4 (query/mutation/child/input).
4. **No silent payload drops**: the member arms make the lookup-drop class unrepresentable; the
   compact constructors reject a member whose payload is empty when its trigger fact fired (the
   non-vacuity direction) so a trigger that stops producing is loud.
5. **The existing dispatch ratchets keep holding**: `PLAN_LEAF_REFERENCES` and the two generator
   pins fall as membership re-sources, pinned in the same commits; the borrowed-model dial
   (`BORROWED_MODEL_REFS`) is edited deliberately or not at all (member payloads reaching
   `command`/`render` cross that boundary only by the dial's own rules).
6. **The hierarchy-kind label**: the relation and the member seal get registry entries at slice 1
   (the registry's own note reserves a "derived view at emit grain" kind for when it is needed;
   whether `OperationMember` is WALKED_FACT or joins a new kind is decided at the label, with the
   registrar's reasoning recorded).

## Slices

Numbered as real seams; each ships to trunk with the build green. The intent is to deliver all of
them, serially; sequence by what is cheapest next given what has landed. **No slice that is purely a
migration payment**: each ships a simplification, a deletion, or a capability, named in its row.

| # | slice | why here | cost |
|---|---|---|---|
| 1 | **The relation skeleton.** `OperationRelation` + sealed `OperationMember` on `GraphitronSchema`, derived from the current leaves by one compile-total switch; the vocabulary crosswalk written as that code; population pin; hierarchy-kind labels; the two display consumers (LSP hover, MCP view) re-read from rows as the first honest readers | cheapest complete vertical; the vocabulary exists in trunk before anything expensive rests on it, and the crosswalk debt (17 summary arms vs 7 seam kinds, no written mapping) is paid where it is enforceable | low |
| 2 | **Instruments.** The leaf ratchet installed at current counts; the axis-pair census gains a member-grain extraction beside the coordinate-grain one (both print; the coordinate-grain rows stay valid as the empty-or-one projection); baseline measurement rows recorded in this file | the ratchet must precede the migration it measures, and the census must exist before slice 3 commits to the member vocabulary it would falsify | low |
| 3 | **The keystone: trigger-fact minting.** The classifier's component resolver (`resolveTableFieldComponents` and the per-verb router) produces member rows; equality pin over the window; the leaf-to-member projection retires; payloads single-home (leaf records shed `filters`/`orderBy`/`pagination`/`lookupMapping` components as their readers move); `OutputField.operation()` narrows to a derived summary over the member set for the two structural consumers until slice 4 retires it | designing this validates or breaks the model: production from triggers is the whole thesis, and it lands against the frozen emit with no SQL change | medium |
| 4 | **The two structural consumers re-source.** `TenantBindingIndex.directSlots` and `TenantDslEmitter.slotReads` read member rows; their `participantFilters()` fallbacks dissolve (participant filters are condition-member payload); `OutputField.operation()` and the three leaf-identity `operation()` switches retire; `Operation`'s summary arms narrow to whatever the corpus still names (decided at slice 7) | the smallest honest beachhead: the two places the one-arm assumption already visibly fails, and the retirement of the summary column's last structural readers | low |
| 5 | **Back-half membership re-sources.** The plan producers derive membership from member presence instead of leaf identity: `LauncherCommands.MINTING_KINDS` becomes "has a launching select member" plus source facts; `ProjectionCommands.contributionOf`'s operation-grain arms (the Multiset-vs-LookupMultiset fork, the Nest/Pivot walk) read members; `ConditionCommands` re-keys its membership onto the condition member (it is already fact-grain; this aligns the fact's home). `PLAN_LEAF_REFERENCES` falls and is re-pinned | this is where the R549 relations become views over the front half, which is the programme's architectural claim; doing it before dissolution means the leaves die with no readers left | medium |
| 6a | **Dissolution: the lookup triplet.** `LookupTableField`, `BatchedLookupTableField`, `QueryLookupTableField` fold into their Fetch siblings plus a lookup member; the dropped-facts class becomes unrepresentable; the filtered-lookup rejection re-grains onto member co-presence (capability payload: the rejection stops being a leaf accident and states its real ground) | first dissolution because the census evidence is sharpest here and the surviving siblings already carry every component | medium |
| 6b | **Dissolution: the DML verb split.** `DmlTableField`'s four verb leaves and the payload arms' verb half fold onto write members carrying the verb; the `MutationDml*RecordField` pair's `DmlKind` component is the precedent (verb as data landed once already); bulk-ness stays the target wrapper | the mutation router's per-verb classify methods collapse to one path plus a verb fact; the largest single leaf-count drop (15 mutation leaves shrink) | medium |
| 6c | **Dissolution: the pivot pair's operation half and the routine leaves' operation half** | closes the read-family dissolution; what survives of the leaves is source/delivery/target grain only | medium |
| 7 | **The corpus voice.** `@classified(operation:)` becomes the member-set assertion (`operations: [Op!]!` or the sibling-directive shape, decided at the slice against the R543-landed `@commits` precedent); `DimensionTuple` re-grains; the axis-pair census re-keys; `OPERATION_ARMS` re-sources (domain = the member vocabulary, covered = declared-and-agreeing member rows); the launcher `@commits` vocabulary aligns. R543's Spec parked exactly this as "one coherent edit owned by whichever item makes operations a relation": this slice is that edit | the payoff that justifies the relation, and it wants minted rows to assert against, so it runs after the keystone | medium |
| 8 | **Obligation re-typing where the leaf zoo dissolution bites.** The coverage obligations whose domains are leaf `Class<?>` sets re-source onto the surviving leaf sets plus the member vocabulary; the `@ProjectionFor`/`FieldClassification` projection surfaces update per dissolved leaf (started in the 6x slices, closed here) | parked by R543 for this programme by name; closing it here keeps the discovery guard honest at the end state | low |

Ordering rules beyond the numbering: 1 before everything (skeleton in trunk first); 2 before 3 (the
instrument precedes the migration it measures); 3 before 4, 5, 6x, 7 (nothing re-sources onto rows
that are still a projection); 6x slices are independent of each other and of 7; 8 last. Slices 4 and
5 may land in either order.

## Design debts owed before code, and where they are paid

- **The vocabulary crosswalk (slice 1).** The shipped 17-arm summary vocabulary vs R333's seven seam
  kinds, plus the arms in neither (`Lookup`, `Pivot`, `NodeResolve`, `Count`, `Facet`,
  `RoutineWrite`, the `*Matching` verbs). Slice 1's total switch is the mapping, reviewed as code.
  Expected shape: `Fetch` = select(+condition+orderBy members); `Paginate` = those plus paginate;
  `Lookup` = lookup plus the members its slot dropped; `Pivot` = pivot(+join); the DML verbs = write
  members; `RoutineWrite` = write member with the routine chain staying a source-side `tableExpr`
  fact; `NodeResolve`/`EntityResolve` = protocol members; `Count`/`Facet` = members whose coordinate
  home is R562's question (see relationships). The slice's design record binds the actual mapping.
- **The member key for multi-instance kinds (slice 1).** The condition member is per
  `(coordinate, resolvedTable)` in the landed back-half relation; whether the front-half relation
  carries one condition member per coordinate (with the participant expansion as payload) or one per
  table mirrors that key. Default: mirror `ConditionRelation`'s key so the join is 1:1; the slice
  record states the decision. Composite select stays one member with arity as payload (R508 pinned
  the one-carrier form; this programme does not reopen it).
- **The reentry member (slice 3).** The DML companion row is the live proof of two operations on one
  coordinate; the write member and the reentry re-select member land as distinct members, and
  `LauncherRelation`'s single-operation javadoc sentence retires with a successor stating the real
  key.
- **`Operation`'s end state (slice 7).** Whether the 17-arm seal survives as the corpus's summary
  vocabulary, narrows, or retires outright is decided when the corpus re-grains; until then it stays
  the derived summary column slice 3 makes it.

## Relationship to existing items

| item | relationship |
|---|---|
| R333 (Ready) | governs. This programme is the "emit re-platforming's front-half successor" its scope section anticipates; its normalized schema, trigger rule, crosswalk and corpus resolution are consumed verbatim. Two stale spots to fold through at slice 1, not re-derive: seam-worklist row 5 predates R552's landing (its own topology table is current), and the worklist vocabulary has no written mapping to the shipped seal (the crosswalk debt above). This programme's landing plausibly discharges R333's stay-Ready condition; that call belongs to R333's own gate |
| R222 (Spec) | the umbrella this programme partially absorbs, the R546 precedent. Its field-side content (Stage 3's dimensional slots for output fields, Stage 5's cross-product permit deletion) is superseded by this programme where operations are concerned; its input-side classification, failure-at-the-wrapper (Stage 4), diagnostics unification and directive narrowing (Stage 7) are genuinely separate work and stay its own. At this item's Spec gate, R222 gets a governance note re-scoping it to the surviving halves; whether it then re-specs or splits is its own business |
| R543 (Done) | the launcher corpus voice whose Spec parked the set-capable operations vocabulary, the census re-grain and the `OPERATION_ARMS` re-source "as one coherent edit, owned by whichever item makes operations a relation", and the obligation re-typing for "whichever item dissolves the leaf zoo". Slices 7 and 8 are those edits, honored as specified |
| R562 (Backlog) | owns the synthesised-connection-fields-as-coordinates question. The `Count`/`Facet` members' coordinate home depends on its answer: if the synthesised fields become coordinates, the members land there and the exemptions retire; if not, they stay members on the paginating coordinate. Slice 7 consumes whatever R562 has decided by then, or keeps the two exemptions with re-anchored reasons; this programme does not decide R562's question for it |
| R558 (root-family-validator-mirror-gaps) | keeps the per-arm verdicts for the condition-matched writes (`UpdateMatching`, `DeleteMatching`); they enter the member vocabulary as modeled-but-unpopulated exactly as they sit in the seal today |
| R541 / R552 (Done) | the landed back-half proofs. Their relations become views over the front half at slice 5; nothing in their SQL or their keys moves |
| R508 (Done) | pinned the one-arity-N-carrier form for composite columns; the composite select member keeps arity as payload and the per-column-row normalization stays unopened |
| R7 (Backlog) | further subsumed: `TypeFetcherGenerator`'s remaining leaf dispatch (93 references) shrinks as membership re-sources; its decomposition item stays independent |
| R544 (Backlog) | the error-channel fourth kind, independent; `ErrorsField` is a no-operation leaf and unaffected |
| R323 / R471 (Backlog) | source-grain items (multi-parent batch keys, OnlyChild direct SQL); unaffected by the operation axis, named here so their leaf citations get re-anchored when 6x slices rename around them |

## Progress measurement

Baseline, census-measured 2026-08-01 (counting rules: `GeneratorCoverageTest.sealedLeaves` for leaf
counts, `CommandSeamRatchetTest`'s patterns for dispatch, plain `wc -l` for LOC):

- Leaves: `OutputField` 51 (12 query / 15 mutation / 24 child), `InputField` 4, `Operation` 17 arms.
- Dispatch: `generators/` 71 + 76, `plan/` 156, validator 114.
- `FieldBuilder` 7,879 LOC, ~49 qualified minting sites.
- Truth table: 374 constants / 35 enums. Corpus: 51 examples.
- `Operation` structural consumers: 2.

Re-run after the keystone and after the last 6x slice. What matters is direction: leaf counts and
the minting-site count fall with each dissolution; `FieldBuilder` sheds its per-verb router weight;
the dispatch pins fall as membership re-sources. A slice that moves none of them is a slice worth
re-examining. The stall risk R549 named applies with the same mitigation: ratchets hold each
conversion once it lands, so an interruption leaves a smaller model rather than two half-models; the
widest window (between slice 3 and the last 6x slice, when leaves and members coexist with the
leaves read-only on the operation axis) is measured by the leaf ratchet staying flat, and the
programme's answer is the serial-delivery intent, stated plainly.

## Retired vocabulary

Declared per the item-file conventions in `roadmap/workflow.adoc`; each term names the slice that
retires it, and the sweep at each gate runs against what has actually shipped.

| term | slice | successor |
|---|---|---|
| `OutputField.operation()` as the single-valued accessor, and the three leaf-identity `operation()` switches | 4 | the coordinate's member rows on `GraphitronSchema`; the summary column, if the corpus keeps one, is a derived view named at slice 7 |
| `LookupTableField`, `BatchedLookupTableField`, `QueryLookupTableField` | 6a | the Fetch-sibling leaves plus the lookup member row |
| `MutationField.DmlTableField`'s four verb leaves; the payload arms' verb halves | 6b | write members carrying the verb; source/target grain leaves |
| `LauncherRelation`'s "this family is single-operation" javadoc claim | 3 | the reentry and write members; the real key stated |
| `requireNoGeneratedFilterOnLookup` (the producer backstop) | 6a | the member co-presence rejection at fact grain |
| `DimensionTuple`'s single `Class<? extends Operation>` component | 7 | the member-set assertion |

## Non-goals

- **No generic fact bus and no query-engine runtime** (R333's own resolutions; typed keyed relations
  in the type system, referential integrity as typed checks).
- **`source` and `target` stay as they are.** Only `operation` is normalization-forced; this
  programme does not touch the arrival or output facts beyond what payload single-homing requires.
- **Byte-identical output is not the acceptance**; the existing tiers are (compilation, execution,
  the closure oracles, the corpus). Emit-neutrality is the per-slice default all the same.
- **No emit capability changes.** Dissolving a leaf does not enable what its absence rejected;
  rejections re-grain onto facts and stay build errors until an emit item implements the
  combination. The filtered-lookup combination in particular stays rejected at 6a, with its real
  ground stated; implementing it is its own future item.
- **Not R222's other halves**: input-side classification, failure-at-the-wrapper, the diagnostics
  stream, directive narrowing, and the `ChildField`-to-`SourceField` namespace collapse all stay
  where they are.
- **Not R562's decision**: the synthesised-fields coordinate question keeps its owner.

## Acceptance

The programme is not "done"; individual slices are. What signals it worked: the classifier mints no
operation-encoding leaf (the surviving leaves are source, delivery and target grain, and the
reconstruction key `leaf = f(source, delivery, target)` holds with no operation term); every
operation payload lives on exactly one member row; membership predicates across the plan producers
are fact-grain reads in the `ConditionCommands` shape; the corpus asserts a coordinate's member set
on declared-equals-produced agreement with the obligations re-sourced; and the leaf ratchet, the
dispatch pins and the minting-site count have all moved monotonically down. What signals it failed:
the ratchets stall for two consecutive slices, a slice lands whose only product is migration, or the
equality pin's window (slice 3 to the last 6x) stays open past the programme's serial cadence.
