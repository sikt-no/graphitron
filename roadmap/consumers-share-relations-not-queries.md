---
id: R684
title: "fact-model doctrine: consumers share relations, not queries"
status: Backlog
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-16
last-updated: 2026-08-16
---

# fact-model doctrine: consumers share relations, not queries

The store-read discipline that every migration keeps re-deriving is written down only in transient
places. The rule: consumers of the fact store share the relations and derived views, never the
queries. Each consumer formulates its own reads against the `StoreHandle`, asking its own question
of the views, even where the SQL comes out similar; a read two consumers genuinely both need is the
signal for a missing derived view, which lands in the store at its own grain, not in a shared
query-helper layer between the store and its readers. That layer is the trap: a consumer-shaped
accessor API that re-grows the model's read surface one tier down, so consumers read the layer
rather than the store, which is the current problem with the walk wearing a new name.

The LSP migration settled the rule ("what they share is the relations, not the query", stated on
the catalog-shaped completion arms) and the planner/emitter conversion imports it, but both are
roadmap items, and roadmap items ship and get deleted. The durable home is
`docs/architecture/explanation/fact-model.adoc`, which already carries the neighbouring halves of
the doctrine: "Derived reads are views, not stored facts" says what a read is, and the row-assertion
check ("say what a single row asserts, without naming a consumer") bans consumer-shaped relations.
What is missing between them is what consumers may share. The addition is one short section beside
"Derived reads are views, not stored facts", carrying the rule, the missing-view signal, and the
smell (a shared reader whose signature is one consumer's convenience), with the LSP catalog arms as
the exemplar. `development-principles.adoc` sits at 3,497 of its 3,500-word enforced budget, so if a
principles-level pointer is wanted it must displace text; the item should decide whether a one-line
xref from "One model, many views" is worth the displacement or the fact-model section suffices.

Enforcement is review-only at filing: nothing structural distinguishes a shared reader from any
other class beside the store. Whether a meta-test can pin it (for instance, a guard on what may
take a `StoreHandle` parameter in a consumer package) is for the Spec to decide, per "every
invariant has an enforcer"; a review-only label is an invitation, not an end state.
