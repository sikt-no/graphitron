---
id: R334
title: "Generated @condition arg extraction is an unreadable nested-ternary one-liner"
status: Backlog
bucket: Backlog
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-06-18
last-updated: 2026-07-15
---

# Generated @condition arg extraction is an unreadable nested-ternary one-liner

Every generated `@condition` argument is inlined as a nested ternary directly in the WHERE
chain, e.g. `env.getArgument("filter") instanceof Map<?, ?> map1 ? (String) map1.get("brukerId") : null`,
repeated once per argument across a single `.and(...)` term. A method with several condition args
renders as one dense, hard-to-read, hard-to-breakpoint expression (flagged by a consumer as "an
eyesore" that "violates our principle of readable and debuggable code"). R330 made the surrounding
`.and(...)` chains and the FK-target `EXISTS` multi-line, but did not touch the per-argument
extraction, which is cross-cutting: it lives in `ArgCallEmitter.buildArgExtraction` and feeds every
WHERE-emitting site (the `QueryConditions` shim plus the inline / lookup / split fetcher emitters).
The fix likely extracts each argument into a named local (a clearly-typed `var <name> = ...`)
before the call, or routes through a small generated helper, so the call site reads as
`Conditions.method(table, brukerId)` and each extraction is independently debuggable. Scope:
emitter-only, generated output changes shape but not behaviour; pipeline tests must not assert on
generated method bodies, so coverage stays at the compile/execution tier.

R85 (`helper-emission-non-fetcher-hosts`) reshapes the same method: it fixes the
`ContextArg` arm of `ArgCallEmitter.buildArgExtraction` that fails to emit the
`graphitronContext` helper on non-fetcher hosts, while this item extracts the
per-argument extraction into named locals or a helper for readability. Whichever
lands first changes the other's diff; sequence knowingly (distinct deliverables,
not a merge).
