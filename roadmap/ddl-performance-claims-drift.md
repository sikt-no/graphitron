---
id: R831
title: "Re-measure the performance claims written into DDL comments"
status: Backlog
bucket: Fact model
priority: 3
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# Re-measure the performance claims written into DDL comments

The store's relation comments carry measured performance claims, and several of them steer the next
author away from a shape: this join must not be written that way, this expression must be projected
into a derived table first, this collapse is a two-orders-of-magnitude regression. Each was true when
it was taken. Nothing re-takes them, so a claim survives the change that retires it and goes on
steering. One has now been caught: `intent_argument_scope_table`'s comment argued at length against
joining the type binding onto a field's stripped type expression, and the registration of
`intent_resolved_type_binding` retired that hazard by making the far side of the join a table with
nothing left to re-evaluate. Re-measured, the shape the comment warned against is four times faster
than the shape it recommends, at either grain. The comment had been steering authors wrong for
several increments, and it was found by accident rather than by a check.

The store already owns an instrument for this, the read-cost gate's `EXPLAIN ANALYZE` harness, and it
already ratchets one class of claim: `meta_materialize`'s registrations are priced against their
readers on every build. What has no gate is the far larger population of measured claims written into
ordinary relation comments, which is where most of the store's performance reasoning lives. Worth
asking what a check could even look like here, given that a comment's claim is prose and its subject
is usually a shape that no longer exists in the file; the honest starting point may be a census of
which comments make a re-checkable claim at all, and whether the ones that do should instead become
rows a gate can read.

