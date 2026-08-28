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

The generator, the language server and the MCP server read the store only through jOOQ
`Tables.INTENT_*` constants. No raw-SQL relation name appears in any main source, and the only other
accessor form (`model.tables.IntentX`) names relations already in that set, so the set of relations a
consumer names is exactly extractable: 39 relations across `graphitron`, `graphitron-mcp` and
`graphitron-lsp`. Walking the view dependency graph outward from those 39, following `<target>_live`
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
6293 seconds against 90.8. The cold-refresh split has since fixed the ordering, which is what made
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
