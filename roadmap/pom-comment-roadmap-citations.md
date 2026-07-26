---
id: R547
title: "Retire transient roadmap-ID citations from pom.xml comments"
status: Backlog
bucket: cleanup
priority: 4
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Retire transient roadmap-ID citations from pom.xml comments

The transient-citation rule in `CLAUDE.md` ("Javadoc conventions") is enforced by
`RoadmapReferenceGuardTest`, which scans Java comment and string-literal regions. Build
configuration is a third habitat that no scan reaches: nine `pom.xml` files carry roadmap-ID
citations in XML comments, spanning 22 distinct IDs, and the rot has already happened. The
Java-17 floor comment in `graphitron-jakarta-rest/pom.xml` explains why the module carries no
`@Test` classes by citing an item whose file no longer exists under `roadmap/`, so a contributor
reading that comment has no way to recover the reason.

These comments are load-bearing in the same way javadoc is: they explain why a plugin execution,
a `<release>` override, or a deploy-skip flag is shaped the way it is, and a reader with no
`roadmap/` directory (a consumer inspecting a published pom, or any contributor after the item
ships) gets a dangling reference instead of a rationale. The fix has the same shape the rule
already prescribes for javadoc: state the fact, or point at the durable doc page that carries the
rationale, and drop the citation. Whether the sweep earns a mechanical guard is a Spec-stage
question; the citation-recurrence check being scoped to prose documents leaves this habitat open
either way.

Surfaced by the Spec review of the agent-onboarding surface item, which corrects the same drift in
`CLAUDE.md` and `.claude/web-environment.md` and scopes poms out.
