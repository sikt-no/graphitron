---
id: R805
title: "A principles section under docs/architecture"
status: Backlog
bucket: docs
priority: 6
theme: docs
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# A principles section under docs/architecture

The architecture docs have no principles section, and the principles the project actually
runs on are scattered across three homes with three registers: the strategic principles sit
at the docs root (`docs/graphitron-principles.adoc`, outside the architecture tree
entirely), the six development axioms sit under
`docs/architecture/explanation/development-principles.adoc`, and a growing family of
modeling rules lives inside `fact-model.adoc` (each with its enforcer named, a register
neither of the other two uses). A contributor asking "what are this project's principles"
has no one place to start, and a new principle has no obvious home, which is how rules end
up stated only in a roadmap item or a gate's failure message.

The fork is resolved (user decision, 2026-08-22): `docs/architecture/` grows a principles
section beside `explanation/`, `how-to/` and `reference/`, and the architecture principles
migrate into it. `development-principles.adoc` moves; its word-budget gate moves with it
unchanged, since the budget is about consult-time context cost, not about where the file
sits. Whether the strategic root page (`docs/graphitron-principles.adoc`) moves too or is
linked from the new section's index is a Spec question; the user's decision names
architecture principles, and the root page is also linked from the user manual, the FAQ,
the top-level README and the site footer, so moving it has a wider, consumer-facing blast
radius than moving the architecture page.

The migration is mostly link surgery and the Spec should scope it honestly: fifty-plus
files reference the `development-principles` path or its anchors, including Java javadoc
in the generator (`TypeFetcherGenerator`, `ServiceCatalog`, `FieldBuilder` and siblings),
`DocSizeBudgetTest`, which pins the file's path as well as its budget, `CLAUDE.md`, the
`srp` and `reviewer-prompt` skills, the `principles-architect` agent definition, several
architecture pages' xrefs, and a long tail of roadmap-item bodies (which are transient and
need no sweep, but the permanent artifacts do). The roadmap-tool link checker
(`LinkTargetRoundTripTest` territory) and the docs build are the enforcers that catch a
missed xref; the Java-side path mentions sit in prose javadoc, so they need a grep sweep,
not a gate.
