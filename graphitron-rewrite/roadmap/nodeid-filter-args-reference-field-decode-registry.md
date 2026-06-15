---
id: R312
title: "Thread CompositeDecodeHelperRegistry through inline/split reference-field filter emitters"
status: Backlog
bucket: correctness
priority: 2
theme: nodeid
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Thread CompositeDecodeHelperRegistry through inline/split reference-field filter emitters

When a filter input used as the `filter:` argument of a **reference/list child field** (e.g. `soknader(filter: HentSoknadInput): [Soknad!] @reference(...)`) mixes `@nodeId`-decoded fields with `@condition` fields, codegen crashes. `@nodeId`-decoded filter args reach `ArgCallEmitter.buildNodeIdDecodeExtraction(...)` through call sites that pass a **null** `CompositeDecodeHelperRegistry`; that method throws by design when the registry is null (the decode must be lifted into a per-class helper, and only `QueryConditionsGenerator` currently owns and drains a registry). This regressed in the RC10+ rewrite; it worked on 9.3.0. The valid schema has no schema-only workaround.

Two parts. **Part A:** the inline and split reference-field filter emitters (`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, and the lookup-rows path in `TypeFetcherGenerator`) must own a `CompositeDecodeHelperRegistry`, thread it into `ArgCallEmitter.buildCallArgs(..., registry)`, and **drain** the lifted decode-helper methods onto the class they generate (`<Type>` for the inline emitters via `TypeClassGenerator`; `<Type>Fetchers` via `TypeFetcherGenerator`), mirroring the own-and-drain lifecycle `QueryConditionsGenerator` already implements. **Part B:** a same-table / empty-join-path reference whose filter input is entirely `@condition` fields (no key/`@nodeId` projection) crashes with `Index -1 out of bounds for length 0` (`aliases.get(aliases.size() - 1)` on an empty alias list in `InlineTableFieldEmitter`/`InlineColumnReferenceFieldEmitter`/`SplitRowsMethodEmitter`); guard the empty case.
