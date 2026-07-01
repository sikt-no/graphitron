---
id: R387
title: "Migrate TypeConditionsGeneratorTest off code-string assertions on generated method bodies"
status: Backlog
bucket: testing
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Migrate TypeConditionsGeneratorTest off code-string assertions on generated method bodies

`TypeConditionsGeneratorTest` asserts on generated condition-method bodies via
`method.code().toString()` + AssertJ `contains(...)` on literal jOOQ expression strings
(e.g. `assertThat(body).contains("table.FILM_ID.in(ids)")`). This contradicts the
rewrite design principle that *code-string assertions on generated method bodies are banned
at every tier* (`rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural
tier", and § "Compilation against real jOOQ is a test tier"): such assertions test
implementation rather than behaviour and break on every emitter refactor. The pattern predates
R380 (the existing cases trace to R375 / R79 / R50) and R380 extended it for the
`RemoteColumnPredicate` / correlated-`EXISTS` arm; in every case the emitted behavior is
already proven at the execution tier (`GraphQLQueryTest`, real rows against seeded PostgreSQL)
and locked at the compilation tier (`graphitron-sakila-example` against real jOOQ classes), so
the code-string cases are redundant maintenance burden. Migrate the whole file: replace
body-string assertions with structural assertions (method/parameter shape, `TypeSpec` /
`MethodSpec` structure — the `paramType` / call-type delegation tests already model this) and
lean on the execution + compilation tiers for body correctness. Scope is test-only; no generator
or model change. Worth confirming during Spec whether any body-shape invariant (e.g. the
`EXISTS` correlation direction or terminal-alias binding) is genuinely not observable at the
execution tier and therefore needs a non-string structural pin rather than outright removal.
