---
id: R954
title: "A resolved @reference path is rows on disk every reader seeks into, not a recursive view re-walked once per driving row"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: [reference-step-arms-are-separate-keyed-relations]
created: 2026-09-16
last-updated: 2026-09-17
---

# A resolved @reference path is rows on disk every reader seeks into, not a recursive view re-walked once per driving row

## Goal

A consumer's build resolves each authored `@reference` path once per capture, written down by the
gatherer that owns it, and every reader afterwards seeks into stored rows on an index over the
coordinate it asks by. Today that resolution is a stack of SQL views held up by registrations: the
walk that chains the hops is a recursive view carrying window functions no outer predicate can prune,
two of its three departure sites name it where it is re-evaluated once per driving row, and beside it
in the same statement an anonymous derived table recounts the foreign keys between a table pair per
row. That makes both the `@nodeId` decode rule and every other reader of a walk grow as the square of
the store's size, which on the `sis` consumer is a `graphitron:dev` round that runs past twenty
minutes. Phase 3's measurement says what that twenty minutes is made of, and it is two things rather
than one: a statistics cliff on a single column, which is R953's to fix, and one relation that does
not return at all, `intent_field_reference_step_fanout`, which no statistic reaches because its cost
is the field walk being re-expanded eight times in one evaluation. When this lands, the field walk is
written by the `graphitron` gatherer into its own family once per capture, the register is smaller
rather than larger, and that relation answers in under a second instead of not answering. The decode
rule's own remaining per-row term is the input-field walk, which a successor stores; "What this plan
was conditioned on" carries that split and its reasoning.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A
*registration* is a row of `meta_materialize`, which keeps a rule in a view under a `_live` name and
moves the canonical name onto a table a refresh pass empties and refills; it exists to schedule
refreshes for rules that have no owner to schedule them. A *capture stage* is a step of a gatherer
that computes a relation and writes its rows with an `INSERT ... SELECT` of its own, of which
`GraphitronFactCapture.capture` runs ten, `FieldEndpoints.derive` being the eighth and
`FieldRoutines.derive` and `FieldTableLinks.derive` the two after it.

Two claims sit under that outcome and they are separable, because only one of them is measured.

**The cost claim.** With a walk stored and the foreign-key count stored, a rule that names them grows
linearly in the store's size where today it grows as its square. The growth half of that is still a
fixture reading; what a `sis` store measured on 2026-09-17 is the cost at one size, and phase 3
carries it. What this item delivers of that is the field walk and the foreign-key count: the field
walk is what makes `intent_field_reference_step_fanout` return at all and what takes
`intent_node_id_instruction_live` from 436 s to under a second in the regime that failed, and the
foreign-key count is 1.44 s of the decode rule's 4.124 s. The decode rule's linearity needs the
input-field walk stored as well, which is phase 5 and is the successor's.

**The modelling claim.** Nothing in this stack ever needed a register. Every relation under the three
walks bottoms out entirely in captured facts, so every one of them is a resolution whose owner is
known and whose inputs a gatherer holds. That is the top rung of the lever order in
`docs/architecture/explanation/fact-model.adoc`, and R876's thesis stated for one subtree. No
measurement moves it.

The cost claim is what makes this priority 1, and phase 3's measurement re-grounds it on something
narrower and harder than the refresh pass: on the `sis` consumer a lint's read of
`intent_field_reference_step_fanout` does not return, and the field walk this item stores is what
makes it return in 0.37 s. That read sits outside the refresh pass and outside the reach of R953's
statistics fix, so it is this item's cost claim and nobody else's. The modelling claim is what decides
the shape, and it is the one that survived the structural check below. Neither claim is about the
`@nodeId` decode rule any more, which the measurement moved to a successor and to R953 between them,
and the item is named for the walk rather than for that rule because the walk is what every reader of
it shares.

## The structural finding this plan is built on

Computed from the shipped DDL, so it is checkable rather than argued, and recomputed against trunk
`94e23bae4` on 2026-09-17 after this section was found to have been taken on a checkout predating two
commits of 2026-09-14. The transitive closure of the three walks through `intent_` relations holds
**26 relations, 8 of them registered**. Every one of the 26 bottoms out entirely in captured facts:
not one reads anything that is not ultimately `graphitron_`, `graphql_`, `sql_`, `store_` or `code_`.
There is no cross-family derivation anywhere in this subtree. It is all resolution, which is to say
matching what an author wrote against what the catalog holds, and capture holds both sides of every
one of them.

The method, so the next reader can re-take it rather than trust it: parse the DDL statement by
statement with line comments and string literals stripped, so all 396 relations are seen; resolve a
registered relation through its `_live` rule; follow only `intent_` names, collecting everything else
as a leaf. `code_condition_method` and `code_condition_method_parameter` are in the leaf set because
`intent_condition_method_route` moved onto them, and they are captured facts on the same terms as the
rest: `CodeCapture.capture` writes them, `ModelCapture.capture` is what runs it, and `FactCapture.capture`
writes no `code_` row at all, so those rows are on disk before the pass this item's stages sit in
begins. That is the same standing `store_` rows have here and it is what a stage needs.

`intent_spelled_table` is the clearest case and the bottom rung. Its whole rule is a three-way equi
join of `graphitron_spelled_reference_entry`, `store_graph_source` and `sql_table`, and capture has
already pre-folded the case into `name_part_upper` and `namespace_part_upper` so the match is an
indexable equality. Capture does most of that work and hands the last join to a view, which then
needs a register row to be affordable.

**What the registrations are holding up.** By the fact-model page's inlining rule, the one
`report-inline-multiplicity` implements, recounted over the shipped DDL at `94e23bae4`:
`intent_node_id_decode_hop_live` as shipped is a statement of size 12, and with the registrations
under it demoted to their rules it is **4448**. The field walk is 4 against 145, the input-field walk
4 against 655. So plan size is not what costs today, and the reason is that eight registrations are
truncating the tree. They are scaffolding under a subtree that is entirely resolution.

The three shipped figures are what the tool reports and are unchanged; the three demoted ones fell
from 4796, 163 and 709 with the recount, because the retired `intent_name_matched_key_pair` was a
plain view under the hop and its subtree left the closure with it. Stated rather than silently
replaced, since the same staleness is what the round-4 review found in the paragraph above.

**A registration already evaluates its rule exactly once per capture.** `Materializations.refresh`
issues one `DELETE` and one `INSERT ... SELECT` per registration per graph. So for the eight relations
that carry one, the rows are already on disk and a stage would not evaluate them fewer times. What
converting them buys is not fewer evaluations; it is the per-graph partition write, the register's
disappearance, and the seam below.

**The seam is three seams, and each has its own answer.** The rule a stage has to satisfy is one
rule, stated once: **a stage may not read a table a later step of the same pass writes**, whether
that table is a registered target, a hand-written producer's output, or an ordinary `graphitron_`
table a later stage of the same gatherer writes. `FactCapture.capture` runs
`GraphitronFactCapture.capture`, then the five hand-written producers
(`ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`, `ArgMappingCandidates.derive`,
`TypeBackingRows.derive`, `AuthoredClaimRejectionRows.derive`), and only then
`Materializations.refresh`. A stage that breaks the rule reads the previous capture's rows rather
than failing, which is what makes it worth stating as an invariant instead of leaving to care.

Against a registered target the answer is conversion order. A capture stage may read a plain view,
whose own inputs are current by the time the stage runs, and may not read a registered target. Taken
one relation at a time that blocks the move, and `meta_materialize`'s own `reason` for
`intent_node_id_decode_column_live` records it being blocked for exactly this reason. Taken bottom-up
it does not block anything: convert `intent_spelled_table` first, whose inputs are all captured, and
the hop's inputs are then all captured or plain views over captured facts, so the hop converts with no
seam, and so on up. Each conversion removes the seam for the one above it.

Against a hand-written target, conversion order does nothing and **placement** is the whole answer,
because this item converts none of the five producers and their tables are written where they are
written whatever the ladder does. The rungs split cleanly on it. Rungs 0 to 3 reach no hand-written
table at all, so they are stages of `GraphitronFactCapture` beside `FieldEndpoints.derive`. Rungs 4
and 5 reach three (`intent_input_occurrence_path`, `intent_input_occurrence_path_step` and
`intent_type_backing_class`, all on `MaterializeRegistryGateTest.HAND_WRITTEN`), so they go into the
derivation stratum after the five producers, the position `ArgMappingCandidates.derive` already
occupies. The phases below say this again where an implementer needs it.

Against a `graphitron_` table a later stage of the same gatherer writes, neither conversion order nor
the producer split says anything, and the answer is that this ladder reaches none of them. The
gatherer runs ten steps, of which `FieldEndpoints.derive` is the eighth: `FieldRoutines.derive`
writes `graphitron_field_routine` and `FieldTableLinks.derive` writes `graphitron_field_table_link`,
and those two tables are the whole of what any step after the eighth writes. Neither is named by any
view body in the DDL, so neither can be in any rung's closure, and a scan of the schema finds every
occurrence of both names inside their own `CREATE TABLE` and `COMMENT` block or in `meta_relation`.
What rungs 2 and 3 do reach in the gatherer's own family is eight `graphitron_` relations, and every
one of them is written at or before `navigation`, the seventh step: six are entry relations the
transcription writes and the flush inside `capture` publishes before the first stage runs
(`graphitron_argument_reference_step_entry`, `graphitron_field_reference_entry`,
`graphitron_field_reference_step_entry`, `graphitron_routine_entry`,
`graphitron_spelled_reference_entry`, `graphitron_table_entry`), `graphitron_field_chain_application`
is the first stage, and `graphitron_field_navigation` is the seventh. So the third seam is discharged
for this ladder by the same thing that discharges the other two, which is what an input reaches.

Still no ordering mechanism is minted, which is what the earlier reading of this item got wrong; what
the earlier reading also got wrong was thinking conversion order alone carried the whole invariant.

**A converted relation moves to `graphitron_`, and that is load-bearing rather than cosmetic.**
`MaterializeRegistryGateTest.nothingMaterializesOutsideTheMechanism` scans `intent_` base tables
only, and `HAND_WRITTEN`, the roster it checks them against, admits a relation on an impossibility
argument: "no view could state its rule". None of these relations can make that argument, the view
existing and being the oracle every conversion is proved against. A relation the gatherer writes
belongs to the gatherer's family by the ownership rule anyway, and in that family the gate does not
apply, exactly as it does not apply to `graphitron_field_table`. So renaming with the move is what
keeps the impossibility criterion meaningful instead of forcing this item to weaken it. R876 set the
precedent when `intent_argmapping_binding_leaf` became `graphitron_argmapping_match`, on the finding
that the `intent_` prefix there "recorded the default placement rather than a decision"; the
`graphitron_` family now holds 130 tables and 2 views, so neither shape is new.

**The target the conversions copy already exists.** `FieldEndpoints.derive` is the eighth of the ten
stages of `GraphitronFactCapture.capture`, it reads `graphitron_`, `graphql_` and `sql_` relations
across three families, it joins and ranks, and it writes `graphitron_field_table` with three
`INSERT ... SELECT` statements. It is not hand-rolled Java resolution and nothing here would be either: the rule stays
stated once, in SQL, and what changes is who runs it and when.

That precedent transfers to the ladder and to nothing outside it, which is worth stating because the
distinction is easy to lose. `meta_gatherer_corpus` defines a crawler as a gatherer carrying a corpus
row, whose rows about its own corpus "may not vary with any other corpus's contents". The graphitron
gatherer carries none and is thereby free to cross corpora, which is exactly the licence every rung of
this ladder needs and exactly what `FieldEndpoints` is already exercising. Phase 0 sits in a crawler
and therefore argues its own case rather than citing this one.

## What is in scope

The conversion ladder, bottom rung first. Each rung is convertible only once the rung below it is, and
each rung is independently landable and independently verifiable.

| rung | relation | today | reads, in `intent_` terms |
|---|---|---|---|
| 0 | `intent_spelled_table` | registered | nothing |
| 0 | `intent_condition_method_route` | plain view | nothing; reads `code_condition_method` and `code_condition_method_parameter` |
| 1 | `intent_field_reference_step_hop` | registered | the two rung-0 relations, plus `sql_name_matched_key_column` |
| 2 | `intent_resolved_type_binding` | registered | `intent_bound_table`, `intent_routine_return_binding` |
| 3 | `intent_field_reference_step_target` | plain view | rungs 1 and 2 |
| 4 | `intent_argument_scope_table`, `intent_field_scope_table`, `intent_carrier_data_field`, `intent_input_field_resolving_table` | all registered | a wider subtree of the column-scope family |
| 5 | `intent_input_field_reference_step_target`, `intent_argument_reference_step_target` | view, registered | rungs 1 and 4 |

**Rungs 4 and 5 are the successor's**, on the phase-3 measurement and the call under "What this plan
was conditioned on". They stay in this table and keep their phase text because the closure, the
placement argument and the convert-or-demote rule all transfer to the successor unchanged, and
re-deriving them there would be work already done.

Three things the table is saying that are easy to miss. The field walk needs only rungs 0 to 2, which
is **three registrations**, and its subtree is closed: `intent_bound_table`,
`intent_routine_return_binding`, `intent_field_chain_terminus`, `intent_field_chain_node`,
`intent_field_chain_start` and `intent_field_navigated_type` are all plain views a stage may read
inline. The other two walks need rung 4, which reaches into the column-scope family and is where this
item stops being small. And rung 1's third input is a captured `sql_` table rather than a view:
`sql_name_matched_key_column`, written by `NameMatchedKeys.derive`, which `FactCapture.capture` calls
after the two crawlers and before both `SdlFactCapture` and `GraphitronFactCapture`, so it is current
for a stage at rung 1 with nothing to arrange. A `sql_` table is a stronger base for the bottom-up
argument than the plain view it replaced, and it is why rung 0 holds two relations rather than three.

Beside the ladder, and independent of all of it: `intent_node_id_decode_hop_live` inlines an anonymous
derived table over `sql_referential_constraint` counting the foreign keys connecting an ordered table
pair, on the inner side of a `LEFT JOIN`, so it is recounted per decode endpoint. It reads one catalog
relation, so its owner computes to `catalog`, and it needs no rung of the ladder at all.

**Convert or demote, per relation.** A rung does not have to become a stage. Where a registered
relation's only reader is the stage above it, demoting it to a plain view the stage evaluates inline
is the smaller change and costs one evaluation inside one statement, which is what the registration
was buying for many readers and is not buying for one. Where it has several readers, it converts.
`DerivedReadCostTest`'s reader counts decide this per relation rather than a rule in this body, and
the commit records which way each went and why.

**Two relations arrive decomposed, which is R956's and is this item's one dependency, and no gate
changes.** The hop and the field walk each state four arms discriminated by a `via` literal with two
different natural keys between them, which is why neither carries a primary key today and why neither
could carry one as written; a conversion of either would be the first declared keyless base table in
the tree. R956 splits each into a keyed table per key shape under a view carrying the existing name,
and this item then converts what it finds. "The key gate, and the arm this item takes" under
Implementation derives that and records the three arms declined, one of which was a gate widening this
item drafted and withdrew. Nothing here edits a gate:
`nothingMaterializesOutsideTheMechanism` and `HAND_WRITTEN`'s impossibility criterion are left exactly
as they stand, which was the point of moving family in the first place, and
`aDeclaredTableKeyMatchesItsGrain` is satisfied rather than widened.

## Implementation

Five phases. The first is independent of the ladder entirely. After that each phase is one rung or
two, lands on its own, and leaves the tree green and the register smaller or the same.

### What every converting stage does, stated once so no phase has to restate it

Three obligations, in the order an implementer meets them. Each is a consequence of the conversion
rather than a policy this item invents, and each was found by a review round reading where the
conversion lands rather than what it removes.

**A stage reconciles; it does not append.** The statement is a graph-scoped `DELETE` and then the
`INSERT ... SELECT`, in that order, per graph:

```
DELETE FROM graphitron_<x> WHERE graph_name = ?;
INSERT INTO graphitron_<x> <the rule's text, seed filtered to that graph>;
```

That is exactly what `Materializations.refreshPartition` issues for a registered target today, run by
the owner instead of by the register, and it is the whole of what a conversion changes about
reconciliation: who runs it and when, not what it is. The shipped precedent is
`ArgMappingCandidates.derive`, which opens `dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName))` and
then inserts, on a target carrying no `touched_at`. It is not `FieldEndpoints.derive`, which this body
cites as the precedent for a stage's *shape*: that one reconciles by `onDuplicateKeyUpdate` on a
primary key and then sweeps `WHERE graph_name = ? AND touched_at <> ?`, and both halves need what the
ladder's own relations lack. Take the shape from `FieldEndpoints` and the reconciliation from
`ArgMappingCandidates`; the phases below say `INSERT` for brevity and mean both statements.

Why this is mandatory per rung rather than good practice. The store persists across `graphitron:dev`
rounds, so a second capture of the same graph is the ordinary case rather than an edge one. Without
the `DELETE`, a keyed rung fails loudly on a primary-key violation and a keyless one fails silently by
doubling the relation, which every reader above it then fans out on. The silent half is the one that
would ship.

**A converted relation owes a `meta_relation` declaration.**
`MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks` pins the relations carrying no declaration,
no registration naming them as a source view and no `meta_stated_relation` row to a frozen roster in
`undeclared-relations.txt`, and its own message states the ratchet: a missing entry "is a new relation
that must be declared rather than added to the roster". All five relations the ladder touches stand on
that roster today under their `intent_` names. A conversion retires the `intent_` name and introduces a
`graphitron_` one, and of the roster's three doors the new name can take none: the roster is frozen,
the registration's source view is the thing this item exists to retire, and `meta_stated_relation` is
for relations whose rows this DDL file itself supplies. So the declaration is owed by every rung, not
by whichever rung happens to want one.

That is a well-trodden path and not a new burden. Sixteen `graphitron_`-owned relations are declared
today, `graphitron_field_table` and `graphitron_argmapping_candidate` among them. The corpus half of
the obligation is vacuous here: `MetaDeclarationGateTest`'s corpus check skips an owner with no
`meta_gatherer_corpus` row, and the graphitron gatherer carries none, which is the same fact the
placement argument above leans on from the other side. What each rung owes concretely is a roster line
removed, a `meta_relation` row added, a `meta_grain` row where the grain is new, and the `_live` view
deleted where there was one. The roster observes views as well as base tables, so a decomposed rung
owes three declarations rather than one: each keyed arm table and the union view over them, which R956
carries. `intent_condition_method_route` converts nothing, stays a plain view and stays on the roster.

**A declared base table meets the key gate, and two of the four cannot satisfy it as written.** That
is the next subsection, because the answer is a design call rather than a step.

### The key gate, and the arm this item takes

`MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain` requires every declared `BASE TABLE`'s
primary-key shape to equal its grain's `key_shape`. `meta_grain.key_shape` is `NOT NULL` with
`CHECK (CHAR_LENGTH(key_shape) >= 1)` and the gate builds its comparison map from
`INFORMATION_SCHEMA`, so a table with no primary key has no entry and offends any declared shape. Its
message says so: "an unkeyed declared table owes a key before it owes anything else". Of the shipped
tree's 270 base tables, 17 carry no primary key and every one of the 17 is an `intent_` registration
target standing on the frozen roster. So there is no keyless declared table to lean on, and the
absence is not an oversight: a registration target is exempt from both gates for one reason, that it
is on the roster, and the conversion is precisely what removes that standing.

The four conversions split three ways, and the split is the grain's rather than a preference.

`intent_spelled_table` is already keyed on all five of its identity columns and converts with nothing
owed but the declaration.

`intent_resolved_type_binding` **takes a real primary key with no change to its rule and no column
made non-nullable**, which an earlier reading of this fork got wrong by counting it among the
unkeyable. All six of its columns are `NOT NULL` today, and its rule is a `COUNT(*) OVER (PARTITION BY
graph_name, type_name)` over a `UNION` of `intent_bound_table` and `intent_routine_return_binding`. The
`UNION` dedupes, so `(graph_name, type_name, table_source_name, table_schema, table_name)` is unique by
construction and `candidates` is a payload the partition determines. Declare that as the grain and the
gate is satisfied outright.

`intent_field_reference_step_hop` and `intent_field_reference_step_target` are the two that cannot be
keyed as written, and following why says what to do about it. Their rule is a `UNION ALL` of four arms
discriminated by a `via` literal, and on the `NAME_MATCH` and `CONDITION` arms `key_matched_by`,
`constraint_name` and `fk_on_from` are projected as literal `NULL`, because a name-matched hop joins on
no foreign key and a condition hop joins on an authored predicate. The shipped column comments state
each of those three absences as the fact it is.

That is not a relation with three optional columns. **The arms have two different natural keys, which
is the whole reason no key exists.** On the `KEY` and `TABLE` arms `constraint_name` and `fk_on_from`
are identity, both orientations of every foreign key connecting the pair being separate rows; on the
`NAME_MATCH` and `CONDITION` arms there is no key to enumerate, so the coordinate with the departing
and arriving triples is already total. One relation cannot carry two key shapes, and a projected
literal `NULL` is padding that makes four row shapes share one column list rather than a fact about a
hop. The subject is the fact-model page's own worked anti-example: a `@reference` path element that
"needed nine nullable columns and a legality rule no constraint could state" became three relations,
one per assertion. `intent_condition_method_route` and its `_defect` sibling are the shipped instance
of the same move.

**The arm: the relations decompose on `via`, no gate changes, and the decomposition is R956 rather than
this item.** Each becomes two keyed base tables and a view unioning them under the name every reader
already spells: one table for the foreign-key arms, keyed on the coordinate, both table triples,
`constraint_name` and `fk_on_from`; one for the keyless arms, keyed on the coordinate and both triples,
which is already total there. Both `NOT NULL` throughout, each carrying a `CHECK` on the `via` values it
admits. `aDeclaredTableKeyMatchesItsGrain` then clears untouched, because every declared base table
carries a real primary key and the key gate does not read views, and
`theUndeclaredRosterOnlyShrinks` is satisfied by declaring each arm table at the grain it actually has.
R956 carries the diagnosis, the shape and the evidence it owes; this body states it, depends on it and
does not perform it.

Two things that buys beyond clearing the gate, which is why it is the arm rather than the concession.
It refuses a duplicate row, which R876's burn-down records that nothing in these targets does today and
which that item names a stage-written table as the moment to fix; the `DELETE` above covers
reconciliation and a key covers the rest. And it states each arm's legality in constraints rather than
in prose, which the tree already ships the exemplar of: `graphitron_field_table_link` is a stage-written
`graphitron_` table over the same `via` vocabulary, carrying a primary key beside `CHECK
((constraint_name IS NULL) = (fk_on_from IS NULL))`, `CHECK (constraint_name IS NOT NULL OR via <>
'KEY')` and `CHECK (key_matched_by IS NULL OR via = 'KEY')`. Why that table is keyed where the hop is
not, since it is the nearest thing to a counter-case: its arm-conditional columns are payload, one link
per position per target, where the hop's are identity, many candidate routes per position. That
asymmetry is the whole problem in one sentence.

**Why it is a separate item and not two more paragraphs here.** The walk's recursive term joins the hop
once per accumulated row and `ix_field_reference_step_hop_step` is what makes that a seek, 18308 scans
against 523 by its own comment. Under a union view the planner has to push that eight-column seek into
both arm tables. H2 usually does; usually is not good enough for the figure this item's cost claim
rests on, so the decomposition owes that re-measurement as its own acceptance evidence, and owing a
measurement is what makes it an item rather than a paragraph. This body writes no figure for it,
because none has been taken.

**The fallback if R956 does not land, which this body already licenses per rung.** The rung takes the
registration instead: the rows land on the same disk, the `intent_` name and the frozen roster line
stay, both gates clear with no change, and `intent_field_reference_step_fanout` still answers in
0.37 s, because what fixes it is the walk being a table rather than who wrote it. That is "Other
solutions we've considered"'s cheap fallback applied per rung, and this body's own "Convert or demote,
per relation" rule already says taking it and recording why is a legitimate outcome rather than a
failure. What it costs is the per-graph partition write and the modelling claim for that one relation,
and where the walk is concerned it grows the register by a row. It is a smaller concession than being
the item that puts the first keyless table through a gate whose message forbids it.

Three arms were weighed and declined, recorded so the call is not re-litigated. **Manufacturing a key**
needs a sentinel standing for "no constraint", which makes one column mean two things and is the
denormalisation phase 0 declines by name a few paragraphs down. **Keeping one relation and keying a
narrow prefix beside the nullable columns** does not work: the nullable columns are what tells two
routes apart, so no prefix of them is unique. And **widening the key gate to admit a declared keyless
table with a stated reason** was drafted and withdrawn, for reasons worth recording because it is the
arm a reader would reach for. `meta_grain.key_shape`'s own comment is that "a declared base table at
this grain carries exactly this primary key (gated)", so an aspirational `key_shape` on the exceptions
makes one column mean two things. The three exception rosters this project treats as normal each admit
on something harder than an author's argument: `undeclared-relations.txt` is frozen and only shrinks,
`HAND_WRITTEN` admits on an impossibility criterion this item refuses to weaken by name, and `NO_INDEX`
carries a measurement per entry. "The grain names a column the table declares nullable" is none of
those three and would admit every future relation that nominates one. And an index is not a key: it
would have traded duplicate refusal away for a seek and called it a buy-back. The honest reading is
that the gate was right and the relation was wrong, which is what the decomposition acts on.

**This is a shared answer, and the sibling item needs it too.** R955's "What each stage owes on
landing" takes the same first step, a `meta_relation` row on the same roster-ratchet argument, and then
says "a primary key where the grain admits one" with an index and a stated reason where a meaningfully
nullable column is in the grain, leaning on `everyTargetIsIndexedOrStatesWhyNot`. That is the same
graded rule and this body adopts it rather than inventing a second one. What it does not do is clear
the key gate, which R955 never names: twelve of its fifteen carry no primary key today by its own
table, and the gate it leans on stops covering a relation the moment the registration is retired, while
`aDeclaredTableKeyMatchesItsGrain` starts covering it the moment it is declared. R955's own last commit
deletes `MaterializeRegistryGateTest` outright. So the pincer is identical there and larger, and this
item takes the gate change because it lands first and because four relations is a cheaper place to get
the shape right than fifteen. "Relation to other items" carries the hand-off.

### Phase 0: the foreign-key count becomes a captured catalog fact

Independent of everything below, the top rung of the lever order, and admitted whether or not a reader
is currently slow.

```sql
CREATE TABLE sql_table_reference (
  source_name            VARCHAR NOT NULL,
  table_schema           VARCHAR NOT NULL,
  table_name             VARCHAR NOT NULL,
  referenced_source_name VARCHAR NOT NULL,
  referenced_schema      VARCHAR NOT NULL,
  referenced_table       VARCHAR NOT NULL,
  constraints            INT     NOT NULL,
  touched_at             TIMESTAMP NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name,
               referenced_source_name, referenced_schema, referenced_table)
);
```

Grain: one ordered pair of tables at least one foreign key connects, and how many connect it. It admits
a real primary key, which nothing on the ladder does. `intent_node_id_decode_hop_live`'s
`DISCOVERED_KEY` arm joins it on the six columns it already holds and tests `constraints = 1`.

`sql_` and not `intent_` because the relation reads one family, which by the family rule makes it a
relation of that family whatever shape it takes.

**The gatherer is `jooq`, not `catalog`.** `CatalogFactCapture` writes no `sql_` row: its javadoc is
"The `jvm_` family", it records that "It used to fill the `sql_` family too, and that half is gone",
and its `capture` calls `captureExtensions` alone. `JooqFactCapture` is "the `sql_` family and nothing
else", and its `referentialConstraints` stage writes the rows this relation counts. So the stage is
`JooqFactCapture`'s, added after `referentialConstraints` and before the sweep.

**The corpus-purity argument is made directly rather than by precedent.** `meta_gatherer_corpus`
defines a crawler as a gatherer carrying a corpus row, "a transcription pass whose rows about its own
corpus may not vary with any other corpus's contents", and `jooq` carries one. So the
`FieldEndpoints` precedent does not transfer here: that is the graphitron gatherer, which carries no
corpus row and is thereby free to cross corpora. The argument this relation needs is its own and is
stronger: it reads `sql_referential_constraint` and nothing else, so its rows vary with the catalog
corpus alone and the invariant holds by construction.

**The declared owner is `jooq`, and this spec settles that rather than leaving it to the gate.** The
relation needs a `meta_relation` row, that roster only shrinking. Fifteen `sql_` relations are
declared today and every one of them names `catalog` as owner, while `JooqFactCapture` writes fourteen
of them and the `jooq` gatherer owns no declared relation at all. So the tree's declared ownership and
its actual writer disagree for fourteen of fifteen. Declaring `catalog` would entrench a mismatch no
gate currently catches; declaring `jooq` makes this the first correct row for a jooq-written relation
and leaves fourteen beside it that are not. Take `jooq`, and file the fourteen as a Backlog item
rather than correcting them here: they are a pre-existing declaration defect this item happens to
surface, and reconciling them is R877's kind of work.

The fifteenth is worth naming rather than folding into the count, because it is a live counter-case
and it postdates this section's first draft. `sql_name_matched_key_column` declares `catalog` and is
written by `NameMatchedKeys.derive`, which `FactCapture.capture`'s own comment calls "A stage of the
catalog gatherer rather than a derivation", so for that one relation the declaration is right. It
establishes that a `sql_` relation may legitimately be `catalog`-owned, which is exactly why the
argument here has to be about the writer rather than about the family: `sql_table_reference` is
`JooqFactCapture`'s stage, so it is `jooq`'s, and no appeal to what the family usually declares
settles it either way.

**The lifecycle is the family's stamp-and-sweep, so the table carries `touched_at`.** Fourteen of the
sixteen `sql_` tables carry one, `sql_referential_constraint.touched_at`'s comment states the rule the
sweep implements, and `JooqFactCapture.capture` closes with `sweep(dsl, sourceNames(tables),
touchedAt)`. The stage takes the same instant, and the sweep covers the new table once it is added to
`TABLES_TO_SWEEP`, the fixed fourteen-entry list in `JooqFactCapture` that `sweep` iterates: one line,
named here so the edit is not discovered at implementation. This is not tidiness: a pair row that
outlives the foreign key it counted says `constraints = 1` about a pair with none, which is exactly
the arm the decode rule's `LEFT JOIN` reads. The two tables without a stamp are
`sql_table_record_supertype` and `sql_name_matched_key_column`, and neither is a precedent this
relation can take, for different reasons. `NameMatchedKeys.derive` clears its relation whole before
refilling it, which is available to a derivation over the store and not to a reading of a consumer's
database. `JooqFactCapture.recordSupertypes` neither stamps nor clears: every column of that relation
is a key column, so a row that is still true is the row already there and there is nothing to
reconcile. `sql_table_reference` is on neither arm, carrying a `constraints` count that is exactly the
non-key column a stale row would lie about.

**The reach for the constraint name.** After the change the `DISCOVERED_KEY` arm still joins
`sql_referential_constraint` on six columns, of which only the first three prefix its primary key
`(source_name, table_schema, table_name, constraint_name)`, and that relation carries no other index.
Measure whether the join wants one at the same time as the phase-0 timing; a three-column prefix seek
on a relation of this size very likely does not, and the commit records the reading either way rather
than the expectation.

Deliberately not a `constraints` column on `sql_referential_constraint`, which would repeat one value
down every constraint of a pair, the denormalisation the referenced-side discipline declines.

### Phase 1: the departure, and the hop all three walks read

Rung 0 then rung 1, in one phase because rung 0 buys nothing on its own.

`intent_spelled_table` becomes `graphitron_spelled_table`, written by a stage of the graphitron
gatherer immediately before `FieldEndpoints.derive`, which is the eighth of that gatherer's ten
stages and not the last. Its registration is retired. Its `_live` view is
deleted, the rule moving into the stage's `DELETE` and `INSERT ... SELECT` unchanged. It keeps the
primary key it already carries on all five identity columns, so it is the one rung the key gate costs
nothing.

`intent_field_reference_step_hop` becomes `graphitron_field_reference_step_hop`, written by the stage
after it. Its whole input list, checked against the shipped rule rather than assumed:
`graphitron_field_reference_step_entry`, `store_graph_source`, `sql_table`, `sql_constraint`,
`sql_referential_constraint`, `sql_name_matched_key_column`, `graphitron_spelled_table` (rung 0, just
written) and `intent_condition_method_route` (rung 0, a plain view read inline). Every one is a
captured fact or a plain view over captured facts by the time the stage runs, the `sql_` table among
them because `NameMatchedKeys.derive` runs earlier in the same pass. Its registration is retired. It
keeps `ix_field_reference_step_hop_step`, whose comment already prices what that index removes:
reading the field walk whole costs 18308 scans without it and 523 with it, on a copy per arm table.

**The hop arrives already split and keyed, which is R956's, and this phase converts what it finds.**
Per the key-gate subsection above, the hop is a discriminated union of four arms with two natural keys
between them, so R956 decomposes it into a keyed table per key shape under a view carrying the existing
name; this phase then writes each of those two tables with a stage of its own and retires the
registration. The two stages are the two arms of today's `UNION ALL` taken apart, so the rule's text is
unchanged rather than rewritten, which is what keeps the `EXCEPT` oracle comparing two evaluations of
one text. The declaration each arm table owes is the ordinary one, and it needs no gate to move. If
R956 has not landed when this phase is picked up, the rung takes the registration fallback the key-gate
subsection states and the commit records that it did.

Two registrations retired, none added, and the hop is the relation *all three* walks read, so this
phase is the one that moves the most for the least.

**Where this phase's stages run, and why that position is current for every input.** Inside
`GraphitronFactCapture.capture`, immediately before `FieldEndpoints.derive`, which is where a
`graphitron_` relation's writer belongs by the family rule and where the precedent this phase copies
already sits. The position is admissible because rungs 0 and 1 reach no table a later step of the pass
writes, on all three of the invariant's cases: no registered target, by the conversion order above; no
`HAND_WRITTEN` table, the field walk's closure holding none of the six; and none of the two tables the
gatherer's ninth and tenth stages write. That is the same invariant a sibling item states for its own
fifteen relations and it is why those land in the derivation stratum and these do not; the criterion is
identical, and it is what an input reaches that decides where a stage goes, not which item the stage
belongs to.

**The position is before `FieldEndpoints.derive` rather than at the end of the gatherer, and now that
those are two different lines the choice is made rather than inherited.** Before, for three reasons.
The invariant is forward-only, so what a position has to clear is what runs *after* it, and every
input rungs 0 to 3 reach is written at or before `navigation`, the seventh step; the eighth position
clears them all with two stages to spare. Putting the stages at the end instead would assert a
dependency on `FieldRoutines.derive` and `FieldTableLinks.derive` that does not exist, and a later
reader would have to re-derive that it does not. And it is the smaller edit against the tree as it
stands, which matters because the position is stated in a comment as well as in an order and a wrong
comment is what produced this correction. The end of the gatherer is equally admissible on the
closures, and an implementer who finds the shipped order changed again takes whichever line satisfies
the three cases and says which it took.

### Phase 2: the field walk

`intent_resolved_type_binding` converts or demotes per the rule above; eight view bodies and `StoreNodeTables` name it, so
converting is the expectation and the commit says which way the count sent it. On conversion it gains
the primary key the key-gate subsection above derives, `(graph_name, type_name, table_source_name,
table_schema, table_name)`, which needs no column made non-nullable and no change to the rule.

`intent_field_reference_step_target` becomes `graphitron_field_reference_step_target`, written by a
stage as a graph-scoped `DELETE` and then **one insert per graph**: `INSERT INTO
graphitron_field_reference_step_target WITH RECURSIVE chain AS (...) SELECT ..., MAX(target_rank) OVER
(...), COUNT(*) OVER (...) FROM (...) ranked`, with the seed filtered to the graph. The view text moves
into the stage exactly as phase 1's does, and the `EXCEPT` oracle then compares two evaluations of one
text rather than two texts. The `DELETE` is what an earlier draft of this paragraph left out, and on a
keyless target its absence is silent: see the subsection above for why it is per rung rather than per
taste.

The earlier drafts of this phase proposed a Java fold instead, one insert per position with a
termination bound and an assertion, on the reading that the fold would remove "two window functions
inside a recursive term". That reading was wrong about the shipped rule and the correction is the
reason this phase changed shape. In `intent_field_reference_step_target` as shipped, the recursive
term is a plain `UNION` of the seed with one join to `intent_field_reference_step_hop` on the eight
columns `ix_field_reference_step_hop_step` serves; `DENSE_RANK`, `MAX(target_rank) OVER` and `COUNT(*)
OVER` are all in the outer `SELECT` over the finished `chain`. So the fold would have removed nothing
that is there, and it would have restated in Java a rule this tree states in SQL, against this item's
own "the rule stays stated once, in SQL". Three further reasons it goes: the walk standalone is
milliseconds by this body's own table, so the cost was never the recursion but the per-driving-row
re-evaluation, which any once-per-capture write removes; the termination assertion is unnecessary,
`UNION` reaching a fixpoint and `position` strictly increasing; and the one precedent for a Java
fixpoint loop in this tree, `ClassificationDomainCapture`, is a `HAND_WRITTEN` producer admitted on
the argument that no view could state its rule, which is a licence a rule stated by a shipped view
cannot borrow. H2 2.4.240, the pinned version, accepts the single-statement form; the round-2 review
checked it on the jar in the local repository with a seed filtered to one graph, and the implementer
re-checks rather than taking it from here.

**The walk arrives split too, on the hop's shape and for the hop's reason**, and R956 carries that half
as well: its `constraint_name` and `fk_on_from` are identity on two arms and absent on the other two,
so one relation would carry two key shapes, with `targets` and `candidates` as payload either way.
Earlier drafts of this paragraph said "indexed and not keyed" and gave H2's refusal of a key over a
nullable column as the reason; that is a statement about an engine and could never have been the reason
for a modelling decision, which is the correction the round-6 review's finding forced.

This phase therefore writes two inserts per graph rather than one, each carrying the same `WITH
RECURSIVE chain` and the same ranking over it and differing only in a closing arm filter, so the rule's
text is still one text and the `EXCEPT` oracle still compares two evaluations of it. That evaluates the
walk twice per graph, which phase 3 priced standalone at 0.08 s, so the second evaluation is under a
tenth of a second once per capture; an implementer who wants one may stage the ranked chain and split
out of it, and says which they did. Each arm table keeps a coordinate index shaped like
`ix_argument_reference_step_target_coordinate` with a `COMMENT ON INDEX` naming its reader, which is
what the three readers holding the element coordinate seek on.

**What this phase reaches in `intent_node_id_instruction_live`, and what it does not.** That rule is
the 592 s statement R953 measured and it does read the field walk, but not once per driving row
directly: it reads it inside a CTE named `slot_table`, and `slot_table` is derived from the
`instructed` CTE and then joined back to `instructed` in the union arm that produces the
`TARGET_TABLE_NODE_TYPE` instruction. H2 inlines a non-recursive `WITH` exactly like a view and
eliminates no common subexpression, so `slot_table` is recomputed once per `instructed` row with the
walk inside it. Storing the walk removes the recursion from the inside of that loop. It does not
remove the loop. The relation's own `meta_materialize.reason` already says which of the two the fix
is: it records that "snapshotting the inner alias into a table put the arm at 0.7 s, which is the
shape of the fix and the reason this is a registration rather than a rewrite", and that "The narrower
registration that would cut this one is the inner alias, which is a local alias rather than a named
relation today and wants promoting to one before it can be registered." So this phase is a term of
that rule's cost and the honest claim is a term, not the whole. Promoting the alias is a separate
change this item does not take, named under "What this item does not do".

Phase 3's measurement quantifies both halves of that and does not flip it. In the regime the failing
round ran in, storing the walk alone takes that rule from 436.1 s to 0.91 s and promoting the alias
on top takes it to 0.06 s, both single executions. On an analysed store the same pair reads 2.582 s
as shipped, 1.100 s with the walk stored and 0.080 s with the alias promoted. So the loop is real and
the promotion is worth a further order of magnitude, and it is an order of magnitude of a second
rather than of the failure.

**What this phase also fixes, named as a beneficiary rather than taken into scope.**
`intent_field_reference_step_fanout` is an unregistered plain view whose `pair` CTE self-joins this
walk and is named four times downstream, so one evaluation expands the walk eight times. On the `sis`
store it does not return: a lint's read of it in the live round ran for sixteen minutes without
answering and a probe of it did not answer inside a 30-minute cap. With this walk stored it answers in
0.37 s for 60 rows. Phase 3 carries the figures and the caveats. The view is not edited, its own
`pair` alias is not promoted, and nothing about it is filed: with the walk stored there is a 0.28 s
residue left and that is not worth an item. Stated here so the phase's value is not read off the
decode rule alone.

### Phase 3: the acceptance measurement, taken on 2026-09-17

No code. This phase was the acceptance measurement for phases 0 to 2 on a `sis` store copy and the
decision point for whether phases 4 and 5 are this item's or a successor's. **It has been taken, and
what follows is what it found.** The phase is therefore discharged at Spec time rather than at
pickup; what an implementer still owes is the same configurations re-taken on the shipped tree per
phase, which "Tests" already asks for. The rule this phase was filed with stands and is what the
scope call below applies: if the round is in seconds here, the remaining rungs are a modelling tidy
rather than a fix and say so in their own priority.

**Method, stated first because two readings in this family have been wrong before.** Per the
`store-performance` skill: per-relation timing before any hypothesis, body bisection, a control on
the same fixture before believing anything, and `EXCEPT` in both directions before any figure from a
substituted relation is used. Instrument: single-file Java over JDBC against a private copy of a real
`sis` consumer store, `SET OPTIMIZE_REUSE_RESULTS FALSE`, timings read out of
`INFORMATION_SCHEMA.QUERY_STATISTICS`, sweeps interleaved. No profiler, no thread dump, no reactor
wall-clock. Two stores: an archived capture, and one captured by trunk `94e23bae4` with the refresh
committed and all 23 targets analysed. Every view this body's figures touch was dumped out of the
trunk store and compared against a fresh H2 built from trunk's DDL, and they match, three of them
byte for byte and the rest with identical conjunct sets rendered in a different predicate order. The
box was loaded throughout, so the ratios are trustworthy and the second-scale standard deviations are
inflated.

**The live round, from its own log and no instrument at all.** The refresh pass took 2915.7 s.
`intent_node_id_decode_hop` took 2536.0 s to insert 377 rows and `intent_node_id_instruction` 356.5 s
to insert 683. That is 2892.5 s of 2915.7 s, **99.2% of the pass in two registrations**. The other 21
took 23.2 s between them and inserted 29413 rows, none of them costing more than 6.3 s.

**A statistics cliff dominates the pass, and it is R953's subject rather than this item's.** Three
states of `intent_field_reference_step_hop`'s statistics, each on its own copy of the store and each
in its own JVM so no plan survives the change, timing `intent_node_id_instruction_live`: as captured
and analysed, 3.54 s over five sweeps; with hop's fifteen columns at `SELECTIVITY 50`, which is the
regime the failing round ran in, 626.9 s over three sweeps (474.3, 603.2 and 803.2 s, rising as other
runs joined the box, so read 470 to 800 s as the band); and with `graph_name` alone put back to
`SELECTIVITY 1` and every other hop column left at 50, **4.65 s**. That is **135x on one column**. The
same pair on `intent_node_id_decode_hop_live` is 4.04 s analysed, 2447.6 s de-analysed (**a single
execution**, so it ranks rather than measures) and 7.76 s with `graph_name` restored: **315x**. The
de-analysed figure agrees within 4% with the failing round's own 2536.0 s. De-analysing the entire
database instead, 2129 columns, lands on 631.9 s (a single execution), the same number as
de-analysing hop's fifteen, so nothing else in the store contributes.

The mechanism is visible in the round's log without any of that: hop was refilled at 10:11:10 and
**all 23 `ANALYZE TABLE` statements ran at 10:59:49, after the whole pass**. Every registration that
reads hop was refreshed while hop carried no statistics. This body states the relationship and does
not annex it: the cliff is a defect in the cost of *one* evaluation and is R953's to fix, and R953's
lever 2, a static `SELECTIVITY 1` declared in the DDL, is exactly the change state (c) above
measures. This item is about the *number* of evaluations. The two compose and neither substitutes for
the other.

**The shape terms, on the analysed trunk store, five interleaved sweeps each.**
`intent_node_id_decode_hop_live` as shipped is 4.124 s. Storing the foreign-key count alone takes it
to 2.688 s, a saving of 1.44 s and a factor of 1.5. Storing the input-field walk alone takes it to
1.466 s, a saving of 2.66 s and a factor of 2.8. Storing both, which is what phase 0 and phase 5 do
between them and so is not a figure this item delivers on its own, phase 5 being the successor's,
takes it to **0.098 s, a factor of 42**. Snapshot build cost is 0.06 s for the
foreign-key count and 0.08 s for the walk. Neither term alone is the fix and both together are,
because each sits on the inner side of a `LEFT JOIN` driven per endpoint row, so storing one leaves
the other still inlined and still driven. Two controls refute the obvious alternative: storing
`intent_node_id_decode_endpoint` alone is 4.108 s against the 4.124 s baseline, and adding it on top
of the foreign-key count is 2.982 s against 2.688 s for the count alone. **Every configuration was
proved answer-identical by `EXCEPT` in both directions**, fourteen counts across the decode-hop and
instruction configurations, all zero.

This confirms this body's own recorded ablation on a roughly ten times larger population than the
fixture table above. That ablation put the foreign-key count at about 1.4 s and both walks together
at about 3.6 s; here the count is **1.44 s**, essentially to the tenth, and the one walk still
unstored is 2.66 s, the argument-site walk having been registered since and now contributing nothing.

**The discriminating measurement, in the regime that actually failed.** On a copy of the trunk store
with hop's fifteen columns at `SELECTIVITY 50` and the probe tables analysed so they model registered
targets. **All four figures are single executions and rank rather than measure**; the ratios are 100x
to 1000x and nothing about the regime is subtle. `intent_node_id_instruction_live` as shipped is
**436.1 s**; with the field walk stored and `slot_table` left a CTE, **0.91 s**; with `slot_table`
promoted as well, 0.06 s. `intent_node_id_decode_hop_live` with the input-field walk stored is
**1.45 s**, and with the foreign-key table stored too, 0.11 s. Decode-hop as shipped was not re-run
in this regime on the trunk store; the comparison for it is against 2447.6 s on the archived store
and 2536.0 s in the failing round's log, and that is a number from a different store.

So the answer to whether promoting `slot_table` is a requirement or an optimisation is: an
optimisation. Storing the walk alone takes that rule from 436 s to 0.91 s in the regime that failed.
On the analysed store the same pair reads the other way round, 2.582 s as shipped to 1.100 s with the
walk stored and 0.080 s with the alias promoted, which on its own would say the loop is the term. The
de-analysed pair is the one that matters, because the rule was never slow on an analysed store.

**Phase 2 fixes a relation this item never mentions, and that is the finding to weigh most.**
`intent_field_reference_step_fanout` is a plain view with no registration, so no refresh progress line
ever times it and it does not appear in the 2915.7 s pass at all. Its `pair` CTE self-joins
`intent_field_reference_step_target` and `pair` is named four times downstream, so one evaluation
expands the recursive walk eight times. In the live round a lint read it, `ReferencePathFanout`
issuing its query at 10:59:55.797, and it had not returned when the build was stopped at 11:16:37.573,
sixteen minutes and forty-two seconds later. **`intent_field_reference_step_hop` had been analysed at
10:59:49.554, six seconds before that read began**, so this cost is pure shape and the statistics
cliff does not explain it. Probed on its own it did not return inside a 30-minute cap (a single
execution against its own store copy, killed at the cap, so a lower bound and not a figure). With the
field walk stored it answers in **0.37 s for 60 rows**, and storing `pair` on top of that buys a
further 0.28 s.

Answer preservation there is the weaker proof and is stated as such. `EXCEPT` runs against the live
view, which is the thing that does not return, so that proof is unavailable by construction. What was
proved instead is that the substituted relation is row-identical to the one it replaces,
`intent_field_reference_step_target EXCEPT probe_step_target` and the reverse both returning zero rows
over 276 rows each, and that the two configurations differ only by that substitution. That is
equivalence by substitution, not by comparing the two answers.

**A structural finding that touches the rung table.** `intent_field_reference_step_target` appears
nowhere in `intent_node_id_decode_hop_live`'s reference closure on trunk: that view names exactly
`intent_node_id_decode_endpoint`, `intent_argument_reference_step_target`,
`intent_input_field_reference_step_target` and `sql_referential_constraint`, and the endpoint view
names `intent_node_id_instruction`, `intent_argument_scope_table`, `intent_input_occurrence_path` and
`graphitron_tabletype`, all stored. The field walk left that path when the argument walk was
registered, and the one unstored walk there is the input-field one. The configuration that stored the
field walk against that rule measured 4.104 s against a 4.124 s baseline, which is the no-op it has to
be and is reported as the instrument control it is rather than as a result.

**A population caveat that makes every shape figure conservative, and which every reader of them has
to carry.** On the trunk store `code_condition_method` and `code_condition_method_parameter` hold zero
rows while the JVM census is full (2486 classes, 13549 methods) and identical to the archived store's.
So `intent_condition_method_route` is 0 rows there against 82 on the archived store, hop loses its
whole `via = 'CONDITION'` arm (10776 rows against 11183, the `KEY` and `TABLE` arms identical at 872
and 9904), and the field walk is 276 rows against 683. Every shape figure above is therefore taken on
a walk two and a half times smaller than a fully populated one, which makes the walk-storing wins
**conservative rather than optimistic**: a bigger walk is a bigger thing to stop re-evaluating. Stated
as an observation about that store. Whether an empty `code_condition_method` beside a full JVM census
is a capture gap on trunk is not this item's question and is worth someone checking separately.

**One precision the loose version gets wrong.** Storing a walk does not remove the statistics cliff;
it **moves** it, from N reader evaluations to one refresh evaluation. The refill still evaluates the
walk once, and in the failing regime it evaluates it against an unanalysed hop, so that one evaluation
still pays. The snapshots above were built on an analysed store, so the figures price the read and not
that refill. That is also why R953's lever 2 is not made redundant by this item: it is the only lever
that reaches the one evaluation a stage or a registration still pays.

**What the measurement could not settle**, listed so no later reader mistakes silence for a null
result. Growth across sizes was not measured; this is one population at one moment, and the growth
claim in the goal still rests on the fixture table above. `intent_node_id_decode_hop_live` as shipped
was not re-run de-analysed on the trunk store. Whether the foreign-key count still costs 1.44 s once
the walk is a stage-written table rather than an unindexed snapshot is unknown, and could only be
better, a real target inheriting the indexes declared beside it. `intent_field_reference_step_fanout`
as shipped has a lower bound and not a figure. And `intent_field_chain_node`, a third reader of the
hop table that is neither walk, returned 0 rows on this store, which says little on a store whose
condition family is empty; it was not priced in the de-analysed regime and is named as unpriced rather
than as safe.

### Phase 4: the column-scope departures

Rung 4, and the phase that is not small: `intent_argument_scope_table`, `intent_field_scope_table`,
`intent_carrier_data_field` and `intent_input_field_resolving_table`, which reach `intent_type_backing`,
`intent_errors_field`, `intent_field_payload_producer`, `intent_poly_member`,
`intent_field_participant_scope_table` and, one relation further down than earlier drafts of this
paragraph went, `intent_type_backing_class` under `intent_type_backing` plus
`intent_input_occurrence_path` and `intent_input_occurrence_path_step` under
`intent_input_field_resolving_table`. All still bottom out in captured facts; the subtree is simply
wider, and it belongs to the column-scope family rather than the reference family. Four registrations
convert or demote.

**Where this phase's stages run, and why that position is current for every input.** In the derivation
stratum of `FactCapture.capture`, after the five hand-written producers and before
`Materializations.refresh`, which is the position `ArgMappingCandidates.derive` already occupies and
the one a sibling item takes for its own fifteen. After the producers because the three relations
named above are exactly the `HAND_WRITTEN` tables `InputOccurrencePaths.derive` and
`TypeBackingRows.derive` write, so a stage inside `GraphitronFactCapture` would read the previous
capture's rows; before the refresh because registrations that survive this item read the walks and
have to see this capture's. There is no cycle to arrange around: none of the five producers reads any
relation on this ladder, checked against what each one names rather than assumed. The relations are
still `graphitron_` and still the graphitron gatherer's by the ownership rule; what moves is the
statement's position in the pass, not whose family the rows are in, exactly as
`ArgMappingCandidates.derive` writes `graphitron_argmapping_candidate` from that stratum today.

**This phase and the next split into a successor item, on the phase-3 measurement.** That measurement
put the field walk and the fanout view it feeds inside this item's own family and put the refresh
pass's dominant term in R953's, so nothing it found names rungs 4 and 5 as the failure. They are a
real residual, the input-field walk being 2.66 s of the decode-hop rule's 4.124 s, and they reach into
a second family, which this body already calls scope creep on a measurement that did not name it. The
phase text stays here so the successor inherits the placement argument and the closure rather than
re-deriving them. "What this plan was conditioned on" carries the call and its reasoning.

### Phase 5: the input-field and argument walks

`intent_input_field_reference_step_target` and `intent_argument_reference_step_target` become
`graphitron_` stage-written tables on the phase-2 shape, which is now the single-statement `INSERT ...
WITH RECURSIVE ... SELECT` per graph rather than the Java fold earlier drafts described. The shape
transfers without qualification, checked against both rules rather than assumed: each is the same
recursive `UNION` of a seed with one join to a hop relation, with `DENSE_RANK`, `MAX(target_rank)
OVER` and `COUNT(*) OVER` in the outer `SELECT` over the finished `chain`, and only the seed differs,
which is the whole reason there are three walks and not one. The argument walk's registration is
retired, the third of the three.

These stages run where rung 4's do, in the derivation stratum after the five producers, for the same
reason: rung 5 reads rung 4, and the input-field walk's own closure reaches
`intent_input_occurrence_path`, `intent_input_occurrence_path_step` and `intent_type_backing_class`
through it. Across all six phases the register ends between three and eight rows smaller than it
started, having gained none; with phases 4 and 5 split out on the phase-3 call above, this item's own
share is the three retired by phases 1 and 2.

**What every phase owes the register's prose, because the register is set-relative.**
`meta_materialize.reason`'s own comment requires it: "A registration whose source view reads another
registration's target is priced by that other row still being there, so dropping a neighbour can make
this one dearer rather than cheaper." A retirement re-prices neighbours exactly as an addition does. So
each phase edits the `reason` of every surviving registration whose rule reads what that phase
converted, and `intent_node_id_decode_hop_live`'s is the one most of them touch, its argument being
built on the walks' per-driving-row cost. Separately and on a different surface: the refuted claim that
a second naming of the endpoint subtree "would dominate the read" is on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE` and is the only occurrence of that phrase in the DDL, so correcting the reason does
not reach it. Both surfaces, named separately, or the correction is made in one place and left standing
in the other.

## What was measured

Fixture only, and phase 3 above is what corrects that: this table is the growth reading and phase 3
is the consumer reading. Taken on the scaled registry
fixture (`MaterializedRegistryFixture.scaledSdl`, with a `@nodeId` input field added so the
`INPUT_FIELD` decode arm has a population), three sweeps per configuration, best of three,
`OPTIMIZE_REUSE_RESULTS` off. `intent_node_id_decode_hop_live`, which is the statement the refresh
issues per graph:

| decode endpoints | as shipped | input-field walk stored | walk and foreign-key count stored |
|---|---|---|---|
| 96 | 73.4 ms | 10.5 ms | 1.0 ms |
| 192 | 264.0 ms | 20.0 ms | 1.4 ms |
| 384 | 1069.4 ms | 41.8 ms | 2.8 ms |

As shipped the rule grows by 3.6 and then 4.05 per doubling, an exponent of about 1.93. Both stored
forms grow by 1.9 to 2.1, so linear. The walk answers in 0.8 to 2.7 ms standalone at these sizes and
the foreign-key count in 0.3 ms over 56 rows at every size, so neither is expensive; what costs is that
each is evaluated once per driving endpoint row. `intent_argument_reference_step_target` costs nothing
measurable, being an indexed table since R943, which is the same claim from the other direction.
Snapshotting was proved to change cost and nothing else: `EXCEPT` in both directions returned zero rows
at every size.

One limit on the table and one correction to what it leaves open. Every column of it is neutral
between a stored rule and a registered one: what it prices is rows being on disk, not who wrote them,
so the case for who wrote them is the structural finding above and never this table.

The terms have been separated once already, on a consumer capture rather than a fixture, and
`intent_node_id_decode_hop_live`'s own `meta_materialize` reason carries the reading. Against a 4.7 s
baseline, dropping one inner-side naming at a time leaves 3.4 s without the argument-site walk, 1.9 s
without the input-field one, 3.3 s without the derived table counting foreign keys per table pair, and
4.2 s without the closing `MAX(position) OVER`, while dropping both walks together leaves 1.1 s. The
row states the conclusion as arithmetic: the two walks "cost 12 ms and 25 ms standalone and account
for 6.0 s of the rule between them, which is the per-driving-row re-evaluation stated as arithmetic".

So each half is separately large on a real population: the foreign-key count about 1.4 s of 4.7 s, the
two walks about 3.6 s between them. Two things that reading does not settle. It was taken before R943
registered the argument-site walk, so the shipping baseline is not 4.7 s and the remaining unstored
term is the input-field walk alone. And it is one population at one moment, where the conditioning
measurement below asks about growth. It narrows what that measurement owes rather than replacing it.

**The refresh's graph predicate does not prune.** With three graphs of 72 endpoints in one store, the
refresh of a single graph came out at 170.9 ms against 175.3 ms for the same statement with no
predicate at all, and 22.6 ms for that graph alone in a store of its own.
`Materializations.refreshPartition` issues `INSERT INTO target SELECT * FROM source WHERE graph_name = ?`,
and that predicate cannot reach inside a recursive term, so a workspace store holding several subgraph
modules pays the rule over all of them at every module's refresh. A stage writes one graph's partition,
so this is the one cost term that a registration cannot reach and a conversion removes by construction.

**Rung 3 of the lever order was tried on the walk and does not reach it.** Restating `last_position` as
a join to a `GROUP BY` derived table instead of a window function measured 41.2 s against 170.9 ms, a
regression of about 240 times, because it names the body twice. Moving the site discriminator out of the
`ON` clause, and hoisting the walk into a non-recursive `WITH`, each changed nothing. Splitting the rule
into one arm per site buys 3.6 times and stays quadratic. That last reading also refutes a claim on
`intent_node_id_decode_hop`'s own comment, that a second naming of the endpoint subtree "would dominate
the read", which this item corrects where it touches that comment.

## What this plan was conditioned on, and what the measurement settled

The table above is a shape claim on a synthetic population, and this family has produced a wrong reading
before. The `store-performance` skill's posture section records one: "a family of recursive
reference-target views was the expensive term, when timing each relation on its own said the term was
somewhere else entirely." The investigation that filed this item had a second conclusion overturned,
reporting that statistics were not the lever from a regime that measured identically at every fixture
size, which R953's readings on a copy of the `sis` store contradict.

So the first act was a measurement, and it is one nobody can take without the `sis` workspace on disk.
**It was taken on 2026-09-17 and phase 3 above is what it found.** The configurations stay here as
the method, because "Tests" asks for the same ones re-taken on the shipped tree per phase. Per the
`store-performance` skill's method, on a copy of the `sis` store, timing
`intent_node_id_decode_hop_live` and `intent_node_id_instruction_live` per graph:

1. as shipped;
2. with R953's lever 2 alone, a static `SELECTIVITY 1` on `graph_name`;
3. with the foreign-key count snapshotted into a table, walks left as views;
4. with the field walk snapshotted, the rest left alone;
5. with the input-field walk snapshotted, the rest left alone;
6. with all three, which is the shipping shape.

Four, five and six are separated because the phases above land in that order and each owes its own
evidence. The register's ablation has already separated the terms once on a consumer population, so
what these configurations add is growth across sizes and a post-R943 baseline, not the separation
itself. Each snapshot is proved answer-preserving by `EXCEPT`
in both directions before it is timed.

**The four outcome branches this section used to carry are not mutually exclusive against this
evidence, and saying so is a better answer than forcing a fit.** Two of them fire at once. "Lever 2
alone brings the round into seconds" is true of the *refresh pass*: with `graph_name` alone back at
`SELECTIVITY 1` the instruction rule is 4.65 s and the decode-hop rule 7.76 s, which on a 2915.7 s
pass whose other 21 registrations cost 23.2 s is a round in seconds. And "the input-field walk is the
term" is true of the decode-hop rule's residual shape, 2.66 s of 4.124 s on an analysed store and
about 2450 s to 1.45 s in the failing regime. The branches were written on the assumption that one
relation's cost is one term, and the lattice says every rule here has two.

They also share an assumption the measurement broke: that the refresh pass is the whole of the round.
It is not. `intent_field_reference_step_fanout` is read by a lint outside the pass, is unregistered so
nothing times it, is unaffected by statistics (hop was analysed six seconds before the live round's
read of it began), and is the one relation that did not return at all. Phase 2 fixes it, to 0.37 s,
without either the view or this item changing a line for it.

**The scope call: phases 0 to 2 are this item, and phases 4 and 5 split to a successor.** The argument
in the order the evidence supports it.

1. **Phase 2 is necessary for the `sis` round to finish and is reachable by nothing else, R953
   included.** The fanout read is outside the refresh pass and outside the cliff's reach, it did not
   return inside a 30-minute cap with hop analysed, and storing the field walk takes it to 0.37 s.
   Lever 2 does not reach it and no statistic will. That is this item's priority-1 ground, and it is
   ground R953 does not stand on, so claiming it is not annexing the cliff. Necessary and not
   sufficient, which the heading used to overstate: with phase 2 landed and R953 not,
   `intent_node_id_instruction` goes from 436.1 s to 0.91 s but `intent_node_id_decode_hop` is
   outside this item's scope at about 2536 s, so the pass is still tens of minutes. The contingency
   bullet below and the acceptance evidence under "Tests" both already said so; the heading is what
   was out of step.
2. **This item's own rule fires for phases 4 and 5.** The rule phase 3 was filed with is that if the
   round is in seconds, the remaining rungs are a modelling tidy rather than a fix and say so in their
   own priority. Lever
   2 alone puts the pass in seconds. The input-field walk is real, and it is the decode-hop rule's
   residual shape term, but it is seconds and not the failure, and rungs 4 and 5 reach into a second
   family. "What is in scope" already calls a reach into a second family on a measurement that did not
   name it scope creep whatever its doctrine, and the measurement names it as seconds. So they leave,
   with the modelling claim intact and the figures above as their filed cost evidence.
3. **Phase 0 stays with 1 and 2 rather than going with 5.** Its own measured value is 1.44 s of the
   decode rule's 4.124 s on an analysed store, a factor of 1.5, and that is the whole of what it
   delivers alone: the 42x needs the input-field walk beside it, and phase 0 alone was not measured in
   the de-analysed regime at all. It stays because it is the top rung of the lever order on a value
   that is a plain catalog fact, because it is independent of every rung of the ladder, and because
   it costs one stage. That is the modelling claim carrying it, not the measurement.

**The price of this call, stated rather than left to be discovered.** The `@nodeId` decode rule is
the successor's. This item takes 1.44 s of its 4.124 s and leaves the 2.66 s its remaining unstored
walk costs, so that rule's growth stays quadratic in that term until phase 5 lands, and what makes it
tolerable in the meantime is R953's lever 2 rather than anything here. The goal paragraph and the
"does not do" section both say so, because an item named for reference-step walks is exactly where a
reader would look for that rule and find the wrong answer.

**Where the evidence runs out, stated rather than glossed.** Growth across sizes was not measured, so
the goal's growth claim still rests on the fixture table; the acceptance evidence "Tests" asks for per
phase is what closes it. The de-analysed figures are single executions and rank rather than measure.
Decode-hop as shipped was not re-run de-analysed on the trunk store, so its de-analysed comparison is
against a figure from another store. Whether the foreign-key count still costs 1.44 s beside a
stage-written walk was not measured. And the fanout view has a lower bound, not a figure.

**One contingency, stated once.** If R953 has not landed when this item is done, the successor
carrying phases 4 and 5 is urgent rather than a tidy, because the pass is then still minutes and the
input-field walk is the decode-hop rule's remaining unstored term. Its cheap first move in that case
is the registration fallback under "Other solutions we've considered", which is three DDL lines and
lands the same rows.

## Tests

Every conversion is proved against the rule it replaces rather than re-asserted, which is what keeps a
phase cheap to review however large the ladder gets.

- **Answer preservation, per rung.** `EXCEPT` in both directions between the stage-written table and the
  view text it was converted from, over a populated store, per graph. This is the oracle the whole plan
  rests on and it exists precisely because these rules are expressible as views. **It is blind to a
  stage that appends instead of reconciling, in two independent ways, and the bullet below is what
  covers that rather than this one.** `EXCEPT` is set semantics, so both directions return zero rows
  even when one side holds every row twice; and on a fresh store the stage runs once, so there is
  nothing to duplicate. An oracle this load-bearing is worth knowing the shape of what it does not see.
- **Reconciliation, per rung: a second capture of one graph into one store leaves the row count
  unchanged.** This is the reading that tells a stage which reconciles from one which appends, and it
  is the acceptance evidence the `DELETE`-then-`INSERT` discipline owes. It has a home already:
  `WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` captures twice into a persistent store and
  compares a per-relation row-count census against a cold load, and `census` counts every non-view
  table in the schema, so a converted relation joins it with no edit. What it does not do today is give
  these relations any rows: it runs on that class's `SDL`, which binds no table, and the class already
  carries `TABLE_BOUND_SDL` and a `captureBound` helper that exist for exactly this, "so the intent
  targets take rows". So the ask is a bound-schema arm on an existing case, not a new fixture.
- **`ReferenceStepTargetTest`, `ArgumentReferenceStepTargetTest`, `ReferenceStepFanoutTest`,
  `ChainTerminusTest`, `InputFieldResolvingTableTest`** already pin what these relations answer at the
  coordinate grain. They must pass with no edit beyond the renames; a test whose expectations move is a
  signal a conversion moved an answer.
- **A case for `sql_table_reference`'s grain**: a pair connected by two foreign keys is one row saying
  two, a self-referential key is one row, a pair with none has no row. The third is what the decode
  rule's `LEFT JOIN` depends on.
- **`MaterializeRegistryGateTest`** is the gate that notices this item most. `REGISTRATIONS` falls
  rather than rises and its refresh-stage depth moves; both are equality-pinned so they cannot move in
  a commit arguing for something else. `nothingMaterializesOutsideTheMechanism` keeps its meaning
  untouched, every converted relation having left the `intent_` prefix its scan is scoped to.
- **`DerivedReadCostTest`** prices every pair of a registration and a relation reaching its target. A
  retirement moves its pinned set as surely as an addition does, and the set is edited per phase, which
  is the confrontation it is built to force. Its reader counts are also what decides convert against
  demote.
- **`MetaDeclarationGateTest`** binds on **every** converted relation, per rung and not conditionally,
  which is the correction the round-6 review forced: `theUndeclaredRosterOnlyShrinks` makes a
  declaration mandatory the moment a relation leaves the frozen roster under a new name, so there is no
  arm where a conversion carries none. Three of its cases bind per rung. The roster case is the one
  that creates the obligation, and it is two edits: the `intent_` line removed, the declaration added.
  `aDeclaredTableKeyMatchesItsGrain` is satisfied rather than widened, every declared base table this
  item introduces carrying a real primary key, which for two of the four is what R956 delivers. And the
  view-ownership gate is the mechanical check that a moved relation's reads match its new owner, which
  now binds on two union views as well. The corpus case is
  vacuous here, the graphitron gatherer carrying no `meta_gatherer_corpus` row, which is worth stating
  so an implementer does not go looking for a grain corpus to pick.
- **`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`** binds on every rung once it is
  keyed, which is all four after R956: each key must lead with `graph_name`, which every candidate key
  here does.
- **`FactCaptureAgreementTest`, `FactSchemaGateTest`, `CaptureCorpusIsolationTest`** are the regression
  surface for a column list or a capture path that moved, and the third is what covers a producer's
  reads, which no catalog parse can see.
- **Acceptance evidence for the goal**, which a green build does not supply: phase 3's `sis` timings
  re-taken on the shipped tree per phase and written into the changelog entry, on the configurations
  "What this plan was conditioned on" lists. Phase 3 priced the shipped tree and a stack of snapshot
  proxies; what a phase owes is the same reading against the stage-written relation it actually
  landed, which is the one thing a proxy cannot stand in for. Three readings in particular:
  `intent_field_reference_step_fanout` answering at all, which is the cost claim; the decode-hop rule
  after phase 0, where the foreign-key count's 1.44 s was measured on an unindexed snapshot and a real
  target carries its declared indexes; and the growth claim, which only the same configurations
  answering linearly across sizes demonstrate and which phase 3 did not take.

## What this item does not do

- **It does not mint an ordering mechanism for producers inside the refresh pass.** It does not need
  one. The invariant every placement here satisfies is that **no stage reads a table a later step of
  the pass writes, registered, hand-written, or written by a later stage of the same gatherer**, and
  three existing things carry it between them: converting bottom-up handles the registered case,
  placing rungs 4 and 5 after the five hand-written producers handles the second, and for the third
  the ladder simply reaches neither of the two tables the gatherer's last two stages write.
  R876's open question, what orders two relations under one
  owner, stays open and stays R876's; this item avoids it rather than answering it, and the phase
  table is the proof that avoiding it is possible for this subtree.
- **It does not change what admits a hand-written derivation.** `HAND_WRITTEN`'s impossibility criterion
  stands, and every relation here leaves its scope by moving family rather than by weakening it.
- **It does not change a gate at all, and an earlier draft of this plan did.** The declaration the
  rename forces meets `MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain`, whose message is that
  "an unkeyed declared table owes a key before it owes anything else", and two of the four relations
  could not carry a key as written. The draft answer was a roster-pinned exemption arm on that gate.
  It is withdrawn: the key-gate subsection records why, and the short form is that the gate was right
  and the relation was wrong. Every declared base table this item introduces carries a real primary
  key.
- **It does not promote `intent_node_id_instruction_live`'s inner alias to a named relation.** That
  rule's `slot_table` is a local alias joined back to the `instructed` alias it derives from, so H2
  recomputes it once per driving row, and its own `meta_materialize.reason` already says the alias
  "wants promoting to one before it can be registered" and that snapshotting it put that arm at 0.7 s.
  Phase 2 takes the recursion out of the inside of that loop and leaves the loop, which is a real term
  and not the whole of that rule. Phase 3's measurement prices what is left: with the walk stored the
  rule is 0.91 s in the failing regime and 1.100 s on an analysed store, and the promotion takes those
  to 0.06 s and 0.080 s. Worth having, and not what makes the build finish. The promotion is a
  different change to a different relation and belongs in its own item. **It is not filed anywhere as
  of 2026-09-17**, which is stated so that a reader of this bullet does not go looking for a covering
  item that does not exist.
- **It does not store the input-field or argument walks, and so it does not make the `@nodeId` decode
  rule linear.** Those are phases 4 and 5, which the phase-3 measurement split to a successor. The
  decode rule reaches the input-field walk on the inner side of a `LEFT JOIN` driven per endpoint row,
  and that term stands until the successor lands: this item takes 1.44 s of its 4.124 s with phase 0's
  foreign-key count and leaves the 2.66 s the walk costs. A reader who came here for that rule should
  read "What this plan was conditioned on" for why, and R953 for the term that dominates it today.
- **It does not touch R953's levers 1 and 2.** They fix which plan one evaluation gets; this changes how
  many evaluations there are. Lever 2 should land whatever this item does.

## Relation to other items

**R953** is a different defect on an overlapping path and the two compose. It diagnoses a statistics
cliff: `intent_field_reference_step_hop.graph_name` at H2's default selectivity makes the planner choose
a one-column index over the step index, and the first refresh on a store plans with no statistics at all.
That is a cliff in the cost of *one* evaluation; this item is about the *number* of evaluations. They
multiply, because these walks join that table in both the anchor and the step. Phase 3's measurement
prices the cliff and hands the figure to R953 rather than keeping it: that one column is worth 135x on
the instruction rule and 315x on the decode-hop rule on a `sis` store, and the live round's 23
`ANALYZE TABLE` statements all ran after the pass that read the table they analyse. It is the larger
term of the refresh pass and it is R953's. What phase 3 also establishes is that it is not the whole
round: the fanout view's read sits outside the pass, and hop had been analysed six seconds before it
began. Its lever 3 proposes
registering the field walk, which phase 2 supersedes: the same rows land, written by their owner rather
than scheduled by the register. The record of that split is **one-way and this item's**: R953 is
`Backlog` and nobody has picked it up, so a mutual clause would be a gate with no owner, and
this item carries no edge to R953 deliberately rather than inventing a wait on a transition nobody is
committed to. Phase 2 states the supersession, and R953's body is amended whenever it is next touched;
an implementer who reaches phase 2 first and finds lever 3 already landed takes the registration as
that phase's fallback and says so. Its lever 2 is a static `SELECTIVITY` on a partition column and
stays true of a stage-written table too, so it survives this item unchanged.

**R876** is the doctrine this item instantiates, and this is the first subtree to be taken bottom-up
rather than one relation at a time. It contributes three things back: the finding that a 26-relation
closure bottoms out entirely in captured facts, the observation that a bottom-up order plus one
placement rule needs no successor to `meta_materialize_dependency`, and three register rows against
its twenty-three, with up to five more in the successor carrying phases 4 and 5 (earlier drafts of
this sentence promised between three and eight, which was the whole ladder before the phase-3
measurement split it). A fourth thing it contributes is a correction rather
than a contribution: R876's own enumeration of family-local misplacements still lists
`intent_name_matched_key_pair`, which commit 78b6a58 retired on 2026-09-14, so that count is one row
stale and is that item's to re-take. The precedent it follows is R876's own `graphitron_argmapping_match`.

**R956** is this item's one `depends-on:` edge and the answer to the round-6 review's finding that two
of the four conversions could not carry a primary key. It decomposes the hop and the field walk on
their `via` discriminator into a keyed relation per key shape under a view carrying the existing name,
which is what lets those two be declared without a gate moving. The dependency is real rather than
courteous: phases 1 and 2 convert what R956 leaves, and if it has not landed those rungs take the
registration fallback the key-gate subsection states. It is a separate item because it owes a
re-measurement of the recursive step's seek across a union view, and owing a measurement is what makes
something an item rather than a paragraph.

**Why this one carries an edge where R953 does not, since both are Backlog items nobody has picked
up.** R953's lever 3 is a *substitute* for phase 2: either shape lands the same rows, so an edge would
name a wait with no owner and no purpose. R956 is what phases 1 and 2's *stated shape* is built on:
without it those rungs convert relations that cannot be declared, which is a different plan rather than
a delayed one. So the edge records the shape, and the per-rung registration fallback records what
happens if R956 does not land, which is the same fallback R953's lever 3 names. The rule is that an
edge goes where the plan changes without the other item, not where the other item would merely be
convenient.

**R955** converts the register's remaining fifteen registrations on the same doctrine and the two items
have to land one answer to one question, which is why the key-gate subsection above argues it once and
this paragraph carries the hand-off rather than a second argument. Three things they share and one
they do not. They share the placement criterion, what an input reaches, which is why that item's
fifteen all go to the derivation stratum and rungs 0 to 3 here stay in the gatherer. They share the
declaration obligation, both items reaching it from `theUndeclaredRosterOnlyShrinks`'s ratchet. And
they share the first half of the key rule, a primary key where the grain admits one, which is that
item's wording. Where they part is the other half. R955 says an index with a stated reason where a
meaningfully nullable column is in the grain, and leans that on
`MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot`. That gate iterates live registrations,
so it stops covering a relation at the moment its registration is retired, which is the moment that
item converts one, and that item's own last commit deletes `MaterializeRegistryGateTest` outright. It
never names `MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain`, which starts covering a relation
at the moment it is declared, whose message is that "an unkeyed declared table owes a key before it
owes anything else", and which twelve of its fifteen would offend. An index is also not a key: it does
not refuse a duplicate row, which is the property R876's burn-down asks a stage-written table to add.
So the pincer is that item's too and larger, and this body's answer is the one it should take: where the
grain admits no key, ask whether the relation is one relation before asking the gate to tolerate a
keyless one. Both of this item's two unkeyable relations turned out not to be, which is R956. Whether
each of that item's twelve is the same shape is that item's to determine, and R956's own body says so.
The record is one-way for the same reason the R953 one is: that item is in Spec with an open review
round of its own, so nothing here waits on it.

**R900** is the naming sweep, and the renames here are taken with their moves rather than deferred to
it, on R876's reasoning that correcting the family and the noun together is one edit rather than two.
Nothing here pre-empts the spellings that sweep is about.

**R942** is a gate rather than a fix. Its detector drops the `INNER_SIDE` position because
`ViewReferences`' javadoc records that an inner-side reading is the executed shape "only where the join
order is fixed, which is the outer joins", and asks for such a naming to be priced rather than asserted.
`intent_node_id_decode_hop_live` naming the input-field walk is one of those, now priced. A scan of the
DDL puts the outer-join subset at 9 distinct (reader, view) pairs across 12 namings against 45 distinct
on inner joins, so widening is a nine-row question rather than the fifty-six R942 costed. Phase 0 removes
the foreign-key naming from that census anyway.

**R899** exists to make a registration's alternative countable so the last lever stops being the first
reached for. This item is the counted alternative for one subtree, and it retires register rows rather
than adding them, which is the direction R899 was filed to make ordinary.

**R857** and **R872** would stop a dev round refreshing this rule when the edit did not touch it, which
mitigates the symptom on the dev loop and does nothing for a `generate` or for CI. R872 also owns the
catalog family's refresh lifecycle, which phase 0's relation joins.

## Other solutions we've considered

- **Register the two unstored walks**, which is R953's lever 3 and the shape R943 gave the third walk.
  This is the cheap fallback and it remains available per phase: it lands the same rows on disk, needs no
  conversion, and is three DDL lines. It is not the plan for two reasons the structural finding supplies.
  It grows a register that is already holding up a subtree with no cross-family rule in it, which is the
  accretion R876 diagnoses. And it cannot reach the per-graph refresh term, the one measured cost a
  conversion removes by construction. If a phase turns out to be larger than its measured win, taking the
  registration for that rung and recording why is a legitimate outcome rather than a failure. Phase 3's
  investigation independently reached for this shape and ranked it first of its own four fixes, on the
  finding that it is the only lever reaching all three slow relations and that it reaches them in the
  regime that actually failed. That is the same finding stated as a register row; the two differ in who
  writes the rows, which is the modelling claim and not a measurement question.
- **A producer inside the refresh order, with a `meta_` relation declaring what it reads.** This was the
  earlier reading of the item, and the bottom-up order plus the placement rule above make it
  unnecessary for this subtree. It is also the one artifact
  the fact-model page rules out by name, a hand-kept ordering with no derivable source, "which is the
  shape `SchemaIdentifierDriftCheck` exists to refuse"; an admissible version would derive the edge set
  from the producer's own source and gate on drift, which is a larger piece of work than this item and
  belongs with R876.
- **A rewrite of the walk.** Three were measured, above. One regressed about 240 times, two changed
  nothing, and the one that helped left the exponent alone.
- **R953's lever 2 alone.** Cheap, stated in a Backlog body, orthogonal, and the question of whether
  it is *sufficient* is now answered rather than open. It is sufficient for the refresh pass, which it
  takes from 2915.7 s to something in seconds, and it is not sufficient for the round: the fanout
  view's read is outside the pass and is unaffected by any statistic, and the walks' residual shape
  terms survive it. Lever 2 should land whatever this item does, and this item should land whatever
  lever 2 does.
- **Converting the whole 26-relation closure in one move.** Eight registrations and two families, with
  the column-scope half reached only through two of the three walks. The phase table exists so the
  measurement decides how far up the ladder this item goes, rather than the doctrine deciding it in
  advance.

## Provenance

Filed 2026-09-16 from an investigation into a `graphitron:dev` round on the `sis` consumer taking more
than twenty minutes, which began as a question about `intent_node_id_decode_hop_live` and found the shape
defect underneath it. The investigation's instrument was a throwaway probe in a scratch directory, which
is the failure mode R899 names, so its figures are recorded above rather than left to be re-derived. Two
of its conclusions were overturned before filing and are recorded in this body rather than dropped: that
statistics were not the lever, and that the window function was what blocked the graph predicate from
pruning.
## Reviewer findings

### Round 1 (2026-09-16, Spec -> Ready, reviewer session 01Un87ZyPLmwD9kLBuTdSFKx)

Verdict: withhold. Two blocking findings on question two, both inside slice one, both about which
gatherer owns the new relation and on what lifecycle. Five non-blocking findings follow them.

Nearly everything else checks out against the tree, and the checks were not cheap ones. The three
walks are as described: `intent_field_reference_step_target` and
`intent_input_field_reference_step_target` are views, `intent_argument_reference_step_target` is a
table carrying `ix_argument_reference_step_target_coordinate`. The field walk is named by exactly
three view bodies and by `intent_field_reference_step_fanout` twice, and `SchemaQueries` and
`ClaimFacts` both read it through `Tables.INTENT_FIELD_REFERENCE_STEP_TARGET`.
`intent_node_id_decode_hop_live` names the input-field walk on the inner side of a `LEFT JOIN` and
inlines an anonymous derived table over `sql_referential_constraint` counting foreign keys per
ordered pair through a `COUNT(*) OVER (PARTITION BY ...)`, exactly as the body says.
`MaterializeRegistryGateTest.REGISTRATIONS` is 23, `HAND_WRITTEN` holds six `intent_` tables under
the impossibility javadoc quoted, `GraphitronFactCapture`'s stages are eight,
`noOrderingNeedCrossesTheHandWrittenBoundary` discloses the asymmetry claimed, and both verbatim
quotations (the `intent_node_id_decode_column_live` reason and `intent_node_id_decode_hop`'s "would
dominate the read") are in the DDL word for word. `sql_table_reference` is a free name. R953's 592 s
figure for `intent_node_id_instruction_live` is its own. Every named class, gate method and test
exists. The goal paragraph answers question one on its own reading: a consumer of graphitron gets a
`graphitron:dev` round whose cost grows with the schema rather than its square, and the three terms
it needs are glossed where they first appear.

**Finding 1 (question two: architecture fit). Slice one puts the stage in a class that writes a
different family, and the ownership fork it walks into is not seen.**

The slice says the new relation "is a stage of the catalog gatherer, which today has none,
`CatalogFactCapture.capture` being a transcription into a `FactSink`", and its Lifecycle paragraph
says the stage writes "the same scope `CatalogFactCapture.capture` writes". `CatalogFactCapture`
writes no `sql_` row at all. Its own class javadoc says so: it is "the `jvm_` family", and "It used
to fill the `sql_` family too, and that half is gone: `JooqFactCapture` was writing the same fourteen
relations from the other capture entry point". Its `capture` method calls one thing,
`captureExtensions`, which writes `jvm_class` and its children; its `SQL_REFERENTIAL_CONSTRAINT`
import is unused residue. What transcribes the relation slice one reads is
`JooqFactCapture.capture`, whose javadoc is "The `sql_` family and nothing else: one source of one
shape behind one entry point", and whose `referentialConstraints` stage writes the rows.

By the ownership rule the slice invokes, the latest owner of anything it reads, `sql_table_reference`
therefore belongs to the jooq gatherer. That is not a rename of the paragraph: it decides which class
the implementer edits and which lifecycle applies, and it is the input to the second finding.

There is a live fork underneath it that the slice does not see, and it is the author's to settle
rather than the implementer's. The new relation needs a `meta_relation` row, because
`MetaDeclarationGateTest`'s frozen roster only shrinks and "a new relation is on no frozen roster, so
it cannot arrive undeclared". That row carries `owner_name`, a `meta_gatherer` key. All fourteen
declared `sql_` relations today name `catalog` as their owner while `JooqFactCapture` is what writes
them, and the jooq gatherer owns no declared relation at all. So the tree's declared ownership and
its actual writer already disagree for this family, and slice one has to pick one: declaring
`catalog` entrenches a mismatch the gate does not currently catch, declaring `jooq` makes the new row
the first correct one and leaves fourteen beside it that are not. The slice's "Check the choice
against `MetaDeclarationGateTest`'s corpus gate before landing and say which way it went in the
commit" reads as a gate formality; it is a modelling question with a wrong answer available, and the
spec should answer it.

*Response.* Both halves taken, and verified against the tree before acting: `CatalogFactCapture.capture`
calls `captureExtensions` alone and its javadoc records the `sql_` half having moved, while
`JooqFactCapture`'s referentialConstraints stage writes the rows. Phase 0 now puts the stage in
`JooqFactCapture` after that stage and before the sweep, and names the `jooq` gatherer as owner. The
declaration fork is settled in the body rather than deferred to the gate: declare `jooq`, on the
reasoning that it is the first correct row rather than the fifteenth wrong one, and file the fourteen
`sql_` relations declaring `catalog` as a Backlog item rather than reconciling them inside this item.

**Finding 2 (question two: architecture fit). The `sql_` family's lifecycle is stamp-and-sweep, and
the proposed table carries neither the stamp nor a stated reason to be the exception.**

The Lifecycle paragraph says "The stage clears and refills the sources the capture touched". That is
a third discipline, neither of the two the family actually uses. Fourteen of the fifteen `sql_`
tables carry a `touched_at TIMESTAMP`, and `sql_referential_constraint.touched_at`'s own comment
states the rule: "The reading finishes by deleting this source's rows carrying a different instant,
which are the tables, columns and keys the consumer's database no longer has and which an upsert
alone cannot find." `JooqFactCapture.capture` closes with `sweep(dsl, sourceNames(tables),
touchedAt)` and takes the instant as a parameter. The fifteenth, `sql_table_record_supertype`, has no
`touched_at` and its writer is the one stage called without one, so there is a precedent for the
other arm.

The proposed DDL has no `touched_at`, and the body does not say which arm it takes or why. This is
load-bearing beyond tidiness: a derived pair relation that is not swept the same way its source is
outlives the foreign key it counted, and the surviving row says `constraints = 1` about a pair with
no constraints, which is precisely the arm the decode rule's `LEFT JOIN` reads. Name the discipline
and the reason.

Smaller, in the same slice and not blocking on its own: after the change the `DISCOVERED_KEY` arm
joins `sql_referential_constraint` on six columns to reach the constraint name, of which only the
first three are a prefix of its primary key `(source_name, table_schema, table_name,
constraint_name)`, and the relation carries no other index. Slice two states an index discipline and
a `COMMENT ON INDEX` obligation for its own tables; slice one should say whether this join wants
anything, even if the answer is that a three-column prefix seek on a small relation does not.

*Response.* Taken. The DDL carries `touched_at TIMESTAMP NOT NULL`, the stage takes the capture's
instant and the existing `sweep(dsl, sourceNames(tables), touchedAt)` covers it. The body states the
discipline and the reason, including the failure the finding names: a pair row outliving its foreign
key would say `constraints = 1` about a pair with none, on the arm the decode rule's `LEFT JOIN`
reads. `sql_table_record_supertype` is named as the precedent for the other arm and why this relation
is not on it. The smaller point is taken as a measurement rather than an assertion: the six-column
reach into `sql_referential_constraint` is timed alongside the phase-0 reading and the commit records
what it found, rather than the body predicting that a three-column prefix seek suffices.

**Finding 3 (question two, non-blocking). The `FieldEndpoints` precedent comes from the one gatherer
the crawler invariant does not bind.**

"A gatherer writing a computed relation into its own family is `FieldEndpoints` exactly" is true as
far as it goes: it is a stage of the graphitron gatherer and writes `graphitron_field_table`. But
`meta_gatherer_corpus`'s comment defines a crawler as a gatherer carrying a corpus row, "a
transcription pass whose rows about its own corpus may not vary with any other corpus's contents",
and says in the same breath that the graphitron gatherer carries none and "is thereby free to cross
corpora". Both jooq and catalog carry a `catalog` corpus row, so both are crawlers and both are bound
by that invariant. The precedent cited is the one place it does not apply.

The argument slice one actually needs is available and stronger: `sql_table_reference` reads
`sql_referential_constraint` and nothing else, so its rows vary with the catalog corpus alone and the
crawler invariant holds by construction. Make that argument rather than the precedent one.

*Response.* Taken, and it improved two places rather than one. Phase 0 now makes the corpus-purity
argument directly, that the relation reads `sql_referential_constraint` alone so its rows vary with
the catalog corpus only and the crawler invariant holds by construction, and cites no precedent. The
`FieldEndpoints` precedent is kept for the graphitron ladder, where it does apply, and the reason is
now stated instead of assumed: the graphitron gatherer carries no `meta_gatherer_corpus` row and is
thereby free to cross corpora, which is the licence every rung needs.

**Finding 4 (question two, non-blocking). Slice two's edit list points the "would dominate the read"
correction at the wrong surface.**

The bullet reads "`intent_node_id_decode_hop_live`'s reason, whose argument is built on the two
walks' per-driving-row cost, and whose claim that a second naming of the endpoint subtree 'would
dominate the read' the measurements above refute". The first half is right, the reason row is built
on that cost. The second half is not: the refuted sentence is on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE`, and it is the only occurrence of the phrase in the DDL. An implementer working
the list edits the reason and leaves the refuted claim standing where it lives. Name both surfaces.

*Response.* Taken. The plan was re-cut around a bottom-up conversion and no longer carries that edit
list, so the obligation is restated at the end of the phases in the form the finding asks for: each
phase edits the `reason` of every surviving registration whose rule reads what it converted, and the
"would dominate the read" claim is named separately as living on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE` and reachable from nowhere else.

**Finding 5 (question one, non-blocking). The register already carries a per-term ablation for the
foreign-key count, on a consumer capture rather than a fixture.**

"What was measured" says of the two limits on its table that "The foreign-key count was never timed
without the walk, so the two are priced as a pair and the third column is not evidence that either
half suffices." That is true of this item's own fixture sweeps, but `intent_node_id_decode_hop_live`'s
`meta_materialize` reason already carries the separated reading, taken on a capture of a consumer
schema: against a 4.7 s baseline, dropping one inner-side naming at a time leaves "3.4 s without the
argument-site reference-target walk, 1.9 s without the input-field one, 3.3 s without the derived
table counting foreign keys per table pair", and dropping both walks together leaves 1.1 s.

That reading does not overturn anything here, and it points the same way the plan does. It does two
things for the body. It narrows what the conditioning measurement still has to establish, since
configurations three and four exist to separate a pair that has in fact been separated once already
on a real population. And it puts a rough size on each half, the count at about 1.4 s of 4.7 s and
the two walks at about 3.6 s between them, which bears directly on the outcome branch that says
"The count is the term and the walks are not." Cite it and say what it leaves open.

*Response.* Taken, and it is the most useful finding of the round. The passage is quoted in "What was
measured" with its figures, the "never timed apart" limit is withdrawn, and the two halves are given
their rough sizes on a real population, the count at about 1.4 s of 4.7 s and the two walks at about
3.6 s between them. Two caveats are stated with it rather than left implicit: the reading predates
R943 registering the argument-site walk, so 4.7 s is not the shipping baseline and the remaining
unstored term is the input-field walk alone, and it is one population at one moment where the
conditioning measurement asks about growth. The conditioning configurations are re-described as adding
growth and a post-R943 baseline rather than the separation itself.

**Finding 6 (question one, non-blocking). R953 is Backlog, and the coordination clause assumes it is
further along than it is.**

Slice two says "**Neither item implements until both bodies record that split**; R953's Spec is where
its half of the record goes", and "Other solutions" calls lever 2 "already specced". R953's
front-matter reads `status: Backlog`. It has a body, and lever 3 in it is indeed the twin of half of
slice two, but it has no Spec state and nobody has picked it up, so as written this item's
implementation waits on a transition no one is committed to making. `depends-on:` here is empty,
which is the one place a reader would look for that wait.

Either drop the mutual clause to a one-way one, this item records the split and R953's body is
amended whenever it is next touched, or make the dependency real in the front-matter. The clause as
it stands is a gate with no owner.

*Response.* Taken, as the one-way form. This item records the supersession, R953's body is amended
whenever it is next touched, and `depends-on:` stays empty deliberately with the body saying why
rather than leaving a reader to infer it. The plan also says what an implementer does if lever 3 lands
first: take the registration as that phase's fallback and record it. "Already specced" is corrected to
"stated in a Backlog body".

**Finding 7 (question one, non-blocking). The conditioning measurement has four outcomes and no
branch for not being able to take it.**

"What this plan is conditioned on" says the measurement is "one nobody can take without the `sis`
workspace on disk" and makes it "the implementer's first act at pickup, before any code". All four
outcomes below presuppose it was taken. R943 and R953 both took `sis` readings, so this is plainly
obtainable and not a reason to doubt the plan, but a Ready item whose first act may be unavailable
should say what happens then. The body already contains the answer: slice one is justified by the
modelling claim alone, which no measurement moves, and "Other solutions" says slice one would stand
as its own item. State it as the fifth branch rather than leaving the implementer to assemble it.

*Response.* Taken. A fifth branch says what happens if the store cannot be obtained: phase 0 and
phases 1 and 2 land on the modelling claim with the fixture table and the register's ablation as the
cost evidence on record, phases 4 and 5 wait because nothing else justifies reaching into a second
family, and the changelog entry says the growth claim went unverified rather than letting a green
build stand in for it.

**Small, and left as findings rather than corrected here because their resolution depends on
Finding 1.** The Goal says "the decode rule joins four stored relations" and slice three says it
"becomes a join of one view to three stored relations". Counting the shipping shape:
`intent_node_id_decode_endpoint` is the view, and `intent_argument_reference_step_target`,
`intent_input_field_reference_step_target`, `sql_table_reference` and `sql_referential_constraint`
are four stored relations, the last of them because slice one still reaches it for the constraint
name. One of the two numbers is wrong, and which one depends on whether that reach survives Finding
1's rework.

*Response.* Both numbers are gone. The re-cut's Goal no longer counts the decode rule's operands and
the phase that used to is now a measurement phase with no such sentence, so there is nothing left to
be inconsistent. The underlying fact the finding establishes is kept where it matters: phase 0 still
reaches `sql_referential_constraint` for the constraint name, and the body now says so explicitly and
asks whether that reach wants an index.

### Round 2 (2026-09-16, Spec -> Ready, reviewer session 01AXRLbCvhG9iCJNKVHG5akT)

Verdict: withhold. Two blocking findings on question two. Both are about the shape of a phase rather
than about the goal, which question one clears: a consumer whose schema is large gets a
`graphitron:dev` round, a `generate` and a CI build whose cost grows with the schema rather than with
its square, because the `@reference` walk every `@nodeId` decode reads becomes rows the graphitron
gatherer wrote once per capture instead of a recursive view re-expanded under each driving row. The
structural finding checks out as computed rather than as argued: the closure of the three walks
through `intent_` relations is 27 relations with 8 registered, the field walk's own closure carries
exactly the three registrations the ladder names and no hand-written table, and every rung 0 to 2
relation's inputs are captured facts or plain views over them. `FactCapture.capture` runs the jooq,
catalog and sdl gatherers before the graphitron one, so a stage placed before `FieldEndpoints.derive`
reads current `sql_`, `jvm_` and `graphql_` rows. Round 1's findings are all answered in the body as
their responses say.

**Finding 1 (question two: architecture fit). The seam dissolves bottom-up for registered targets,
and rungs 4 and 5 read hand-written targets the same seam covers and nothing on the ladder converts.**

"The seam is self-dissolving bottom-up" and "converting bottom-up means no stage ever reads a
registered target" are both true and both too narrow. `FactCapture.capture` runs
`GraphitronFactCapture.capture`, flushes, and then runs the hand-written producers
`ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`, `ArgMappingCandidates.derive`,
`TypeBackingRows.derive` and `AuthoredClaimRejectionRows.derive` before `Materializations.refresh`. A
stage inside the graphitron gatherer therefore reads a hand-written table's *previous* capture's rows,
exactly as it would a registered target's. Computed from the DDL: the input-field walk's closure
contains `intent_input_occurrence_path`, `intent_input_occurrence_path_step` and
`intent_type_backing_class`, and the argument walk's contains `intent_type_backing_class`; all three
are `HAND_WRITTEN` and written after the gatherer. The field walk's closure contains none of them,
so phases 1 and 2 are placed correctly and this finding does not touch them. Phase 4's list of what
rung 4 reaches names `intent_type_backing` and stops one relation short of the table under it.

The fix is placement rather than a mechanism, and the tree already has the shape: `ArgMappingCandidates.derive`
writes `graphitron_argmapping_candidate`, the R876 precedent this body cites, and it runs in the
derivation stratum after the hand-written producers, not as a stage of `GraphitronFactCapture.capture`.
None of the five hand-written producers reads any ladder relation (checked against their `Tables`
imports and the closures of the views they do read), so placing rungs 4 and 5 after them closes the
seam with no cycle. What would satisfy this: phases 4 and 5 say where their producers run and why
that position is current for every input, and the "What this item does not do" bullet says "no stage
ever reads a registered or hand-written target that a later step of the pass writes", or the
equivalent in the author's words. Whether the rung 0 to 2 stages stay in the gatherer or all of them
move to the stratum is the author's call; the body should make it, since it decides which class an
implementer edits.

**Finding 2 (question two: architecture fit). Phase 2's fold restates a rule the tree states in SQL,
on a reading of the view that is not what the DDL holds, and phase 5 inherits the shape.**

The structural finding says the rule "stays stated once, in SQL, and what changes is who runs it and
when", and phase 1 does exactly that, "the rule moving into the stage's `INSERT ... SELECT`
unchanged". Phase 2 does something else: a Java loop of one insert per position with a termination
bound and an assertion, then "one grouped `UPDATE` per graph rather than two window functions inside
a recursive term". Two things about that.

The reading is off. In `intent_field_reference_step_target` as shipped, `targets` and `candidates`
are `MAX(target_rank) OVER` and `COUNT(*) OVER` in the outer `SELECT` over the finished `chain`, not
inside the recursive term; the recursive term is a plain `UNION` of the seed with one join to the hop
table on the eight columns `ix_field_reference_step_hop_step` serves. So the fold removes nothing that
is there, and the walk standalone is milliseconds by this body's own table; the cost was the
per-driving-row re-evaluation, which any once-per-capture write removes.

The single-statement form is available. H2 2.4.240 accepts `INSERT INTO t WITH RECURSIVE chain AS
(...) SELECT ..., COUNT(*) OVER (...) FROM chain`, checked on the jar in the local repository with a
seed filtered to one graph. So the view text moves into the stage as phase 1's does, per graph, with
the `EXCEPT` oracle comparing two evaluations of the same text rather than two texts, and the
termination assertion is unnecessary because `UNION` reaches a fixpoint and `position` strictly
increases. A Java fixpoint loop has one precedent in the tree, `ClassificationDomainCapture`, which is
a `HAND_WRITTEN` producer admitted on the argument that no view could state its rule; this rule is
stated by a view, so the licence does not transfer. What would satisfy this: phase 2 (and phase 5,
"on the phase-2 shape") adopt the single-statement form, or the body states the H2 limit or the
measurement that makes the fold necessary, so an implementer is not left to pick.

**Non-blocking, question two.** Phase 0 says "the existing `sweep(dsl, sourceNames(tables),
touchedAt)` covers it". The sweep iterates `TABLES_TO_SWEEP`, a fixed list in `JooqFactCapture`,
so the new table is covered once it is added there; the discipline the body names is right, the
sentence understates the edit by one line.

**Non-blocking, question one.** Phase 2 says `intent_resolved_type_binding` "has five named readers".
Eight view bodies name it in the DDL (`intent_field_reference_step_target`,
`intent_field_column_scope_live`, `intent_field_reference_discovery`, `intent_type_backing`,
`intent_field_participant_scope_table`, `intent_field_scope_table_live`, `intent_carrier_routine_hop`,
`intent_input_field_filter_role_live`) and `StoreNodeTables` reads it from Java. The count only
strengthens the convert expectation, so nothing in the plan moves; the number should match whatever
`DerivedReadCostTest` says when the phase lands.

### Round 3 (2026-09-16, Spec -> Ready, reviewer session 01XUN2yJExpaZCTtLj3DPWqC)

Verdict: withhold. Nothing new is blocking; round 2's two blocking findings stand unanswered in the
body, and both were re-checked against the tree by this session rather than taken on trust. The plan
sections still read as they did when round 2 was written: phase 2 still describes a Java fold and
"two window functions inside a recursive term", phases 4 and 5 still say nothing about where their
producers run, and neither finding carries a response. A spec with an open blocking finding is not
Ready whatever a third reader thinks of it, so this round exists to record that the findings were
independently confirmed and to say what the next pass will look at.

**Round 2, finding 1 stands (question two).** `FactCapture.capture` runs `GraphitronFactCapture.capture`,
flushes, then runs `ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`,
`ArgMappingCandidates.derive`, `TypeBackingRows.derive` and `AuthoredClaimRejectionRows.derive`, and
only then `Materializations.refresh`. Recomputed from the DDL: the input-field walk's closure holds
`intent_input_occurrence_path`, `intent_input_occurrence_path_step` and `intent_type_backing_class`,
the argument walk's holds `intent_type_backing_class`, and rung 4's own reach holds all three through
`intent_type_backing`. All are `HAND_WRITTEN` and written after the gatherer. The field walk's closure
holds none, so phases 1 and 2 are placed correctly. What satisfies it is what round 2 said: phases 4
and 5 name where their producers run and why that position is current for every input, and the
"does not do" bullet widens from "registered target" to any target a later step of the pass writes.

**Round 2, finding 2 stands (question two).** In `intent_field_reference_step_target` as shipped the
recursive term is a plain `UNION` of the seed with one join to the hop on the eight indexed columns;
`DENSE_RANK`, `MAX(target_rank) OVER` and `COUNT(*) OVER` sit in the outer `SELECT` over the finished
`chain`. Phase 2's fold therefore removes nothing that is there, and the plan's own structural finding
("the rule stays stated once, in SQL") argues against it. What satisfies it is what round 2 said:
phase 2 and phase 5 adopt the single-statement `INSERT ... WITH RECURSIVE ... SELECT` per graph, or
the body names the H2 limit or measurement that makes the fold necessary.

**Everything else was re-verified and holds.** The closure of the three walks through `intent_`
relations is 27 relations with 8 registered, exactly the eight the ladder names, and every leaf is
`graphitron_`, `graphql_`, `sql_`, `jvm_` or `store_`. Rung 0's inputs are all captured facts, and
`graphitron_spelled_reference_entry` is written by `SdlCapture.captureGraphitronAnchors` before the
graphitron gatherer runs, so a stage placed before `FieldEndpoints.derive` reads current rows for
phase 1. `GraphitronFactCapture.capture` runs eight stages ending in `FieldEndpoints.derive`.
`JooqFactCapture.capture` writes `referentialConstraints` and closes with the sweep over
`TABLES_TO_SWEEP`; `CatalogFactCapture.capture` calls `captureExtensions` alone. Fourteen of the
fifteen `sql_` tables carry `touched_at`, `sql_table_record_supertype` being the one without. All
fourteen declared `sql_` relations name `catalog` as owner and none names `jooq`; `jooq` carries a
`catalog` corpus row and `graphitron` carries none. The `graphitron_` family is 127 tables and 2
views, `graphitron_argmapping_match` is among the views, and `sql_table_reference`,
`graphitron_spelled_table` and `graphitron_field_reference_step_hop` are free names. Both indexes the
body names exist with the comments it quotes, "would dominate the read" occurs once in the DDL and on
`intent_node_id_decode_hop`'s table comment, and R953 is `Backlog`. Every test class, gate method and
fixture the body names exists under that name.

**Corrected in passing.** Phase 2's "five named readers" of `intent_resolved_type_binding` is now the
count the DDL and `StoreNodeTables` give, per round 2's non-blocking note; the convert expectation it
supports is unchanged.

### Round 4 (2026-09-17, Spec -> Ready, reviewer session 01SHm9PuTU3FYYFhNeuyV7S3)

Verdict: withhold. Round 2's two blocking findings are still open, and both were re-derived from the
tree by this session rather than read off round 3. One new blocking finding, on the section the body
names as what the whole plan is built on: the structural finding reproduces the tree as it stood
before two commits of 2026-09-14, which is two days before the item was filed, and the ladder
carries a rung 0 relation one of them retired.

Question one is not what withholds, and the goal reads clearly without the phase list. A consumer with
a large schema gets a `graphitron:dev` round, a `generate` and a CI build whose cost grows with the
schema rather than with its square, because the `@reference` path every `@nodeId` decode reads stops
being a recursive view re-evaluated once per driving row and becomes rows the graphitron gatherer
wrote once per capture, which every reader then seeks into on an index. Nothing a consumer authors
changes; what changes is how long the build takes on a schema of real size.

**Round 2, finding 1 stands (question two).** Verified again from the source rather than from round 3.
`FactCapture.capture` runs `GraphitronFactCapture.capture`, flushes, then
`ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`, `ArgMappingCandidates.derive`,
`TypeBackingRows.derive` and `AuthoredClaimRejectionRows.derive`, and only then
`Materializations.refresh`. `intent_input_field_resolving_table_live`, which is rung 4, reads
`intent_input_occurrence_path` and `intent_input_occurrence_path_step`; `intent_type_backing`, which
rung 4 also reaches, reads `intent_type_backing_class`. All three are in
`MaterializeRegistryGateTest.HAND_WRITTEN` and all three are written after the gatherer, so a rung-4
stage placed inside `GraphitronFactCapture` reads the previous capture's rows exactly as it would a
registered target's. The field walk's closure holds none of them, so phases 1 and 2 stay correctly
placed. Phase 4 still says nothing about where its producer runs, phase 5 still says "on the phase-2
shape", and the "does not do" bullet still reads "no stage ever reads a registered target". What
satisfies it is what round 2 said.

**Round 2, finding 2 stands (question two).** In `intent_field_reference_step_target` as shipped, the
recursive term is a plain `UNION` of the seed with one join to `intent_field_reference_step_hop`;
`DENSE_RANK`, `MAX(target_rank) OVER` and `COUNT(*) OVER` are all in the outer `SELECT` over the
finished `chain`. Phase 2 still describes a Java fold with a termination assertion and still says the
fold replaces "two window functions inside a recursive term", which is not what the DDL holds, and
phase 5 still inherits the shape. What satisfies it is what round 2 said: adopt the single-statement
`INSERT ... WITH RECURSIVE ... SELECT` per graph, which is what the body's own "the rule stays stated
once, in SQL" asks for, or name the H2 limit or the measurement that makes a fold necessary.

**Finding 3 (question two, blocking). The structural finding, its family list and rung 0 are computed
from a tree two commits out of date, and one rung names a relation that no longer exists.**

`intent_name_matched_key_pair` is not in `graphitron-model.sql`. Commit 78b6a58, "a name-matched key
is a catalog fact, and only whole keys are keys", landed 2026-09-14 and retired it;
`DerivedReadCostTest`'s javadoc records the retirement and says a catalog gatherer now answers its
question as `sql_name_matched_key_column`. `intent_field_reference_step_hop_live` today reads
`graphitron_field_reference_step_entry`, `store_graph_source`, `sql_table`, `sql_constraint`,
`sql_referential_constraint`, `sql_name_matched_key_column`, `intent_spelled_table` and
`intent_condition_method_route`. So rung 0 holds two relations and not three, and the rung table's
second row is a rung an implementer would go looking for and not find.

The closure figure is from the same superseded tree. Parsing the shipped DDL statement by statement,
with line comments and string literals removed so every one of the 396 relations is seen, and
following each registered relation through its `_live` rule: the closure of the three walks today is
**26 `intent_` relations, 8 of them registered**, where the body says 27 and 8. At `78b6a58~1` the
same computation gives 27 and 8. The eight registrations are exactly the eight the ladder names, in
both trees; the single relation that left the closure is the retired one.

The leaf family list dates the paragraph the same way. The body says no relation in the closure "reads
anything that is not ultimately `graphitron_`, `graphql_`, `sql_`, `jvm_` or `store_`", which is the
list at `78b6a58~1` exactly. Today the closure's non-`intent_` leaves are `graphitron_`, `graphql_`,
`sql_`, `store_` and `code_`, with no `jvm_` relation in it at all: commit f826c5c, also 2026-09-14,
moved `intent_condition_method_route` off `jvm_class` and `jvm_method` onto `code_condition_method`
and `code_condition_method_parameter`.

The modelling claim survives all of this, which is why the finding is about the evidence rather than
the conclusion, and the direction is favourable rather than otherwise. `code_condition_method` is
written by `CodeCapture`, which `ModelCapture.capture` runs and which `AbstractRewriteMojo.runGenerator`
calls before the generator's own capture, so it is a captured fact and current when a graphitron stage
would read it. Twenty six relations bottoming out entirely in captured facts argues what twenty seven
did. And the input that replaced the retired view on rung 1 is a captured `sql_` table rather than a
plain view, which is a stronger base for the bottom-up argument than what it replaced.

What makes this the author's rather than a stale number a reviewer corrects in passing is phase 1's
closing paragraph. It says `intent_name_matched_key_pair`'s "owner computes to `catalog` and it stays
a plain view here", and that moving it into the catalog family is one of the nine misplacements R876
enumerates and that item's to take. That move has already happened by another route, so the paragraph
is instructing an implementer about a relation that is not there, and R876's count of misplacements
may have moved with it.

What would satisfy it: the structural finding's count and family list and the rung table recomputed
against the shipped DDL, phase 1's closing paragraph dropped or restated, and rung 1's read of
`sql_name_matched_key_column` named where rung 0's relations are named today. Whether the phase-1
scope changes at all is the author's call; on this session's reading it gets slightly smaller.

**Everything else re-verified this round holds.** The field walk's closure is 11 `intent_` relations
with exactly three registered, `intent_spelled_table`, `intent_field_reference_step_hop` and
`intent_resolved_type_binding`, and the six relations the body names as plain views around it,
`intent_bound_table`, `intent_routine_return_binding`, `intent_field_chain_terminus`,
`intent_field_chain_node`, `intent_field_chain_start` and `intent_field_navigated_type`, are all views
and all in that closure. `intent_spelled_table_live` is the three-way join the body describes, over
`graphitron_spelled_reference_entry`, `store_graph_source` and `sql_table`.
`MaterializeRegistryGateTest.REGISTRATIONS` is 23. `intent_resolved_type_binding` is named by eight
view bodies and by `StoreNodeTables`, which is the count round 3 corrected into the body.
`ix_field_reference_step_hop_step`'s comment carries the 18308-against-523 figures the body quotes.
`MaterializedRegistryFixture.scaledSdl` exists, as do `FieldEndpoints`, `Materializations`,
`JooqFactCapture`, `CatalogFactCapture`, `ClassificationDomainCapture`, `SchemaIdentifierDriftCheck`,
`StoreNodeTables`, `SchemaQueries`, `ClaimFacts`, `RefreshStages` and every test class the Tests
section names. `sql_table_reference`, `graphitron_spelled_table` and
`graphitron_field_reference_step_hop` are still free names.

### Author's response to round 4 (2026-09-17)

Not a review round: this is the author answering, and the session that wrote it is on the
`Claude-Session` trailer of the commit that carries it.

All three blocking findings answered in the body. Every figure the response leans on was recomputed
from the shipped DDL at `94e23bae4` in this session rather than read off the review, per the round-4
instruction, and where the recomputation disagrees with anything previously in this body the body now
carries the recomputed number and says it changed. Nothing here is a `status:` change; that stays a
reviewer's.

**Round 2, finding 1 (rung-4 stage placement). Taken, and the answer is placement rather than a
mechanism.** Verified from the source before writing: `FactCapture.capture` runs
`GraphitronFactCapture.capture`, flushes, then `ClassificationDomainCapture.derive`,
`InputOccurrencePaths.derive`, `ArgMappingCandidates.derive`, `TypeBackingRows.derive` and
`AuthoredClaimRejectionRows.derive`, and only then `Materializations.refresh`.
`intent_input_field_resolving_table_live` reads `intent_input_occurrence_path` and
`intent_input_occurrence_path_step`; `intent_type_backing` reads `intent_type_backing_class`; all
three are in `MaterializeRegistryGateTest.HAND_WRITTEN`, whose six members were read off the test
rather than assumed. So a rung-4 stage inside `GraphitronFactCapture` would read the previous
capture's rows.

The body now answers in four places. The structural finding's seam paragraph is rewritten as two
seams under one invariant, **a stage may not read a table a later step of the same pass writes,
registered or hand-written**, with conversion order carrying the registered half and placement
carrying the other. Phase 1 gains a "Where this phase's stages run" paragraph putting rungs 0 to 3
inside `GraphitronFactCapture` before `FieldEndpoints.derive`, admissible because the field walk's
closure holds none of the six hand-written tables. Phase 4 gains the same paragraph putting rungs 4
and 5 in the derivation stratum after the five producers and before the refresh, the position
`ArgMappingCandidates.derive` already occupies. Phase 5 says it runs where rung 4 does and why. The
"does not do" bullet is widened from "no stage ever reads a registered target" to the invariant above.

The sibling item that settles the analogous question for its fifteen registrations was read and its
answer is adopted rather than argued against, but the split is worth stating because the two items
reach different placements from one criterion. All fifteen of that item's relations bottom out in at
least one hand-written table bar one, so all fifteen go to the stratum. Rungs 0 to 3 here bottom out
in none, so they stay in the gatherer beside the precedent they copy; rungs 4 and 5 do, so they join
the stratum. The rule is what an input reaches, not which item the stage belongs to, and both items
now state it the same way. The gate that sibling proposes for the order is that item's to build and
this body does not pre-empt it; the placements here satisfy it either way.

Phase 4's reach list also gained the relation round 2 said it stopped one short of, and two more
beside it: `intent_type_backing_class` under `intent_type_backing`, and
`intent_input_occurrence_path` and `intent_input_occurrence_path_step` under
`intent_input_field_resolving_table`.

**Round 2, finding 2 (phase 2's shape claim). Taken in full: the claim was false and the fold is
gone.** Read in the shipped DDL rather than from the review: in `intent_field_reference_step_target`
the recursive term is a plain `UNION` of the seed with one join to `intent_field_reference_step_hop`
on the eight columns `ix_field_reference_step_hop_step` serves, and `DENSE_RANK`, `MAX(target_rank)
OVER` and `COUNT(*) OVER` are all in the outer `SELECT` over the finished `chain`. Phase 2 now adopts
the single-statement `INSERT INTO ... WITH RECURSIVE ... SELECT` per graph, which is the remedy the
reviewer named and the one this body's own "the rule stays stated once, in SQL" asks for. The body
states the wrong reading and the correction rather than quietly replacing the paragraph, because the
fold was argued for on that reading and a reader who saw the earlier text is owed the retraction.

Four reasons are given for the single statement and none of them is the reviewer's authority: the
fold removes nothing that is there; the cost was the per-driving-row re-evaluation, which any
once-per-capture write removes; the termination assertion is unnecessary because `UNION` reaches a
fixpoint and `position` strictly increases; and `ClassificationDomainCapture`, the one Java fixpoint
loop in the tree, is admitted on an impossibility argument a rule stated by a shipped view cannot
borrow. The H2 acceptance is cited as round 2's check on the pinned 2.4.240 rather than restated as
this session's, and the implementer is told to re-check.

Phase 5 inherits the corrected shape and now says so explicitly rather than by reference. Both other
walk bodies were read to confirm the shape transfers: `intent_argument_reference_step_target_live` and
`intent_input_field_reference_step_target` are each the same recursive `UNION` with the three window
functions in the outer `SELECT`, differing only in the seed.

**Finding 3 (the structural finding is two commits stale). Taken, recomputed independently, and the
staleness reached three further paragraphs the finding did not name.** The recomputation was done from
the shipped DDL with line comments and string literals stripped, all 396 relations seen (270 tables,
126 views), registered relations resolved through their `_live` rules, and it agrees with the review
on every figure the review gave:

- the closure of the three walks through `intent_` relations is **26 relations, 8 registered**, where
  this body said 27 and 8;
- the non-`intent_` leaves are `graphitron_`, `graphql_`, `sql_`, `store_` and `code_`, with no `jvm_`
  relation anywhere in the closure;
- `intent_field_reference_step_hop_live` reads `graphitron_field_reference_step_entry`,
  `store_graph_source`, `sql_table`, `sql_constraint`, `sql_referential_constraint`,
  `sql_name_matched_key_column`, `intent_spelled_table` and `intent_condition_method_route`;
- `intent_name_matched_key_pair` is not in the schema;
- the field walk's closure is 11 `intent_` relations with 3 registered.

The body's count, family list and rung table are corrected to these, the recomputation is dated and
attributed to a commit so the next reader can re-take it rather than trust it, and the method is
stated in two sentences for the same reason. Rung 0 now holds two relations. Rung 1's read of
`sql_name_matched_key_column` is named where rung 0's relations are named, together with the fact that
makes it admissible: `NameMatchedKeys.derive` writes it inside `FactCapture.capture` after the two
crawlers and before both `SdlFactCapture` and `GraphitronFactCapture`, so it is current for a rung-1
stage with nothing to arrange. Phase 1's closing paragraph about the retired relation is dropped, its
place taken by the stage-placement paragraph. The "does not do" bullet that promised not to move it
into the catalog family is dropped, that move having already happened by another route. The remaining
two namings of the stale figure, in "Relation to other items" and in "Other solutions", are corrected
to 26.

**Three further things the same two commits made stale, found while recomputing and not named in any
round.** Stated here rather than folded in silently, because they are the same defect and a reader
should be able to see how far it reached.

- *The inlining figures.* This body priced the register at "size 12 against 4796" for
  `intent_node_id_decode_hop_live`, "4 against 163" for the field walk and "4 against 709" for the
  input-field walk. Reimplementing `InlineMultiplicityCheck.subtree` and running it over the shipped
  DDL reproduces the three *shipped* figures exactly (12, 4, 4), which is the check that the
  reimplementation is the tool's metric and not a lookalike, and gives **4448, 145 and 655** for the
  demoted ones. The drop is consistent with the diagnosis: `intent_name_matched_key_pair` was a plain
  view under the hop and its subtree left the closure when it was retired. The body carries the new
  figures, the old ones, and the reason they moved.
- *The `sql_` family's size.* Phase 0 said "Fourteen of the fifteen `sql_` tables carry" a
  `touched_at`. There are **sixteen** `sql_` tables today and fourteen carry one; the two without are
  `sql_table_record_supertype` and the newly arrived `sql_name_matched_key_column`. Neither is a
  precedent this relation can take and they are not the same case as each other, which phase 0 now
  says rather than grouping them: `NameMatchedKeys.derive` clears its relation whole, which a
  derivation over the store may do and a reading of a consumer's database may not, while
  `JooqFactCapture.recordSupertypes` neither stamps nor clears because every column of its relation is
  a key column. `sql_table_reference` carries a `constraints` count, the non-key column a stale row
  would lie about, so it is on the stamp-and-sweep arm and neither of theirs.
- *The declared-ownership argument.* Phase 0 said "All fourteen declared `sql_` relations name
  `catalog` as owner while `JooqFactCapture` writes them". Fifteen are declared today, all naming
  `catalog`, and the fifteenth is `sql_name_matched_key_column`, which `NameMatchedKeys.derive`
  writes and which `FactCapture.capture`'s own comment calls "A stage of the catalog gatherer rather
  than a derivation". So that one declaration is *right*, and the mismatch is fourteen of fifteen
  rather than fourteen of fourteen. The verdict for `sql_table_reference` is unchanged and the
  argument for it is stronger: a `sql_` relation may legitimately be `catalog`-owned, so the question
  has to be decided on who writes the relation, which for this one is `JooqFactCapture`.

**Round 2's unanswered non-blocking note, taken.** Phase 0 said "the existing `sweep(dsl,
sourceNames(tables), touchedAt)` covers it", which understated the edit by one line. `TABLES_TO_SWEEP`
is a fixed fourteen-entry list in `JooqFactCapture` that `sweep` iterates in reverse, and the body now
names it and says the new table is added there.

**Phase 3 and one live finding folded into phase 2.** The acceptance measurement is being taken right
now against a copy of a real `sis` store, in another session, and **no number from it is written into
this body**. Phase 3 and the conditioning section both say it is in progress, name the six
configurations that section already listed, and leave every outcome branch standing. What the live
round has established about *shape* rather than about cost is folded into phase 2, because it changes
a claim that was overstated there. Phase 2 used to say flatly that storing the walk "is what reaches
`intent_node_id_instruction_live`, the 592 s statement R953 measured, since that rule reads the field
walk once per `table_node` row". Two things are wrong with that. The driving relation is `instructed`
and not `table_node`: the walk is read inside a CTE named `slot_table`, `slot_table` is derived from
`instructed` and then joined back to `instructed`, and H2 inlines a non-recursive `WITH` exactly like
a view, so `slot_table` is recomputed once per `instructed` row with the walk inside it. And "reaches"
overclaims, because storing the walk removes the recursion from the inside of that loop and leaves
the loop. That relation's own `meta_materialize.reason` says which of the two the fix is, and it is
quoted in the body as it stands: "snapshotting the inner alias into a table put the arm at 0.7 s,
which is the shape of the fix and the reason this is a registration rather than a rewrite", and "The
narrower registration that would cut this one is the inner alias, which is a local alias rather than a
named relation today and wants promoting to one before it can be registered." Phase 2 now claims a
term rather than the whole, and a new "does not do" bullet puts the alias promotion out of scope and
records that it is not filed anywhere as of 2026-09-17.

**One thing left for a later round on purpose.** The live measurement's partial readings on a fresh
`sis` store at `94e23bae4` are provisional and are deliberately not in the body: 21 of the 23
registrations refresh between 34 ms and 5.8 s, `intent_node_id_instruction` took 356.5 s for 683 rows,
and `intent_node_id_decode_hop` had not finished after 25 minutes. They are recorded here as the
provenance for the phase-2 qualification above and nowhere else. They are not the acceptance
measurement, which is the six-configuration sweep phase 3 names, and they should not be read as one or
copied into "What was measured" until that sweep finishes.

**One finding for a neighbouring item rather than this one.** R876's own enumeration of family-local
misplacements still lists `intent_name_matched_key_pair` under the `catalog` owner, and that relation
has not existed since 78b6a58. That count is one row stale. It is named in "Relation to other items"
here and left for that item to re-take, this item touching no other item's body.

### Round 5 (2026-09-17, Spec -> Ready, reviewer session 012mGDp2tCWRZnnLhXg275rz)

Verdict: withhold. Round 2's two blocking findings and round 4's are answered, and each answer was
re-derived from the shipped tree by this session rather than read off the response. One new blocking
finding, of the same kind as round 4's and out of the same window: the body's account of the pass it
places stages in is taken from a tree nine DDL commits old. `FieldEndpoints.derive` is not the last
stage of `GraphitronFactCapture.capture` any more, and the invariant the placement argument
discharges is enumerated in two cases where the shipped pass now has three.

Question one clears and is not what withholds. A consumer with a large schema gets a
`graphitron:dev` round, a `generate` and a CI build whose cost grows with the schema rather than with
its square, because the `@reference` path every `@nodeId` decode reads stops being a recursive view
re-evaluated once per driving row and becomes rows the graphitron gatherer wrote once per capture.
The goal paragraph reads on its own and every term it needs is glossed where it first appears.

**Round 2, finding 1 (rung-4 stage placement) is answered.** Recomputed rather than accepted:
`MaterializeRegistryGateTest.HAND_WRITTEN` holds six relations, `intent_type_domain`,
`intent_type_backing_class`, `intent_authored_claim_rejection`, `intent_input_occurrence_path`,
`intent_input_occurrence_path_step` and `intent_field_unlowerable_ordering_rejection`. Taking each
rung's closure against that roster: rungs 0, 1, 2 and 3 reach none of the six, rungs 4 and 5 reach
exactly the three the body names. `FactCapture.capture` runs `GraphitronFactCapture.capture`, flushes,
then the five producers, then `Materializations.refresh`, as the body says. So the split the body
makes, rungs 0 to 3 in the gatherer and rungs 4 and 5 in the derivation stratum, is the split the
closures give, and the "does not do" bullet now carries the invariant in the general form round 2
asked for.

**Round 2, finding 2 (phase 2's shape claim) is answered.** In `intent_field_reference_step_target`
as shipped the recursive term is a plain `UNION` of the seed with one join to the hop, and
`DENSE_RANK`, `MAX(target_rank) OVER` and `COUNT(*) OVER` are all in the outer `SELECT` over the
finished `chain`, exactly as the body now states. The fold is gone and the single-statement `INSERT
... WITH RECURSIVE ... SELECT` per graph is what phases 2 and 5 take.

**Round 4, finding 3 (the structural finding is stale) is answered.** Recomputed independently from
the shipped DDL, parsed statement by statement with comments and string literals stripped, registered
relations resolved through their `_live` rules: 396 relations (270 tables, 126 views), 23
registrations, and the closure of the three walks through `intent_` relations is 26 relations with 8
registered, which are exactly the eight the ladder names. The non-`intent_` leaves are `graphitron_`,
`graphql_`, `sql_`, `store_` and `code_`, with no `jvm_` relation in the closure.
`intent_name_matched_key_pair` is not in the schema. The field walk's closure is 11 with 3 registered.
Every figure in the author's response reproduces.

**Finding 4 (question two, blocking). `FieldEndpoints.derive` is no longer the last stage of the
gatherer, and the invariant's two cases are three in the shipped pass.**

`GraphitronFactCapture.capture` today runs ten steps: `FieldChainApplications.derive`,
`TableTypes.derive`, `Nodes.derive`, `NodeKeyColumns.derive`, `MacroCapture.expand`,
`ElementAnchors.derive`, `navigation`, `FieldEndpoints.derive`, `FieldRoutines.derive` and
`FieldTableLinks.derive`. `FieldEndpoints.derive` is third from last. The body says otherwise in four
places: the `Goal`'s gloss of a capture stage ("of which `GraphitronFactCapture.capture` runs eight,
the last being `FieldEndpoints.derive`"), "The target the conversions copy already exists"
("`FieldEndpoints.derive` is the last stage of `GraphitronFactCapture.capture`"), and phase 1 twice,
where it puts both of its stages "immediately before `FieldEndpoints.derive`".

The window is the one round 4 opened. Between the tree round 3 read and `94e23bae4` there are nine
commits to `graphitron-model.sql`, three of which add a `graphitron_` table and a stage to write it:
`graphitron_field_chain_link`, `graphitron_field_routine` and `graphitron_field_table_link`. Round 4's
recomputation covered the DDL-derived figures and did not reach the two Java-derived ones, which is
how a body dated `94e23bae4` still describes an eight-stage gatherer. The source helped it along:
`FieldEndpoints.derive`'s own comment in `GraphitronFactCapture` still opens "Last, because", with two
stages under it and the next comment beginning "After it". That comment is a neighbouring defect and
belongs to whichever item owns those stages, not to this one.

Why this is about the argument and not only the count. The body's invariant is stated generally and
correctly, a stage may not read a table a later step of the same pass writes, and then discharged in
exactly two cases: a registered target, answered by conversion order, and a hand-written producer's
output, answered by placement. The shipped pass has a third, an ordinary `graphitron_` table written
by a later stage of the same gatherer, and neither answer reaches it. "The seam is two seams, and each
has its own answer" is the sentence that is now wrong, and it is the sentence phases 1 and 2 place
their stages on. An implementer told that `FieldEndpoints.derive` ends the pass has no reason to look
at what follows it, which is the situation round 2 blocked to prevent.

The conclusion survives, and the computation is here so the revision is a paragraph rather than a
redesign. The three tables those two stages write appear in no rung's closure. Rungs 2 and 3 do reach
`graphitron_field_chain_application`, `graphitron_field_navigation`, `graphitron_routine_entry` and
`graphitron_table_entry`, and all four are written at or before `navigation`, so before
`FieldEndpoints.derive` and before any position phase 1 or phase 2 would take. Placement before
`FieldEndpoints.derive` is therefore still admissible for rungs 0 to 3; what is missing is the body
knowing why.

What would satisfy it: the stage list and `FieldEndpoints.derive`'s position in it recomputed against
the shipped tree wherever the body states them; the invariant's discharge widened to the third case
and discharged for this ladder by naming the three tables and the fact that no rung reads them; and
phase 1 saying whether "immediately before `FieldEndpoints.derive`" is still the intended position or
whether the intended position was the end of the gatherer, which is now a different line. Whether the
position moves is the author's call; on this session's reading either works and the first is the
smaller change.

**Corrected in passing.** "the `graphitron_` family now holds 127 tables and 2 views" is 130 and 2 on
the shipped tree, the three additions being the tables named above. The claim the sentence supports,
that neither shape is new, is unaffected, and the same count in round 3's findings is left alone,
being true of the tree that round read.

**Everything else re-verified this round holds.** Rung 1's inputs are exactly the eight the body
lists, `graphitron_field_reference_step_entry`, `store_graph_source`, `sql_table`, `sql_constraint`,
`sql_referential_constraint`, `sql_name_matched_key_column`, `intent_spelled_table` and
`intent_condition_method_route`; rung 2 reads `intent_bound_table` and `intent_routine_return_binding`
and rung 3 reads rungs 1 and 2. The six relations named as plain views under the field walk are all
views. `NameMatchedKeys.derive` runs after the two crawlers and before `SdlFactCapture` and
`GraphitronFactCapture`. `CodeCapture.capture` runs from `ModelCapture.capture`, which the mojo's
`captureModel` calls before it invokes the generator, so `code_` rows are on disk before the pass
this item's stages sit in. `CatalogFactCapture.capture` calls `captureExtensions` alone;
`JooqFactCapture`'s javadoc is "The `sql_` family and nothing else" word for word, and
`TABLES_TO_SWEEP` is a fourteen-entry list swept in reverse. There are sixteen `sql_` tables, fourteen
carry `touched_at`, and the two without are `sql_table_record_supertype` and
`sql_name_matched_key_column`, which the body now treats as two cases rather than one. Fifteen `sql_`
relations are declared in `meta_relation` and every one names `catalog`.
`sql_referential_constraint`'s primary key is `(source_name, table_schema, table_name,
constraint_name)`, so the body's three-column-prefix reading is right.
`ix_field_reference_step_hop_step`'s comment carries 18308 against 523. `sql_table_reference`,
`graphitron_spelled_table` and `graphitron_field_reference_step_hop` are free names.

### Author's response to round 5 (2026-09-17)

Not a review round: this is the author answering. **This round's commit carries no `Claude-Session`
trailer**, unlike the round-4 response, because it was written by a subagent and subagent commits do
not get one. The next reviewer should read the commit's own message for who authored it rather than
the trailer.

Finding 4 is answered in the body, and the phase-3 measurement the body has been waiting on since it
was filed is now in it, which is the larger of the two changes. Every claim below was verified against
the shipped tree at `94e23bae4` in this session rather than read off the review, and where the
verification disagrees with the review the body carries what the tree says and this response names the
difference.

**Finding 4 (the gatherer's stage list and the invariant's cases). Taken, and the reviewer's
conclusion reproduces.** `GraphitronFactCapture.capture` runs the ten steps the review lists, in that
order, and `FieldEndpoints.derive` is the eighth. Four places in the body said eight and last; all four
are corrected, in the goal's gloss, in "The target the conversions copy already exists", and twice in
phase 1. The seam paragraph is rewritten from two cases to three, the third being an ordinary
`graphitron_` table a later stage of the same gatherer writes, and the "does not do" bullet that
enumerated two carriers now enumerates three.

**One correction to the finding, on the third case's discharge.** The review names three tables as
what the two trailing stages write. It is two. `FieldRoutines.derive` writes `graphitron_field_routine`
and `FieldTableLinks.derive` writes `graphitron_field_table_link`; `graphitron_field_chain_link` is
written by `GraphitronAnchor.chainLinks`, reached through `SdlCapture.captureGraphitronAnchors`, which
`FactCapture.capture` calls on the line *before* `GraphitronFactCapture.capture`. `FieldTableLinks`
only reads it, for `max(position)` and as a join. Its `meta_relation` row declares owner `document`
against `graphitron` for the other two, which is the same fact from the declaration side. The three
tables were added by the same window of commits, which is presumably how they came to be grouped; the
writers differ. The discharge is unaffected and is stated on the two: a scan of the DDL finds every
occurrence of either name inside its own `CREATE TABLE` and `COMMENT` block or in `meta_relation`, so
no view body names either and no rung's closure can reach them.

**One widening, on what rungs 2 and 3 do reach.** The review names four `graphitron_` relations. The
closure computed here, parsing the DDL statement by statement with comments and string literals
stripped and registered relations resolved through their `_live` rules, holds eight:
`graphitron_argument_reference_step_entry`, `graphitron_field_chain_application`,
`graphitron_field_navigation`, `graphitron_field_reference_entry`,
`graphitron_field_reference_step_entry`, `graphitron_routine_entry`,
`graphitron_spelled_reference_entry` and `graphitron_table_entry`. The review's four are among them and
its conclusion holds for all eight: six are entry relations the transcription writes and the flush
inside `capture` publishes before the first stage runs, `graphitron_field_chain_application` is the
first stage and `graphitron_field_navigation` is the seventh, so every one is written at or before
`navigation` and before any position phase 1 or phase 2 would take. The body states eight rather than
four so a later reader checking it finds the same set.

**The position call, which the review left to the author: it stays immediately before
`FieldEndpoints.derive`.** Phase 1 now argues it rather than inheriting it. The invariant is
forward-only, so a position has to clear what runs after it, and the eighth position clears every
input rungs 0 to 3 reach with two stages to spare. Placing them at the end of the gatherer instead
would assert a dependency on `FieldRoutines.derive` and `FieldTableLinks.derive` that does not exist,
and a later reader would have to re-derive that it does not. The end of the gatherer is equally
admissible on the closures and the body says so, with the instruction that an implementer who finds
the order changed again takes whichever line satisfies the three cases and records which.

Checked and not needed: none of the three trailing stages reads any `intent_` relation at all, so none
of them is reading a registered target the refresh fills later. Checked to back an absolute: the two
stages after `FieldEndpoints.derive` touch no relation beyond those two tables, each writing its own
with an `INSERT ... SELECT` and sweeping its own with a `DELETE`, and neither issues a merge or an
update anywhere. Left alone as the review asks: the
"Last, because" comment in `GraphitronFactCapture`, which is a neighbouring defect belonging to
whichever item owns those stages, and round 3's "127 tables and 2 views".

**Phase 3's acceptance measurement is taken and written into the body, and phase 3 is retitled to say
so.** It replaces the "in progress" status paragraph, and the four outcome branches under "What this
plan was conditioned on" are replaced by what the measurement found and by the scope call. The
configurations themselves stay, because "Tests" asks for the same ones re-taken on the shipped tree
per phase. Every figure in the body is attributable to the probe record or to the failing round's own
log, every single execution says it is one, and the three caveats that would otherwise be lost are
carried: the trunk store's condition family is empty so every shape figure is taken on a walk two and
a half times smaller than a full one and is conservative rather than optimistic; storing a walk
*moves* the statistics cliff to one refresh evaluation rather than removing it, and the snapshots were
built analysed so they price the read and not that refill; and the fanout view's answer preservation
is equivalence by substitution, not an `EXCEPT` against a relation that does not return.

**The scope call, which is the author's and is argued in the body.** The branches that section carried
are not mutually exclusive against this evidence and two of them fire at once, so the body says that
first rather than forcing a fit. **Phases 0 to 2 are this item; phases 4 and 5 split to a successor.**
The load-bearing reason is not the refresh pass, which R953's lever 2 alone takes into seconds, but
`intent_field_reference_step_fanout`: an unregistered plain view read by a lint outside the pass, which
did not return in sixteen minutes in the live round with `intent_field_reference_step_hop` analysed six
seconds before that read began, and which phase 2 takes to 0.37 s. No statistic reaches it. That is
this item's priority-1 ground and it is not R953's. Phases 4 and 5 leave on this item's own stated
rule, that a reach into a second family on a measurement that did not name it is scope creep: the
input-field walk is a real 2.66 s of the decode-hop rule's 4.124 s, and 2.66 s is a residual rather
than the failure.

**One thing the round did not name, found while making the call: the goal promised what the call
removes.** The goal paragraph answered question one by saying the `@nodeId` decode rule's cost stops
growing as the square of the store's size, and the cost claim under it said the same. Both are the
successor's after the split, because the walk that rule names is the input-field one and phase 3
establishes that the field walk is not in its closure at all. They are also the causal story phase 3
refutes: the twenty minutes is a statistics cliff plus a relation that does not return, and not the
decode rule's shape. The goal is re-grounded on the two things that actually run long and on which of
them is this item's, the cost claim now says what this item delivers of it, "What is in scope" marks
rungs 4 and 5 as the successor's, the scope call states the price, and a new "does not do" bullet
says the decode rule's remaining per-row term stands until the successor lands. A reviewer checking
question one against phase 3 would otherwise have found the goal answering with a deliverable the
item had dropped.

The fanout view is named as a beneficiary in phase 2 and in phase 3 and is not taken into scope: the
view is not edited, its `pair` alias is not promoted, and nothing about it is filed, because with the
walk stored there is 0.28 s left in it. The existing "does not do" bullet on
`intent_node_id_instruction_live`'s `slot_table` alias stands unchanged in its stance and gains the
measured figures, which make it an optimisation rather than a requirement: 436.1 s to 0.91 s is the
walk, and 0.91 s to 0.06 s is the alias.

**Body text the measurement made stale, corrected in the same pass**: the goal's cost claim and its
priority-1 sentence, "What was measured"'s opener, phase 2's paragraph on the instruction rule, phase
4's split-if sentence, phase 5's register-row count, the R876 and R953 paragraphs, the "R953's lever 2
alone" and "register the two unstored walks" bullets under other solutions, and the acceptance-evidence
bullet under "Tests", which now names the three readings a proxy cannot stand in for.

**One arithmetic note.** The failing round's other 21 registrations inserted 29413 rows in 23.2 s,
computed from the log's own per-registration lines and from 2915.7 minus 2892.5. The body carries
those rather than any rounder figure.

### Round 6 (2026-09-17, Spec -> Ready, reviewer session 014DVampdhrbmzh4U1ezxb6J)

Verdict: withhold. Round 5's finding 4 is answered, and every figure this round could recompute from
the shipped tree reproduced, including the ones rounds 4 and 5 found stale. Two new blocking findings,
both on question two, both about the same thing: the rename that carries the whole conversion argument
walks the converted relations into a gate the body never names, and the reconciliation discipline the
stages need is not the one the body cites as its precedent. Neither is a redesign. One non-blocking
precision note follows them.

Question one clears. A consumer with a large schema gets a `graphitron:dev` round in which a lint's
read that today does not return answers in under a second, because the `@reference` walk it expands
eight times is rows the graphitron gatherer wrote once per capture rather than a recursive view. The
goal paragraph stands alone, every project term arrives glossed, and the causal story it rests on
checks out: at read time `intent_node_id_decode_hop_live` reaches the input-field walk and does not
reach the field walk, which is what makes the scope call's split the closures' split rather than a
preference.

**What reproduced.** Recomputed independently, parsing the DDL statement by statement with comments
and string literals stripped and registered relations resolved through their `_live` rules: 396
relations (270 tables, 126 views), 23 registrations, and the three walks' `intent_` closure is 26 with
8 registered, which are exactly the eight the rung table names. Leaf prefixes are `code_`,
`graphitron_`, `graphql_`, `sql_` and `store_`, with no `jvm_`. The field walk's closure is 11 with 3
registered, and the six relations the body names as plain views under it are all views.
`intent_name_matched_key_pair` is gone. `GraphitronFactCapture.capture` runs the ten steps the body now
lists and `FieldEndpoints.derive` is the eighth; `graphitron_field_routine` and
`graphitron_field_table_link` occur only inside their own `CREATE TABLE` and `COMMENT` blocks and in
`meta_relation`, so no rung's closure can reach them, and `graphitron_field_chain_link` declares owner
`document`, which is the author's correction to the review standing up. In
`intent_field_reference_step_target` the recursive term is a plain `UNION` with one join to the hop and
all three window functions sit in the outer `SELECT` over the finished `chain`.
`intent_field_reference_step_fanout`'s `pair` CTE names the walk twice and `pair` is named four times
below it, which is the eight expansions. `intent_node_id_decode_hop_live` inlines the foreign-key count
as an anonymous derived table on the inner side of a `LEFT JOIN`, and it projects `d.constraint_name`
and tests it in the outer `WHERE`, which is the reach phase 0's own paragraph already anticipates.
`HAND_WRITTEN` holds the six named; `REGISTRATIONS` is 23; `TABLES_TO_SWEEP` is fourteen entries swept
reversed; sixteen `sql_` tables, fourteen stamped, the two unstamped the two the body treats as two
cases; fifteen `sql_` relations declared and every one `catalog`; `graphitron_` is 130 tables and 2
views; `ix_field_reference_step_hop_step`'s comment carries 18308 against 523; and
`nothingMaterializesOutsideTheMechanism` filters to `intent_`-prefixed base tables, so the family move
does leave its scan's scope exactly as the body argues. The DDL has not moved since `94e23bae4`, so the
window that produced rounds 4 and 5 is closed for this pass.

**Finding 5 (question two, blocking). The rename forces a `meta_relation` declaration, and three of
the four converted relations cannot carry one.**

The body's argument for moving a converted relation to `graphitron_` is that it leaves
`nothingMaterializesOutsideTheMechanism`'s scan rather than weakening `HAND_WRITTEN`'s impossibility
criterion. That half is right. What the body does not follow is where the new name lands instead.

`MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks` asserts that the observed relations carrying
no `meta_relation` row, no `meta_materialize` row naming them as a source view, and no
`meta_stated_relation` row are *exactly* the frozen roster in `undeclared-relations.txt`. Its own
message states the ratchet: "a missing entry is a new relation that must be declared rather than added
to the roster". All five relations the ladder touches sit on that roster today under their `intent_`
names. A conversion removes the `intent_` name and introduces a `graphitron_` one, and the new name has
three doors: the frozen roster, which is frozen and which a new name may not join; a registration's
source view, which is the thing this item exists to retire; and `meta_stated_relation`, which is for
relations whose rows the DDL file itself supplies, "for example lint_rule", and not for a stage-written
derivation. So every conversion in phases 1 and 2 owes a `meta_relation` declaration. The Tests section
says `MetaDeclarationGateTest` "binds on any converted relation that carries a declaration", which
reads the obligation as optional; the roster ratchet makes it mandatory, and for every rung rather than
for some.

That obligation then meets `aDeclaredTableKeyMatchesItsGrain`, which for every declared `BASE TABLE`
requires the table's primary-key shape to equal its grain's `key_shape`. `meta_grain.key_shape` is
`NOT NULL` with `CHECK (CHAR_LENGTH(key_shape) >= 1)`, and the gate compares against a map built from
`INFORMATION_SCHEMA`, so a table with no primary key has no entry and is an offender against any
declared shape. All 133 declared base tables in the shipped tree carry a primary key; there is no
counter-case to lean on.

Three of the four conversions are keyless. `intent_field_reference_step_hop` declares fifteen columns,
every one nullable, and no primary key. `intent_resolved_type_binding` has none either, and phase 2
expects it to convert rather than demote. And `intent_field_reference_step_target` is keyless *by this
plan's own decision*: "Indexed and not keyed: the grain includes `constraint_name` and `fk_on_from`,
both meaningfully nullable, and H2 refuses a primary key over a nullable column". Only
`intent_spelled_table` is keyed and converts cleanly.

So the item is in a pincer of its own making, and it is worth stating that way because either half
alone looks fine. Keeping the `intent_` prefix trips `nothingMaterializesOutsideTheMechanism`, which is
why the body renames. Renaming forces a declaration, which trips the key gate, which is what the body
does not check. Today these relations sit outside both gates for one reason only, that they are
registration targets on a frozen roster, and the conversion is precisely what removes that standing.

This is the author's design call and not the reviewer's. Four arms are visible from here and the body
should take one and say why: make the grain keyable, which costs `NOT NULL` on the key columns and an
answer for the `via = 'CONDITION'` arm where `constraint_name` is genuinely absent; declare a grain
whose `key_shape` is a real key over a narrower relation and carry the nullable columns beside it;
extend the key gate to admit a declared keyless table with a stated reason, which is a change to a gate
this item would then own; or convert only the keyed rungs and demote the rest, which changes what the
item delivers. What would satisfy the finding is the body naming the gate, saying which arm each of the
three keyless relations takes, and the Tests section stating the declaration obligation as per-rung
rather than conditional.

**Finding 6 (question two, blocking). What removes the previous capture's rows is unstated, and the
precedent the body names is the one that does not transfer.**

A registration reconciles by `Materializations.refreshPartition`: `DELETE FROM target WHERE graph_name
= ?` then `INSERT INTO target SELECT * FROM source`. That is what keeps a second capture of the same
graph into the same store from stacking rows on the first, which is the ordinary `graphitron:dev` case,
the store persisting across rounds.

The phases specify the insert and not the delete. Phase 1 moves each rule "into the stage's `INSERT ...
SELECT`" and phase 2 writes the walk "as **one statement per graph**: `INSERT INTO
graphitron_field_reference_step_target WITH RECURSIVE chain AS (...) SELECT ...`". One statement. On a
keyless target nothing rejects the duplicates, so the second dev round doubles the walk and the readers
above it silently fan out.

The precedent the body offers makes this harder rather than easier. "The target the conversions copy
already exists" points at `FieldEndpoints.derive`, and that stage reconciles by upserting with
`onDuplicateKeyUpdate` on the target's primary key and then sweeping `WHERE graph_name = ? AND
touched_at <> ?`. Both halves need what finding 5 says these relations do not have: a key to collide on
and a `touched_at` to sweep by. `graphitron_field_table`, `graphitron_field_routine` and
`graphitron_field_table_link` all carry both. So an implementer told to copy `FieldEndpoints.derive`
reaches for a discipline that is unavailable on three of the four relations, and the body's own phase-0
paragraph shows the author reasoning carefully about exactly this for `sql_table_reference` while the
ladder's own relations get nothing. The parenthetical "It admits a real primary key, which nothing on
the ladder does" is the place the thought stopped.

The precedent that does transfer is in the tree and the body already cites it for something else:
`ArgMappingCandidates.derive` opens with `dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName))` and then
inserts, which is `refreshPartition`'s discipline run by the owner instead of the register, on a target
carrying no `touched_at`. Naming that as the shape each converting stage takes is a paragraph, and it
also strengthens the item's thesis: what a conversion changes is who runs the reconciliation and when,
not what the reconciliation is.

The Tests section compounds this rather than catching it. `EXCEPT` is set semantics, so `A EXCEPT B`
and `B EXCEPT A` both return zero rows when A holds every row twice; and on a fresh store the stage
runs once, so there is nothing to duplicate. The oracle the plan calls "the oracle the whole plan rests
on" is blind to this defect on both counts. Whatever arm the body takes, the acceptance evidence wants
a second capture into the same store asserting the row count is unchanged, which is the one reading
that distinguishes a stage that reconciles from one that appends.

**Finding 7 (non-blocking, question one). The scope call's first heading overstates the paragraph
under it.**

"**Phase 2 is what makes the `sis` build finish, independently of R953**" argues necessity in its own
prose, that the fanout read is outside the pass and no statistic reaches it, and then claims
sufficiency in its heading. On this body's own figures it is not sufficient. With phase 2 landed and
R953 not, `intent_node_id_instruction` goes from 436.1 s to 0.91 s in the failing regime, and
`intent_node_id_decode_hop` is untouched by this item's scope at about 2536 s, so the pass is still
tens of minutes. The body knows this: the contingency bullet says "the pass is then still minutes", and
the Tests section's acceptance evidence correctly asks for the fanout answering rather than for the
build finishing. The goal paragraph is careful too, claiming only that the relation answers. It is one
heading out of step with the rest, and the accurate form is that phase 2 is necessary for the round to
finish and reachable by nothing else, while the pass still needs lever 2.

**Corrected in passing: nothing.** Every claim this round checked held, which is worth recording
because the last two rounds each found a stale one.

### Author's response to round 6 (2026-09-17)

Not a review round: this is the author answering. **This round's commit carries no `Claude-Session`
trailer**, as the round-5 response's did not, because it was written by a subagent and subagent commits
do not get one. The next reviewer should read the commit's own message for who authored it.

Both blocking findings are taken and both are answered in the body rather than here. Every claim below
was re-derived from the shipped tree in this session rather than read off the review, and where the
verification disagrees with the review the body carries what the tree says and this response names the
difference. Finding 5 is a design fork, so the `principles-architect` subagent was consulted on it
before an arm was taken, as the contributor guide asks; its reading changed the answer, and the arm
this body had drafted first is recorded as declined rather than quietly dropped.

**Finding 5 (the rename forces a declaration, and the declaration meets the key gate). Taken, and both
halves reproduce.** `theUndeclaredRosterOnlyShrinks` compares the observed relations carrying no
`meta_relation` row, no `meta_materialize` source-view row and no `meta_stated_relation` row against a
225-line frozen roster, and all five relations the ladder touches stand on it.
`aDeclaredTableKeyMatchesItsGrain`'s helper selects every declared `BASE TABLE`, joins `meta_grain` and
flags any row whose `key_shape` differs from the primary-key column list read out of
`INFORMATION_SCHEMA`, so a table with no key has no entry and offends. `meta_grain.key_shape` is `NOT
NULL` with the length check the review names. The review's "no counter-case" is stronger than it
claimed and the stronger form is in the body: parsing the shipped DDL statement by statement, of 270
base tables 17 carry no primary key, and **every one of the 17 is an `intent_` registration target on
the frozen roster**. So keylessness and roster standing are the same population, and the conversion is
exactly what separates them.

**Two corrections to the finding, both narrowing it.** The review counts three of the four conversions
as keyless. Two is right. `intent_resolved_type_binding` has no primary key today, but all six of its
columns are already `NOT NULL` and its rule is a `COUNT(*) OVER (PARTITION BY graph_name, type_name)`
over a `UNION` of `intent_bound_table` and `intent_routine_return_binding`; the `UNION` dedupes, so the
five identity columns are unique by construction and `candidates` is a payload. It takes a real primary
key with no nullability change and no change to the rule. And the review locates the unkeyable arm at
`via = 'CONDITION'`. It is two arms: `intent_field_reference_step_hop_live` is a `UNION ALL` of four
branches projecting `'KEY'`, `'TABLE'`, `'NAME_MATCH'` and `'CONDITION'` as constants, and
`key_matched_by`, `constraint_name` and `fk_on_from` are projected as `NULL` and `CAST(NULL AS
BOOLEAN)` on both `NAME_MATCH` and `CONDITION`.

**One door the finding did not check, checked here because the same failure shape produced rounds 4, 5
and 6: it is closed.** A `meta_relation` row needs a grain, a grain needs a corpus, and
`MetaDeclarationGateTest` holds a declared relation's grain corpus against its owner's. That check skips
an owner with no `meta_gatherer_corpus` row, and the graphitron gatherer has none, which is the same
fact the placement argument already leans on from the other side. Sixteen `graphitron_`-owned relations
are declared today, `graphitron_field_table` and `graphitron_argmapping_candidate` among them. A fourth
gate was checked and binds only where a key exists:
`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension` reads only tables that have one, so
every key introduced here must lead with `graph_name`, which every candidate key does.

**The arm, which is the author's call: the two unkeyable relations are not one relation each, so they
decompose, and no gate moves.** Following the finding's own observation one step further is what
settles it. If a present, closed, four-valued `via` determines which columns are null, the relation is a
discriminated union of four arms, and the arms have two different natural keys: on `KEY` and `TABLE`,
`constraint_name` and `fk_on_from` are identity, both orientations of every connecting foreign key
being separate rows, which is what `ix_field_reference_step_hop_step`'s own comment means by "Not
UNIQUE and not the grain"; on `NAME_MATCH` and `CONDITION` there is nothing to enumerate, so the
coordinate with the two table triples is already total. One relation cannot carry two key shapes, and a
projected literal `NULL` is padding to make four row shapes share one column list rather than a fact
about a hop. The subject is the fact-model page's own worked anti-example, a `@reference` path element
that "needed nine nullable columns and a legality rule no constraint could state" and became three
relations. So the answer is a keyed relation per key shape under a view carrying the existing name,
which satisfies `aDeclaredTableKeyMatchesItsGrain` outright and closes R876's burn-down finding that
nothing refuses a duplicate row in these targets.

**That decomposition is filed as R956 and this item depends on it rather than performing it.** The
reason is evidence rather than size: the walk's recursive term joins the hop once per accumulated row
and `ix_field_reference_step_hop_step` is what makes that a seek, 18308 scans against 523 by its own
comment, and under a union view the planner has to push that eight-column seek into both arm tables.
H2 usually does. Usually is not good enough for the figure this item's cost claim rests on, so the
decomposition owes that re-measurement as its own acceptance evidence, and owing a measurement is what
makes it an item. No figure for it appears in either body, none having been taken. Phases 1 and 2
convert what R956 leaves; if it has not landed, each rung takes the registration fallback this body
already licenses per rung.

**The arm this body drafted first and withdrew, recorded because it is the one a reader would reach
for.** The first draft of this response widened `aDeclaredTableKeyMatchesItsGrain` with a roster-pinned
exemption for a declared base table whose grain names a column the table declares nullable. Three
things killed it. `meta_grain.key_shape`'s own comment is that "a declared base table at this grain
carries exactly this primary key (gated)", so an aspirational `key_shape` on the exceptions makes one
column mean two things, which is the denormalisation phase 0 declines by name. The three exception
rosters this project treats as normal each admit on something harder than an argument:
`undeclared-relations.txt` is frozen and only shrinks, `HAND_WRITTEN` admits on an impossibility
criterion this item refuses to weaken by name, and `NO_INDEX` carries a measurement per entry; "the
grain names a nullable column" is none of those and would admit every future relation that nominates
one. And the index the draft offered as compensation is not a key: it does not refuse a duplicate row,
so it traded an integrity property for a seek and called it a buy-back. The justification the draft
gave, that `via` is present and closed so the NULLs are its consequence, is correct and is kept in the
body; what it establishes is that the relation is four relations, not that the gate should admit it as
one. It also answered the wrong doctrine: absence-carries-meaning is satisfied, and the one that
objects is sealed hierarchies over enums, whose named tell is "this enum value implies these fields are
non-null", which the hop's three column comments state verbatim.

**One correction to the review's own framing of its arm 4.** "Convert only the keyed rungs and demote
the rest" would not have dropped the field walk or the cost claim. A registration lands the same rows
on the same disk and `intent_field_reference_step_fanout` still answers in 0.37 s, because what fixes
it is the walk being a table rather than who wrote it, which this body says twice already. What a
registration loses is the per-graph partition write and the modelling claim for one relation. That
makes it the honest fallback rather than a non-starter, and the body now states it as such per rung.

**Finding 6 (the phases specify the insert and not the delete). Taken, and the precedent verified.**
`Materializations.refreshPartition` is a `deleteFrom(target).where(graph_name = ?)` and then an
`insertInto(target).select(... from source where graph_name = ?)`, as the review says.
`ArgMappingCandidates.derive` opens `dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).execute()` and
then seeds and expands, on a target carrying no `touched_at`, so it is that discipline run by the owner.
`FieldEndpoints.derive` is the other thing: `onDuplicateKeyUpdate` on the primary key plus a
`deleteFrom ... where graph_name = ? and touched_at <> ?` sweep. The body now names both, takes the
shape from one and the reconciliation from the other, and states the `DELETE` once in a subsection every
phase points at rather than four times. The review's reading that this strengthens the thesis is
adopted in that subsection's own words: what a conversion changes is who runs the reconciliation and
when, not what it is.

**One thing the finding leaves implicit, which is what makes the discipline mandatory rather than
advisable.** The two failure modes are not the same failure. A missing `DELETE` on a keyed rung fails
loudly on a primary-key violation at the second capture; on a keyless one it fails silently by doubling
the relation. Only the second would ship. After R956 every rung here is keyed, which narrows the
exposure without removing the obligation, since a loud failure on every second dev round is not a
shipping state either.

**The Tests section's blindness is stated rather than patched over, and the acceptance evidence has a
home already.** The `EXCEPT` bullet now carries both of the review's reasons it cannot see this defect,
because an oracle this load-bearing is worth knowing the shape of. Beside it is a new per-rung bullet
asking that a second capture of one graph into one store leave the row count unchanged. That is not a
new fixture: `WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` already captures twice into a
persistent store and compares a per-relation row-count census against a cold load, and its `census`
helper counts every non-view table in the schema, so a converted relation joins it with no edit. What it
does not do today is give these relations rows, running on that class's `SDL`, which binds no table; the
class already carries `TABLE_BOUND_SDL` and a `captureBound` helper that exist for exactly this, their
javadoc saying "so the intent targets take rows". So the ask is a bound-schema arm on an existing case.

**Finding 7 (non-blocking). Taken.** The heading claimed sufficiency where the paragraph argues
necessity, and it now claims necessity and reachability-by-nothing-else, with the figures that make it
not sufficient stated under it rather than left to the contingency bullet.

**What else changed with these two.** "What is in scope" gains a paragraph on the decomposition and the
one dependency, and says explicitly that no gate moves. The Tests section states the declaration
obligation as per-rung rather than conditional, which is what finding 5 asked for whatever arm was
taken, and names the two further gate methods the declaration brings to bear. Phase 1 and phase 2 say
what each relation arrives as and what the stage does with it, and phase 2 drops "H2 refuses a primary
key over a nullable column" as a reason, an engine's behaviour never being the reason for a modelling
decision. One "does not do" bullet is added, recording that no gate changes and that a draft of this
plan changed one. "Relation to other items" gains R956, says why that edge exists where the R953 one
does not, and rewrites the sibling paragraph.

**Left alone deliberately.** The `intent_` names of the relations that stay views, and
`intent_condition_method_route`'s roster line, which no conversion touches. R955's body, whose own
answer to this question is incomplete in a way this round found and which is that item's to repair; the
record is here and in the sibling paragraph. And the scope call itself: nothing in this round moves
which phases are this item's.
