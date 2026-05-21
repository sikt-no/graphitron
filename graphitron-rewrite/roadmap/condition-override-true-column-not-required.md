---
id: R209
title: '@condition(override: true) on input field with no matching column rejected as Unresolved'
status: Spec
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# `@condition(override: true)` on input field with no matching column rejected as Unresolved

R205 escalated every `Unresolved` input-field result to a typed build-error rejection. That escalation catches the legitimate case where an input field carries `@condition(override: true)` and the field's name (or `@field(name:)`) does not match any column on the resolving table. With `override: true`, no implicit column predicate is emitted; only the explicit condition method fires. The column is unused by construction, so requiring it to resolve is over-constraint.

## Symptom

Production schema (`opptak-subgraph`):

```graphql
extend type Query {
    sakerV2(filter: SakFilterV2Input): [Sak!] @asConnection
}

input SakFilterV2Input {
    opptaksId: ID @nodeId(typeName: "Opptak")
    sakskode: String @condition(
        condition: {className: "no.sikt.fs.opptak.saksbehandling.SakService", method: "harSakskode"},
        override: true
    )
}
```

R205 build error:

```
2:5  [author-error]  Field 'Query.sakerV2': argument 'filter': plain input type 'SakFilterV2Input': input field 'sakskode': no column 'sakskode' found in table 'sak'
```

The `sakskode` field has no `@field(name:)` and `sak` has no `sakskode` column. With `override: true`, the `harSakskode` method owns the predicate entirely; codegen should not require column resolution.

## Direction

Add a new sealed permit on `InputField`:

```java
record ConditionOnlyField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    boolean nonNull,
    boolean list,
    ArgConditionRef condition
) implements InputField {}
```

In `BuildContext.classifyInputFieldInternal`, at the final "no column found" fall-through (currently `BuildContext.java:1780-1781`), check for `@condition(override: true)` before returning `Unresolved`:

- If the field carries `@condition(override: true)` and `buildInputFieldCondition` returns a present `ArgConditionRef`, return `Resolved(ConditionOnlyField(...))`.
- Otherwise continue to the existing `Unresolved` arm (no column found).

In `FieldBuilder.walkInputFieldConditions`, add a `case InputField.ConditionOnlyField cof ->` arm that emits the `ConditionFilter` and no implicit body param (mirrors the existing `ColumnField` cond-only emission shape).

Other exhaustive `InputField` pattern-match sites get explicit handling:
- `ContextArgumentClassifier.collectFromInputField` — collect context-arg names from the condition.
- `EnumMappingResolver` — no-op (no column to enum-bind).
- `CatalogBuilder` — no-op (no column to project).
- `MutationInputResolver` — reject (filter-only construct, doesn't fit mutation INSERT/UPDATE).
- `TypeFetcherGenerator` — no-op in the column-emit walks (no column to emit).

The change applies symmetrically to plain inputs (the reported case) and `@table` inputs (same `classifyInputFieldInternal` path).

## Acceptance tests

1. **Pipeline: bare `@condition(override: true)` plain-input field with no matching column resolves.** Schema: `input SakFilterLike { sakskode: String @condition(override: true, ...) }` against a query whose target table has no `sakskode` column. Assert: `PlainInputArg.fields()` contains exactly one `InputField.ConditionOnlyField` with `condition()` carrying the resolved method ref. The projected `WhereFilter` list contains exactly one `ConditionFilter` pointing at the condition method; no `BodyParam.Eq` is emitted.
2. **Pipeline: `@table` input symmetry.** Same shape with the input carrying `@table` — verifies the @table path also accepts the case.
3. **Pipeline: condition reflection failure still rejects.** `@condition(override: true)` on a field whose method does not exist — still produces a typed `Rejection` (condition error supersedes "no column found").
4. **Execution-tier: the schema from the symptom section builds, codegens, and invokes the explicit method at runtime.** Add a Sakila fixture mirroring the production shape.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1780-1781` — the "no column found" Unresolved arm to gate on `@condition(override: true)`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java` — add the `ConditionOnlyField` variant; update the `permits` clause.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1526-1595` — `walkInputFieldConditions` exhaustive switch.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/ContextArgumentClassifier.java:133-140`, `EnumMappingResolver.java:317-380`, `catalog/CatalogBuilder.java:403-419`, `MutationInputResolver.java:478-481`, `generators/TypeFetcherGenerator.java:1855-1996` — exhaustive `InputField` switches the compiler will flag.
- R205 (`condition-on-plain-input-field-silent-drop.md`) — the item whose escalation surfaced this gap.
