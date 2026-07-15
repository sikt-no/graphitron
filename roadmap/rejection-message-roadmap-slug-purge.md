---
id: R484
title: "Purge roadmap-slug references from rejection and deprecation message literals"
status: Backlog
bucket: cleanup
priority: 14
theme: diagnostics
depends-on: []
created: 2026-07-15
last-updated: 2026-07-15
---

# Purge roadmap-slug references from rejection and deprecation message literals

Roadmap-item citations do not only live in javadoc (R482's target); they also appear in *user-facing string literals*, specifically rejection and deprecation messages (e.g. `GraphitronSchemaValidator`, `FieldBuilder` deprecation text) that point the SDL author at a transient `roadmap/<slug>.md` file or an `R<n>` id. This is arguably a worse smell than the javadoc case: a rejection is meant to be a fact stating the replacement instruction, not prose pointing a consumer at an internal, transient roadmap artifact they cannot and should not read. Split out of R482 because the defect sits in a different habitat (string literals, not comments/javadoc) that R482's comment-only lexical guard deliberately does not scan, and because the fix touches user-facing message wording rather than internal documentation. Scope: find rejection/deprecation message literals that cite a roadmap id or slug and rewrite them to state the replacement/instruction directly (or link the published docs), consistent with the "rejections are facts, not prose composed at the detection site" principle. Sibling to R482 (comment/javadoc purge) and R483 (drift audit).
