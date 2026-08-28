# Derived read cost in the fact store: what was measured, and which premise it breaks

Audit taken 2026-08-27 and 2026-08-28 against a capture of the Sikt consumer schema, about 2,300
types and 8,400 fields. It is filed as an audit rather than inside an item because nine roadmap items
are dissolved against it and an item's file dies at Done; the evidence has to survive that. The
successor item states the plan and points here for the numbers.

## Provenance, so every figure can be placed

One store. A capture of that schema taken 2026-08-27 under `mvn -X` with the store directory pinned,
on a DDL carrying twenty registrations, predating both the cold-refresh split and the two payload
registrations that landed 2026-08-28. The store file is kept, 99 MB, SHA-256 recorded alongside it.
Everything below is either a reading off that capture's own log, a reading taken afterwards against
that store, or a static walk over the shipped DDL and the main sources. Each section says which.

**Section 1's absolute figures describe a DDL the tree has since left, and section 10 re-prices the
same store on the shipping one.** The pass that section 1 reports is 15477.1 seconds; the same store
rebuilt on the DDL of 2026-08-28 refreshes in 43.0 seconds. Read section 1 as the record of what the
defect cost, and section 10 for what is left. What survives the re-pricing, and what does not, is
stated there rather than left for a reader to work out.

Two standing cautions apply to every number here and are stated once. Readings taken after the
capture were taken with `SET OPTIMIZE_REUSE_RESULTS FALSE`, on a fresh connection per statement,
because H2 caches plans per connection and will otherwise hand back a byte-identical plan for a
statement that plans differently. And the store predates two fixes, so its absolute costs describe a
regime the tree has partly left; what carries forward is the attribution, not the wall clock.

## 1. Where the refresh time goes

Readings from the capture's own `RefreshProgress` output. The pass cost 15477.1 seconds, four hours
and nineteen minutes.

[cols="1,5,2,2,2"]
|===
| # | registration | refresh | rows | reachable

| 1 | `intent_argmapping_pair` | 11 ms | 108 | yes
| 2 | `intent_errors_field` | 6.4 s | 149 | yes
| 3 | `intent_spelled_table` | 46 ms | 313 | yes
| 4 | `intent_field_reference_step_hop` | 1.4 s | 12817 | yes
| 5 | `intent_resolved_type_binding` | 89 ms | 635 | yes
| 6 | `intent_carrier_data_field` | 50.9 s | 151 | yes
| 7 | `intent_field_column_scope` | 3.9 s | 3737 | yes
| 8 | `intent_field_scope_table` | 30.4 s | 2618 | yes
| 9 | `intent_argument_scope_table` | 4 ms | 967 | yes
| 10 | `intent_argument_column_scope` | 42 ms | 967 | no
| 11 | `intent_argument_column_match` | 176 ms | 114 | no
| 12 | `intent_input_field_resolving_table` | 20 ms | 1804 | no
| 13 | `intent_mutation_write_payload` | 4 ms | 86 | no
| 14 | `intent_node_id_instruction` | 624.5 s | 629 | yes
| 15 | `intent_input_field_filter_role` | 2823.2 s | 1725 | no
| 16 | `intent_node_id_decode_hop_column` | 2721.5 s | 1078 | no
| 17 | `intent_mutation_payload_refusal` | 1408.4 s | 41 | no
| 18 | `intent_mutation_payload_column` | 7804.9 s | 545 | no
| 19 | `intent_mutation_payload_key_membership` | 607 ms | 239 | no
| 20 | `intent_mutation_write_destination` | 696 ms | 344 | no
|===

Positions 14 to 18 carry 15382.5 seconds, 99.4%. Position 18 alone is half the pass and inserts 545
rows. The thirteen cheapest positions cost 93.4 seconds between them.

**No target is large.** The biggest is 12817 rows and refills in 1.4 seconds. Nothing in the register
is expensive for the volume it produces, which forecloses any reading that the consumer schema is
simply big.

## 2. Ten of the twenty targets are unreachable, and they carry 95.4% of the pass

A static walk, not a measurement.

The generator, the language server and the MCP server reach every `intent_` relation through jOOQ
`Tables.INTENT_*` constants. The only other accessor form, `model.tables.IntentX`, names relations
already in that set, so the set of relations a consumer names is exactly extractable: 39 relations
across `graphitron`, `graphitron-mcp` and `graphitron-lsp`. Re-checked on 2026-08-28, and the same
39 come out.

A third access form does exist and the first version of this paragraph was wrong to deny it. Main
sources also reach relations by string, through jOOQ's `table(name(...))`, in five classes:
`Materializations`, `MaterializeDependencies`, `StoreCatalog`, `StoreProse` and `ViewReferences`.
Every such site resolves to a `meta_*` relation, `store_graph` or `INFORMATION_SCHEMA`, never an
`intent_` one, which is why the walk below is unaffected: those are the register's own machinery
reading the register, not a consumer reading a derivation. But the soundness of the walk rests on that
continuing to hold, and a string-named read is precisely the form that would break it silently.

One of the five names nothing literally. `StoreProse` builds it, `table(name(relation.toUpperCase(...)))`,
bounded to `metaRelations(dsl)` at the call site. That is the case for a gate stated more sharply than
"the form exists": a gate written as a grep for a literal `intent_` name would pass a computed one, so
the check has to be on what the argument can resolve to rather than on how it is spelled. Walking the view dependency graph outward from those 39, following `<target>_live`
wherever the walk meets a registered target that is a base table with no view definition of its own,
gives what a read can reach. Ten registered targets fall outside it.

They carry 14759.5 seconds of the 15477.1. The ten reachable ones carry 717.6.

**They are not orphans, and the distinction is the finding.** Every one of the ten is named by other
views. Their readers are each other plus seven further `intent_` views:
`intent_mutation_matched_key`, `intent_mutation_write_agreement`, `intent_mutation_write_refusal`,
`intent_condition_membership`, `intent_argument_filter_role`, `intent_input_field_column_scope`,
`intent_input_field_reference_step_target`. None of those seven is in the 39 either. It is a closed
subgraph with no exit, so a grep for any one relation finds readers and every one of those readers is
itself unreached.

What the subgraph is: the mutation write surface and the input-filter surface. Each of the ten
appears in two to five test sources, so this is work under construction rather than dead code, and
nothing here argues for deleting the relations. What it argues is that a registration was made before
it had a reader, and that the register currently spends 95% of its refresh on a bet about future
readers that nobody decided to make.

## 3. Three defect classes, and materialization is the right answer to none of them

### Class A: a grain the capture family never wrote

H2 expands a shared subtree once per path through the dependency graph, so a diamond becomes a tree.
Two rules were being reconstructed at read time from multi-arm unions over per-directive sibling
tables:

[cols="4,4,2"]
|===
| grain | reconstructed by | real size

| a written table or routine reference | 6-arm `UNION` over `graphitron_table`, three `*_reference_step` tables, `graphitron_mutation`, `graphitron_routine` | 313 rows
| an argMapping pair | 7-arm `UNION ALL` over the `*_arg_mapping_pair` siblings, each joined to its owning directive table | 108 rows
|===

Neither derives anything. Both re-tag and re-union rows that were facts when capture wrote them, and
capture holds their provenance (`source_name`, `source_line`, `source_column`) at the moment it
writes them. Building each as an indexed table costs 0.05 and 0.04 seconds.

Plan sizes, `EXPLAIN` only, no execution, before and after both grains:

```
intent_argmapping_projection_defect   235756 -> 89296 lines
intent_node_id_decode_defect           96755 -> 67049
intent_resolved_node_key_projection    41302 -> 17071
```

Five relations that had not completed in 120 seconds complete, **with nothing materialized**:
`intent_carrier_routine_hop` 0.07 s, `intent_field_chain_node` 0.03 s,
`intent_field_reference_step_target` 3.62 s, `intent_field_separate_fetch` 10.52 s,
`intent_resolved_node_key_column` 18.09 s.

Nine relations still exceed 30 seconds after both grains, so more are missing. The residual scan
signatures name the candidates: `graphql_field_directive` (12588 rows), `graphql_field` (8408),
`graphql_type` (2345), `graphitron_field_node_id`, `graphitron_argument_lookup_key`.

### Class B: a join key that exists only as an expression

No index can serve a key that is computed inside a view. Two instances measured.

`intent_class_member_slot` builds a bean-property name inline from a method name and
`intent_field_accessor_hop` joins on it against a `COALESCE` on the other side. H2 pushes the
predicate into the view, finds nothing indexable and nested-loops; the read did not complete in 120
seconds. The fix is a stored computed column, which is neither of the two levers the retired item on
this subject weighed:

```sql
ALTER TABLE jvm_method ADD COLUMN bean_property VARCHAR GENERATED ALWAYS AS (CASE ... END);
CREATE INDEX jvm_method_bean_ix ON jvm_method (source_name, bean_property);
```

With the view reading the column: **over 120 s to 1.84 s, 21287 rows both ways.** It also dissolves
the `get`/`is` prefix join, a two-row inline `VALUES` joined on `LEFT(method_name, n)`. The schema
already uses `GENERATED ALWAYS AS` in about thirty-five places.

The second instance is the wrapper-stripping named-type expression, spelled identically at three
sites over a `LEFT JOIN graphitron_field_synthesis`, and used as a join key at two of them. Measured
separately, earlier: a rung shaped that way cost 19.9 s for 157 rows against 0.13 s for the same rows
with the expression projected as a column first. Three controls on that fixture: joining
`f.named_type` directly is 0.08 s, so the cost is the expression and not the row count; materialising
the inner relation in a `WITH` is still 19.6 s, H2 inlining a non-recursive `WITH`; and routing
through `graphql_type` before joining, which is how both live sites spell it, is 19.6 s and no fix.

**Where the value comes from decides the lever.** A named type is handed to capture by graphql-java,
so capture writing it is the right rung. A bean-property name is a pure function of a column already
in the store, which is what a generated column is for; routing it through capture would make a
producer own a rule the DDL states in one place.

### Class C: a normalized column that exists and is not indexed

`sql_column` carries `jooq_name_upper` and `column_name_upper` and indexes neither, so somebody
already applied class B's lever to the case-folding and stopped short. Necessary, and on its own not
sufficient: adding indexes to `sql_table.table_name_upper` and both `sql_column` normalized columns
moved the worst plan in the stratum from 235756 lines to 235682. An index changes the access path and
never the expansion.

No index on any `graphql_*_directive` table leads with `directive_name`; every one is
coordinate-first, so "which fields carry `@table`" can never be index-served, and eleven distinct
`directive_name = '<literal>'` filters exist across the stratum. Indexes leading with
`directive_name` were added during the investigation and their timing effect was **not isolated**.
Treat that as untested.

## 4. Why materialization kept winning, and why that was an artefact

Three mechanisms, none of them written down before.

**It was the only candidate on the ballot.** Every registration was argued by comparing the rule
evaluated on demand against the rule stored. Neither arm is the rule with its missing grain captured,
or with its join key stored as a column. A comparison between two options cannot report that a third
was better.

**A registration silently delivers an index.** A target is a table and a table can carry an index; a
view cannot. So every registration bought an index nobody priced separately, and removing
registrations removes indexes along with the stored rows. An ablation that demoted the registered set
to views measured over 1839.4 seconds with 15 timeouts against 175.8 seconds with 1, which reads as
"materialization is vindicated" and is not: it measured lost indexes and lost expansion cuts
together. The register has been acting as an index-delivery mechanism.

**Every figure was taken in a regime the build never entered.** Each registration's stated `reason`
was measured against a settled store whose statistics were current, while the capture refresh ran
against an unanalysed one. On this store that difference is 69-fold across refresh positions 1 to 16,
6262.6 seconds against 90.8. That figure is the pass, re-derived position by position from the
capture log's own `n/20 done in ...` lines, which also sum to 15477.2 over all twenty and to 15382.5
over positions 14 to 18, matching section 1's table. An earlier reading of 6293 seconds circulated in
the working notes and is arithmetic rather than a second measurement: it exceeds the pass by 30.4
seconds, which is exactly position 8, counted twice. The ratio is unaffected at 69-fold either way. The cold-refresh split has since fixed the ordering, which is what made
the two regimes separable on one store and is why this audit could be taken at all.

**And statistics are necessary, not sufficient.** The same split buys almost nothing on the tail:
position 17 costs 1141.8 seconds analysed against 1408.4 cold, and position 18 was still running at
5892.3 seconds when it was cancelled, so its analysed cost is a lower bound. About 1.2-fold, against
69-fold on the prefix. A capture on a DDL carrying the split but not the payload pair is therefore
predicted to cost over 7126 seconds, of which over 7034, about 98.7%, is positions 17 and 18. Both
are unreachable. That is a prediction from two readings, not a measurement, and no capture has been
taken against it.

## 5. The intent layer re-derives semantics the graphitron family already holds

The store has two families by design: `graphql_*` holds the SDL as parsed, syntax-near, and
`graphitron_*` holds the semantics extracted from it. Six `intent_` views ask a semantic question of
the syntax-near family.

The worst is `intent_carrier_data_field_live`, which asks whether any field of a type carries a
semantic directive by scanning the largest table in the store, `graphql_field_directive` at 12588
rows, against a twelve-name `IN` list, correlated on `type_name` alone, per outer row. Answering the
identical question from the semantic family returns the identical answer: **529 types either way**,
off tables an order of magnitude smaller.

Two of the six are not defects. The two `authored_*_claim` views exist to detect what an author wrote
that did *not* extract, so a claim view has to see the raw SDL by construction. And
`@notGenerated` is not a graphitron directive at all, so there is nothing for capture to have
extracted and the SDL read is the only correct source. That leaves three views re-deriving.

## 6. Hypotheses refuted, including this investigation's own

Recorded so nobody re-runs them.

- **"Materialization is vindicated"** and **"the registry may be unnecessary"**, both drawn from the
  demote-everything ablation above, which conflated three effects.
- **A semi-join rewrite** of the `graphql_field` by `store_graph_source` join, read straight off a
  plan and obviously right on the page: 330.7 s, against 0.46 s for an indexed temp table and 87.2 s
  for the same table unindexed. Discarded.
- **A unified `graphql_applied_directive` table.** Built at 15190 rows with indexes, with
  `graphql_directive_site` repointed at it. Plan sizes byte-identical before and after (89296, 67049,
  17071), because only two view bodies name more than one `graphql_*_directive` table and the hot
  views read `graphql_field_directive` directly. Right shape, wrong layer: the unification that would
  pay is in the semantic family.
- **Indexing the `_upper` columns as the cure for large plans**: 235756 to 235682 lines.
- **"`@notGenerated` is a capture gap"**: it is not a graphitron directive.
- **"The refresh never analyses"**: it did, once, after the transaction committed. The defect was
  ordering, not absence, which changed the fix from adding a statement to moving a boundary.

## 7. Method, and two corrections earned the hard way

**`EXPLAIN` plan size is an instant, direct measure of expansion multiplicity, and it is the first
instrument to reach for.** Plain `EXPLAIN` never executes. The size of the text it returns reads how
many times H2 instantiated a shared subtree, and the `/* PUBLIC.X.tableScan */` annotations name the
access paths. Fourteen relations were characterised in **6.7 seconds** where running them cost about
half an hour of 120-second timeouts and said less. Read on a fresh connection per statement. Take the
plan as a string, never as a rendered `Result`, which truncates the column to about fifty characters
and reads as an empty plan.

**A plan generates a candidate. It never confirms one.** The two most plausible candidates this
investigation produced from plan reading were both wrong by more than two orders of magnitude, and
both were caught only by ablating. Nothing goes into a recommendation without a measurement against a
named alternative.

## 8. What the dissolved items contributed, so nothing is lost with their files

Nine items are dissolved against this audit. What each established and is worth keeping:

- **The consumer-capture item** localised the cost to a per-driving-row re-evaluation of an
  unregistered view inside a correlated term, measured the mechanism directly rather than reading it
  off a plan, and landed two registrations that cut its two dearest statements. Its fixture figures:
  `intent_mutation_payload_column_live` 851/826/827 ms against about 27, and
  `intent_mutation_payload_refusal_live` 69/73/82 ms against 10/9/12, for about 67 ms of added
  refresh per graph. It also set down the transaction-boundary arm with a measured ratio of 0.82
  against it, which is a dead end that should not be re-entered.
- **The cut-set item** contributed the vocabulary this subject needs (read cost against refresh cost,
  cut set, refresh depth, substitutes), the instrument table separating what each measurement can and
  cannot answer, and the conclusion that a static reading of the view definitions cannot rank the
  register. That last is a negative result from a scoring metric that was built, failed its own
  pre-committed gate with eight inversions where zero were demanded, and was deleted.
- **The payload-verification item** contributed the instrument recipe for a reportable long capture
  (`mvn -X` for the per-registration tier, a pinned store directory) and the discharge rule that a
  registration's name is emitted before its `DELETE`, so the position that never returns is the one
  that gets named.
- **The write-payload item** asked whether four seconds of refresh on a fixture was real. It is:
  7804.9 s and 1408.4 s at consumer scale, with the key-membership sibling innocent at 607 ms.
- **The decode-read item** measured the dearest single read in the store, 742 ms visiting 10603 rows
  to answer 47, and observed that no gate holds a figure over it. The second half is an obligation the
  successor inherits.
- **The inlining-cost item** attributed a 151-second relation to expansion rather than to any child,
  by plan node counts, and recorded that two registrations elsewhere took one evaluation to about
  144 ms.
- **The expression-keyed-join item** contributed the three-site census of the wrapper-stripping
  expression and the four controls quoted in class B.
- **The multiplicity-reporter item** established that a textual reference count cannot see an inlined
  CTE or a correlation, which are the two mechanisms that made the most expensive statement in the
  store expensive. Section 7 supersedes the metric rather than repairing it.
- **The DDL-claims item** caught a relation comment that had been steering authors wrong for several
  increments, and asked what a gate over prose claims could look like. Section 4 makes that
  systematic: every claim in the DDL was taken in the regime section 4 describes.

## 9. Reconciliation with the set-level pricing taken the same week

R848 priced the register as a set and reached Done on 2026-08-28. Its file is deleted, so its figures
live in `roadmap/changelog.md` under its own entry; that entry is the citation, not this paragraph.
It concluded that all twenty registrations earn their place. That looks opposed to section 2 and is
not, so the reconciliation is recorded here rather than left for a reader to derive.

The two instruments answer different questions. That harness asks what a registration is worth to the
relations above it, by removing it and reporting refresh saved against read time lost. Section 2's
walk asks whether anything above it is ever demanded by a consumer. For the ten unreachable targets
both answers hold together: their readers exist, those readers do get much more expensive when the
registration is removed, and no consumer is ever waiting on the result. A registration can be worth a
great deal to a chain that nothing pulls on.

That entry contains the seam where this becomes visible from its own side. It records that
`intent_argument_column_scope` has no reader that is not itself a registered source view, so removing
it moved nothing its instrument could see, and lets that row stand on a pre-committed rule rather than
on a measured value. That is section 2's finding met at one relation by an instrument with no notion
of reachability.

**Its Done gate bounded it on population, which is the more important half of this reconciliation.**
Round 3 withheld on exactly that ground, and the entry now states that the consumer-scale population
was never reached, that every verdict is conditional on sakila, and that on a population whose refresh
axis weighs orders of magnitude more the candidate-C trade would need re-taking before anyone acts on
it there. Section 1's population is that one: 15477.1 seconds against about 1317 ms. So the narrowing
this audit would have argued for was applied by that item's own reviewer, and the two documents agree
about the boundary rather than disputing it.

What is sound in that work and unaffected by this audit: the `CandidateCutSet` harness, the
three-capture spread analysis that reversed four verdicts on the ground that the baseline's own
refresh moves more than the savings claimed, the candidate-C result, and the shape pin. The spread
discipline is the right standard for anything measured on this subject and should be adopted rather
than re-derived.

What does not carry is one inference: from "the readers of X get dearer without X" to "X earns its
place". That holds only where the reads are demanded. Beside it sits one figure worth re-taking
rather than inheriting: candidate C's cost of 568 seconds across thirty readers is summed over
readers whose reachability was never checked, so some part of it may be cost that nothing collects.
Re-running the walk over those thirty is cheap and would say how much, and it tests this audit as much
as it tests that entry.

None of this argues the register is incoherent. It argues that the register is coherent against a
metric that is the wrong one for half of it, on a population its own author flagged as the one not
measured.

## 10. The same store, re-priced on the shipping DDL

Taken 2026-08-28, after sections 1 to 9 were written, against the same kept capture. It is filed as a
section of this audit rather than as a second document because it moves section 1's headline figure
by more than two orders of magnitude, and a reader who found section 1 without this would carry away
a number the tree stopped producing on the day the audit was written.

### How a capture can be re-priced without re-taking one

Section 1's figures came off one build's log, and the closing sections treat a capture on the shipping
DDL as out of reach for any session working from the repository. That was too strong, and the
distinction it missed is between capturing a schema and refreshing one already captured. A capture
needs the consumer's machine, its sources and its catalog. A refresh needs only the captured base
facts, which the kept store holds, and a schema to run them against, which is a file in the
repository.

So the pass is reproducible from the kept store on any DDL: build the schema from the DDL under
measurement, copy every base table's rows in from the kept store, derive the refresh order, refresh
each registered target from its `_live` view, and time each one. `MatBench` already did the first two
steps and the last, and needed two corrections before it could price a pass rather than a workload.
It took the refresh order from the order the `meta_materialize` seed rows appear in the DDL, which is
not the order the store computes: the shipped `refreshOrder` is a Kahn walk over edges parsed from
the booted store's own view definitions, and the literal order refreshes
`intent_argument_scope_table` before the relation it reads, which empties it and everything under it.
And it analysed once before the pass, where the shipped capture cadence,
`Materializations.refreshAnalysing`, analyses each target immediately after refilling it.

**Two checks say the rebuild is the same population.** The derived order reproduces the observed
capture order for all twenty registrations that existed on 2026-08-27, position for position. And
every one of those twenty targets comes out at the row count section 1 records for it: 108, 149, 313,
12817, 635, 151, 3737, 2618, 967, 967, 114, 1804, 86, 629, 1725, 1078, 41, 545, 239, 344. A rebuild
that agreed on cost and disagreed on rows would be answering a different question.

**One known shortfall.** `sql_enum_binding` is a capture-written table the shipping DDL declares and
the 2026-08-27 store predates, so it is empty in the rebuild. One view reads it,
`intent_java_enum_class`, whose other arm is unaffected; what is missing is the arm that reaches a
generated jOOQ enum. No registered target's row count moves, which bounds the effect, but a figure
below should not be taken as exact for a relation whose answer turns on a generated enum.

### The pass on the shipping DDL: 43.0 seconds

Twenty-two registrations, the cadence above, positions in the order the store derives.

[cols="1,5,2,2,2"]
|===
| # | registration | refresh | rows | reachable

| 1 | `intent_argmapping_pair` | < 0.05 s | 108 | yes
| 2 | `intent_errors_field` | 5.6 s | 149 | yes
| 3 | `intent_spelled_table` | 0.1 s | 313 | yes
| 4 | `intent_field_reference_step_hop` | 1.0 s | 12817 | yes
| 5 | `intent_resolved_type_binding` | 0.1 s | 635 | yes
| 6 | `intent_carrier_data_field` | 1.5 s | 151 | yes
| 7 | `intent_field_column_scope` | 1.2 s | 3737 | yes
| 8 | `intent_field_scope_table` | 0.9 s | 2618 | yes
| 9 | `intent_argument_scope_table` | < 0.05 s | 967 | yes
| 10 | `intent_argument_column_scope` | < 0.05 s | 967 | no
| 11 | `intent_argument_column_match` | < 0.05 s | 114 | no
| 12 | `intent_input_field_resolving_table` | < 0.05 s | 1804 | no
| 13 | `intent_mutation_write_payload` | < 0.05 s | 86 | no
| 14 | `intent_node_id_instruction` | 3.6 s | 629 | yes
| 15 | `intent_input_field_filter_role` | 18.7 s | 1725 | no
| 16 | `intent_node_id_decode_hop_column` | 4.1 s | 1078 | no
| 17 | `intent_node_id_decode_column` | 3.7 s | 1559 | no
| 18 | `intent_input_field_carrier_role` | < 0.05 s | 1165 | no
| 19 | `intent_mutation_payload_refusal` | 1.3 s | 41 | no
| 20 | `intent_mutation_payload_column` | 0.2 s | 545 | no
| 21 | `intent_mutation_payload_key_membership` | 0.4 s | 239 | no
| 22 | `intent_mutation_write_destination` | 0.6 s | 344 | no
|===

Seven positions are reported as a bound rather than a figure because the harness renders a tenth of a
second and they came in under one; nothing below turns on which side of a millisecond they fall.

Two totals appear for this pass and they are not in conflict. The positions above sum to 43.0
seconds, and the harness reports the pass as 43.2, the difference being the per-position `ANALYZE`
calls the cadence makes between positions. Comparisons against other arms use the harness total on
both sides.

The two positions that carried 9213.3 seconds of section 1's pass, the payload refusal and the
payload column, cost 1.5 seconds between them. The dearest position is now
`intent_input_field_filter_role` at 18.7 seconds.

### Which of the two landed changes did it, and a control that the harness is not simply fast

Two things landed between the capture and this reading: the cold-refresh split, which is the
`refreshAnalysing` cadence, and two registrations on the payload family,
`intent_node_id_decode_column` and `intent_input_field_carrier_role`.

**The two registrations are the tail.** The same DDL and the same cadence, with those two demoted
back to views and the other twenty kept, refreshes in **588.2 s**: the payload refusal at 82.3 s and
the payload column at 464.5 s, against 1.3 and 0.2 with them. Every other position is within noise of
the table above. So the pair is worth 13.6-fold on the pass, and all of that lands on the two
positions that dominated section 1.

**The split is the prefix, and the control says the harness reproduces the regime it should.** The
DDL as of the capture, twenty registrations, with no analysis anywhere in the pass, is the condition
section 4 describes. Position 14 costs **727.2 s** in the rebuild against 624.5 s in the capture
itself, so the harness reproduces within about sixteen per cent a figure it was not fitted to, on the
position where section 4 predicts hundreds of seconds. That is the check that matters: a harness that
returned seconds here would have said nothing about the 43.0.

### What survives, and what does not

**The reachability finding survives and has grown.** Re-run on the shipping DDL, over a read set
re-derived from today's main sources and unchanged at 39 relations, twelve of the twenty-two
registered targets are outside the consumer cone: the same ten, plus both of the two that landed on
2026-08-28. They carry 29.0 of the 43.0 seconds, 67.4 per cent.

**But its force changes with the number it governs.** Section 2 says a registration was made before
it had a reader, and that this puts 95 per cent of a four-hour refresh into filling relations nothing
demands. The first half is unchanged and is a statement about how registrations get made. The second
half is now 29 seconds, and 29 seconds is a coherence argument rather than a performance one. Anybody
carrying section 2 forward should carry that with it.

**The three defect classes are untouched, because they were never about the refresh.** Section 3's
grain measurements, section 5's re-derivation and the expression-keyed join in class B were all taken
against reads, and the read side is where the consumer-scale cost now lives. Timed on the rebuilt
store over the 39 relations a consumer names, one statement per fresh connection, 120 seconds per
statement, with the register as it ships:

```
intent_field_accessor_hop              over 120 s, refused
intent_resolved_node_key_projection        32.6 s
intent_argmapping_projection_defect        32.3 s
intent_resolved_field_demand               29.6 s
intent_field_column_table                   9.2 s
intent_resolved_type_demand                 9.1 s
intent_authored_claim_conflict              8.0 s
intent_field_participant_scope_table        7.1 s
                             total        251.5 s, 1 refused
```

**The workload is a proxy and its bias is upward.** It is `SELECT count(*)` over each of the 39
relations, which is the set a consumer names but not the queries a consumer writes: a real read
carries predicates that can prune, where a count forces the whole relation. So every read figure in
this section is an upper bound on what a consumer actually pays for that relation, and a relation that
looks like it needs help here may not. What the proxy is fit for is comparing arms, since the same
statement runs in each, and that is all it is used for below. Extracting a faithful workload from a
traced `generate` run is the standing prerequisite before retiring any registration on read evidence.

**So the register is not buying good reads either, and the relation it cannot fix is the worst one.**
`intent_field_accessor_hop` is class B's measured instance over the bean-property expression, and it
refuses the budget with all twenty-two registrations in place, because no registration can index an
expression.

### The arm nobody had run: no registrations at all, with the shape fixes in

Section 4 says materialization kept winning because it was the only candidate on the ballot. The arm
that puts the other candidates on it is the register emptied and the shape fixes applied: both known
supertypes captured and repointed, the bean-property key as a `GENERATED ALWAYS AS` column with an
index, the navigated type as a stored total relation, and indexes on the folded `sql_*` columns. It
applies to the rebuilt store in 0.9 seconds and every repointed relation returns exactly the rows it
returned before, 313, 108, 4198 and 8408.

[cols="4,2,2,2"]
|===
| arm | refresh | reads | over budget

| 22 registrations, no shape fixes | 43.0 s | 251.5 s | 1 of 39
| no registrations, shape fixes in | 0 s | 1054 s, a floor | 7 of 39
|===

The total says the register wins as things stand. The composition says something more useful.

**One relation moves the right way and it is the one that refused above.** `intent_field_accessor_hop`
goes from refusing 120 seconds with the whole register to **1.90 seconds with none of it**, which
reproduces the 1.84 s class B predicted for the stored key. A third arm isolates which change did it:
with no registrations *and* no column it refuses the budget too, so the register makes no difference
to that relation in either direction and the generated column makes all of it. Same 21287 rows in all
three.

**Ten move the wrong way**, worst first: `intent_node_id_decode_defect` 0.49 s to refused,
`intent_column_match_claim` 0.10 to refused, `intent_field_reference_discovery` 0.38 to refused,
`intent_resolved_field_claim` 1.77 to refused, `intent_field_column_table` 9.2 to refused,
`intent_argmapping_projection_defect` 32.3 to refused, `intent_resolved_node_key_projection` 32.6 to
refused, `intent_field_participant_scope_table` 7.1 to 89.9, `intent_carrier_data_field` 0.00 to 35.3,
`intent_resolved_node_key_column` 0.16 to 18.4.

**Three of the ten have a named cause and the rest do not.**
`intent_argmapping_projection_defect`, `intent_node_id_decode_defect` and
`intent_resolved_node_key_projection` are exactly the residual relations whose dependency closure
reaches `intent_argmapping_bound_parameter_type`, which section 11 identifies as a six-arm
reconstruction of a supertype nothing has captured. No other residual relation reaches an unrepointed
reconstruction, and one relation that does reach one, the accessor hop through
`intent_declared_type_ref`, is fast anyway. So the signature explains part of this residual and is not
a general theory of it, which is worth stating precisely because the temptation is to read three
confirmations as seven.

**And section 4's prediction is refuted by the thing it predicted.** It forecast a capture on a DDL
carrying the split but not the payload pair at over 7126 seconds. That DDL was never shipped: the
pair landed the same day. The pass on what did ship is 43.0 seconds, and the prediction should be
read as a superseded intermediate rather than as a figure to quote.

### Where the store and the instruments are

The store is at `~/temp/graphitron-store-backups/sis-2026-08-27/store.mv.db` on the workstation that
took the capture, read-only, beside a `PROVENANCE.txt` and a recorded SHA-256 which this reading
verified before using it. The sibling `investigation-2026-08-27/` directory holds `MatBench`, the
smaller probes and the pass harness above. None of it is reactor code and none of it belongs there:
it measures one consumer's store against schemas from several days of the tree, and it is research
apparatus rather than anything a build should run. What the repository owes instead is the gate over
the access form that section 2 argues for, which is the one claim here a build could hold.

## 11. The defect class underneath class A, stated without measuring anything

Section 3's class A described "a grain the capture family never wrote" and argued it from what it
cost. That is the symptom. This section states the defect, which is a modelling one, and gives a
detector for it that reads the shipped DDL and needs no store, no capture and no timing. It was taken
on 2026-08-28 after sections 1 to 10, and it finds instances those sections missed.

### The defect

Where one fact is written at several kinds of site, capture writes one table per kind and no table for
the fact. Each set is closed and every member carries the same attributes, differing only in the key
that says which site owns the row. That is a subtype set with no supertype.

A reader whose question is uniform across the sites then has to reconstruct the supertype, and SQL
gives it one way to do that: union the arms, synthesise a discriminator, synthesise a uniform key.
The reader is doing the modelling capture did not do, and it does it once per reader.

**The store already contains a worked confession of this**, in `intent_declared_type_ref`. Its comment
says "the three census relations are three keys, so a reader whose question is uniform across the
owners has to name the owner before it can ask". It carries `owner_kind`, whose comment says there is
"one value per census relation of this shape, and there is no fourth". It carries `owner_descriptor`
and `owner_position`, NULL on exactly the arms whose key does not need them, each documented as "the
union's key shape rather than a fact withheld". A discriminator over a closed subtype set, arm-
determined NULLs and a synthesised uniform key is a supertype relation, written as a view because
capture wrote no table for it.

### The detector

Three parts, all off the DDL. A **subtype set** is three or more capture tables sharing a group of two
or more attribute names, where an attribute is a column not in that table's own primary key and not
provenance. A **confirmed omission** is a subtype set that some view `UNION`s three or more members of
**and** whose shared attributes that view names. `investigation-2026-08-27/tools/supertype_scan.py` is
the implementation.

**Two calibration errors were made in order, and they pull opposite ways.** The key-versus-value split
has to come from each table's own primary key and not from a list of key-looking names: the first
version excluded `class_name` globally, because it is the key of `jvm_class`, and missed the largest
subtype set in the schema for that reason alone. A name is a key in one family and a value in another.
Then the attribute part was missing, and its absence invented reconstructions: membership overlaps, the
`*_reference_step` tables carrying `table_ref` and `class_name` and `method` all three, so a view
unioning them to answer "which method does this site name" was credited with reconstructing the
table-or-routine reference too. The unchecked rule reported six union sites for the method-bearing set
where three are real, and four for the table-or-routine reference where one is. Both errors were caught
by checking the scan's output against the DDL by hand, which is what this instrument is owed every time
it is run.

**The check is name matching, not proof.** It asks whether a shared attribute appears in the view body,
not whether it is projected from the unioned arms. That separates the sets on this schema and it is why
a confirmed row is a reading to check rather than a verdict to inherit. Anything built as a gate on top
of it owes a tighter check or a written exemption list.

### What it finds on today's schema

[cols="4,1,1,4"]
|===
| the fact nobody wrote a table for | subtypes | union sites | shared attributes

| a directive site that names a Java method | 10 | 3 | `class_name`, `method`
| a written table-or-routine reference | 6 | 1 | `table_ref` and its four split and folded halves
| an argMapping pair | 8 | 1 | `argument_path`, `param_name`
| a declared type reference | 3 | 2 | `referenced_class`, `variance`
|===

Two further sets share an attribute group that nothing unions yet, which is the same defect with no
reader paying for it: a GraphQL type expression across `graphql_field`, `graphql_argument` and
`graphql_directive_argument`, sharing all eight of `type_sdl`, `named_type`, `non_null`, `is_list`,
`item_non_null`, `default_value_sdl`, `description` and `ordinal`; and a described ordinal member
across three.

**The first row is what this section adds.** Ten directive tables carry `class_name` and `method`,
because ten directives can name a Java method, and no relation says which site declares which method.
Three views reconstruct it: `intent_argmapping_bound_parameter_type`, whose six `UNION ALL` arms are
one query written six times, take an argMapping pair, join the directive table that owns the site,
project `class_name` and `method`, differing only in which table is joined and which `site` literal is
filtered on; and `intent_condition_param_extraction` and `intent_condition_table_parameter`, doing the
same at five arms each.

**What section 3 missed by looking at cost, stated carefully because an earlier draft of this section
overstated it.** Section 3 named the one reconstruction of the table-or-routine reference that exists,
and it was right: the claim that there were four was this scan's own defect and not section 3's. What
section 3 did miss is the declared type reference, which it never reached because that one is cheap on
this consumer's schema and a hunt driven by wall clock stops when the clock stops, and the
method-bearing site, which is the largest set here and has no entry in section 3 at all.

**And this is the mechanism connecting the modelling defect to section 3's plan sizes.** H2 expands a
shared subtree once per path through the dependency graph, so each independent reconstruction of one
missing supertype is expanded independently at every reader above it. That is why the symptom is plan
expansion rather than slow scans, and why the fix is a table rather than an index.

### What the detector does not settle

A shared attribute group is a candidate and not a verdict. Four `sql_*` tables share `table_schema`,
`table_name` and `column_name` and are not subtypes of anything: they reference a column rather than
being kinds of one. The union part separates those, a reader wanting a supertype unioning where a
reader wanting a reference joins, and the attribute part separates the sets that overlap.

Neither part makes a confirmed row a verdict, for the reason given above: the attribute check is name
matching over a view body. Any gate built on this needs a written exemption list, for the `sql_*`
reference groups, for the two sets being left alone deliberately, and for whatever the name matching
gets wrong next. That is the point rather than a weakness: it turns adding the eleventh sibling into a
decision somebody records, which is what nobody did while ten tables accumulated `class_name` and
`method`.
