---
id: R493
title: "Strip transient roadmap ids from generated-output javadoc"
status: Backlog
bucket: cleanup
priority: 6
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Strip transient roadmap ids from generated-output javadoc

Several schema/facade/dev-executor generators emit javadoc into their generated output via `addJavadoc` string literals that embed transient roadmap ids: `ErrorRouterClassGenerator` ("a future query @error lift (R397)"), `GraphitronDevExecutorGenerator` ("Dev-loop query execution entry point (R428)"), and `GraphitronFacadeGenerator` ("the opinionated path (R429)", "owned-connection path (R429)"). Roadmap ids are transient (items renumber, ship, or get discarded), so these citations rot in the generated sources shipped to consumers. Because they live in string literals feeding `addJavadoc` rather than in real comment/javadoc regions, `RoadmapReferenceGuardTest` does not catch them (it is lexically scoped to comment/javadoc regions), and the R492 reference gate only inspects hand-authored sources. This is the generated-output analogue of the gap R482 closed for hand-authored comments. The fix must strip or restate each id; it is complicated by generated-source golden/pipeline tests that assert on the emitted javadoc text, so it needs a deliberate pass rather than a blind sweep.

Surfaced by the R483 javadoc drift audit.
