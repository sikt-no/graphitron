---
id: R187
title: Nested @service param-name mismatch shadowed by 'unrecognized sources type' diagnostic
status: Spec
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Nested @service param-name mismatch shadowed by 'unrecognized sources type' diagnostic

A nested `@service` method whose Java parameter name doesn't match any GraphQL argument (typo, alias drift, etc.) currently produces "parameter 'X' in method 'Y' has an unrecognized sources type: '<Type>'" whenever the parameter type isn't a recognised SOURCES shape (`LocalDate`, `String`, `Integer`, …). The diagnostic describes a SOURCES-flavoured feature the user never asked for, and shadows the actionable arg-mismatch message that lists available argument names and suggests `argMapping`.

The arg-mismatch branch at `ServiceCatalog.java:282-319` is gated on `parentPkColumns.isEmpty()` (root only); nested fields with a non-empty parent PK fall through to the generic "unrecognized sources type" branch at `ServiceCatalog.java:326-328`. The gate is too coarse: at nested coordinates the parameter type axis already discriminates (Row/Record/TableRecord vs anything else), so the arg-mismatch diagnostic is just as applicable to nested fields whose parameter is clearly not SOURCES-adjacent.

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

Current diagnostic (`statushistorikk`, parent PK columns are non-empty):

> Field 'Utdanningsspesifikasjon.statushistorikk': service method could not be resolved, parameter 'input' in method 'getUtdanningsinstansstatusByDatoOrPreviousRow' has an unrecognized sources type: 'java.time.LocalDate'

Expected diagnostic (the one already produced for root fields at `ServiceCatalog.java:282-319`, currently shadowed for nested fields):

> parameter 'input' in method 'getUtdanningsinstansstatusByDatoOrPreviousRow' does not match any GraphQL argument or context key on this field, available GraphQL arguments: `dato`; available context keys: (none), either rename the Java parameter to match one of the available argument names, or bind explicitly via the `@service` directive's `argMapping` field (e.g. `argMapping: "input: dato"`, ...).

## Design

### Decision

Restructure the discriminator at `ServiceCatalog.java:258-329` so the arg-mismatch arm runs regardless of `parentPkColumns` emptiness, gated on the parameter type clearly *not* being SOURCES-adjacent. New decision order inside the `sourcesShape.isEmpty()` block:

1. `-parameters` flag check (unchanged at lines 260-264).
2. Root + `looksLikeSourcesShape(...)` → "@service at root does not support batch parameters" (unchanged at 276-281; R185 narrows the predicate separately, independent of this item).
3. `dtoSourcesRejectionReason(...)` non-null → DTO-shape rejection with the `@sourceRow` hint (moved up from its current position at 321-324; precedence kept above arg-mismatch so the existing `dtoSources_onChildField_rejectedWithLifterDirectiveHint` test still gets the lifter-directive hint).
4. **NEW unified arm.** If the parameter is *not* SOURCES-adjacent (`!looksLikeSourcesShape(p.getParameterizedType())`) → arg-mismatch diagnostic (the existing block at 282-319). The `parentPkColumns.isEmpty()` gate is removed; the discriminator becomes "shape isn't SOURCES-adjacent and isn't DTO-actionable, so the only plausible diagnosis is a name mismatch (or a missing context key)".
5. Generic "unrecognized sources type" (current 326-328) survives as last-resort fall-through.

The new arm subsumes the current arg-mismatch branch ; what used to be the root-only path becomes the parameter-shape-driven path that fires at any coordinate.

### Precedence between DTO-hint and arg-mismatch

`List<DTO>` / `Set<DTO>` parameters under non-empty parent PK keep the `@sourceRow` lifter-directive hint, even when the Java parameter name doesn't match any GraphQL argument. The lifter-directive hint *is* actionable at child coordinates (DataLoader batching applies); shadowing the name-mismatch hint at this shape is a deliberate trade-off, not an accident of branch order.

This contrasts with R185, which at *root* makes name-mismatch win over the Sources-batch diagnostic for `List<XRecord>` shapes. The two precedence choices don't contradict each other because they target different parameter shapes: R185 is about `List<TableRecord>` at root (where batching is impossible and `InputBeanResolver` is the realistic intent), R187 is about clearly non-container types at nested (where neither SOURCES nor `@sourceRow` applies).

The existing `dtoSources_onRootField_pointsAtArgCtxMismatch` test (`ServiceCatalogTest.java:172-187`) already pins the root-side precedence: root + `List<DTO>` + named GraphQL arg → arg-mismatch wins. That coverage stays untouched; this item is strictly additive at the nested coordinate for non-SOURCES-shaped parameter types.

### Architectural smell (acknowledged, deferred)

The discriminator at lines 258-329 is now walked by two consumers (R185 and R187) inspecting overlapping predicates (`classifySourcesType`, `looksLikeSourcesShape`, `dtoSourcesRejectionReason`) with subtly different precedence. The principles-architect review of this Spec flagged that a sealed `UnresolvedParam` classification ; classify once, then a single `switch` produces the rejection ; would be a stronger shape: precedence would live in one place, the validator-mirrors-classifier rule would apply trivially, and "what does the classifier emit for `List<DTO>` with a name typo?" would be answered in one location with a test pinning it.

This item *deliberately* doesn't take that on. R187 is a bug fix with a small, surgical diff; the unification is its own architectural item and the right move is to file it as a follow-up rather than expand R187's scope. The follow-up Backlog stub is filed as R192 (`service-param-classification-sealed-hierarchy.md`), referencing R185, R187, and the predicate-chain smell.

## Implementation

Single-file change in `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`:

- Reorder branches inside `sourcesShape.isEmpty()` per the decision above. The `dtoSourcesRejectionReason` block moves up so it can short-circuit before the arg-mismatch arm; the arg-mismatch arm sheds its `parentPkColumns.isEmpty()` outer gate and runs whenever `!looksLikeSourcesShape(p.getParameterizedType())`.
- Update the existing comment block at 265-275 ("SOURCES batching is only meaningful when there is a parent table…") to reflect the new framing: the parameter-shape axis is the discriminator, and `parentPkColumns` only affects whether SOURCES batching was a *possible* outcome ahead of the name-mismatch check, not whether the name-mismatch diagnostic is produced.
- No new helpers, no signature changes, no model changes. The change is local to this one block.

## Tests

### Unit tier (`ServiceCatalogTest`)

- **Update `reflectServiceMethod_unrecognisedParam_onChildField_stillErrors` (line 111-120).** Today it asserts `contains("unrecognized sources type")` on a child-field shape with `parentPkColumns = filmPk` and `getWithUnknown(Object opaque)`. Post-R187, that same input should produce the arg-mismatch diagnostic instead. Rewrite the test to assert the arg-mismatch substring and the available-args/context-keys lines, mirroring the root-coordinate `reflectServiceMethod_unrecognisedParam_onRootField_pointsAtArgCtxMismatch` test (line 122-136). Do *not* lock the exact wording in this Spec ; the assertion is on observable shape (substring `"does not match any GraphQL argument or context key"` and the `available GraphQL arguments:` / `available context keys:` lines), not the surrounding prose.
- **Add a new test pinning the non-SOURCES nested case from the Reproduction section.** A method whose parameter is `LocalDate` (or `String` / `Integer`) under non-empty `parentPkColumns` and no matching GraphQL arg gets the arg-mismatch diagnostic, not "unrecognized sources type". Requires a new stub on `TestServiceStub` (e.g. `getFilmsWithLocalDate(java.util.List<org.jooq.Row1<Integer>> keys, java.time.LocalDate input)` returning `Result<FilmRecord>`).
- **Keep `dtoSources_onChildField_rejectedWithLifterDirectiveHint` (line 156-169) unchanged.** This pins the precedence: `List<DTO>` on a child keeps the `@sourceRow` hint even after the reorder.

### Pipeline tier (`GraphitronSchemaBuilderTest`)

Add a new rejection case under the same enum table as `SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED` (`GraphitronSchemaBuilderTest.java:6407`):

- `SERVICE_ON_CHILD_WITH_NON_SOURCES_PARAM_NAME_MISMATCH_REJECTED` (name shape-only; the exact identifier is the implementer's call): nested `@service` on a `@table`-parent type, method parameter is the new `LocalDate input` stub from above, no `dato` argument on the field (or `dato` present with a different name). Assert `UnclassifiedField` whose reason contains "does not match any GraphQL argument or context key" and the available-args list. The complement test reusing the existing `SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED` pins that root-level Sources-batch rejections are unaffected.

### Audit of existing "unrecognized sources type" assertions

`ServiceCatalogTest.java:119` is the only existing assertion on `unrecognized sources type` that this item flips. The genuinely-unrecognised-SOURCES last-resort fall-through stays in code as defence-in-depth but is likely unreachable in practice once the reorder lands (every shape reachable today either classifies as SOURCES, looks-like-sources, is DTO-actionable, or falls through to arg-mismatch). No coverage is added to assert that fall-through fires ; if a future reachability case surfaces it earns its own targeted test.

## Acceptance criteria

- Nested `@service` with a non-SOURCES-shaped parameter (e.g. `LocalDate`, `String`, `Integer`) whose name doesn't match any GraphQL argument produces the "does not match any GraphQL argument or context key" diagnostic (listing available args and suggesting `argMapping`), not "unrecognized sources type".
- Root-field behaviour from `SERVICE_AT_ROOT_*` tests and R185's eventual narrowing remain intact.
- `List<DTO>` / `Set<DTO>` on a child of a `@table` parent continues to produce the `@sourceRow` lifter-directive hint (existing `dtoSources_onChildField_rejectedWithLifterDirectiveHint` test passes unchanged).
- A follow-up Backlog stub is filed at Spec → Ready hand-off, capturing the sealed `UnresolvedParam` classifier unification suggestion from the principles-architect review.
- Genuinely unrecognised SOURCES-positioned types still get a clear diagnostic; "unrecognized sources type" survives as the last-resort fall-through if reachable.
