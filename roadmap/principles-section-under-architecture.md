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

The item is the structural question, not any one principle's content: should
`docs/architecture/` grow a principles section beside `explanation/`, `how-to/` and
`reference/`, gathering the strategic and development principles under one nav entry, or is
the current placement right and only the wayfinding lacking (an index page that routes to
the three homes)? A Spec should weigh the Diátaxis framing the tree currently follows
(principles are arguably explanation), the cost of moving pages that other docs and
`CLAUDE.md` deep-link to, and the `development-principles.adoc` word-budget gate, which
exists precisely because that page is loaded on every design consult and would argue
against merging more content into it rather than beside it.
