---
id: R497
title: "Pin or drop the FederationSpec.URL caller census in its class javadoc"
status: Backlog
bucket: docs
priority: 7
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Pin or drop the FederationSpec.URL caller census in its class javadoc

`FederationSpec`'s class javadoc carries a hand-maintained caller census ("three callers reach for it: `TagLinkSynthesiser`; `FederationLinkApplier`; and `GraphitronSchemaBuilder`") that has already drifted from the code. The actual code consumers of `FederationSpec.URL` are `TagLinkSynthesiser` and `GraphitronSchemaBuilder`; `FederationLinkApplier` consumes it only indirectly via the registry contents (its own javadoc says so) and names it solely in a javadoc cross-link, and `ScalarTypeResolver` references it in javadoc but is omitted from the list. An enumerated caller census cannot be repointed to a single `{@link}` and keeps drifting as callers come and go. Either drop the census (state only what the constant is) or replace it with a form something mechanical breaks on when the caller set changes.

Surfaced by the R483 javadoc drift audit.
