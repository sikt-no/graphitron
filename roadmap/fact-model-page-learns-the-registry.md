---
id: R758
title: "The fact model page never learns the materialization registry"
status: Backlog
bucket: dx
priority: 4
theme: docs
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The fact model page never learns the materialization registry

`docs/architecture/explanation/fact-model.adoc` is the contributor-facing home for the store's
derive-on-read doctrine, and it does not know that a registry now exists. No authored page under
`docs/` mentions `meta_materialize`, the `_live` naming convention, or the refresh entry point, and
the page's doctrine sentence still states the narrow rule ("materialization is sanctioned above where
a view cannot serve") that the registry widened: a relation may now be stored because a view
expresses its rule correctly and only too slowly.

The page already sanctions the mechanism, `INSERT INTO derived SELECT ... FROM <view>` on the capture
cadence, which is why nothing shipped is unsanctioned and this is a documentation gap rather than a
correctness one. What is missing is that a contributor sent to that page cannot learn from it how to
register a relation, or why two relations under one rule carry the names they do.
`Materializations`'s class javadoc points at that page for exactly that rationale, so the pointer
currently lands somewhere that does not answer.

Found by the independent Done-gate review of the materialization work, which correctly classified it
as out of that item's stated contract: the contract named three DDL paragraphs and all three moved.
