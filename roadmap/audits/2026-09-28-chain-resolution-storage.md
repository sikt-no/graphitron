# What storing the chain-link resolution measured

Measured 2026-09-28 on a copy of a consumer store (the sis schema: 828 chain links, 3569 readings,
3987 resolution rows), taken from a `graphitron:dev` round that had sat in the `EntryDefects` stage
for over 36 minutes at one full core. The same round's predecessor, on a jar from before the chain
walk moved into the catalog, ran the whole derivation stratum in 34.5 s. Filed as an audit for the
reason the others state: the item's file dies at Done and the numbers have to survive it.

The instrument was a throwaway JDBC harness timing one statement at a time against copies of the
store, each variant built as a view or `CREATE TABLE AS` beside the shipped relations and proved
answer-identical to them by `EXCEPT` in both directions. **Not kept.** Single executions on a
machine whose noise floor at this scale is a few seconds; the fill figures below vary between 8 and
22 s across runs, and only order-of-magnitude differences are claimed.

## Where the time went

| relation, as shipped | time |
|---|---|
| `graphitron_field_chain_link_reading` | 14 to 16 s |
| `graphitron_field_chain_link_resolution` | 8 to 22 s |
| `graphitron_field_table_link_rule` | 30 s |
| `graphitron_entry_defect_rule` | did not return: 36 min live, over 10 min on the copy |

The defect rule names the resolution through two non-recursive CTEs, `routes` and `head_reach`,
and `broken` reads `routes` inside a correlated `NOT EXISTS`. H2 inlines a non-recursive `WITH`
like a view, so the recursive walk runs once per chain driven through it rather than once.

## Which relation to store

| stored | defect rule | table-link rule |
|---|---|---|
| nothing | did not return | 30 s |
| the reading only | 47 s | 0.08 s |
| the resolution only | 3 s analysed, 1.1 s unanalysed | 0.04 s unanalysed |
| both | 0.2 s | 0.1 s |

Storing the reading alone leaves the defect rule's per-row re-walk in place: the walk is cheap once
its input is stored, and 47 s is the correlation. Storing the resolution is what removes it.

**No statistics cliff.** The assembly pass reads the resolution table in the same capture that
fills it, before the capture's `ANALYZE`, so a cold store plans that read against default
selectivity (50 on `graph_name`). Measured that way the table-link rule took 0.04 s and the defect
rule 1.1 s. The stratum runs after the capture's `ANALYZE`, so `EntryDefects` plans against true
figures.

## The reading's KEY arm

Of the reading's 14 to 16 s, the KEY arm is 8.8 to 9.7 s for 508 rows; ROUTINE, TABLE, NAME_MATCH
and CONDITION together are under a second. The arm matches a written key against `sql_constraint`
through a three-way `OR` no index can serve, joined to every source the graph reads (47 on this
schema), with a correlated `NOT EXISTS` behind the jOOQ-name branch. Rewritten as a `UNION ALL` of
two equi-joins, one per branch, it ran in 1.06 s and matched the shipped arm by `EXCEPT` both ways.
Every KEY row on this schema matched by SQL name, so the jOOQ-name branch was not exercised. Not
done under the storage change; it is its own node.

## Transactions

The first shape tried filled the three arm tables from one walk staged in a local temporary table.
H2 2.4.240 accepts `CREATE LOCAL TEMPORARY TABLE t (...) TRANSACTIONAL` inside an open transaction
without committing it, and rejects the `AS SELECT` form with that clause; `DROP TABLE` and
`TRUNCATE` on it both commit. The assembly pass runs inside the capture's transaction, so the
stage fetches the rule's rows once and binds them into the three tables instead.
