---
id: R801
title: "View-read census and mechanical closure of the family bridge roster"
status: Backlog
bucket: architecture
depends-on: [family-page-introductions]
created: 2026-08-21
last-updated: 2026-08-21
---

# View-read census and mechanical closure of the family bridge roster

R800 lands `meta_family_bridge`, the authored roster of sanctioned cross-family normalization
crossings, with resolve-only gates: rows must name observed relations and rostered families, but
nothing checks that the roster covers what the views actually do. This item closes it
mechanically, in two steps. First, generalize the `MaterializeDependencies` AST walk (jOOQ parser
over stored view definitions, qualified-name filtering) from the materialize registrations to
every view in the schema, writing a machine-owned census of each view's direct read set at boot,
in the `meta_materialize_dependency` one-writer pattern. Second, gate on it: a comparison that
applies functions to columns tracing to two different families' relations is a normalization
crossing, and it must occur inside a bridge-registered view, exemption polarity, so a new view
that re-derives its own spelling match between families fails the build until it reads through
the registered bridge or argues itself in as one. The Spec must pin what counts as a crossing
predicate (which function applications, whether casts count, how a column traces to its source
relation through aliases and subqueries) and both closure directions (every registered bridge is
an observed crossing too). Plain column-equality joins on shared keys stay ungated; they are
declared paths, not rules.
