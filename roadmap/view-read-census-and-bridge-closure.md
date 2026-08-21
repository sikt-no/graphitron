---
id: R801
title: "Predicate-level closure of the family bridge roster"
status: Backlog
bucket: architecture
depends-on: [family-page-introductions]
created: 2026-08-21
last-updated: 2026-08-21
---

# Predicate-level closure of the family bridge roster

The family-page item lands `meta_family_bridge` (the sanctioned normalization crossings),
`meta_view_read` (the machine-written census of every view's direct read set) and the
relation-grain crossing gate: a view reading across families must argue itself into the bridge
roster or the keyed-crossing register. That gate is deliberately coarse: it sees which relations
a view reads, never how the view compares their columns, so a view can hold a truthful
keyed-crossing row and still add its own function-mediated spelling match beside the sanctioned
bridge. This item closes that gap at predicate grain: walk each view's parsed definition
(the same jOOQ query object model the read-census walk uses), and reject any comparison that
applies functions to columns tracing to two different families' relations unless it occurs
inside a bridge-registered view. The Spec must pin what counts as a crossing predicate (which
function applications, whether casts count, how a column traces to its source relation through
aliases, subqueries and CTEs), the exemption shape for legitimate cases, and whether the
keyed-crossing register's reasons can then be verified rather than trusted. Plain column
equality on shared keys stays ungated; those are declared paths, not rules.
