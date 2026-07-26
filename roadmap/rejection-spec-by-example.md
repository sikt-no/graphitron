---
id: R544
title: "Declarative mechanism for the rejection rows the classified corpus cannot hold"
status: Backlog
bucket: testing
theme: testing
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Declarative mechanism for the rejection rows the classified corpus cannot hold

The `@classified` corpus is success-only by design: it asserts that a coordinate classifies, and to what. Every failure-path assertion therefore stayed in the enum truth table, and that is now the largest block in `GraphitronSchemaBuilderTest`. A re-derivation on 2026-07-25 bucketed roughly 214 of its 400 `ClassificationCase` constants as rejection or warning rows: `UnclassifiedField` / `UnclassifiedType` outcomes, `RejectionKind` and typed `Rejection` assertions, directive-conflict and case-clash cases, excluded-field nulls, and emit-site warning checks. `roadmap/audits/classification-test-dsl-inventory.md` excluded them from the corpus migration with an explicit promise, "a separate mechanism replaces these", and no item ever owned that mechanism. This item owns it.

The gap matters because rejections are the most author-facing behaviour the classifier has. A rejection is what a schema author sees when their SDL is wrong, it carries a stable LSP code (the `graphitron.pivot.*` family and its siblings), and it is replayed on three surfaces: the build's `ValidationReport`, the LSP squiggle, and the MCP `diagnostics` tool. Yet the specification of which SDL shape produces which rejection lives in a Java assertion lambda, unreadable to anyone asking "what will graphitron reject and what will it tell me", which is exactly the readability argument that motivated the success-side corpus. The prose counterpart is also thin: `typed-rejection.adoc` documents the taxonomy, not the shape-to-code mapping.

The obvious shape to evaluate first is the symmetric one: a test-only `@rejects(code: ...)` directive (or a fixture-level equivalent) declaring the expected rejection at the offending coordinate, validated SDL-side against the live code namespace the way `ClassifiedDsl.PRELUDE`'s enums are validated against the live arm sets, with the harness asserting the build rejects that coordinate with that code and nothing else. Spec owes the harder questions: rejections are not always coordinate-scoped (some are type-scoped, some fold several located rejections into one report entry), a rejection's identity is a code plus a location plus a message the tests should probably not pin verbatim, and some current rows assert warnings rather than rejections, which may want a separate declaration. R333 is relevant but not load-bearing here: it makes classification failure a `WalkerResult.Err` rather than an `Unclassified*` permit, so the mechanism should be designed against that direction of travel rather than against today's failure leaves.

Worth deciding at Spec whether the mechanism also renders, the way `ClassifiedDocTest` renders the success corpus into `code-generation-triggers.adoc`. A rejection catalog generated from the same fixtures would give the user manual a shape-to-diagnostic reference it currently lacks.

Out of scope: changing any rejection's code, message, or location; the input-side rows; the success-side vocabulary widening (its own item).
