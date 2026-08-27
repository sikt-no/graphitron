# The carrier's population filter: redundancy proved, cost comparison not takeable in-repo

A working document, not a roadmap item. It lives in `audits/` so the roadmap-tool ignores it.
It records what could and could not be established about the fork raised in R839's reviewer
findings 6, 11 and 12, so the next session does not re-derive the settled half or re-run the
instrument that failed. The probe code is disposable and is not committed anywhere.

## The question

`intent_carrier_data_field_live`'s `data_channel` CTE filters its population with

```sql
WHERE EXISTS (SELECT 1 FROM producer p
               WHERE p.graph_name = f.graph_name AND p.payload_type_name = f.type_name)
```

where `producer` is a non-recursive `WITH` naming `intent_field_payload_producer`. Three spellings
were on the table:

* **C**, as written, with `intent_field_payload_producer` registered in `meta_materialize` so the
  re-derivation reads a table.
* **A**, the filter deleted, the view's own outer join left to do the work.
* **B**, the filter respelled as a join into `data_channel` on a distinct projection of the
  producer.

Two questions separate them. Do all three return the same rows? And which is cheapest?

## Settled: all three return the same rows

By reading, and then by execution. The view's outer query is
`FROM producer p JOIN data_channel d ON d.graph_name = p.graph_name AND d.type_name =
p.payload_type_name`, and `d.graph_name` / `d.type_name` are `f.graph_name` / `f.type_name`
projected, so the join's surviving condition is the filter's condition verbatim. The window is
`COUNT(*) OVER (PARTITION BY f.graph_name, f.type_name)` and the filter is a function of those two
columns alone, so it is constant across each partition: it drops whole partitions and never thins
one, which is the case where a population filter under a window does change an answer. The
per-field `NOT EXISTS` against `intent_errors_field` beside it is the one that thins, and it is not
in question. `data_channel` is named once.

Confirmed by execution on a synthetic H2 2.4.240 fixture built to the same shape, the window and the
outer join included: the three spellings returned identical row counts. That is what a synthetic
fixture can establish, and it is the half of finding 6 that needed no store.

## Settled: joining the producer CTE directly multiplies rows

The `producer` CTE is `SELECT DISTINCT graph_name, payload_type_name, family`, so it is not unique
on the two columns the filter tests. A payload type produced under two families is two rows there,
which is load-bearing in the outer join, where the relation deliberately reports a row per family.
Joined into `data_channel` on the two columns it duplicates each field row per family and doubles
`data_fields`. Reproduced on the fixture: the naive join returned exactly twice the correlated
spelling's rows. So arm B needs its own narrower projection and is not a three-character edit.

## Not settled, and the instrument that failed

Which arm is cheapest is not answerable in this repository, and one plausible-looking shortcut
should not be retried.

**The store on disk is too small.** The only fact store a build had written is
`graphitron-maven-plugin/target/it-store/*/store.mv.db`: three graphs, 63 `graphql_field` rows, 45
types, one `intent_field_payload_producer` row and zero `intent_carrier_data_field` rows. The 41 s
figure R839 is about was taken on a consumer schema of 8408 fields. Nothing at the integration
fixture's scale can rank the three arms.

**A synthetic fixture did not reproduce the re-derivation.** Two harnesses were built at increasing
fidelity, the second carrying the window function and the outer join. In both, the correlated
`EXISTS` cost about the same as one evaluation of the rule rather than one evaluation per driving
row: the rule alone measured 62 to 71 ms and the whole correlated statement 86 to 268 ms, against a
driving population of 400 rows. H2 flattened the correlated reference rather than re-deriving it.
The window function did not prevent that, which was the reason the second harness was built.

So the mechanism R839 measured is real on the consumer store, on the strength of its own control
(snapshotting the producer CTE into a table took the CTE from 44.9 s to 224 ms, with the two
`CASE WHEN EXISTS` relations snapshotted as the control that changed nothing), and the conditions
under which H2 re-derives rather than flattens are not reproduced by a small synthetic stack. A
synthetic fixture is therefore not a valid instrument for this comparison, and a figure taken on one
would be worse than no figure: it would rank the arms wrongly and read as measured.

**What the comparison needs.** The three timings have to be taken on a consumer-scale captured
store, by the `store-performance` procedure: a store a real build wrote, single-file JDBC programs
over the pinned H2 version, `OPTIMIZE_REUSE_RESULTS` off, the real statements, two sweeps. For arm A
the figure that decides it is not the carrier's total but what the two `CASE WHEN EXISTS` probes into
`intent_bound_table` and `intent_type_backing` cost once the filter no longer narrows their
population, which is a driving-row ratio the same store can answer.

## What this leaves R839

The correctness half of the fork is closed, so the arms are interchangeable in what they return and
the choice is purely a cost choice. The cost half is open and needs a store this repository does not
contain. R839's plan body names the three arms and the doctrine that orders them
(`meta_materialize`'s own registration reasons require the reader-side rewrite to be tried and
priced before a registration is).

What R839 does with the unavailable ranking, as of its round 7 revision, is decide arm B on a bound
rather than on a ranking: arm B changes no population inside the view body, so its worst case is one
further inlining of a 4-to-31 ms rule and it cannot be dearer than the shape it replaces, while arm A's
population widening is exactly the quantity no store here can price. The consumer-store timing is
demoted to confirmation, recorded where such a store is reachable and recorded as absent where it is
not. So nothing in this document is a precondition on that item any more; it is the record of why the
ranking was not available and of the instrument that must not be retried.
