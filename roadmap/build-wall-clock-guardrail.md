---
id: R733
title: "Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-20
---

# Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured

R732 harvests the three slices whose wins were measured before it was written, and stops there
deliberately. This item carries the rest: the slices whose wins were still unmeasured at that point,
and the guardrail without which the recovered time drifts back. It is the second pass, and it wants
its own Spec cycle rather than an amendment to the first, because the guardrail is an architectural
choice and the remaining slices need numbers before they can be ordered against each other.

Everything below restates the facts it needs rather than pointing into R732's body, because that
body is deleted when R732 reaches Done.

## A third measurement pass has run, and it is the current word

Two passes are recorded below this section. Read this one first: it was taken after R742 landed,
which moved the cost again, and unlike them it settles slices with numbers rather than reordering
them. One is refuted, one loses its performance case, one is promoted from a footnote, and one that
neither earlier pass considered is now the largest thing here. Where this section and a later one
disagree, this one is later.

All figures were taken on one 4 vCPU, 15 GB sandbox against a warm local repository, with
sequential `mvn install -Plocal-db`. Ratios transfer between machines and absolute seconds do not.

### The build is 6m51s and no module dominates it any more

Baseline on trunk: **410.1 seconds**, 610 test classes reporting. Where it goes:

| Module | Wall clock | Share | Second pass |
|---|---|---|---|
| `graphitron-sakila-example` | 93.6s | 23% | 410.6s |
| `graphitron` | 85.0s | 21% | 79.4s |
| `graphitron-model` | 68.3s | 17% | 63.9s |
| `graphitron-lsp` | 50.6s | 12% | 50.3s |
| `graphitron-maven-plugin` | 39.6s | 10% | 37.6s |
| `graphitron-mcp` | 37.2s | 9% | 32.7s |
| everything else | 31.5s | 8% | 31.8s |

The second-pass column is the point. Every module except `graphitron-sakila-example` is within a few
seconds of where it was when the build took 706 seconds; that module alone fell from 410.6s to 93.6s,
and it is what R742 bought. So the build is now flat, and an item that wants to take a big slice out
of it has to find something every module pays rather than one class that is slow.

One caveat on reading that table too closely: `graphitron-maven-plugin` measured 39.6s, 46.1s, 30.8s
and 32.0s across four runs of identical work, its integration tests forking Maven builds of their own.
Treat any single figure for it as ±8s, and do not attribute a change to it without repeating the run.

`GeneratorDeterminismTest` is 18.99s (R742's landed 18.13s, remeasured here) and
`FixtureWarningsGateTest`, which is one full-fixture generator run and nothing else, is 2.862s.

### A per-statement instrument, which is what this pass added

JFR attributes a sample to a call site; it cannot say which statement ran, how many times, or
whether one read cost 900ms or twelve reads cost 35ms each. This pass wired an env-guarded
`ExecuteListener` onto the store's own `DSLContext`, fingerprinting each statement (bind values
collapsed) and dumping count, total and max at JVM exit. The recipe is at the end of this section
and it is the durable part of this pass: every number below came from it, and none of them was
visible in a profile.

**One full-fixture generator run executes 185 statements and spends 2.55 seconds inside them.** Five
reads are 84% of that:

| Read | Time | Times executed |
|---|---|---|
| `intent_argmapping_projection_defect` | 912.3 ms | 1 |
| `intent_resolved_node_key_column` | 415.6 ms | **12** |
| `intent_resolved_node_key_projection`, two column sets | 601.0 ms | 2 |
| `intent_resolved_node_type_id` joined to `intent_resolved_type_binding` | 213.6 ms | 1 |
| the other 180 statements together | about 410 ms | 180 |

The JFR profile agrees and adds the non-store residue: over `GeneratorDeterminismTest` (20.80s, 1443
samples, `stackdepth=1024`) the store reads are 55.3% of samples, the store's own DDL boot 7.7%,
`ConnectionPromoter.rebuildAssembledForConnections` 4.2%, `FactSink.flush` 2.5%, and nothing else
reaches two percent. Garbage collection is 49 pauses and about 0.3 seconds of 21, so it is not a
lever and should not be looked at again.

### The third registration is worth more than everything else in this item combined

R742 lands the multiplicity report so that the third registration is chosen from data. It ranks views
by their own subtree size, which answers *which read is expensive*. The question a chooser actually
has is a different one: **if this relation were materialized, how many instantiations disappear from
the reads a run performs?** That is computable from the same parse, weighting each read by the
milliseconds the instrument measured for it.

The answer is `intent_resolved_type_binding`, and it is not close. It is named in all four hot reads
and removes 684 of the 1321 instantiations across them, halving every one:

| Read | Instantiations now | With it registered |
|---|---|---|
| `intent_argmapping_projection_defect` | 765 | 423 |
| `intent_resolved_node_key_projection` | 284 | 113 |
| `intent_resolved_node_key_column` | 156 | 42 |
| `intent_resolved_node_type_id` | 116 | 59 |

Registered as a throwaway instrument, with both target classes green:

| | `GeneratorDeterminismTest` | `FixtureWarningsGateTest` | Store time per run |
|---|---|---|---|
| trunk | 18.99s | 2.862s | 2.55s |
| plus this one registration | **11.61s** | **2.205s** | **0.40s** |

Per read, and this is the part that reorders the slices below: the defect read goes 912.3ms to
72.4ms, the two projection reads 601.0ms to 36.2ms, the key-column loop 415.6ms to **1.3ms**, and the
type-id read 213.6ms to 1.7ms. The registration's own refresh costs 59ms per run, which is the whole
of the new cost.

**One small thing the mechanism does per refresh, which matters more as the store gets faster.**
`Materializations` decides a target's refresh shape by asking `INFORMATION_SCHEMA.COLUMNS` whether it
carries a `graph_name` column, once per registration per refresh. The instrument prices that at about
13ms a call: 26.5ms per run at R742's two registrations, 79.9ms across five runs at three. It is 1% of
a 2.55 second run and 7% of a 0.40 second one, and it grows linearly with the registry. The answer is
a property of the schema and invariant for a store's lifetime, so it wants computing once rather than
per refresh. Small, but it belongs to whoever touches the materializer next, which is R746.

**The metric under-predicts a registration's value, badly, and the report should say so.** Linear in
instantiations removed, the projection was 1.18 seconds per run; the measurement was 2.15. Removing a
windowed view from the middle of a tree removes its subtree's re-evaluation *per instantiation*, which
a linear model cannot see. R742 argues for a generous ceiling on the grounds that the metric
over-approximates a relation's *cost*; as a predictor of a registration's *saving* it under-approximates,
and a chooser who trusts the linear reading will decline registrations worth taking.

### That registration is blocked, and R746 is the block

`intent_resolved_type_binding_live` reads `intent_bound_table`, which reads `intent_spelled_table`,
which is a registered target. `Materializations.refresh` iterates the registry in
`source_view_name` order, and `intent_resolved_type_binding_live` sorts before
`intent_spelled_table_live`, so the binding target is refilled from a spelling target that is still
empty. R746 predicted exactly this and called it the first thing a third registration would want.

What R746 did not predict is the symptom, and it is worse than the quiet wrong answer that item
expects. The build fails, confidently, blaming the schema author:

```
Field 'Mutation.rentFilmPayloadProjected': @routine argMapping entry
'pCustomerId: input.customerId.customer_id' names 'customer_id', which is not a key column of
'Customer'; 'Customer' resolves no key columns on any tier, so pin them with @node(keyColumns:)
on that type
```

Nothing is wrong with that schema. A materializer ordering bug arrives at the author as an
instruction to change their own SDL, which is the worst available failure mode for a performance
mechanism. The 11.61s above was measured by renaming the source view so it sorts last, which is a
measurement trick and not a fix.

Two consequences for R746, and both are that item's business rather than work for this one. Its
`priority: 4` looks wrong now that it gates the largest measured saving left in the store. And its
closing claim that it "is not a performance item at all" is true of the registry as it stands and
false of the registry anyone will want next. R746 entered Spec under another session while this pass
was running, so both are recorded in its body as arguments for its Spec pass to weigh rather than
applied over it; the priority field is untouched.

**The gate that should catch it cannot yet exercise it, and that is a third consequence.**
`FactSchemaGateTest.everyMaterializedTargetEqualsItsRule` is the case that holds a target against its
rule. R742's Done gate found it vacuous, both registered relations yielding zero rows in its fixture,
and R742's rework has since given it real rows, so it would now report a target populated from an
empty predecessor. What it still cannot reach is the ordering itself: its fixture carries two
registrations that do not depend on each other, which is the very property that makes ordering
unnecessary today. Closing that wants a fixture registration whose view reads another registration's
target, and this pass's failure text is the concrete case such a fixture has to fail on before R746's
sort is in place.

### The third registration has since shipped elsewhere, and this slice needs re-scoping

Added by the In Review gate on the diagnostics-drain budget-overrun item, since shipped, which
landed `intent_resolved_type_binding` as a `meta_materialize` registration at `272ef13` for the
language server's diagnostics drain, together with a second registration of
`intent_field_column_scope`. So the slice above, the one this item measured as worth more than
everything else in it combined, is on trunk. It arrived from the language-server side rather than
from the build-wall-clock side, and its `reason` row argues the drain's arithmetic rather than this
item's.

Two things follow for this item's Spec pass. The 18.99s to 11.61s on `GeneratorDeterminismTest` and
the 2.55s to 0.40s of store time per run are now trunk's baseline rather than an available win, so
the ordering of the remaining slices was computed against a store that no longer exists and wants
re-deriving from fresh numbers. And the block this item recorded is gone: the refresh order is no
longer the registry's `source_view_name` order but a topological one over
`meta_materialize_dependency`, derived at boot by
`no.sikt.graphitron.model.derive.MaterializeDependencies`, which is exactly the case that landed
here, `intent_field_column_scope_live` reading the binding's target while sorting ahead of it
alphabetically.

The per-refresh `INFORMATION_SCHEMA.COLUMNS` probe this item priced is untouched and has grown
rather than shrunk. `Materializations` still asks the catalog whether each target carries a
`graph_name` column once per registration per refresh, and the registry now holds six registrations
rather than the three that number was taken at.

### The index slice is refuted, and the premise it rested on was wrong too

Slice 3 below was promoted to first by the second pass. This pass measured it and it is worth nothing.

Start with the premise. The slice says the DDL declares "zero `CREATE INDEX`", which is true and
reads as a store with no indexes. The store has **260**: H2 creates a primary-key index for each of
the 143 keys and a constraint index for each of the 117 foreign keys that needed one. What the store
lacks is an index on a column that leads no key, which is a much smaller claim.

Then the measurement. The join predicates on the two materialized targets are uniform enough to index
exactly: `intent_spelled_table` is joined on `(graph_name, spelling)` at all six of its sites, and
`intent_argmapping_pair` on `(graph_name, type_name, field_name)` at ten and
`(graph_name, site, use_site, position)` at six. Three indexes cover every one of them. With all three
in place, `GeneratorDeterminismTest` measured 18.42s and 19.98s against a baseline of 18.99s and
20.80s, and `FixtureWarningsGateTest` 2.973s and 2.628s against 2.862s. There is no effect to find.

The reading, offered as the best available rather than as proven: after R742 the remaining cost is
per-instantiation plan overhead, not per-row comparison. The relations these joins reach are small
(`sql_table` 71 rows, `sql_column` 251 in the plugin's own store), and an index on a 71-row table
saves nothing against a plan instantiated 765 times. `Value.compareToNotNullable` staying the top JFR
leaf frame at 11.3% is consistent with this and is not evidence against it: comparisons are what a
plan does, and there are a great many cheap plans rather than a few expensive scans. The write-side
worry the second pass raised about indexes is moot, the slice having no read-side win to trade against
it.

Keep the negative result. It is the second time this item has aimed at indexes on a reading of a
profile leaf frame, and the finding is that this store's cost has not been row volume since R742.

### The batched key-column read keeps its enforcer case and loses its performance case

The violation is real and it is worse than this item describes. `StoreNodeTables.read` loops
`bindings(...)` and calls `keyColumns(...)` inside the loop, 12 times per run on this fixture, and it
is the second-largest call site in the JFR profile at 11.4%. It also calls `tableRef(...)` in the same
loop, which issues **three more reads per node type** (`sql_table` joined to `sql_schema`, then
`sql_column`, then `sql_primary_key` joined through `sql_constraint_column` to `sql_column`). Four
reads per node type, not the one this item names.

And after the registration above, the whole `intent_resolved_node_key_column` loop costs **1.3
milliseconds per run**: 60 reads across five runs, 6.7ms total, 0.2ms at the worst. The 380ms this
slice would have recovered is recovered by the registration instead, and taking both does not take it
twice.

So this stops being a performance slice and becomes purely what this item wanted it for: the worked
violation behind the "batch by key set, never loop by key" enforcer, with a method whose own javadoc
asserts what its body contradicts. Spec it on those grounds and quote 1.3ms, not 380ms.

### Every store boot compiled Java, and that one is shipped

Shipped at `452c497`, re-measured at `821890c`, split out as its own item because it was one line of
DDL and touched nothing else here. The wire spelling left storage: every `file` column and the
`diagnostic` view hold paths, the two boundaries whose protocol names a document by URI render one at
their own edge, and the `CREATE ALIAS` carrying inline Java source went with them, so no store boot
runs javac any more.

Kept here as the datum this item's flat module table asks for, not as remaining work. The statement
was 64.5ms of a 212ms boot on the sandbox that found it, seven times the next most expensive, and it
never amortised, each store paying its own compilation (four warm alias-only boots in one JVM: 85.9,
65.9, 62.9, 51.1ms). Re-measured on a faster sandbox at landing: 28.8ms of a 159.5ms boot, so 18.6ms
and 11.7% off every boot, and 387s to 349s end to end on a full green `mvn install -Plocal-db`. It
was consumer-facing as well, every `graphitron:generate`, language-server session and MCP server
start paying it once.

### Extending test parallelism is now a slice rather than a footnote

This item carries the extension as "smaller than a slice" at the end of the unmeasured list. It is
measured now, and it is one module rather than three.

**The first reading of this was wrong and is worth keeping as the method lesson.** Giving
`graphitron-model`, `graphitron-lsp` and `graphitron-mcp` the same `junit-platform.properties`
`graphitron` already has took two full builds from 367.4s to 310.8s and 317.7s, both green, and the
three modules' spans appeared to account for 34.6s of it. Those spans are not a usable instrument on a
4 vCPU sandbox. Over five runs of identical work `graphitron-maven-plugin` spanned 30.8s to 46.1s and
`graphitron-model` 37.7s to 113.9s, so an 11-second "win" inside one pair of runs is noise wearing a
number.

A clean per-module A/B, `mvn test -pl :<module>` with the arms alternated and repeated, says:

| Module | With parallelism | Without | Verdict |
|---|---|---|---|
| `graphitron-model` | 45.0, 45.3, 45.8s | 66.0, 66.7, 66.7s | **−21s, landed** |
| `graphitron-lsp` | 30.6s | 30.8s | nothing, and wrong; see below |
| `graphitron-mcp` | 24.5s | 24.9s | nothing, and wrong; see below |

`graphitron-model` is worth more alone than the three were credited with together, and that fits what
the module is: its test cost is almost entirely per-case store boots, 152 of them, each an independent
in-memory database, which is the shape concurrency helps. That row stands.

**The other two rows are an artifact and the real figures are large.** Once `graphitron-model`'s
properties file landed it began riding along in that module's test-jar, which `graphitron-lsp` and
`graphitron-mcp` both consume at test scope, so both arms of their A/B ran parallel and the test
compared parallel against parallel. Re-measured with the entry stripped from the installed test-jar
and the arms alternated: `graphitron-lsp` 33.4/33.0s against 50.1/50.8s, **−17.5s**, and
`graphitron-mcp` 26.9/28.1s against 35.0s, **−7.5s**. So test parallelism is worth about 25 seconds in
those two modules after all, it is already in effect, and it arrives through a channel neither
module's pom mentions. R764 carries the mechanism and what to do about it.

Two traps to know before re-running this, both of which produce the same false "nothing".
Removing a `junit-platform.properties` from `src/test/resources` does not remove the copy Maven
already put in `target/test-classes`. And for any module that depends on another module's test-jar,
the file can arrive from that jar instead, in which case a `-pl` A/B varies nothing at all. Check the
installed test-jars of the upstream modules, not just the module under test.

The remaining caution stands unchanged: green runs are not thread safety. `graphitron`'s own properties
file documents the process-global hazard and carries `@Isolated` on the two classes that trip it, and
nothing measured here establishes that `graphitron-model` has no such state; what is established is
that every case there owns its own store.

### How to re-measure, per statement

The recipes at the end of this item still apply. This one is new and is what produced every number
above.

Add an env-guarded `ExecuteListener` where the store builds its `DSLContext`
(`GraphitronModelStore`'s constructor), recording each statement's wall clock against a fingerprint
of its SQL with string literals collapsed, and dump the ranking from a shutdown hook. Two details are
load-bearing. Dump to a file named by PID rather than to stderr: a Maven build has at least two JVMs
executing store statements, the build's own and each surefire fork, and a fork's stderr after the test
run is lost. And read the two dumps as different subjects, because they are: the Maven JVM's covers
the module's `graphitron:generate` executions, which is the **write** side, and the fork's covers the
tests, which is the **read** side. Conflating them is how the census below looks like a read cost.

### The write side is the classpath census, and R685 owns it

Same instrument on the Maven JVM, over `graphitron-sakila-example`'s five `graphitron:generate`
executions: 1346 statements and 13.3 seconds inside them, of which the `jvm_` family's deletes and
merges are about 8.1 seconds, **62%**. On a second run under heavier load the same set was 12.6 of
15.5 seconds, 81%. The single most expensive statement outside the census is one derivation insert
into `intent_type_backing_class`, at 1.87 seconds across six executions.

R685 already owns this and should not be re-specced. What it does not carry is this half of the
number: R685 measures the *scan* (516ms of the 648ms each classpath scan costs) and the row count (87%
of everything the store holds), and the store-*write* cost of those same rows is the 62% above. Worth
adding there as evidence. One more figure for the same item: the persisted store the plugin leaves
behind is **845 MB** for 418,192 rows, of which about 400,000 are the census.

### A fourth pass dug into the census read side and found no win there

Recorded because it is a negative result and the next person should not pay for it twice. The
question was whether the census, which is written enumeratively and mostly serves the editor, is also
read enumeratively on the build path, which would have made a read-side cut available on top of
R685's width cut. It is not. Fourteen views depend transitively on the `jvm_` family, and every one
the generator reads (`intent_field_producer_method`, `intent_type_backing_seed`,
`intent_resolved_node_key_projection`, `intent_field_accessor_hop`,
`intent_argmapping_projection_defect`) reaches the census through a join on a `graphitron_` or
`graphql_` relation, so it is already anchored to the names the schema wrote. The enumerative readers
are the editor's and the MCP server's. There is no build-path read to narrow, and the write cost
above stays the whole of the census's contribution to build wall clock. The row-share figures the dig
produced went to R685, whose case they strengthen.

The dig did turn up one thing, and it is a correctness hazard rather than a win: the census's
transitive closure view `intent_class_assignable` does not return on a real census, filed as R760
with a validated rewrite. It costs no wall clock today precisely because nothing reads it, so it is
not a slice of this item; it is on this list only so that a later pass measuring the census does not
rediscover it as a mystery hang.

Pushing the same question one level further did find a write-side lever, filed as R762, and it is
larger than anything left on this list. Anchored is not the same as shallow: the reads being anchored
to a written class name is exactly what makes the *depth* of the census optional. Two of the nine
`jvm_` relations carry the enumerative load at 11,851 rows, the other seven are 405,374 rows and
97% of the census, and every read of those seven pins a class name that is already in the document.
One class's members resolve from the classfile in about 0.1 ms, so the depth is a storage choice.
Against the 8.1 seconds of `jvm_` writes above, and on a measured row-to-time proportionality, that
is where the census's contribution to build wall clock actually sits. It belongs to R762 and R685
rather than here, and it wants an end-to-end measurement after the change rather than a projection
from the proportionality.

### What this pass leaves for whoever specs the item

The first two are the cheapest things on this list by a wide margin, neither needs the third and
neither needs this item's guardrail. The second has since landed directly against trunk, for one
module rather than the three first credited, at a reproducible 21 seconds; the first is filed as its
own item at 42.7 seconds and is what a Spec pass should take first. Quote those two figures
separately rather than adding them: they were measured with different instruments, on trunks twenty
commits apart, and the combined 96-second figure an earlier draft of this section carried came from
whole-build spans that the parallelism entry below shows are too noisy on this hardware to carry a
sum.

In the order the numbers argue for, and every one of them is now measured rather than bounded.

0. **The store-boot *count*, which is larger than everything else on this list put together and was
   not considered by any of the three passes.** A fourth measurement, taken after the alias below had
   landed, counted the boots rather than pricing one: a full `mvn install -Plocal-db` executes the
   1894-statement schema **1051 times** and spends **395.8 seconds** in it, which is 44% of the
   test-class time of the four store-heavy modules and about **80 seconds of a 339-second build**.
   Emptying every clearable table costs 0.85 ms and a reset including re-materialization 9.3 ms,
   against a 138 ms boot, so the boots are almost all replaceable. R768 carries it. Its priority over
   the entries below is not close, and the instrument is six lines, so a Spec pass on this item should
   re-run it rather than trust the projection.
1. **The store-boot alias.** 42.7s, one line, green, its own item, and a design improvement
   independently of speed. Cuts the price of a boot; R768 above cuts their number, and the two
   compose, with the alias already banked in R768's figures.
2. ~~**Test parallelism in the three modules.**~~ **Landed directly against trunk for
   `graphitron-model` only**, on the user's explicit call that a change consisting only of a
   `junit-platform.properties` file did not warrant a pipeline cycle. **The three-module figure this
   pass first reported was wrong, and the correction matters more than the win.** A clean per-module
   A/B, alternating arms and deleting the stale `target/test-classes` copy each time, gives
   `graphitron-model` 45s against 66s over three rounds, and gives `graphitron-lsp` (30.6 vs 30.8)
   and `graphitron-mcp` (24.5 vs 24.9) nothing at all. Only `graphitron-model` carries the file.

   How the first figure went wrong is worth recording, because the same trap is waiting for the next
   person. It was read off whole-build module spans, and those spans are not a usable instrument on a
   4 vCPU sandbox: over five runs of identical work `graphitron-maven-plugin` spanned 30.8s to 46.1s
   and `graphitron-model` 37.7s to 113.9s, so an 11s "win" in a single pair of runs is inside the
   noise. Attribute a test-configuration change with `mvn test -pl :<module>`, alternating, repeated,
   never with a module span diff. The second trap is Maven's: removing a resource file does not remove
   the copy already in `target/test-classes`, so the arm that is supposed to be sequential runs
   parallel and the difference disappears.

   One refinement against what was measured, kept because it is strictly less change: only the four
   parallelism keys are set, not the `extensions.autodetection.enabled` that `graphitron`'s file also
   carries, since only `graphitron` registers an extension under `META-INF/services` and enabling it
   elsewhere would change nothing today and quietly activate whatever is added later.
3. **The third registration, `intent_resolved_type_binding`.** 7.4s off one class and 84% off the
   store's per-run read cost, gated on R746.
4. **R746 itself**, whose priority and self-description both need correcting.
5. Slices 1, 2 and 4 below, unchanged and each about 2% of a build that is now 40% smaller than when
   they were bounded, so re-bound them before spending a Spec cycle on them.
6. **Slice 3 is refuted**, and slice 5's successor work is done in R742. The batched key-column read
   is an enforcer question and not a performance one.

## A second measurement pass has run, and it reordered this item once before

Everything in the slice table further down was written before R732 landed. A fresh pass over the
post-R732 reactor moved the cost somewhere else entirely, so read the slice table as the record of
what the first pass expected and this section as the second. **The third pass above supersedes both
wherever they disagree**, and in particular this section's promotion of the index slice to first
place, which the third pass measured and refuted.

All figures below were taken on one 4 vCPU, 15 GB sandbox against a warm local repository, with
sequential `mvn install -Plocal-db` unless stated otherwise. R732's numbers came from a machine
where that same build ran 9m06s; here the same command on trunk runs 11m47s, so the ratios
transfer between machines and the absolute seconds do not.

### The cost left `graphitron` and went to the generator itself

Trunk baseline: 11m47s, 5919 tests in 604 classes. Where it goes, by module wall clock:

| Module | Wall clock | Share |
|---|---|---|
| `graphitron-sakila-example` | 410.6s | 58% |
| `graphitron` | 79.4s | 11% |
| `graphitron-model` | 63.9s | 9% |
| `graphitron-lsp` | 50.3s | 7% |
| `graphitron-maven-plugin` | 37.6s | 5% |
| `graphitron-mcp` | 32.7s | 5% |
| everything else | 31.8s | 5% |

R732 did its work: `graphitron` is 79.4s against the 181.2s it started from, and
`ColumnMatchShadowTest` is 8.96s against the 74 to 120s that motivated the item. The three slices
below that target `graphitron` and `graphitron-mcp` are now aimed at 16% of the build between them.

`graphitron-sakila-example` is where the build now is, and inside it two classes are almost all of
it: `GeneratorDeterminismTest` at **240.5s across 2 test methods**, and `FixtureWarningsGateTest` at
**56.8s across 1**. Neither is slow because it is a slow test. Both run
`GraphQLRewriteGenerator` over the full fixture schema, four times and once respectively, and one
such run costs about 57 seconds. The build pays for six of them. So the subject is not the test
tier at all: it is the generator's own hot path, which means every consumer pays it on every build
of theirs, and the build is merely the place we can see it.

### What one generator run spends its time on

JFR over one run (`FixtureWarningsGateTest` in isolation, 60.49s, 5470 samples, `stackdepth=1024`
because the default 64 truncates every stack below the H2 frames and hides the caller):

**97.4% of samples are inside H2 evaluating a query.** Attributed to the deepest `no.sikt` frame,
five call sites are 95.7% of the run:

| Call site | Share | What it reads |
|---|---|---|
| `ArgmappingProjectionDefects.authorDefects` | 31.2% | `intent_argmapping_projection_defect`, once |
| `StoreNodeTables.keyColumns` | 22.3% | `intent_resolved_node_key_column`, **once per node type** |
| `ResolvedKeyProjections.read` | 15.8% | `intent_resolved_node_key_projection`, once |
| `ArgmappingProjectionDefects.unemittableProjections` | 15.5% | the same view again, joined |
| `StoreNodeTables.bindings` | 10.9% | `intent_resolved_node_type_id` joined to `intent_resolved_type_binding` |

`EXPLAIN ANALYZE` on the first of those, inside the real populated store: **one query scans about
2.57 million rows** to return a handful of defect rows, and `intent_spelled_table` is expanded at
**469 separate plan nodes** within it.

### Two changes, measured end to end

Both were applied as throwaway instruments to get numbers, not as proposed final code. A full
`mvn install -Plocal-db` was green on both, all 5919 tests, and reproduced on a second run.

| | Full-fixture generator run | Whole build (log span) | `graphitron-sakila-example` |
|---|---|---|---|
| trunk | 60.49s | 706.3s | 410.6s |
| batch the key-column read | 46.90s | 584.2s | 316.2s |
| and one index on `sql_table` | 23.49s | 515.6s / 513.6s | 232.5s |

So about 122 seconds for the batched read and a further 69 for the index, 191 seconds of a 706
second build between them, and `GeneratorDeterminismTest` goes 240.5s to 86.2s while
`FixtureWarningsGateTest` goes 56.8s to 20.6s. The index's end-to-end share is smaller than its
read-side share for the reason the write-side paragraph below gives.

**The batched read.** `StoreNodeTables.read` loops `for (var binding : bindings(...))` and calls
`keyColumns(...)` inside the loop, so it reads `intent_resolved_node_key_column` once per node type,
31 times over the fixture schema. That view is a `DENSE_RANK() OVER (PARTITION BY graph_name,
type_name)` over a three-arm union, and a window sees its whole partition whatever predicate the
reader applies outside it, so each of the 31 reads paid the entire view. This is the same defect
R732 fixed in `ColumnMatchShadowTest`, found this time in the generator rather than in a test. The
change is one query grouped with `fetchGroups`, 19 lines across the method and its two helpers.

Worth naming precisely, because it is the argument for an enforcer rather than a fix: the method's
own javadoc already says it "reads the whole population rather than a requested subset ... the query
is one pass per relation either way". The prose was right and the code did not match it, and
nothing checked.

**The index.** The fact store's DDL declares 140 tables, 56 views, 161 primary keys and zero
`CREATE INDEX`. The spelling-resolution join in `intent_spelled_table` matches
`sql_table.table_name_upper`, a generated column with nothing behind it, so it scans `sql_table`
every time, and that view is expanded hundreds of times per query. A single
`CREATE INDEX ... ON sql_table (source_name, table_name_upper)` takes the generator run from 46.90s
to 23.49s.

**The index is not free, and this is the part the first pass did not anticipate.** Capture writes
the whole catalog, so an index is maintained on every insert. Timing the five
`graphitron:generate` executions alone (`mvn generate-sources -pl :graphitron-sakila-example
-Plocal-db`): 1m06.7s without the index, 1m31.0s with it, so about +24s on the write side in that
module, and `graphitron-maven-plugin` moves 37.6s to 45.9s for the same reason. The net across the
build is strongly positive because five of the six full-fixture generator runs happen in tests,
which read and do not write. But it means each candidate index has to be measured on both sides,
and a broad "index the hot columns" sweep is not the shape of the work.

### What this does to the slice ordering

1. **Index the fact store's hot join columns.** Slice 3 below, promoted from last-but-one and
   "unmeasured, measure first" to first. The caveat it carried, that H2 may decline an index under
   an `OR`, is about `intent_column_match_claim`'s predicate and does not apply to the equality on
   `sql_table.table_name_upper` that actually dominates. The write-side cost above is the new
   constraint on it.
2. **Batch the key-column read, and give the rule an enforcer.** This is the first of the three
   unenforced rules below, with a worked violation to point at.
3. **Reduce the high-multiplicity relations. Moved to R742, which owns it now.** Slice 5 below was
   the reduction slice and has become a pointer: the subject turned out to be the whole `intent_`
   stratum rather than one relation, with a measured 24.5s to 0.72s on the hottest read, so it wants
   the spec cycle it now has in R742 rather than a bullet here. What stays this item's business is
   the enforcer question, since the statically computable multiplicity metric R742 proposes is the
   missing enforcer for the derived-read rules named below.
4. Slices 1, 2 and 4 stay on the list and drop below those three. Their bounds hold (slice 4's
   `GraphitronMcpServerTest` measured 15.85s here against the 15.5s claimed) but each is about 2%
   of the build, where the three above are 27% together and not yet exhausted.
5. Slice 6 was not measured this pass and keeps its place.

## Why a guardrail is the spine of this item

Trunk CI went from a 5.2 minute median to a 15.3 minute median over seven weeks while the test-method
count rose 21 percent. Cost outran volume, and nothing measured it. R732 buys the time back once; the
reason the curve was allowed to triple is that no build artifact ever failed or flagged when the shape
regressed, so a repeat is a matter of time rather than of vigilance.

The failure mode is specific and it is what a guardrail has to catch. It was never a thousand slow
tests: it was one class, `ColumnMatchShadowTest`, at 74 to 120 seconds inside a `graphitron` suite
whose median class costs a fraction of a second. A per-class ceiling would have caught it on the
commit that introduced it. A total-suite budget would have absorbed it for weeks.

**The second measurement pass makes this argument concrete rather than hypothetical, and widens its
scope.** The same shape was sitting in the reactor the whole time, in a module R732 never looked at:
`GeneratorDeterminismTest` at 240.5 seconds across two test methods, in a reactor whose median class
costs a fraction of a second, which is 34% of the entire build in one class. Any per-class ceiling in
the 10 to 30 second range would have flagged it. Two consequences for the guardrail's design. The
ceiling has to be reactor-wide rather than scoped to `graphitron`, because the recurrence was not in
`graphitron`. And it needs a way for a genuinely long cross-cutting test to carry an explicit,
committed exemption, because raising the ceiling to accommodate one class is how a ceiling stops
working: `GeneratorDeterminismTest` at 86.2 seconds after the two changes above is still an order of
magnitude over any healthy class, and it is doing four full generator runs on purpose.

## The guardrail decision, and the argument that settles it

Three candidate shapes. The Spec pass has to pick one and keep the other two as rationale.

. **A per-class ceiling read from the Surefire reports.** Surefire already writes
  `target/surefire-reports/*.txt` with a `Time elapsed` per class on every build, and `roadmap-tool`
  already reads per-module build artifacts through a `**/target/leaf-coverage.jsonl` glob, so the
  precedent for a sibling step that reads build output is in place.
. **A total-suite budget.** Simpler to state, but noisier, and it drifts with hardware.
. **Recording the trend in CI without gating.** Documents the curve; does not stop it.

The objection that sinks option 2, hardware drift, looks like it should sink option 1 too, and it does
not. That is the argument the Spec pass should make explicitly, because without it the choice reads as
a coin flip. The signal here is two orders of magnitude, not a few percent: the same class measured
74.0s on one 4 vCPU sandbox and 120.4s on another while the module's median class stayed far under a
second on both, so any absolute ceiling in the 10 to 30 second range separates the pathology from
every healthy class on either machine. A per-class ceiling is *more* hardware-tolerant than a suite
total, not less.

Whichever shape is chosen, the budget belongs in the repository next to the tests it governs, and
raising it should be a visible commit rather than a silent drift.

One measured fact sharpens option 2 rather than reviving it. Under the `-T 1C` CI uses, the build's
wall clock and its critical path are currently the *same number*: mvnd's own scheduler puts the
critical path at 340s against a 340s wall clock, and forcing the build sequential gives 339s, so
parallelism is worth one second and the path has no slack anywhere. A total-suite budget on a 4 vCPU
runner therefore cannot distinguish "the build got slower" from "the build got wider", and a change
that traded 20s of critical path for 40s of off-path work would read as a regression. R763 carries the
measurement and the two modules where the idle cores actually are.

## What the chosen host has to answer

Assuming the Spec pass lands on the Surefire reader, three mechanics are load-bearing and none of them
is obvious.

**Ordering, which is the one that can make the gate lie.** `roadmap-tool` declares a dependency only
on `graphitron-model`, so under the `-T 1C` that CI uses, Maven is free to schedule its `verify`
alongside or ahead of the modules whose reports it would read. `mvn test` never reaches `verify` at
all. And a `-pl`-scoped inner-loop build leaves reports behind that a later full build's reader would
happily treat as current. A reader that silently passes on absent or stale reports is worse than no
gate, because it reports safety it is not providing. Either fail closed (reports must postdate the
build's start, else fail loudly), or host the check in the module whose tests it governs instead of in
`roadmap-tool`.

**Its own test.** All nine existing `roadmap-tool` checks and reports have a paired `*Test`
(`AdocMarkdownTableCheck`, `AdocXrefAnchorCheck`, `CoverageAgentWiringCheck`, `DirectiveSupportReport`,
`LeafCoverageReport`, `ModuleEnumerationCheck`, `SchemaIdentifierDriftCheck`, `SourceCoverageReport`,
`TransientCitationCheck`). A new check inherits that convention.

**Where the rule is written down.** Every build gate in this repo has a prose home: the javadoc
reference gate has a paragraph in `CLAUDE.md`, and the `roadmap-tool` steps are described there and
under `docs/architecture/`. A new gate needs its paragraph in the same commit, or contributors meet it
first as a build failure.

## The unmeasured slices

R732 measured its three. These were listed as tempting and left unmeasured, and the numbers below are
the honest bounds established during R732's Spec review rather than the optimistic ones its first
draft carried. Measure before ordering: two of them may have no motivation left once R732's slice 1
lands.

| # | Slice | Bound | Confidence | Second pass |
|---|---|---|---|---|
| 1 | Memoise `ClassifiedHarness.classify` | bounded by ~17s | high | unchanged, now ~2% of the build |
| 2 | Share one captured-corpus store | bounded by ~9s after R732 | medium | unchanged, now ~1% of the build |
| 3 | Index the hot non-key join columns | unmeasured | measure first | promoted to first, then **refuted by the third pass** |
| 4 | Share the Jetty server in `graphitron-mcp` | up to 15.5s | high | bound confirmed at 15.85s, ~2% of the build |
| 5 | Reduce a derived relation at write time | unmeasured, reader-facing | architectural | **subject named, and it is build time after all** |
| 6 | Decide whether PR builds need `-Pcoverage` | unmeasured | measure first | still unmeasured |

**1. Memoise the corpus classification.** Five sweep classes (`ClassifiedDslTest`,
`DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`,
`WrapperAlgebraTest`) plus the two shared readers they go through, `ExemptionRegistry` and
`CorpusDocuments.coveredLeaves`, call `ClassifiedHarness.classify(document.sdl())` per document with no
memoisation, several from more than one test method. The precedent is in the same class:
`launcherProductions()` is memoised once per JVM and its javadoc says why. A static map keyed on the
fixture SDL is nearly the whole change, with one caveat. `ClassifiedHarness.Result` is a record but
not deeply immutable, since `classify` hands its four bare `ArrayList`s straight into the constructor.
No caller mutates them today, so nothing is broken now, but a shared memo plus R732's class-level
parallelism turns that into a cross-test flake of exactly the kind that slice already tripped over.
Wrap the four lists at construction.

The bound is the eleven non-`ColumnMatchShadowTest` corpus sweeps' 17.1s, not the 96.1s an early draft
quoted: the class that dominated that figure calls `TestSchemaHelper.buildSchema` directly and never
touches `classify`, so this slice stands on removing repetition and holding the shape rather than on a
large recovery. Harvest check: total corpus classification passes per build drop from hundreds to 55.

**2. Share one captured-corpus store.** The capture-side sweeps each build their own store over the
whole corpus. One shared fixture, captured once per JVM, removes on the capture side the repetition
slice 1 removes on the classification side. Larger than slice 1 because store lifetime becomes shared
state across classes, so it wants R732's parallelism model settled first and the isolation
requirements known. Bound the expectation before starting: once R732 has taken `ColumnMatchShadowTest`
down, the remaining capture-side sweeps are `InputOccurrenceShadowTest` at 4.9s and
`DemandShadowTest` at 4.1s, and that is the whole pool.

**3. Index the hot non-key join columns.** The fact-store DDL declares 140 tables, 56 views, 161
primary keys and **zero** `CREATE INDEX`. The hot join inside `intent_column_match_claim` matches
`sql_column` on `jooq_name_upper` OR `column_name_upper`, neither of which leads a key, so it scans
the catalog per candidate field; `Value.compareToNotNullable` was 27 percent of the JFR samples on the
profiled class. Measure first and keep the result either way: H2 may decline to use an index under an
OR, in which case the finding is that the predicate wants restructuring rather than an index.

*Measured on the second pass, and the result is larger than anything else in this table.* The
predicate that dominates is not the `OR` this paragraph anticipated; it is the plain equality on
`sql_table.table_name_upper` inside `intent_spelled_table`, which resolves an authored table
spelling against the catalog and is expanded at 469 plan nodes inside a single generator query. One
index takes a full-fixture generator run from 46.90s to 23.49s. The `OR` caveat above stands
unrefuted for `intent_column_match_claim` specifically, and is simply not where the time was. What
does need carrying into the Spec pass, and what this paragraph did not anticipate, is the write
side: capture inserts the whole catalog, so the same index costs about 24 seconds across
`graphitron-sakila-example`'s five `graphitron:generate` executions. Each candidate index is a
separate two-sided measurement, and `Value.compareToNotNullable` remaining the top JFR leaf frame is
a symptom shared by every unindexed comparison rather than a pointer to one.

*Refuted on the third pass, and the premise with it.* R742 materialized `intent_spelled_table`, so
the 469-node expansion this paragraph priced no longer happens and the index that fixed it has
nothing left to fix. Three indexes covering every join predicate on both materialized targets
changed no measurable time. The premise that the store declares "zero `CREATE INDEX`" is also
misleading as stated: H2 auto-creates 143 primary-key indexes and 117 foreign-key constraint
indexes, so the store has 260 and what it lacks is an index on a column leading no key. The third
pass section at the top of this item carries the numbers.

**4. Share the Jetty server in `graphitron-mcp`.** `GraphitronMcpServerTest` costs 15.5s across 60
test methods, 0.26s per test, the highest per-test cost in the reactor. Read that as a class average
rather than as 60 boots: the class constructs `new GraphitronMcpServer(...)` at 19 sites, while the
remaining tests call statics such as `GraphitronMcpServer.statusResult` and boot nothing. So the slice
is to share one server across the methods that need one, and a few of the 19 have the server's
lifecycle as their subject (port-in-use, close semantics) and must keep their own.

**5. Reduce one derived relation at write time.** The producer-side change described below, on one
relation, with a before and after number. Deliberately last among the code slices, and the honest
justification is reader latency in the dev loop, the LSP and the MCP server rather than build time.
Populate from the view so the rule stays in one place, and write the population order explicitly
rather than deriving it, since H2 offers no dependency catalog to derive it from. Read
`docs/architecture/explanation/fact-model.adoc` first: R732 lands the ruling there on what a reduction
may be built out of on H2, which is an ordinary table or a `LOCAL TEMPORARY` one and never a
materialized view, along with the trap that H2's bare `CREATE TEMPORARY TABLE` defaults to `GLOBAL`
and its global temporary tables share rows across every attached session. This slice is that ruling's
first consumer, so if the page does not yet carry it, R732 did not finish.

*Measured on the second pass, then moved out of this item.* The subject turned out to be an
architectural property of the whole `intent_` stratum rather than one relation's tuning: H2 inlines
every view reference with no common-subexpression elimination, so one read of
`intent_argmapping_projection_defect` expands to 2149 relation instantiations and takes 24.5
seconds. Reducing two relations takes it to 0.72s. R742 carries that work, with the measurements,
the statically computable selection rule proposed as this item's missing derived-read enforcer, and
what the migration costs. This slice stays here as the pointer; do not spec it twice.

One correction worth keeping even so, since the paragraph above states the opposite: it says the
honest justification is reader latency in the dev loop rather than build time. On the measured
numbers it is build time, and specifically *consumer* build time, the generator being what reads
these relations. The dev-loop, LSP and MCP latency case still holds and is now the smaller half.

**Also carried across, smaller than a slice.** R732 turns on class-level test parallelism in
`graphitron` only, because that is the module the 170.5s to 117.1s number was taken in. Extending the
same `junit-platform.properties` settings to `graphitron-lsp`, `graphitron-mcp` and `graphitron-model`
is unmeasured, and each module has its own shared-state question to answer first, so take them one at
a time and only after R732's parallelism model has settled in the module it was measured in.

*Done, and no longer this item's business. It landed directly against trunk for `graphitron-model`
alone, at a reproducible 21 seconds, on the user's explicit call that a change consisting only of a
`junit-platform.properties` file did not warrant a pipeline cycle. The one-module-at-a-time
instruction above turned out to be the load-bearing part of this paragraph: measured per module, two
of the three were worth nothing, and a single three-module figure would have shipped two files that
buy no time and one contention risk that buys none either.*

**6. Decide whether PR builds need `-Pcoverage`.** CI attaches the JaCoCo agent to every run
including PRs (`mvn install -Plocal-db -Pcoverage --batch-mode -T 1C`). The stated reason, keeping the
wiring continuously exercised so an `argLine` regression fails on the PR that introduced it, is sound
and should not be discarded casually; `CoverageAgentWiringCheck` already guards part of it. But the
cost has never been measured. One A/B run settles it; if it is material, exercising the wiring on
trunk pushes only is a defensible trade.

## Derived data has a producer side, and three of its rules have no enforcer

We derive on read: capture writes plain facts, and every classification, reduction and scope
resolution is a view evaluated when someone selects from it. That is why the schema is
self-documenting and why a rule lives in exactly one place. What is missing is any notion of *when* a
derivation is paid for. Deriving on read is right for a relation read once per pass and wrong for one
read once per graph in a loop.

Under this repo's own "every invariant has an enforcer" axiom, three rules here are prose with nothing
behind them, and turning them into enforcers is the most principled work in this item.

* Batch by key set, never loop by key. A caller that needs a derived relation for N partitions issues
  one query and groups in the caller. This is the same discipline as the DataLoader batching the
  generated code already does, applied to our own reads. **This rule has a measured violation in the
  tree**, and it is the strongest argument in this item for turning the three into enforcers:
  `StoreNodeTables.read` loops a window view once per node type, its own javadoc says it does not,
  and correcting it took 13.6 seconds off every full-fixture generator run. A rule that a method's
  own documentation asserts and its body contradicts is not a rule anyone is going to catch by
  reading. *Third pass: the violation is four reads per node type rather than one, and after R742
  plus the third registration it is worth 1.3 milliseconds per run rather than 13.6 seconds. The
  enforcer argument is unaffected and is now the whole of the argument.*
* Put the derivation first in the FROM clause. `intent_column_match_claim`'s DDL comment already
  states this, and `intent_field_column_scope`'s comment restates it, and nothing checks either.
* A derived relation that will be read per partition is a candidate for reduction, not a candidate for
  a cleverer query.

An enforcer for the second is plausible mechanically, since the DDL is parseable and the rule is
structural. The first and third are about call sites rather than about the schema, so their enforcer is
likelier a review rule with a named test than a build gate. Decide per rule at Spec time; a rule
declared enforceable and then left as prose is the drift smell the axiom names.

## The consumer side, for whoever picks this up

The same pathology exists one level out, in generated code: a resolver reading a derived relation once
per parent row is the per-outer-row cost again, and PostgreSQL's planner is much stronger than H2's but
the N+1 shape does not care. Graphitron already defends this with DataLoader batching, and the
execution tier already has the instruments to prove it, the `QUERY_COUNT` and `SQL_LOG` recording
idioms in `graphitron-sakila-example`'s execution tests. So the consumer-facing work is coverage, not
new machinery: assert round-trip counts on the paths that read derived relations, so a generator change
that turns a batched read into a per-row read fails here rather than in a consumer's production
database.

Worth knowing for anyone extending this to consumer schemas: PostgreSQL has what H2 lacks, `pg_matviews`
naming materialized views with `ispopulated` and `definition`, and `pg_depend` joined to `pg_rewrite`
yielding the dependency graph uniformly for views and materialized views. A consumer-side story could
use them. The fact store cannot, for reasons that live in
`docs/architecture/explanation/fact-model.adoc`: R732's fourth deliverable moves the H2
materialized-view ruling there precisely so this slice has something permanent to read, since R732's
own file is deleted when it reaches Done. Read that page before designing the reduction; it also
carries what a reduction may be built out of, which is not a materialized view.

## How to re-measure

The recipes R732 carries apply unchanged, and any number above can be checked or refuted with them.
Two standing caveats: always pass `-Plocal-db` or the jOOQ catalog jar is silently emptied and the
failures will be unrelated cascades, and measure against a warm local repository or artifact downloads
will dominate. The three that matter here:

```bash
# Per-class times, from a timestamped log; no extension needed.
mvn install -Plocal-db \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS | tee build.log

# Hot-path attribution inside one suspect class.
mvn test -pl :graphitron -Plocal-db -Dleaf-coverage.skip \
  -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="-XX:FlightRecorderOptions=stackdepth=1024 -XX:StartFlightRecording=filename=hot.jfr,settings=profile,dumponexit=true"
jfr print --events jdk.ExecutionSample --stack-depth 2000 hot.jfr

# Wall clock, both ways; they answer different questions.
time mvn install -Plocal-db
time mvn install -Plocal-db -T 1C
```

Per-class times come from the `Tests run: ... Time elapsed: ... -- in <class>` lines, attributed to
modules by tracking the preceding `Building no.sikt:<module>` line.

Three corrections to these recipes, learned by running them on the second pass. **`stackdepth=1024`
is load-bearing and is why the flag above changed.** JFR's default depth is 64 frames, the H2 view
stack is deeper than that on its own, and every sample truncates below the H2 frames, so the profile
attributes 97% of the run to `org.h2` with no caller and looks like a dead end. It is not a dead
end; it is a truncated stack. **Do not read per-goal timings off the log by measuring the gap to the
next goal line.** Untimed work between goals lands in the preceding goal's bucket, which is how the
generate goal appeared to grow by 18 seconds when the change under test only removed reads. Time a
phase directly instead (`time mvn generate-sources -pl :graphitron-sakila-example -Plocal-db`).
**For "which query, and why", `EXPLAIN ANALYZE` beats the profiler**, because a JFR frame names the
call site and not the plan. Run it against the real populated store rather than a seeded one, by
temporarily printing `dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))` beside the
read under test behind an environment-variable guard; H2's `scanCount` per plan node is what turns
"this query is slow" into "this view is expanded 469 times".
