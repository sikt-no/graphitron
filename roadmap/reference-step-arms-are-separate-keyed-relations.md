---
id: R956
title: "A reference-step hop is four arms with two natural keys, so it is two keyed relations under a view rather than one relation padded with nulls"
status: Ready
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-17
last-updated: 2026-09-18
---

# A reference-step hop is four arms with two natural keys, so it is two keyed relations under a view rather than one relation padded with nulls

## Goal

A `@reference` path element's local resolution becomes two relations that each refuse a duplicate
row, split along the line that was always in it: one keyed relation for the hops that join on a foreign
key, one for the hops that join on none, every key column `NOT NULL`, each arm's legality stated in a
`CHECK` rather than in prose, and a view unioning the two under `intent_field_reference_step_hop`, the
name every reader already spells. Today that one relation carries two key shapes at once, which is why
it has no primary key and why nothing in the tree refuses a duplicate row in it. Nothing a reader
spells changes, and who writes the rows does not change either: both relations stay registration
targets, refreshed by the mechanism that refreshes the one today. When this lands, both are declared in
`meta_relation` and satisfy `MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain` with no gate
touched, which is what unblocks R954's conversion of the hop into gatherer-written facts. The recursive
walk over the hop takes the same two-arm shape on the day it is stored, and this item fixes that shape;
the walk is a plain view today and stays one here, for the reason "The walk" below gives. What this
buys is an enforcer where there was none, a key that refuses the duplicate row: every reader keeps
reading the padded fifteen-column view, and a reader that would rather read one arm typed is R954's or
a successor's, not this item's.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A *registration* is a
row of `meta_materialize`, which keeps a rule in a view under a `_live` name and moves the canonical
name onto a table a refresh pass empties and refills. An *arm* here is one branch of a `UNION ALL`
whose rows are told apart by a literal the branch projects into a discriminator column. A *hop* is one
table-to-table step a path element could express, before the walk decides which one the chain took.

## The diagnosis

`intent_field_reference_step_hop` declares fifteen columns and **every one of them is nullable**, with
no primary key. Its rule, `intent_field_reference_step_hop_live`, is a `UNION ALL` of four branches
projecting `'KEY'`, `'TABLE'`, `'NAME_MATCH'` and `'CONDITION'` as constants into `via`. On the
`NAME_MATCH` and `CONDITION` branches the constraint columns are projected as literals: `NULL` for
`key_matched_by` and `constraint_name`, `CAST(NULL AS BOOLEAN)` for `fk_on_from`. A projected literal
`NULL` is not a fact about a hop. It is padding that makes four row shapes share one column list.

The three column comments already state the rule that padding encodes, one by one:
`key_matched_by` is "NULL on a TABLE, NAME_MATCH or CONDITION hop, none of which names a constraint";
`constraint_name` is "NULL on a NAME_MATCH hop, which joins on no foreign key, and on a CONDITION hop,
which joins on an authored predicate instead"; `fk_on_from` is "NULL on a NAME_MATCH hop ... and on a
CONDITION hop". That is the shape `docs/architecture/principles/development-principles.adoc` names
under sealed hierarchies over enums: variants carrying different data forced into one field set, whose
tell is that one discriminator value implies which fields are non-null.

**The arms have two different natural keys, and that is why no key exists.** On the `KEY` and `TABLE`
arms, `constraint_name` and `fk_on_from` are identity: both orientations of every foreign key
connecting the two tables are separate rows, which is exactly the ambiguity
`ix_field_reference_step_hop_step`'s comment names when it says it is "Not UNIQUE and not the grain".
On the `NAME_MATCH` and `CONDITION` arms there is no foreign key to enumerate, so the element
coordinate with the departing and arriving table triples is already total. One relation cannot carry
two key shapes.

`intent_field_reference_step_target`, the recursive walk over the hop, projects the same three columns
with the same absences. It is a plain view today, so it can carry no key and the problem is latent
there; R954 stores it, and the problem is real the day it is a table. An earlier draft of that item
explained the missing key by H2's refusal of a primary key over a nullable column. That is a statement
about an engine and cannot be the reason for a modelling decision, and the reason underneath it is this
item's subject.

This is the fact-model page's own worked anti-example rather than a new observation. That page records
a `@reference` path element that "needed nine nullable columns and a legality rule no constraint could
state" and became three relations, one per assertion. `intent_condition_method_route` and its
`_defect` sibling are the shipped instance of the same move.

## The shape

Two keyed base tables and a view, all three in `graphitron-model.sql` beside the relation they replace.
The split axis is **key shape**: a hop that a foreign key identifies has that key's name and orientation
in its identity, and a hop that none identifies does not. The projected `NULL`s the diagnosis names are
the symptom that led here, not the rule, and one of them survives the split for that reason, below.

`intent_field_reference_step_hop_keyed` holds the `KEY` and `TABLE` arms: the hops that join on a
foreign key, in one orientation of it. Fifteen columns, the same list the relation carries today, with
one nullable column left in it and every other column `NOT NULL`:

```sql
PRIMARY KEY (graph_name, type_name, field_name, ordinal, position,
             from_source_name, from_schema, from_table,
             to_source_name, to_schema, to_table, constraint_name, fk_on_from),
FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
CHECK (via IN ('KEY', 'TABLE')),
CHECK (key_matched_by IS NULL OR key_matched_by IN ('SQL_NAME', 'JOOQ_NAME')),
CHECK ((key_matched_by IS NOT NULL) = (via = 'KEY'))
```

`key_matched_by` stays nullable and stays a column, and the `TABLE` branch of the rule keeps projecting
a `NULL` into it. That is not the padding the diagnosis names. It is an attribute of the fact the row
states, which namespace answered a written constraint name, and on a `TABLE` hop it is inapplicable
rather than omitted, a table element writing no name to match. It is payload and never identity, the
`KEY` and `TABLE` arms share one key shape, and key shape is the axis, so the two stay one table and the
attribute's legality is a `CHECK` rather than a third relation. The third `CHECK` is a biconditional
because a `KEY` hop always has a namespace, the rule computing it from the constraint it matched, so a
`KEY` row without one is a defect and the constraint says so.

`graphitron_field_table_link` is the shipped relation a reviewer will raise here, and the answer is one
sentence. It holds five arms over the same `via` vocabulary in one keyed table, with `key_matched_by`,
the constraint columns and `fk_on_from` nullable under eight `CHECK`s, and it can because its key is the
chain position: one link per position per target, the constraint being payload of a resolved link. The
hop has no chain to be positioned in, its rows being the candidate routes the walk has not yet chosen
between, so the constraint is identity there and a second key shape appears. Its `CHECK`s are still the
model for stating an arm's legality in a constraint, and the ones above are written on its pattern.

`intent_field_reference_step_hop_keyless` holds the `NAME_MATCH` and `CONDITION` arms: the hops that
join on no foreign key. Twelve columns, `key_matched_by`, `constraint_name` and `fk_on_from` not among
them, every column `NOT NULL`:

```sql
PRIMARY KEY (graph_name, type_name, field_name, ordinal, position,
             from_source_name, from_schema, from_table,
             to_source_name, to_schema, to_table),
FOREIGN KEY (graph_name) REFERENCES store_graph (graph_name),
CHECK (via IN ('NAME_MATCH', 'CONDITION'))
```

The three columns are absent from this table rather than nullable in it, which is the whole move: a
`NULL` the rule projected was padding, and the padding has nowhere to go once the row shapes stop
sharing a column list.

`intent_field_reference_step_hop` becomes a view: `SELECT` the fifteen columns from the keyed table,
`UNION ALL` the twelve columns from the keyless table with `CAST(NULL AS VARCHAR)` for
`key_matched_by` and `constraint_name` and `CAST(NULL AS BOOLEAN)` for `fk_on_from` between them in the
positions the readers expect. Every reader that spells the name today reads exactly the rows it reads
today, so none is edited: `intent_field_reference_step_target`, `intent_field_chain_node`,
`intent_input_field_reference_step_target`, and through those every registered rule above them. The
`NULL` that was padding inside the rule is now a presentation of absence at the one surface that has
to present fifteen columns, which is where a compatibility shape belongs.

**Neither arm carries a step index: the keys serve the seek, measured.**
`ix_field_reference_step_hop_step` served the recursive step's join on eight columns, the element
coordinate and the departing triple, and both primary keys above lead with exactly those eight columns
in that order. That makes a declared index read as redundant, and the tree had already measured that
reading and refuted it once: `intent_spelled_table`'s own comment records that `ix_spelled_table_spelling`
"stays beside the key rather than being folded into it, and that was measured rather than assumed: it
is a prefix of the key, so it reads as redundant, and removing it moved three refresh statements into
needing the registered targets' statistics to plan and changed a pair in the read-cost gate. A narrow
non-unique index is not the same offer to the planner as the leading columns of a wide unique one."
So the pair shipped with one index apiece and the folding was run as the experiment under "The
measurement this item owes", which came back the other way on both instruments: identical scan counts
and identical plans on the read-cost gate, and the same nine-member set on `RefreshPlanStatisticsTest`.
The indexes are therefore gone and the two targets carry `MaterializeRegistryGateTest.NO_INDEX` rows
in `intent_node_id_decode_hop`'s structural form, the argument being that an index on those eight
columns is a prefix of each key. The two verdicts are not in conflict: what the spelled-table finding
says is that a prefix is not always free to drop, which is why this one was measured rather than
assumed.

**Why `via` is a payload and not part of either key.** Whether an element resolves through the `KEY`
or the `TABLE` arm is a property of the element: the two arms select on `key_ref IS NOT NULL` and on
`key_ref IS NULL`, so one coordinate is on one arm. `NAME_MATCH` and `CONDITION` select on
`table_ref IS NOT NULL` and `table_ref IS NULL` and are likewise disjoint. A coordinate *can* have rows
in both tables, the `TABLE` and `NAME_MATCH` arms sharing the `table_ref IS NOT NULL AND key_ref IS
NULL` selection, and that is the point of splitting by key shape rather than by coordinate: a table
element whose spelling reaches two tables, one by a foreign key and one by column-name match from a
function result, is two candidate routes with two key shapes, and each lives where its key is.

**Uniqueness per arm, checked against the shipped rule rather than assumed.** On the `KEY` arm one
`sql_constraint` row joins one `sql_referential_constraint` row and one orientation, and the key
determines the constraint: the declaring table is the departing triple where `fk_on_from` is true and
the arriving one otherwise, so `(constraint_name, fk_on_from)` beside the two triples names one
constraint. The `TABLE` arm multiplies only through `intent_spelled_table`'s candidates, each of which
is a different arriving triple. A self-referential key draws one row because the rule's `WHERE` admits
only the `fk_on_from` orientation when the two endpoints coincide. On the keyless arm, `NAME_MATCH`
enumerates `sql_table` rows once each through `store_graph_source`, and `CONDITION` joins
`intent_condition_method_route`, which is a `UNION` and so distinct on its projected columns, on the
entry's `(graph_name, class_name, method)`, one entry row per coordinate. So each arm's `_live` view is
already at its table's grain and no `DISTINCT` is needed; if a refresh ever finds otherwise the key
refuses the row loudly, which is the property this item exists to add.

## Implementation

All in `graphitron-model`, plus the pinned figures in `graphitron`'s test tier that a register change
moves. No Java in the mechanism changes: `Materializations` refreshes whatever `meta_materialize` names
and `MaterializeDependencies` derives the order from the view texts.

**DDL.** Replace `intent_field_reference_step_hop_live`, the `intent_field_reference_step_hop` table
and `ix_field_reference_step_hop_step` with:

- `intent_field_reference_step_hop_keyed_live`: the first two branches of today's `UNION ALL`, text
  unchanged. Its `COMMENT ON VIEW` carries the `KEY` and `TABLE` paragraphs of today's table comment,
  which is where the rule is stated and therefore where the rule's prose lives once the target's own
  comment is fixed by the declaration gate (below).
- `intent_field_reference_step_hop_keyless_live`: the last two branches, text unchanged except that the
  three literal-`NULL` projections are dropped from the column list. Its comment carries the
  `NAME_MATCH` and `CONDITION` paragraphs.
- The two tables above, each with the `COMMENT ON TABLE` the declaration gate requires (grain sentence
  and example, verbatim) and a `COMMENT ON COLUMN` per column, which `FactSchemaGateTest` requires of
  every column. Two things the echo gate evicts from a declared target's comment, and where each goes.
  The rule prose goes to the `_live` views, above. The materialization notice every registered target
  closes its comment with today ("Materialized: this relation is a table refilled from ... under the
  registration in `meta_materialize`") cannot be a second sentence after the example, so these become
  the first registered targets whose own comment does not say they are refilled tables, and the union
  view's comment says it for both in the sentence that says which table holds which arm. These are the
  first declared registration targets in the tree, confirmed by joining `meta_materialize`'s targets
  against `meta_relation` on the shipped DDL, so there is no precedent to copy and this is the one.
- No index on either table: the keys lead with the eight columns every reader seeks on, and the
  folding experiment found them serving it. Two `MaterializeRegistryGateTest.NO_INDEX` rows instead,
  the roster carrying the measurement.
- The union view under the existing name, with a `COMMENT ON VIEW` saying what a row is (one candidate
  route of one element, before the walk decides), that it is two relations under one name because its
  rows have two key shapes, which refilled table holds which arm, and the pushdown premise readers
  depend on with its figure from reading 1 below. It is the **comment of record for the eleven shared
  columns**: their fifteen column comments carry over from today's table, whose "NULL on a NAME_MATCH
  or CONDITION hop" sentences are now literally true of this surface and of nothing underneath it. The
  arm tables' shared columns take the pointer form the `_live` views use today, aimed at the view
  ("the from_table of a row of this arm, presented on intent_field_reference_step_hop.from_table, whose
  comment carries what the value means"), so each column's meaning is stated once with the readers'
  name on it; the keyed table owns only the three columns that are its alone. Three free copies of one
  sentence with nothing holding them equal would be drift at column grain, which is what this avoids.

Register the two `_live` views in `meta_materialize`, one row each in place of today's one. Each
`reason` is set-relative on the register's own terms: it inherits the retired row's timings (the hop at
around thirty milliseconds as a view and under one as a table, `intent_node_id_decode` from about fifty
seconds to about thirteen, the walk from around thirty milliseconds to three), names the other arm as
the neighbour it is priced beside, and says that the pair together is one evaluation of the same text
the single row was, the `UNION ALL` having been a concatenation and nothing else. It does not inherit
the retired row's reader census, which is stale: that text says two view bodies name the relation, and
three do today with five namings between them, `intent_field_reference_step_target`,
`intent_field_chain_node` and `intent_input_field_reference_step_target`. The census is re-derived from
the tree at implementation time or left to the timings and the neighbour pricing to carry.

**Declarations.** Two `meta_grain` rows and two `meta_relation` rows, owner `derivation`, the gatherer
every declared `intent_` relation already names and whose class is `Materializations`, the register's
own refresher. Proposed grain names, R900's sweep being free to respell them:

- `reference-step-keyed-hop`: "one candidate foreign-key route one field-site @reference path element
  could take, in one orientation, in one graph"; `key_shape` the thirteen-column list above, in that
  order; corpus `catalog`, as `reference-path-intermediate` declares.
- `reference-step-keyless-hop`: "one candidate table pair one field-site @reference path element joins
  without a foreign key, in one graph"; `key_shape` the eleven-column list; corpus `catalog`.

The union view is **not** declared here, and the Backlog draft's count of three declarations was
wrong for this item: `MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks` holds observed relation
*names* against `undeclared-relations.txt`, the view keeps the name that roster already carries, and
nothing checks a rostered name's kind. Declaring it would force a single grain onto a relation whose
rows have two, which is exactly the fact the split states. The question becomes real when the name
changes, which is R954's rename, and "Relation to other items" hands it over with the reading this item
proposes. The retired `_live` view was exempt through the register and stood on no roster, so no roster
line moves at all.

**Gates and pins that moved**, each re-derived on the fixture rather than predicted:

- `MaterializeRegistryGateTest.REGISTRATIONS` 23 to 24. `REFRESH_STAGES` held at 16, both arms sitting
  at the depth the one target sat at, as the body expected.
- `MaterializeRegistryGateTest.NO_INDEX` gains both arm tables, the folding experiment having gone the
  way "The shape" above now records. `everyTargetIsIndexedOrStatesWhyNot` and
  `everyIndexOnATargetStatesItsReader` bind on the roster rows rather than on two indexes.
- `DerivedReadCostTest` moved on all four of its pinned figures. `READERS_IN_SCHEMA` 126 to 128, one
  `_live` view out and two in plus the union view crossing from the table side; `READERS_WITH_CELLS` 63
  to 65; `CELLS` 145 to 164, nineteen cells, because the canonical name is a view again so the
  reachability walk no longer stops there and every reader that reached the hop reaches two
  registrations. The non-monotonic set went from eight cells charged to the hop to six: the three
  readers that were regressions stay, one cell per arm, and the five at the instrument's four-scan
  floor left because the split's own cost is larger than the floor. Figures on the two survivors, in
  scans registered against each arm unregistered: the walk 973 against 619 and 477, the field-site
  column scope 2750 against 2396 and 2254, the fan-out 22183 against 19351 and 18215.
- `RefreshPlanStatisticsTest` gains a ninth member, `intent_resolved_type_binding_live`, and it is the
  set's own first-paragraph mechanism arriving on another statement: with the targets analysed its read
  of the hop seeks the keyed arm's primary key on the eight columns, and cold it falls to that arm's
  `graph_name` constraint index. The set moved because the relation gained a key, not because this rule
  changed, which is a new way for that set to move and the note there says so. The same paragraph's
  named index no longer exists and is respelled to the arm key.
- `DetectionReadReachGateTest`'s `UnlowerableOrderings` component gains
  `intent_field_reference_step_hop`, which the body did not anticipate and which follows from the same
  fact: that name is a view again, so the detection pass expands it. What it expands to is a `UNION
  ALL` of two registered targets with no rule under either, so the row costs a concatenation of two
  seeks.
- `FactCaptureAgreementTest`'s roster: the two tables, the two `_live` views and the union view under
  `Arm.DERIVED`, where the two retired names sat.
- `WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` censuses every non-view table, so both arm
  tables joined it with no edit, and it passes: a second capture of one graph into one store leaves
  both row counts unchanged.
- `SchemaIdentifierDriftCheck`, the roadmap-tool gate nobody named in this body, caught the one stale
  citation the sweep would otherwise have left: a `meta_materialize.reason` naming the retired rule.
  Worth recording because it is the mechanical half of the retirement sweep for store identifiers.

**Documentation.** `docs/architecture/explanation/fact-model.adoc`'s paragraph on splitting the hop
from the walk gains the sentence that the hop is itself two relations under a view, because its arms
have two key shapes, which makes it the second shipped instance of the page's own anti-example beside
`intent_condition_method_route`. The same page's rule under "The question to ask of every column" gains
the tell this item acted on, stated as a tell and not as the rule: a discriminator whose value implies
which columns are null is the sign to ask whether the rows have one key shape, and key shape decides.
The same page's "Derived reads are views, not stored facts" cites the retired index twice, once as "the
measured gain reported below on the reference-step hop table is half index and half statistics" and once
as the index rung's own worked example at 18308 scans against 523; both are respelled to the arm indexes
with the figures reading 1 takes, so the rung's one example keeps pointing at an index that exists.
`docs/architecture/how-to/dev-loop-internals.adoc`'s example refresh log line names
`intent_field_reference_step_hop_live`, a relation that will not exist; it is respelled to one of the
arm rules. Nothing in the user manual changes, this item having no author-visible surface.

## The walk

`intent_field_reference_step_target` is a plain view, and this item leaves it one. Its rows have the
same two key shapes the hop's do, `constraint_name` and `fk_on_from` being identity on the `KEY` and
`TABLE` elements and absent on the other two, with `targets` and `candidates` as payload either way. The
shape it takes when stored is therefore fixed here and lands with the storing, which is R954's phase 2:

- `intent_field_reference_step_target_keyed` and `intent_field_reference_step_target_keyless`, keyed as
  the hop tables are with `targets` and `candidates` beside the key, `CHECK` on `via` as above, and a
  coordinate index per table shaped like `ix_argument_reference_step_target_coordinate` where the
  readers holding the element coordinate need one, with the comment `everyIndexOnATargetStatesItsReader`
  requires.
- A union view under the existing name with the same `NULL` presentation, and a declaration per table
  under `derivation` or under `graphitron`, whichever owner the storing gives the rows.

Why the split does not land as views now: the walk is one recursion whose arms are separated only after
the chain is complete, so two views each carrying the `WITH RECURSIVE` under a union view would evaluate
the recursion twice for every reader, doubling the very re-evaluation R954 exists to remove, and would
buy nothing a view can hold, a view refusing no duplicate row. R954's phase 2 text already writes the
walk as two inserts per graph differing only in a closing arm filter, so the DDL above is what that
phase's inserts land in and nothing about its writer changes.

## The measurement this item owes

**Taken, and here is what it said.** Reading 1: the seek survives the union view and costs 178 scans.
On `DerivedReadCostTest`'s twelve-unit fixture with statistics current, reading
`intent_field_reference_step_target` whole is 973 scans over the union view against 795 over the single
table it replaces, and `intent_field_column_scope_live` 2750 against 2572, the same 178 on both because
it is the walk's namings against the second branch. The `EXPLAIN ANALYZE` plan of the recursive step
shows an index probe into each branch and no scan of either: the keyed branch seeks its primary key on
all eight columns at one row per iteration, and the keyless branch seeks its `graph_name` constraint
index at two, that arm holding two rows at this fixture's size. What a scan would cost is on the same
plan for comparison: with the keyed arm's registration removed the union view costs 14457 scans where it
costs 397 as two tables. The kill criterion below is therefore not met. The folding experiment came back
against the indexes on both instruments, and "The shape" above records the outcome and where the
argument now lives.

Readings 2 and 3, on a store captured from the sakila example schema, best of five reads: the union view
1 millisecond for 280 rows against 2 for the single table, the walk 1 against 1,
`intent_field_chain_node` 4 against 4, and `intent_field_reference_step_fanout` 22.3 seconds against
17.1. That last one is the one place the split costs anything a clock can see, and it is the relation
that was already this store's outlier by three orders of magnitude, the walk paired with itself over the
catalog; the scan counter charges the same direction at a seventh of the size, 22183 against 20759.
Refresh: 12 milliseconds for the keyed arm's 265 rows and 7 for the keyless arm's 15, against 18 for the
single refill of 280 they replace. The register rows carry readings 2 and 3 and the union view's comment
carries reading 1, as planned below.

**The `EXCEPT` oracle, run during implementation**, on three fixtures, the pre-split four-arm rule text
against the new union view: the read-cost gate's twelve-unit store (197 rows, 192 `KEY`, 4 `TABLE`, 1
`NAME_MATCH`), a routine-then-hop capture over the sakila catalog (11 rows, 9 `TABLE`, 2 `NAME_MATCH`),
and a seeded four-arm fixture in `graphitron-model` (5 rows, 2 `KEY`, 2 `TABLE`, 1 `CONDITION`). Both
directions empty and `COUNT(*)` equal on every one.

The plan the readings above answer, kept because it states what each reading was for. The
recursive step in the walk, the same join in `intent_field_chain_node` and the input-field walk, and the
`@nodeId` decode rule through that walk all seek into the hop on the eight leading columns. Under a
union view the planner has to push that eight-column equality into both branches and seek each arm
table's primary key. H2 does push a join condition into a union view's branches by parameterising the
view's query, and it usually plans the seek. Usually is not a figure. Three readings, in this order, per
the `store-performance` skill's own ranking of evidence:

1. **Scan shape on the read-cost fixture.** `DerivedReadCostTest`'s twelve-unit store with statistics
   current is the instrument the retired index's comment used, and its figures are the baseline: reading
   `intent_field_reference_step_target` whole cost 18308 scans without the index and 523 with it, and
   `intent_field_column_scope_live` 19839 against 2054. Take the same two figures against the union view
   over the two arm tables with their step indexes declared. The claim to meet is that both land within
   the instrument's floor of the indexed figures. The `EXPLAIN ANALYZE` plan of the walk's recursive
   step is read beside the count, and it has to show a seek into each arm rather than a scan of either.
   This reading answers one question, whether the eight-column seek survives the union view, and its
   figure goes on the union view's own comment, where the premise it prices is stated.
   **The folding experiment** is a separate question with a separate home: take the same figures and
   `RefreshPlanStatisticsTest`'s pinned set with each arm's step index dropped, its key alone offering
   the prefix. If nothing moves, on either instrument, the indexes go and the key-prefix argument goes
   into two `NO_INDEX` rows in `intent_node_id_decode_hop`'s structural form, which the roster's own
   javadoc says "is falsified by a new reader rather than by a new figure". If anything moves, the
   indexes stay and their comments say the experiment was run, as `intent_spelled_table`'s does.
2. **Per-relation timing on a populated store.** The `graphitron-sakila-example` schema captured
   through `CapturedStore.ofCatalog`, five interleaved sweeps over the hop view, the walk,
   `intent_field_chain_node` and `intent_field_reference_step_fanout`, before and after, read out of
   `INFORMATION_SCHEMA.QUERY_STATISTICS`. Documentation rather than assertion, per that skill: a wall
   clock is not a claim a test may hold.
3. **The refresh itself**, from the `-X` log line per registration on the same capture: two refills
   whose durations sum to about the one they replace.

Where the figures go: the union view's comment carries reading 1's pushdown figure; the two index
comments, or the two roster rows if the indexes fold, carry the folding experiment's result; the two
`meta_materialize.reason` texts carry readings 2 and 3 as the set-relative pricing the register asks
of every row.

**The kill criterion, stated so it is not discovered in review.** If reading 1 shows the planner
scanning an arm with the step indexes declared, then no index can fix it, the seek never having reached
the table, and the union view's premise is false: readers would have to name the arm tables directly, which is a
different item with edits to every walk. In that case the item comes back to `Spec` with the plan
attached, and R954's phases 1 and 2 take the registration fallback that body already licenses per
rung. The exposure is bounded, and worth stating so it is not read as larger than it is: what the seek
prices is the build of each walk over the hop, not the reads above a stored walk, which read a table
whichever way this lands.

## Tests

What demonstrates the goal, by name:

- **`MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain`** binds on both arm tables, which are
  declared and keyed. This is the gate the goal names, satisfied with the gate untouched; and
  `theUndeclaredRosterOnlyShrinks`, `theRegisterExemptsOnlyTheRuleItStates`,
  `theCommentEchoesTheDeclaration` and `ownerAndGrainAgreeAboutTheCorpus` all bind on the two new
  declarations, which is what the two grains and comments have to satisfy.
- **A constraint pin per arm table**, in `graphitron-model`'s test tier beside `ReferenceStepTargetTest`
  and on its seeded catalogs: inserting a row already present is refused by the key, a keyed row with
  `via = 'TABLE'` and a `key_matched_by` is refused by the `CHECK`, a keyed row with `via = 'KEY'` and
  no `key_matched_by` is refused, and a keyless row with `via = 'KEY'` is refused. This pins the DDL and
  not the rule, which keeps `ReferenceStepTargetTest`'s stated intent that the hop's rule is pinned
  once, through the chain that reaches or refuses it. The implementer checks which arms those seeded
  catalogs exercise and makes sure each table takes rows in at least one case; the `CONDITION` arm needs
  a classpath census the read-cost fixture lacks, and `ReferenceStepTargetTest`'s own fixtures decide
  whether it has one.
- **`ReferenceStepTargetTest`, `ReferenceStepFanoutTest`, `ChainTerminusTest` and
  `ArgumentReferenceStepTargetTest` pass unchanged.** Their expectations are the rows the walk produces
  over the hop, and the union view reproduces today's rows exactly. The argument-site test's parallel
  case, which seeds one path shape at both sites and compares the shared columns, is the cross-check
  that the split changed no arm's rule.
- **The `EXCEPT` oracle, run once during implementation** and recorded in the commit: on the sakila
  capture and on the seeded fixtures, today's `intent_field_reference_step_hop_live` text against the
  new union view, both directions empty and `COUNT(*)` equal, the count being what `EXCEPT` cannot see
  when a side holds a row twice.
- **`MaterializeRegistryGateTest`** in full: `targetsAreShapedLikeTheViewsThatFillThem` on both pairs,
  `theRegisterIsTheShapeThisTestStates` at 24, `everyTargetIsIndexedOrStatesWhyNot` on the two
  `NO_INDEX` rows the folding experiment bought, `nothingMaterializesOutsideTheMechanism` seeing two
  registered `intent_` tables where it saw one.
- **`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`** on both keys, which lead with
  `graph_name`; and its comment census on every new column.
- **`WarmStartRefreshTest`** on a bound schema, the second capture leaving both counts unchanged.
- **The measurement above**, written into the union view's comment, the two `NO_INDEX` roster rows and
  the register reasons, is the item's acceptance evidence at the Done gate: a green build is compatible
  with the seek having been lost, and only reading 1 says it was not.
- **No reader discriminates on the hop's nullable columns**, checked on the shipped tree: the
  `constraint_name IS NULL` and `IS NOT NULL` tests in view bodies are over other aliases (a primary-key
  probe, the fan-out rule's covering constraint, the decode rule's anonymous foreign-key count, the
  mutation key match), none over a hop row. So no reader is the one that should name an arm table
  directly today, and the implementer re-checks that while reading every reader body, since one that
  does would be the first candidate for an arm-typed read under R954.

## Retired vocabulary

- `intent_field_reference_step_hop_live`, the single rule view; replaced by the two arm rules.
- `ix_field_reference_step_hop_step`, the declared step index; replaced by nothing, the two keys
  serving the seek, which the folding experiment measured on both instruments.
- `intent_field_reference_step_hop` as a *table*: the name persists as the union view, so the sweep
  is for prose calling it a table or a registration target, in comments, `reason` texts and docs.

## Other solutions we've considered

**Keying one relation with a sentinel for "no constraint".** Makes `constraint_name` mean two things
and is the denormalisation the fact-model page declines by name.

**Keying a narrow prefix beside the nullable columns.** The nullable columns are what tell two routes
between one table pair apart, so no prefix of them is unique.

**Widening `aDeclaredTableKeyMatchesItsGrain` to admit a declared keyless table with a stated reason.**
Drafted and withdrawn in R954's round-6 response and recorded there. `meta_grain.key_shape`'s own
comment says a declared table at a grain carries exactly that key, every exception roster this project
runs admits on something harder than an argument, and an index is not a key.

**Three arm tables, one per `via` value with a nullable column.** Would split `KEY` from `TABLE` to
make `key_matched_by` `NOT NULL`. The two arms share a key shape, which is the axis, and the column is
an inapplicable attribute on one of them, which a `CHECK` states exactly.

**One keyed table on `graphitron_field_table_link`'s pattern.** Its constraint columns are payload of a
link keyed by chain position; the hop's are identity, and no set of `CHECK`s makes one relation carry
two key shapes. "The shape" carries the sentence.

**Keeping a step index beside each key.** Reads as the safe default and was measured not to be
needed. `intent_spelled_table` records what folding cost there, which is why this was run as the
experiment under "The measurement this item owes" rather than decided by analogy; here the read-cost
figures and plans were identical either way and `RefreshPlanStatisticsTest` held the same nine members,
so the indexes went.

**Splitting the walk as views now.** Doubles the recursion under every reader and refuses no duplicate,
per "The walk".

**A `graphitron_` prefix for the arm tables**, as the Backlog draft spelled them. The prefix names the
writer, the writer stays the register, and `nothingMaterializesOutsideTheMechanism` is what holds a
stored `intent_` table to being registered or hand-written. The `graphitron_` names are R954's, when
the writer changes.

## Relation to other items

**R954** converts this subtree's relations into facts the graphitron gatherer writes, and its phases 1
and 2 depend on this item. Its body cites `ix_field_reference_step_hop_step` and the single hop rule in
several places, and those citations are stale the moment this lands: the index is gone, the keys serve
the seek, and the rule is two. They are left for that item's own next revision rather than edited here,
since a cross-item edit to a `Spec` body moves that item's reviewer-rule guard. The dependency is the declaration the conversion forces: a converted
relation leaves `MaterializeRegistryGateTest.nothingMaterializesOutsideTheMechanism`'s `intent_`-scoped
scan and lands on `MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks`, whose ratchet makes a
`meta_relation` row mandatory, which in turn brings `aDeclaredTableKeyMatchesItsGrain` to bear. Of the
shipped tree's 270 base tables the 17 carrying no primary key are all registration targets standing on
the frozen undeclared roster, so a conversion of an unkeyable relation would be the first declared
keyless base table. This item removes the unkeyability instead of asking the gate to tolerate it. R954's
round-7 review asked the two bodies to agree on the seam, and this body's answer is the reading that
round called coherent: the names this item leaves behind are the `intent_` ones, with the union view on
the roster line the name already holds, and R954's phase 2 is the phase that splits the walk, this item
contributing the two key shapes and the DDL they land in but no relation. Three things pass to R954
from here. Phase 1 converts two keyed registration targets rather than one keyless
one, retiring two rows. Phase 2 lands the walk's DDL under "The walk" above, its two-insert text
already matching it. And the union views' declarations arrive with R954's renames: a `graphitron_`
name is new to the roster, so each view then owes a `meta_relation` row. The view's rows are not at the
keyed arm's grain, whose sentence says "in one orientation" of a foreign key and is false of a keyless
row, so the view gets a third grain of its own: "one candidate route one field-site @reference path
element could take, foreign-key or not, in one graph", at the thirteen-column shape, which under
null-as-value semantics is a key of the union and is what `aDeclaredTableKeyMatchesItsGrain`, binding
only on base tables, will never be asked to check. One extra `meta_grain` row, satisfied by the view
the moment it is declared, and confirmed against `aDeclaredViewReadsOnlyWhatItsOwnerMay` rather than
taken on trust.

**R876** is the doctrine both items instantiate. Its burn-down records that nothing refuses a duplicate
row in these targets today; a key closes that here rather than deferring it to whatever reconciles the
rows.

**R955** converts the register's remaining fifteen registrations and twelve of them carry no primary
key. Its rule is a primary key where the grain admits one, and this item is the answer to the case
where the grain does not, at least where the reason is a discriminated union: ask whether the relation
is one relation before asking the gate to admit a keyless one. Whether each of its twelve is that shape
is that item's to determine. One of them plainly is: `intent_argument_reference_step_target` carries
the same three nullable columns for the same reason, and `intent_argument_reference_step_hop`, a plain
view, is the argument-site copy of the four arms. Both are R954's rung 5 and the successor's, and this
item does not touch them; it is the shape they take.

**R900** is the naming sweep. `_keyed` and `_keyless` are proposed because the discriminating property
is whether the hop joins on a foreign key, and because `_key` would collide with the `via` value that
names only one of the two arms it would hold. Nothing here pre-empts the sweep.

## Provenance

Filed 2026-09-17 out of R954's round-6 Spec review, which found that the rename R954's conversion
argument rests on walks the converted relations into a declaration obligation and thence into the key
gate, and that three of its four relations looked unkeyable. Following why produced this diagnosis: one
of the three keys cleanly after all and the other two are not one relation each. R954's body records
the four arms weighed there and why three were declined, including a widening of the key gate that was
drafted and withdrawn.

## Reviewer findings

### Round 1 (2026-09-18, In Review -> Ready, reviewer session `01BKfBzr8KdGXF354jzXEaKw`)

Verdict: rework. Question one passes without reservation. Question two fails on one of the three
artifacts this body names as its own acceptance evidence, and the retirement sweep this gate owes
turns up one surviving term. Both findings are located, narrow and prose-only; nothing below asks
for a different design, a different DDL or a different test.

Reviewed at `218dd0d` as it stands on trunk, with the full reactor green under
`mvnd install -Plocal-db` at `9b289c2` (`BUILD SUCCESS`, 14 modules, none skipped). The two finding
sites are unchanged at `2d2414f`.

**Question one passes.** The delivered tree is the change this body approved, checked against the
contract rather than against the commit message. The two arm tables carry the exact column lists,
keys, foreign key and `CHECK`s the "The shape" section specifies, verbatim. The rule split is
faithful where it matters most: diffing the two `_live` bodies against the retired four-arm rule,
the `KEY` and `TABLE` branches are byte-identical and the `NAME_MATCH` and `CONDITION` branches
differ only by the three literal-`NULL` projections being dropped from the column list, which is
exactly what the body promised. The union view's explicit column list reproduces the retired table's
column order position for position, so no reader could see a shift. The two `meta_grain` rows carry
the proposed grain sentences and key shapes word for word; the two `meta_relation` rows sit under
`derivation`; the register carries two rows where one stood, and the reader census in the keyed
arm's `reason` is re-derived correctly rather than inherited, five namings across three view bodies
being what the DDL actually holds. `ReferenceStepHopArmConstraintTest` pins every refusal the body
named and two it did not, seeds both arms rather than assuming them, and asserts on constraint
violations rather than on any code string. The docs edits land where the body said, with no
roadmap-internal vocabulary leaking into them.

The index decision is the one place the delivery departs from the shape the body was written
around, and the departure is licensed: the body ran the folding as an experiment with a stated
decision rule, so dropping the indexes is the rule firing rather than a substitution. Finding 1 is
about what the tree now says that experiment found, not about the decision.

**Finding 1, question two: the `NO_INDEX` roster entry misstates the folding experiment, in the
direction that argues against the decision it justifies.** `MaterializeRegistryGateTest:290` reads
"On `RefreshPlanStatisticsTest` the indexes were a cost rather than a wash: with them declared,
`intent_resolved_type_binding_live` joins the set of registrations whose refresh plans differently
with the targets' statistics than without, which is a ninth statement whose cold-capture plan stops
matching its settled one. So the indexes went".

That is false three ways, and the first needs no measurement. The shipped tree declares no indexes
and pins `intent_resolved_type_binding_live` in `PLAN_DEPENDS_ON_STATISTICS`, with the test green:
the member is in the set *without* the indexes, so its membership cannot be what declaring them
cost. Second, it contradicts this body and the landing commit, both of which say the opposite in
plain terms, "the same nine-member set on `RefreshPlanStatisticsTest`" here and "the same
`RefreshPlanStatisticsTest` set either way" there. Third, it was put to the instrument: declaring
both eight-column indexes on the arm tables and re-running leaves `RefreshPlanStatisticsTest` green
on its pinned set and `DerivedReadCostTest` green on all four of its figures and its non-monotonic
set. The folding is a wash on both instruments, which is what this body says and what the roster
denies.

Why this blocks rather than reading as a phrasing slip. This body names its own acceptance evidence
and the roster is one third of it: "The measurement above, written into the union view's comment,
the two `NO_INDEX` roster rows and the register reasons, is the item's acceptance evidence at the
Done gate." The other two thirds are right; this one is not. And the error inverts the body's own
decision rule, which says that if anything moves on either instrument the indexes stay. Read
literally the roster now records a measurement that mandates the opposite of the DDL beside it, in
the one roster whose stated doctrine, quoted inside the same entry, is that it carries a measurement
rather than an argument. The next contributor weighing a prefix index against a key meets a
precedent that says the prefix cost a plan divergence it did not cost, on exactly the question
`intent_spelled_table`'s comment says is subtle.

What satisfies it: restate that entry's second instrument as a wash, and account for
`intent_resolved_type_binding_live`'s membership where it belongs, as the consequence of the
relation gaining a key that `RefreshPlanStatisticsTest`'s own new paragraph already states
correctly. No DDL, no pin and no test needs to move; the indexes stay gone and the key-prefix
argument stands on the wash.

**Finding 2, retirement sweep: `intent_field_reference_step_hop_live` survives in javadoc.**
`RefreshPlanStatisticsTest:284` still names it, in the paragraph listing the registrations the fact
tables' statistics move onto plans of their own. The term is declared retired in this body's
`## Retired vocabulary`, the relation no longer exists, and javadoc is the first surface the sweep
names. Three neighbouring paragraphs in that same file were rewritten for the split and this one was
passed over, so the miss is local rather than systematic. Nothing mechanical catches it:
`SchemaIdentifierDriftCheck` scans `docs/architecture` and the store's own prose, not Java.

The citation's substance needs a look too, not just its name. The claim is about which registrations
move when only the fact tables are analysed, and the retired rule is now two rules; whether both arm
rules behave the way the single one did is a fact the paragraph asserts and nobody has checked. So
the fix is a re-derivation and not only a respelling.

The surviving citations in other roadmap bodies are not findings. `reference-step-walks-are-stored-rows.md`
and `per-row-view-naming-fails-the-build.md` are `Spec` items whose bodies this item deliberately
left alone with a reason stated under "Relation to other items", and
`first-refresh-plans-without-statistics.md` cites the retired index as the historical shape its own
measurements were taken on, which is provenance rather than drift.

**Non-blocking, and not this item's to fix.** `PartitionSelectivityWorthTest` and
`fact-model.adoc`'s cold-refresh paragraph were both re-grounded on the split by R953's own commits
after this landed, correctly, and the eight-member figure they now carry matches the set as R953
left it. Nothing here is owed by this item; it is recorded so the next reader of this section does
not re-derive it as a discrepancy.
