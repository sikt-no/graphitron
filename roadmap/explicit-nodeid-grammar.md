---
id: R473
title: "Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution"
status: Backlog
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-13
last-updated: 2026-07-13
---

# Explicit @nodeId grammar: Node.id is the only implicit nodeId; typeName-first decode resolution

Today an `ID`-typed field can acquire node semantics implicitly, with the node identity derived from *table* facts (the catalog's `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants) rather than from what the schema author declared via `@node`. That inversion shows up as the two input-side synthesis shims (`BuildContext.classifyInputField`, the qualifier-map site and the bare same-table site), and it forces decode-helper resolution to route through the table even at call sites that hold an authoritative type name: `buildInputNodeIdReference` and `NodeIdLeafResolver.resolve` both start from `@nodeId(typeName: T)`, derive the target table from T, then call `resolveDecodeHelperForTable`, whose `NodeIndex.byTable` lookup can report "ambiguous" for a question the author already answered. The helper's typeId-suffix fallback branch agrees with the emitted `decode<TypeName>` helpers only when typeId happens to equal the type name.

## The grammar

1. **`Node.id` is the only implicit nodeId.** The `id` field satisfying the `Node` interface on a type declared `implements Node @node` is obviously a nodeId and obviously of the enclosing type. The directive is redundant there (existing `id: ID! @nodeId` fixtures stay legal); `typeName:` is rejected there as contradiction-prone noise.
2. **Bare `@nodeId` (directive without `typeName:`) is legal only on output fields of the enclosing `@node` type**, where "current type" is well-defined; it is a generalization of rule 1.
3. **Everywhere else (input fields, arguments, anything crossing to another type), node semantics require `@nodeId(typeName: T)`.** An `ID`-typed field without the directive has no node interpretation, full stop.
4. **`ID` + `@reference` without `@nodeId` is rejected** with a fix-it pointing at `@nodeId(typeName: T)`. Today (`BuildContext.java:2436`) that shape silently classifies as a plain `ColumnReferenceField` with `CallSiteExtraction.Direct()`: the client's wire value is compared raw against the FK-target column, so an actual encoded node ID matches nothing. Rejecting closes the last case where table-mediated node resolution could ever be argued necessary.
5. **Decode resolution becomes typeName-first everywhere**: `NodeIndex.byName.get(typeName).decodeMethod()` (the by-name view already exists on `record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName)`). `resolveDecodeHelperForTable`, its multi-`@node`-per-table ambiguity arm, and its typeId-suffix fallback are deleted rather than guarded.

## Phasing

- **Phase 1 (no consumer impact, doable now):** flip the type-bearing callers (`buildInputNodeIdReference` at `BuildContext.java:2765`, `NodeIdLeafResolver.resolve` at `:275`, and the bare-`@nodeId` argument path at `FieldBuilder.java:1342` via its enclosing type) onto by-name resolution. After this, `resolveDecodeHelperForTable` survives only inside the synthesis shims.
- **Phase 2 (breaking SDL change, shares R27's consumer-migration gate):** enforce rules 2-4 as build errors and delete the table-first helper together with the shims. R27 (`retire-synthesis-shims`) remains the deletion vehicle for the shims themselves; this item widens the migration slightly beyond R27's recipe in that bare `@nodeId` on inputs/arguments needs `typeName:` added (the sakila INSERT-input fixture writes the bare form today), on top of the directive-less cases R27 already covers. R34 (`sis-rewrite-migration`) tracks the consumer side.

## Provenance

Supersedes, in stronger form, the discarded R263 (`decode-helper-typename-first-resolution`, see the 2026-07-13 changelog entry and its re-open trigger): R263 proposed a typeName-first *sibling* entry point for hypothetical future callers; the finding here is that existing callers already hold the type name and the resolution polarity is simply backwards. Grammar shape settled in design discussion on 2026-07-13.
