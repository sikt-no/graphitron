---
id: R608
title: "Sweep the DirectiveConflict.directives contract over every producer site"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# Sweep the DirectiveConflict.directives contract over every producer site

`Rejection.InvalidSchema.DirectiveConflict.directives` now carries a stated contract on its javadoc:
every listed name is applied at the rejection's own declaration, and a remedy the author has not
written belongs in the prose. The contract was settled while typing the input-field resolution path,
which found the one site that violated it (the `@asConnection`-on-inline-`TableField` site listed the
absent `splitQuery`) and pinned the property at that site. One site is a spot check, not a contract:
the other producer sites are unpinned, and `mcp-aggregated-diagnostics` wants to count rejections per
directive, which is exactly what a counterfactual entry corrupts.

Sweep the property over every producer instead. The shape that would make it build-enforced is a test
that walks a corpus schema's `UnclassifiedField` / `UnclassifiedType` verdicts and asserts, for every
`DirectiveConflict` produced, that each listed directive is applied at that declaration; the
classification corpus is the natural source of fixtures. Worth checking at pickup whether the
type-level and argument-level sites can state the property as sharply as the field-level one, since
a rejection on a field may legitimately name a directive applied to its argument or its parent type.
