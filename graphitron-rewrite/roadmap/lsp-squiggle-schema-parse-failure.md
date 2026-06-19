---
id: R345
title: "Surface schema parse failures as LSP red squiggles"
status: Backlog
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Surface schema parse failures as LSP red squiggles

Follow-on to R344 (which fixed the dev-log noise). A syntactically invalid schema is the canonical `InvalidSchema` case and the one diagnostic a developer *can* fix by rewriting their schema, yet today it produces no editor diagnostic: on parse failure the snapshot is demoted to `Built.Previous` and `Diagnostics.validatorDiagnostics` (`Diagnostics.java:149-179`) silences all squiggles (R139 freshness-aware silence: "a red squiggle the developer cannot fix by rewriting their schema is the noise we are trying to avoid"). A syntax error is precisely the carve-out that policy should admit.

R344 positioned the transport for this: `SchemaParseException` (`graphitron/.../rewrite/SchemaParseException.java`) carries a nullable `SourceLocation location` and a `String brief`, which are the offending site this item needs to place a squiggle. This item is the consumer that justifies carrying those fields; until it lands, only `location` is non-derivable (the `brief` is a slice of `getMessage()`), so the implementer should decide whether to keep `brief` as a carried field or recompute it here.

Scope sketch (decide deliberately at Spec): reshape `GraphQLRewriteGenerator.buildOutput()` so a parse failure returns a report-only output (empty/previous artifacts plus a `ValidationReport` carrying the parse error) instead of throwing, and add a parse-error carve-out to the `Diagnostics` silence policy so the syntax-error squiggle survives the `Built.Previous` demotion. This changes `buildOutput`'s throw-vs-report contract, so it is not a free fall-out of R344. See the R344 entry in [`changelog.md`](changelog.md) (the spec is retired) for the full reasoning behind deferring it.
