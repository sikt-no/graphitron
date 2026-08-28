---
id: R856
title: "A consumer-schema capture spends over an hour inside the materialization refresh"
status: In Review
bucket: bug
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-28
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

One identification stood between here and that: which statement of the refresh spends the time, and
why the same statement is cheap when it is measured after a capture and not while one runs. Two arms
were standing. Either the refresh's statements are planned differently inside a cold capture than
they are anywhere a measurement has ever been taken, in which case the fix is capture's transaction
shape; or one statement is expensive however it is planned, in which case the fix is a lever on one
relation. They were separated by a measurement that needs no consumer schema, and the plan took it
first rather than choosing.

**That measurement has now been taken, and it picks the second arm.** The section "The measurement
that separates the arms" below carries it. The identification is a positive one rather than the
elimination the plan expected: one unregistered view is re-evaluated once per driving row inside a
correlated subquery, the per-row cost is one whole evaluation of that view, and substituting a table
for the one name removes it. The first arm was tested in the form the plan specified and came back
with a ratio of 0.82 against it.

So the reopen. What the Spec gate was previously asked to approve was the decision rule and the
first arm's design; the decision rule has now returned a verdict and the first arm is not it. What
this gate is asked to approve instead is smaller and is a design nobody has signed off on yet: two
registrations, which relation each one is, and the order they land in. The first arm's design is
retained below as a recorded dead end rather than as work, because the item is priority 1 and a
future reader tempted by the transaction boundary should meet the measurement against it rather than
the argument for it.

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

The stack is now named: that grouped select is the `landing` CTE of `intent_input_field_carrier_role`,
and the recursive term under it is `intent_node_id_decode_column`. The measurement section below shows
that reading holding up under its own controls, which is worth saying here because a stack was read
wrongly twice on this investigation and this one turned out to mean what it appeared to mean.

The cost is the expansion rather than any child. Timed in isolation against a populated store of this
schema, the children of the two most expensive relations answer in 1 to 206 milliseconds each, four
of them totalling under 0.7 seconds, while the parents take 35 and 59 seconds. There is nothing
expensive underneath to reach for.

## The priced order, and what it does not prove

Both slow processes ran a 16-registration DDL. That is measured for the first, whose open file
descriptors named its store, and inferred for the second, which started 76 minutes before the
installed model jar changed. The order was reproduced from that store's own
`meta_materialize_dependency` rows, and every registration ahead of the last two was timed in
isolation against a populated store of the same schema. The `#` column numbers that store's
16-registration order:

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
| 15 | `intent_mutation_payload_refusal_live` | **unmeasured at this scale** |
| 16 | `intent_mutation_payload_column_live` | **unmeasured at this scale** |

Positions 1 to 14 total 199 seconds and are exactly the set of registrations the populated store
holds, so nothing ahead of the tail is unmeasured and nothing is argued cheap from its shape. The
tempting conclusion is that a run still going at 80 minutes must be at position 15 or 16.

The tail is still unmeasured *on this schema*, and the two rows say so rather than saying nothing:
no populated consumer store exists at 16 registrations for them to be timed against, which is the
population fact below. What has changed since this table was written is that both are now measured on
the fixture, where position 16 is the most expensive statement in the whole refresh by a factor of
fifty over its neighbours. That figure lives in the measurement section rather than in this table,
which is a consumer-schema price list and must not be read as carrying a fixture number in one row.

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

That was the reason the plan opened on a capture rather than on a lever, and it still is a reason to
take that capture: it may already complete, in which case the population fact becomes evidence about a
DDL nobody runs any more. What it is no longer a reason for is deferring the localisation, which the
next section takes on the fixture and on the DDL the tree currently ships.

## The measurement that separates the arms

Taken on the `graphitron-sakila-example` fixture, 302 types and 928 fields, against a settled store
with statistics present, `OPTIMIZE_REUSE_RESULTS` off, medians of three to five runs. Every figure in
this section is a fixture figure and none of them is a consumer-schema figure; the caveats section
says what that does and does not license.

**Every child is cheap and the parent is not, so the cost is the expansion.** The same shape the
consumer-scale timings found, now with the guilty relation named:

| relation | kind | one evaluation |
|---|---|---|
| `intent_node_id_decode_hop_column` | table | 0.4 ms |
| `intent_input_field_filter_role` | table | 0.2 ms |
| `intent_node_id_decode_endpoint` | view | 0.6 ms |
| `intent_resolved_node_key_column` | view | 1.5 ms |
| `intent_input_field_column_scope` | view | 6.3 ms |
| `intent_node_id_decode_column` | view, recursive | 49.5 ms |
| `intent_input_field_carrier_role` | view | 56.7 ms |
| its `landing` CTE alone | aggregate over the above | 59.8 ms |

The last row is the localisation. `intent_input_field_carrier_role` costs what its own `landing` CTE
costs and nothing else: a `GROUP BY` over `intent_node_id_decode_column`, which carries a recursive
term and which no predicate from outside can prune. That is the shape the thread dumps showed,
`Select.gatherGroup` above `RecursiveIndex.find`, and the stack was reading correctly. What a stack
cannot say is how many times.

**H2 re-evaluates that whole view once per driving row.** This is the load-bearing claim, so it is
stated as a measurement with its own control rather than as a reading of a plan. Driving *n* rows
through a correlated `NOT EXISTS` over the view, then the identical shape over an indexed snapshot
table of the same rows:

| driving rows | over the view | over a table | ms per driving row |
|---|---|---|---|
| 1 | 60.2 ms | 0.3 ms | 60.2 |
| 2 | 126.9 ms | 0.2 ms | 63.5 |
| 4 | 367.5 ms | 0.2 ms | 91.9 |
| 8 | 478.7 ms | 0.2 ms | 59.8 |
| 16 | 933.5 ms | 0.2 ms | 58.3 |
| 32 | 1861.0 ms | 0.2 ms | 58.2 |
| 64 | 3425.6 ms | 0.3 ms | 53.5 |

Linear in driving rows, at a slope that is one evaluation of the view per row. The arithmetic closes
against the 56.7 ms in the table above, which is what makes this an understood plan rather than a
suggestive one. Against a table it is flat, and flat all the way out.

**Both suspects reach that view through a CTE that is inlined twice**, and in the refusal view one of
the two namings sits inside the correlated `NOT EXISTS`. So the multiplier on the expensive relation
is a driving-row count in one suspect and a constant in the other, which is why the two are not the
same size. Positions here number the shipped 20-registration order where the price list above numbers
the slow store's 16, so a position is comparable between the two tables only through the relation it
names:

| # | refresh statement | one evaluation | rows |
|---|---|---|---|
| 12 | `intent_mutation_write_payload_live` | 0.4 ms | 24 |
| 13 | `intent_input_field_filter_role_live` | 10.4 ms | 122 |
| 15 | `intent_mutation_payload_refusal_live` | 61.4 ms | 0 |
| 16 | `intent_mutation_payload_column_live` | 1075.8 ms | 66 |

Position 16 is two orders of magnitude dearer than the positions ahead of it, on a store a thirtieth
the size of the consumer one. Read that as the population fact arriving a second way and from a
direction that needs no cross-quantity comparison: the two registrations that appeared nineteen
minutes before captures stopped finishing are also the two whose refresh statements are the
expensive ones at any scale anybody can measure.

**The snapshot control, which is also the price of the fix.** Snapshot the suspect into an indexed
table, check the content agrees in both directions, re-time the readers. `source EXCEPT target` and
`target EXCEPT source` were both empty for both snapshots, so these are cost changes and nothing else.
Read "indexed" as load-bearing and unrecorded: the snapshots carried an index, the shape is not written
down anywhere in this item, and the register holds one case where the same registration without its
index was fifteen times worse than the view it replaced. So the two ratios below are an indexed
target's, they are not a registration's until the declared shape reproduces them, and step 3b's index
paragraph is where that is owed:

| refresh statement | as shipped | `carrier_role` as table | and `decode_column` too |
|---|---|---|---|
| `intent_mutation_payload_refusal_live` | 61.4 ms | 17.8 ms | 10.1 ms |
| `intent_mutation_payload_column_live` | 1075.8 ms | 69.7 ms | 13.2 ms |

Fifteen times on the expensive statement from the first substitution, eighty-one from both. The
refresh each one adds is one evaluation of its source view, 56.7 ms and 49.5 ms in the first table, so
the trade is net positive on the fixture before any of the growth that makes it matter on a real
schema. That is the whole of the case for the lever, and it is measured rather than argued.

**Why this is expected to be worse at consumer scale rather than merely bigger.** Two factors
multiply and both grow with the schema. The driving population grows with mutation payload paths, and
the per-evaluation cost grows too, because `landing` aggregates the entire `INPUT_FIELD` decode
population of the schema with nothing able to restrict it. A product of two growing terms is
sufficient to turn one second into an hour, and no third mechanism has to be posited. What is not
established is the exponent; see the caveats.

**Both candidates pass the reader test and the deeper one passes it better.** Counted over the
shipped DDL, `intent_input_field_carrier_role` has exactly the two suspects as readers and nothing
else, so its registration buys nothing beyond them. `intent_node_id_decode_column` has three:
`intent_node_id_decode`, the carrier view, and the column suspect directly. The lever hierarchy
prefers the deepest relation whose materialization removes re-evaluation for more than one reader,
which orders these two.

## The transaction boundary, which was the first arm and now has a measurement against it

**Read this section as an arm set down rather than as a dead end, and read the label off the section
after it.** It was the strongest arm this item had before the measurement above, its reasoning about
H2 is correct and worth keeping, and its conclusion about where the hour goes did not survive. It is
retained in full because a future reader looking at a cold capture will reach for the transaction
boundary on exactly this reasoning, and should meet the measurement rather than repeat the argument.
An earlier draft of this paragraph said "recorded dead end" flatly; that is one word too strong for
what was measured, and the correction is the next section's closing paragraph rather than a hedge
here.

**The two measurements against this arm are consistent, and it is worth saying so once rather than
leaving a reader to reconcile them.** The timing pair below finds two statements whose clocks do not
move with statistics. The plan pair in the next section finds seven of the twenty registrations
planning differently on a cold store, and finds the statistics they turn on to be the registered
targets' rather than the base facts'. Those are not the same claim about the same relations: the
timing pair's two statements are among the seven, their plans move too, and what does not move is
their clocks, because the term that dominates them is a `GROUP BY` over a recursive term re-evaluated
once per driving row and no selectivity informs it. Where the clocks do move it is among the other
five, of which that section prices two. So both readings stand as taken. What the
wider one refutes is not this arm's premise but the cheap rung of the fix under it, and what it adds
is that the cold penalty on a whole refresh grows with the schema rather than sitting at a constant.
Neither touches the second arm, whose 81-fold is an order of magnitude larger than anything measured
here, and R867 is where the residue goes, so this item's branch is unaffected in both directions.

A second session measured this same arm over a wider surface, concurrently and without seeing the
result below, and the two measurements agree where they overlap and part company on how far the
verdict reaches. "The first arm measured over all twenty registrations" further down carries it. The
short version, so a reader of this section is not left with a claim the next section qualifies: the
two statements timed below are the right two to have timed and their clocks really do not move, and
the plans of seven of the twenty registrations do move, on a mechanism that grows with the schema.
None of that touches the case for the second arm, which is an order of magnitude larger and stands
unaffected. What it changes is the label: not the fix for the hour, and not nothing.

**The measurement, in the form this section itself specified.** Capture, time the two suspects, drop
the selectivity the capture's own analysis stated on every base-table column, time them again. Same
rows, same indexes, statistics the only difference. 1263 columns had their selectivity dropped:

| statement | with statistics | without | ratio |
|---|---|---|---|
| `intent_mutation_payload_column_live` | 1333.1 ms | 1097.6 ms | 0.82 |
| `intent_mutation_payload_refusal_live` | 61.0 ms | 59.8 ms | 0.98 |

Statistics change nothing here, and on the expensive statement the direction is the wrong way round.
This section pre-commits to reading an agreement at fixture size as a weak negative, on the grounds
that a few hundred fields estimate small either way, and that caveat is fair in the abstract. It
carries much less against a *positive* identification, because the mechanism the measurement section
found is statistics-independent by construction: no selectivity figure and no index prunes a
`GROUP BY` over a recursive term that is re-evaluated once per driving row. There is nothing for a
plan to choose differently, so there is nothing for statistics to inform.

That matters for sequencing rather than only for correctness. This arm's fix is the expensive half of
the item: it restructures capture into one transaction per registration, it puts three invariants at
risk that the implementation section below enumerates, and it moves four prose surfaces. Landing that
against an arm with a 0.82 ratio against it would be the wrong trade whatever else is true.

**One correction to make while this arm is being set down**, because it is the arm's own supporting
evidence and it is the kind of evidence this investigation has been burned by twice.
`Materializations.analyse`'s javadoc argues for statistics with a *scan count*: 8880 scans with the
index and no statistics against 523 with both. Scan counts are row counts and not costs, H2 attaches
no per-node timing, and this repo has already recorded an index that removed 96% of a relation's
visits and moved the clock not at all. The javadoc's claim may well be true. As written it is not
priced, and it should either carry a timing or say that it does not.

The rest of this section is the reasoning as it stood, unchanged.

The boundary controls two things. One is H2's query-expression result cache, and that half is refuted
by measurement below. The other is when statistics exist, and it was the strongest arm this item had,
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
What that section leaves open is this half alone, and the pair of plans was the instrument for it.

Two notes on that instrument now that it has been run. The `SELECTIVITY 0` syntax is confirmed
against the pinned H2 rather than only argued from the parser: it was issued successfully on 1263
columns. And the measurement was taken as a timing pair rather than as a plan pair, which is a
deliberate substitution and a better instrument than the one specified here. Reducing plan text to
the access paths and join order it names is real work, this section's own Tests entry says so, and it
is only worth doing where a timing cannot reach the statement. On the fixture both statements
terminate in around a second, so both plans could simply be priced. The plan-pair form is still the
only instrument for a statement that never returns, which is why it stays described here.

The sibling logging item helps in a way worth naming separately: it prices nothing, but it makes a
cold capture legible while it runs, so the seventy minutes nobody currently waits out would at least
report which registration they went into.

## The first arm measured over all twenty registrations

Taken independently of the section above and before it existed, on the read-cost gate's scaled
fixture rather than on the sakila example, four statistics regimes over one captured store with the
same rows and the same declared indexes throughout. Landed as `RefreshPlanStatisticsTest`. It exists
because the section above measures two statements at one size and the arm it sets down is a claim
about the whole refresh at any size.

**Where the two measurements agree, and they do.** Both suspects' clocks are flat, and this
measurement gets the same answer from the other fixture. Three runs apiece, targets analysed against
cold: the payload column 68/54/50 ms against 61/57/63, the refusal 37/37/35 against 44/43/35.
Overlapping ranges on both, which is a wash and is the same verdict the timing pair reached. So the
section above is right about the statements it timed, and it is right about why, which is the part
worth keeping: no selectivity informs a `GROUP BY` over a recursive term that is re-evaluated once
per driving row, because there is nothing there for a planner to choose. That reasoning is sound and
this section does not touch it.

**One thing does move on those two, and it is a warning about the instrument rather than about the
arm.** Their *plans* differ cold, and their rows visited roughly triple, the payload column 41961
against 12483 and the refusal 42685 against 13207, while the clocks stay inside each other's spread.
Rows visited moving by a factor of three for no time at all is this repo's most-repeated measurement
lesson met once more, and here it cuts in favour of the timing pair rather than against it: the
counter is the misleading instrument and the clock is right. What it also shows is that "there is
nothing for a plan to choose differently" holds of the dominant term and not of the statement around
it, which is why a plan comparison finds these two and a timing does not.

**Seven of the twenty registrations plan differently on a cold store.** The field column scope, the
field scope table, the input-field filter role, the payload column, the payload refusal, the node-id
decode hop column, and the node-id instruction. One mechanism, seen plainly on the decode hop
column's read of `intent_field_reference_step_hop`, a registered target: with statistics H2 seeks
`IX_FIELD_REFERENCE_STEP_HOP_STEP` on seven columns, and without them it seeks `CONSTRAINT_INDEX_98`
on `graph_name` alone, which is a scan of the whole graph's partition per driving row. H2's
no-statistics default assumes every column has half as many distinct values as its table has rows,
which reads a partition column as highly selective, so the one-column seek prices as though it were
nearly exact. That is the case `Materializations.analyse`'s javadoc describes, arriving at the one
call site `analyse` cannot reach. Where the clocks do move it is these relations rather than the
suspects, and there the ranges do not overlap: the decode hop column 29/29/29 ms analysed against
107/103/120 cold, the input-field filter role 40/29/26 against 59/51/61.

**Which half of the boundary, which this item asked for and nothing had answered.** Two further
regimes over the same store settle it. Analysing the base fact tables alone, which is the most that
committing the facts ahead of a still-single-transaction refresh could buy, leaves every one of the
seven planning exactly as it did cold and moves two further registrations onto plans of their own.
Analysing the registered targets alone reproduces the settled store's plans on all twenty. So the
cheap rung the implementation section reaches for is refuted independently of whether the arm is the
fix: the statistics these plans turn on belong to relations the refresh itself writes.
`analysingTheFactsAloneReachesNoneOfThem` is the assertion that fails the day somebody lands it
believing otherwise. The targets-analysed regime is also a faithful upper bound for what the
expensive half would give rather than only an approximation, `MaterializeDependencies` refusing a
registration whose source view reads its own target and ordering every registration after the ones
whose targets it reads.

**The cold penalty is not a constant, which is the whole of what this section adds to the verdict.**
One whole `Materializations.refresh` of the fixture graph against a populated store, cold and then
with the targets analysed, twice each and the second run reported:

| fields | types | cold refresh | targets analysed | ratio |
|---|---|---|---|---|
| 187 | 79 | 338 ms | 274 ms | 1.2 |
| 349 | 139 | 504 ms | 377 ms | 1.3 |
| 673 | 259 | 1841 ms | 976 ms | 1.9 |
| 1321 | 499 | 9633 ms | 3036 ms | 3.2 |
| 2617 | 979 | 68347 ms | 13199 ms | 5.2 |

A ratio at one size is consistent with a constant overhead. Five sizes are not: a fifth at 187
fields, a factor of five at 2617, still climbing at the last row, which is what a per-driving-row
seek against a growing partition looks like. At the top row the refresh has become the capture, the
whole capture taking 66.9 seconds and the cold refresh alone 68.3.

**Read the analysed column too, because it is a separate finding.** With statistics throughout, one
refresh goes from 274 ms at 187 fields to 13.2 seconds at 2617. The register is superlinear in schema
size before any statistics question is asked, which is R848's frame stated as a measurement and is
what R857 doubles.

**What this fixture does and does not exercise, which bounds the reading.** It scales the input,
reference and node-id families with its unit count and holds the `@mutation` payload surface fixed at
three, so the suspects' driving population does not grow here. The growth in the table above is
therefore not the second arm's mechanism showing up under another name; it is a second cost sitting
beside it. That cuts both ways: it is evidence the two are independent, and it means this fixture
understates the total by leaving the larger mechanism flat.

**So the label.** The first arm is not the fix for the hour, the second arm's 81-fold is an order of
magnitude larger and the sequencing argument above is right that landing a transaction restructure
against it would be the wrong trade. "Recorded dead end" is one word too strong for what was
measured: what the timing pair shows is that statistics do not inform the dominant term of two
statements, and what this shows is a real cost on a different set of relations, growing with the
schema, with the fix's cheap half refuted and its expensive half priced at exactly the settled
store's plans. The honest sequencing is that it waits behind the second arm and behind a
consumer-scale capture that says whether anything is left after the second arm lands.

**Two notes the section above asked for.** Its correction is that
`Materializations.analyse`'s javadoc prices statistics with a scan count and no timing, and should
carry one or say it does not; the wall clocks in this section are that timing, and the widest of them
is a factor of three and a half on one relation at fixture scale. And its own instrument note, that a
timing pair is the better instrument where a statement terminates, is right and needs one correction
that cost this session real time: plain `EXPLAIN` is not the plan that runs. H2 optimizes a view's
inner query per set of index conditions at execution, so a bare `EXPLAIN` renders the unmasked form
and reports three of the seven above while agreeing on the other four. `EXPLAIN ANALYZE` renders what
ran. A reduction of the plan to relation order and index names, which this item's Tests entry
recommends, is also wrong here and for a related reason: the failing shape is one index used two ways
and the difference lives in the seek conditions a reduction discards. Whole executed plan text with
the rows-visited annotation removed is both simpler and strictly more faithful, and that is what the
test compares. Both notes are on the engine rather than on this item, so they are also recorded in
`docs/architecture/explanation/fact-model.adoc`, which outlives this file.

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

Every consumer-schema timing here was taken against copies of stores left under the per-user cache
home, driven over JDBC with the pinned H2, `OPTIMIZE_REUSE_RESULTS` off, five interleaved sweeps where
the relation was cheap enough to repeat, and H2's own query statistics, with cumulative-over-maximum
ratios between 1.14 and 2.30 so no row is result-reuse corrupted.

The measurement section's figures are a separate population and carry their own limits, four of them,
none of which is a precision caveat:

- **No consumer-scale capture.** Nothing in that section is a completion on the schema this item is
  about. The proof of this item is still a capture that finishes, and no fixture figure substitutes
  for it.
- **No exponent.** The fixture is 302 types and 928 fields against roughly 2300 and 8400, and 1.1
  seconds does not reach an hour under a simple square of that ratio. The shortfall is absorbed either
  by the consumer schema's mutation and node-id density being much higher than the example schema's,
  or by growth steeper than quadratic, and this item holds the mechanism rather than the curve. No
  consumer-scale figure may be extrapolated from those tables.
- **The multiplier inside the column suspect is not fully localised.** That statement costs about
  nineteen evaluations of the carrier view, and the bisection went far enough to show the substitution
  removes it, not far enough to say which term supplies the nineteen. The fix does not depend on the
  answer; a `reason` row's arithmetic would.
- **The fixture carries no classpath census**, so every `jvm_`-reading arm on these relations is
  unpopulated in it. That cannot inflate the figures above, all of which are costs the fixture did
  pay. It can hide a second term that only a populated census would show.

One figure elsewhere in the tree needs correcting rather than caveating, and it is in this family.
`DerivedReadCostTest` records the payload refusal at 8 and 9 milliseconds. That relation returns
**zero rows** on that fixture, so its correlated `NOT EXISTS`, which is the expensive term, never
fires and the figure is one unmultiplied evaluation. The number is not wrong; it is about a different
query than the one that costs an hour, and it should not be read as evidence that this relation is
cheap. This is the driving-rows-are-zero trap that the store-performance procedure warns about,
landing on a figure already recorded in the tree.

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

## The structural candidate, which the measurement confirmed

This section was written as a conditional and is no longer one. The measurement above priced its
substructure reading and ran its control, so read it as the design of the fix rather than as a
hypothesis to test: the two relations it names are the two registrations this item now proposes, and
the ordering argument at the end of it is the one that decides which goes first.

The suspects' shared substructure is where to look, and the reader counts pick one relation out of it.
Counted over the shipped DDL with comment text stripped, since the comments name relations in prose:

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

**The control comes before the hypothesis is worth anything, and it has now been run.** The caution
this paragraph opened with was the right one: this project has taken back a per-driving-row reading of
a stack once already, on this very investigation, and the reading above was that shape. So it was
tested rather than believed, in three parts, all in the measurement section. The per-driving-row claim
was measured directly against a synthetic driver and came back linear at one evaluation per row. The
snapshot control was run on both relations and collapsed both suspects. And the agreement check
passed in both directions, so the snapshots priced a cost change and not a content change.

The reader counts in the table above are confirmed as stated. Two amendments to the paragraph above
it, both about the *size* of the multiplier rather than its existence. The refusal view's `refused`
CTE is referenced twice as described, and H2 inlines a non-recursive CTE exactly like a view, so the
expansion is two per read before the correlation multiplies one of them. The column view's `admitted`
CTE is likewise referenced twice, once per union arm, and each naming reaches the carrier view through
its `site` CTE, so that suspect is two evaluations and not one. Neither count is visible to
`report-inline-multiplicity`, which is the subject of the Roadmap entries section below.

What the control did not settle is the driving-rows question on the *refusal* view specifically: that
relation returns zero rows on the fixture, so its correlated term never fires there. The check the
paragraph asks for, that the driving rows are not zero, fails for that one suspect on that one
fixture. Its 61 ms is one unmultiplied evaluation, the fix still moves it, and its real multiplier
remains unmeasured at any scale.

One more thing bears on the lever and not on the localisation. Registering either relation is a
twenty-first registration in a register that took four in two days, which R848 is the item for. That
is a reason to prefer the deepest candidate and to price the refresh it adds, not a reason to prefer
an unmeasured rewrite.

Registering `intent_node_id_decode_column` would also be the first registration whose source view
carries a recursive term; none of the twenty has one. Recorded because it is a first and not because
it is a risk: the dependency walk parses this view today, reaching it from
`intent_mutation_payload_column_live`, and `ViewReferences` handles recursive CTEs explicitly, so
nothing in the register's machinery meets a new shape here.

## What the first registration bought

`intent_node_id_decode_column` is registered, which is item 3 of the sequence in Implementation, and
this section is the measurement that admits it rather than a restatement of the case for it. Taken
against a store captured from the sakila example schema, 302 types and 928 fields, result reuse off,
three runs apiece, each read through a reader minted for it so no session answers from a resolution
it already holds. The two columns are the same store with the rule evaluated on demand and with its
rows stored, which is the comparison `UnregisteredRelation` exists to make:

[cols="3,2,2"]
|===
| relation | rule on demand | rows stored

| `intent_mutation_payload_column_live` | 851 / 826 / 827 ms | 78 / 77 / 79 ms
| `intent_mutation_payload_refusal_live` | 69 / 73 / 82 ms | 12 / 10 / 11 ms
| `intent_input_field_carrier_role` | 63 / 63 / 66 ms | 6 / 5 / 6 ms
| `intent_node_id_decode_column` itself | 63 / 64 / 60 ms | 1 / 0 / 1 ms
| `intent_node_id_decode` | 966 / 961 / 959 ms | 911 / 929 / 931 ms
|===

Every reader improves and none is worse, which is the claim a registration makes and the one
`DerivedReadCostTest` holds. The refresh this installs is one evaluation of the source view per
graph, 60 / 57 / 60 ms on the same store, so the registration costs about a fourteenth of what a
single read of the payload column rule was paying and buys back ten times that on it.

**Read the ratios against the earlier tables rather than beside them.** The measurement section above
prices the same statements on the same schema at 1075.8 ms and 61.4 ms, where this table has 851 and
69 for the unregistered shape; the two were taken on trees a fortnight apart with concurrent work in
between, and the shape of the finding, not the figure, is what carries across. What is comparable
within this table is its own two columns, which were taken minutes apart on one store.

**The index is declared, on the shape this item named first, and the alternative it warned about is
the worse one.** Four shapes were measured on that store with statistics current on both sides, in
rows visited by the three readers that move at all (the payload column relation, the payload refusal,
the carrier role): no index 19069 / 9257 / 1236; `(graph_name, site, use_site)` 17847 / 8993 / 1148;
`(graph_name, use_site)` the same three as that; `(graph_name, site, use_site, position)` the same
three again, so the position buys nothing; and `(graph_name, site, path)`, the spelling the probe used
to carry, 18593 / 9155 / 1202, worse on every one of the three. The reason it is worse is the second
reader this item predicted would decide it: `path` is null on the argument site and does not serve
`landing`'s `GROUP BY (graph_name, use_site)` as an ordered input, so the shape that reads on the
family's declared key is also the shape that serves two readers rather than one. The probe's third
conjunct moved to `d.use_site = a.path` in the same change, and the two column comments that call
`path` not-a-join-key stay true rather than needing the move this item's step 3b put on the other
branch.

**What the index does not do here is move a clock.** The target is ninety rows on this fixture and H2
reads it whole either way, which is the reading `intent_argument_column_match`'s roster row takes of
the same situation at its own population. The declaration is claimed on the scans and on the second
reader, and the population that would make it a clock is a consumer schema, where the driving side
grows with the mutation payload surface and the target with the node-id surface. Recorded here
because a future reader meeting a flat clock beside a declared index should meet the reason too.

**Item 4 was put to the same measurement rather than inherited from the pair having been proposed
together, and it passes.** The question step 4 asks is whether the first registration leaves either
suspect expensive, and the honest way to answer it is to register the carrier and toggle it, which is
what "What the second registration bought" below records. The short answer is that the first
registration made one evaluation of the carrier view cheap, 63 ms to 6, and did not remove the
per-driving-row multiplier over it, which is what this item's own reasoning predicted: what remains
after item 3 is 85 ms on the payload column relation, and the carrier registration takes it to 27.

## What the second registration bought

`intent_input_field_carrier_role` is registered too, which is item 4, and it was priced on its own
rather than on the pair's figures: the store below already has `intent_node_id_decode_column`
registered, so what this table shows is what the carrier registration adds on top of the first one
and not what the two are worth together. Same schema, same method, three runs apiece:

[cols="3,2,2"]
|===
| relation | carrier on demand | carrier's rows stored

| `intent_mutation_payload_column_live` | 85 / 91 / 81 ms | 23 / 23 / 35 ms
| `intent_mutation_payload_refusal_live` | 15 / 14 / 14 ms | 10 / 9 / 12 ms
| the carrier's own read | 6 / 6 / 8 ms | 1 / 0 / 0 ms
| `intent_mutation_matched_key` | 26 / 26 / 27 ms | 27 / 28 ms past a first-run outlier
|===

The refresh is one evaluation of the source view per graph, 8 / 7 / 6 ms, so this registration costs
about a tenth of one read of the relation it is bought for. Taking the two registrations in order is
what makes these numbers mean anything, and it is worth saying what the order established rather than
only that it was followed: the first registration took one evaluation of the carrier rule from 63 ms
to 6 and left the multiplier over it where it was. A rule that is expensive and a rule that is
evaluated many times are different faults with different levers, and only the second is a
registration's to fix; had both landed together, the 851-to-23 would have been one number with no way
to say which half of it either registration bought.

**The index is declined, on a measurement, and the roster carries it.** Four shapes over both readers
with statistics current on both sides. By rows visited three of them are a large improvement: the
payload column relation and the payload refusal are 4286 / 2163 with no index, 3101 / 790 on the
six-column grain, 3101 / 787 with `carrier_role` appended and 3101 / 790 on the field coordinate
alone, with `(graph_name, carrier_role)` reaching 4286 / 811. The clocks refuse all four. The payload
column stays inside its own spread, 25/24/23 against 22/22/23 on the grain, and the refusal goes the
wrong way and consistently: 13/11/11 with no index against 21/23/22 on the grain, 30/18/20 with the
role appended and 24/23/20 on the field coordinate. A third of the rows visited for twice the time is
this investigation's own most-repeated lesson arriving once more, and the mechanism is size: the
target is ninety-five rows here, so the seek costs more than the scan it replaces. What would change
that is a driving side larger than the target, which is the consumer schema and is not something this
fixture can stand up. Nothing here is the hazard step 3b warned about from
`intent_field_scope_table`, where registering without an index was worse than not registering: this
target unindexed takes its expensive reader from 85 ms to 25.

**What the two registrations are worth together, said once and kept separate from the two tables
above.** On this schema the payload column relation goes from 851 ms with both rules evaluated on
demand to about 27 with both registered, against a combined refresh of about 67 ms per graph. That is
a fixture figure and this item's caveats apply to it in full: it is not a consumer-scale capture, it
licenses no exponent, and the fixture holds the mutation payload surface fixed at three, which is the
term the consumer schema grows.

## Implementation

Numbered because each step's result decides the next and the intermediate states are observable.

**Step 2 has run and its outcome is recorded above. Step 1 has not.** Step 2 came back with an
agreement on the two suspects, which is what decides the branch: it is 3b and 3a is set down. Read
that as the narrow claim it is. The same step run as a plan pair over all twenty registrations rather
than as a timing pair over two came back with a disagreement on seven of them, and "The first arm
measured over all twenty registrations" above carries it. Nothing in that moves the branch, the
re-evaluation mechanism being an order of magnitude larger, and it is recorded here rather than only
there so that an implementer reading this preamble does not meet the wider result later as a
contradiction. Step 1 is a consumer-schema capture, it is still
owed, and the sequence below pays it where an earlier draft of this preamble claimed the debt and then
skipped it. The step list further down is kept in its original order because it is the record of what
was decided when; the sequence for the work that remains is:

1. *Spent.* This was "land the sibling logging item, so the next real capture reports which
   registration it entered". R855 is Done and the instrument is in the tree, so the work starts at
   item 2.
2. **Step 1 on the consumer schema, against the DDL the tree ships, before either registration is
   written.** Step 1's own text stands as written, "Nothing below is chosen before this step reports"
   included, and the paragraph added under that step says what "reports" means on a run that does not
   complete.
3. Register `intent_node_id_decode_column`, the deeper candidate, with the index its probing reader
   dictates, and re-price both suspects against that declared shape rather than against the snapshot
   control's. This is step 3b with the localisation already done.
4. Register `intent_input_field_carrier_role`, on the same terms about its index, if the first
   registration leaves either suspect
   expensive. Take these one at a time rather than together, so each `reason` row carries the
   arithmetic of what its own registration bought, and so the second is landed on evidence rather
   than on the pair having been proposed together.
5. Step 1 again against the fix, which is step 4 below and is still the proof of this item.
6. Step 5 below, unchanged.

**Where the work stands, and the one item of it nobody in this repository can do.** Items 3 and 4 have
landed, in that order and priced one at a time, and the two sections below carry their measurements.
Items 2 and 5 are both step 1, and step 1 needs the machine that holds the consumer schema; no session
working from this repository alone can take it, which is why step 1's own text calls it a step rather
than a test. So item 2 is owed and unpaid, and this is said plainly rather than left to be inferred
from a silence: both registrations landed without anybody knowing whether the failure still reproduces
on the DDL the tree ships.

What licenses that ordering is narrower than the decision rule item 2 was written to be, and it is
worth stating as the exception it is rather than as a reading of the rule. Both registrations are
measured on their own figures; every reader of the first improves and none is worse; the register's
own gates admit both, and the read-cost gate did more than admit them, four of its recorded
regressions leaving and none arriving. If the capture item 2 asks for turns out to complete, neither
registration becomes wrong: they stand as improvements to the register, argued in their own `reason`
rows on their own numbers, and this item's remaining work is step 5 alone, which is the outcome that
branch already names. What the ordering costs is the thing item 2 was there to buy, and it is a real
cost: nobody yet knows whether these two registrations are the fix for the hour or an improvement
that leaves it standing. Only step 1 answers that, and it is the next thing anybody should do with
this item.

**Why the capture keeps its place ahead of the registrations, rather than the fixture prices standing
in for it.** The prices establish that those two refresh statements are expensive and that the
mechanism is a per-driving-row re-evaluation. What no fixture figure can say is whether a capture of
the consumer schema still fails on the DDL the tree currently ships, and two facts recorded above make
that a live question rather than a formality: `intent_mutation_write_payload`, the driving relation of
both suspects, was an inlined view on the slow store and is a table now, so the multiplier's own input
has moved in the direction of cheaper by an unmeasured amount; and R848 is open on whether the
register should grow at all, so a twenty-first and twenty-second registration have to be worth their
place rather than merely be an improvement on something. Landing two registrations without knowing
whether the failure still reproduces is the one thing the evidence here does not support.

So step 1 is a decision rule and not a formality, and it has two outcomes:

- **The capture completes.** Then this item's remaining work is step 5 alone, its priority drops, and
  neither registration lands under it. The fixture prices become evidence for whatever item argues the
  register's shape, R848 or R849, rather than a fix here.
- **The capture does not complete.** Then the logging names the registration it went into, that name
  is consumer-scale confirmation of the fixture identification, and steps 3 onward are the fix.

Do not read the ordering as licence to skip the re-pricing between the two registrations. The measured
81× is the pair's, the 15× is the carrier view's alone, and no figure yet isolates what the deeper
registration buys on its own.

**1. One instrumented capture of that schema on the current DDL.** Either instrument names the
statement, and the point of naming one here is that neither is a guess about a plan:

- The sibling logging item's per-registration line, emitted before each statement is issued. It has
  landed, R855 being Done, so this step needs nothing from this item: `Materializations.refresh`
  reports to a `RefreshProgress` the caller supplies and `FactCapture.capture` passes one. The pass
  boundary prints by default and the per-registration line is at the debug tier, so **the run that
  matters has to be taken under `mvn -X`** or the line this step is discharged by never appears.
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

**What "reports" means, since this step now sits ahead of the fix and must not become an unbounded
wait on a priority-1 bug.** It does not mean a completion. It does mean `mvn -X`, repeated here
because the run costs an hour and a second attempt for want of a flag is the avoidable failure: the
per-registration line is at the debug tier, so a default-verbosity run reports the pass boundary and
nothing this rule can be discharged by. The refresh emits a
registration's name *before* its `DELETE` is issued, which is the ordering that item argues for
precisely so that the registration which never returns is the one that gets named; the fact model page
states the same property as the instrument's whole point. So a console stopped after a registration
line has already reported, and this step is discharged the moment the run either finishes with a
duration or has sat in one named registration past every earlier registration's own line put together.
Neither outcome needs the run watched to the end, and a run stopped after it has reported costs what
every kill on this schema costs and nothing more, which the section on killing it prices. What this
step does need is the machine that holds the consumer schema; nobody can take it anywhere else, and
that is the reason it is a step rather than a test.

**2. The plan pair, in the form that varies statistics alone.** As the boundary section above
describes: capture, plan, drop selectivity, plan again. In `graphitron`'s test tier over
`CapturedStore`, taking each plan as a string rather than as a `Result`, which truncates the one
column it is for. This is a fixture-scale measurement, it needs no new seam in capture, and it is
worth taking whatever step 1 says, because it is the only question in this item answerable without
the consumer schema. Run the same pair against a copy of a consumer store while one is available,
where the estimates are the real ones.

*Run, as a timing pair rather than a plan pair, for the reason the boundary section now gives. It
came back with an agreement: 0.82 on the expensive statement and 0.98 on the other. Still worth
running against a consumer store copy if one becomes available, where the estimates are the real
ones, and that is the only part of this step still owed.*

*Also run in the plan-pair form, concurrently and on the other fixture, over all twenty
registrations rather than two, and landed as `RefreshPlanStatisticsTest`. Seven registrations plan
differently cold, the statistics they turn on are the registered targets' rather than the base
facts', and the cold penalty on a whole refresh grows from a fifth to a factor of five between 187
and 2617 fields. "The first arm measured over all twenty registrations" above carries it, and none
of it disturbs the branch: the agreement on the two suspects is confirmed from the other fixture, and
3b remains the fix. What it does change is that 3a's cheap half is refuted rather than merely
unpriced, so a future reader picking this arm up starts from its expensive half.*

**3a. If the plans differ: change capture's transaction shape.** *(Not taken. Step 2's timing pair
agreed on the two suspects, which is what decides the branch; the wider plan pair disagreed on seven
of the twenty registrations, which does not, and R867 carries that. The transaction-boundary section
above says why those two results are consistent. Retained
as the design that was approved and set down, and as the reasoning a future reader will need for the
statistics-shaped cost that did turn up. Nothing in it is work under this item.)* The shape that reaches both
populations from the boundary section is one transaction per registration, each committing its own
target's `DELETE` and `INSERT` together and analysing the target it just refilled, with the facts
committed ahead of the first of them. Then a registration plans against base tables its predecessor's
commit analysed and against targets the registrations before it analysed, which is the whole of what
the arm claims is missing. Landing only the cheaper half, facts committed and analysed with the
refresh still one transaction, fixes the base population and leaves the target population exactly as
it is, so say which half is being landed and why rather than describing the split as one change.

*The cheap rung below is refuted rather than merely unpriced, so a reader picking this arm up starts
from the expensive half. The plan-pair measurement ran the base-facts-only regime directly: it
reaches none of the seven registrations whose plans move, because every one of them turns on a
registered target's statistics, and it moves two further registrations onto plans of their own. The
paragraph is kept because both arguments in it are true on their own terms and a reader will
otherwise re-derive them.* Take the cheap rung before building anything.
`Materializations.analyse`'s argument against a bare
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

**3b. If the plans agree: localise, then lever.** *This is the branch.* Bisect the named statement's
body: each CTE on its own, each union arm on its own, then one join dropped at a time. Run the
snapshot control above before believing the substructure reading. Then choose by the lever hierarchy,
a captured fact before a registration before a rewrite, preferring the deepest relation whose
materialization removes re-evaluation for more than one reader, and put the arithmetic in the
registration's `reason` with the refresh it adds priced as a cost and not only as a saving.

The localisation and the control are done and the lever is chosen; what remains of this step is the
two registrations, the index each new target declares, and their `reason` rows. An earlier draft of
this sentence said "the two registrations and their `reason` rows" and enumerated three obligations
with the index absent, which is the one obligation the build refuses the change for; the paragraph
after the bullets is that correction. Four things the rows have to carry, since the measurement
section is a fixture measurement and a `reason` is read as a statement about the relation:

- The saving and the refresh both, per registration, taken one registration at a time. The refresh
  each adds is one evaluation of its own source view, which the first table in the measurement section
  gives, and it is a cost the row must state rather than imply.
- That the mechanism is a per-driving-row re-evaluation and not a large constant, because that is what
  makes the registration the right lever rather than a rewrite of the view. A future reader who reads
  only "expensive view" will reach for the rewrite rung, which this investigation has already measured
  as the rung that pays least often.
- The rung below, and why it was not taken. A captured fact is the cheaper lever by the hierarchy, so
  the row states the reason the rung is unavailable rather than leaving it as an omission, and the
  reason is the seam and not the shape of the computation: the rows the fold walks are written by the
  refresh, and every hand-written producer has already run by then. The section below establishes
  that, and the row should say it in one clause rather than restate the argument.
- The index's own figure and the reader it was bought for, on whatever shape the target ends up
  declaring. The register's convention is that an index deciding a registration is argued twice, in the
  `reason` and at the index's own site, which is what `intent_field_scope_table` and
  `ix_field_scope_table_coordinate` do between them.

**The index each new target declares, which is part of the registration rather than a tuning of it.**
The decision is the implementer's to measure and it is not open, because the shape is dictated by a
join predicate that is readable in the DDL today and the page states the rule:
`docs/architecture/explanation/fact-model.adoc` says to declare an index on the target, on the columns
a named reader joins it on, and to give the planner current statistics after the refill. So this step
names the shape to measure first per registration and the reader that dictates it, and leaves the
figure and any alternative shapes to the measurement.

The default is to declare rather than to decline, and the reason is the register's own worst case.
`intent_field_scope_table`'s `reason` prices its one correlating reader at 6167 ms over the view,
342 ms over the indexed target and 91045 ms over the target with no index declared, so registering
without the index was fifteen times worse than not registering: an inlined view can be evaluated
restricted where a table can only be scanned. This item's own headline figures sit on that side of the
fork. The 15x and the 81x were measured by snapshotting each candidate into an *indexed* table and the
control did not record which shape, so neither figure transfers to a declared index by assumption, and
re-taking them against the shape actually declared is part of this step rather than a refinement of it.

**`intent_node_id_decode_column`: one probing reader and two scanning ones, and the probe spells a
column the family says is not a key.** `intent_mutation_payload_column_live` probes it from its
`admitted` CTE, `ON d.graph_name = a.graph_name AND d.site = 'INPUT_FIELD' AND d.path = a.path`.
`intent_node_id_decode` drives from it through a window over the whole relation, and the carrier view's
`landing` CTE aggregates it filtered on `site` and grouped on `(graph_name, use_site)`. So there is one
seek for an index to serve and two readers that would pay only maintenance, which is the
`intent_field_scope_table` shape exactly.

The shape to measure first is `(graph_name, site, use_site)` rather than the probe's literal columns,
and the reader's third conjunct becomes `d.use_site = a.path` in the same diff. That is the same
predicate and not a widening: `intent_node_id_instruction.use_site` states that an input field's
serialization *is* the occurrence path, so the two columns hold one string on every row this probe
touches. What the swap buys is that the index is then on the family's declared key rather than beside
it. `intent_node_id_decode_column.use_site`'s own comment says the whole decode family keys on that
pair and says why, and `path`'s comment on the same relation says it is carried for a reader that wants
the descent *rather than as a join key*, its sibling on the hop relation saying "never as a join key".
An index on `path` would ratify a second spelling of a value the relation already carries under its
key, in a family that states the discipline twice. It also stands to serve a second reader, `landing`'s
`WHERE site = 'INPUT_FIELD' GROUP BY graph_name, use_site` being an ordered input this shape can
supply, which makes the index a two-reader investment rather than a one-reader cost. If the measurement
prefers keeping `d.path` and indexing `path`, that is a fair outcome and it carries a second
obligation: the two column comments that say `path` is not a join key stop being true and move in the
same commit.

**`intent_input_field_carrier_role`: both readers drive from it, and it still owes a measurement rather
than a structural decline.** Each suspect names it inside an inlined CTE, `refused` in the refusal view
and `site` in the column view, joining `intent_input_field_filter_role` on the full grain
`(graph_name, type_name, field_name, resolving_source_name, resolving_schema, resolving_table)`, and
each outer query then joins that CTE on the same coordinate. Whether the outer coordinate reaches the
carrier read as a seek is a plan question and not a reading of the text, this item having recorded that
H2 optimizes an inlined view or CTE per set of index conditions at execution. That is exactly the
question a "nothing probes in" decline may not assume, so the roster's structural route is unavailable
here and `EXPLAIN ANALYZE` is the instrument. The null hypothesis to measure is the mirror of the
sibling index `ix_input_field_filter_role_site`: that six-column grain, with `carrier_role` appended on
the sibling comment's own reasoning, the refusal arm filtering `cr.carrier_role = 'REMOTE'` where the
payload arm applies no role filter, so the tail makes the two arms' populations disjoint in the index
for one reader of the two.

**Three gates, and what each wants.** `MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot`
asserts by equality in both directions that the registered targets carrying no declared index are
exactly that test's `NO_INDEX` roster, so each new target either declares an index or adds a roster
row. The roster takes two kinds of argument and the difference decides which is available here: most
rows are a measured decline, several index shapes over every view whose derivation reaches the target
with statistics current on both sides, and four rows decline structurally on a reader roster with no
measurement, every reader driving from the target so that no coordinate is sought. The deeper candidate
has a probing reader and the carrier's probe is a plan question, so neither may take the structural
route. The sibling gate `everyIndexOnATargetStatesItsReader` then requires any declared index to carry
a `COMMENT ON INDEX` naming the reader that justifies it;
`ix_node_id_decode_hop_column_step` and `ix_input_field_filter_role_site` are the exemplars for what
that comment contains, a named reader, the predicate it spells, the shapes weighed and the `UNIQUE`
decision argued. So the measurement's output is a comment and not only a column list.
`DerivedReadCostTest` is the third gate on the same decision, failing a registration that costs another
reader more than it saves, and both scanning readers of the deeper candidate are inside its matrix.

**Do not reach for a primary key instead.** Where a target's grain has no nullable column a
`PRIMARY KEY` on it looks like the fact model's key discipline arriving, and the carrier candidate may
well be that shape. It is the wrong reflex here: a PK's backing index is generated and
`declaredIndexesOf` counts only indexes the DDL declares, so a PK-only target reports as unindexed and
the gate then demands a roster row whose argument would be false. No registered target declares a PK
today, which settles the house pattern. The deeper candidate could not take that route anyway, its
grain carrying `argument_name`, `path` and `local_column_name` nullable, which is the gate's own stated
reason a key cannot supply the index.

**One thing the recursive source view changes, and it is not the index.** The recursion sits on the
write side of the registration, so it says nothing about what shape the new target wants; the
dependency walk already parses this view and `ViewReferences` handles recursive CTEs, as the
structural-candidate section records. What it touches is a `reason` one rung below.
`ix_node_id_decode_hop_column_step`'s comment opens by naming `intent_node_id_decode_column`, "whose
recursive `lifted` term joins this relation on these three columns once per accumulated row", and
after the registration the canonical name is a table with no recursive term, the recursion running once
per refresh in the `_live` view instead of once per naming. That index's justification is the weakest
in its family by its own account, the smallest gain of the five indexes it ranks itself among and
claimed on the shape rather than on its 6268-against-6124 figure, and it is now charged against a
reader whose cadence changed. Its comment moves in the same commit, and
that gate checks a comment is non-blank rather than true, so nothing but this paragraph will catch it.

**Four equality-pinned figures move with the registrations**, and they are the same omission class the
index was. `MaterializeRegistryGateTest.REGISTRATIONS` is 20 by equality and each registration edits
it. `REFRESH_STAGES` is 12 and the deeper registration lengthens the chain
`hop_column → decode_column → payload views`, so state the new depth rather than discovering it; that
constant's javadoc sanctions raising it in the commit that argues for it.
`RefreshPlanStatisticsTest.PLAN_DEPENDS_ON_STATISTICS` is pinned by equality both ways and already
holds both suspects, and an index on a freshly registered target is precisely the mechanism that set
documents, so the index decision is expected to move it. And `DerivedReadCostTest`'s cell counts move
by the readers reaching the new target.

**The first rung, checked.** Nothing above had run this check and the plan asked for it, so here it is
and here is what it returned. The question was not whether the decode walk can be captured, the hop
relation being a registered target and already a table, but whether the *fold* over it can: whether
the per-position lift `intent_node_id_decode_column` computes is derivable at capture time from rows
the capture already writes.

**It is not, and the obstruction is the seam rather than the computation.** `lifted`'s only input
relation is `intent_node_id_decode_hop_column`, and that is a registered target, which is to say a
table the *refresh* fills. `FactCapture.capture` states the order and states that it is load-bearing
in one direction: the hand-written producers run after `sink.flush` and before
`Materializations.refresh`. So at the only seam a captured fact has, the rows the fold would walk do
not exist yet. Nor does moving a producer after the refresh help, because the readers that cost the
hour are themselves refresh statements: both suspects read `intent_node_id_decode_column`, so a
producer that ran after the refresh would write its rows after everything that needed them. A captured
fact here would have to be interleaved *between* two registrations of the refresh order, which is a
hand-written body inside the register rather than a captured fact, and the register has no shape for
one.

`InputOccurrencePaths.derive` is genuine precedent for a capture-time walk and it is precedent for the
shape only, which the seam is what distinguishes: its inputs are `graphql_argument`, `graphql_field`
and `graphql_type`, transcription tables the flush wrote, so it reads base facts and this fold would
read a refresh target. That difference is the whole of the answer, and it took a read of the two
sources and no measurement, as the plan predicted it would.

Two consequences worth recording, because they close the fork rather than leaving it narrowed. What
*would* make the fold a captured fact is reimplementing the hop rule in Java as well, so the
derivation reaches base facts; that is not the top rung but the bottom one, moving a rule out of the
view it is stated in, which the hierarchy reserves for a rule no view can express, and the hop rule is
a working view carrying its own registration and its own measured reason. And it would not displace
both registrations even then: the carrier view's cost is its `landing` aggregate re-evaluated once per
driving row, which a cheaper `intent_node_id_decode_column` reduces without removing, and the 15× the
carrier substitution bought was measured with `decode_column` still a view. So the carrier
registration is needed on the measurement whatever happens above it.

One narrower middle-rung candidate exists and is not preferred: registering the fold itself, which
would first need `lifted` promoted from a local CTE to a named relation. That is the same move
`intent_node_id_instruction_live`'s `meta_materialize` reason already describes wanting for its own
inner alias, the one row in the register that mentions one: "The narrower registration that would cut
this one is the inner alias, which is a local alias rather than a named relation today and wants
promoting to one before it can be registered." It is
unpriced where the two registrations here are priced, and it does not remove the carrier's
per-driving-row aggregate either. It belongs to whatever item argues the register's shape rather than
to this fix.

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

  *Landed, as `RefreshPlanStatisticsTest`, and in the disagreeing form rather than the pinning one.
  This note previously read that step 2 agreed and that the test would pin statements not depending on
  statistics; the wider measurement says otherwise, seven of the twenty registrations depending on
  them, so what it pins is which seven. Three corrections to the paragraph above, each arrived at by
  getting it wrong first. The claim cannot be the equality stated, seven registrations failing it on
  the shipping tree, so it is a pinned set asserted by equality both ways, which is
  `DerivedReadCostTest`'s ratchet. The trap named is not the trap: H2 renders no estimate into these
  plans except the rows-visited annotation, which the test removes. And the reduction recommended is
  itself the trap, missing four of the seven because the failing shape is one index used two ways and
  the difference lives in the seek conditions a reduction discards, so whole executed plan text minus
  that one annotation is both simpler and strictly more faithful. The assertion in it that earns its
  place is `analysingTheFactsAloneReachesNoneOfThem`, which is what fails if somebody lands 3a's cheap
  half believing it closes this.*
- **The per-driving-row claim is the assertion worth having instead**, and it is the one the fix is
  actually about. What the measurement above establishes, and what a future edit could silently undo,
  is that no registered source view re-evaluates an unregistered view once per driving row. The
  directional form is assertable without a wall clock: for each suspect, the cost of the statement
  against a snapshot of the candidate must not exceed its cost against the view. `DerivedReadCostTest`
  is the precedent and this is the same shape as its existing claim rather than a new kind of test, so
  the honest question at implementation time is whether this belongs in that test as another axis
  rather than as a test of its own. Prefer joining it.
- **The invariants step 3a creates need enforcers of their own**, and a fix that lands without them is
  half a change: what serializes a refresh transaction against a second writer of the same graph, and
  what a source stamp promises once facts and targets commit separately. Both are assertable at the
  pipeline tier, and the second already has a family of tests to join rather than a new one to invent.
  *Moot while 3a is set down. Retained with that arm.*
- **The index decision has its gates already and adds no test.** Three existing assertions decide it
  and none of them is written for this item, which is the reason to name them here rather than to
  invent a fourth: `MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot` refuses a target
  that neither declares an index nor earns a `NO_INDEX` roster row, its sibling
  `everyIndexOnATargetStatesItsReader` refuses a declared index whose comment names no reader, and
  `DerivedReadCostTest` refuses a registration that costs another reader more than it saves. What the
  implementer writes is a measurement and two comments, not a test. The equality-pinned constants in
  step 3b's last paragraph move in the same commit for the same reason.
- **The consumer-scale result stays a figure**, in this item while it lives and in the registration's
  `reason` or the changelog afterwards. Nothing in this repo captures a schema of that size, and
  building a fixture that did would be a wall-clock gate, which the build-guardrail item is the place
  for.

If step 1 reports a completing capture, the deliverable is the smallest thing that would have told us
so in the first place: the sibling logging item, which is its own item, plus a `roadmap/changelog.md`
entry recording that the failure was on a DDL two generations back and which registrations closed it.

## Roadmap entries

The original plan expected none, and the reasoning is kept because the reopen vindicated half of it:
filing 3a and 3b as Backlog stubs the measurement promotes would have given the gate one design to
approve at a time, at the cost of leaving a priority-1 bug with no fix in it. Keeping both on one item
is what let the measurement land here and pick a branch without a second filing. What it cost is this
reopen, which is the mechanism the plan named for exactly this case, so the trade came out roughly
even rather than badly.

Three entries, all from the measurement rather than from the fix and none blocking. Two are filed and
the third is still expected:

- **R867 carries the first arm's measurement past this file's deletion**, and it is filed rather than
  left here for one reason: what "The first arm measured over all twenty registrations" establishes is
  a cost that grows with the schema, and this item's body dies at Done. The test and the fact model
  page keep the finding, and R867 keeps the prospective work: the cheap half refuted, the expensive
  half priced at exactly the settled store's plans, and the three invariants a transaction split
  touches. It opens by saying it should not be picked up unless the registrations have landed and a
  consumer-scale capture still costs materially more than the same refresh warm, so it cannot compete
  with the branch this item chose.

- **`report-inline-multiplicity` cannot see the two things that made this expensive**, and R871
  carries it, filed fresh. It counts references to relations the DDL declares, so a local CTE name is
  invisible to it, and H2 inlines a non-recursive CTE exactly like a view; it under-reported the
  carrier view's expansion by half in each suspect for that reason. And it cannot see correlation at
  all, which is the factor that turns a constant into a driving-row count. The metric's own
  documentation is careful that it ranks breadth and not cost, so this is not a broken tool; it is a
  tool that missed the two relations that stopped captures from finishing.

  An earlier draft of this bullet routed the finding into R849 instead, on the reading that R849 was
  Ready and would put the keep-or-delete question to this reporter through its acceptance gate's
  negative branch. That reading is wrong twice over now. R849 is Done, and `roadmap/changelog.md`
  records it as a negative result: the weighted metric was built, run against a real capture, failed
  its own pre-committed gate, and the negative branch was executed, `ReEvaluationMetric` and its test
  deleted with `ViewReferences` and its positions kept. The branch asked its question of the metric
  R849 itself built, and `report-inline-multiplicity` survived that pass without being asked:
  `InlineMultiplicityCheck` is still wired into `Main`, still configured in that module's pom and
  still documented on the fact model page. So the destination is closed and the decision it was meant
  to inform was taken without it.

  Filed fresh rather than folded into R848, which is the other candidate and is the wrong one on its
  own terms: R848 is In Progress and declines instrument work explicitly, on R849's result that a
  static reading of the stored definitions cannot rank the twenty registrations. Folding a live
  reporter's keep-correct-or-delete question into an item under implementation would widen a plan
  whose scope section exists to refuse exactly that. Dropped-as-spent was the third option and is
  refused for the opposite reason: the reporter runs in the build today, its ranking is what a reader
  meets first, and the two relations it missed are the sharpest worked case anybody has for what it
  cannot see. R871 carries the case and the three dispositions, and the observation itself outlives
  this file either way, being recorded where a reader of the metric meets it.
- **`Materializations.analyse`'s javadoc prices statistics with a scan count.** 8880 scans against
  523, offered as "most of the gain the indexes exist for". Scan counts are row counts and not costs,
  and the tree already records an index that removed 96% of a relation's visits and moved the clock not
  at all. Either take a timing on that fixture or restate the sentence as the row-count claim it is.
  Small, and worth filing rather than folding into this item's diff, since it is a correction to a
  neighbouring mechanism's prose rather than part of this fix.

The sibling logging item R855 was the dependency for step 1 and has shipped, so `depends-on` is
empty and step 1's instrument is in the tree: the refresh prints its pass boundary by default and
names each registration before it runs it under `mvn -X`. R848 is where a twenty-first registration
is argued, and this item now proposes a
twenty-first *and* a twenty-second, so the obligation is stronger than when this paragraph was
written: both registrations have to be defensible against that item rather than in spite of it, and
the pair arriving from one bug is itself evidence for whatever that item concludes about the
register's shape.

## Related

The sibling logging item adds per-registration output to the refresh. Its value has changed shape
since this was written rather than diminished: the statement is now identified, so the logging is no
longer the only route to a localisation, and it is instead what turns the fix's verification run from
an hour of silence into a progress report. It should still land first, and step 1 is still the
argument for it.
R850 describes this failure mode at a different relation and assumes the trigger is someone editing
that arm; this is a second trigger, on store size alone with no code change. R839 is position 6.
R848 is the frame, asking whether the register's shape is right at all, and it is In Progress. R849
was the instrument that frame asked for and it is Done as a negative result: the weighted metric was
built, refused by its own acceptance gate and deleted, with `ViewReferences` kept, and
`roadmap/changelog.md` carries the figures. So the frame proceeds without a score, which is what R848
now plans around. The reporter finding in the Roadmap entries section is R871, filed fresh, that
gate having already run without reaching the reporter. R857 has a dev start
evaluating the register twice, which doubles whatever a refresh costs on the surface a person waits
on, and its second pass runs on a settled store with statistics, so it is not the pass this item is
about.

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session 011bBUdhmLsSsrtotN6UKsdg)

Verdict: withhold. Two findings, both on question 2, and both about the same thing: the
Implementation section holds two positions at once on whether the two registrations are the fix.
Neither finding disputes the localisation, which is the strongest work in this item.

Question 1 passes and reads off the plan without reconstruction. Today a developer on a consumer
schema of this size cannot use the tooling at all: `graphitron:validate` never returns and
`graphitron:dev` never binds its language server or MCP ports, because the bind sits behind a
capture that has never been observed to finish. When this lands, that capture completes and both
goals return, with the store answering exactly what it answers now. The mechanism behind it is
identified positively rather than by elimination, the identification survives its own controls, and
two independent lines of evidence, the population before-and-after and the fixture prices, land on
the same pair of registrations. The item is also careful about what it has not established: no
consumer-scale capture, no exponent, and it refuses to extrapolate one. That care is what makes the
rest of it trustworthy.

**Finding 1: the amended sequence drops the pre-fix consumer capture, and the section it supersedes
says nothing below may be chosen before that capture reports.** Step 1 is "one instrumented capture
of that schema on the current DDL", and its own text says "Nothing below is chosen before this step
reports" and that if it completes, the remaining work is step 5 alone and the priority drops. The
amended sequence's preamble agrees the debt is live, saying step 1 "is still owed on the consumer
schema", and then the sequence never pays it: its item 4 is the capture *against the fix*, mapped
explicitly onto step 4. So an implementer following the amended sequence lands the twenty-first and
twenty-second registrations without ever learning whether the failure still reproduces on the DDL the
tree ships. The item supplies the reason that makes this matter rather than pedantic: the section
above it establishes that `intent_mutation_write_payload`, the driving relation of both suspects, was
an inlined view on the slow store and is a table now, so the suspects' cost has already moved in the
direction of cheaper by an unmeasured amount, and R848 is a live item asking whether the register
should grow at all. The same paragraph in the same section also opens "Steps 1 and 2 have run" and
then says step 1 is still owed, which is the contradiction in miniature.

What would satisfy it: either put the pre-fix capture ahead of the registrations in the amended
sequence, or say plainly that the fixture prices justify both registrations whatever that capture
would now report, and carry the argument for why that survives R848. Either is fine; what a reviewer
cannot approve is the plan holding both.

**Author note.** Took the first branch: the pre-fix capture is now item 2 of the amended sequence,
ahead of both registrations, and the registrations moved to 3 and 4. The preamble no longer says step 1
has run; it says step 2 has and step 1 has not, and names the earlier draft's claim-then-skip as the
thing it is correcting. Two additions came with it. A paragraph after the sequence says why the
capture keeps its place rather than the fixture prices standing in for it, on the two facts the
finding named, the driving relation having become a table and R848 being live, and states step 1's two
outcomes as a decision rule: a completion drops this to step 5 alone with neither registration landing
under it, a non-completion names the registration and licenses steps 3 onward. And a paragraph under
step 1 itself says what "reports" means, since a step ahead of the fix must not become an unbounded
wait: the sibling item emits a registration's name before its `DELETE`, so a run stopped inside one has
already reported and no completion is required to discharge the step.

**Finding 2: the plan tells the implementer to write into a permanent `reason` row that the top rung
is unavailable, and the paragraph below it says nobody has checked and that if it is available it
beats both registrations.** Step 3b's third `reason`-row obligation is "The rung below, and why it
was not taken", with the reason given as settled: what these relations compute is a fold over the
decode walk rather than anything the capture reads off a schema document or a catalog. The next
paragraph then asks for "one explicit check before either registration is written, because nothing
above has run it", frames it as whether the per-position lift is derivable at capture time from rows
the capture already writes, and states outright that "If it is, that is a captured fact and it beats
both registrations". Those are two positions on one question in adjacent paragraphs, and this one is
not a detail of the fix: it is the identity of the fix. The check is also not obviously going to come
back "no". `intent_node_id_decode_hop_column` is already a registered target, so the rows the fold
walks are already a table the capture writes, and `InputOccurrencePaths.derive` is standing precedent
in this repo for a hand-written capture-time derivation that computes a walk. An implementer who runs
the check and gets "yes" is designing a captured fact, which this plan does not specify anywhere.

What would satisfy it: run the check, which the plan itself prices at a read of the hop relation's
comment and no measurement, and record the answer in the plan body. Then the design under review is
the fix rather than a fork, and the `reason`-row obligation states something that was established
rather than assumed. This is the item's own standard: the reopen paragraph withholds approval from an
arm whose decision rule had returned a verdict, and this is a decision rule that has not been run.

**Author note.** Ran the check and recorded the answer in step 3b, which now opens "The first rung,
checked" instead of asking for it. The answer is no, and the obstruction is the seam rather than the
computation, which is a different reason from the one the `reason`-row bullet was asserting:
`lifted`'s only input is `intent_node_id_decode_hop_column`, a registered target the *refresh* fills,
and `FactCapture.capture` runs every hand-written producer before `Materializations.refresh` and says
that order is load-bearing. So at the only seam a captured fact has, the rows the fold walks do not
exist yet, and a producer moved after the refresh is too late because the readers that cost the hour
are themselves refresh statements. A captured fact here would have to sit between two registrations of
the refresh order, which is a hand-written body inside the register and not the top rung.

That also disposes of the two pointers the finding supplied. `intent_node_id_decode_hop_column` being
a registered target is exactly what makes the fold unreachable rather than reachable, the target being
the refresh's output and not the capture's. And `InputOccurrencePaths.derive` is precedent for the
shape and not for the seam: its inputs are `graphql_argument`, `graphql_field` and `graphql_type`,
transcription tables the flush wrote. The section says both in those terms.

Two things were added rather than only the answer, so the fork closes instead of narrowing. What would
make the fold a captured fact is reimplementing the hop rule in Java too, which is the bottom rung and
not the top one, and the section says why. And even a "yes" would not have displaced both
registrations: the carrier view's cost is its `landing` aggregate re-evaluated once per driving row,
which a cheaper `intent_node_id_decode_column` reduces without removing, and the 15× the carrier
substitution bought was measured with `decode_column` still a view. One narrower middle-rung candidate
is named and declined in the same place, registering the fold itself, which would need `lifted`
promoted from a local CTE to a named relation first. The third `reason`-row bullet now states the seam
in a clause and points at that section rather than asserting a settled reason of its own.

Non-blocking, no response needed:

- The two refresh-position tables number from different bases, the price list from the
  16-registration order and the measurement section's from the 20-registration one, so positions 12
  and 13 name different relations in each. Both are provenance rather than instruction, and the
  measurement section already warns the two populations are separate; saying which order each table
  numbers in would cost a clause.
- The proposed Backlog item on `report-inline-multiplicity` may already be filed. R849 is open on
  exactly that question and reached Ready while this review was being written, and `ViewReferences`
  plus `ReEvaluationMetric` are already in the tree
  reading recursive and correlated positions off the stored view definitions, which is the capability
  the bullet asks someone to build. Worth reading R849 before filing.
- Registering `intent_node_id_decode_column` would be the first registration whose source view
  carries a recursive term; none of the twenty has one. I looked for a viability problem and found
  none: the dependency walk already parses this view today, reaching it from
  `intent_mutation_payload_column_live`, and `ViewReferences` handles recursive CTEs explicitly. Noted
  only because it is a first.

**Author note on the three.** All acted on. Each table now says which order it numbers in, and the
sentence says a position is comparable between them only through the relation it names. The
`report-inline-multiplicity` bullet is no longer a filing: R849 read as suggested, and it disclaims
touching that reporter at any outcome *except* through its acceptance gate's negative branch, which
asks whether the shipped step is deleted or kept as a nearly-right one, so the bullet now carries the
two missed relations into that branch as a worked case rather than proposing an item beside it. The
Roadmap entries section opens on one expected item instead of two, and Related says where the finding
went. The recursive-source-view note is recorded as a first in the structural-candidate section, in the
terms it was given, viability included.

### Round 2 (2026-08-27, Spec -> Ready, reviewer session 01FLu4Z5tDnLbeZXMWU7jGe4)

Verdict: withhold. Two findings, both on question 2. Round 1's two findings are answered and stay
answered: the pre-fix capture is now item 2 of the sequence with a decision rule and a discharge
condition, and the first rung is checked with an answer that holds up against the tree. Neither
finding below disputes the localisation or the choice of lever, which remain the strongest work in
this item.

Question 1 passes, and it reads off the plan without reconstruction. Today a developer working on a
large internal schema, about 8,400 fields and 2,300 types, cannot use this tooling at all:
`mvn graphitron:validate` never returns and `mvn graphitron:dev` never binds its language server or
MCP ports, because both sit behind a fact-store capture that spends over an hour inside the
materialization refresh and has never once been observed to finish. When this lands, that capture
completes: validate returns, dev binds, and the store answers exactly what it answers now, the same
relations and rows under the same names every reader already spells. The outcome is reachable in this
codebase, because the lever is a mechanism the tree already runs twenty times rather than a new one,
and the identification behind it is positive and measured rather than an elimination.

**Finding 1: the plan does not say what index either new target carries, and that decision is
build-gated, precedented as decisive, and inside the measurement the plan is priced on.** Step 3b
closes with "what remains of this step is the two registrations and their `reason` rows" and then
enumerates three things those rows have to carry: the saving and the refresh, the per-driving-row
mechanism, and the rung below. The index is not among them, and the word does not appear anywhere in
the Implementation, Tests or structural-candidate sections; its only appearances in this item are the
control's own indexed snapshots and a note about the *existing* refusal registration's index.

Three facts make that an omission rather than a detail. First, it is enforced:
`MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot` fails the build unless every
registered target either carries a declared index or has a row in that test's `NO_INDEX` roster
saying why not, and the roster's own standard is a measured decline, "measured as several index
shapes, over every view whose derivation reaches the target, with statistics current on both sides".
So an implementer following step 3b lands the registration, meets a gate the plan never mentions, and
has to design and measure the answer with no guidance on a priority-1 bug.

Second, the question is live rather than formal for the registration this plan lands *first*.
`intent_mutation_payload_column_live`, the 1075.8 ms statement, reaches the deeper candidate through
`JOIN intent_node_id_decode_column d ON d.graph_name = a.graph_name AND d.site = 'INPUT_FIELD' AND
d.path = a.path`, driven from its `admitted` CTE. That is a probe on a coordinate, which is exactly
the shape the gate's roster calls a seek an index could serve, and it is the shape
`intent_mutation_payload_refusal`'s own reason row calls "the whole point of the registration".

Third, the register already records this going wrong in the direction that matters.
`intent_field_scope_table`'s reason prices its one reader at 6167 ms over the view, 342 ms over the
indexed target and 91045 ms over the target with no index declared, which its own text calls fifteen
times worse than the view. That is the case where registering without the index was worse than not
registering, and this item's headline figures are on the other side of that fork: the 15x and the 81x
were both measured by snapshotting into an *indexed* table, as the control paragraph says outright. So
the numbers that justify the fix are numbers an un-indexed registration is not entitled to.

What would satisfy it: say in step 3b what the index decision is for each of the two registrations,
or say that it is the implementer's to measure and name the gate and the roster it has to satisfy.
Either is fine, and pricing it here is not required. What a reviewer cannot approve is an
enumeration that presents itself as the remaining work while leaving out the one obligation the
build will refuse the change for.

**Author note.** Took both branches, which turned out to be one: the shape is named per registration
and the figure is the implementer's. Step 3b's closing sentence now says "the two registrations, the
index each new target declares, and their `reason` rows", the enumeration is four obligations with the
index's own figure and its reader as the fourth, and a paragraph after the bullets carries the
decision. What made the shape nameable rather than open is that the fact model page already states the
rule, declare an index on the target on the columns a named reader joins it on, and that for both
candidates the reader that dictates it is in the DDL now. So the paragraph names the shape to measure
first with the reader that dictates it, states the default as declare-rather-than-decline on the
`intent_field_scope_table` precedent the finding supplied, and leaves the figure and any alternative
shape to the measurement.

The deeper candidate's shape is not the probe's literal columns, and finding it out is the one place
this went somewhere the finding did not point. The probe is on `(graph_name, site, path)` as quoted,
but `intent_node_id_decode_column.path`'s own comment says it is carried for a reader that wants the
descent *rather than as a join key*, the hop sibling says "never as a join key", and `use_site`'s
comment states that the whole decode family keys on that pair and why. The two columns hold one string
on every row the probe touches, `intent_node_id_instruction.use_site` recording that an input field's
serialization is the occurrence path itself. So the shape to measure first is
`(graph_name, site, use_site)` with the reader's third conjunct spelled `d.use_site = a.path` in the
same diff: the same predicate, on the family's declared key instead of beside it, and it stands to
serve the carrier view's `landing` aggregate as an ordered input too, which makes it a two-reader
investment. Indexing `path` stays a fair outcome of the measurement and now carries its own
obligation, the two column comments that call it not-a-join-key moving in the same commit.

Four things came with it that the finding did not ask for and that belong to the same omission class.
The carrier candidate cannot take the roster's structural route, its readers driving from it inside
inlined CTEs whose outer coordinate may or may not reach it as a seek, which is precisely the plan
question a "nothing probes in" decline may not assume; its null hypothesis is stated as the mirror of
`ix_input_field_filter_role_site`. The gate has a sibling, `everyIndexOnATargetStatesItsReader`, so the
measurement's output is a `COMMENT ON INDEX` and not only a column list, and `DerivedReadCostTest` is a
third gate on the same decision. A `PRIMARY KEY` is the wrong reflex here, its backing index being
generated where the gate counts only declared ones. And the deeper registration makes
`ix_node_id_decode_hop_column_step`'s comment false one rung below, that comment naming
`intent_node_id_decode_column`'s recursive term as the reader it serves once per accumulated row, which
is the one thing the recursive source view actually changes and it is not the index. Step 3b closes on
the four equality-pinned constants each registration moves, `REGISTRATIONS`, `REFRESH_STAGES`,
`PLAN_DEPENDS_ON_STATISTICS` and `DerivedReadCostTest`'s cell counts, for the same reason the index was
worth stating: they are what the build refuses the change for.

Two places outside step 3b moved with it. The snapshot control paragraph now says that "indexed" is
load-bearing and that the shape was not recorded, so the 15x and the 81x are an indexed target's and
not a registration's until the declared shape reproduces them. And Tests gains a bullet naming the
three existing gates and saying that what the implementer writes here is a measurement and two
comments rather than a test.

**Finding 2: the item's disposal of its own `report-inline-multiplicity` finding routes it into R849,
which has shipped, and whose acceptance gate has already run.** The Roadmap entries bullet says the
capability exists and that "R849 is Ready to build the weighted metric over it", that R849 "disclaims
touching this reporter at any outcome except through its acceptance gate's negative branch", and that
the finding "should be carried into R849 rather than filed beside it". Related says the same, that
R849 "builds the instrument that frame needs". R849 is Done: `roadmap/changelog.md` records it as a
negative result, the metric was built, run against a real capture and refused, `ReEvaluationMetric`
and `ReEvaluationMetricTest` are deleted, and `ViewReferences` and its positions are kept. The
negative branch is one of the commits that entry lists as executed.

The reporter itself survived that: `InlineMultiplicityCheck` is still in `roadmap-tool`, still wired
into `Main`, still configured in that module's pom and still documented on the fact model page. So
this item's observation is live, and it is a good one, the two relations that stopped captures from
finishing being the sharpest worked case anybody has for what that reporter cannot see. As the plan
now reads it lands nowhere: the destination is closed, and the decision it was meant to inform was
taken without it. This is not a plan-body edit a reviewer can make, because the alternatives, file it
fresh, fold it into R848 where the register's shape is argued, or drop it as spent, are a choice about
what gets filed rather than a stale pointer.

What would satisfy it: pick one of those and say so, with the two false sentences about R849's status
corrected. On its own this finding would not have withheld the gate; it travels with finding 1.

**Author note.** Picked file-fresh, and filed it rather than only saying so: R871,
`roadmap/inline-multiplicity-reporter-misses-inlined-ctes-and-correlation.md`, Backlog, carrying the
worked case and three dispositions for the reporter, keep-as-is with the blind spots documented,
re-base it on `ViewReferences`, or delete it on R849's own precedent. Filing rather than pointing is
the part this finding is really about: a disposal that names a destination it does not create is the
same failure one round later.

Fold-into-R848 was the alternative and is refused on that item's own terms. R848 is In Progress and its
"What this item does not do" section declines instrument work explicitly, on R849's result that a static
reading of the stored definitions cannot rank the twenty registrations, so a live reporter's
keep-correct-or-delete question would widen a plan whose scope section exists to refuse exactly that.
Drop-as-spent is refused for the opposite reason: the reporter runs in the build today, its ranking is
what a reader meets first, and the two relations it missed are the sharpest case anybody has for what a
breadth ranking cannot see.

The two false sentences are corrected and the correction is stated as one rather than silently applied,
since the Roadmap entries bullet is where a future reader will look for the reasoning. That bullet now
says R849 is Done and a negative result, names what the negative branch actually deleted, and says that
the branch put its question to the metric R849 built while `report-inline-multiplicity` survived the
pass unasked, `InlineMultiplicityCheck` still being wired into `Main`, still configured in that
module's pom and still documented on the fact model page. Related no longer says R849 "builds the
instrument that frame needs": it says the instrument was built, refused by its own gate and deleted
with `ViewReferences` kept, that `roadmap/changelog.md` carries the figures, and that R848 therefore
proceeds without a score.

**Author note on the three non-blocking items.** All three acted on. The middle-rung citation now names
`intent_node_id_instruction_live`'s reason and quotes the sentence, that being the only row in the
register that mentions an inner alias, where it previously pointed at the hop-column reason, which is
about collapsing the walk's six-column coordinate key. The amended sequence's item 1 is struck through
and marked spent, saying R855 is Done and the work starts at item 2. And the debug tier is now stated
twice where it is needed rather than once where it is not: step 1's instrument bullet says the run that
matters has to be taken under `mvn -X`, and the discharge paragraph repeats it, on the reviewer's
reasoning that a second attempt at an hour-long run for want of a flag is the avoidable failure.

Non-blocking, no response needed:

- The declined middle rung cites the wrong register row. "That is the same move `meta_materialize`'s
  hop-column reason already describes wanting for its own inner alias" names
  `intent_node_id_decode_hop_column_live`, whose reason is about collapsing the walk's six-column
  coordinate key and says nothing about an inner alias. The sentence the item wants is in
  `intent_node_id_instruction_live`'s reason, the only row in the register that mentions one: "The
  narrower registration that would cut this one is the inner alias, which is a local alias rather
  than a named relation today and wants promoting to one before it can be registered." The precedent
  is real, so this is a pointer to fix rather than an argument to withdraw. Left for the author
  because correcting a citation inside a paragraph is close enough to the plan body to be worth not
  touching.
- The amended sequence's item 1 is spent. R855 went Done while this review was being written, and
  that commit emptied `depends-on` and rewrote the Roadmap entries paragraph to say the instrument is
  in the tree; the sequence itself still opens "Land the sibling logging item", so as it now reads
  the work starts at item 2. The ordering this item leans on is confirmed at the source rather than
  taken from that item: `RefreshProgress` exists, `FactCapture.capture` passes
  `RefreshProgress.lines(LOG::info, LOG::debug)`, `RegistrationStarted` is observed before
  `refreshPartition` issues the `DELETE`, and both `Materializations`' javadoc and
  `docs/architecture/explanation/fact-model.adoc` state that ordering as the instrument's whole
  point. So the discharge rule in step 1 rests on something real.
- The per-registration line is behind a debug tier, in both the code comment and the fact model page.
  The paragraph R855's Done commit added names `mvn -X`, so the item does carry it now, but it
  carries it in the Roadmap entries section while the run that needs it is step 1 and the discharge
  rule that depends on it is the paragraph under step 1. Repeating it there would cost a clause and
  save the person on that machine a second attempt at an hour-long run.
