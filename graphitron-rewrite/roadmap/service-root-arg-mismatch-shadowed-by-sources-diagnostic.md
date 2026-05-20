---
id: R185
title: "Root @service arg-name mismatch shadowed by Sources-shape diagnostic"
status: Backlog
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

## Sketch

At `ServiceCatalog.java:276`, narrow the Sources-shape exception so it only short-circuits when the element shape is `RowN` or `RecordN` (the anonymous jOOQ batch-key shapes a developer would only ever choose deliberately). Concrete `TableRecord` subclasses at root should fall through to the general arg-mismatch diagnostic below — that's the diagnostic the developer needs, and `InputBeanResolver` will produce the right binding once the name actually matches. Equivalently: split `looksLikeSourcesShape` into a "definitely a batch shape" predicate (`RowN`/`RecordN`) and the broader "any List of TableRecord-ish thing" version, and only the narrow predicate gates the root exception.

## Test surface

Pipeline-tier rejection test in `GraphitronSchemaBuilderTest` (sibling to `MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED` at `GraphitronSchemaBuilderTest.java:6645`): a `Mutation` field whose Java method takes `List<XRecord>` under a name that doesn't match any GraphQL arg gets the arg-mismatch diagnostic (asserts substring "does not match any GraphQL argument or context key" and the offending available-args list), not the Sources-batch message. The existing `RowN`/`RecordN`-element tests at `GraphitronSchemaBuilderTest.java:6407` and `6645` keep producing the Sources-batch message and lock the narrowed predicate in place.

## Acceptance criteria

- Root `@service` with a `List<XRecord>` parameter whose name doesn't match any GraphQL argument produces the "does not match any GraphQL argument or context key" diagnostic (listing available args and suggesting `argMapping`), not the Sources-batch message.
- Root `@service` with a `List<RowN<…>>` or `List<RecordN<…>>` parameter keeps producing the Sources-batch diagnostic exactly as today.
- Existing tests `SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED` and `MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED` continue to pass unchanged.
