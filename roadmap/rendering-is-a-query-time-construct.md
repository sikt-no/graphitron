---
id: R804
title: "Composed columns are atomic and key-dependent: state the discipline in the explanation articles"
status: Backlog
bucket: docs
priority: 5
theme: docs
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Composed columns are atomic and key-dependent: state the discipline in the explanation articles

R803 converts the six string-aggregation sites in the fact schema to rows and adds a build
gate denying the constructs in the DDL. That is the mechanical attack. This item is the
documentation sibling: state the principle those changes enforce in the two explanation
articles, so the next author who reaches for `LISTAGG` (or its next of kin: a sentence
assembled in SQL, a set serialized for grouping) has a page that says why, not just a gate
to bounce off.

The first framing, "rendering is a query-time construct, not a model construct", proved too
blunt under a principles consult: the store as shipped legitimately holds renders (the
rendered coordinate string, the `_upper` case folds), and the worst offender defends itself
as a grouping key rather than presentation, `intent_authored_claim_conflict.directives`
calling itself "the canonical claim render for grouping" in its own comment. The sharper
discriminator is atomicity plus key-dependence, and most of it is already written on the
store's own columns:

* A column may carry an opaque value the store did not compose (a captured message, a
  transcribed docstring), provided nothing joins, groups or filters on it. Four DDL
  comments already state this line as "display material, never a dimension"; lift that
  vocabulary rather than minting a new one.
* A column anything joins, groups or filters on must be atomic to the engine and a function
  of its relation's own key. A count or arity passes
  (`intent_type_backing_conflict.candidates`); a collection serialized into a scalar fails
  (`class_names`, same relation, same key), because its element grain sits inside the value
  where no key, constraint or join reaches it and only string surgery gets it back. That
  pair, same relation and same key, is the whole lesson in one exemplar. This is also what
  makes R803's denylist coherent: `ARRAY_AGG` is no delimited string, but it is a
  collection in a scalar.
* A serialized set used as a canonical group key is the case that looks legitimate and is
  not: it answers only equality of the whole set, where the relational form answers that
  and membership too, for one join. Grouping by a set is a query's business; the store owes
  it the rows, not a canonical spelling of them.
* Order is admissible as data (a captured ordinal, a `position` column) and inadmissible as
  a rule copied from a consumer's vocabulary with nothing binding the copy, which is what
  the eight-branch `CASE` ladder restating a Java enum's declaration order is. That is
  "every invariant has an enforcer" rather than a rendering point, and stating it
  separately gives the doc a rule that survives whichever arm of R803's message fork wins.

Placement: inside fact-model.adoc's "Derived reads are views, not stored facts", in the
opening shape cluster beside the converse test, not a new section. One forward-linking
sentence from "Name the row, not the question": a column encoding a set means the row
asserts something about several things at once, so the one-sentence check cannot be
finished honestly at the column grain either. And one sentence scoping the `diagnostic`
exemption, which covers a relation's name and population and says nothing about what a
column may hold; the largest single offender sits on the exempted relation, so without that
sentence the next author reads `diagnostic` as pre-cleared. In naming-the-row.adoc, extend
the existing `intent_bound_table` worked example with the failing sibling rather than
adding a new example. Do not enumerate the sanctioned columns as a roster (an unguarded
census); state the discriminator, one exemplar per side, and let the DDL comments carry the
per-column arguments they already own.

Enforcer: R803's gate, written named-and-true rather than forward-looking. If this item
lands before the gate exists, the line is a disclosed gap explicitly marked closable,
unlike the Name-the-row section's unclosable one; landing the paragraph in the same window
as the gate commit avoids the page ever carrying the gap.

Two citations that lift the case past taste: "boundaries decode and encode" (a delimited
column is a wire format inside the interior, and `ClaimFacts` wrapping the string straight
back into a jOOQ `multiset` is the decode happening at the wrong place) and "one model,
many views" (`ClaimFacts` in graphitron-lsp and `SchemaQueries` in graphitron-mcp hold two
hand-maintained parsers of one encoding today, the shadow-taxonomy smell at column scale).

Out of scope: development-principles.adoc, whose preamble already delegates the store's
modeling discipline to fact-model.adoc and whose word budget is gated.

A finding for R803's reviewer rather than this item: once the message render's input is
named correctly (a Java enum's declaration order, which is not a captured fact), its arm 1
may fit the schema header's existing first post-capture reason, a derivation no view can
express, so the header extension R803 poses as an open question may be unnecessary.
