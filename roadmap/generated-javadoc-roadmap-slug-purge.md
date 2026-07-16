---
id: R491
title: "Purge roadmap citations from documentation-emitting generator string literals"
status: Backlog
bucket: cleanup
priority: 20
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Purge roadmap citations from documentation-emitting generator string literals

A third habitat of transient roadmap citations, beyond comment/javadoc regions (covered by the javadoc reference purge guard) and user-facing rejection/deprecation message literals (the rejection-message slug purge): generator string literals that *emit* documentation into generated code. For example, `ConnectionRuntimeClassGenerator`'s `.addJavadoc("... (R45) ...")` calls bake roadmap ids into the javadoc of the generated runtime class, so a consumer reading their generated sources meets a stale roadmap reference they cannot resolve. The comment-scoped guard deliberately does not scan string literals, so these are out of its reach by construction. Scope: find generator string literals (`addJavadoc` / `CodeBlock` / emitted comment text) that cite a roadmap id or slug and rewrite them to state the fact or link a live symbol / published docs; consider whether the guard can extend to the documentation-emitting string subset without false-positiving on message literals. Sibling to the comment purge and the rejection-message purge.
