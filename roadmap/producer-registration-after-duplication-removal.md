---
id: R861
title: "Does the carrier producer still want a registration once the duplicated condition is gone"
status: Backlog
bucket: model
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Does the carrier producer still want a registration once the duplicated condition is gone

`intent_field_payload_producer` is a view that `intent_carrier_data_field_live` used to re-derive once
per driving row, because that view stated one condition twice. Removing the duplicate is a sibling
item's subject. This one asks the question that could not be asked while the duplicate stood: with the
carrier naming the producer once, does anything still want the relation registered in
`meta_materialize`?

**Why it is a separate item rather than an arm of the other one.** `meta_materialize.reason`'s column
comment says a registration's row argues that a view expresses its rule correctly and only too slowly,
and that a row which cannot make that claim is not a registration. While the carrier named the producer
twice, that claim was unavailable: the rule was not too slow to evaluate per naming, it was named more
times than it needed to be. The register's own doctrine says the same as an ordering obligation, in
`intent_mutation_payload_key_membership_live`'s reason: a registration prices the rule as it stands, so
a rule with a re-evaluation inside it should be rewritten before it is priced. So this question is not
answerable until the rewrite has landed and the carrier has been re-measured.

**What is already in hand, so nobody re-derives it.** Two candidate registration depths were priced on
a consumer schema of 8408 fields before the duplication was noticed: registering the relation as it
stands measured 327 ms and 261 ms, and promoting the carrier's own `producer` CTE to a first-class
relation and registering that measured 253 ms and 206 ms, so the deeper option bought about 60 ms for a
new relation with a name and comments of its own. One evaluation of the rule is 4 to 31 ms. The
relation has two readers in SQL, the carrier and `intent_field_error_channel`, the latter driving from
it in a plain `FROM` and so already paying exactly one evaluation. Those figures were taken with no
index declared on a candidate target.

**What has to happen before this can move to Spec.** The carrier has to be re-measured on a
consumer-scale store after the duplication is removed. If the carrier is cheap at that point, this item
is `Discarded` and the figures above are its record. If it is still expensive, the term has to be
re-bisected before a registration is proposed, rather than assuming the producer is still the subject:
the whole lesson of the sibling item is that the first-identified term was the one a reader introduced.

Two constraints any eventual registration inherits. The target's grain is
`(graph_name, type_name, field_name, family)` and a declared primary key on it is available, the input
tables being keyed on the coordinate and the ROUTINE arm carrying `DISTINCT`; that key does not serve
the one known probe, which seeks `(graph_name, payload_type_name)`, so the index question is open on its
own terms. And `MaterializationOrderTest` covers no shape where a source view reads a relation from
inside a `WITH` body, which is how the carrier reaches this relation; that hole is open on the tree
today and this item is the one that closes it, the shape mattering only where a registration's refresh
edge has to be derived through it, which no rewrite does. The sibling item specifies the synthetic case
and leaves it here rather than adding it.

**The index question, and why the obvious measurement of it misleads.** Every registered target either
carries a declared index whose `COMMENT ON INDEX` names the reader it serves, or has a row in
`MaterializeRegistryGateTest.NO_INDEX` arguing why not, asserted by equality in both directions. So a
registration here cannot land without answering the question, and the answer has to be a figure. This
would be the first registration whose motivating reader probes in from a population larger than the
target: the carrier sought `(graph_name, payload_type_name)` once per driving row of `graphql_field`
joined to `graphql_type`, thousands of driving rows against a producer of a few hundred, which is the
shape `intent_argument_column_match`'s roster row names as the one that would change its own answer.
Do not assume that roster row here.

Two things about the probe constrain what an honest measurement is, and both were established while
the duplicate still stood. First, the probe carries a constant the two-column coordinate omits: the
carrier's `producer` term selects on `root_operation = 'MUTATION'`, and most of the target's rows are
not mutation-rooted (`root_operation` is null for every producing field on a non-root type and 'QUERY'
for most of the rest), so a seek on `(graph_name, payload_type_name)` alone still filters the bulk of
its matches afterwards. A roster row timed on that shape only would read as settled while the shape
the probe actually wants went unmeasured. Second, the correlated equality sits outside the term's
`SELECT DISTINCT`, so whether any index on the target is reachable at all depends on H2 pushing the
predicate through the `DISTINCT` into the inlined body; if it does not, every shape times identically
and a roster row reading "measured, nothing moved" records the wrong cause.

So the measurement is three timings beside the no-index floor, on both the consumer store and the
twelve-unit fixture `DerivedReadCostTest` runs: `(graph_name, payload_type_name)`; a
`root_operation`-carrying shape, `(root_operation, graph_name, payload_type_name)`, which lets the seek
bind the constant too; and, as the baseline that separates an unhelpful coordinate from an unreached
index, the same probe timed with the `DISTINCT` removed from the term's text. That last one is a
measurement variant only, never a shipped edit. If a shape moves a reader it ships with a comment
naming that reader, on `ix_spelled_table_spelling`'s model, saying which shapes were timed. If nothing
moves, the relation joins `NO_INDEX` with the figures, the shapes timed, and what the `DISTINCT`
baseline showed, so the next reader can tell an unhelpful coordinate from an index the plan never
reached. The 327 ms and 261 ms above were taken with no index declared, so they are the floor a roster
row would stand on, and an index would have to earn its cost on every refresh on top of them.

Nothing else about the registration is a new mechanism: it is the established view-rename-plus-target
pair, on the `intent_spelled_table_live` / `intent_spelled_table` model, with the existing relation and
column comments moved onto the table verbatim and the standard materialization sentence appended.
`MaterializeRegistryGateTest.targetsAreShapedLikeTheViewsThatFillThem` is what closes the column shape,
the refresh being `INSERT INTO target SELECT * FROM source`.

Measurement provenance and one failed instrument are recorded in
`roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`. Read it before re-taking anything: a
synthetic fixture does not reproduce the per-driving-row re-derivation, so a figure taken on one would
rank the levers wrongly and read as measured.
