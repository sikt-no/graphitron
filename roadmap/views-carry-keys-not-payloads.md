---
id: R698
title: "fact-model doctrine: a view carries keys and its own products, and prose only where it was captured"
status: Backlog
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# fact-model doctrine: a view carries keys and its own products, and prose only where it was captured

## Problem

The store has a modelling discipline for what a view may contain, it is followed in the DDL, and it
is written down nowhere. The rule has two halves. A view merges same-grain sources, re-grains by
grouping, and carries keys plus columns that are its own product; a consumer joins the view to the
relations holding the payloads it wants and projects what it needs, so a view never embeds a
denormalized payload to save its reader a join. And a computed column earns its place only when the
calculation needs data from more than one of the joined relations; a calculation over a single base
relation's own columns belongs on that base relation instead. Because the rule is unwritten, a
consumer asking for its answer in one query pulls toward flattening the answer into the model, which
is the private-model smell arriving through the read path rather than through a Java taxonomy.

## The discipline is already in the DDL, three times

* `intent_column_match_claim.column_name`: "With the three columns above this is `sql_column`'s full
  key; the column's other facts (its jOOQ name, type, nullability) are one join away, per the
  referenced-side discipline `sql_referential_constraint` states." The rule, already named as a
  discipline.
* `intent_resolved_field_claim`: "The projection is the claim key plus tier, no trigger, decoded or
  witness component, so nothing goes nullable by kind; a reader wanting provenance joins the tier's
  own view."
* `intent_column_match_claim.matched_name`: "The classifier's own product rather than a projection of
  either input, which is what earns it a column here." The earning test for a computed column,
  already phrased as one.

## What it would have prevented

A union view over the six per-classifier relations, collapsing their decoded components into a
`(kind, value)` pair so one hover could be answered in one query. Every column nullable by kind, no
two kinds sharing a meaning, and the shape justified entirely by one consumer's rendering. The
`intent_resolved_field_claim` comment refuses it in as many words, and the proposal was made anyway
because the refusal lives in a column comment rather than in the doctrine.

## The prose half

Prose in the store is legitimate exactly where it was captured: `rejection_validation_error.message`
and `lint_finding.message` are transcripts of what a walk and a linter said, recorded as data.
Computed prose belongs to the surface, because composing a sentence needs a context the store does
not have. The build report prefixes the coordinate because a console has no cursor; a language
server has a range and should not repeat it in the text; an agent reading rows wants the dimensions
as columns. Same violation, three correct sentences, so there is nothing for the store to share.

An earlier framing of this item proposed a second category, projection views, holding display shapes
whose prose two consumers must render identically. Withdrawn: no two surfaces need identical prose,
and the requirement was invented to justify keeping a computed message where it already sat. The one
computed-prose site in the schema is `intent_authored_claim_conflict.message`, whose removal is
`conflict-message-leaves-the-intent-view.md` (R696).

## Home, and the sibling editing the same file

`docs/architecture/explanation/fact-model.adoc`, in or beside "Derived reads are views, not stored
facts", which already owns the shape of a single derivation. R684
(`consumers-share-relations-not-queries.md`) is Ready and inserts its own section into the same file
between "One base, many views" and "The back half". The two rules are complementary and neither
subsumes the other: R684 governs which module may read what, this one governs what a view may
contain. Whichever lands second should read the other's section rather than restate it, and the
placement of the two should be settled in one pass rather than negotiated twice.

## Not mechanically enforceable, and worth saying so

Neither half is checkable by a gate. "Is this column a payload or this view's own product" is a
modelling judgement, and a `(kind, value)` pair is structurally indistinguishable from a legitimate
narrow relation. The earning test and the captured-versus-computed line are what a reviewer applies;
the doctrine's value is that they are citable instead of re-derived per item.
