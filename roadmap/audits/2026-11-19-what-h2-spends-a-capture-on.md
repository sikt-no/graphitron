# What H2 spends a capture on, 2026-11-19

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It is
R1131's research behind queue steps 15 to 17: where the test suites' H2 time goes, why, and what
makes it cheaper. It follows the `store-performance` skill, and its figures come from the agent
sandbox (4 cores, 15 GB), so whole-build numbers do not compare with the workstation's.

## Where the H2 time goes

From a JFR recording of graphitron-model's test fork (trunk 2df1c2027 with targeted analysis, stack
depth 256), every sample with an H2 frame classified by phase, by what H2 was doing, and by the
nearest graphitron frame. 27,923 of 33,326 samples (84%) are in H2.

| what H2 is doing | share of H2 samples |
|---|---:|
| preparing: join-order search, re-planning nested views per candidate order | 25.0% |
| executing: index seeks and scans | 25.8% |
| preparing: planning a view | 8.3% |
| preparing: parsing SQL | 7.0% |
| executing: writing rows, index maintenance, constraints | 6.3% |
| executing: `ANALYZE` | 5.2% |
| executing: evaluating conditions | 5.1% |
| preparing: plain join-order search | 3.7% |

About 44% of H2's time is preparing statements. The innermost methods are planning bookkeeping, not
data work: `ConditionAndOrN.setEvaluatable` and `Comparison.setEvaluatable` together 10.6%,
`ExpressionVisitor.addDependency` 3.7%, index cost estimates about 6%, and
`StringUtils.quoteIdentifierOrLiteral` 3%.

By caller: `EntryDefects.derive` 13.8% (10% its join-order search), `GraphitronModelStore.create`
8.3% (parsing the DDL and planning every view a store creates), `ModelCapture.analyze` 5.3%, and the
derivation stages after them at 1 to 3% each.

## Why: a fresh store plans everything cold

H2 keeps prepared statements and view plans per session. A store a test opens fresh plans every
statement it runs from scratch, and the suites open hundreds. Measured on the 29 entry-defect rules,
the first execution of each against a graph with no rows (so execution is almost free and planning
is what is timed), median of three:

| | in a store that has captured 40 graphs | in a fresh store |
|---|---:|---:|
| all 29 rules as bare `SELECT`s | 5 ms | 515 ms |
| as `INSERT ... SELECT` | 6 ms | 510 ms |
| as the `MERGE ... USING` the stage sends | 10 ms | 519 ms |

So rewriting the stage's `MERGE` as `INSERT ... SELECT`, a hypothesis taken from the stack shapes,
buys nothing: the cost is planning the rule view, whatever wraps it. And a probe that reuses one
session measures the warm cost, which here was a hundredth of the cold one.

Three rules carry 400 of the 515 ms cold: `occurrence-producers` 172 ms, `occurrence-depth-defects`
130 ms, `occurrence-builds` 98 ms. None reads a view. Each is a union of 19 to 25 arms, many of whose
query blocks join seven or more inputs, which is where H2's join-order search stops being exhaustive
and is most expensive.

## A static measure that ranks them

`ViewComplexityTest` parses every view with jOOQ's parser, splits it at its top-level `UNION`s, and
weighs each query block by the join orders a search of its inputs considers. Against the cold costs:

| rule | cold first execution | static weight | widest block | blocks of 7 or more |
|---|---:|---:|---:|---:|
| `occurrence-depth-defects` | 130 ms | 96,536 | 11 | 12 |
| `occurrence-producers` | 172 ms | 69,515 | 12 | 8 |
| `occurrence-builds` | 98 ms | 35,346 | 8 | 6 |
| `occurrence-tenants` | 5 ms | 34,412 | 10 | 4 |
| `occurrence-bindings` | 17 ms | 12,658 | 8 | 2 |
| the other 24 | 0.5 to 17 ms | under 7,100 | 1 to 8 | 0 or 1 |

It picks the three expensive rules and misranks `occurrence-tenants`. It reads 264 views in about
0.9 s of CPU with no store; jOOQ 3.20.11 and 3.21.10 give identical reports. Three arms the parser
refuses are named in the report (`graphitron_call_context_key_rule`,
`graphitron_outbox_target_rule`, `graphitron_outbox_routine_target_rule`), and four views over a
`VALUES` list are named as such.

## Analysis

`ModelCapture` ran a whole-store `ANALYZE` twice per capture. Measured on H2 2.5.250 with a table
holding rows and an empty one, both with `graph_name` declared at selectivity 1: `ANALYZE TABLE f`
measured `f` and left the other alone; a whole-store `ANALYZE` reset the empty table's declared
value to 50, H2's default. So the whole-store pass undid `GraphPartition`'s declaration on every
stage table still empty at the capture's first `ANALYZE`.

Two experiments, each an interleaved pair of graphitron-model and graphitron against the
whole-store pass on the same tree, listed as pair 1 / pair 0 (fork CPU):

| analysis policy | model fork | graphitron fork |
|---|---:|---:|
| whole store, twice per capture | 1,301 s / 1,280 s | 716 s / 700 s |
| only the tables the capture changed | 1,286 s / 1,242 s | 668 s / 669 s |

| analysis policy (an earlier tree) | model fork | graphitron fork |
|---|---:|---:|
| whole store, twice per capture | 1,241 s / 1,299 s | 658 s / 660 s |
| none, H2's own (`ANALYZE_AUTO` default) | 1,256 s / 1,221 s | 648 s / 666 s |

Targeted analysis is the policy kept: a few percent cheaper and the declaration intact. Letting H2 analyse itself removed `ANALYZE` (5.2% of the
model fork's samples, 11.6% of graphitron's) but its plans cost about as much again, and
`RulePlanGateTest` caught three rules probing by `graph_name` alone where the indexes they need exist
but go unpicked without statistics.

## The three rules, arm by arm

Each top-level arm timed alone, cold: `SELECT COUNT(*) FROM (<arm>) x (<the view's columns>) WHERE
x.graph_name = '<a graph with no rows>'` in a store of its own, median of three, the view's leading
`WITH` carried onto every arm. The arms add up to more than the whole view, since each pays planning
the view shares, so they rank rather than sum.

| rule, whole view cold | the arms that carry it (cold ms) |
|---|---|
| `occurrence-producers`, 153 ms | 18 (66), 10 (51), 15 (41), 17 (37), 1 (28): 78% of the arms' total |
| `occurrence-depth-defects`, 127 ms | 20 (38), 22 (32), 8 (27): 46%, the rest spread thin |
| `occurrence-builds`, 114 ms | 14 (77), 6 (58), 13 (35): 54% |

Every one of them has a query block joining seven inputs, the most H2 searches exhaustively (5,040
orders), with correlated `EXISTS` or `NOT EXISTS` guards over several of the joined tables. The
dearest, producers' arm 18 (`SERVICE_RETURN_MISMATCH`), joins producer, occurrence, field, element,
result, call method and `code_method`, then guards with five correlated subqueries under `OR` and
`CASE`; builds' arm 14 (`NODE_ID_LANDS_ON_TABLE`) joins seven and guards with four `NOT EXISTS`, three
of them the same probe of `graphitron_argmapping_landing` at three coordinates. That matches the
recording's innermost frames: evaluability bookkeeping repeated per candidate order. The arms and
the two levers (narrower blocks, a repeated guard stored once) are handed to R876's node "No view
reads a view, and every rule view is simple".

The static weight ranks arms less well than views: of its top five arms per rule, 2, 1 and 3 are in
the measured top five. Two reasons, both for `ViewComplexityTest` to take up. Its weight keeps rising
past seven inputs, where H2 switches to a cheaper partial search (producers' arm 16 joins twelve and
costs 16 ms); it should peak at seven. And among seven-input blocks it cannot tell a 66 ms arm from a
15 ms one; the correlated conditions over the block are the likely discriminator, and it does not
count them yet. A shared `WITH` is also counted once though H2 inlines it at each reference.

## The weight, corrected against the arms

`ViewComplexityTest`'s weight was reworked against the 67 measured arms and the 29 rules whole,
compared by Spearman rank correlation and by how many of each rule's five dearest arms it also put
in its top five.

H2 2.5.250's `Optimizer` searches every join order up to `MAX_BRUTE_FORCE_FILTERS` (7) inputs and,
past that, at most `MAX_BRUTE_FORCE` (2,000) partial orders and `MAX_GENETIC` (500) genetic rounds, so
a block of eight inputs plans in fewer orders than one of seven. The measured arms agree: in
`occurrence-depth-defects`, arms whose widest block joins seven inputs cost 31 to 38 ms, those joining
eight to ten 9 to 13 ms. A view's leading `WITH` is now carried onto every arm, as H2 sees it, and a
CTE adds its weight wherever it is named.

| weight per block | Spearman, 67 arms | top-5 overlap, of 15 | Spearman, 29 rules |
|---|---:|---:|---:|
| `n!` rising past seven (the first version) | 0.69 | 6 | 0.92 |
| H2's orders alone (`n!` to seven, 2,500 past it) | 0.79 | 11 | 0.89 |
| orders times (1 + subquery guards) | 0.76 | 10 | 0.87 |
| orders times (1 + conditions), kept | 0.82 | 10 | 0.87 |
| orders times (1 + conditions + 3 times guards) | 0.81 | 10 | 0.87 |

Most of the gain is H2's real search bound and the carried `WITH`; counting conditions adds a little
at arm level and costs a little at rule level, and on this sample a few hundredths is not a
difference to lean on. The kept weight follows the mechanism the recording showed, H2 re-checking
every condition per order tried, and the report carries `conditions` and `guards` beside it.
`occurrence-tenants` stays the rule-level miss: third by weight, with 42 guards, and 5 ms cold.
