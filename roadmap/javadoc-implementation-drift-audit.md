---
id: R483
title: "Audit javadoc for drift against current design and implementation"
status: Backlog
bucket: cleanup
priority: 10
theme: docs
depends-on: [javadoc-roadmap-reference-purge]
created: 2026-07-15
last-updated: 2026-07-15
---

# Audit javadoc for drift against current design and implementation

The rewrite made several large design pivots over the last two months, and javadoc written against earlier designs is likely to describe behavior that no longer exists: renamed or removed collaborators, retired passes, superseded control flow, contracts that have since inverted. Stale-but-confident javadoc is worse than none: it actively misleads a reader (human or agent) into a wrong mental model of the code. This item is a systematic sweep comparing doc comments against the current implementation and design, module by module, and rewriting anything that has drifted. The central hazard to design against is that a corrector will confidently rewrite a stale comment to match its own wrong understanding: every rewrite must be verified against the code and the current design docs, not against the old comment it replaces. This is unbounded semantic work and a natural fan-out (readers per module propose rewrites; each rewrite is adversarially verified against the implementation before it lands). Sequenced after R482, which shrinks and de-noises the surface first (much of the most-drifted text is exactly the historical roadmap narration R482 removes). Whether to run the audit as a multi-agent workflow is a deliberate scoping decision to make at Spec time, given the token cost.
