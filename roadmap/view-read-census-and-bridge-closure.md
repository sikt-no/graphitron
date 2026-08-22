---
id: R801
title: "View-read census and closure of the declared family bridges"
status: Backlog
bucket: architecture
depends-on: [family-page-introductions]
created: 2026-08-21
last-updated: 2026-08-22
---

# View-read census and closure of the declared family bridges

The family-page item declares the base facts: `meta_family_bridge`, the sanctioned normalization
crossings, authored in the DDL and resolve-gated only. This item derives over those declarations
and closes them against what the views actually do. Layers, in order:

**The census.** `meta_view_read`, machine-written at boot in the `meta_materialize_dependency`
one-writer pattern: one row per view and relation its stored definition directly reads, produced
by the parse walk `MaterializeDependencies.relationsReadBy` already implements (jOOQ's parser
over the stored `VIEW_DEFINITION`, qualified-name filtering so aliases and CTE names cannot mint
rows). The walk is `private static` today and must be lifted or widened. As a keyed `meta_` base
table the relation needs its own case in `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`,
whose `meta_` arm hard-codes the two materialize relations and defaults the rest to `graph_name`.

**The crossing gate.** Every view whose census rows span two or more families argues itself into
exactly one register: the declared bridge roster, or a keyed-crossing exemption register this
item adds (one row per multi-family view whose meetings are plain equality on shared keys or
reads through a registered bridge). Exemption polarity throughout, both directions closed. A
bridge row names the relation a consumer reads, per the declaring item, so for a registered
reduction the gate resolves the row through `meta_materialize` to its source view before
consulting the census. The Spec-review of the declaring item counted roughly 46 multi-family
views (textual approximation), nearly all `intent_`; at that size the exemption register's
`reason` should be a small closed vocabulary of crossing kinds, each kind's sentence stated once
on the column comment, with a free note only where a row differs, rather than dozens of
paraphrases no gate can check. Recorded here as the reviewer's recommendation and this item's
starting shape.

**The predicate analysis, last.** The census sees which relations a view reads, never how it
compares their columns, so a view can hold a truthful exemption row and still add its own
function-mediated spelling match beside the sanctioned bridge. Walk each view's parsed definition
with the same query object model and reject any comparison applying functions to columns tracing
to two different families' relations unless it occurs inside a bridge-registered view. The Spec
must pin what counts as a crossing predicate (which function applications, whether casts count,
how a column traces to its source relation through aliases, subqueries and CTEs) and whether the
exemption register's kinds can then be verified rather than trusted. Plain column equality on
shared keys stays ungated; those are declared paths, not rules.

The declaring item's population sweep reconciles all seventeen relations whose view bodies hold
a function-mediated match site, and this analysis inherits the complete list (the declaring
spec's Population section carries the full reasoning per site). Beyond the four rostered rows:
five disclosed normalizations to classify rather than reject, among them the SDL
type-expression peel spelled at three sites, the settled case-fold convention stated on
`intent_resolved_node_key_column.column_name` as nobody's rule and applied at two readers, the
bean-prefix strip (`intent_class_member_slot`, a real forkable rule with no crossing), and the
rostered key-reference rule's coordinate-shaped restatement in
`intent_argument_reference_step_hop`, which the DDL assigns to the field-site view and pins with
an anchor test. Five more fail a membership test outright or match nothing, `diagnostic`'s
seven directory-rendering `REGEXP_REPLACE` calls in a six-family view being the conspicuous
case the walker must not trip over. Two design questions ride with the list, this item's to
answer: whether a normalizer that is not a crossing (the bean strip) gets its own register
beside the bridge roster, and whether the settled convention is reified as a declarable fact so
its applications can be gated rather than recognized case by case.
