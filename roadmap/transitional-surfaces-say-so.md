---
id: R810
title: "Transitional surfaces say so where a reader arrives, and say why"
status: Spec
bucket: docs
priority: 5
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Transitional surfaces say so where a reader arrives, and say why

## Problem

A contributor opening the architecture docs meets the classification taxonomy first and has no
way to tell it documents the half of the system that is leaving.

The docs do say it. `code-generation-triggers.adoc` calls the walk "the transitional producer
surface of the strangler migration" and links the pipeline overview, and the pipeline overview
devotes a whole `The strangler frame` section to what that means today. The problem is where
those statements sit relative to where a reader arrives. The taxonomy page says it in its third
paragraph, after its title and its opening sentence have both introduced it in neutral present
tense as "a guide to how GraphQL schema patterns drive Graphitron's code generation". The
pipeline overview says it on a page the reader has not opened. Every path in gets there first:

* `docs/architecture/index.adoc`, the Reference quadrant blurb, names "the classification
  taxonomy and what each generator emits" as the first of four things, unmarked.
* `docs/architecture/reference/index.adoc` lists it as bullet one; the fact store schema, which
  is the model of record, is bullet six and last.
* `README.md` and `roadmap/README.md` both put it in the three-page first-time-contributor
  reading order, unmarked.

The second half of the gap is worse, because no page closes it. A reader can learn *that* the
walk is transitional and never learn *why*. The measurement that redirected the work onto a
relational core is in `docs/history/road-to-the-relational-core.adoc`, which the architecture
index links under "how the architecture got here" and which neither the taxonomy page nor the
pipeline overview's strangler frame links at all. That page states its own reason for existing in
those terms: the retired approaches are the project's most expensive knowledge, worth stating
once so they are not re-proposed by whoever did not read the item that retired them. A transition
whose rationale is one unlinked page away is one a newcomer argues with rather than joins.

Deleting the taxonomy page was considered first and rejected. It is a rendered view over the live
`@classified` corpus, not dead prose: it includes a fragment rendered from every corpus document
carrying a projection, so the file cannot be removed while the corpus publishes through it, and
its two halves have opposite lifetimes anyway. The legacy taxonomy retires with the walk; the
corpus view outlives it. Explaining the transition is the change that helps a reader now and costs
nothing that the eventual split would have to undo.

## What changes for a reader

Someone landing on the architecture docs sees, before they have chosen a page, that the
generator has a model of record and a surface being drained, and which is which. If they open the
taxonomy anyway, its first screen says the same thing and links the one page that explains how it
got that way. Nothing they read stops being accurate: the taxonomy describes live behaviour, and
saying it is transitional is not a hedge about its correctness.

## Implementation

**1. The entry points mark it, and lead with the model of record.**

`docs/architecture/reference/index.adoc`: the fact store schema moves off the bottom of the list
to the top, since it is the reference for the store every new fact lands in; the taxonomy bullet
keeps its own description and gains the transitional marker.

`docs/architecture/index.adoc`, the Reference quadrant blurb: same reordering in prose, so the
quadrant card and the page it links agree about what comes first. The "You came here because…"
entry for the taxonomy already reads "every variant the transitional classification walk
produces" and needs nothing.

`README.md` and `roadmap/README.md`: the first-time-contributor reading order marks the taxonomy
as the transitional surface rather than presenting it flat beside the principles.

**2. The taxonomy page leads with it.**

`code-generation-triggers.adoc` gains a lead paragraph above `== How Classification Works` that
says three things in this order: the page documents the transitional producer surface of the
strangler migration, everything on it is accurate and live today, and the walk is being drained
one consumer at a time with new facts landing only in the store. The existing paragraph-three
statement folds into it rather than being duplicated; what stays in place is the source map and
the taxonomy itself.

**3. The why gets linked from both present-tense pages.**

The taxonomy page's new lead and the pipeline overview's `The strangler frame` section each link
`docs/history/road-to-the-relational-core.adoc` in one clause naming what it answers: what was
measured, and what the measurement redirected. One clause, not a summary, because the history
page owns the account and a paraphrase beside it is a second version to keep in step.

**4. The marking is a stated form, not four ad-hoc sentences.**

The store's DDL already has this convention at relation grain: the `walk_` and `rejection_`
charters carry their own retirement clocks, `walk_` saying that when the walk is gone the family
has no referent. The docs equivalent gets written down once, in `docs/architecture/index.adoc`
beside the quadrant grid or in the reference index, so the next transitional surface is marked
the same way instead of the question being reargued. The form should say what a marker has to
carry: that the surface is transitional, that it is accurate today, and where the rationale
lives.

## Tests

No new gate, and the item should say so rather than inventing one. What the four call sites need
is that they agree with each other and with the pipeline overview, which is a claim about prose
that no cheap check reads; a marker-presence gate over a hand-kept page list would be a list to
fall behind rather than a check. The existing guards already cover the mechanical half: the
roadmap-tool link checks resolve the new xrefs, `AdocXrefAnchorCheckTest` holds the anchors, and
`SchemaIdentifierDriftCheck` holds any relation name the new prose cites. The unenforced half is
disclosed here on the precedent the fact model page sets for claims whose enforcer does not close
them.

## Open questions for the reviewer

* Whether reordering the reference index is in scope or is a separate call. It is the change most
  visible to a reader and the one least about "explaining the transition", so a reviewer may
  reasonably want it split out.
* Whether the stated form belongs on the architecture index or the reference index. The index is
  where a reader meets the quadrants; the reference index is where the marked page actually
  lives, and a convention stated on the index it does not govern is one nobody reads.

## Non-goals

Retiring the taxonomy page, splitting the corpus view onto its own page, or moving where the
rendered fragments land. Those follow the walk's own clock and are worth their own item when
the first consumer group finishes re-sourcing. Retiring the classification walk itself is a
strangler completion of an entirely different size and is not this.
