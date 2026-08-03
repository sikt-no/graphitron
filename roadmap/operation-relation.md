---
id: R563
title: "Operations as a relation: the leaf cross-product dissolves additively"
status: In Progress
bucket: architecture
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-01
last-updated: 2026-08-02
---

# Operations as a relation: the leaf cross-product dissolves additively

This item is a **programme** in the sense R549 (Done 2026-08-01; see `roadmap/changelog.md`) used the
word: it frames a direction, states the invariants that make it falsifiable, and lists slices that
each ship to trunk on their own. Consult-checked (principles-architect, two rounds, 2026-08-01); the
findings are folded into this body. R549 built the back half of the model: the emit as coordinate-keyed
command relations (launcher, condition, projection, fetcher-edge) the shell folds over, with
membership declared as producer data. This programme executes the front half R333 resolved and left
waiting: `coordinate -> operation` is a 0..N relation, the one genuinely multi-valued relation in
the model, and storing it as a single-valued arm plus leaf identity is the 1NF fault that
multiplies the leaf cross-product in the first place (paraphrasing R333's normalized schema, whose
own words are "`operation` *had* to split out: it is multi-valued, so one slot was a genuine 1NF /
repeating-group fault"). The deliverable
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
- **A DML coordinate performs two operations, one of which the launcher relation materializes.**
  `LauncherCommands.dmlRowOf` mints exactly one launcher row per projected/discriminated mutation
  coordinate, the reentry re-select; the write itself is deliberately not a launcher row (it stays
  with the mutation entry point). The coordinate's operation set has two members and today's model
  can name only the materialized one. `LauncherRelation`'s javadoc still claims the family is
  single-operation; the claim is already strained and retires with this programme.
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
  `MutationField` 15, `ChildField` 24). Structural readers: two external consumers
  (`TenantBindingIndex`, roughly eight sites including the `fanOutArmOf` rejection ladder, and
  `TenantDslEmitter.slotReads`) plus two model-internal derived predicates
  (`OutputField.requiresReFetch()` and `emitsKeyedReQuery()`, single-homed switches over the arm
  with many downstream readers); prose: 1 (a validator diagnostic interpolates the arm). The plan
  package reads `.operation()` zero times. The LSP hover and MCP schema view read
  `TypeClassification.Root.operation()`, an unrelated field (the root type name), so there are no
  display readers of the model arm at all.
- The leaf zoo: `OutputField` 51 leaves (`QueryField` 12, `MutationField` 15, `ChildField` 24),
  `InputField` 4, `GraphitronField` 56 total. Operation-grain distinctions that dissolve here: the
  Fetch-vs-Lookup pairs (three of them), the pivot pair's operation half, `DmlTableField`'s four-verb
  seal, the payload arms' verb half (`MutationUpdatePayloadField` vs `MutationDeletePayloadField`
  and their Bulk twins' verb component), the routine leaves' operation half. Source/delivery/target
  distinctions that survive: root vs child placement, inline vs DataLoader delivery
  (`BatchKeyField`), sourceShape Table vs Record (already a component, the R432 precedent), target
  shapes (Table/Record/Column/Field/Interface/Union), the service call-carrier split, transport.
- Dispatch surface: `generators/` 71 instanceof + 76 case and `plan/` 156 under
  `CommandSeamRatchetTest`'s pinned counting rules (of which `FetcherEdgeCommands` 54,
  `ProjectionCommands` 34, `LauncherCommands` 33, `TypeUnitCommands` 30, `ConditionCommands`
  **4**, `EmitPlan` 1); validator 31, lint 0, LSP 0 hand-measured under the same pattern with no
  pinned rule.
  `ConditionCommands`' 4 is the shape this programme drives toward: its membership is one
  capability read (`SqlGeneratingField` with non-empty filters), not a leaf enumeration.
- The trigger substrate already exists: the `no.sikt.graphitron.facts` package (`GatheredFacts`,
  `FactVisitor`, `FactSubjectKind`) with its import-direction rule stating "facts sit below
  commands; the corpus will read facts without a plan", and `PaginationFacts` is already the
  paginate member's trigger fact, population-pinned, with a compile-total slot-fill switch.
  `CommandSeamRatchetTest`'s tertiary-pin javadoc names the fact-visitor engine as the mechanism
  that drives `PLAN_LEAF_REFERENCES` to zero.
- The leaf-set enforcer this programme edits most: the four-way dispatch partition
  (`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` over
  `TypeFetcherGenerator.IMPLEMENTED_LEAVES` / `STUBBED_VARIANTS` / `NOT_DISPATCHED_LEAVES` and the
  `CONTRIBUTION_MINTING_LEAVES`-derived bucket, with a hand-pinned dual-arm list naming the batched
  leaves), gated at build time by `ValidateMojo` on stubbed variants. It is the named enforcer of
  validator-mirrors-classifier at leaf grain.
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

## The two relations

The consult's sharpest correction: trigger *presence* is a walked fact, but the member payloads
(`OrderBySpec`, filter surfaces, `LookupMapping`) are labelled COMMAND in the hierarchy-kind
registry, and one relation asked to be simultaneously the walked fact base and the resolved view
over it can answer neither its label question nor its homing question. So the design names two
things, each with the label and home its kind already prescribes.

**The trigger relation: walked, in `facts/`.** Per-trigger populations as `GatheredFacts` slots
filled by per-trigger `FactVisitor`s, exactly the substrate the tree already built for this:
`PaginationFacts` is the paginate trigger today, population-pinned, with the compile-total slot-fill
switch; `condition`, `orderBy`, `lookup`, `service`, `write` and `reference` visitors join it. Each
slot is WALKED_FACT grain (or rides an existing walked fact: the reference fact already exists),
carries per-trigger population pins, and the corpus can read it without a plan, per the package's
own import rule. The condition slot's population is R333's union-then-suppress resolution (authored
plus live generated rows, with `@condition(override:)` suppressing the consumed generated subtree);
today that resolution is applied upstream in `FieldBuilder.projectFilters`, which expresses a
suppressed generated filter as absence, and the slot inherits the resolved population rather than
re-deriving the suppression downstream, exactly as the landed condition producer already does.

Between the walked slots and the member view sits a third layer the tree has already named three
times: per-member **resolvers** in `rewrite/` (`PaginationResolver`, `OrderByResolver`,
`LookupMappingResolver` are the shipped precedents), joining trigger presence with the catalog and
the reference facts into member payloads. The layer is forced, not optional: generated condition
payloads, lookup mappings and participant expansions need `resolvedTable`, which no SDL-grain
visitor can supply, and `facts/` stays jOOQ-free while `plan/` is emit-only, so `rewrite/` (where
`JooqCatalog` and both join keys live) is the resolvers' home. The resolvers take no hierarchy-kind
label; the registry's scope note already excludes resolver families as gathering scaffolding. Two
contract lines bind the layer. First, **a member resolver reads facts, never another member's
presence**: a resolver forking on a sibling member reconstitutes the cross-product inside the
layer. Second, **the dependency direction is an obligation, not an accident**: in the additive
window the resolvers may read leaf-derived resolutions (the `ConditionCommands` javadoc already
names this as "later work"), but each dissolution slice states which way the edge points when it
lands, so the terminal state has the resolvers upstream of leaf construction and never the members
as a permanent view over the leaf zoo (R333's named failure mode inverted: leaves never dissolved
because the members depend on them). This is also the mechanism `CommandSeamRatchetTest`'s tertiary pin names for
driving `PLAN_LEAF_REFERENCES` down, so the programme uses the tree's declared engine rather than
substituting a new one.

**The member view: resolved, on `GraphitronSchema` beside `connectionSynthesis()`.** The
per-coordinate join of the trigger slots: rows keyed `(coordinate, member)`, a sealed
`OperationMember` family whose arms carry typed references to the trigger facts they realize,
labelled RESOLVED_VIEW (now citing `ConnectionSynthesisRelation`'s precedent for the right kind of
thing: a classify-walk product coalescing walked facts, with typed accessors instead of restated
facts). The back-half command relations in `plan/` are its realizing views (R333: the seam worklist
is the back-half view of the operation relation) and keep their own keys and payloads; what they
stop doing is deriving *membership* from leaf identity.

- **Row grain.** One row per `(coordinate, member)`. The member vocabulary is fixed at slice 1 by
  writing the missing crosswalk as code (see the design debt below): the shipped 17-arm summary
  vocabulary and R333's seven-kind seam vocabulary (`select`, `join`, `paginate`, `condition`,
  `orderBy`, `serviceCall`, DML) have no written mapping today. The crosswalk is **many-to-many**:
  one summary arm expands to a member *set* (`Fetch` to select plus condition plus orderBy), so the
  slice-1 derivation's return type is a set of members, bound here; the mapping's content is bound
  by the slice's total switch.
- **Select is one member; launch-hood is not a member.** R333's crosswalk lands `select` on two
  seams, but its own layer sort puts them in different layers: the `$project` contribution is an
  operation seam, while the Query unit is the **dispatch target** the operation set renders into,
  and the live `LaunchSource` arms hold `UnitRef` references *to* projection units (a launch
  functionally depends on 0..N projections; set peers do not do that). A launch member would also
  have to co-occur with serviceCall and the reentry, carrying host strategy for three member kinds.
  So the vocabulary keeps one `select` member (the projection contribution, triggered by the
  table-bound target), and anchor-hood is derived from the **delivery fact** (the design debt
  below): launch *strategy* stays in the back-half launcher relation exactly where R549 put it, and
  the intro's one-member-one-seam rule holds without exception.
- **Payload homed once.** Each member arm carries (or references) the payload its trigger fact
  produces: the condition member the filter surface (participant-expanded where the coordinate is
  polymorphic, preserving the sealed generated-versus-authored `WhereFilter` split), the orderBy
  member the `OrderBySpec`, the paginate member the window, the lookup member the `LookupMapping`,
  the serviceCall member the call carrier. The **write member is itself a sealed family**
  (`Insert(TableInputArg)`, `Update(inputArg, updateRows)`, `Delete(inputArg, deleteRows)`,
  `Upsert(TableInputArg)`) so the per-verb payloads and the per-verb compact-constructor invariants
  (DELETE rejects projected return arms; the dialect requirements differ per verb) keep structural
  homes; a verb-as-component row would re-introduce single-table inheritance, and the live
  `MutationField.dmlOperation` throw on the wrong `DmlKind` is the proof that shape is too weak,
  not a precedent for it. The leaf records shed their payload components as their families migrate
  (`TableTargetField` slims; the lookup leaves stop carrying facts their arm drops), with the
  borrow dial mostly untouched: `OrderBySpec` and `LookupMapping` already sit on
  `BORROWED_MODEL_REFS`, so `command`/`render` keep borrowing payload refs rather than importing a
  member seal.
- **0..N, honestly.** A record-read or nesting coordinate has an empty member set; the DataFetcher's
  existence is the fact (R333). Empty is a value; no `NoOperation` arm.
- **Anchor address.** R333's address column (`address in {self, enclosing anchor}`), derived as a
  view over the delivery fact, joins the view
  when the first member that needs it migrates (the split key-projection member); the
  grandchild-through-inline-ancestor threading residue R333 names stays open until that slice and
  is decided there, not here.
- **Membership is trigger-fact presence.** A member row exists because its trigger slot is
  populated (a table-bound return mints select, pagination args mint paginate, `@condition` or
  filter inputs mint condition, `@orderBy` mints orderBy, `@service` mints serviceCall, the
  reference fact mints join, `@mutation` mints the write verb). The union is the set. Membership
  censuses become fact-grain, the `ConditionCommands` shape, and the per-leaf declared sets the
  R549 window landed (`MINTING_KINDS`, `CONTRIBUTION_MINTING_LEAVES`) are the transitional form
  this replaces family by family.
- **`SqlGeneratingField` survives as a derived view over the members.** The capability is the
  principles doc's named exemplar and the single home of the `conditionsReadRequestContext` fold
  every converged condition call site reads; slice 3 preserves its accessor contract as a view over
  the condition, orderBy and paginate members rather than gutting a live exemplar as a side effect.
  If a later slice retires it after its readers migrate, the principles doc repoints in the same
  commit.

## The co-occurrence grammar

The 17-arm seal made a novel operation combination unrepresentable (one arm per coordinate); the
open member set needs that legality structure stated, and the review pass asked for it bound here
rather than deferred to the implementer. The grammar is layered because the combinations carry
three different kinds of structure, each with a home the tree already uses for that kind of fact:

1. **Constitutive dependencies are typed edges.** A member whose meaning refines another carries a
   typed reference to it (the paginate member windows the select it pages; the lookup member keys
   the select it refines), the same shape as the `LaunchSource` arms holding a `UnitRef` to their
   projection unit. The illegal combination is unrepresentable at construction, never rejected
   downstream; this is the sealed-hierarchies-over-enums discipline covering the strongest rules
   for free.
2. **Multiplicity is the member key.** Per-kind cardinality (one write, one paginate; condition
   rows per `resolvedTable`; join 0..N because input-side references mint joins too) is stated
   once, in the `(coordinate, member)` key's per-kind discriminant (the member-key design debt),
   never restated as rules beside the key.
3. **The fence during the additive window is a derived image.** Slice 1's compile-total switch
   carries a declared shape per leaf arm: the required member kinds plus the payload-gated optional
   ones (a Fetch arm yields `{select}` required with `condition`, `orderBy`, `join` optional). The
   admitted combination set is the *image* of that switch, mechanically enumerable, so the fence
   cannot drift from the projection that defines it; a hand-maintained allow-list (which drifts
   silently) is unconstructible by design. A combination outside the image is rejected. The
   per-arm shape declarations are the grammar's statement, reviewed as code with the crosswalk.
4. **At dissolution the image graduates to a declared, ratcheted relation.** Each 6x slice seeds
   the dissolving family's admitted combinations from the final leaf-derived image into the
   dispatch partition's member-grain successor; thereafter a newly legal combination is a
   deliberate, visible row edit gated by `ValidateMojo`. This is the reject-by-default rule of
   *Production path and coexistence* given its mechanism, and the seeding guarantees the declared
   set starts equal to what the emit actually implements rather than being reconstructed from
   memory.
5. **Structural implications are theorems, not fences.** Statements like "every paginate co-occurs
   with a select" and "write never co-occurs with lookup" are asserted as tests over the admitted
   set: layers 1 and 3 gate, these document, and an edit that breaks structure nobody intended to
   break fails loudly.

One shape is refused by name so review does not re-propose it: a sealed enumeration of combination
profiles (`FetchProfile`, `PaginatedLookupProfile`, ...) is the leaf zoo reborn at combination
grain and dies by the same argument as the 17-arm seal, since an independent new trigger would
multiply profiles instead of adding a row. And one generosity is deliberate: the image over
independent optionals is a per-arm powerset, admitting combinations no fixture exercises yet. That
is the additive thesis working as intended, and slice 2's member-grain census makes the
admissible-versus-observed gap visible data rather than a surprise.

## Production path and coexistence

The same discipline as R549's cutovers: additive, equality-pinned, then destructive.

1. **The relation lands first as a projection over the current leaves** (slice 1): one total switch
   from the 51-leaf model to member sets, in the `PlanCompileGraph` precedent (a projection built
   beside the thing it replaces, population-pinned, zero emit change). The switch is compile-total
   with no default, so a new leaf fails compilation until the projection covers it.
2. **The keystone lands the trigger slots and re-sources the view onto their join** (slice 3):
   per-trigger `FactVisitor`s populate `GatheredFacts` slots (`PaginationFacts` is the live
   precedent), the member view derives from the joined slots, a derived-equals-minted equality pin
   holds over the corpus and the pipeline fixtures for the window, and the leaf-to-member
   projection then retires. From this point the trigger relation is the source and the leaves'
   operation content is derived or gone.
3. **Consumers re-source, then leaves dissolve, family by family.** Each dissolution slice retires
   leaf names through `RetiredVocabularyGuardTest`'s registry (the R432 entries are the precedent),
   updates the coverage obligations whose domains are leaf sets, and keeps `sourceShape` arms the
   parent-backing derivation independently agrees with. Each dissolution slice also re-sources the
   projection seam in the same commit sequence, and the obligation is plural, phrased over the seam
   rather than one method: `CatalogBuilder.projectFieldClassification` *and its siblings*
   (`TypeClassification`, `TypeBackingShape`, `CompletionData`, `CatalogFacts`) move their
   compile-checked switches onto the surviving leaves plus member reads as each family dissolves.
   R333 calls this the single load-bearing requirement of the three-consumer model and names the
   failure of deferring it (leaves revived as a shim to feed the editor and the agent); slice 8
   closes whatever residue remains, it does not carry the whole obligation.

Emit-neutrality is the default for every slice: this is model-side work, the landed SQL baselines
(`ConditionSqlBaselineTest`, `RootLauncherSqlBaselineTest`) and the sakila expectations stay
byte-identical, and a slice that cannot hold that names the exception in its design record before
any code (none is currently foreseen). Dissolving a leaf constraint does not silently enable
unimplemented emit, and equally does not silently remove a shipped capability: the filtered-lookup
guard rejects only **generated** filter terms (authored `@condition` entries on a lookup coordinate
are supported today and mint ordinary condition rows), so its re-grain is a payload predicate over
the sealed `WhereFilter` split (a condition member carrying a generated term, co-present with a
lookup member), never blanket member co-presence. Rejections move to that fact grain and stay build
errors until an emit slice deliberately implements the combination, per
validator-mirrors-classifier; the four-way dispatch partition and its `ValidateMojo` gate are the
enforcer, edited at each dissolution slice (see the design debt on its re-grain). The same rule
generalizes into the member set's **co-occurrence default**, adopted from the Spec review: the
17-arm seal made a novel operation combination unrepresentable (one arm per coordinate), the open
member set does not, so a member combination no current leaf produces is **rejected by default**
and becomes legal only through a deliberate edit to the dispatch partition's member-grain
successor. Legal-by-default with the rejection list as the only fence would contradict the
no-silent-enablement rule above; the full layered statement is *The co-occurrence grammar* above,
and slice 1 owes only its mechanical encoding.

## Invariants

1. **The equality pin during the keystone window**: derived-from-leaves equals minted-from-triggers,
   per coordinate, over the corpus and the pipeline fixtures; deleted with the projection when the
   window closes, like R549's migration dials. The comparison runs over a canonical member
   projection derived mechanically from the record components (a `RecordComponent` walk with refs
   canonicalized to rendered names and a justified exclusion set for minted addresses, whose R1
   names are never value-equal across producers), never an inclusion list and never deep record
   equality: an inclusion list that omits a component lets the two loci disagree on it silently.
   The pin's domain states the walked-population boundary explicitly: the synthesised connection
   coordinates (the registry's `SYNTHESISED_TYPE_PERMITS`, no SDL, hence no walked rows) carry
   their members on the paginating coordinate exactly as the launcher's `ResultShape.Connection`
   does today, pending R562, stated up front rather than discovered as a mismatch.
2. **Population censuses per trigger slot**, in the `PaginationFacts` and `ConditionMembershipTest`
   shapes: each slot's population equals an independently derived covered set, and the member view's
   rows equal the joined slots.
3. **The leaf ratchet**: a per-hierarchy leaf count (`GeneratorCoverageTest.sealedLeaves` is the
   counting rule) pinned in `CommandSeamRatchetTest`'s style, moving only downward, one dissolution
   slice at a time, with a history line per move. Installed at 12/15/24/4 (query/mutation/child/input).
4. **No silent payload drops**: the member arms make the lookup-drop class unrepresentable. The
   non-vacuity direction (a member whose payload is empty while its trigger fact fired is rejected
   at construction) applies to the **payload-bearing arms only**: condition, orderBy, paginate,
   lookup, serviceCall and the write verbs. The select and join members legitimately carry nothing
   beyond their trigger reference (a table-bound return, the reference fact) and are exempt by
   arm, stated here so the invariant has an enforcer for exactly the arms that can fail it.
5. **The existing dispatch ratchets keep holding**: `PLAN_LEAF_REFERENCES` and the two generator
   pins fall as membership re-sources, pinned in the same commits; the borrowed-model dial
   (`BORROWED_MODEL_REFS`) is edited deliberately or not at all (member payloads reaching
   `command`/`render` cross that boundary only by the dial's own rules).
6. **The hierarchy-kind labels answer themselves under the two-relation split**: the trigger slots
   are WALKED_FACT (or ride existing walked facts), the member view is RESOLVED_VIEW on
   `ConnectionSynthesisRelation`'s precedent, and the write-member seal's label is recorded with
   the registrar's reasoning at slice 1. `Operation`'s own registry entry (COMMAND today, justified
   as minted at emit grain) contradicts the programme's thesis that the operation relation is a
   front-half fact; the entry is re-labelled or retired, with the registrar's reasoning, at the
   slice that makes the seal a derived summary, since nested seals inherit their root's kind and a
   wrong root label mislabels the whole payload set in one stroke.

## Slices

Numbered as real seams; each ships to trunk with the build green. The intent is to deliver all of
them, serially; sequence by what is cheapest next given what has landed. **No slice that is purely a
migration payment**: each ships a simplification, a deletion, or a capability, named in its row.

| # | slice | why here | cost |
|---|---|---|---|
| 1 | **The skeleton: member view + first honest readers.** The sealed `OperationMember` family and the member view on `GraphitronSchema`, derived from the current leaves by one compile-total switch returning member *sets* (the vocabulary crosswalk written as that code), whose arms carry the per-arm shape declarations (required plus payload-gated optional kinds) the co-occurrence grammar's admitted image derives from; population pin; hierarchy-kind labels; and the first honest readers re-sourced: `OutputField.requiresReFetch()` and `emitsKeyedReQuery()` become member-presence reads ("has a serviceCall or write member"; "has a reentry member"), retiring their `default ->` arms | cheapest complete vertical with real readers (the consult falsified the display-reader claim: LSP hover and MCP view read the root type name, not the model arm); the two derived predicates are the exact "compound predicate over one slot" smell the programme cures, and the crosswalk debt is paid where it is enforceable | low |
| 2 | **Instruments.** The leaf ratchet installed at current counts; the axis-pair census gains a member-grain extraction beside the coordinate-grain one (both print; the coordinate-grain rows stay valid as the empty-or-one projection); baseline measurement rows recorded in this file | the ratchet must precede the migration it measures, and the census must exist before slice 3 commits to the member vocabulary it would falsify | low |
| 3 | **The keystone: trigger slots and their join.** The trigger relation lands as `GatheredFacts` slots filled by per-trigger `FactVisitor`s (`PaginationFacts` is the precedent and the paginate slot already); the member view re-sources from the leaf-derived projection onto the joined slots under an equality pin; the projection then retires; payloads single-home (leaf records shed `filters`/`orderBy`/`pagination`/`lookupMapping` components as their readers move, with `SqlGeneratingField` preserved as a derived view per the design above); `OutputField.operation()` narrows to a derived summary over the member set until slice 4 retires it | designing this validates or breaks the model: production from triggers is the whole thesis, it uses the fact-visitor engine the ratchet's own javadoc names as the mechanism, and it lands against the frozen emit with no SQL change | medium |
| 4 | **The remaining `operation()` readers re-source and the accessor retires.** `TenantBindingIndex`'s roughly eight sites (the `fanOutArmOf` rejection ladder and `directSlots`) and `TenantDslEmitter.slotReads` read member rows; their `participantFilters()` fallbacks dissolve (participant filters are condition-member payload); the validator diagnostic re-words off members; `OutputField.operation()` and the three leaf-identity `operation()` switches retire; `Operation`'s summary arms narrow to whatever the corpus still names (decided at slice 7) | the places the one-arm assumption already visibly fails, and the retirement of the summary column's last readers; sized medium because the fan-out ladder and the reentry guard are behavioral dispatch, not display. Review attention concentrates here (Spec-review emphasis): the `fanOutArmOf` re-source is where the member vocabulary's sufficiency is actually tested, and a vocabulary gap surfaces at this slice before any other | medium |
| 5 | **Back-half membership re-sources.** The plan producers derive membership from member presence instead of leaf identity: `LauncherCommands.MINTING_KINDS` becomes anchor-hood (a view over the delivery fact) joined with the members the launch hosts; `ProjectionCommands.contributionFor`'s operation-grain arms (the Multiset-vs-LookupMultiset fork, the Nest/Pivot walk) read members; `ConditionCommands` re-keys its membership onto the condition member (it is already fact-grain; this aligns the fact's home). `PLAN_LEAF_REFERENCES` falls and is re-pinned. One property must survive the re-source: the three total minting switches' compile error for an undecided new leaf (their javadoc names the totality as the enforcer) is not traded for a bare boolean predicate; the slice's design record names the replacement enforcer (the delivery fact's own total classification plus the member-population census) | this is where the R549 relations become views over the front half, which is the programme's architectural claim; doing it before dissolution means the leaves die with no readers left | medium |
| 6a | **Dissolution: the lookup triplet.** `LookupTableField`, `BatchedLookupTableField`, `QueryLookupTableField` fold into their Fetch siblings plus a lookup member; the dropped-facts class becomes unrepresentable; the generated-filter-on-lookup rejection re-grains onto its real predicate (a condition member carrying a generated `WhereFilter` term co-present with a lookup member; authored `@condition` on lookup stays supported exactly as today); the dispatch partition's dual-arm list and stub sets update with the fold | first dissolution because the census evidence is sharpest here and the surviving siblings already carry every component | medium |
| 6b | **Dissolution: the DML verb split.** `DmlTableField`'s four verb leaves and the payload arms' verb half fold onto the sealed write-member family, whose arms keep the per-verb payloads and compact-constructor invariants (DELETE rejects projected returns; per-verb dialect requirements); bulk-ness stays the target wrapper; the dispatch partition and `ValidateMojo` stub gate update with the fold | the mutation router's per-verb classify methods collapse to one path plus a sealed verb member; the largest single leaf-count drop (15 mutation leaves shrink) | medium |
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
  home is R562's question (see relationships); and a reentry member, minted where the leaf truth
  (`OutputField.emitsKeyedReQuery()`) holds, so slice 1's honest-reader re-source is expressible in
  the slice-1 vocabulary rather than waiting on slice 3's trigger production. The slice's design
  record binds the actual mapping.
- **The member key for multi-instance kinds (slice 1).** The condition member is per
  `(coordinate, resolvedTable)` in the landed back-half relation; whether the front-half relation
  carries one condition member per coordinate (with the participant expansion as payload) or one per
  table mirrors that key. Default: mirror `ConditionRelation`'s key so the join is 1:1; the slice
  record states the decision. Composite select stays one member with arity as payload (R508 pinned
  the one-carrier form; this programme does not reopen it).
- **The delivery fact (slice 3).** Named by this Spec because it is the coordinate fact R333's
  catalog never names (the leaf model carries it as `LoaderRegistration` plus leaf identity;
  `docs/architecture/explanation/dispatch-axes.adoc` treats loader container and dispatch as
  first-class axes): the walkable trigger whose views are anchor-hood and every member row's
  address column, so anchor-hood is stated once and never twice (a launch member beside an address
  column would be two unbound encodings of one fact). The keystone's design record binds its arm
  set and totality (authored `@splitQuery`, the batched arrival rule, inline default; whether the
  service and reentry launches are arms or derived). The trigger never reads `Source.OnlyChild`:
  the arrival honesty clause and its pin (`ArrivalUniformEmitPinTest`) stay undisturbed.
- **The reentry member and the launcher key (slice 3).** The write member and the reentry re-select
  (the coordinate's select member, realized as the reentry launcher row) are distinct members in the
  crosswalk vocabulary from slice 1; this slice binds their production from the delivery and write
  triggers, making the coordinate's two operations expressible from facts. That opens a
  named fork the slice record must close: either `LauncherRelation` re-keys to
  `(coordinate, member)` (contradicting "back-half relations keep their own keys", so it must be
  argued, not assumed), or it stays coordinate-keyed with the write member deliberately
  unmaterialized there (the write staying with the mutation entry point, as its javadoc already
  states). The single-operation javadoc sentence retires with a successor stating whichever key
  wins.
- **The dispatch partition's shape (first 6x slice).** `STUBBED_VARIANTS` keyed by leaf class is
  what makes `ValidateMojo`'s build gate possible; once unimplementedness is a member combination
  rather than a leaf identity, the partition either stays leaf-keyed with a member-grain stub set
  beside it or re-grains outright. Decided at the first dissolution slice's design record, with the
  gate's strength preserved either way.
- **`Operation`'s end state (slice 7).** Whether the 17-arm seal survives as the corpus's summary
  vocabulary, narrows, or retires outright is decided when the corpus re-grains; until then it stays
  the derived summary column slice 3 makes it.

## Relationship to existing items

| item | relationship |
|---|---|
| R333 (Ready) | governs. This programme is the "emit re-platforming's front-half successor" its scope section anticipates; its normalized schema, trigger rule, crosswalk and corpus resolution are consumed verbatim. Its staleness against the R549-landed state was folded through 2026-08-01 (the crosswalk's naming-regime column, the landed seam-worklist rows, the retired level-2 registry in its scope section), so the one debt left is the crosswalk vocabulary's written mapping to the shipped seal, paid as slice 1's compile-total switch. This programme's landing plausibly discharges R333's stay-Ready condition; that call belongs to R333's own gate |
| R222 (Spec) | the umbrella this programme partially absorbs. The precedent is R333's 2026-07-04 governance note on R222 itself (re-scoped in place, stage-tracking role kept), applied a second time, not R546's wholesale discard: discarding would orphan R222's absorption ledger (R97, R144, R98, R215, R213, R209, R221) which nothing else carries. Superseded here: Stage 3's operation-axis content and Stage 5's operation-encoding permit rows for output fields. Staying R222's own: input-side classification (including Stage 5's non-operation retirements: the `InputType` four-arm permit, `TableInputArg`/`PlainInputArg`, the `InputField` family, `HasInputRecordShape`, `RootField`; the `findReturnTablesForInput` back-scan R222's Stage 5 text still lists is already gone, deleted when input-table resolution went consumer-derived), failure-at-the-wrapper (Stage 4), Stage 6's namespace collapse, diagnostics unification, and Stage 7's directive narrowing. That governance note, with this enumeration, was applied 2026-08-01 |
| R543 (Done) | the launcher corpus voice whose Spec parked the set-capable operations vocabulary, the census re-grain and the `OPERATION_ARMS` re-source "as one coherent edit, owned by whichever item makes operations a relation", and the obligation re-typing for "whichever item dissolves the leaf zoo". Slices 7 and 8 are those edits, honored as specified |
| R562 (Backlog) | owns the synthesised-connection-fields-as-coordinates question. The `Count`/`Facet` members' coordinate home depends on its answer: if the synthesised fields become coordinates, the members land there and the exemptions retire; if not, they stay members on the paginating coordinate. Slice 7 consumes whatever R562 has decided by then, or keeps the two exemptions with re-anchored reasons; this programme does not decide R562's question for it |
| R558 (root-family-validator-mirror-gaps) | keeps the per-arm verdicts for the condition-matched writes (`UpdateMatching`, `DeleteMatching`); they enter the member vocabulary as modeled-but-unpopulated exactly as they sit in the seal today |
| R541 / R552 (Done) | the landed back-half proofs. Their relations become views over the front half at slice 5; nothing in their SQL or their keys moves |
| R508 (Done) | pinned the one-arity-N-carrier form for composite columns; the composite select member keeps arity as payload and the per-column-row normalization stays unopened |
| R7 (Backlog) | further subsumed: `TypeFetcherGenerator`'s remaining leaf dispatch (93 references) shrinks as membership re-sources; its decomposition item stays independent |
| R544 (Backlog) | the error-channel fourth kind, independent; `ErrorsField` is a no-operation leaf and unaffected. One pointer for later slices: R333 models `errorGuard` (channel plus interned handler set, on throwing operations) as an operation *sub-fact*, so its member-grain home rides the write and serviceCall member rows when those land; noted here, decided at those slices, without deciding R544's channel question |
| R323 / R471 (Backlog) | source-grain items (multi-parent batch keys, OnlyChild direct SQL); unaffected by the operation axis, named here so their leaf citations get re-anchored when 6x slices rename around them |

## Progress measurement

Baseline, census-measured 2026-08-01 (counting rules: `GeneratorCoverageTest.sealedLeaves` for leaf
counts, `CommandSeamRatchetTest`'s patterns for dispatch, plain `wc -l` for LOC):

- Leaves: `OutputField` 51 (12 query / 15 mutation / 24 child), `InputField` 4, `Operation` 17 arms.
- Dispatch: `generators/` 71 + 76, `plan/` 156, validator 31.
- `FieldBuilder` 7,879 LOC, ~49 qualified minting sites.
- Truth table: 374 constants / 35 enums. Corpus: 51 examples.
- `Operation` structural consumers: 2.

Slice-2 baseline (measured 2026-08-02, instruments installed):

- Leaf ratchet installed at 12/15/24/4 (query/mutation/child/input), `LeafRatchetTest`,
  counting rule `GeneratorCoverageTest.sealedLeaves`.
- Member-grain census (`ClassifiedDslTest.memberGrainCensusIsDerivable`, over the corpus): 195
  output coordinates, 26 empty member sets; kind histogram SELECT=128, JOIN=20, SERVICE_CALL=24,
  REENTRY=14, WRITE=12, ORDER_BY=10, LOOKUP=4, NODE_RESOLVE=3, PAGINATE=2, PIVOT=2, CONDITION=1.
  Admissible-versus-observed prints per leaf; the widest gaps are the batched table families
  (4 of 32 and 2 of 32 combinations observed) and the lookup family (1 of 16), the additive
  image working as intended. Zero-observation shapes: `MutationUpsertTableField` (the corpus's
  Upsert exemption) and `PivotSlotField` (empty-set leaf, no annotated corpus coordinate yet).
- Dispatch pins unchanged by slices 1-2: `generators/` 18 entry points, 71 instanceof + 76 case;
  `plan/` 156 (`CommandSeamRatchetTest`).
- `Operation` structural consumers: 2 (unchanged); `OutputField.requiresReFetch()` /
  `emitsKeyedReQuery()` re-sourced onto member reads at slice 1, their summary-arm switches
  retired.

Slice-3 baseline (measured 2026-08-02, keystone landed):

- Trigger slots: 6 (`pagination`, `condition`, `orderBy`, `lookup`, `service`, `write`), each
  population-pinned; `INPUT_OBJECT_FIELD` left the `NOT_GATHERED` waiver set.
- The view serves the minted relation over the flat classified index; the
  membership-agreement pin holds over the corpus plus the per-kind coverage fixtures with
  floors where the corpus was thin (CONDITION >= 7 observations against the corpus's 1,
  PAGINATE >= 4 against 2).
- Leaf ratchet unchanged at 12/15/24/4 and dispatch pins unchanged at 18 / 71 + 76 / 156: the
  coexistence window opens flat by design (model-side production landed; no generator or plan
  membership moved yet). `Operation` structural consumers unchanged at 2; its registry label
  is now RESOLVED_VIEW.

Slice-4 baseline (measured 2026-08-02, accessor retired):

- `Operation` structural consumers in main: 0 (was 2); `OutputField.operation()`, the three
  leaf-identity switches and the four arm-building statics deleted; the seal survives as the
  corpus's verb vocabulary (test-side fold plus SDL enum mirror).
- Leaf ratchet unchanged at 12/15/24/4; dispatch pins unchanged at 18 / 71 + 76 / 156 (the
  deleted tenant-emitter traversal was carrier-payload dispatch, outside the ratchet's
  patterns). Emit byte-identical through both of the slice's commits.

Slice-5 baseline (measured 2026-08-03, back-half membership re-sourced):

- Plan leaf references fell 156 to 140: the launcher's root null arms, census naming switch
  and schema-free leaf filter retired for the member-derived launch verdict; the condition
  producer's polymorphic arms dissolved into the condition member's participant arm; the
  projection producer's six table-target leaf cases merged into one intermediate-seal arm.
  The survivors are payload dispatch behind verdict guards plus the sealed nest, pivot and
  record arms, each with a named dissolution owner.
- Leaf ratchet unchanged at 12/15/24/4; generator pins unchanged at 18 / 71 + 76. Emit
  byte-identical through both of the slice's commits.

Slice-6a baseline (measured 2026-08-03, first dissolution landed):

- Leaf ratchet fell 12/15/24/4 to 11/15/22/4: the lookup triplet is the first fold, and the
  reconstruction key `leaf = f(source, delivery, target)` now holds for the three retired
  leaves (each differed from its fetch sibling on the operation axis alone).
- Plan leaf references fell 140 to 132 (the edge producer's three vacuous lookup arms, the
  launcher's four lookup payload arms, the projection's inline-correlation lookup read);
  generator pins fell 71 + 76 to 69 + 69 (the two dedicated fetcher arms, the deleted
  input-rows adapter's name switch, the scatter and parent-input gates' lookup twins); the
  launcher render-site pin fell 10 to 8 with the two arm merges. Entry points unchanged
  at 18.
- Emit byte-identical through both of the slice's commits.

Slice-6b baseline (measured 2026-08-03, the DML verb split dissolved):

- Leaf ratchet fell 11/15/22/4 to 11/8/22/4, the largest single drop of the programme: the
  four direct-return verb leaves folded into one `DmlTableField` record and the four
  payload-verb leaves onto the two record carriers, with
  the verb identity and the per-verb input surfaces carried as the sealed `Write.Dml`
  payload, so the reconstruction key `leaf = f(source, delivery, target)` now holds for all
  eight retired leaves (each differed from a sibling on the write verb alone).
- Plan leaf references fell 132 to 128 (the edge producer's four payload-verb arms folded
  onto the surviving write-carrying arms, all keeping the condition-glue derivation);
  generator case patterns fell 69 to 62 (the dispatch block's four verb arms merged into one
  write-arm fork and its four payload-carrier arms folded onto the two record-carrier arms);
  the generator instanceof pin is unchanged at 69, entry points at 18, launcher render
  sites at 8.
- Emit byte-identical through both of the slice's commits.

Re-run after the keystone and after the last 6x slice. What matters is direction: leaf counts and
the minting-site count fall with each dissolution; `FieldBuilder` sheds its per-verb router weight;
the dispatch pins fall as membership re-sources. A slice that moves none of them is a slice worth
re-examining. The stall risk R549 named applies with the same mitigation: ratchets hold each
conversion once it lands, so an interruption leaves a smaller model rather than two half-models; the
widest window (between slice 3 and the last 6x slice, when leaves and members coexist with the
leaves read-only on the operation axis) is measured by the leaf ratchet staying flat, and the
programme's answer is the serial-delivery intent, stated plainly. The window is a dated fact, not a
feeling (adopted from the Spec review): slice 3's landing commit appends a one-line
`roadmap/changelog.md` entry opening it, and the last 6x slice's landing commit appends the closing
line, so a stalled window is visible as an old date.

## Slice records

### Slice 1 (landed 2026-08-02): the skeleton

Landed: the sealed `OperationMember` family (thirteen top-level kinds plus the sealed `Write`
sub-family; the modeled-but-unpopulated arms `EntityResolve`, `Count`, `Facet`,
`Write.UpdateMatching`, `Write.DeleteMatching` construct but nothing mints them); the
compile-total 51-leaf crosswalk `OperationMembers.membersOf` with the per-leaf co-occurrence
declaration `OperationMembers.DECLARED_SHAPES` (the grammar's admitted image is that
declaration's per-leaf powerset over optionals, validated at construction); the member view
`GraphitronSchema.operationMembersOf` (a pure derivation at this slice, the `nestingReach()`
no-stored-index precedent, re-sourced onto trigger slots at the keystone with the read surface
unchanged); the population pin `OperationMemberProjectionTest` (an independent derivation from
the summary arm plus target/source facts, compared per coordinate over the corpus, with payload
mirroring and a declaration-totality check against the sealed leaves); and the honest-reader
re-source (`OutputField.requiresReFetch()`'s produced-record half reads
`OperationMember.producesRecord()`, a total per-arm property; `emitsKeyedReQuery()` reads
reentry-member presence; both `default ->` arms retired).

Decisions bound here:

- **The crosswalk vocabulary.** `Fetch` maps to `select` plus gated `condition` / `orderBy`
  (plus `join` off the reference path); `Paginate` to those plus `paginate` gated on the
  *carried* window payload, so a connection-shaped coordinate whose leaf carries no window (the
  batched polymorphic connection) mints no paginate member until the walked pagination facts
  take over production, the connection-ness staying readable on the target fact meanwhile;
  `Lookup` to `select` plus `lookup` plus the gated slots the summary arm dropped (the census
  fault made visible); `ServiceCall` to `serviceCall` plus the gated table-read surface on the
  child service-table leaf; `Pivot` to `pivot` plus `join`; the DML verbs to sealed write
  members carrying the verb payloads; `RoutineWrite` to `Write.RoutineWrite`, whose
  `producesRecord()` is false so the shipped re-fetch truth (a routine write's response is the
  post-commit chain re-read, not a record handed back) is preserved as an arm property rather
  than a default arm; `NodeResolve` to the protocol member; `Nest` and every record-read leaf to
  the empty set (the DataFetcher's existence is the fact).
- **The member key.** The condition member is keyed `(coordinate, table)`, mirroring
  `ConditionRelation`'s key so the front/back join is 1:1; a polymorphic root mints one
  condition member per table-bound participant carrying filters. Every other kind admits one
  member per coordinate; the write verb is the sealed arm of one write member, never a second
  key column.
- **The reentry mint.** Minted centrally in the projection from the facts the site-level
  predicate always encoded (bare catalog table target, received or produced record, minus the
  root service passthrough), so the compound predicate is stated once at the mint and
  `emitsKeyedReQuery()` is a presence read.
- **Labels.** `OperationMember` is RESOLVED_VIEW on `ConnectionSynthesisRelation`'s precedent;
  the nested `Write` seal inherits that kind (the verb payloads are trigger-fact references the
  view realizes, not emit-grain mints), recorded with the registrar's reasoning in the registry.
  `Operation`'s own COMMAND entry is untouched until the slice that makes the seal a derived
  summary.

### Slice 2 (landed 2026-08-02): instruments

Landed: the leaf ratchet (`LeafRatchetTest`, pinned 12/15/24/4 on the
`GeneratorCoverageTest.sealedLeaves` counting rule, downward-only with a history line per move);
the member-grain census beside the coordinate-grain axis-pair census
(`ClassifiedDslTest.memberGrainCensusIsDerivable`: one observation per `(coordinate, member)`
row, printing the kind histogram, the per-leaf admissible-versus-observed combination gap
derived from `DECLARED_SHAPES`' image, and the member-kind-by-source / member-kind-by-target
pairs; the coordinate-grain rows stay valid as the empty-or-one projection); and the baseline
measurement rows in *Progress measurement* below.

### Slice 3 (landed 2026-08-02): the keystone

Landed: five per-trigger `FactVisitor`s beside the pagination precedent (`ConditionFactVisitor`,
`OrderByFactVisitor`, `LookupFactVisitor`, `ServiceFactVisitor`, `WriteFactVisitor`), each the
single lexical home of its directive name (`BuildContext` delegates, the `DIR_AS_CONNECTION`
precedent), with `GatheredFacts` grown to six slots, `INPUT_OBJECT_FIELD` leaving the
`NOT_GATHERED` waiver set (the condition and lookup gathers subscribe it), and per-slot
population pins (`TriggerFactPopulationPinTest`); the minted member relation
(`OperationMemberRelation`, a post-walk fold on the `TenantBindingIndex.compute` precedent)
stored on `GraphitronSchema`, with `operationMembersOf` re-sourced onto it and the leaf-derived
projection kept as the walk-less-schema fallback; the membership-agreement pin
(`OperationMemberMintPinTest`: minted equals leaf-projected per coordinate over the corpus plus
per-kind coverage fixtures, compared through a canonical `RecordComponent` rendering with a
justified exclusion set for minted alias addresses); the slot theorems and the reentry-launcher
agreement pin; `Operation`'s registry entry re-labelled COMMAND to RESOLVED_VIEW with the
registrar's reasoning, and `OutputField.operation()`'s contract narrowed to the derived summary
column; `LauncherRelation`'s single-operation javadoc sentence retired for the successor
stating the host relationship.

Decisions bound here:

- **The delivery fact** (the Spec's slice-3 design debt, bound as design; materialization is
  slice 5's, where anchor-hood becomes its first view). Two arms, `Batched(trigger)` and
  `Inline`, with the trigger sealed as `Authored` (the `@splitQuery` / `@tenantFanOut` marker
  set, single-homed today in `FieldBuilder.forcesSplitDelivery`) or `RecordHandedParent` (the
  classifier's record-backed-parent arrival rule). An authored marker and a record-handed
  parent both name the batched delivery, so they are provenances of one arm, never two arms;
  `Inline` is the complement, and the falsifiable content is the trigger disjunction, not a
  catch-all third arm. The service and reentry launches are derived, not arms. The arm
  predicate's homes (`forcesSplitDelivery` plus the record-parent classification arms) migrate
  onto the materialized fact at slice 5; the address column joins the member view when the
  split key-projection member migrates; the trigger never reads `Source.OnlyChild`.
- **The reentry member and the launcher key.** `LauncherRelation` stays coordinate-keyed: the
  launch is the dispatch target a coordinate's member set renders into (one query unit hosting
  select, condition, orderBy and paginate members in one SELECT), so a member is never a second
  key column, and a `(coordinate, member)` re-key would hang host strategy on members that
  never launch. The write member is deliberately unmaterialized in the launcher relation (the
  write stays with the mutation entry point); its reentry companion launches through the
  relation's reentry-sourced rows. The enforcer is the reentry-launcher agreement pin: over the
  corpus's producible examples, the minted reentry-member coordinate set at DML grain equals
  the launcher relation's reentry-sourced row set, so the two independently produced encodings
  stay one fact.
- **The minting discipline and the window's edges.** The production splits structurally:
  `memberKindsOf` decides membership from facts alone (no leaf-class switch; trigger slots for
  serviceCall and the DML write verbs, the routine chain and `@pivot` SDL facts, the Node
  signature joined with the type verdict, target/source shape facts and the table-bound type
  verdicts for select, the reference fact for join, capability reads for the payload-gated
  kinds), while `payloadsFor` extracts payloads from leaf-carried resolutions, the identity
  half the additive window sanctions. Its enumerated identity reads and their dissolution
  owners: the write verb records (slice 6b), the pivot spec (6c), the reference-path accessor
  arms (the walked reference home). The service payloads read the existing `ServiceField` /
  `MethodBackedField` capabilities; the polymorphic-root filter surface gained the two-record
  `ParticipantFilterField` capability rather than identity arms. Per-kind current edges, each
  flipped at its family's dissolution slice: condition, orderBy, paginate and lookup membership
  read the leaf-resolved payload presence (the resolver-reads-leaf-derived edge; for paginate
  the walked slot already produces the carried window through `PaginationResolver`, and the
  slice-1 carried-window gate stays, so the batched polymorphic connection still mints no
  paginate member). The kinds whose membership inputs are shared across both productions at
  this slice are named: select, join and reentry read the same shape/reference/record facts on
  both sides, pinned for regression rather than independence until the delivery fact and the
  walked reference home separate them; the reentry production reads the write and service
  trigger slots, never a sibling member's presence.
- **The domain boundary.** The minted relation covers the flat classified index, exactly the
  coordinates the view served before the keystone. A nesting type's fields mint no
  coordinate-keyed rows: the record-handoff corpus example reaches one nested coordinate
  through two source shapes whose reentry truths differ, so a coordinate-keyed row would
  collapse two truths; nested leaf instances keep the leaf-local derivation until the
  mixed-reach question is taken up with the delivery fact. Synthesised coordinates carry no
  pre-rewrite definition node, so their slot joins are empty and membership rides the shape
  facts, matching the projection.
- **What "source" means during the window** (stating the gap rather than leaving it to slice
  5's reviewer): the trigger relation is the source for the slot-gathered kinds and the view's
  production; the leaf projection remains the emit-side producer behind
  `OutputField.requiresReFetch()` / `emitsKeyedReQuery()` (leaf-local reads with no schema in
  reach) until the delivery fact materializes, and survives besides only as the
  membership-agreement pin's comparison side and the walk-less-schema fallback. Payload
  single-homing is correspondingly partial by design: the member rows are the view's single
  production home, while the leaf payload components stay for their remaining readers (the
  slice-4 and slice-5 re-sources), which is the "leaves read-only on the operation axis"
  window arriving reader by reader.
- **The summary column's status.** `Operation` re-labelled RESOLVED_VIEW (the derived summary
  over the member rows; the payload-mirroring pin holds the column's slots equal to the member
  payloads), `OutputField.operation()`'s javadoc narrowed to say so, and the three leaf-identity
  `operation()` switches stay the materialisation until slice 4 retires the accessor, per the
  retired-vocabulary table.

### Slice 4 (landed 2026-08-02): the remaining readers re-source and the accessor retires

Landed in two commits, applying the programme's additive-then-destructive discipline one level
down: the first commit ships the member-derived replacements pinned equal to the summary column
they replace, the second deletes the column, so the equality is demonstrated in history rather
than asserted after the fact.

First commit (the tenant re-source):

- `TenantBindingIndex` reads the coordinate's minted member rows: the builder computes the
  member relation ahead of the tenant fold and threads it in, and the fold rejects the `EMPTY`
  sentinel at entry, so the classifier and the routing emitter provably read one walk-minted
  production. `directSlots` walks the whole member surface (every condition member's filters,
  which subsumes the per-participant polymorphic fallback; the lookup member's key mapping; an
  INSERT / UPSERT write member's `@table` input), deduped by slot name across members; the
  `participantFilters()` fallbacks dissolved in both the classifier and the emitter.
- `TenantBinding.BoundSlot` gained a sealed `SlotRead` component (`TopLevelArg` /
  `NestedInput` / `ContextArg`) minted where the slot is discovered, so `TenantDslEmitter`
  renders already-resolved reads instead of re-walking the operation carriers; the duplicated
  traversal and its classification-versus-emission `IllegalStateException` guard are deleted
  (the consult's "decide once, carry the decision as a type" finding, taken at the cheap
  moment when both traversals were being rewritten anyway).
- The `@tenantFanOut` ladder re-keyed: the lookup and node rungs read member kinds, the
  `@asConnection` rung deliberately reads the target axis (see the decisions below), and the
  fan-out "already binds" rung reads the same member-derived slots as the direct-binding fold.
- The reentry-guard validator diagnostic re-worded off member kinds.
- `Operation.ServiceCall.Call` moved out to top-level `ServiceCallCarrier` (the service-call
  member row is its primary holder; registry label RESOLVED_VIEW), unblocking the seal's
  end-state decision at the corpus re-grain.
- The bridge pin: `DimensionTuple.summaryArmOf` (the member-derived precedence fold) held
  equal to `operation().getClass()` arm-for-arm over every corpus coordinate, ridden fields
  included, while both existed.

Second commit (the retirement):

- `OutputField.operation()`, the three leaf-identity `operation()` switches
  (`QueryField` / `MutationField` / `ChildField`) and the four `Operation`-building statics
  (`readOperation`, `bareFetch`, both `serviceCall` overloads) deleted; javadocs re-pointed at
  the member rows (the `Source.Root` legality gate, the re-fetch orthogonality note).
- `DimensionTuple.of` reads the fold; the bridge pin retired with the accessor (its
  demonstration lives in the first commit's history).
- `OperationMemberProjectionTest` narrowed to the declaration fence (shape totality,
  well-formedness, declared-not-accidental unpopulated kinds); population agreement is
  `OperationMemberMintPinTest`'s alone.
- `Operation` re-documented as the corpus's verb vocabulary: arms construction-dead in main,
  the family surviving as the fold's codomain and the corpus SDL enum mirror until the corpus
  voice re-grains and decides its end state.

Decisions bound here:

- **The widening is structural; its new surface is unreachable today.** Reading the whole
  member surface exposes what the one-arm summary hid: a lookup coordinate's condition
  filters, and a service-table child's. Both are unconstructible in the current tree (a
  generated filter on a lookup coordinate is a build error, validator plus
  `ConditionCommands.requireNoGeneratedFilterOnLookup`; both `ServiceTableField` builder sites
  construct empty filter / ordering / pagination slots), so the re-source is
  behavior-preserving over every constructible schema, option (a) of the consult with the
  delta named rather than silent. When either gate opens (the lookup dissolution's re-grain,
  or a populated service filter surface), the widened read makes those shapes' tenant
  classification a decision to take with a pinned fixture, and the consequence to weigh then
  is that the fold prefers `ArgumentBound` over `Inherited`: a newly bound coordinate moves
  its database-per-tenant routing from ancestor-derived to client-argument-derived, a
  security-adjacent flip that must not fall out of a refactor.
- **Connection-ness stays a target-axis fact.** The paginate member is minted from the
  carried window payload and is strictly narrower than the retired `Paginate` summary arm
  (the batched polymorphic connection is connection-shaped with no window). The
  `@asConnection` fan-out rung and the fold's `Paginate` arm both read
  `TargetShape.Connection`, not member presence: the first vocabulary gap the Spec predicted
  this slice would surface, answered by reading the axis that actually carries the fact.
- **The empty-set summary arm is a named fiction.** The fold derives `Nest` versus `Fetch`
  for empty member sets from the target shape (the nesting embed is the unique empty-set leaf
  with a bare table target). That is the summary column's fiction for coordinates the member
  relation deliberately says nothing about; it lives only in the test-side fold, documented
  as such, and retires with the corpus re-grain.
- **The slot read single-homed on the slot.** The tenant-routing agreement between
  classification and emission is now structural (one traversal mints both the verdict and the
  read) instead of guarded by a generation-time throw.

Slice-4 baseline (measured 2026-08-02):

- `Operation` structural consumers in main: 0 (baseline 2; both tenant readers re-sourced).
  The seal's arms are constructed nowhere in main; `ServiceCallCarrier` holds the live
  service-call payload.
- Leaf ratchet unchanged at 12/15/24/4 (dissolution belongs to the 6x slices); dispatch pins
  unchanged at 18 / 71 + 76 / 156 (the deleted emitter traversal was carrier-payload dispatch,
  not leaf dispatch, so `CommandSeamRatchetTest`'s patterns never counted it).
- Generator suite 3100 tests green; emit byte-identical through both commits.

### Slice 5 (landed 2026-08-03): back-half membership re-sources

Landed in two commits on the additive-then-destructive discipline: the first materializes the
delivery fact with no consumer moved, the second re-sources the three plan producers onto the
members and the fact and retires the leaf-identity membership.

First commit (the delivery fact):

- `DeliveryFact` (model, WALKED_FACT): `Batched(trigger)` | `Inline`, the trigger sealed
  `Authored` | `RecordHandedParent` | `PolymorphicFanIn`. The third arm extends the keystone's
  two-trigger disjunction, which this materialization falsified: the batched polymorphic pair
  mints from the cardinality-plus-participant rule on table and record parents alike, a
  provenance neither bound arm covered. The falsifiable content did its job; the arm is named
  rather than absorbed into a catch-all.
- The authored markers became the seventh gathered-facts slot (`DeliveryFactVisitor` owns the
  `splitQuery` / `tenantFanOut` literals; `BuildContext` delegates), and the classifier's four
  delivery-forcing reads re-sourced onto the gathered rows, so the marker set single-homes
  including the directive names. The row carries the two markers separately because their
  forcing scopes differ (the table-child arm reads the union; the pivot gate, routine-chain
  gate and nesting deferral read the `@splitQuery` half), which resolves the marker-set
  discrepancy the consult surfaced without splitting the fact's home.
- `DeliveryFactRelation`: the post-walk fold of the markers with the source-shape, target and
  verdict facts, stored on `GraphitronSchema` beside the member relation with the same
  EMPTY-sentinel-plus-crosswalk-fallback read surface. `DeliveryFact.leafDerivedOf` is the
  compile-total comparison side and walk-less fallback, never a production source; unlike the
  member crosswalk it carries no expiry date, because delivery is one of the three axes the
  leaf reconstruction key keeps. `DeliveryFactPinTest` holds the two productions equal over
  the corpus with per-trigger floors; the shared-inputs discipline (production and classifier
  read the same facts during the window, pinned for regression rather than independence) is
  the keystone record's, cited on the relation.
- `OperationMember.Condition` re-grained into `OnReturnTable` | `OnParticipant` on the `Write`
  sub-family precedent: the participant arm carries the `ParticipantRef.TableBound` whose type
  name the back-half glue naming reads off the arm, retiring the reverse table-to-typeName
  lookup the flat record would have forced. Both arms answer `table()`, so the
  `(coordinate, table)` key and the 1:1 back-half join are unchanged.

Second commit (the re-source):

- `LauncherCommands`: membership single-homes on a sealed launch verdict
  (`Launch`: none, service, DML reentry, root catalog, batched child catalog), derived from
  members, delivery and position only, with the fact-parameterized overload serving the
  schema-free walk from the leaf projection and crosswalk so the two walks read one
  predicate. `MINTING_KINDS`, the `MintingKind` key shapes and the schema-free leaf filter
  retired; the row builders survive as payload dispatch behind per-verdict switches whose
  default throws are drift guards, and the `DmlReturnExpression` switch stays total (the
  `Encoded*` arms now throw: their exclusion is the absent reentry member). `mintedMethodOf`
  re-derives naming from the verdict plus the lookup member. The single-table anchor is the
  target-axis read (`TargetShape.Table`, which includes the single-table interface root); the
  polymorphic family's `Interface` / `Union` shapes exclude it without leaf identity.
- `ConditionCommands`: one row per condition member, the member payload being the row input
  and the glue naming a total switch over the member's two arms; the polymorphic
  `participantFilters()` arms dissolved. Nested instances read the leaf-projected members per
  the member relation's domain boundary; the two validator-mirror backstops stay.
- `ProjectionCommands`: the six table-target leaf cases merged into one
  `ChildField.TableTargetField` arm whose forks read the serviceCall member, the delivery
  fact, the parent-row-demand capability and the lookup member; payload extraction keeps the
  sanctioned casts (the step-0 correlation has no capability home until the lookup triplet's
  fold). `PLAN_LEAF_REFERENCES` re-pinned 156 to 140.
- The launcher membership census re-shaped onto the verdict: coordinate-set equality against
  the pinned fixture roster, per-family and per-`LaunchSource`-arm non-vacuity floors; the
  reentry-launcher agreement pin re-worded (the launcher side now reads the member, so the pin
  guards the verdict-to-payload seam rather than two independent productions).

Decisions bound here (the consult's findings, adopted):

- **Membership is a sealed verdict, not a boolean.** A boolean `covers` would have re-derived
  the launch family at three consumers; the verdict is computed once and every consumer
  switches total over it, which is the compile-error property the Spec required carried
  forward, relocated onto the verdict's arm set plus the two total crosswalks and the
  censuses.
- **`Inline` deliberately covers two situations** (rides the parent's statement; has no
  statement of its own). A serviceCall coordinate reads `Inline` because the call owns its
  delivery the way it claims the projection slot; the launcher's anchor-hood is therefore a
  join of delivery with the hosted members, not a view over delivery alone, and the service
  and reentry rules bypass the delivery axis by design (the keystone's "derived, not arms").
- **The nest and pivot arms stay sealed dispatch.** Folding the nest walk onto
  (empty members and Table-shaped target) would trade the projection seam's compile-checked
  coverage for a runtime cast over an observed-not-structural conjunction, and the pivot
  pair's operation half is slice 6c's dissolution by name; the spec row's "Nest/Pivot walk
  reads members" lands as the unit-minting membership staying with the sealed arms whose
  leaves 6c folds, recorded here as the deviation and its grounds.
- **The delivery axis's corpus voice** is the delivery pin until the corpus re-grain; whether
  `@classified` grows a delivery argument is slice 7's question, decided beside the member-set
  assertion shape, so the axis does not land unasserted (the pin is corpus-wide) nor
  half-asserted (a directive shape chosen before the member vocabulary's own re-grain).

Slice-5 baseline: recorded under Progress measurement above.

### Slice 6a (landed 2026-08-03): the lookup triplet dissolves

Landed in two commits on the additive-then-destructive discipline: the first materializes the
sealed resolution and single-homes the detection with no consumer moved, the second folds the
three leaves onto their fetch siblings and retires the capability.

First commit (the resolution):

- `LookupResolution` (model, WALKED_FACT): `None` | `Keyed(mapping)`, declared total on the
  `TableTargetField` seal beside `filters()` / `orderBy()` / `pagination()` and carried as a
  component by the three fetch siblings; the polymorphic and service variants answer `None`
  structurally with no storage. The consult redirected the payload home from a nullable
  component to this seal: the vacuous mapping became unrepresentable
  (`ColumnMapping` now rejects an empty arg list; the resolver returns `None` instead), the
  "non-null implies non-empty" invariant got a type instead of prose, and the relation's
  payload arm lost a throwing default.
- The `@lookupKey` detection single-homed on the gathered trigger: `LookupFacts` grew a
  type-grain closure (input type names transitively carrying the directive, a fixpoint over
  the visitor's reference edges, per the consult's grain finding), `triggersFor` is the
  field-level predicate's one home, and the classifier's recursive walk retired with its
  depth-10 cap (a silent false negative, named as the one behavior delta: applications below
  depth 10 were invisible, now seen).
- Membership did not flip onto the raw trigger row (the consult's hazard finding: an
  application-site row on a coordinate the classifier routes elsewhere, a `@service` or
  `@routine` bearer, would desynchronize kind from payload). The LOOKUP kind stays a
  payload-presence read beside condition, orderBy and paginate, with kind and payload reading
  one axis through `OperationMembers.lookupResolutionOf`.

Second commit (the fold):

- The three leaves, the `LookupField` capability and the `LookupValuesJoinEmitter` adapter
  deleted; the classifier's lookup gates survive as routing and rejection (the root
  promotion gate keeps the `@lookupKey`-claims-a-table-read verdicts, `resolveAtChild` keeps
  the connection and split-cardinality rejections), minting the same fetch siblings the plain
  paths mint with the resolution riding as payload.
- Producers fork on the lookup member: the launcher's root-catalog and batched-child arms
  read the member's mapping payload (`lookupRow` / `batchedLookupRow` survive as payload
  builders re-signed onto the siblings), and the schema-free walk reads the same fork through
  the leaf projection. The producer fork is four slots wide and deliberately so (the
  consult's slot audit): the lookup row differs from the plain row in method-name scheme
  (`lookup<Field>` vs `launch<Field>`), source arm (`KeyedLookup` / `CorrelatedLookupChain`),
  tenant strategy (structurally `Single`; the fan-out pair is classifier-rejected) and result
  shape (`RecordList` with the ordering slot empty, the "a lookup entails input ordering"
  statement), so the payload builders were kept distinct rather than merged onto
  `resultShapeOf`, preserving emit exactly.
- The generators fork on the launcher row's source arm (`KeyedLookup` /
  `CorrelatedLookupChain`), needing neither schema nor member relation on the paths where the
  schema is legitimately null; the two type-level scatter gates and the parent-input gate
  read the sibling leaf plus its resolution; the reified-read arm's lookup twin deleted
  outright (single-cardinality inline lookups are classifier-rejected, so the folded
  instances always took the same branch).
- The validator's per-leaf lookup arms folded into the sibling arms gated on the resolution;
  the merged batched arm's lookup branch owns the Connection verdict outright (one located
  rejection, not the ORDER-BY guard stacked on top, the consult's double-error hazard) and
  the falsified "ctor rejects Record + Connection" comment was rewritten with the gate. The
  merged constructor invariant gates on the resolution axis (`Record` + `Connection` stays
  unrepresentable for the plain read, author-reachable and validator-rejected for the keyed
  read, exactly today's grain); the local-context allow-list keeps its lookup exclusion as a
  resolution gate and its rejection message now names the disqualifying fact.
- The generated-filter-on-lookup rejection re-grained onto the fact-level predicate on both
  sides: the validator mirror reads member co-presence (a condition surface carrying a
  generated term beside the lookup member; the EMPTY-relation fallback serves the hand-built
  fixtures), and the producer backstop was already member-sourced since slice 5.
- Instruments: leaf ratchet 12/15/24/4 to 11/15/22/4 with history lines;
  `PLAN_LEAF_REFERENCES` 140 to 132; generator pins 71 + 76 to 69 + 69; the launcher
  render-site pin 10 to 8; the dual-arm list dropped its lookup entry; `DECLARED_SHAPES`
  moved LOOKUP from required-on-lookup-leaves to optional-on-siblings (the single
  load-bearing fence edit, gating both productions).

Decisions bound here:

- **The dispatch partition stays leaf-keyed** (the design debt owed at the first 6x slice).
  `STUBBED_VARIANTS` is empty; the leaf-keyed partition shrinks with each fold, and
  unimplemented member combinations are guarded by fact-grain rejections (the
  generated-filter-on-lookup pattern) and producer drift guards, so no member-grain stub set
  is minted while the stub set is empty. The gate's strength is unchanged: the partition
  census still fails loudly on an unplaced leaf.
- **Realization of member kinds co-present with lookup**, stated per kind (the consult's
  census finding): select and join realize through the lookup SELECT and its reference path;
  condition realizes for authored `@condition` rows (generated terms stay rejected at
  exactly today's grain); orderBy and paginate members can mint beside a lookup member at
  child grain but no seam realizes them (the ordering slot is deliberately empty, the
  `@orderBy` rejection exists at root only), and the batched single-record-per-key lookup
  still fails loud at production rather than validating; these deliberately-unrealized
  combinations are owned by the Backlog item filed with this slice
  (roadmap/lookup-unrealized-co-members.md) rather than silently documented here.
- **Test fixtures follow the sibling constructors**: the three per-leaf validation test
  files became the lookup-gated cases on the siblings (`InlineLookupValidationTest`,
  `BatchedLookupValidationTest`, `RootLookupValidationTest`), the class-assert rows assert
  the keyed resolution so a fixture that silently stopped resolving fails with the
  discriminating message, and the retired-vocabulary registry gained no entries (its bar is
  demonstrated recurrence, not rename); the two successor strings naming the retired batched
  leaf were rewritten.

Slice-6a baseline: recorded under Progress measurement above.

### Slice 6b (landed 2026-08-03): the DML verb split dissolves

Landed in two commits on the additive-then-destructive discipline: the first lands the verb
payload's sealed home and re-keys the record carriers onto it with the reachable-state set
unchanged, the second folds the eight verb-encoding leaves.

First commit (the write payload lands):

- The member family's `Write` seal gained the intermediate `Dml` seal (Insert, Upsert,
  Update, Delete) exposing the write-target table and the input cardinality uniformly over
  the arms' structurally different input surfaces; the two record carriers re-keyed their
  `DmlKind` slot plus `TableInputArg` component onto one carried `Write.Dml` payload,
  exposed through the new `DmlWriteField` capability (WALKED_FACT, on the
  `HasInputRecordShape` precedent). The member view and the minted relation's payload
  extraction read the capability, so the write member row is the leaf's component by
  identity, not by copy, and both `recordCarrierWrite` crosswalk helpers (including the
  stringly-typed one) retired. The consult refused the parallel model seal a 6a-shaped
  design would have minted: unlike `LookupResolution` it would carry no axis the member arm
  cannot (WRITE is required on every write-carrying leaf, so there is no `None` arm to
  earn it), and identity beats a mapping a pin must keep proving equivalent.
- Two emit bodies that were dead by constructor rejection (the `@value`-driven record
  UPDATE chain and its bulk per-row twin) were deleted, so the fold could not leave two
  UPDATE emit shapes on one leaf; the edge producer's record-carrier arms joined the
  condition-glue derivation the class javadoc already declared instead of hard-coding the
  absence (behaviorally identical: no mutation coordinate has a condition row).

Second commit (the fold):

- The four `DmlTableField` verb leaves folded into one direct-return record and the four
  payload-verb leaves onto the two record carriers; `UpdateRowsField` and `DeleteRowsField`
  (capability seals with zero readers, the `LookupField` shape) deleted. The classifier's
  per-verb resolution lattices survive as trigger-fact resolution (the walkers and the
  write-target rungs genuinely differ per verb); what collapsed is the minting, now one
  record per family with the verb riding the write arm.
- The dialect requirement became a derived accessor on the merged record, computed from
  the write arm and its input cardinality, never stored beside them: the stored slot would
  have made mispairings like a Delete arm carrying RequiresFamily constructible with no
  rejector, and the derivation deletes the classifier's three per-site computations. The
  DELETE construction invariant re-grained onto the real fact (a Delete arm pairs only
  with Encoded* returns, where the old ctor rejected Projected* and left Discriminated*
  representable on a prose contract), which makes the validator's reentry key-arity check
  provably vacuous for Delete; the validator's ten mutation-DML arms became three with one
  shared method.
- The catalog projects the verb, write-target table and input-type name off the write arm;
  `DmlKind` survives as the classifier's parse vocabulary and the catalog surface
  (`FieldClassification.DmlMutation` / `DmlRecord` unchanged, so the LSP and MCP consumers
  are untouched). `DECLARED_SHAPES` fell from ten mutation-DML entries to three; the
  merged direct-return entry widens DELETE's declared optionals to include REENTRY, and
  the entry's comment names the compensation: the fence moved below the image check, to
  the constructor, where the reentry-minting DELETE is unconstructible rather than
  image-rejected.

Decisions bound here:

- **Bulk-ness stays two carrier leaves, named honestly**: the single/bulk pair differs on
  neither source, delivery, nor its own target (the carrier is single on both sides; the
  list lives on the data field), so the distinguishing fact is input cardinality, an
  input-side fact outside the reconstruction key. The acceptance criterion tolerates the
  pair as a deliberate exception whose owner is the input-side classification half R222
  keeps; folding it is not operation-relation work.
- **Dependency direction**: the write payload originates at leaf construction (the
  classifier resolves the write target, the walkers produce the carriers) and the member
  view reads it off the leaf by identity. This edge is terminal for the programme; no
  later slice moves the walkers upstream of leaf construction.
- **The UPSERT consumer statement** lost its two leaf-keyed homes (the generated shapes
  row and the `ExemptionRegistry` projection/corpus exemptions keyed by the retired leaf);
  the surviving statements are the classifier's typed deferral, the code-generation
  triggers row, and `Operation.Upsert` in the known-gaps registry. The per-verb generated
  row degrades to prose deliberately; a verb dimension in the leaf-coverage tool was
  refused as scope creep.
- **Test fixtures follow the write arm**: the four per-verb validation files became
  verb-gated cases on the merged record (`InsertMutationValidationTest` and siblings), and
  payload reads across the test tree go through `DmlWriteReads`' asserting extractors, so
  a fixture that silently classifies onto a different verb fails with the discriminating
  message. The dialect pins in `TypeFetcherGeneratorTest` re-anchored onto the derived
  reasons (the injected fixture-only reason strings retired with the stored slot).

Slice-6b baseline: recorded under Progress measurement above.

## Retired vocabulary

Declared per the item-file conventions in `roadmap/workflow.adoc`; each term names the slice that
retires it, and the sweep at each gate runs against what has actually shipped.

| term | slice | successor |
|---|---|---|
| `OutputField.operation()` as the single-valued accessor, and the three leaf-identity `operation()` switches | 4 | the coordinate's member rows on `GraphitronSchema`; the summary column, if the corpus keeps one, is a derived view named at slice 7 |
| `LookupTableField`, `BatchedLookupTableField`, `QueryLookupTableField` | 6a | the Fetch-sibling leaves plus the lookup member row |
| `LookupField` (the capability seal) | 6a | the sealed `LookupResolution` carried total on the table-target leaves |
| `LookupValuesJoinEmitter` (the input-rows adapter) | 6a | `render/LookupRows`, called with the launcher row's mapping payload |
| `MutationField.DmlTableField`'s four verb leaves; the payload arms' verb halves | 6b | write members carrying the verb; source/target grain leaves |
| `UpdateRowsField`, `DeleteRowsField` (the reader-less capability seals) | 6b | the Update / Delete write arms carrying the walker carriers, read via `DmlWriteField` |
| `MutationDmlRecordField.kind()` (the stored `DmlKind` slot) and the stored `dialectRequirement` component | 6b | the carried write arm; the dialect requirement derived from arm plus input cardinality |
| `LauncherRelation`'s "this family is single-operation" javadoc claim | 3 | the reentry and write members; the real key stated |
| `requireNoGeneratedFilterOnLookup` (the producer backstop) | 6a | the fact-grain rejection: a condition member carrying a generated `WhereFilter` term co-present with a lookup member |
| `DimensionTuple`'s single `Class<? extends Operation>` component | 7 | the member-set assertion |

## Non-goals

- **No generic fact bus and no query-engine runtime** (R333's own resolutions; typed keyed relations
  in the type system, referential integrity as typed checks).
- **`source` and `target` stay as they are.** Only `operation` is normalization-forced; this
  programme does not touch the arrival or output facts beyond what payload single-homing requires.
- **Byte-identical output is not the acceptance**; the existing tiers are (compilation, execution,
  the closure oracles, the corpus). Emit-neutrality is the per-slice default all the same.
- **No emit capability changes, in either direction.** Dissolving a leaf does not enable what its
  absence rejected, and does not remove what shipped: rejections re-grain onto facts at their real
  predicate and stay build errors until an emit item implements the combination. The
  generated-filter-on-lookup combination in particular stays rejected at 6a at exactly today's
  grain (authored `@condition` on lookup stays supported); implementing the generated combination
  is its own future item.
- **Not R222's other halves**: input-side classification, failure-at-the-wrapper, the diagnostics
  stream, directive narrowing, and the `ChildField`-to-`SourceField` namespace collapse all stay
  where they are.
- **Not R562's decision**: the synthesised-fields coordinate question keeps its owner.

## Reviewer suggestions (Spec review pass, 2026-08-01; dispositions recorded same day)

Three non-blocking suggestions from the review pass, all adopted, each folded into the section it
belongs to rather than living here as a side channel:

- **State a member co-occurrence grammar.** Adopted as reject-by-default, then promoted to a full
  Spec-bound statement so the gate reviews the design rather than deferring it: *The co-occurrence
  grammar* section carries the layered form (typed dependency edges, key-borne multiplicity, the
  derived admitted image, the seeded declared relation, theorem tests). Slice 1 owes only the
  mechanical encoding.
- **Date the coexistence window.** Adopted: slice 3's landing commit opens the window with a
  `roadmap/changelog.md` line, the last 6x slice's landing commit closes it. Bound in *Progress
  measurement*.
- **Budget review attention on slice 4.** Adopted as a review emphasis in slice 4's row: the
  `fanOutArmOf` re-source is where the member vocabulary's sufficiency is actually tested.

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
