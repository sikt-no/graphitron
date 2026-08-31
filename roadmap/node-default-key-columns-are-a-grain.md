---
id: R902
title: "@node key columns defaulted from the primary key are a grain table, not a read-time tier"
status: Backlog
bucket: architecture
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# @node key columns defaulted from the primary key are a grain table, not a read-time tier

An `@node` that omits its key columns takes the bound table's primary key in declaration order, which
the user manual documents as the default. Nothing captures that. It is the third arm of
`intent_resolved_node_key_column`, reconstructed on every read by joining the type's resolved binding
to `sql_primary_key` and `sql_constraint_column`, under a `DENSE_RANK()` window that picks the
winning tier.

**The obvious home for it is the wrong one, which was established by measurement rather than
argument.** The natural move is to have the gatherer that decodes `@node` write the default beside
the columns an author spelled, now that the catalog is captured before it runs. It cannot: a default
needs to know which table the type is bound to, and the transitive read closure of
`intent_resolved_node_key_column` is forty relations, three of which are `jvm_declared_type_ref`,
`jvm_method` and `jvm_method_parameter`. The binding's routine arm resolves a bound routine's return
type through the classpath census, which a later gatherer captures. The rows do not exist when the
decode runs.

**So the fact belongs to the gatherer that runs after the census, and the shape it should take there
is a grain table.** That gatherer already computes and stores a register of relations, and the
standing criticism of that register is that its targets are keyless copies of view bodies rather than
tables keyed on what a row is about. This relation has an obvious grain: the graph, the type and the
key position. Landing it keyed rather than as another keyless copy is the difference between adding
to the problem and demonstrating the fix.

**What it is worth, and the honest bound on that.** Three joins and a window function leave every read
of the resolved key. The binding relation the arm joins to carries a fifty-fold planner degradation
reported separately, so removing one of its readers is worth more than the join count suggests.
Neither figure has been measured for this change specifically, and the item owes that before it
claims anything.

**Two questions to settle in Spec.** Whether the other two tiers move with it or only the defaulted
one, since a relation half captured and half reconstructed is the shape this schema keeps finding
expensive. And whether a disagreement between the tiers, which the resolution view's own comment says
nothing records today, becomes a defect relation at the same time.
