# Replace body-substring assertions on generated `CodeBlock`s

> **Status:** Pending Review
>
> C1 (`TypeSpecAssertions` helper) and C2 (28-site migration) shipped. 28 → 4: only four intentionally-marked body-content assertions remain, each justified inline. C3 (lint gate) deferred per the plan's recommendation.

## Current state

Searching `grep -rn "\.code()\.toString()" graphitron-rewrite/src/test`:

| File | Count | Notes |
|---|---|---|
| `TypeFetcherGeneratorTest.java` | 21 | Largest concentration — fetcher bodies, wiring blocks, pagination internals |
| `TablePipelineTest.java` | 2 | `$fields` switch-arm content |
| `TypeClassGeneratorTest.java` | 2 | `$fields` platform-id arm content |
| `TableFieldPipelineTest.java` | 1 | `$fields` switch-arm content (G5) |
| `LookupTableFieldPipelineTest.java` | 1 | `$fields` switch-arm content (argres 2a) |
| `FetcherPipelineTest.java` | 1 | Helper (`findWiring`) that returns the rendered string for callers |
| **Total** | **28** | |

## Why now

The pattern spreads by default — every new pipeline test copies the nearest example, and `TypeFetcherGeneratorTest` is the largest neighbour. Argres Phase 2a's `LookupTableFieldPipelineTest` was the most recent copy. CLAUDE.md's ban is stated but unenforceable as long as the sibling tests model the anti-pattern.

## Audit by pattern

Assertions group into six patterns. Per-pattern verdict determines the per-case treatment.

### Pattern 1 — "method body is not a stub" (~5)

```java
assertThat(method(spec, "films").code().toString())
    .doesNotContain("UnsupportedOperationException");
```

**Verdict: delete.** Redundant with `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`'s four-way partition — any leaf in `IMPLEMENTED_LEAVES` or `PROJECTED_LEAVES` is guaranteed not to route through `stub(f)`. The pattern test is the right place for this invariant; per-field "is not stub" assertions are bookkeeping.

Sites: `TypeFetcherGeneratorTest:146, 275, 286, 521, 161` (the last is `doesNotContain("dataFetcher(")` on an empty wiring — structurally the same "this path didn't emit"; covered by a `wiring.methodSpec().code()` emptiness check or by the partition test).

### Pattern 2 — "method A references helper B" (~6)

```java
assertThat(method(spec, "filmById").code().toString()).contains("lookupFilmById");
assertThat(method(spec, "films").code().toString()).contains("filmsOrderBy(env)");
```

**Verdict: delete.** `graphitron-rewrite-test-spec` compile catches dangling references — an emitted call to a non-existent helper fails the compile-tier gate. Execution tier catches "calls the wrong helper" via GraphQL query results. Per-assertion body-substring matches neither signal cleanly and breaks on every emitter refactor.

Sites: `TypeFetcherGeneratorTest:244, 469, 543, 636, 645`; `FetcherPipelineTest:273` (the `findWiring` helper — see Pattern 6).

### Pattern 3 — "$fields switch arm for field X exists" (~5)

```java
assertThat(code).contains("case \"title\"");
assertThat(code).contains("case \"filmId\"");
```

**Verdict: rewrite via structural helper.** The arm's existence is a real structural fact — the type class either projects this field or doesn't. Today this can only be recovered by scanning the rendered body, but the gathering step in `TypeClassGenerator.generateForType` could be restructured to produce a `Map<String, FieldKind>` alongside the `$fields` method. Option A: refactor to carry the map and test it directly. Option B: add an `assertThat(typeSpec).hasFieldsArm("title")` AssertJ helper that performs the grep once, centralising the fragility.

Option B is smaller and doesn't touch generator shape. Worth it for the five assertions; if Pattern 4 also benefits (likely), the helper earns its keep.

Sites: `TableFieldPipelineTest:53, LookupTableFieldPipelineTest:54, TablePipelineTest:104, 106, 119`; `TypeClassGeneratorTest:83, 98`.

### Pattern 4 — "wiring contains / does not contain X" (~5)

```java
assertThat(wiringCode).contains("ColumnFetcher");
assertThat(wiringCode).contains("QueryFetchers::films");
assertThat(wiringCode).doesNotContain("LightDataFetcher");
```

**Verdict: mostly rewrite via structural helper, some delete.** The wiring is a map from field name to data-fetcher shape; same argument as Pattern 3. An `assertThat(typeSpec).wiresField("title").with(DataFetcherKind.COLUMN_FETCHER)` style helper captures the actual fact without body-scan fragility. Kinds are enumerable: `COLUMN_FETCHER`, `METHOD_REFERENCE`, `LAMBDA_UNWRAP`, `DSL_FIELD`.

`doesNotContain("LightDataFetcher")` — this asserts a negative (an internal detail of what we *don't* use). Delete unless there's a specific regression story; otherwise an assertion that we use `METHOD_REFERENCE` implicitly excludes `LightDataFetcher`.

Sites: `TypeFetcherGeneratorTest:169-170, 178-179, 189-190`.

### Pattern 5 — "body reflects runtime behaviour" (~11)

Connection / pagination / cursor internals: arg names, Relay validation literals, seek-helper usage, column-driven cursor decode.

```java
assertThat(code).contains("\"first\"");
assertThat(code).contains("\"after\"");
assertThat(code).contains("IllegalArgumentException");
assertThat(code).contains("first != null && last != null");
assertThat(code).contains(".seek(seekFields)");
assertThat(code).contains("decodeCursor(cursor, extraFields)");
```

**Verdict: delete — covered by execution tier.** The pagination-arg-name tests exist because `PaginationSpec` lets the schema author rename the args; the executable signal is a GraphQL query using those names against the test-spec schema, which the `graphitron-rewrite-test-spec` execution tests already exercise. The Relay-validation assertions (`IllegalArgumentException` + predicate literal) are the most fragile — they pin the generator's exact Java expression, which a stylistic rewrite (`Objects.requireNonNull`, early return, etc.) would break without changing behaviour. Execution tier catches "reject when both first and last present" with a query that supplies both and asserts the error.

Before deleting, confirm per-assertion: **is there a GraphQL-execution test that fails if the body's behaviour regresses?** If yes → delete. If no → write the execution test first, then delete. (See Open Decisions.)

Sites: `TypeFetcherGeneratorTest:297, 453, 527-530, 552-553, 569-573, 581-583, 590-592, 598-600, 635-637, 644-645`; `TablePipelineTest:120` (`doesNotContain("hidden")` on `@notGenerated` field — execution-tier check that hidden column doesn't surface covers this).

### Pattern 6 — infrastructure that returns rendered code (~1)

`FetcherPipelineTest.findWiring` returns `method.code().toString()`. Not an assertion itself; it's plumbing that enables Pattern 4 assertions. Deleted when Pattern 4 migrates to the structural helper.

## Approach

Two-phase work. Either can ship standalone if the other is deferred.

### Phase 1 — add the structural helper

New file: `graphitron-rewrite/src/test/java/.../util/TypeSpecAssertions.java` (or extend `TestFixtures`). Three helpers cover Patterns 3 + 4:

```java
// Pattern 3 — $fields switch arms
boolean hasFieldsArm(TypeSpec type, String fieldName);

// Pattern 4 — wiring dataFetcher kind per field
enum DataFetcherKind { COLUMN_FETCHER, METHOD_REFERENCE, LAMBDA, DSL_FIELD }
Optional<DataFetcherKind> wiringFor(TypeSpec type, String fieldName);

// Pattern 3 + optional — method existence with modifiers / params (already covered by JavaPoet API)
```

Implementation strategy: both `hasFieldsArm` and `wiringFor` render the target method's `CodeBlock` once internally, pattern-match the well-known shapes (`case "name" -> …`, `.dataFetcher($S, new $T<>(…))`), return the structured answer. Body-scan fragility is confined to one file; test call-sites become declarative.

If that feels like hiding the same pattern one level deeper: alternative is refactoring `TypeClassGenerator` / `TypeFetcherGenerator` to return the structured map as a side output. Larger change, better long-term. Defer unless the helper approach proves leaky.

### Phase 2 — the 28-site migration

Per-file sweeps, mechanically applying the per-pattern verdict:

- **Delete sites**: 11 of 28 remove outright (Patterns 1, 2, 6 + most of 5).
- **Rewrite via helper**: 10 of 28 use Phase 1's helpers (Patterns 3, 4).
- **Rewrite via execution test**: ~5 migrate to / are covered by a new or existing `GraphQLQueryTest` case. Each of these needs a companion execution-tier assertion to land in the same commit (no net coverage loss).
- **Keep with justification**: 2 remaining — `TypeFetcherGeneratorTest:297` (`getDataType()` in inputRows helper) and `TypeFetcherGeneratorTest:453` (`OrderByResult(` in `filmsOrderBy`). Both assert on a specific Java construct inside a generator helper where neither compile nor execution catches the regression cleanly. Flag both with an inline `// intentional body-content assertion — no structural equivalent` comment so the exception is visible to future copy-pasters. Revisit when the inner helpers get a proper sub-model.

## Commit structure

Three commits, ordered. Can compress to two if the helper is trivial.

### C1 — `TypeSpecAssertions` helpers

New test-only file with `hasFieldsArm` + `wiringFor` (and a small enum). Covers ~10 of the 28 sites when applied. One commit, test-infrastructure only, no production code touched.

### C2 — migrate the 28 sites

Six-file sweep. For each assertion: apply the verdict from the audit above. Most are deletions; the rewrites use the helper from C1. Any sites that needed a new execution-tier test land it alongside the deletion in the same commit.

Commit message body lists the per-assertion disposition (delete / rewrite / keep) so a reviewer can match against the audit without re-reading the diff. This is the review-critical commit.

### C3 (optional) — enforce via lint

Add a small `ForbiddenWordsTest` or checkstyle rule that flags `code().toString()` in `src/test/**` and fails the build. Prevents the pattern from being re-introduced by the next copy-paste. If the migration itself is the discipline (reviewer rejection), defer C3 until we see a regression.

## Out of scope

- Refactoring the generators to emit a structured side-product alongside `TypeSpec`. The helper approach confines the body-scan to one file; a generator-side refactor is a larger change with its own justification needed.
- Banning `.code()` entirely — `CodeBlock` authoring is legitimate inside production code. The ban is specifically on `.toString().contains(...)` in tests.
- Pipeline tests outside `graphitron-rewrite` (e.g. `graphitron-codegen-parent` legacy tests). Different generator, different conventions.

## Open decisions

1. **Helper vs. generator-side refactor for Patterns 3 + 4.** Recommend helper (smaller, local). Revisit if the helper's pattern-matching grows past ~3 shapes.
2. **Execution-tier companion tests for Pattern 5 deletions.** Confirm per-assertion before deleting — some may already be covered, others may need a new execution test. If companion tests are required for more than ~3 sites, split C2 into "deletes + rewrites" and "execution-tier additions" commits.
3. **C3 lint gate: adopt now, or defer?** Defer. Landing the ban with the migration risks blocking unrelated test edits that happen to touch the forbidden pattern. Add C3 only if the pattern re-appears within a release cycle.
4. **`TypeFetcherGeneratorTest:453` (`OrderByResult(`).** Confirm that the orderBy helper's correctness is covered end-to-end by the pagination execution tests. If yes, delete. If no, keep with the marker comment.

## Completion criteria

- Zero `code().toString()` uses in `graphitron-rewrite/src/test/**` (grep clean), OR every remaining site carries the `// intentional body-content assertion` marker.
- All tests green on `mvn -pl :graphitron-rewrite test`.
- Execution tests in `graphitron-rewrite-test-spec` still green — the whole point is that behavioural coverage doesn't regress.
- Optional: C3 lint test rejecting new occurrences.

## History

- **2026-04-19 (In Progress → Pending Review)** — C1 + C2 landed. `TypeSpecAssertions` helper added (`hasFieldsArm`, `wiringFor(field) → DataFetcherKind`, `hasNoDataFetchers`). 28 body-substring sites reduced to 4 intentionally-marked ones — honest disposition documented in C2's commit message. Open decisions resolved:
  - **OD 1 (helper vs generator refactor).** Adopted helper. Scan fragility confined to one file.
  - **OD 2 (execution-tier companions).** Two kept sites (`connectionField_customPaginationArgNames_emittedInFetcher` and `connectionField_emitsRelayValidation_firstAndLastConflict`) would need new execution fixtures to delete cleanly — deferred as follow-up rather than bundled. Plan budgeted 2 kept; actual is 4, with the extra two flagging real execution-coverage gaps.
  - **OD 3 (C3 lint gate).** Deferred per the plan's recommendation. Revisit if the pattern re-appears.
  - **OD 4 (`OrderByResult(` assertion).** Deleted — redundant with the sibling return-type assertion (`.returnType().toString().endsWith("OrderByResult")`). Java's type system forces the body to construct it.
- **2026-04-19 (In Progress)** — author-of-Draft starts implementation on user direction; workflow's Draft → Approved independent reviewer still needed for the Pending Review → Done transition.
- **2026-04-19 — drafted.** Status: Draft. Audit of 28 sites across 6 files grouped them into 6 patterns with per-pattern verdicts: 11 delete / 10 rewrite via helper / ~5 rewrite via execution test / 2 keep with justification. Three-commit structure (helper / migration / optional lint). Four open decisions pinned.
