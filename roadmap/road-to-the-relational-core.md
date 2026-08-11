---
id: R634
title: "The rewrite's architectural history as an explanation page"
status: Backlog
bucket: docs
priority: 2
theme: docs
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# The rewrite's architectural history as an explanation page

The architecture docs say what the generator is and why it is shaped that way, but nothing says
how it got here, and the record that would answer it is scattered across 354 changelog entries,
603 item files (most of them deleted on ship) and 14 dated audits. That gap costs three things.
A contributor meeting the fact store cannot tell which parts of the design are settled
conclusions and which are the current position in a sequence that has already moved four times,
so the DDL reads as arbitrary where it is actually load-bearing. The reasoning that retired a
mechanism is preserved only in the item that retired it, so a discarded approach gets re-proposed
by whoever did not read that item, and the "why not X" answers are the most expensive knowledge
the project owns. And the pivots themselves have a pattern worth stating once, since each was a
correct partial answer exposed by the next scale of use rather than a mistake corrected, which is
the difference between a project that looks like thrash and one that looks like learning.

A new explanation page under `docs/architecture/explanation/` should carry that history: the
sealed-leaf model and what it was right about, the June sequence in which the model was
re-theorised four times, the July functional-core work and the measurement that redirected it,
and the August move to relations. Two constraints shape it. It is history, not a second statement
of current behaviour, so it must point at the pages that own the present rather than restating
them, and it must not become a place where stale architecture claims survive contradiction by the
DDL. And it cites work by roadmap id, which the project's own conventions treat as transient, so
the citation form needs deciding at Spec: the ids of shipped items are permanently recoverable
from `roadmap/changelog.md`, while links to `roadmap/<slug>.md` dangle the moment an item ships.

Sequenced after R630, whose pages own the present-tense description of the fact architecture this
page refers back to.

