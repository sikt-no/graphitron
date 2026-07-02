---
id: R424
title: "Inline @reference field reads its filter/pagination args from the top-level env, silently dropping them"
status: Backlog
bucket: bug
priority: 7
theme: structural-refactor
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Inline @reference field reads its filter/pagination args from the top-level env, silently dropping them

An inline (non-`@splitQuery`) `@reference` list field with arguments silently ignores
those arguments at runtime. The generated condition and decode logic are correct, but
the argument *value* is read from the wrong `DataFetchingEnvironment`: the emitted code
calls `env.getArgument("filter")`, where `env` is the top-level operation field's
environment (the ancestor fetcher that builds the whole correlated-`multiset` tree), not
the inline field's own environment. The ancestor has no such argument, so
`env.getArgument(...)` returns `null`, the filter condition collapses to
`noCondition()`, and the field returns unfiltered rows. This is a data-correctness bug:
a client that passes a narrowing filter (or, worse, an authorization-relevant id filter)
gets back rows it did not ask for, with no error.

## Reproducer

Field `Studiekurv.kladder(filter: HentKladderInput): [Soknadskladd!]!` with
`@reference(path: [...])` (no `@splitQuery`), where `HentKladderInput` carries
`@nodeId` filter fields. Querying `megSomSoker { studiekurv { kladder(filter: { opptakId: ["...opptak 2..."] }) { id } } }`
against seed data whose only kladd belongs to opptak 1 returns that kladd anyway
(expected: empty). The sibling field `Soker.soknader(filter:)` behaves correctly for the
identical filter shape *only because* it is `@splitQuery`: `@splitQuery` gives it a
dedicated fetcher whose `env` genuinely is the field's own environment, so
`env.getArgument("filter")` resolves.

## Root cause

- `ArgCallEmitter.buildArgExtraction` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java`)
  unconditionally emits `env.getArgument($S)` (and the nested-Map traversal roots at
  `env.getArgument(outerArg)`). That is correct at root/`@splitQuery` fetcher call sites,
  where `env` is the field's own environment, but wrong at the inline call site.
- `InlineTableFieldEmitter` (`.../generators/InlineTableFieldEmitter.java`) builds the
  correlated-subquery WHERE by delegating to the shared arg emitter. In the generated
  `<Type>.$fields(sel, table, env)` method the field's own arguments live on the
  in-scope `SelectedField` local (already used for `sf.getSelectionSet()`); the fix is to
  source arguments from `sf.getArguments()` rather than `env`.
- The same defect hits inline pagination: `InlineTableFieldEmitter.java:210` emits
  `env.getArgument("first")` for the `first` limit on an inline list field, which is
  likewise read from the wrong environment.

## Scope / direction (to be firmed up at Spec)

Thread the argument source (the `SelectedField` local) into the inline arg-extraction
path so the inline emitter reads `<sf>.getArguments()` while root/`@splitQuery` call
sites keep emitting `env.getArgument(...)`. `SelectedField.getArguments()` returns
`Map<String, Object>` with the same shape `env.getArgument` yields for an input object,
so the downstream nested-Map traversal and decode helpers are unaffected. Cover both the
filter-condition path and the inline `first` pagination path, and add pipeline +
execution coverage for an inline `@reference` list field with a filter argument (the
existing `@splitQuery` coverage masks the bug).

Discovered via an opptak-subgraph reproducer (`MegSomSokerQueryIT.kladderOpptakIdFilterIsIgnored`)
on graphitron 10.0.0-RC23.
