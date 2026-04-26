---
title: "Clarify `FkJoin` direction semantics"
status: Backlog
bucket: architecture
priority: 11
---

# Clarify `FkJoin` direction semantics

`JoinStep.FkJoin.sourceTable` is written to the traversal-origin table in `BuildContext.synthesizeFkJoin:473` and `parsePathElement:559-560`, contradicting the docstring at `JoinStep.java:70-72` (which claims it resolves to the FK-holder table). Currently dead data (zero readers today) but was a bug magnet for the first candidate reader (the cardinality-driven `deriveSplitQueryBatchKey` helper, shipped under *Single-cardinality `@splitQuery` support* in Done).

Options: fix construction to match the docstring (low risk, field unread); rename to `originTable` and add a derived `fkOnSource()` / `parentHoldsFk()` helper; or remove the raw field altogether since no reader needs it. Add a construction-time invariant check whichever direction wins.
