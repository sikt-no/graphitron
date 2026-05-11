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

What still ships under R43 is the actual lift. The In Review → Ready review flagged that the diff narrowed scope but did not retire the deferred entry: `TypeFetcherGenerator.STUBBED_VARIANTS` still maps `ChildField.TableMethodField.class`, and the dispatch in `generateChildFetcher` at `TypeFetcherGenerator.java:433` still routes to `stub(f)`.

## Plan

### Implementation

- `TypeFetcherGenerator`: introduce `buildChildTableMethodFetcher(ChildField.TableMethodField, ...)` modelled on the root-site `buildQueryTableMethodFetcher` (`TypeFetcherGenerator.java:985`). The root emit is the closer cognate than the batched `ChildField.ServiceTableField` path: `TableMethodField` carries no `parentSourceKey` / `loaderRegistration` (cf. `ChildField.java:301-309`), so the emission is per-row, not a DataLoader-keyed batched call. The developer's static method is invoked the same way at child as at root, passed the return-type table alias (compare `TestTableMethodStub.getLanguage(Language table)`). The `@LoadBearingClassifierCheck`-pinned `field.returnType().table().tableClass()` lets the emitted local declare the specific generated jOOQ table class without a cast.
- The difference vs. root is the join: at child, the returned table has to be filtered by the parent row's key, and `field.joinPath()` is the resolved chain from the parent table to the returned table. The path is populated by `ctx.parsePath` (`FieldBuilder.java:3875`) from `@reference(path:)`; absent the directive, `BuildContext.parsePath` returns an empty list (no auto-inference, cf. `BuildContext.java:754-756`). See the existing classification fixture `WITH_REFERENCE_PATH` in `GraphitronSchemaBuilderTest` for the populated shape.
- Replace the `STUBBED_VARIANTS` entry for `ChildField.TableMethodField.class` with an `IMPLEMENTED_LEAVES` entry. The dispatch case in `generateChildFetcher` (`builder.addMethod(stub(f))` at `TypeFetcherGenerator.java:433`) flips to call the new emit method.
- Design fork to resolve before coding: what does an empty `joinPath()` mean? Two acceptable answers: (a) reject at classification ("`@tableMethod` on a child field requires an explicit `@reference(path:)`"), or (b) accept and emit a same-table call (the developer's method targets the parent's table and uses the parent row directly). Pick one in the implementing commit's message; the other can be deferred to a follow-up item. `ServiceTableField`'s FK-auto-inference is not a precedent here, because its parent-source machinery is what makes auto-inference work, and that machinery is absent from `TableMethodField`.

### Tests

- Pipeline tier (primary, per `rewrite-design-principles.adoc` § "Pipeline tests are the primary behavioural tier"): a `FetcherPipelineTest` case with `Film @table { language: Language @tableMethod(...) @reference(path: [...]) }` shape (mirroring the existing `WITH_REFERENCE_PATH` classification fixture) asserting the generated `TypeSpec` for `FilmFetchers` contains the expected fetcher method and dispatches to the developer's static table method.
- Compilation tier: covered automatically by the `graphitron-sakila-example` compile step, since the new fetcher must type-check against real jOOQ classes.
- Execution tier: a `graphitron-sakila-example` execution test against `rewrite_test` with a developer-authored static table-method class. The fixture covers whichever empty-path semantics the design fork above chose; the other shape stays uncovered until its follow-up item.
- Validation/structural: `TableMethodFieldValidationTest`'s three `stubbedError` cases (`NO_PATH`, `WITH_FK_PATH`, `WITH_CONDITION_ONLY`) all assert the deferred-error path. Once the lift lands those cases either flip to positive (no error) or are replaced by pipeline-tier emit assertions; the case currently named `NO_PATH` is the one to delete or retarget once the empty-path fork is resolved.

### Roadmap entries

- On landing, `STUBBED_VARIANTS` ends up shorter by one and `IMPLEMENTED_LEAVES` longer by one; `inference-axis-coverage.adoc` updates to reflect that `ChildField.TableMethodField` is no longer deferred.
- `changelog.md` gets a new entry for the R43 lift commit referencing both the preparatory milestone (`a868125` + `46ed616`) and the lift commit SHA.

### Out of scope

- Re-opening the scalar/enum-return carve-out (closed by the structural rejection above).
- Any change to the root-site `QueryTableMethodTableField` shipping in `IMPLEMENTED_LEAVES`.

Not currently a blocker for any in-flight migration.
