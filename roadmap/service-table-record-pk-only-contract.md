---
id: R516
title: "Narrow SourceKey.Wrap.TableRecord contract to PK-only, revert full-row projection"
status: Ready
bucket: correctness
priority: 2
theme: service
depends-on: []
created: 2026-07-23
last-updated: 2026-07-28
---

# Narrow SourceKey.Wrap.TableRecord contract to PK-only, revert full-row projection

## Status: bounced a second time on the retirement sweep, one surface left (2026-07-28)

Everything the contract asks for is delivered and verified. What is left is a single false
statement in the delivery's own prose; see "Second gate" below. The remaining work is that one
javadoc plus two adjacent surfaces in the same file, and the recommended registry graduation
that stops a fourth sweep from being needed.

Shipped and accepted:

- Scope items 1 through 8 and the whole declared test surface **shipped at `cc270f1`**.
- Self-review prose sweep **shipped at `ffce130`**.
- Retirement-sweep rework, twelve surfaces, **shipped at `b811bdb`**.

Verified independently at both gates: full reactor green under `mvn install -Plocal-db`, 13
modules, execution tier and docs render included. The narrowing itself, the `SourcesOnPkLessParent`
rejection arm, the service rewrites, and the manual correction are accepted as delivered. The
sections from "Problem" down describe work that already landed and are kept only for context.

The first bounce was the retirement sweep, which `roadmap/workflow.adoc` makes a Done-gate
obligation for any item declaring a `Retired vocabulary` section. Eight prose surfaces still named
the deleted mechanism as live; nothing behavioural was in question. All eight are fixed, confirmed
at the second gate. The re-sweep that followed found four more (9 through 12 below), one of them
introduced by the `ffce130` self-review sweep itself. The first reviewer's second improvement note
is Backlog item R554 (filed, confirmed); the other three are addressed or answered in place.

## Second gate: rework, one blocking finding (independent reviewer, In Review → Ready, 2026-07-28)

Confirmed at this gate before the finding below: build green (13/13, exit 0); all twelve prose
surfaces from the first bounce fixed; the token sweep over every declared retired term
(`reservedFullRow`, `reservedSourceAlias`, `RESERVED_SRC_ALIAS_PREFIX`/`_SUFFIX`, `__src_`,
`appendsFullParentRow`, `serviceChildKeyExtractionForksOnTypedRecord`,
`ServiceParentTableRecordKeyExtractionTest`, the `RequiredProjection` record, "fully-populated
parent record", "every column on the parent table") returns clean outside `roadmap/`;
`docs/` diff clean under the workflow's user-facing-doc check (no `R<n>`, `Phase <n>`, TODO or
plan-slug markers in the manual); no code-string assertions on generated method bodies in any
delivered test class. `PkLessParentServiceSourcesRejectionTest` is exemplary: positive case plus
three controls (root, no-SOURCES over-fire, keyed parent), honest assertion descriptions.

**Blocking. `graphitron-sakila-service/.../services/CityService.java:33-37`, authored by this
delivery at `cc270f1`, states something that is false.** The `cityUppercase` javadoc says the key
extraction copies columns "by field identity, and a column absent from that row yields `null`
rather than throwing", concluding "this method receives records whose `cityId` is `null` and every
lookup misses".

Verified against jOOQ 3.20.11: `Record.get(Field)` on a field absent from the row type throws
`IllegalArgumentException: Field B is not contained in row type (A)`. It does not yield `null`.
The emitted extraction is
`key.set(Tables.CITY.CITY_ID, source.get(Tables.CITY.CITY_ID))`
(`graphitron-sakila-example/target/generated-sources/graphitron/.../fetchers/CityFetchers.java`,
`cityUppercase`), so if the force-projection regressed the request would fail loudly through
`ErrorRouter`, and `CityService.cityUppercase` would never be entered. The javadoc describes the
fixture's own regression mode, which is the fixture's entire reason for existing, and describes it
wrongly.

Three things make this the same defect class as the first bounce rather than a nitpick:

- It is finding 12's failure mode repeated. `cc270f1` rewrote this exact sentence (`into` "leaves
  absent columns `null` instead of throwing" → "a column absent from that row yields `null` rather
  than throwing"), carrying the retired contract's consequence forward into new prose it was itself
  authoring.
- It contradicts the sibling javadoc twenty lines below. `cityLowercase:58-62` calls itself "the
  *loud-throw* arm: the framework's key extraction reads
  `((Record) env.getSource()).get(Tables.CITY.CITY_ID)` ... which throws on an absent field instead
  of yielding `null`". Post-narrowing that is the identical jOOQ call `cityUppercase` now emits, so
  one expression is documented with opposite failure modes in one file. This is finding 1's shape,
  which the first reviewer called the sharpest one.
- It contradicts prose this delivery corrected elsewhere. `GraphQLQueryTest:2503-2508` now reads
  "both read CITY_ID off the parent row by jOOQ field identity, so the wraps differ in the shape of
  the key they build and not in what the projection owes them", and `TestFilmService:82-86` now
  reads "Both wraps demand the same key columns and nothing wider". The silent-null-versus-loud-throw
  contrast was retired by finding 9; it survived in the service fixture those very tests drive, and
  the manual points readers at this method pair as the canonical `@service` example (Scope 7/8).

**Fix the whole silent-null family in `CityService.java` in one pass**, not just the one false
sentence. Two adjacent surfaces are the same retired framing (both pre-date the item, both are in
its touched area, which the workflow's retirement sweep puts in scope):

- `:24-26`, class javadoc: "so a `null` key produces a `null` field value rather than being papered
  over by a key-independent body." There is no null-key state for either method now.
- `:58-62`, `cityLowercase`: "loud-throw" is still true of the Row arm but no longer distinguishes
  it from the TableRecord arm, so the framing invites the contradiction back.

Recommended in the same pass, and the thing that stops a fourth sweep: **graduate this item's
retired tokens into `RetiredVocabularyGuardTest`'s `REGISTRY`.** `roadmap/workflow.adoc`'s
Retirement-sweep paragraph says recurrently-surviving terms graduate, and these survived two
sweeps (eight findings, then four more). They are all identifier-shaped, which is exactly what that
registry scans, and the token sweep is clean right now so entries would go in green:
`reservedFullRow`, `reservedSourceAlias`, `RESERVED_SRC_ALIAS_PREFIX`, `RESERVED_SRC_ALIAS_SUFFIX`,
`appendsFullParentRow`, `serviceChildKeyExtractionForksOnTypedRecord`. Successor strings are
straightforward (`the base-named required-projection column list`, `the per-column key copy`,
`appendsRequiredColumn`, `serviceChildKeyExtractionIsUnconditional`).

Non-blocking, fix if convenient, do not hold a third gate on them:

- `graphitron/.../model/TableRef.java:41-47` names three live `allColumns()` readers; there are four
  distinct call sites (`TypeBuilder:854-855`, `BuildContext:1744` and `:1752`, `FieldBuilder:1186`
  *and* `:7147`, the last a candidate list for an unknown-column rejection, not the pivot search).
  The load-bearing claims are correct, and the umbrella "all answer what columns does this table
  have?" covers the fourth. `development-principles.adoc`'s "unguarded inventory" clause argues for
  dropping the enumeration rather than extending it.
- `graphitron/src/test/.../TestFixtures.java:311-312` names two of those readers where `TableRef`
  names three. Same inventory, two counts, one commit.
- `graphitron/.../generators/TypeClassGenerator.java:120` ("the omission it exists to catch is a
  child's DataLoader key column being absent from the parent row and silently null") pre-dates the
  item and stays true for the `Wrap.Record` arm, so it is stale-in-kind at worst.
- `graphitron-sakila-example/.../schema.graphqls:684` labels `cityLowercase` "the loud-throw shape",
  the SDL-side echo of the `CityService:58-62` framing. Accurate; no longer distinguishing.

Not re-litigated, per the previous pass's request: `TypeSpecAssertions`'s body-scan family (the file
is the project's sanctioned confinement point for it and R554 owns retiring it, though note
`serviceChildKeyExtractionIsUnconditional`'s `!body.contains("instanceof")` is the family's only
negative assertion and the most brittle member); the seven-argument `reflectServiceMethod` overload;
the `isRoot` disambiguation.

## Review findings (independent reviewer, In Review → Ready, 2026-07-28)

Rework, in descending order of how wrong the surviving prose is:

1. `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/TableRef.java:41-47`. The
   `allColumns` javadoc still says the component "exists so emit-time consumers can enumerate the
   whole row", naming "the `SourceKey.Wrap.TableRecord` key reconstruction
   (`GeneratorUtils.buildKeyExtraction`) and the reserved-alias full-parent-row projection
   (`TypeClassGenerator`)" as those consumers, and asserts a single-homing invariant over the
   reserved-alias names. All of it is now false: `TableRef.allColumns()` has zero consumers in the
   generators package, the key read drives off `sourceKey().columns()`, and the alias scheme is
   deleted. The live consumers are classification-time (`TypeBuilder` interface base/detail split,
   `BuildContext`, `FieldBuilder` pivot-column search). This is the sharpest one because
   `cc270f1` corrected the counterpart javadoc on `JooqCatalog.allColumnRefs` (which explicitly
   says it "Backs `TableRef#allColumns()`") and left this side saying the opposite, so a reader
   following the link gets two contradictory answers.
2. `docs/architecture/explanation/dispatch-axes.adoc:103`. The consumer-side dispatch bullet says
   `GeneratorUtils.buildKeyExtraction` "switches over `sourceKey.wrap()` to choose between
   `DSL.row(...)`, `parent.into(table.col, ...)`, and the reserved-alias full-row reconstruction".
   The third arm no longer exists. This renders to the published site and is exactly what the
   "Documentation names only live tests/code" principle rules out.
3. `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationEntitiesDispatchTest.java:511`.
   Present-tense claim that "the DataLoader key extraction (`.into(Tables.CITY)`) reads a non-null
   key". The sibling test's javadoc in the same file was rewritten correctly; this one was missed.
4. `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java:2504-2505`.
   Same shape: "cityUppercase resolves to null (silent `.into(Tables.CITY)` extraction)". The
   extraction is now a per-column `key.set(...)` copy, so the silent-versus-loud contrast the
   comment draws against `cityLowercase` no longer rests on `into`.
5. `graphitron/src/test/java/no/sikt/graphitron/rewrite/TestFixtures.java:311-312`. The
   hand-built-`TableRef` comment still explains the empty `allColumns` by pointing at "the
   reserved-alias full-row emit / TableRecord key reconstruction that read this". Neither reads it.
6. `graphitron/src/test/java/no/sikt/graphitron/rewrite/ArrayColumnCodegenPipelineTest.java:54`.
   The class javadoc was honestly rewritten to say the `ClassName.bestGuess` crash site is gone
   and no `Class` literal is emitted, but the assertion description two screens below still reads
   `"full-row key reconstruction over an array-typed column must not crash ClassName.bestGuess"`,
   contradicting it.
7. `graphitron-sakila-db/src/main/resources/init.sql:704-707`. "Any code generation that
   reconstructs this table's full row per column, notably the `SourceKey.Wrap.TableRecord`
   key-extraction arm, crashed ..." The historical framing is fine; the clause naming the
   TableRecord arm as a full-row reconstructor is not.
8. `graphitron/src/test/java/no/sikt/graphitron/rewrite/ArrayColumnTypeDecodeTest.java:63`.
   "The full-row iterator (the reachable crash path's column source)". `allColumnsOf` is still a
   full-row iterator, but the TableRecord path no longer reaches it, so the parenthetical is stale.

Also unmet at the gate, and cheap to fold into the same pass: the spec body was still written
entirely as future work with no landing SHAs when it arrived In Review. This section and the one
above are the fix; keep them current if the item bounces again.

## Rework applied (2026-07-28)

All eight findings above are fixed. Notes where the fix was a judgment call rather than a
substitution:

- Finding 1 (`TableRef.allColumns`) now names the three live classification-time readers instead of
  the two retired emit-time ones. They are package-private in `no.sikt.graphitron.rewrite` while
  `TableRef` is in `.model`, so javadoc cannot link them; they are named in `{@code}` with the
  reason stated inline, so the next reader does not "fix" it into a dangling `{@link}`.
- Finding 2 (`dispatch-axes.adoc`) replaces the retired third arm with the per-column copy and adds
  the sentence that makes the axis claim true post-narrowing: all three arms read the same
  `sourceKey.columns()`, so the wrap picks the shape of the key and never its contents.
- Finding 7 (`init.sql`) keeps the historical framing the reviewer accepted and drops only the
  clause naming the TableRecord arm as a full-row reconstructor, restating the crash condition as
  what it actually was: enumerating columns and emitting a per-column `Class` literal.
- Finding 8 (`ArrayColumnTypeDecodeTest`) keeps "full-row iterator", which is still accurate for
  `allColumnsOf`, and replaces the stale parenthetical with why the type-lift is pinned there:
  it is the widest decode surface in the catalog, not one consumer's crash path.

Four more the wider sweep found, fixed in the same pass:

9. `graphitron-sakila-example/.../GraphQLQueryTest.java`, the two `City` service-child execution
   tests. Their comments drew a silent-null (`Wrap.TableRecord`) versus loud-throw (`Wrap.Row`)
   contrast on the regression failure mode. That contrast was real only while the TableRecord arm
   mapped by name through `into(...)`; both wraps now read by field identity, so the pair covers
   two key shapes over one guarantee and the block comment says that instead.
10. `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`, the `cityUppercase`
    fixture label, same stale "silent-null reproducer shape" phrasing.
11. `graphitron/src/test/java/no/sikt/graphitron/rewrite/ServiceProjectionPipelineTest.java` class
    javadoc: "the key extraction reads `null` and the child silently resolves to `null`". Predates
    this item and describes a failure mode no wrap now has.
12. `graphitron-sakila-example/src/main/resources/graphql/federated-schema.graphqls`. Introduced by
    the `ffce130` self-review sweep in this item: the rewritten comment carried the old
    "reads null and the child silently resolves to null" clause forward into new prose. Corrected
    to say the extraction has no key column to read.

Findings 9 through 12 share one root cause with the reviewer's eight: the first two sweeps grepped
for phrases naming the *deleted mechanism* (`__src_`, `reservedFullRow`, "full parent row") and so
could not see prose that described the mechanism's *observable consequences* without naming it. The
third sweep grepped the semantics instead (`into\(Tables\.`, "silently resolv", "wrap-gated",
`buildKeyExtraction`), which is what surfaced them.

Also applied from the reviewer's non-blocking notes: the retired-axis section header in
`ParentProjectionContainmentCheckTest` is renamed. The `TypeSpecAssertions` string-scan family is
filed as Backlog item R554 rather than fixed here, per the reviewer's own scoping. The seven-argument
`ServiceCatalog.reflectServiceMethod` overload is left in place: it is a test-facing convenience
alongside an equally test-facing six-argument sibling, so removing one of a pair is churn without a
principle behind it.

Verification: full reactor green under `mvnd install -Plocal-db`, 13 modules, execution tier and
docs render included.

Not rework, and not conditions on the next gate. Recorded so the next reviewer does not
re-litigate them:

- `TypeSpecAssertions.serviceChildKeyExtractionIsUnconditional` asserts
  `body.contains("key.set(") && !body.contains("instanceof")` on a generated method body, which is
  the pattern `development-principles.adoc` bans, and the negative half is brittle in a new way
  (any unrelated future `instanceof` in that fetcher method flips it). The delivery did not
  introduce the pattern: it is a pre-existing four-helper family in that file, and this item net
  removes one member of it. Retiring the family belongs in its own Backlog item, and R549's
  keystone slice deletes the walk these helpers audit anyway. Fixing it here is welcome but not
  required.
- `ServiceCatalog.reflectServiceMethod`'s seven-argument overload (slotTypes, no `pkLessParent`) is
  now reached only from `ServiceCatalogTest`; production threads the eight-argument form.
- `ParentProjectionContainmentCheckTest`'s section header still reads "The table-record axis"
  after the axis was retired.
- The `isRoot` disambiguation in `ServiceDirectiveResolver.resolve` genuinely changes which
  validation arm a PK-less-parent child reaches (child arms instead of root arms). The direction is
  toward correct classification and the test javadoc is honest that the flip is unpinned rather
  than claiming to pin it, which is the right call for an arm that is currently inert.

## Problem

`docs/manual/how-to/handle-services.adoc` currently documents (around the `@service`-on-a-child-field
sections) that a `@service` child field keyed via `SourceKey.Wrap.TableRecord` receives a
"fully-populated parent record... every column on the parent table." This promise is architecturally
wrong. The contract between Graphitron and service authors must be PK-only: the framework hands the
service a batch of parent PKs (as a typed `TableRecord` carrying only PK columns), and if the service
needs other columns it fetches them itself in one batched query via the injected `DSLContext`, using
the database the way it's supposed to be used, rather than relying on the framework to smuggle
arbitrary columns through the parent SELECT.

This wrong promise was built up across a chain of roadmap items, each treating a symptom rather than
the root premise:

- **R425** (legitimate, keep the bug fix, re-scope the shape): fixed a real bug — a missed
  pattern-match arm meant a `@service`/`@splitQuery` child's key columns weren't force-included in
  the parent projection, causing silent-null DataLoader keys under federation `_entities` fetches.
  The fix itself is correct and should be *kept*, but re-scoped: for `Wrap.TableRecord` it should
  force-include only the parent's PK column(s) as base-named columns (the same `baseColumns`
  mechanism already used for `Wrap.Row`/`Wrap.Record`), not a full-row projection.
- **R426** (revert): took R425's premise further and promised the *full* parent row is always
  projected for `Wrap.TableRecord` children, because an existing example
  (`FilmService.titleTitlecase`, `graphitron-sakila-service/src/main/java/no/sikt/graphitron/rewrite/test/services/FilmService.java:140-147`)
  read a non-PK column (`film.getTitle()`) off the parent record and happened to work only when the
  client's own selection coincidentally included `title`. The fix should have been to correct
  `titleTitlecase` to fetch its own data, the way `CityService.cityUppercase`
  (`graphitron-sakila-service/src/main/java/no/sikt/graphitron/rewrite/test/services/CityService.java:39-51`)
  already correctly does via an injected `DSLContext` — not to make the framework guarantee full
  rows. The two services demonstrate two different, inconsistent contracts for the same directive
  today.
- **R436** (revert): built the reserved `__src_<col>__` full-row aliasing scheme to avoid
  multiset-alias collisions — machinery that exists only to support R426's full-row premise.
- **R511** (revert/simplify): added a runtime `instanceof` fork in
  `GeneratorUtils.buildKeyExtraction` to reconcile two parent arrival shapes (SQL-projected generic
  `Record` vs. a typed record returned directly by a service) for full-row reconstruction. Once only
  the PK is ever read, the same field-identity read (`source.get(Tables.X.PK_COL)`) works uniformly
  across both arrival shapes — the SQL-projected row has the PK force-included as a base-named
  column, and a service-returned typed record always carries its own PK as a real column — so no
  runtime type fork should be needed at all.

## Design

### The core mechanism is a narrowing, not a rebuild

`TypeClassGenerator.collectRequiredProjection` (`TypeClassGenerator.java:532-576`) already computes
`bk.sourceKey().columns()` as the parent's PK columns for the `Wrap.TableRecord` case — identical to
every other wrap — and then discards it in favor of `reservedFullRow = true`:

```java
case BatchKeyField bk when bk.sourceKey() != null -> {
    if (bk.sourceKey().wrap() instanceof SourceKey.Wrap.TableRecord) {
        reservedFullRow = true;                       // <- discards sourceKey().columns()
    } else {
        columns.addAll(bk.sourceKey().columns());
    }
}
```

The fix deletes the special case: `Wrap.TableRecord` falls into the same `columns.addAll(...)` branch
as `Wrap.Row`/`Wrap.Record`. `RequiredProjection` collapses from its current two-axis
`(reservedFullRow, baseColumns)` shape back to a single `baseColumns` list — no separate axis, no
"regardless of the user's SDL selection, unconditionally emit the whole row" special case for this one
wrap.

`GeneratorUtils.buildKeyExtraction`'s `TableRecord` arm (`GeneratorUtils.java:537-577`) collapses from
the R511 runtime `instanceof` fork to one unconditional form: for each PK `ColumnRef`, read it off
`source` by field identity/base name and set it on the freshly constructed key record —

```java
Record source = (Record) env.getSource();
XRecord key = new XRecord();
for (ColumnRef col : parentTable.primaryKeyColumns()) {
    key.set(Tables.X.<COL>, source.get(Tables.X.<COL>));
}
```

— no `__src_<col>__` reserved aliases, no `instanceof` branch. This is safe because the PK is present
under its base name on both arrival shapes: force-included as a base-named column when the parent came
from `$fields`, and naturally present as a real column when a service hands back its own typed record
(a jOOQ-generated record always carries its declared columns, PK included). Reverting R436's
reserved-alias scheme does not reopen the multiset-alias-collision hazard it existed to dodge:
`into(Tables.X)` (the old by-name whole-row map that collided) is not reintroduced — reads stay
strictly field-identity, scoped to PK columns only, which is exactly the safety profile
`Wrap.Row`/`Wrap.Record` already ship with today.

`ParentProjectionContainmentCheck` (the `TableRecord` arm at
`ParentProjectionContainmentCheck.java:88-100`) updates in step:
the `Wrap.TableRecord` arm's guarantee becomes a PK-only `baseColumns` demand like the other wraps,
not a special-cased whole-row guarantee.

### PK-less parent tables: validation, not a silent fallback

A `@table` type with no primary key cannot support `@service`/`@splitQuery` via a `Set<XRecord>` /
`List<XRecord>` Sources parameter at all under a PK-only contract — there is no key to build. Today
this case (`primaryKeyColumns()` empty) falls through `ServiceCatalog.classifySourcesType`
(`ServiceCatalog.java:855`, called at `:229`) into a
generic arg-name-mismatch diagnostic (`:229-305`) that does not name
the real cause. This item adds a build-time rejection: when a `Set<XRecord>`/`List<XRecord>` Sources
shape is recognized on a parent whose table has empty `primaryKeyColumns()`, fire a dedicated
`Rejection`/`ServiceMethodCallError` variant naming the PK-less table as the cause, sited at the same
classifier decision point (`classifySourcesType` or its caller) so the classification fact and the
rejection are single-sourced, not re-derived.

One discriminator to get right: `parentPkColumns` is also empty at a *root* coordinate, where there is
no parent table at all — and root already has its own handling (`Set<Row>`/`Set<Record>` get the
dedicated batch-at-root diagnostic, and `List<XRecord>` at root is the legitimate InputBeanResolver
shape, not an error). The new rejection must fire only when a parent *table exists* but has empty
`primaryKeyColumns()`, never at root; the caller's coordinate context carries that fact, which is why
"or its caller" may be the right siting.

### Node-key columns: union with PK in the required-projection walk

`nodeKeyColumns()` usually equals `primaryKeyColumns()` but can diverge: the column order can differ,
and — rarely — `nodeKeyColumns()` can be a subset of the PK, or a unique key (or subset of one) instead
of the PK. Wherever the required-projection walk force-includes a table's key columns as "must be
present regardless of client selection," it should force-include the *union* of `primaryKeyColumns()`
and `nodeKeyColumns()` for a `@node` table type, not PK alone — so that whichever consumer (a
`@service`/`Wrap.TableRecord` child's DataLoader key, or Node ID encoding) needs which columns, both
stay covered by the same force-inclusion computation rather than requiring two independently-verified
mechanisms that can silently drift apart.

Implementation note for whoever picks this up: `ChildField.SingleRecordIdField`
(`ChildField.java:231-253`) is the one existing `SourceKey.Wrap.TableRecord` consumer keyed on
`nodeKeyColumns()` rather than PK, but per its own javadoc it "declines `BatchKeyField` (no
DataLoader)" and is sourced from a `@service`/DML producer's own returned record (`SourceShape.Record`),
not from the type's own `$fields`-projected parent row — so it does not appear to route through
`collectRequiredProjection` at all today. Confirm at implementation time whether any *other* site
computes "required projection" for a `@node` type's own `id` field reading off its own `$fields` row
(as opposed to the mutation-payload/service-returned-record shape `SingleRecordIdField` models); if no
such site exists because node-key columns already end up selected for unrelated reasons in every
existing schema (a masked gap, not a proven non-issue), add the union at `collectRequiredProjection`
regardless so the general case is covered going forward, and note in the PR whether this closes a
latent gap or is confirmed a no-op.

## Scope

1. Fold `Wrap.TableRecord`'s key requirement into the existing `baseColumns` axis of
   `TypeClassGenerator.RequiredProjection` — delete the `reservedFullRow` axis and its unconditional
   whole-row append entirely; `RequiredProjection` becomes a single `baseColumns` list.
2. Force-include `primaryKeyColumns()` in the required-projection walk, per the Design section above.
   **Narrowed 2026-07-27 (was: the union of `primaryKeyColumns()` and `nodeKeyColumns()`).** The node-key
   half has no counterpart in the Design section, whose key-extraction sketch loops
   `parentTable.primaryKeyColumns()` alone, and node-id-ness does not affect projection: the selection
   switch's own comment states that "Compaction (Direct vs NodeIdEncodeKeys) does not affect projection,
   the SELECT terms are the same columns in both cases. The wrapping happens at the fetcher value, not in
   the SELECT clause." So a `@node` type's key columns reach the SELECT through the ordinary column arm
   when `id` is selected, and are not needed when it is not. If there is a reason the node-key union was
   specified that this narrowing misses, reinstate it with that reason written down.
3. Remove the `__src_<col>__` reserved-alias scheme: `reservedSourceAlias` and the projection append
   it drives. Note the scheme has *no* allowlist entry in `GeneratedSourcesLintTest`
   (`EXTERNAL_TOKEN_PREFIXES` carries only `__NODE_`; the aliases are string-literal-only and masked
   before the scan) — the cleanup surface in tests is the javadoc prose describing the scheme, in
   `GeneratedSourcesLintTest` (the `EXTERNAL_TOKEN_PREFIXES` and dunder-guard javadocs) and in
   `DunderFreeEmissionPipelineTest`'s class javadoc.
4. Remove the runtime `instanceof` parent-shape fork in `GeneratorUtils.buildKeyExtraction`'s
   `TableRecord` arm; replace with the single direct field-identity PK read described above.
5. Update `ParentProjectionContainmentCheck` accordingly. Keep the update to the minimum that leaves the
   check honest against the narrowed demand: R549's keystone slice deletes both this check and the
   required-projection walk it audits, so effort spent generalising either is effort spent on something
   already scheduled for removal.
6. Add a build-time rejection for a `Set<XRecord>`/`List<XRecord>` Sources shape on a PK-less parent
   table (see Design section), sited in `ServiceCatalog`.
7. Rewrite `FilmService.titleTitlecase` using an idiomatic jOOQ batch-fetch (e.g.
   `dsl.selectFrom(FILM).where(FILM.FILM_ID.in(ids)).fetchMap(FILM.FILM_ID)`) rather than a manual
   loop — this is the manual's canonical teaching example, so it should demonstrate proper jOOQ usage,
   not just "any working code." Bring `CityService.cityUppercase` to the same idiom for consistency
   between the two examples the docs present side by side.
8. Correct `docs/manual/how-to/handle-services.adoc`: replace every "fully-populated parent record" /
   "every column on the parent table" passage with the corrected PK-only contract, using the (rewritten)
   `titleTitlecase`/`cityUppercase` pair as consistent canonical examples of the one supported pattern.
   Documentation correction is in scope for this item, not a follow-up.

## Test changes

- **Delete** `ServiceParentTableRecordKeyExtractionTest` outright. Its purpose was pinning the
  discriminator basis for R511's now-deleted `instanceof` fork; once that fork is gone there is nothing
  left for it to pin.
- **Delete** `FederationEntitiesDispatchTest.entities_tableRecordServiceChildOnly_nonKeyColumnReadResolvesNonNull`
  — it asserts the reverted behavior by name. **Add** a proper `_entities` test in its place covering
  the corrected contract: a `Wrap.TableRecord` `@service` child resolving correctly under a
  representations-driven `_entities` fetch when the service does its own batched fetch for non-PK data
  (the real federation scenario R425 was protecting).
- Update `GraphQLQueryTest`'s execution-tier tests pinning service-returned-typed-parent and
  colliding-multiset-sibling resolution for `titleTitlecase` to match the rewritten service body.
- Update `DmlBulkMutationsExecutionTest.rowsUpdateFilms_duplicateKeys_yieldOnePayloadRowPerKeyInKeysOrder`:
  it reads payload titles through the reserved alias (`r.get("__src_title__", String.class)`) under an
  empty selection set and breaks on the revert. Re-anchor the row-count/order assertion on the PK
  column (force-included under the corrected contract) or on a selected base-named column.
- Update `ParentProjectionContainmentCheckTest`: it asserts the divergence message names
  `reservedFullRow` (`hasMessageContaining("reservedFullRow")`), which the revert deletes; re-anchor
  on the PK-column containment message.
- Update `ServiceProjectionPipelineTest`'s R426/R511 shape-assertion groups and `TypeSpecAssertions`'s
  `appendsFullParentRow` / `serviceChildKeyExtractionForksOnTypedRecord` helpers to assert the new
  PK-only shape instead (or delete if the assertion no longer has a distinct shape to check once
  `Wrap.TableRecord` folds into the common `baseColumns` path).
- Add pipeline-tier coverage for the new PK-less-table rejection (Scope item 6).
- Add coverage for the node-key/PK union (Scope item 2), once its implementation site is confirmed.

## Retired vocabulary

Expected to retire at the Done gate: `reservedFullRow`, `reservedSourceAlias`,
`RESERVED_SRC_ALIAS_PREFIX` / `RESERVED_SRC_ALIAS_SUFFIX`, the `__src_<col>__`
alias scheme (javadoc-prose-only in `GeneratedSourcesLintTest` — no allowlist entry exists, per Scope
item 3), the "fully-populated parent record" / "every column on the parent table" framing in docs,
and the `TableRecord`-arm `instanceof` runtime fork. Note the SDL description prose in
`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` (around the smallint-convert
comment) narrates the reserved-alias projection; the sweep's `__src_` grep catches it.

Retiring with them, as the shape of Scope item 1 rather than a separate decision: the
`TypeClassGenerator.RequiredProjection` record itself. With one axis left it was an
`List<ColumnRef>` wearing a name, so the walk returns the list and
`ParentProjectionContainmentCheck.check` takes it. On the test side, `TypeSpecAssertions`'s
`appendsFullParentRow` goes (the shape it detected no longer exists, and `appendsRequiredColumn`
is the check) and `serviceChildKeyExtractionForksOnTypedRecord` becomes
`serviceChildKeyExtractionIsUnconditional`, since the absence of the fork is now the observable.

## Migration / compatibility

None. This is an internal contract change with no deprecation window — accepted and owned by the user
requesting this item, independent of any downstream consumers who may currently rely on the reverted
full-row behavior.

## Open risks

- **Enforcement asymmetry.** The corrected invariant — "the PK is present under its base name on both
  arrival shapes" — is enforced by `ParentProjectionContainmentCheck` on the SQL-projected side, but on
  the service-returned-typed-record side it rests on the (true-by-jOOQ-codegen, but
  Graphitron-unenforced) convention that a generated record always carries its own PK. Acceptable, but
  should be stated explicitly in the implementation PR rather than left implicit.
- **Node-key union implementation site is unconfirmed** (see Design section) — resolve during
  implementation, not deferred silently.

## Note

This item corrects R426's premise and unwinds R436's and R511's downstream complexity. R425 remains
valid; this item is what actually completes R425's fix correctly, re-scoped to PK-only rather than
full-row.

**Why an unconditional append exists at all (added 2026-07-27, no scope change).** Worth knowing while
implementing, because it explains why this chain kept recurring. `TypeClassGenerator`'s selection switch
has arms for exactly the seven leaf kinds that project data of their own (`ColumnBackedField`,
`ColumnBackedReferenceField`, `TableField`, `LookupTableField`, `NestingField`, `ComputedField`,
`PivotField`). A `BatchedTableField`, a `BatchedLookupTableField` and a `@service` child have **no arm**,
because from the switch's point of view they project nothing: their data arrives via a DataLoader. So when
their correlation key turned out to be needed in the parent SELECT, the only available home was the
unconditional append this item narrows, and getting a new shape right meant remembering to widen the append
rather than adding a case. R425's "missed pattern-match arm" is that gap seen from the inside.

**Premise re-verified at pickup (2026-07-27, no scope change).** Three things worth having confirmed
before the first edit, since the whole item rests on them. The service-side `Wrap.TableRecord` key is
built from `MethodRef.Param.Sourced`, which `ServiceCatalog` constructs with `parentPkColumns`
verbatim (`ServiceCatalog.java:307-308`), so `bk.sourceKey().columns()` for that wrap already *is* the
parent PK list: deleting the special case yields PK-only force-inclusion with no new computation, and
the Design section's "narrowing, not a rebuild" claim holds literally. There are exactly two
`SourceKey.Wrap.TableRecord` mint sites, `ServiceCatalog.java:891` (the service Sources shape, PK-keyed)
and `FieldBuilder.java:5966` (`SingleRecordIdField`, `nodeKeyColumns()`-keyed), which settles the
node-key question the Design section leaves open: the one node-key-keyed consumer is precisely the one
that does not route through `collectRequiredProjection`, so scope item 2's narrowing to PK alone is
confirmed rather than assumed. And this item shares no file with R552's slices, so it can run alongside
the first command family rather than queueing behind it.

The end state (R549's keystone slice) is a gated arm that projects the key when the child is selected and
nothing when it is not, which retires the append, the walk, the containment check, and the over-projection
together. This item's force-include is therefore an interim expression of the same demand, chosen because a
priority-2 correctness fix should not wait on a programme in Spec. Implement it as the smallest thing that
narrows the contract correctly; do not build machinery around it.
