---
id: R334
title: "Generated argument extraction is unreadable nested-ternary one-liners"
status: Backlog
bucket: Backlog
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-06-18
last-updated: 2026-07-24
---

# Generated argument extraction is unreadable nested-ternary one-liners

Every generated `@condition` argument is inlined as a nested ternary directly in the WHERE
chain, e.g. `env.getArgument("filter") instanceof Map<?, ?> map1 ? (String) map1.get("brukerId") : null`,
repeated once per argument across a single `.and(...)` term. A method with several condition args
renders as one dense, hard-to-read, hard-to-breakpoint expression (flagged by a consumer as "an
eyesore" that "violates our principle of readable and debuggable code"). R330 made the surrounding
`.and(...)` chains and the FK-target `EXISTS` multi-line, but did not touch the per-argument
extraction, which is cross-cutting: it lives in `ArgCallEmitter.buildArgExtraction` and feeds every
WHERE-emitting site (the `QueryConditions` shim plus the inline / lookup / split fetcher emitters).
The fix likely extracts each argument into an explicitly-typed named local (never `var`;
`GeneratedSourcesLintTest.emittedSourcesDoNotUseVar` bans it in emitted code) before the call, or
routes through a small generated helper, so the call site reads as
`Conditions.method(table, brukerId)` and each extraction is independently debuggable. Scope:
emitter-only, generated output changes shape but not behaviour; pipeline tests must not assert on
generated method bodies, so coverage stays at the compile/execution tier.

## Expanded scope (2026-07-24 audit)

A full audit of the `graphitron-sakila-example` generated tree found the same
expression-over-statement pattern beyond `@condition` extraction; this item now covers all of it
(same emitter family, same fix shape: hoist to explicitly-typed named locals per path segment,
named after the GraphQL argument or column):

- Deep input-path extraction in fetchers and `$fields` mappers: nested
  `instanceof Map/List` ternary chains up to ~600 characters on one line
  (`QueryFetchers.filmsByNestedListPath`, the two ~330-char `.where(...)` arguments in
  `occupantsByFilter`, `types/Store.$fieldsGrouped`). Some sites re-check the same
  `instanceof` twice or test `in != null` after `in.get(...)` was already dereferenced; the
  locals-first rewrite removes the redundancy. Note the emitter already generates typed
  `inputs/*.fromMap` classes for many of these shapes and then ignores them, re-digging raw
  maps at the use site; reusing the input classes where one exists deletes the pattern
  outright.
- Insert-value ternaries: `in.containsKey("x") ? DSL.val(...) : DSL.defaultValue(...)`
  emitted once per column per mutation (39 occurrences in `MutationFetchers`). A named local
  per column, or a tiny emitted `valOrDefault(in, "x", FIELD)` helper, restores the column
  list's readability.
- The polymorphic discriminator expression
  `DSL.field(table.getQualifiedName().append(DSL.name("content_type")), Object.class)` is
  rebuilt inline up to four times within a single method (29 occurrences across the root
  fetcher classes); bind it once to a named local per method.

R521 (`generated-output-hygiene-sweep`) tracks the complementary naming/dedup/hygiene findings
from the same audit and explicitly excludes statement-form defects in favour of this item.

R85 (`helper-emission-non-fetcher-hosts`) reshapes the same method: it fixes the
`ContextArg` arm of `ArgCallEmitter.buildArgExtraction` that fails to emit the
`graphitronContext` helper on non-fetcher hosts, while this item extracts the
per-argument extraction into named locals or a helper for readability. Whichever
lands first changes the other's diff; sequence knowingly (distinct deliverables,
not a merge).
