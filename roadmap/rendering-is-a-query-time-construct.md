---
id: R804
title: "Rendering is a query-time construct: state the principle in the explanation articles"
status: Backlog
bucket: docs
priority: 5
theme: docs
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Rendering is a query-time construct: state the principle in the explanation articles

R803 converts the six `LISTAGG` sites in the fact schema to rows and adds a build gate
denying string aggregation in the DDL. That is the mechanical attack. This item is the
documentation sibling: the explanation articles never state the principle those changes
enforce, so the next author who reaches for `LISTAGG` (or its next-of-kin: a sentence
assembled in SQL, an `ORDER BY` chosen for how prose reads) has a gate to bounce off but no
page that says why.

The principle, generalized past the one construct: *rendering is a query-time construct,
not a model construct*. The store states facts at a grain; converting several facts into
one presentation value (a delimited list, a sentence, an ordering chosen for reading) is
the consuming read's own business. `LISTAGG` and `STRING_AGG` are just the SQL spellings of
that mistake: they fold rows into a scalar the store cannot undo on read, cannot join on,
and whose shape belongs to whichever consumer's screen line it was built for. This is the
same failure "Name the row, not the question" guards against at naming time, appearing at
derivation time.

The line is not "no aggregation" and not "no strings", and drawing it precisely is most of
the item's value. Candidate contrast cases the articles should sanction explicitly:

* an arity column (`intent_bound_table.candidates`, the `targets`/`candidates` split) folds
  rows into a scalar, but the scalar is a relationally meaningful fact readers compare and
  filter on, and the rows stay beside it;
* the rendered coordinate string is a per-row render of the row's own key columns, grain
  preserved, already sanctioned narrowly by the key-discipline section (rendered, never the
  key);
* a captured `message` column is transcribed prose, a stratum-one fact about what a
  producer emitted, not a render the store performed.

Deliverable: a stated form of the principle in `fact-model.adoc` (with its enforcer named;
R803's gate once it lands, a disclosed "not mechanically enforced" gap until then) and a
gentle telling in `naming-the-row.adoc`, where the pantry metaphor extends naturally: the
pantry stores ingredients, not plated dishes. Exact placement (inside "Derived reads are
views, not stored facts", beside "Name the row", or its own section) is a Spec question.
