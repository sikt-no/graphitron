---
id: R43
title: "Stub: child `@tableMethod` with table-bound return (`TableMethodField`)"
status: Ready
bucket: stubs
priority: 4
theme: model-cleanup
depends-on: []
---

# Stub: child `@tableMethod` with table-bound return (`TableMethodField`)

Lift `ChildField.TableMethodField` out of `TypeFetcherGenerator.STUBBED_VARIANTS`. Today schemas using `@tableMethod` on a child field of a `@table`-bound parent fail the build with `[deferred] child @tableMethod (table-bound return) not yet implemented`, even though classification succeeds and the directive's reflection path already ships at the root site (`QueryField.QueryTableMethodTableField` in `IMPLEMENTED_LEAVES`).

This item was originally scoped as the carved-out scalar/enum-return form of `TableMethodField`. That carve-out is closed: `@tableMethod` returning a non-table type is rejected by `TableMethodDirectiveResolver` as a schema error (`"@tableMethod requires a @table-annotated return type"`) regardless of root vs child site, since the directive's whole purpose is to bind a developer-authored jOOQ table method (which by construction returns a generated jOOQ table class). What remains is the table-bound child case, the only shape the classifier produces for `TableMethodField` after the resolver narrows it.

R43 keeps its ID across this scope change rather than spinning off an `R<n+1>` because nothing shipped against the original scalar/enum scope: the item stayed in Backlog from allocation through the scope rewrite, no spec was authored, no fixture was built, no entry was retired from `STUBBED_VARIANTS`. The "IDs are never reused" rule in `workflow.adoc` protects post-`Done` provenance; a single in-place rewrite of a Backlog stub before any artefact references it is a strictly weaker case than the rule targets.

## Progress to date

Preparatory work landed on trunk (commits `a868125` + `46ed616`) and is recorded in `changelog.md` as the R43 scoping-change milestone:

- `TableMethodDirectiveResolver` rejects non-`TableBoundReturnType` at both root and child sites; the sealed `Resolved` collapses to `{TableBound, Rejected}` and the dead `NonTableBound` arms in `FieldBuilder` are gone.
- `ChildField.TableMethodField.returnType()` is narrowed to `ReturnTypeRef.TableBoundReturnType`. The `@LoadBearingClassifierCheck` key `tablemethod-resolver-return-is-table-bound` pins the producer/consumer pair (resolver → `ChildField.TableMethodField` + `QueryField.QueryTableMethodTableField`).
- Two `GraphitronSchemaBuilderTest` cases pin the rejection message at root and child sites; `TableMethodFieldValidationTest` fixtures use `TestFixtures.tableBoundFilm`.

What still ships under R43 is the actual lift. The In Review → Ready review at `b94757f` / this revision flagged that the diff narrowed scope but did not retire the deferred entry — `TypeFetcherGenerator.STUBBED_VARIANTS` still maps `ChildField.TableMethodField.class` and the dispatch in `generateChildFetcher` (~line 447) still routes to `stub(f)`.

## Plan

### Implementation

- `TypeFetcherGenerator`: introduce `buildChildTableMethodFetcher(ChildField.TableMethodField, ...)` modelled on the root-site `buildQueryTableMethodFetcher`. Reuse `reflectTableMethod`'s already-resolved `MethodRef.StaticOnly` (the resolver narrowed the shape; do not re-reflect). The join path on the child variant (`field.joinPath()`) drives the in-fetcher join construction analogously to `ChildField.ServiceTableField`'s emission; `field.returnType().table().tableClass()` is the load-bearing input the existing `@DependsOnClassifierCheck` already covers.
- Replace the `STUBBED_VARIANTS` entry for `ChildField.TableMethodField.class` with an `IMPLEMENTED_LEAVES` entry. The dispatch case in `generateChildFetcher` (`builder.addMethod(stub(f))` near line 447) flips to call the new emit method.
- Decide whether `joinPath().isEmpty()` is rejected at classification (no explicit `@reference`, no developer-authored condition) or accepted as an FK-auto-inference path; if the latter, document the auto-inference rule alongside the analogous `ServiceTableField` behaviour.

### Tests

- Pipeline tier: a `FetcherPipelineTest` (or sibling) case with `Film @table { actors: [Actor] @tableMethod(...) }` shape asserting that the generated fetcher compiles and dispatches the table-method call. Mirror an existing root-site assertion shape so reviewers can diff the test pair.
- Execution tier: a `graphitron-sakila-example` execution test exercising a real child `@tableMethod` against `rewrite_test` and a developer-supplied `getXxx(film: Film, ...)` table-method class. The fixture should cover both the FK-implicit and the explicit `@reference` join-path shape if both are supported.
- Validation/structural: extend or remove the existing `TableMethodFieldValidationTest` `stubbedError` expectations (they assert the deferred-error path; once the lift lands those cases either flip to "no error" or are deleted in favour of a positive emit test).

### Roadmap entries

- On landing, `STUBBED_VARIANTS` ends up shorter by one and `IMPLEMENTED_LEAVES` longer by one; `inference-axis-coverage.adoc` updates to reflect that `ChildField.TableMethodField` is no longer deferred.
- `changelog.md` gets a new entry for the R43 lift commit referencing both the preparatory milestone (`a868125` + `46ed616`) and the lift commit SHA.

### Out of scope

- Re-opening the scalar/enum-return carve-out (closed by the structural rejection above).
- Any change to the root-site `QueryTableMethodTableField` shipping in `IMPLEMENTED_LEAVES`.

Not currently a blocker for any in-flight migration.
