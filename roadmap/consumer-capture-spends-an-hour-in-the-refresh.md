---
id: R856
title: "A consumer-schema capture spends over an hour inside the materialization refresh"
status: Spec
bucket: bug
priority: 1
theme: model-cleanup
depends-on: [materialization-refresh-emits-no-progress]
created: 2026-08-27
last-updated: 2026-08-27
---

# A consumer-schema capture spends over an hour inside the materialization refresh

On a real consumer schema, `mvn graphitron:validate` and `mvn graphitron:dev` both spend more than an
hour inside the materialization refresh, and no capture has been observed to finish. In practice
everyone kills them, and `graphitron:dev` never binds its language server or MCP ports at all,
because the bind sits behind the initial run.

Whether this terminates at all is not established. The recursive terms carry path guards, which is a
structural argument that the walk is bounded rather than infinite, and it is the only argument there
is: no completion has ever been observed on a DDL carrying the two suspect registrations. Treat it as
combinatorial blowup of unknown magnitude rather than as either a deadlock or a known-finite wait,
and be careful about the word "hang", which invites a deadlock hunt this evidence does not support.

## What changes when this lands

A capture of a consumer schema this size completes, so `mvn graphitron:validate` returns and
`mvn graphitron:dev` binds its language server and MCP ports. Today neither happens on the schema
described here, and the only honest advice anyone can give a developer on it is that the schema
cannot be captured at all. Nothing about what the store answers changes: the same relations, the
same rows, under the same names every reader already spells.

One identification stands between here and that, and it is what most of this plan is about: which
statement of the refresh spends the time, and why the same statement is cheap when it is measured
after a capture and not while one runs. Two arms are still standing. Either the refresh's statements
are planned differently inside a cold capture than they are anywhere a measurement has ever been
taken, in which case the fix is capture's transaction shape; or one statement is expensive however it
is planned, in which case the fix is a lever on one relation. They are separated by a measurement
that needs no consumer schema, and the plan takes it first rather than choosing.

What the Spec gate is being asked to approve, since the two arms are not the same size: the decision
rule and the first arm's design. The second arm is one registration, whose case is made in its
`reason` row against measurements it does not have yet and whose precedent is R839, so it needs no
second design here. If the measurement picks the first arm and its design has drifted from what was
approved, that is a reopen rather than a surprise landed under this sign-off.

## Vocabulary

A **registration** is a row of `meta_materialize`: a rule kept in a view under a `_live` name, and a
table of the same shape under the canonical name every reader spells. The **refresh** refills those
tables once per capture, walking them in an order derived from the stored view definitions, and it
runs inside the capture's own transaction. The **consumer schema** here is an internal one at Sikt,
about 8,400 fields and 2,300 types.

## What is established

Two JVMs, different goals, same frame, each pegged on one core with cpu time tracking elapsed time,
so spinning rather than blocked or starved:

- `graphitron:dev`, started 11:16, exited ~12:19 without binding.
- `graphitron:validate -pl <consumer module>`, started ~11:11, gone by ~12:32.

Every thread dump showed the same stack:

```
FactCapture.capture
  Materializations.refresh
    Materializations.refreshPartition        <- INSERT INTO <target> SELECT * FROM <target>_live
      org.h2.index.RecursiveIndex.find
        <- IndexCursor.find <- TableFilter.next <- Select.gatherGroup
```

So the time is in the refresh, and the shape is a recursive term re-evaluated under a grouped select.
That much is measured and is not in doubt.

The cost is the expansion rather than any child. Timed in isolation against a populated store of this
schema, the children of the two most expensive relations answer in 1 to 206 milliseconds each, four
of them totalling under 0.7 seconds, while the parents take 35 and 59 seconds. There is nothing
expensive underneath to reach for.

## The priced order, and what it does not prove

Both slow processes ran a 16-registration DDL. That is measured for the first, whose open file
descriptors named its store, and inferred for the second, which started 76 minutes before the
installed model jar changed. The order was reproduced from that store's own
`meta_materialize_dependency` rows, and every registration ahead of the last two was timed in
isolation against a populated store of the same schema:

| # | registration | one evaluation |
|---|---|---|
| 1 | `intent_argmapping_pair_live` | 0.2 s |
| 2 | `intent_errors_field_live` | 20 s |
| 3 | `intent_spelled_table_live` | 0.1 s |
| 4 | `intent_field_reference_step_hop_live` | 3 s |
| 5 | `intent_resolved_type_binding_live` | 0.1 s |
| 6 | `intent_carrier_data_field_live` | 59 s |
| 7 | `intent_field_column_scope_live` | 35 s |
| 8 | `intent_argument_scope_table_live` | 22 s |
| 9 | `intent_argument_column_scope_live` | 0.5 s |
| 10 | `intent_input_field_resolving_table_live` | 0.03 s |
| 11 | `intent_node_id_instruction_live` | 5.3 s |
| 12 | `intent_argument_column_match_live` | 0.7 s |
| 13 | `intent_input_field_filter_role_live` | 47 s |
| 14 | `intent_node_id_decode_hop_column_live` | 4.9 s |
| 15 | `intent_mutation_payload_refusal_live` | **unmeasured** |
| 16 | `intent_mutation_payload_column_live` | **unmeasured** |

Positions 1 to 14 total 199 seconds and are exactly the set of registrations the populated store
holds, so nothing ahead of the tail is unmeasured and nothing is argued cheap from its shape. The
tempting conclusion is that a run still going at 80 minutes must be at position 15 or 16.

**That inference does not carry on its own, because of a flaw in the comparison.** The 199 seconds
were measured post-commit, against a settled store, on connections that wrote nothing. The 63 and 80
minutes are in-transaction wall clock for a whole capture. Those are not the same quantity: some of
that hour is parse, catalog reflection, fact-writing, classification and compilation rather than
refresh, and an in-transaction refresh may cost more than the same relations cost measured
afterwards. So read the table as a price list. It says which relations are expensive when evaluated
against a settled store, and it is not by itself a statement about where an hour goes.

## The population fact, which does carry

The localisation rests on this instead, and it needs no cross-quantity comparison at all.

A survey of all fifteen stores this schema has left on disk finds that every store at 16 or 20
registrations holds zero graphs and zero fields. Not one has ever been captured into. The only
populated stores are at 14 registrations or fewer, and the newest of them carries
`store_graph.LAST_CAPTURED = 2026-08-26 14:30:25`, which is the most recent successful capture of
this schema on this machine.

The plugin carrying the 16-registration DDL was installed nineteen minutes later, at about 14:49 the
same day. From that install onward, no capture has completed.

So the two tail registrations appeared, and captures stopped finishing, with nineteen minutes between
the last success and the DDL that added them. That is a before-and-after in the population rather
than an argument from durations, and it is the strongest evidence this item has.

Note what it is not. It gives no duration for the successful 08-26 capture, so nothing here says what
this schema costs when it works, and no figure should be invented for it.

## The DDL has moved on since the slow runs, and one of the moves is under the suspects

The slow runs ran sixteen registrations. The tree registers twenty. Comparing the two sets rather
than dating them, because the local history is rooted at a squashed import and no DDL addition in it
can be dated, the four the slow store did not have are `intent_mutation_write_payload`,
`intent_mutation_payload_key_membership`, `intent_mutation_write_destination` and
`intent_field_scope_table`.

Two of the four are beneath the suspects and the depths differ. `intent_mutation_write_payload` is
the driving relation of both suspects, named once by each, and on the slow store it was a view
inlined at every naming where today it is a table. `intent_field_scope_table` is a layer below that,
named by `intent_mutation_write_payload_live` (and by `intent_argument_scope_table_live`, which is
position 8). The remaining two are in the same mutation family but neither suspect names them, so
they change nothing here.

That one registration is enough to say the suspects' cost has already changed by an unmeasured
amount, in the direction of cheaper, and nobody has run a capture at consumer scale on the DDL the
tree currently ships. The third-generation attempt this item records was terminated rather than slow,
which leaves the current generation's status unknown rather than negative.

That is the reason the plan below opens on a capture rather than on a lever. It may already complete,
in which case the population fact becomes evidence about a DDL nobody runs any more and this item
ends as a note.

## The transaction boundary, and the half of it that is still open

The boundary controls two things. One is H2's query-expression result cache, and that half is refuted
by measurement below. The other is when statistics exist, and it is the strongest arm this item has,
because the part of it that is a claim about the database rather than about this schema is settled.

**No statistics exist on anything the refresh reads, on a cold store, ever.**
`Materializations.analyse` runs after the capture transaction commits and covers the registered
targets only, which its own javadoc argues for. H2's automatic analysis does not fill the gap while
the transaction is open: `SessionLocal.markTableForAnalyze` adds the table to a pending set, and
`SessionLocal.analyzeTables`, the only path to `Analyze.analyzeTable` outside the explicit `ANALYZE`
command, is called from `SessionLocal.commit`. Read out of the bytecode of the pinned H2, the same
way the refuted hypothesis below was read. So every statement the refresh issues on a cold store is
planned with no selectivity on any relation it reads.

**Two populations of relation, and they are not fixed by the same thing.** The refresh reads base
fact tables, which `FactCapture.capture` wrote earlier in the same transaction (`sink.flush`, then
the hand-written derivations, `InputOccurrencePaths.derive` among them). It also reads *registered
targets*, which the refresh itself writes: `intent_mutation_write_payload` is the driving relation of
both suspects and a registered target, and the column suspect additionally reads
`intent_mutation_payload_refusal`, the other suspect's target, through a correlated `NOT EXISTS`
whose own index reason says the seek is the whole point of the registration. An index without
statistics is the case `Materializations.analyse`'s javadoc prices, and it prices it as most of the
gain the index exists for.

That split decides how far a fix reaches. Anything done before the refresh starts can only state
statistics for the base half, because the other half has not been written yet. The target half needs
statistics stated during the refresh, between one registration and the next, which no single
transaction can do. So the arm has two sub-cases and the plan has to say which one the suspects need
rather than treat the boundary as one lever.

Every timing in this item was taken the other way round: post-commit, against a store `analyse` had
run on and whose fact tables H2 had auto-analysed at that capture's own commit. That is not a caveat
about precision. It may be a different plan.

It also gives the trap in the last section a mechanism, without needing a duration for anything. A
store is cold exactly when no capture has committed into it; a DDL change moves the store to a fresh
compatibility-stamped directory; a killed run commits nothing. So the failing case is cold on every
attempt. Whether the successful 2026-08-26 capture was warm is a further question and this item's
survey cannot answer it, `LAST_CAPTURED` saying when a capture finished and nothing about what the
file held before it started. Nothing here says the difference is the cause either. It says the two
populations of runs differ in a variable no measurement in this item holds fixed.

**The instrument that separates the arms, and it is not a timing.** `EXPLAIN` without `ANALYZE` does
not execute the statement, so plan text is available for exactly the statement no timing can reach.
The naive form of that measurement varies two things at once and has to be rejected: taking a plan
inside the capture transaction and again after the commit compares a store whose registered targets
hold the previous run's rows against one holding this run's, so a disagreement is as consistent with
the population having moved as with statistics, and the targets are exactly what the suspects read.
There is also no seam to take the first plan through, `FactCapture.capture` owning the transaction
end to end.

The form that isolates the one term runs on a settled store and needs no seam. Capture normally, take
each registration's plan, then drop the selectivity the capture's own analysis stated
(`ALTER TABLE ... ALTER COLUMN ... SELECTIVITY 0`, whose keyword is in the pinned H2's parser and
whose action `AlterTableAlterColumn` carries, and which is worth confirming with one statement before
anything is built on it, this item having already recorded that the syntax in circulation is from the
1.x line) and take each plan again. Same rows, same indexes, statistics the only difference.

Read the two outcomes asymmetrically. A disagreement at any fixture size is a strong positive: the
shipped DDL contains a refresh whose plan depends on statistics a cold capture cannot have. An
agreement at fixture size is a weak negative, because a plan is chosen against estimated
cardinalities and a few hundred fields estimate small either way. What a disagreement licenses is
pricing the fix on the consumer store, not landing it: this investigation's record is that most
hypotheses taken from a plan's shape died when somebody priced them.

The earlier reading of this boundary, the result cache, is refuted below and this does not revive it.
What that section leaves open is this half alone, and the pair of plans is the instrument for it. The
sibling logging item helps in a way worth naming separately: it prices nothing, but it makes a cold
capture legible while it runs, so the seventy minutes nobody currently waits out would at least
report which registration they went into.

## A refuted hypothesis, recorded so nobody re-runs it

H2 disables its query-expression result cache when a query's dependencies were written in the current
transaction, which is exactly what a capture does. `Query.isUpdatedInCurrentTransaction` is real in
the pinned H2 and gates caching as described, so this looked like a systemic cause with a systemic
fix: commit the facts first, refresh afterwards, and caching returns for every position at once.

Measured, it is not the driver. Same store, same relation, cache alternated within one connection:
42.3 s with reuse on against 48.3 s with it off, a ratio of 0.88, close to the band the fact model
page already records for that setting and nowhere near orders of magnitude.

The bytecode says why. `Query.query` evaluates `getNoCache()` first and short-circuits, and
`getNoCache` sets no-cache whenever the query expression fails H2's independence check, which is to
say whenever it is correlated. The terms driving the cost here are correlated, so caching was never
available to them in or out of a transaction, and `isUpdatedInCurrentTransaction` is never reached.

What this refutes is the cache half of the transaction-boundary story only. The boundary also
controls when statistics exist, since `Materializations.analyse` runs post-commit and the plans
inside the capture are chosen without it, and the pair above deliberately held statistics fixed at
their good value. That second mechanism is untested and is what the experiment above is for.

## Caveats on the figures

Every timing here was taken against copies of stores left under the per-user cache home, driven over
JDBC with the pinned H2, `OPTIMIZE_REUSE_RESULTS` off, five interleaved sweeps where the relation was
cheap enough to repeat, and H2's own query statistics, with cumulative-over-maximum ratios between
1.14 and 2.30 so no row is result-reuse corrupted.

The price list was taken on the 14-registration DDL and the slow runs used the 16-registration one, a
day apart, so a view among positions 1 to 14 could have changed text between them. The populated
store came from a capture that completed and the slow runs are on a working tree with uncommitted
schema changes, so the populations are not identical.

On the population fact: `LAST_CAPTURED` records when a capture finished, not how long it took, so the
successful run's duration is unknown and must not be reconstructed. "No capture has completed at 16
registrations or more" is a fact about one machine's cache, not about the world; a CI run or another
developer's machine could say otherwise, and checking one would strengthen or break this cheaply.

An earlier draft of this item recorded a 73-CPU-minute completion on 2026-08-27 and reasoned from it
that an hour might be ordinary cost for this schema. There was no such completion. The figure came
from a note whose basis was a run that was killed, and the 2026-08-27 file timestamp behind it is a
file open rather than a capture, `LAST_CAPTURED` on that same store reading 2026-08-26. Nothing in
this item should be read as resting on it.

A third DDL generation was briefly thought to reproduce this and does not: that process was
terminated rather than slow, and its store is empty for that reason. Two hypotheses were killed early
and should not be re-run: the `@lookupKey` recursion, whose seed relation is the retired input-field
site and is empty on this schema, and the schema's existing validation errors, which shorten the
reference walk rather than lengthening it.

## Why killing it is not free, and why that is not a workaround either

The refresh runs inside the capture's transaction, so an interrupted run commits nothing: it leaves a
full-size file holding no rows, and the next run starts as cold as the last. One measured this way
was 124 MB and held 67 rows. So every kill guarantees the next run pays from the beginning again, and
a person hitting this repeatedly is not making the situation worse only by luck.

The tempting advice that follows is "let one capture finish and the store stays warm". Do not give
it. It is true at 14 registrations, where a completed capture is on record and a warm store does open
in milliseconds afterwards, and it is unproven at 16 or more, where no capture has ever finished and
nobody knows what waiting would cost. Advising a developer to wait out an unbounded run is worse than
telling them the truth, which is that there is currently no way to capture this schema.

## The structural candidate, if the localisation is the real question

If the plans agree and one statement is expensive however it is planned, the suspects' shared
substructure is where to look, and the reader counts pick one relation out of it. Counted over the
shipped DDL with comment text stripped, since the comments name relations in prose:

| relation | kind | readers |
|---|---|---|
| `intent_input_field_carrier_role` | view, unregistered | exactly the two suspects, and nothing else |
| `intent_node_id_decode_column` | view, unregistered, recursive | `intent_node_id_decode`, `intent_input_field_carrier_role`, `intent_mutation_payload_column_live` |

Neither has a Java reader outside its own test.

`intent_input_field_carrier_role` has no reader at all outside the two suspects. So unless those views
predate their own registrations, the DDL that added the two tail registrations is also the DDL that
first gave this view a reader, which would be the population fact's before-and-after arriving on a
third relation. The squashed history cannot date it and the reader set is the whole of the argument,
but the check is cheap on the machine that has the stores: a 14-registration store's own
`INFORMATION_SCHEMA` holds its view definitions, so it says outright whether this view existed then
and whether anything read it.

Both carry a shape the fact model already names expensive. The view aggregates: its `landing` CTE
groups over `intent_node_id_decode_column`, so no predicate from outside prunes it, and what it
groups over carries a recursive term. Each suspect then names it from inside something re-derived per
driving row, the refusal view's `refused` CTE appearing twice with one of those inside a correlated
`NOT EXISTS`, and the column view's `admitted` CTE appearing once per arm of its closing union.

**The control comes before the hypothesis is worth anything.** Snapshot
`intent_input_field_carrier_role` into a table on a populated store and re-time the suspect, checking
the driving rows are not zero. This project has taken back a per-driving-row reading of a stack once
already, on this very investigation, and the reading above is that shape: it is what a plan would look
like if the frames meant what they appear to mean, which is not evidence that they do.

One more thing bears on the lever and not on the localisation. Registering either relation is a
twenty-first registration in a register that took four in two days, which R848 is the item for. That
is a reason to prefer the deepest candidate and to price the refresh it adds, not a reason to prefer
an unmeasured rewrite.

## Implementation

Numbered because each step's result decides the next and the intermediate states are observable.

**1. One instrumented capture of that schema on the current DDL.** Either instrument names the
statement, and the point of naming one here is that neither is a guess about a plan:

- The sibling logging item's per-registration line, emitted before each statement is issued. Its spec
  has settled where the output goes, an observer the caller supplies, so this step needs nothing from
  this item once it lands. It should land first for that reason and this step is the argument for its
  priority.
- H2's own statement trace, `SET TRACE_LEVEL_FILE 2`, whose finished file
  `org.h2.tools.ConvertTraceFile` turns into a per-statement ranking and whose last entry names a
  statement that never returned. It needs a knob the tree does not have:
  `GraphitronModelStore.fileUrl` builds the URL with no trace parameter and nothing issues a `SET` on
  that connection afterwards. Putting one behind a system property is a couple of lines, and it also
  ranks by cost where the sibling item's line only names, so the two are complements rather than
  substitutes. Take this arm if the sibling item is not moving, and take it anyway on the run that
  matters.

Either way, pin the store with `-Dgraphitron.store.directory` so the file is findable afterwards, and
record either a completion with a duration or a named statement.

Nothing below is chosen before this step reports, and the outcome to prepare for is that it completes:
the driving relation of both suspects is a table now and was an inlined view then. If it completes,
this item's remaining work is step 5 alone and its priority drops.

**2. The plan pair, in the form that varies statistics alone.** As the boundary section above
describes: capture, plan, drop selectivity, plan again. In `graphitron`'s test tier over
`CapturedStore`, taking each plan as a string rather than as a `Result`, which truncates the one
column it is for. This is a fixture-scale measurement, it needs no new seam in capture, and it is
worth taking whatever step 1 says, because it is the only question in this item answerable without
the consumer schema. Run the same pair against a copy of a consumer store while one is available,
where the estimates are the real ones.

**3a. If the plans differ: change capture's transaction shape.** The shape that reaches both
populations from the boundary section is one transaction per registration, each committing its own
target's `DELETE` and `INSERT` together and analysing the target it just refilled, with the facts
committed ahead of the first of them. Then a registration plans against base tables its predecessor's
commit analysed and against targets the registrations before it analysed, which is the whole of what
the arm claims is missing. Landing only the cheaper half, facts committed and analysed with the
refresh still one transaction, fixes the base population and leaves the target population exactly as
it is, so say which half is being landed and why rather than describing the split as one change.

Take the cheap rung before building anything. `Materializations.analyse`'s argument against a bare
`ANALYZE` is premised on the captured tables being ones "nothing here just rewrote", and at a call
site after the facts commit the capture has just rewritten them, so that objection does not transfer
and one statement may be the whole of the base half. H2 may even supply it for free: its auto-analysis
fires at commit for any table that took more changes than `ANALYZE_AUTO` allows, and on a schema this
size most base tables cross that. Price both before writing a walk.

If a walk is still wanted, the machinery is right and the stopping rule needs one more predicate.
`MaterializeDependencies.populate` already walks the stored view definitions from each registered
source view and stops at a relation H2 calls a table, and the union over the registrations covers
every relation the refresh reads. But registered targets are tables too, and analysing one *before*
the refresh refills it states the selectivity of rows about to be replaced, which on a cold store
states zero rows and is worse than stating nothing. So the set is base tables minus the registered
targets, and it is a function of the DDL alone, which means it has `meta_materialize_dependency`'s
cadence: computed once per booted store, not re-derived inside every capture. Keep the best-effort
posture `analyse` argues for either way; statistics are an optimisation on a cache and no store
failure is worth a build.

Three invariants the split touches, none of which may be left to the diff:

- **The emptied target.** Preserved, and this is why the split is per registration rather than per
  refresh: a reader between two registrations sees one target current and another stale, which is a
  consistency loss rather than an emptied relation. Whether a stale-beside-current pair is acceptable
  to a reader is a question about readers, and `Materializations.refreshAll` is the precedent that says
  it is: it already issues every `DELETE` and `INSERT` in autocommit, holding no transaction at all,
  so the whole-refresh atomicity is capture's alone rather than the store's contract.
- **The concurrent same-graph writer.** `FactCapture.capture`'s javadoc names this beside the emptied
  target: the single transaction is what makes a second writer of one graph serialize on the anchor
  row instead of interleaving deletes with inserts. A per-registration transaction holds no anchor row,
  and a target keyed only by an index cannot reject the duplicate that interleaving produces. So the
  refresh transactions have to take the anchor row too, or the item has to state that same-graph
  concurrency is out of scope and argue it. `DevMojo`'s own `refreshAll` call is already an
  unsynchronized second writer, which is evidence about how much this is worth, not a licence.
- **What a stamp means.** Already settled and only needing to be honoured: `ClasspathSources` states
  that a stamp is written after the rows it vouches for, so that a crash costs repeated work rather
  than leaving a partition claiming rows it never finished writing. That puts the stamps after the
  last refresh transaction, and a run killed between transactions leaves an unstamped partition the
  next run re-captures. Name the test in the warm-reconciliation family that fails if the placement
  moves.

Four prose surfaces argue for today's shape and have to move with it, in the same pass rather than as
a follow-up: `Materializations`' class javadoc on the capture cadence, `analyse`'s javadoc on why it
runs outside the transaction, the two comments around the refresh call in `FactCapture.capture`, and
the ANALYZE-placement sentence in `docs/architecture/explanation/fact-model.adoc`. R839 enumerates
every comment it moves and is the standard this item is held to.

**3b. If the plans agree: localise, then lever.** Bisect the named statement's body: each CTE on its
own, each union arm on its own, then one join dropped at a time. Run the snapshot control above
before believing the substructure reading. Then choose by the lever hierarchy, a captured fact before
a registration before a rewrite, preferring the deepest relation whose materialization removes
re-evaluation for more than one reader, and put the arithmetic in the registration's `reason` with
the refresh it adds priced as a cost and not only as a saving.

**4. Re-run step 1 against whatever the fix is.** The proof of this item is a capture of that schema
that completes. No fixture-scale figure substitutes for it, and the ratchet in step 5 is not that
proof either.

**5. What the repo keeps afterwards.** See Tests.

## Tests

Three, and which tier each sits in follows from what it can hold at fixture scale.

- **The plan pair becomes an assertion** if step 2 comes back with a disagreement: for every
  registration, the access path chosen with statistics is the access path chosen without them. Not
  plan text, which is the trap in stating this claim: H2 renders estimated cardinalities and costs
  into the plan and those move with statistics by construction, so a literal comparison fails where
  the access path is identical. Reducing a plan to the indexes and join order it names is the real
  work in this test and the reason it is a test rather than a probe. `DerivedReadCostTest` is the
  precedent for a claim in this family carrying no number, and it is a precedent for the shape only:
  its claim is directional where this one is an equality. If step 2 comes back with an agreement,
  this assertion pins current behaviour rather than a fix, which is still worth having and should be
  labelled as what it is.
- **The invariants step 3a creates need enforcers of their own**, and a fix that lands without them is
  half a change: what serializes a refresh transaction against a second writer of the same graph, and
  what a source stamp promises once facts and targets commit separately. Both are assertable at the
  pipeline tier, and the second already has a family of tests to join rather than a new one to invent.
- **The consumer-scale result stays a figure**, in this item while it lives and in the registration's
  `reason` or the changelog afterwards. Nothing in this repo captures a schema of that size, and
  building a fixture that did would be a wall-clock gate, which the build-guardrail item is the place
  for.

If step 1 reports a completing capture, the deliverable is the smallest thing that would have told us
so in the first place: the sibling logging item, which is its own item, plus a `roadmap/changelog.md`
entry recording that the failure was on a DDL two generations back and which registrations closed it.

## Roadmap entries

No new items expected, and the alternative was weighed rather than skipped: filing 3a and 3b as
Backlog stubs the measurement promotes would give the gate one design to approve at a time, at the
cost of leaving a priority-1 bug with no fix in it and a deliverable of "we now know what is slow".
The shape above keeps the outcome on this item, approves the decision rule and 3a's design now, and
uses a reopen if 3a's design has to move. Split it if a reviewer disagrees; the evidence sections
carry over either way.

The sibling logging item is the dependency for step 1, is named in `depends-on`, and should land
first. R848 is where a twenty-first registration is argued if step 3b reaches for one, and a
registration landed here has to be defensible against that item rather than in spite of it.

## Related

The sibling logging item adds per-registration output to the refresh, which turns the next real
capture into the identification this item currently lacks; it should land first for that reason.
R850 describes this failure mode at a different relation and assumes the trigger is someone editing
that arm; this is a second trigger, on store size alone with no code change. R839 is position 6.
R848 is the frame, asking whether the register's shape is right at all. R857 has a dev start
evaluating the register twice, which doubles whatever a refresh costs on the surface a person waits
on, and its second pass runs on a settled store with statistics, so it is not the pass this item is
about.
