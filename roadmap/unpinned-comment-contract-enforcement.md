---
id: R527
title: "Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor"
status: Backlog
bucket: testing
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor

The comment-trimming sweep (`roadmap/javadoc-verbosity-alignment-sweep.md`) found load-bearing claims that could be neither deleted (a reader genuinely needs them) nor pinned to a live symbol, test, or docs page; per the sweep's rubric they were left verbatim and routed here. Each needs either an enforcer (a named test the claim can pin to) or a correction:

- `JooqRecordInstantiationEmitter#openDescent` javadoc asserts graphql-java's nested present-null coercion behavior (external-library behavior with no named execution test); add an execution-tier test that pins it.
- `ScalarTypeResolver#resolveFromDirectiveValue` javadoc justifies its design by citing a per-arm LSP `ClassNotFound` fix-it that does not exist in `graphitron-lsp` main sources; build the fix-it or restate the rationale without it.
- `JoinPathEmitter#emitCorrelationWhere` javadoc claims the empty-slot fallback's emitted `DSL.noCondition()` stub is runtime-throwing (a behavioral claim about generated output with no pin); demonstrate it in an execution test or correct the claim.
- `BuildContext` has two channel-rule comments numbered against a rule spec (section-sign references) that resolves to no live artifact; the rule-family names are pinned to fixtures, but the numbering needs a live home or removal.
- `Source.OnlyChild`'s row-correctness contract is explicitly marked machine-unenforced; add an enforcer or a named pin.
