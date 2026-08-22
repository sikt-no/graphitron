---
id: R808
title: "A bridging-condition split-table execution case returns a second actor only in a full-module run"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# A bridging-condition split-table execution case returns a second actor only in a full-module run

`GraphQLQueryTest.splitTableField_bridgingConditionJoin_returnsActorsPerFilm` in
`graphitron-sakila-example` failed one full `mvnd install -Plocal-db` and passed the next, on an
unchanged tree. The assertion is an exact list of actor ids: it expected `[1]` and got `[1, 2]`, so
the read returned one row too many rather than timing out or erroring.

What makes it worth an item rather than a re-run is that the same code answers differently depending
on what ran beside it. Re-run alone the case passes; re-run as the whole `GraphQLQueryTest` class,
378 tests, it passes; the second full reactor build was green. No commit between the last known-green
full build and the failing one touched generator main sources, the model DDL, or this module, which
is what rules out a regression and leaves execution order or residual database state as the
mechanism. An execution-tier case whose answer depends on its neighbours is a case that cannot be
trusted either way: it will fail a green tree, and it will pass a broken one.

Where to start is what a second actor row means for this shape. The case is a `@splitQuery` field
whose join is bridged by a `@condition`, so the candidates are a bridging predicate that admits an
extra row when a row another case wrote is present (making the expected `[1]` correct only against a
pristine table), and a fixture that mutates shared state without restoring it. The full-module
failure is reproducible material: the failing run's ordering is recoverable from the surefire report
beside the passing one.

A second order-dependent failure surfaced in the same session, in `graphitron-lsp` and with nothing
in common with this one beyond being order-dependent; it is filed as
`roadmap/trace-static-state-leaks-between-cases.md`. Two in three full builds is what says the suite
has such cases rather than one unlucky test, which is the reason both are items instead of re-runs.

Found while holding the In Review gate on the inlay enforcer item, which is unrelated to this
module; filed rather than folded into that verdict.
