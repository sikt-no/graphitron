---
id: R185
title: Root @service arg-name mismatch shadowed by Sources-shape diagnostic
status: Spec
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Root @service arg-name mismatch shadowed by Sources-shape diagnostic

A root `@service` method whose Java parameter name doesn't match any GraphQL argument (typo, plural/singular drift, etc.) gets the misleading "`@service` at the root does not support `List<Row>`/`List<Record>`/`List<Object>` batch parameters" diagnostic whenever the parameter happens to be `List<XRecord>` (or `List<RowN>` / `List<RecordN>`), shadowing the actionable "parameter does not match any GraphQL argument or context key" message that lists available argument names and suggests `argMapping`. The Sources-flavoured branch was added (`ServiceCatalog.java:276-281`, guarded by `looksLikeSourcesShape` at `ServiceCatalog.java:737`) so a developer who deliberately writes a Sources batch parameter at root gets a specific reason; but `List<XRecord>` at root is also the canonical shape for "list of input objects mapped to records" via `InputBeanResolver`, so a plain name typo (e.g. GraphQL arg `input` vs Java parameter `inputs`, where the parameter is `List<OrganisasjonRecord>`) collides with the Sources-shape exception and produces a diagnostic that describes a feature the user never asked for.

## Reproduction

```graphql
type Mutation {
    opprettOrganisasjon(
        input: [OpprettOrganisasjonInput!]!
    ): OpprettOrganisasjonPayload
        @service(service: {className: "...OrganisasjonService", method: "opprettOrganisasjon"})
}
```

```java
public List<OrganisasjonRecord> opprettOrganisasjon(List<OrganisasjonRecord> inputs) { ... }
```

Current diagnostic:

> service method could not be resolved — @service at the root does not support `List<Row>`/`List<Record>`/`List<Object>` batch parameters — the root has no parent context to batch against

Expected diagnostic (the one already produced by `ServiceCatalog.java:282-319`, currently shadowed):

> parameter `inputs` in method `opprettOrganisasjon` does not match any GraphQL argument or context key on this field — available GraphQL arguments: `input`; available context keys: (none) — either rename the Java parameter to match one of the available argument names, or bind explicitly via the `@service` directive's `argMapping` field (e.g. `argMapping: "inputs: input"` …).

## Implementation

Narrow `looksLikeSourcesShape` at `ServiceCatalog.java:737` so the `TableRecord`-element branch returns `false`. After the narrowing the predicate is "element is `RowN` or `RecordN` only" — the two anonymous jOOQ batch-key shapes a developer would only ever choose deliberately for SOURCES batching. The predicate has a single caller (`ServiceCatalog.java:276`, the root-op short-circuit), so narrowing it in place is sufficient; splitting into "narrow" + "broad" variants would add a second predicate with no second caller.

Concrete `TableRecord` subclasses at root then fall through to the arg-mismatch block at `ServiceCatalog.java:282-319`, which lists available GraphQL argument names and suggests `argMapping`. `InputBeanResolver`'s `List<XRecord>` binding still produces the right result once the developer fixes the parameter name (or supplies `argMapping`), so removing this branch from `looksLikeSourcesShape` does not hide any legitimate Sources case: at root, `classifySourcesType` returns `Optional.empty()` for every shape anyway (`parentPkColumns.isEmpty()` guard at `ServiceCatalog.java:781`), so the only behaviour change is which diagnostic wins for `List<TableRecord>` at root.

Also update the Javadoc on `looksLikeSourcesShape` to match the narrowed contract (drop "or concrete `TableRecord`" from the list of element shapes), and the inline comment at `ServiceCatalog.java:271-275` describing the exception (drop `List<Object>` from the example since `TableRecord` no longer triggers it).

Files touched:

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java` — narrow `looksLikeSourcesShape`, update its Javadoc, update the inline comment above the call site, and update the diagnostic message itself (drop `/List<Object>` from the user-visible string so it accurately reflects what now triggers it).

## Tests

Pipeline-tier rejection test in `GraphitronSchemaBuilderTest`, sibling to `MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED` at `GraphitronSchemaBuilderTest.java:6645`: a root `@service` field whose Java method takes `List<FilmRecord>` under a name that doesn't match any GraphQL argument gets the arg-mismatch diagnostic. Reuses the existing `TestServiceStub.getFilmsWithTableRecordSources(List<FilmRecord> keys)` method (returns `Result<FilmRecord>`, parameter named `keys`); the GraphQL field declares a different arg (`input: [ID!]` or similar) so the name mismatch fires. The test asserts `contains("does not match any GraphQL argument or context key")` and that the available-args list mentions the declared arg, and asserts `doesNotContain("does not support List<Row>")` so a regression to the old shadowing behaviour is caught.

The existing `RowN`-element tests `SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED` (`:6407`) and `MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED` (`:6645`) stay as-is and lock the narrowed predicate against the opposite regression (removing the `RowN`/`RecordN` branch too).

Also update the diagnostic string in those two existing tests if it changes (the message currently says `List<Row>/List<Record>/List<Object>`; once narrowed, drop `/List<Object>` to keep the diagnostic honest about what now triggers it).

Files touched:

- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` — new case `SERVICE_AT_ROOT_WITH_TABLERECORD_PARAM_NAME_MISMATCH_REJECTED`; substring update in the two existing Sources-batch cases if the diagnostic text changes.

## Acceptance criteria

- Root `@service` with a `List<XRecord>` parameter whose name doesn't match any GraphQL argument produces the "does not match any GraphQL argument or context key" diagnostic (listing available args and suggesting `argMapping`), not the Sources-batch message.
- Root `@service` with a `List<RowN<…>>` or `List<RecordN<…>>` parameter keeps producing the Sources-batch diagnostic.
- `SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED` and `MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED` keep passing (modulo the diagnostic-text trim noted above).
- No execution-tier coverage required: this is a diagnostic-text change in the rejection path; the compilation/execution tiers never reach a field that rejected at the pipeline tier.
