---
name: classified-corpus
description: Author one classification verdict into the spec-by-example corpus and the code-generation-triggers documentation, retiring a pure-verdict GraphitronSchemaBuilderTest enum row if the new example subsumes one. Use when the user says "add a corpus example for X", "migrate the X verdict", "document the X classification", "render X from the corpus", or "retire the X enum case". Encodes the per-verdict loop: author fixture, validate dimensions, render the doc block, write the worked example, retire a subsumed enum row, verify coverage + drift + docs render.
---

# classified-corpus

The per-verdict loop for the `@classified` spec-by-example corpus. The corpus is the source of truth for
output-field and type verdicts; the `code-generation-triggers` page is a view rendered over it. One
verdict at a time, one commit each.

The dominant use is **additive**: a new feature, leaf, or shape needs a corpus example. Retiring an
enum row (step 6) is the narrow secondary case, and the historical bulk migration is finished, see the
retirement note under *The files*.

## The files

All under the repo root:

- **Corpus** (source of truth): `graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/ClassifiedCorpus.java`. A `List<Example>`; each `Example(id, sdl[, query])`. A non-null `query` makes it a doc example.
- **Harness / DSL test**: `.../classifieddsl/ClassifiedHarness.java` (classifies a fixture, reads `@classified`/`@classifiedType` off the AST, records the sealed leaf each coordinate landed on), `ClassifiedDslTest.java` (asserts every annotated coordinate classifies to its declared dimensions, and that every dimension arm is exercised or on a stated known-gap list).
- **Renderer + drift guard**: `QueryViewRenderer.java` (query-as-view, AST-print, strips the internal directives; expands argument input-type closure and renders unions/interfaces reached by a kept field or a `fragment on Type`), `ClassifiedDocTest.java` (asserts each doc example's rendered SDL appears verbatim in the page).
- **Dimensional vocabulary**: `ClassifiedDsl.java` (the `@classified`/`@classifiedType` directives plus the `SourceWrapper` / `Operation` / `TargetWrapper` / `TargetShape` / `SourceShape` / `TypeVerdict` SDL prelude, declared test-only, ignored by the classifier), `DimensionTuple` (the three-axis verdict record, `source` + `operation` arm token + `TargetVerdict`).
- **The page**: `docs/architecture/reference/code-generation-triggers.adoc`.
- **Enum truth table**: `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` (the `*Case implements ClassificationCase` enums). It keeps the slot-asserting, rejection, and input-side rows by design; those are not corpus material.
- **Coverage obligations**: `graphitron/src/test/java/no/sikt/graphitron/rewrite/VariantCoverageTest.java`. Two partitioned checks, no union: `everyOutputFieldAndTypeLeafIsDemonstratedByTheCorpus` reads `ClassifiedCorpus.coveredLeaves()` **alone** (so a green run *is* proof the corpus carries an output-field or type verdict), and `everyInputFieldLeafHasAnEnumClassificationCase` keeps input-field leaves on the enum table. Its `NO_CASE_REQUIRED` allowlist documents leaves unreachable from the standard Sakila catalog; those stay allowlisted rather than forced into a fixture.
- **Historical retirement inventory**: `roadmap/audits/classification-test-dsl-inventory.md`. The bulk migration's deletion whitelist, **exhausted and closed**: all 35 eligible rows retired, and a 2026-07-25 re-derivation found no pure-verdict rows left in the enum. Read it as lineage, not as a work queue; a large surviving `GraphitronSchemaBuilderTest` is the excluded buckets, not residue.

## The loop

### 1. Find the verdict's current home
Grep `GraphitronSchemaBuilderTest.java` for the leaf (e.g. `ServiceTableField`, `ColumnBackedField`) and
for any dedicated pipeline test covering the shape. For each matching enum case, read its assertion
lambda and bucket it yourself; do not lean on the historical inventory, whose row list predates the
current leaf set:
- **pure-verdict** = the lambda asserts only `isInstanceOf(<Leaf>.class)` on the coordinate. Retirement candidate (step 6). Expect to find none: the bulk migration exhausted this bucket.
- **slot-asserting** = the lambda reads an accessor: `joinPath()`, `returnType().wrapper()`, `filters()`, `sourceKey()`, `columnName()`, key columns, warnings. **Keep these** — the corpus asserts the three-axis verdict, not slots; slot detail is the pipeline tier's job. Note the trap: a slot read asserted with `isInstanceOf` (e.g. `assertThat(field.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class)`) is slot-asserting, not pure.
- **rejection** or **input-side** = out of scope entirely (see the never-retire list in step 6).

### 2. Author the corpus example
Add an `Example` to `ClassifiedCorpus.EXAMPLES`. Rules:
- Fixtures classify against the **standard Sakila catalog**. Use real tables/columns/FKs. Prefer unambiguous single FKs (e.g. `city -> country`; **avoid** `film -> language`, which has two FKs and is ambiguous). Mine working SDL from an existing enum case or pipeline test covering the shape.
- Annotate each coordinate: output fields with `@classified(source: ..., operation: ..., target: ..., targetShape: ...)` (plus `sourceShape:` where the arrival shape matters), types with `@classifiedType(as: ...)`. The enum value spaces live in `ClassifiedDsl.PRELUDE`, and a typo is a schema-assembly error before the harness runs.
- For a doc example, add a `query` selecting exactly the coordinates to show. **Minimal pairs teach best**: vary one axis, hold the rest constant (e.g. the same return type with and without `@splitQuery` to isolate the batched delivery; a scalar under a `@table` vs a `@record` parent to isolate `targetShape`).
- A `query` may also be a bare `fragment F on Type { ... }` when the coordinate has no reachable root path; the renderer resolves argument input-type closure and polymorphic members, so mutation and type verdicts can render honest excerpts too. Omit `query` (and skip steps 4-5) when an example is worth testing but not worth featuring in the page.

### 3. Validate the dimensions (discover the true verdict)
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDslTest' -Dsurefire.failIfNoSpecifiedTests=false
```
`corpusClassifiesToDeclaredDimensions` fails if a declared `@classified` doesn't match what the
classifier produces. Fix the declared dimensions (or the fixture) until green — this step is where you
*learn* the verdict; do not force the fixture to a hunch.

### 4. Capture the rendered block
```bash
mvn -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDocTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -A 25 "doc example '<id>'"
```
The failure message prints the exact SDL block (AstPrinter form, e.g. `@table(name: "city")`). Copy it.

### 5. Write the worked example in the page
In `code-generation-triggers.adoc`, add prose stating the rule **in dimensional terms** (the
`(source, operation, target)` axes for fields, the `GraphitronType` leaf for types; never cross-product
leaf names on the field side, the axes are what the dimensional model exposes) + a `[source,graphql]`
block holding the captured SDL **verbatim** +
a closing "Asserted by the `<id>` corpus example." Condense the superseded leaf-name table rows into the
worked example as you go (the tables are the transitional reference and shrink as the doc grows).

### 6. Retire the enum row(s), if step 1 found any
Only when step 1 identified a **pure-verdict** case that your new example subsumes. This is now rare;
skip the step when it finds nothing rather than hunting for something to delete.

Verify pickup at the coordinate, not at the leaf: `VariantCoverageTest`'s corpus obligation is a
per-leaf net, so it stays green when a *different* example already covers the leaf, even though the
shape this row pinned is gone. The step-3 harness run records the sealed leaf per `@classified`
coordinate; confirm your example's coordinate lands on the exact leaf the row asserts, and that the row
asserted nothing else, before deleting. Replace each deleted case with a one-line comment naming the
corpus example that took it over (and where it renders, if it is a doc example). **Never retire**:
- slot-asserting cases (keep them),
- rejection / `UnclassifiedField` / `UnclassifiedType` rows (failure path is out of scope; a separate mechanism replaces them),
- input-field rows (`InputField.*`; a different game, out of scope).

### 7. Verify
```bash
mvn -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDslTest,ClassifiedDocTest,VariantCoverageTest,GraphitronSchemaBuilderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# docs must render (a .adoc break fails CI):
mvn -pl :graphitron-docs -am install -DskipTests
```
`VariantCoverageTest` red means a leaf now has no demonstration at all: extend the fixture, or re-add
the case if the corpus genuinely cannot reach the leaf (then give it a stated `NO_CASE_REQUIRED` entry
instead).

### 8. Commit + publish
One commit per verdict, subject naming the verdict and the example id (e.g.
`corpus: add the <id> example for <verdict>`). Publish via the **publish** skill (push feature branch +
fast-forward trunk).

## Guardrails

- **Additive by default.** The bulk retirement is done. The reason to run this loop is a verdict the
  corpus does not yet demonstrate; a deleted enum row is an occasional by-product, never the goal. The
  enum table's remaining size is its excluded buckets (slot-asserting, rejection, input-side), which
  stay by design, not a backlog.
- **A pure-verdict claim is re-derived, never inherited.** Bucket the row by reading its lambda now (step
  1). The historical inventory's row list predates the current leaf set and its pool is exhausted.
- **Green `VariantCoverageTest` proves leaf coverage, not coordinate coverage.** The corpus obligation
  reads `coveredLeaves()` alone, so it does prove some example carries the leaf; it cannot tell you the
  *shape* a deleted row pinned survived. Verify at the coordinate per step 6.
- **Success-only.** The corpus asserts the happy path. Rejection and input-field rows stay in the enum table.
- **Verdict, not slots.** Assert the `(source, operation, target)` axes / `TypeVerdict`. Slot detail stays in the pipeline tier (the slot-asserting enum cases).
- **Drift is exact.** The page must contain the rendered block byte-for-byte; re-capture after any fixture change.
- **Test-only directives** live in `ClassifiedDsl.PRELUDE`, never in production `directives.graphqls`; the classifier ignores them.
