---
id: R955
title: "The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left"
status: In Review
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-16
last-updated: 2026-09-24
---

# The register empties bottom-up: every remaining registered rule becomes a fact the graphitron gatherer writes in stage order, and meta_materialize has no rows left

## Goal

Every verdict the fact store used to keep in `meta_materialize`, the register that refreshed a `_live`
view into a table after every gatherer had finished, is a fact the `graphitron` gatherer writes, this
item's fifteen and R954's and R958's before them: one
`DELETE` and one `INSERT` per graph per capture, from the view stating its rule, in the order
`DerivationStratum` lists its steps, into a keyed `graphitron_` table every reader names. The
register, its dependency table, the `_live` convention and the refresh pass are gone from the tree,
and the one recursive rule among them, the `@nodeId` decode column, is evaluated once per capture
instead of once per driving row. A consumer's `graphitron:dev` round and `generate` both take this
path, because both run the capture and nothing else derives these tables. What this item does not
claim is what that path costs a consumer: no `sis` round on the shipped tree has been timed, for the
reason "Tests" records, and R972 holds the reading that is owed.

Four terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A *gatherer* is one
pass that fills the store from one input; the `graphitron` gatherer is the one that runs last, after
the transcribing gatherers have flushed, as a sequence of *stages*, each an `INSERT ... SELECT` over
rows earlier stages and earlier gatherers wrote. The `intent_` *family* is the prefix under which
derived rules lived as views and as the tables the register filled. The principle
this item applies, stated on the fact-model page and worked out at length in R876, is that a rule the
last gatherer can compute in a stage needs to be neither a view, nor a registration, nor a reader's
join: it is a fact that gatherer writes.

## Shipped

Fifteen of the register's twenty rows at pickup (the other five were R958's), converted bottom-up so
that no stage ever read a table a later step writes, then the mechanism dropped:

- Rung 0, `graphitron_field_column_scope`, with `StageOrderGateTest` and `StageAnswerAgreementTest`
  built beside it: shipped at `857f103`.
- The argument-site column scope and column match, and the mutation write payload: shipped at
  `938869d`.
- The `@nodeId` decode chain, instruction, decode hop, hop column and decode column: shipped at
  `702d4b0`.
- The input-field column match, filter role and carrier role, and the mutation payload chain, refusal,
  payload column, key membership and write destination; the register empty, and the tests whose
  subject was a registration deleted: shipped at `59e72d2`.
- The register dropped, the stratum stated as data in `DerivationStratum`, `StageProgress` in place of
  the refresh observer, the `derivation` relations re-owned, and the contributor docs and
  `store-performance` skill rewritten around stages: shipped at `4110fea`.

Each of the fifteen is a `graphitron_<x>` table with a primary key and a `meta_relation` row owned by
`graphitron`, filled by one stage from a `graphitron_<x>_rule` view whose text is the old `_live` view's
renamed. None was demoted. The three one-reader candidates each converted on the statement-size term,
the figures being in their commits: the argument column match's rule at 6 instantiations against 13,
the decode column's at 9 against 19, and `intent_mutation_write_agreement` at 13 against 65. The
recursive decode column keeps its `WITH RECURSIVE` inside the rule view; its stage filters the outer
`graph_name`, which evaluates the recursion over every graph and keeps one, the register's own cost
for that rule and no worse. Six of the ten `derivation`-owned relations read only what the
`graphitron` gatherer may and are re-owned to it (`intent_condition_slot`,
`intent_reference_for_application`, both unlowerable-ordering relations,
`intent_field_reference_step_fanout`, `intent_node_id_decode_landing_defect`). Four read `code_` or
`store_` relations that gatherer may not reach and stay with `derivation`, whose gatherer row now
binds `DerivationStratum`: `lint_violation`, `intent_scalar_java_type`,
`intent_condition_context_parameter`, `intent_external_field_contract_defect`. Which family each of
those four belongs in is the misfiling census's question. The five producer-written tables the
register's exemption covered are declared: `intent_type_domain` under `sdl`, and
`intent_type_backing_class`, `intent_authored_claim_rejection` and the occurrence-path pair under
`graphitron`.

**Where the tree departs from the plan this body carried at sign-off.** Each departure is simpler
than the plan, and none moves an answer.

- `MaterializeDependencies` is deleted rather than repointed. `ViewReferences` parses a stored view's
  read set on its own, which is all the order gate needed from it.
- No analyse loop survives, and there is no `meta_relation` roster for one to walk. `ModelCapture`
  already ran a whole-store `ANALYZE` before the derivations and another after them, and the one after
  covers every stratum table on a warm store. On a store none of whose stratum tables holds a row,
  `DerivationStratum.runAnalysing` commits each step and runs `ANALYZE TABLE` on that step's declared
  writes before the next plans. `DerivationStratum.analysingCadenceApplies` asks the stratum's own
  tables which case applies.
- The producers' write sets live on `DerivationStratum.Step`, and `HAND_WRITTEN` is deleted rather
  than repurposed. `StageOrderGateTest.theRosterIsTheStratumItChecks` holds the gate's roster equal to
  `DerivationStratum.steps`. `everyDerivedTableHasAWriterInTheStratum` replaces the old impossibility
  criterion: a stored `intent_` table no step writes fails the build.
- `StoreRefresh` no longer existed at pickup: each gatherer empties what it refills, so a stage's table
  is emptied by its own stage and nothing else, and no exemption had to be replaced.
- Rung 0 runs at the head of the stratum rather than after the producers, because its rule reaches no
  producer's table. The rule decides the position, not a convention.
- `graphitron_node_id_instruction` and `graphitron_node_id_decode_column` carry the node type in their
  keys (`resolved_type_name`, `node_type_name`, both `NOT NULL`). At a polymorphic coordinate the
  instruction is one row per member node type, and at a slot naming a polymorphic container two member
  node types can depart from one table. `NodeIdInstructionTest`'s multi-table cases refused the
  narrower instruction key, and the sakila example's build refused the narrower decode-column key.
- `UnlowerableOrderingRejectionRows` moved into the stratum under R958 (`6305d33`), not here.

## Tests

What demonstrates each claim of the goal, all in the tree:

- **Every rule is written by one stage, in an order no stage can violate.** `StageOrderGateTest`:
  `theRosterIsTheStratumItChecks`, `noStageReadsWhatALaterStepWrites` with
  `aStageAheadOfItsPrerequisiteIsCaught` as its firing case, `everyRelationHasOneWriterInTheStratum`,
  `everyDerivedTableHasAWriterInTheStratum`, and `everyStageTargetIsShapedLikeItsRule` for the column
  shapes `INSERT ... SELECT *` rests on.
- **Each table holds exactly what its rule computes.** `StageAnswerAgreementTest`: `EXCEPT` in both
  directions over three captured fixtures for all fifteen and R958's five. `noStageIsComparedOverAnEmptyRelation`
  and the per-arm fixture cases keep the comparison from passing over nothing.
- **No answer moved.** The relation tests under `graphitron-model`'s `intent` package pin each verdict
  at its coordinate grain and changed by rename alone.
- **Once per graph per capture, and reconciling rather than appending.** `WarmStartRefreshTest`:
  `aSecondCaptureOfOneGraphDoublesNothing`, `warmAndColdAgreeRelationByRelation`,
  `aStoppedFirstStratumIsRepairedByTheNextCapture` and `aSiblingGraphsPartitionSurvivesARefresh`, on
  a fixture that populates the stratum tables.
- **Both cadences plan against analysed tables.** `StagePrerequisiteStatisticsTest`: a cold capture, a
  warm capture and the analysing cadence each meet every stage's prerequisites analysed, and the
  one-transaction cadence on an unanalysed store meets them all unanalysed, which shows the instrument
  can see the difference.
- **The stuck-stage instrument survives the refresh observer.** `StageProgressTest`, with
  `aFailingStageHasAlreadyNamedItself` holding the name-before-statements contract that
  `dev-loop-internals.adoc`'s recipe relies on.
- **Every converted and re-owned relation is declared and reads only what its owner may.**
  `MetaDeclarationGateTest`, including `aDeclaredViewReadsOnlyWhatItsOwnerMay`.
- **The register is gone and stays gone.** No source names `meta_materialize`,
  `meta_materialize_dependency`, `Materializations`, `MaterializeDependencies` or `RefreshProgress`,
  and `FactSchemaGateTest`'s frozen roster refuses a relation name coming back.

**Not demonstrated, and why.** The body at sign-off named one more piece of evidence: the `sis`
consumer's `graphitron:capture` timed warm and cold, before (`fc327fb`) and after, with the per-stage
lines. It was not taken, and it cannot be from this repository: no `sis` store copy exists here, and
one has to come from the consumer. R953, R954 and R958 closed on the same ground. The goal is narrowed
to what the list above shows. The cost of narrowing is that what the stratum costs at consumer scale is
inferred from the mechanism, not observed: each stage runs the statement its registration's refresh
ran, on the same two cadences. The reading is filed as R972 so it has an owner rather than a line in
a closed item. Also not shown: that a reader of `graphitron_node_id_decode_column` seeks into it. The
key makes a seek possible, and `DerivedReadCostTest`, which priced such reads, left with the register it
paired against.

## Retired vocabulary

Mechanism names, gone with the last commit: `meta_materialize`, `meta_materialize_dependency`, the
`_live` suffix and "the `_live` view", "registration", "registered target", "the register",
"materializer", "materialization refresh", "refresh pass", "refresh order", "refresh stage",
`Materializations`, `MaterializeDependencies`, `RefreshProgress`, `refreshPartition`, `refreshWhole`,
`refreshAll`, `refreshAnalysing`, `REGISTRATIONS`, `REFRESH_STAGES`, `HAND_WRITTEN`, and
"hand-written derivation" as a category distinct from a stratum step. `REGISTRATIONS` cannot graduate
into `RetiredVocabularyGuardTest`: it is a live constant with an unrelated meaning in
`RelationRegistrationGateTest`.

Relation names, renamed by the move: the fifteen `intent_` names in the table above, each to its
`graphitron_` successor and its `_rule` view, and R954's eight where R954 has not already swept them.

## What this item does not do

- **It does not restate any rule in Java.** Every stage inserts from a stored rule view.
- **It does not move the family-local misplacements.** Nine `intent_` relations compute to an owner that
  runs before `graphitron` and are R876's enumerated list.
- **It does not retire `FactCapture`.** The stratum is one list in `DerivationStratum`, but
  `ModelCapture` still reaches it through the deprecated `FactCapture.derive`; calling the stratum
  directly is R876's linear-read target.
- **It does not settle per-gatherer transaction control.** Both statistics cadences carried across as
  they were; whether a gatherer should commit its family before deriving over it stays R876's.
- **It does not make stages skippable.** R857 and R872 would let a dev round skip stages the edit did not
  touch; a stage is a better unit for that than a registration, but the scoping is theirs.
- **It does not build the rule bench.** R899 owns making a rule's cost countable from the tree.
- **It does not mint a `lint` gatherer.** R876 has designed one; `lint_violation` waits for it.
- **It does not measure a consumer.** R972 owns that reading.

## Relation to other items

**R954** and **R958** shipped the bottom of this ladder, the `@reference` stratum and the column-scope
departures; both are Done, and every rung here became convertible as the relation under it became a
table.

**R876** is the doctrine, and this is its burn-down item 8, the register. Two amendments to its "What
each mechanism becomes" table, both argued before sign-off and both shipped: the view parse the register's
dependency derivation rested on survives, in `ViewReferences`, as the order gate's read sets, and the `derivation` gatherer
row stays while four relations still name it. What this hands back on R876's open question, "what
orders two relations under one owner": bottom-up, the constraint is satisfiable by construction,
statement order in one list realises it, and the rule views' stored definitions, read by a gate, are
the derivable source.

**R899** priced one register row at a time. Its instrument survives as a bench over stages, and it
should be re-cut against `DerivationStratum`.

**R942** fails the build on a rule that names an unregistered view once per driving row. With no
register, its subject becomes a rule view named on the inner side of a join by another rule view, and
its body should say so when next touched.

**R972** holds the consumer-scale reading this item names in "Tests" and does not take.

Several other bodies still describe the register as live, and a few now have no subject:
`materialize-dependency-derived-before-stamp`, `target-index-exemptions-in-the-model` and
`refresh-what-the-edit-touched`. They are their own items' to re-cut or discard.

## Other solutions we've considered

- **Leave the register and register more.** It is three DDL lines per rule and it lands the same rows.
  It is not the plan because it grows a mechanism that exists to compensate for a gatherer not having an
  order, when the pass has one; because its ordering is derived at boot from a register rather than
  checked at build from the rule; and because its reasons are unchecked prose that R876's audit found
  stale by up to three orders of magnitude with nothing failing. R954's "Other solutions" makes the same
  case for its subtree and it holds for every rung above.
- **Move each rule's text into the stage as a SQL string and delete the view.** This is the Backlog
  draft of this item and R954's phase 1 as written. It takes the rule's read set out of the catalog,
  where `ViewReferences`, the ownership gate and the order gate all read it, and makes the `EXCEPT`
  oracle a one-commit artifact. The fact-model page's stored form keeps the view for exactly these
  reasons. The one place the string form buys something a view cannot, a graph predicate inside a
  recursive seed, is stated under "The recursive rule keeps its recursion in the view".
- **A `meta_` relation declaring what each producer reads.** A hand-kept ordering with no derivable
  source, which the fact-model page rules out by name. The gate above derives the read set from the rule
  view instead, and only the jOOQ-written producers need a declared write set.
- **Convert top-down, starting with the dearest rule.** The dearest rules are the deepest, so a top-down
  order meets the seam at every step. The bottom-up order costs the cheapest conversions first and never
  meets the seam.
- **One commit for all fifteen.** Fifteen relations across three verdict families and a hundred-relation
  closure at the top, with the mutation chain's tests as the regression surface. The per-rung order
  exists so a reviewer checks one `EXCEPT` at a time and so trunk stays green between rungs.
- **Rewrite the rules in Java rather than SQL.** A stage inserts from a view, so the rule stays stated
  once in SQL and what changes is who runs it and when. Hand-rolled Java resolution would restate each
  rule in a second language with no oracle to prove it against.
- **Drop the `derivation` gatherer with the register.** Ten declared relations name it and the foreign
  key refuses. Re-owning them is ten judgements, not one, and one of them is a gatherer R876 has
  designed and not yet landed.

## Provenance

Filed 2026-09-16 as the follow-up R954's reviewer round and re-cut left implied: R954 found that its
27-relation closure bottoms out entirely in captured facts and converts bottom-up without an ordering
mechanism, and asked, in effect, why the same was not true of the rest of the register. It is. The
principle is R876's, stated on the fact-model page: a materialization is usually the price of a fact
nobody captured, and the `intent_` family is the shape a pipeline takes when its intermediate results
are not written down. This item writes them down.

## Reviewer findings

### Round 1 (2026-09-17, Spec -> Ready, reviewer session 01Kc43YJCDD7SrJp3kXLiRxb)

Verdict: withhold, on four findings. None of them touches the ladder or the method, which are right
and were checked rather than taken on trust; three are the plan naming an instrument that will not
do what the plan asks of it, and one is the goal paragraph claiming outcomes the item does not
deliver. All four are a sentence or a bullet each.

**What was recomputed from the tree and holds.** This is stated first because the body's factual
density is unusual and a later reviewer should not spend the passes again. `meta_materialize` holds
exactly 23 rows. The rung of every one of the fifteen was recomputed from the shipped `_live` bodies
with comments stripped, expanding through plain views, and every rung in the scope table is right,
3 and 5 through 15 as printed, with R954's eight occupying 0 through 6 under the same definition and
the fifteen plus the eight partitioning the register exactly. Every `view readers` count is right,
all fifteen of them. The three demote candidates are the three relations with one view reader and no
other, and `intent_mutation_write_destination`'s one reader is the plain view
`intent_mutation_write_agreement`. The `key today` column is right in all fifteen rows. The leaf
closure of the fifteen bottoms out in `graphitron_`, `graphql_` and `sql_` facts plus
`intent_input_occurrence_path` and `intent_input_occurrence_path_step` and nothing else, so no stage
reads `intent_type_domain`, `intent_authored_claim_rejection` or
`intent_field_unlowerable_ordering_rejection` and moving `UnlowerableOrderingRejectionRows` into the
order closes no cycle. `FactCapture.capture` runs the eight stages, flushes, then
`ClassificationDomainCapture`, `InputOccurrencePaths`, `ArgMappingCandidates`, `TypeBackingRows` and
`AuthoredClaimRejectionRows`, then the refresh, then `analyse`, then the rejection rows, in that
order. `Materializations` is 535 lines, `RefreshProgress` 185, `MaterializeDependencies` 261, and
the four call sites are where the body says. `refreshPartition` issues the delete and the
`INSERT ... SELECT * ... WHERE graph_name` the body quotes. `intent_node_id_instruction` is the only
one of the fifteen any main source names, in `NodeIdDecodeCoverageFacts`, and that read sits in
`detect` rather than in the derivation stratum, so it raises no ordering constraint on the stages.
`meta_relation` declares exactly the ten `derivation` relations the body enumerates and
`meta_gatherer_dependency` carries exactly eight `derivation` edges. `HAND_WRITTEN` lists the six
tables on the impossibility argument the body describes, and `ClassificationDomainCapture`'s javadoc
does call itself the SDL gatherer's last stage. Every quotation from `fact-model.adoc` is verbatim,
including the stored form, the plan-existing sentence and the 6293 s against 90.8 s. Every class,
test and gate method the body names exists under that name, `StageOrderGateTest` excepted, which is
the one it proposes.

**Finding 1, blocking (question one). The goal paragraph claims two outcomes this item does not
deliver, and the middle one it does.** "No view is refreshed into a table by a register" is exactly
what lands and is judgeable on its own. The clause around it is not. "Computes every derived verdict
the fact store answers with exactly once per capture" is false the day this lands: about a hundred
plain `intent_` views remain views, inlined at every naming, which "What this item does not do" says
in as many words. "No reader ever meets a rule that a recursive view re-walks once per driving row"
is also not delivered: the schema ships seven recursive views, this item converts one of them
(`intent_node_id_decode_column`) and R954 converts three, leaving `intent_authored_field_claim`,
`intent_field_chain_node` and `intent_jvm_ancestor` as recursive plain views readers still name, and
`intent_field_chain_node` is one R954 explicitly leaves for a stage to read inline. This is blocking
rather than a wording note because of what the goal paragraph is for here: it stands alone, both
gates are decided by reading it, and the revive rule decides an item undelivered when its acceptance
evidence does not demonstrate its goal. As written, the acceptance evidence this item names, a
consumer's capture timed warm and cold, cannot demonstrate two thirds of its own goal sentence.
What satisfies it: trim the first and last clauses to the register and its fifteen rules, and let the
recursion claim say what is true, that the one recursive rule among the fifteen is evaluated once per
capture instead of once per driving row.

**Finding 2, blocking (question two). The demote rule's third term measures against a maximum that
does not exist, and the tool that would supply it refuses to have one.** "Convert or demote, per
relation, on three terms" makes the third term the statement size after the demote, "from
`report-inline-multiplicity`, which must stay inside the shipped maximum". There is no shipped
maximum. `InlineMultiplicityCheck`'s own javadoc is explicit that there is not and argues why:
"Reports rather than gates. A ceiling would have to be a number somebody could defend, and the metric
is a deliberate over-approximation ... Until a few reductions give that number a basis, printing the
ranking is what the metric is for." Run against the tree as it stands it prints a top-15 ranking, no
threshold anywhere, heaviest relation 141, which does not agree with the 963 `fact-model.adoc` states
for the same quantity, so an implementer reaching for a ceiling finds neither a gate nor two figures
that agree. The term bites exactly where the body says it bites hardest: `intent_mutation_write_destination`
is rung 15 with a hundred-relation closure and is one of the three demote candidates, and the same
page prices the fully-demoted schema at 2739455 with two relations exhausting a four-gigabyte heap
while still parsing. What satisfies it: either give the ceiling a basis and say what it is, this item
being precisely the reduction the tool's javadoc says it is waiting for, or drop the third term to a
recorded judgement, the relation's before and after number from the ranking written into the commit
beside the other two terms, with no threshold claimed.

**Finding 3, blocking (question two). `StoreRefresh` reads the register in executable code, not in a
comment.** The last commit lists it as "`StoreRefresh`'s comment naming `refreshPartition` is
repaired". What is actually there is `refilled(dsl)`, a method whose whole body selects
`META_MATERIALIZE.TARGET_TABLE_NAME`, and whose result the warm pass's graph-scoped clear subtracts
so that a registered target is not emptied by a clear that is not its owner's. Dropping
`meta_materialize` breaks the method, and repairing the comment above it does not answer what
replaces the exemption. It is also an unmade decision rather than a mechanical repoint, because every
converted relation gains a `meta_relation` row owned by `graphitron` on this item's own terms, so the
clear either starts covering the converted tables, emptying each one in the same transaction that its
stage is about to delete and refill, on relations that comment calls a consumer store's largest, or it
excludes them from a source that has to exist. The stage roster the order gate and the analyse loop
both already need is the obvious one, which is why this is a sentence and not a redesign. Worth saying
that the adjacent argument holds: `StoreRefresh.prepare` is called from inside the capture, so a
`graphitron:dev` round that skips the capture runs neither the clear nor the stages, and the case
made for deleting `DevMojo`'s `refreshAll` is sound.

**Finding 4, blocking (question two). Blanket graduation into `RetiredVocabularyGuardTest` is against
that guard's entry bar, and the generic half of the list fails the build on arrival.** The last commit
says "the retired names below graduate into `RetiredVocabularyGuardTest`'s registry". Two things
refuse that. The guard's own javadoc sets the bar at demonstrated recurrence, "a term enters the
registry when an audit finds it surviving a cleanup, not at every rename", and excludes the generic
case by name, "tokens too generic to be unambiguous are omitted even when retired";
`roadmap/workflow.adoc` says the same, that recurrently-surviving terms graduate, as the escalation
step of the Done-gate sweep rather than as a plan deliverable. And mechanically the list cannot be
entered as it stands. Token entries match a whole identifier token over the Java identifier character
class, and in main sources outside the three classes being deleted, `registration` occurs 155 times,
`registered` 186 and `register` 106, almost all of it the live and unrelated data-fetcher sense in the
generators, so `noRegisteredTokenIsALiveMainSourceName` fails the build the moment any of them is
registered. `_live` cannot be a token entry at all, being a suffix rather than a token:
`intent_node_id_instruction_live` is one identifier. What satisfies it: keep the `## Retired
vocabulary` section as it is, since it is the right declaration for the Done-gate sweep, and change
the last-commit bullet to say that the sweep runs and only what survives it graduates, naming the
handful that could be token entries (`meta_materialize`, `meta_materialize_dependency`,
`Materializations`, `RefreshProgress`, `refreshPartition`, `refreshWhole`, `refreshAll`,
`refreshAnalysing`, `REGISTRATIONS`, `REFRESH_STAGES`) and leaving the generic terms to the sweep and
to `FactSchemaGateTest`'s frozen roster, which already holds the relation names against regrowth.

**Non-blocking, two.** "Ten of the fifteen carry none today", of a primary key, disagrees with this
item's own `key today` column and with the DDL: three of the fifteen have a primary key
(`intent_field_column_scope`, `intent_argument_column_scope`, `intent_node_id_decode_hop`) and twelve
have none, six of those carrying one index. The plan is unaffected, the instruction being to key what
the grain admits, but the number is quoted as a finding and should be twelve. Separately, "the other
thirteen wait for R954's rung 6" attributes to R954 a number from this item's numbering: R954's own
ladder stops at rung 5 because it counts plain views as rungs and this item counts only registrations,
so the same relations carry different numbers in the two bodies. Under this item's definition the
figure is right. Say "the top of R954's ladder", or state the numbering difference once where the rung
is defined.

**On the dependency.** R954 is `Spec` with three withheld rounds, and its round 3 records that round
2's two blocking findings still stand unanswered in its body. This item takes both of those findings
as settled ("Its round-2 review found two things this body takes as settled"), which is a fair reading
of the findings but not of R954's plan, which still describes the Java fold round 2 objected to. It
also defers a live shape question, the graph predicate inside a recursive seed, to "whatever shape
R954 lands". Not raised as a finding, because deferring one open question to the item that owns it is
the right call and the front-matter edge is honest about the ordering. Worth knowing when this item is
picked up: two of its fifteen are convertible against R954's phases rather than its Done, and the
other thirteen are not startable until R954 both lands and settles the seed question.

### Response to round 1 (2026-09-17, session 01Kc43YJCDD7SrJp3kXLiRxb)

All six findings are answered in the body by the session that raised them, which disqualifies that
session from signing off the result: the next `Spec → Ready` gate needs a third session. What changed,
so the next reviewer reads the diff rather than reconstructing it:

- **Finding 1.** The goal's first sentence is scoped to the twenty-three registered verdicts, which is
  what this item and R954 between them deliver, and the recursion clause now claims the one rule it is
  true of rather than every reader in the store.
- **Finding 2.** The demote rule's third term is a recorded judgement, the reader's statement size
  before and after written into the commit, with no threshold claimed. The body says why there is no
  ceiling to claim, that this item is one of the reductions the tool is waiting on, and what the figure
  is read against instead. The disagreement between the shipped ranking's 141 and the page's 963 is
  stated where an implementer will meet it.
- **Finding 3.** `StoreRefresh` moves out of the comment-repair bullet into one of its own. The
  exemption keeps its meaning and its measured argument and is repointed to the stage roster the order
  gate and the analyse loop already need. The `SELF_SWEEPING` alternative is named and refused, with
  the reason: the sixteen relations declaring `graphitron` today are base tables the gatherer's stages
  write without emptying a partition, and this clear is what empties them.
- **Finding 4.** Graduation into `RetiredVocabularyGuardTest` is conditioned on the Done-gate sweep
  rather than scheduled. The body carries the guard's entry bar, the ten names a sweep could plausibly
  graduate, and the mechanical reason the generic terms and the `_live` suffix cannot be token entries.
- **The two non-blocking corrections** are made: twelve of the fifteen carry no primary key, the three
  that do are named, and the rung numbering says once that it counts only registrations where R954
  counts plain views too, with the one number that read as R954's replaced by the relation it means.

Nothing in the ladder, the placement, the stage shape or the test plan changed, so the verification
those parts had in round 1 carries.

### Done gate round 1 (2026-09-23, In Review -> Ready, reviewer session 014h97RzPWPgkvbGFnB4B3Fx)

Verdict: rework, on question two and on the spec-body precondition. Question one clears, and the code
needs no change for this verdict. The rework is evidence and prose only.

**What was checked and holds, so the next round does not spend the passes again.** Implementation
commits `857f103`, `938869d`, `702d4b0`, `59e72d2` and `4110fea` all carry session
`01G4FWXJKpYYmmggLUQpsGB3`, so this reviewer is eligible. `mvn install -Plocal-db` passes on the
synced tree (17:57, `BUILD SUCCESS`). `meta_materialize`, `meta_materialize_dependency`,
`Materializations`, `MaterializeDependencies`, `RefreshProgress` and `DevMojo`'s session-start refresh
are gone, and no main or test source names any of them. All fifteen relations are `graphitron_<x>`
tables beside a `graphitron_<x>_rule` view, each with a primary key and a `meta_relation` row owned by
`graphitron`, the rule views included. Every one of the fifteen rule views, compared comment-stripped
against its `_live` text at `fc327fb` (rung 0 against `b4c5d00`) with only the renames normalised, is
unchanged, so no rule was restated. The relation tests under `model/intent` moved by rename alone,
with no expectation edited. `DerivationStratum.steps` is the ladder order, with
`UnlowerableOrderingRejectionRows` moved into it at the tail. `StageOrderGateTest` holds the roster
equal to the stratum, fails on a stage reading a later step's writes, and shows itself firing.
`StageAnswerAgreementTest` covers all fifteen plus R958's five in both directions, with a
non-vacuity case. `StagePrerequisiteStatisticsTest` and `StageProgressTest` replace their refresh-era
counterparts. The six re-ownings and four retained `derivation` relations match the shipped
paragraph. The docs are rewritten around stages: `fact-model.adoc`'s ownership and lever sections,
`dev-loop-internals.adoc`'s stuck-stage recipe, and the `store-performance` skill. The
convert-or-demote terms for all three candidates are in the commits (6 against 13, 9 against 19,
13 against 65). No delivered test asserts on generated method bodies.

**Finding 1, blocking (question two). The acceptance evidence this item names for its goal was not
taken, and neither the body nor the tree says so.** "Tests" lists, as the one piece of evidence "a
green build does not supply", the `sis` consumer's `graphitron:capture` pass timed before and after on
a copy of its store, warm and cold, written into the changelog entry beside the per-stage lines. No
such figures exist in the body, the commits or `roadmap/changelog.md`, and the shipped paragraphs do
not name the measurement as outstanding. The goal is mostly structural, and the tree demonstrates the
structural half. But the goal also states what "a consumer's `graphitron:dev` round and `generate`"
pay. On this arc the consumer is exactly where the structural reading and the cost have parted
before: R953 was withheld at this gate on the same ground, and the fact-model page carries a
consumer-scale cold cost, 6293 s against 90.8 s, that no fixture reproduced. What satisfies it,
either way:
- Take the reading: `graphitron:capture` on a `sis` store copy, warm and cold, before (at `fc327fb`)
  and after, with the per-stage lines, figures in the body and the changelog entry.
- Or, if no store copy is reachable from the implementing session, say so in "Tests" and narrow the
  goal's consumer clause to what the tree shows, as R953's rework did. The gate can then judge the
  narrowed goal on the evidence that exists.

*Resolved (2026-09-24), by the second arm.* The goal is narrowed to what the tree shows and says in
its own paragraph what it no longer claims. "Tests" lists the in-tree evidence for each remaining
claim, and records the `sis` reading as not taken, why it cannot be taken here, and what the narrowing
costs. The reading is filed as R972 rather than handed to another item's gate. Judging the narrowing
is the next gate's first job, and a reviewer who thinks the item should not close without the
reading should say so.

**Finding 2, blocking (approval precondition). The body does not reflect what shipped.** The two
"Shipped" paragraphs sit on top of an Implementation section that still reads as the plan, and parts
of that plan no longer describe the tree:
- "`MaterializeDependencies` ... is not deleted ... repointed rather than retired". It is deleted.
- "This is the one piece of `Materializations` that survives, renamed". Nothing survives.
- "The roster the analyse walks is ... `meta_relation` where `owner_name = 'graphitron'`". No such
  roster exists. The warm cadence relies on the whole-store `ANALYZE` `ModelCapture` already ran
  after the stratum, and the cold cadence analyses each step's declared writes.
- "`HAND_WRITTEN` becomes the stage order gate's write-set roster". The write sets live on
  `DerivationStratum.Step`.
- The `StoreRefresh` bullets.

The analyse substitution is the one design change with no record at all. It is a sound
simplification, since the tail `ANALYZE` covers every stratum table, but it is still a substitution
and belongs in the body. What satisfies it: collapse the phases to one-line "shipped at `<sha>`"
notes (the five SHAs above), state the substitutions in one paragraph (analyse loop dropped for the
existing tail `ANALYZE`; write sets on `DerivationStratum` rather than a repurposed `HAND_WRITTEN`;
`MaterializeDependencies` deleted, with `ViewReferences` supplying read sets), and name what remains:
Finding 1's reading.

*Resolved (2026-09-24).* "What is in scope", both "Shipped" paragraphs and "Implementation" are
replaced by one "Shipped" section: five landing notes with their SHAs, then a paragraph of
departures from the signed-off plan (`MaterializeDependencies` deleted; no analyse loop or roster;
write sets on `DerivationStratum.Step` and `HAND_WRITTEN` deleted; `StoreRefresh` gone before pickup;
rung 0 at the head; the node type in two keys; `UnlowerableOrderingRejectionRows` moved under R958).
The "Retired vocabulary" parentheticals that described a renamed analyse loop and a repurposed
`HAND_WRITTEN` are corrected, and "What this item does not do" and "Relation to other items" no
longer describe forks that are settled.

**Retirement sweep, non-blocking; fold into the same pass.** Main sources and docs are clean, apart
from historical past-tense accounts in DDL comments and `fact-model.adoc`, which are fine. Three
present-tense survivals of "the refresh" in the retired sense are in test javadoc:
`PartitionSelectivityTest` (class javadoc and the reopened-store case), `StoreStatistics`'s class
javadoc ("a refresh planning inside a transaction"), and `FactCaptureAgreementTest`'s `Arm#DERIVED`
item ("the materialized capture-cadence derivations"). `REGISTRATIONS` cannot graduate into
`RetiredVocabularyGuardTest`: it is a live constant with an unrelated meaning in
`RelationRegistrationGateTest`. About twenty other roadmap bodies still name the register, and a
few now have no subject, among them `materialize-dependency-derived-before-stamp`,
`target-index-exemptions-in-the-model` and `refresh-what-the-edit-touched`. They are other items'
to re-cut, but the changelog entry should name them so their next pickup starts from the right
premise.

*Resolved (2026-09-24).* The three test-javadoc survivals are reworded around stratum steps, and
`FactCaptureAgreementTest`'s "hand-written materialized derivation" with them. `REGISTRATIONS` is
named in "Retired vocabulary" as a term that cannot graduate. The subjectless roadmap bodies are
named under "Relation to other items" for their own items to re-cut.

### Response to Done gate round 1 (2026-09-24, session 014h97RzPWPgkvbGFnB4B3Fx)

The rework is authored by the session that withheld, at the user's request, with the Fable advisor
consulted on the shape. That session authored substantive edits to the body and a commit outside
`roadmap/`, so it is disqualified from the next `In Review -> Done` gate, as is
`01G4FWXJKpYYmmggLUQpsGB3`. The next gate needs a third session. No implementation code changed: the
only non-roadmap diff is test javadoc.

