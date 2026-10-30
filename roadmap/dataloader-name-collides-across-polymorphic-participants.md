---
id: R1132
title: "Same-named split child fields on different polymorphic participants share one DataLoader and resolve to null"
status: Backlog
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# Same-named split child fields on different polymorphic participants share one DataLoader and resolve to null

## Goal

A query that selects the same `@splitQuery` child field on two participants of one polymorphic
field, through two inline fragments, resolves each participant's children from its own table, as a
query selecting either fragment alone already does. Today every participant after the first gets
null, and on a non-null child the null bubbles until the response loses `data`.

## The defect

A generated split or child fetcher names its DataLoader from the response path alone:
`String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`, emitted by
`DataLoaderFetcherEmitter` (`DataLoaderFetcherEmitter.java:206` at the time of writing). When an
interface's participants each declare a child field of the same name, every participant's fetcher
computes the same name, `computeIfAbsent` hands all of them the loader the first one created, and
that loader's batch function joins the first participant's table. The other participants' keys
match nothing.

Measured in kreg: `Utdanningsprogram`, `Programomraade` and `KregFag` implement `Kompetanseoppnaelse`
over three views, and each declares `eier`, `utdanningsspesifikasjon` and `merknader` as
`@splitQuery @reference(path: [{condition: ...}])` with a condition overload per participant. The
root field is a multitable interface without `@discriminate`.

```graphql
{ kompetanseoppnaelseByNasjonalId(nasjonalId: "50920275521") {
    __typename
    ... on Programomraade { utdanningsspesifikasjon { kode } }
    ... on KregFag        { utdanningsspesifikasjon { kode } } } }
```

All 79 `KregFag.utdanningsspesifikasjon` come back null, in either fragment order. Either fragment
alone is correct, and aliasing one side (`u: utdanningsspesifikasjon`) makes both correct, which
places the cause in the loader name. The same happens under a `@referenceFor` multitable child
(`barn { ... on Programomraade {...} ... on KregFag {...} }`). It blocks kreg's move to
Graphitron 10: opptak's production query to kreg selects these fields on both types under `barn`.

## Direction

Key the loader on the field coordinate rather than the path alone, for example by adding the parent
type, `env.getExecutionStepInfo().getObjectType().getName()`. The same path-only pattern builds a
name or key in `TenantDslEmitter`, `ConnectionRuntimeClassGenerator` and
`QueryNodeFetcherClassGenerator`; the Spec decides for each whether two parent types can meet at one
path there. The execution-tier regression case is a multitable interface whose participants share
a `@splitQuery` child field name, selected through two inline fragments in one query.

A wrong answer on a valid schema, so a regression ahead of planned work.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
