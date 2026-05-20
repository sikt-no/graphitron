---
id: R187
title: "Nested @service param-name mismatch shadowed by 'unrecognized sources type' diagnostic"
status: Backlog
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Nested @service param-name mismatch shadowed by 'unrecognized sources type' diagnostic

A nested `@service` method whose Java parameter name doesn't match any GraphQL argument (typo, alias drift, etc.) gets the misleading "parameter 'X' in method 'Y' has an unrecognized sources type: '<Type>'" diagnostic whenever the parameter type isn't a recognised SOURCES shape (e.g. `LocalDate`, `String`, `Integer`). This shadows the actionable "parameter does not match any GraphQL argument or context key" message that lists available argument names and suggests `argMapping`. The arg-mismatch branch at `ServiceCatalog.java:282-319` is gated on `parentPkColumns.isEmpty()` (root only); nested fields with a non-empty parent PK fall through to the generic "unrecognized sources type" branch at `ServiceCatalog.java:326-328`, which describes a SOURCES-flavoured feature the user never asked for.

This is the nested-field analogue of R185 (root `@service` arg-name mismatch shadowed by Sources-shape diagnostic). R185 narrows a SOURCES-shape exception on a `List<XRecord>` shape; this item extends the helpful arg-mismatch diagnostic to nested fields when the type clearly isn't SOURCES-shaped at all. Independent of R185; both can land in either order.

## Reproduction

```graphql
type Utdanningsspesifikasjon {
    statushistorikk(
        """Filter for status-historikk"""
        dato: Date @field(name: "DATO_FRA")
    ): [Utdanningsinstansstatus]
        @service(service: {className: "...UtdanningsinstansstatusService", method: "getUtdanningsinstansstatusByDatoOrPreviousRow"})
}
```

```java
public Map<UtdanningsinstansRecord, List<UtdanningsinstansstatusRecord>>
    getUtdanningsinstansstatusByDatoOrPreviousRow(Set<UtdanningsinstansRecord> utdanningsinstanser, LocalDate input) { ... }
```

Current diagnostic:

> Field 'Utdanningsspesifikasjon.statushistorikk': service method could not be resolved, parameter 'input' in method 'getUtdanningsinstansstatusByDatoOrPreviousRow' has an unrecognized sources type: 'java.time.LocalDate'

Expected diagnostic (the one already produced for root fields at `ServiceCatalog.java:282-319`, currently shadowed for nested fields):

> parameter 'input' in method 'getUtdanningsinstansstatusByDatoOrPreviousRow' does not match any GraphQL argument or context key on this field, available GraphQL arguments: `dato`; available context keys: (none), either rename the Java parameter to match one of the available argument names, or bind explicitly via the `@service` directive's `argMapping` field (e.g. `argMapping: "input: dato"`, ...).

## Sketch

At `ServiceCatalog.java:282`, drop or relax the `parentPkColumns.isEmpty()` gate on the arg-mismatch diagnostic. The discriminator should be: the parameter type is not a SOURCES shape AND not a DTO-with-actionable-reason, i.e., `classifySourcesType` returned empty, `looksLikeSourcesShape` is false, and `dtoSourcesRejectionReason` is null. When all three are true, the only plausible diagnosis is a name mismatch (or a missing context key), regardless of whether the parent has PK columns. The existing `dtoSourcesRejectionReason` branch keeps its precedence so DTO-shape cases still get their specific reason; the generic "unrecognized sources type" branch becomes a last-resort fall-through (and likely unreachable in practice once the gate is removed).

## Test surface

Pipeline-tier rejection test in `GraphitronSchemaBuilderTest`: a nested `@service` field whose Java method takes a non-SOURCES type (e.g. `LocalDate`, `String`) under a parameter name that doesn't match any GraphQL arg gets the arg-mismatch diagnostic (asserts substring "does not match any GraphQL argument or context key" and the available-args list), not the "unrecognized sources type" message. Existing tests asserting "unrecognized sources type" (`ServiceCatalogTest.java:119`) should be reviewed: keep the ones that exercise genuinely unrecognised SOURCES-positioned types (parent PK columns present, name *does* match an arg/key but the type is wrong); replace the ones that are actually arg-name mismatches with assertions on the new diagnostic.

## Acceptance criteria

- Nested `@service` with a non-SOURCES-shaped parameter (e.g. `LocalDate`, `String`, `Integer`) whose name doesn't match any GraphQL argument produces the "does not match any GraphQL argument or context key" diagnostic (listing available args and suggesting `argMapping`), not "unrecognized sources type".
- Root-field behaviour from `SERVICE_AT_ROOT_*` tests and R185's eventual narrowing remain intact.
- Genuinely unrecognised SOURCES-positioned types (where the name matches an arg/key, or where the shape is SOURCES-adjacent but unclassifiable) still get a clear diagnostic; "unrecognized sources type" survives as the last-resort fall-through if reachable.
