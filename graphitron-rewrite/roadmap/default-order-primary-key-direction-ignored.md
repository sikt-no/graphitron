---
id: R339
title: "@defaultOrder(primaryKey: true) ignores direction and always sorts ASC"
status: Spec
bucket: bug
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# @defaultOrder(primaryKey: true) ignores direction and always sorts ASC

`@defaultOrder(primaryKey: true, direction: DESC)` emits ascending sort fields regardless of the `direction:` argument, so cursor pagination and default ordering run ASC when the schema asked for DESC. The `index:` variant has the same defect. Only the `fields:` variant honours `direction:`.

This contradicts the directive's own published contract. `directive @defaultOrder` declares `direction: SortDirection = ASC` at directive level (`directives.graphqls:284`, docstring "Sort direction (default: ASC)") with no carve-out per column source, and the `FieldSort.direction` docstring (`directives.graphqls:262-266`) names the directive-level `direction:` as the per-entry fallback for `@defaultOrder`. An author who writes `direction: DESC` is making an explicit, build-time request; dropping it silently for `primaryKey:`/`index:` is a contract violation, not a styling choice. We honour it.

## Relationship to R243

R243 (changelog.md, `16c85fd` + `6fd3878`) lifted whole-spec direction down onto per-entry `ColumnOrderEntry.direction`, and as "fork (b)" deliberately had `primaryKey:`/`index:` synthesised entries "stamp `SortDirection.ASC` explicitly". This item **reverses fork (b) for `@defaultOrder` only**. Fork (b) was the wrong call: it lets the author write `@defaultOrder(primaryKey: true, direction: DESC)` and silently ignores half of it, with no validation error to signal the directive-level `direction:` is inert on that variant. The `@order` enum-value directives are explicitly **not** touched, see Scope: they have no directive-level direction surface and are flipped by the runtime `direction:` input field, so their ASC build-time fallback is correct and stays.

## Root cause

`OrderByResolver.resolveOrderEntries` already receives the resolved `defaultDirection` (computed by `resolveColumnOrderSpec` via `readDirectionArg(dir, ASC)`, `OrderByResolver.java:151`) and threads it through the `fields:` branch (`parseDirection(map.get(ARG_DIRECTION), defaultDirection)`, line 272). Two sibling branches in the same method drop it:

- **`primaryKey:`** (`OrderByResolver.java:249-258`) hardcodes `OrderBySpec.SortDirection.ASC` for every PK column (line 256).
- **`index:`** (line 242) delegates to `resolveIndexColumns` (`OrderByResolver.java:211-221`), which hardcodes `OrderBySpec.SortDirection.ASC` for every index column (line 219).

## Direction

In `resolveOrderEntries`, use the threaded `defaultDirection` instead of the hardcoded `ASC`:

- `primaryKey:` branch: stamp each PK `ColumnOrderEntry` with `defaultDirection` (replace the literal at line 256).
- `index:` branch: give `resolveIndexColumns` a `SortDirection` parameter and stamp it onto each entry. The `@index`-alias call site in `resolveEnumValueOrderSpec` (`OrderByResolver.java:200`) passes `SortDirection.ASC` (preserving the enum-value/runtime-flip semantics); the `resolveOrderEntries` call site (line 242) passes `defaultDirection`.

Consequences, none of which need new wiring:

- The `uniformAsc` flag in `resolveColumnOrderSpec` (`OrderByResolver.java:154`) is derived from the entries' directions, so an all-DESC PK/index default correctly yields `uniformAsc == false`; it follows automatically.
- Emission already honours per-column direction. Every fixed-spec call site emits `col.direction().jooqMethodName()` (`TypeFetcherGenerator.java:3783`, `:3827`, `:3907`), and keyset pagination derives its seek predicate from the same ORDER BY via `.seek(page.seekFields())` (`TypeFetcherGenerator.java:3732`), so a DESC PK/index default paginates descending end to end. No emitter or seek change is required.
- Leave the directive-absent implicit-PK fallback in `resolveDefaultOrderSpec` (`OrderByResolver.java:131-140`) at `ASC`: no `direction:` was specified there, so ASC is the correct default for the tiebreaker.

## Scope

In scope: the `primaryKey:` and `index:` branches of `@defaultOrder`. Both gain directive-level `direction:` support, matching `fields:` and the directive's documented contract.

Out of scope:

- **`@order` enum-value directives** (`@order(primaryKey:)`, `@order(index:)`, `@order(fields:)` on `ENUM_VALUE`). These have no directive-level `direction:` argument; the sort direction comes from the runtime input object's `direction:` field and is applied at code-generation time in the `*OrderBy` helper. Their ASC build-time fallback (`resolveEnumValueOrderSpec`, `OrderByResolver.java:192`, and the ASC passed to `resolveIndexColumns`) is correct and unchanged. This is why the `resolveIndexColumns` change threads a direction parameter rather than hardcoding `defaultDirection`.
- The directive-absent implicit-PK fallback (`resolveDefaultOrderSpec`), which stays ASC as above.

## Tests

Pipeline tier (`GraphitronSchemaBuilderTest`):

- **Rewrite `DEFAULT_ORDER_DIRECTION_DESC`** (`GraphitronSchemaBuilderTest.java:1091-1107`). It currently pins fork (b) as intended: it asserts `uniformAsc() == true` and `direction() == ASC` for `@defaultOrder(primaryKey: true, direction: DESC)`, with a descriptive name and comment ("primaryKey-mode synthesised entries hardcode ASC ... Per the R243 spec, fork (b)") that become false after this fix. Per *Documentation names only live tests/code*, flip the assertions to `uniformAsc() == false` / `direction() == DESC` **and** rewrite the case name and comment to state the new contract. Do not leave a new fixture alongside the stale one.
- **Add an `index:` DESC fixture**, e.g. `@defaultOrder(index: "idx_actor_last_name", direction: DESC)`, asserting each resolved `ColumnOrderEntry.direction() == DESC` and `uniformAsc() == false`, mirroring the existing `DEFAULT_ORDER_INDEX` case (`GraphitronSchemaBuilderTest.java:1039`) which currently asserts ASC.

Execution tier (`graphitron-sakila-example`, `GraphQLQueryTest`):

- **Add a `@defaultOrder(primaryKey: true, direction: DESC)` connection** to the sakila schema (alongside the existing `filmsConnection` PK-ASC connection, `schema.graphqls:80-82`) and assert it returns rows in descending `filmId` order, the reverse of the PK-ASC baseline. This is the end-to-end proof that the emitted jOOQ runs `.desc()` and that keyset pagination seeks descending, modelled on `filmsByRateDescTitleAsc_executesHeterogeneousOrder` (`GraphQLQueryTest.java:880`). A pipeline assertion alone would not catch a seek/emission regression.

## Reproduction

Observed against `10.0.0-RC17`: `@defaultOrder(primaryKey: true, direction: DESC)` generated `orderBy = List.of(e0.EVENT_ID.asc())` where `.desc()` was expected; the sibling `@defaultOrder(fields: [{name: "VEDTAK_TIDSPUNKT"}], direction: DESC)` generated `.desc()` correctly. Consumer workaround until this ships: switch to `@defaultOrder(fields: […], direction: DESC)`.
