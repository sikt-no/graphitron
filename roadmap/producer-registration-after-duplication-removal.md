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
inside a `WITH` body, which is how the carrier reaches this relation; the sibling item adds that
synthetic case, so this one inherits it rather than needing it.

Measurement provenance and one failed instrument are recorded in
`roadmap/audits/2026-08-27-carrier-filter-redundancy-probe.md`. Read it before re-taking anything: a
synthetic fixture does not reproduce the per-driving-row re-derivation, so a figure taken on one would
rank the levers wrongly and read as measured.
