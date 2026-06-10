---
name: classified-corpus
description: Migrate one classification verdict into the R281 spec-by-example corpus and the code-generation-triggers documentation, retiring the legacy GraphitronSchemaBuilderTest enum row it subsumes. Use when grinding R281 slice 2/3, or when the user says "migrate the X verdict", "add a corpus example for X", "document the X classification", "render X from the corpus", or "retire the X enum case". Encodes the per-verdict loop: author fixture, validate dimensions, render the doc block, write the worked example, retire pure-verdict enum rows, verify coverage + drift + docs render.
---

# classified-corpus

The per-verdict migration loop for R281 (`classification-test-dsl`). The spec-by-example corpus is the
source of truth; the `code-generation-triggers` page is a view rendered over it; the legacy
`GraphitronSchemaBuilderTest` enum truth table shrinks as verdicts migrate. One verdict at a time, one
commit each.

## The files

All under `graphitron-rewrite/`:

- **Corpus** (source of truth): `graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/ClassifiedCorpus.java`. A `List<Example>`; each `Example(id, sdl[, query])`. A non-null `query` makes it a doc example.
- **Harness / DSL test**: `.../classifieddsl/ClassifiedHarness.java` (classifies a fixture, reads `@classified`/`@classifiedType` off the AST, maps the leaf through `LeafTupleAdapter`), `ClassifiedDslTest.java` (asserts every annotated coordinate classifies to its declared dimensions).
- **Renderer + drift guard**: `QueryViewRenderer.java` (query-as-view, AST-print, strips the internal directives), `ClassifiedDocTest.java` (asserts each doc example's rendered SDL appears verbatim in the page).
- **Dimensional vocabulary**: `ClassifiedDsl.java` (the `@classified`/`@classifiedType` + `ProducerStep`/`Mapping`/`TypeVerdict` SDL prelude, declared test-only, ignored by the classifier), `DimensionTuple` / `ProducerStep` / `Mapping` / `LeafTupleAdapter`.
- **The page**: `docs/code-generation-triggers.adoc`.
- **Legacy truth table**: `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` (the `*Case implements ClassificationCase` enums).
- **Coverage bridge**: `graphitron/src/test/java/no/sikt/graphitron/rewrite/VariantCoverageTest.java` (unions corpus-covered leaves with enum-case leaves; the safety net for retirement). Its `NO_CASE_REQUIRED` allowlist documents leaves unreachable from the standard Sakila catalog; the slice-3 sweep interacts with it, and those leaves stay allowlisted rather than swept.
- **Retirement inventory** (the deletion whitelist): `roadmap/audits/classification-test-dsl-inventory.md`. The committed checklist of pure-verdict candidate rows; only rows listed there may retire, and each migration commit ticks its row off.

## The loop

### 1. Find the verdict's current home
Start from the retirement inventory (`roadmap/audits/classification-test-dsl-inventory.md`): it names the
candidate rows and their leaves; pick the next unticked row. Then grep `GraphitronSchemaBuilderTest.java`
for the leaf (e.g. `ServiceTableField`, `ColumnField`). For each matching enum case, read its assertion
lambda and confirm its classification:
- **pure-verdict** = the lambda only asserts `isInstanceOf(<Leaf>.class)` (optionally `.reason()`-free). These are retirement candidates.
- **slot-asserting** = the lambda also asserts `joinPath()`, `returnType().wrapper()`, `filters()`, `sourceKey()`, `compaction`, key columns, etc. **Keep these** — the corpus asserts the two-axis verdict, not slots; those assertions are the pipeline tier's job.

### 2. Author the corpus example
Add an `Example` to `ClassifiedCorpus.EXAMPLES`. Rules:
- Fixtures classify against the **standard Sakila catalog**. Use real tables/columns/FKs. Prefer unambiguous single FKs (e.g. `city -> country`; **avoid** `film -> language`, which has two FKs and is ambiguous). Mine working SDL from the `GraphitronSchemaBuilderTest` case you're migrating.
- Annotate each coordinate: output fields with `@classified(producer: [...], mapping: ...)`, types with `@classifiedType(as: ...)`. Empty `producer: []` is the inline/correlate (no new query) case.
- For a doc example, add a `query` selecting exactly the coordinates to show. **Minimal pairs teach best**: vary one dimension, hold the rest constant (e.g. the same return type with/without `@splitQuery` for `producer`; a scalar under a `@table` vs a `@record` parent for `mapping`).
- **Doc examples are field/catalog-side only for now.** The renderer does not yet expand input types reachable from a kept field's arguments, and fragment-only (`on Type`) selection is unimplemented, so a mutation or type verdict cannot render an honest excerpt. Migrate those **corpus-only** (omit `query`, skip steps 4-5) until the R281 pre-migration-hardening renderer extensions land.

### 3. Validate the dimensions (discover the true verdict)
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn -f graphitron-rewrite/pom.xml -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDslTest' -Dsurefire.failIfNoSpecifiedTests=false
```
`corpusClassifiesToDeclaredDimensions` fails if a declared `@classified` doesn't match what the
classifier produces. Fix the declared dimensions (or the fixture) until green — this step is where you
*learn* the verdict; do not force the fixture to a hunch.

### 4. Capture the rendered block
```bash
mvn -f graphitron-rewrite/pom.xml -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDocTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -A 25 "doc example '<id>'"
```
The failure message prints the exact SDL block (AstPrinter form, e.g. `@table(name: "city")`). Copy it.

### 5. Write the worked example in the page
In `code-generation-triggers.adoc`, add prose stating the rule **in dimensional terms** (producer/mapping
for fields, the `GraphitronType` leaf for types — never cross-product leaf names on the field side, that
is what survives the R222 pivot) + a `[source,graphql]` block holding the captured SDL **verbatim** +
a closing "Asserted by the `<id>` corpus example." Condense the superseded leaf-name table rows into the
worked example as you go (the tables are the transitional reference and shrink as the doc grows).

### 6. Retire the enum row(s)
Delete **only** the pure-verdict cases from step 1 that are listed in the retirement inventory and that
the corpus now covers. **Verify corpus pickup directly, not via `VariantCoverageTest`**: the union net
is one-way (a deleted row's leaf may still be covered by a *different* enum row, so a green run proves
nothing about pickup). The step-3 harness run records the sealed leaf per `@classified` coordinate;
confirm your new example's coordinate lands on the exact leaf the row asserts before deleting. Replace
each deleted case with a one-line comment noting the migration (corpus example id + where it renders),
and tick the row off in the inventory in the same commit. **Never retire**:
- slot-asserting cases (keep them),
- rejection / `UnclassifiedField` / `UnclassifiedType` rows (failure path is out of scope; a separate mechanism replaces them),
- input-field rows (`InputField.*`; a different game, out of scope).

### 7. Verify
```bash
mvn -f graphitron-rewrite/pom.xml -pl :graphitron -am test -Plocal-db -P'!docs' \
  -Dtest='ClassifiedDslTest,ClassifiedDocTest,VariantCoverageTest,GraphitronSchemaBuilderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# docs must render (a .adoc break fails CI):
mvn -f graphitron-rewrite/pom.xml -pl :graphitron-docs -am install -DskipTests
```
`VariantCoverageTest` is the retirement safety net: if it goes red you deleted the sole coverage for a
leaf the corpus does not yet demonstrate — re-add the case or extend the fixture.

### 8. Commit + publish
One commit per verdict, subject `R281 slice 2: migrate <verdict> ...`. Publish via the **publish**
skill (push feature branch + fast-forward trunk).

## Guardrails

- **The inventory is the deletion whitelist.** A row not listed in
  `roadmap/audits/classification-test-dsl-inventory.md` does not retire, full stop. The inventory
  settled the pool at 35 pure-verdict eligible rows of 407; the slot-asserting (170), rejection (178),
  and input-side (24) rows stay by design.
- **Green `VariantCoverageTest` is not proof of corpus pickup.** Its union also counts the remaining
  enum rows; verify the leaf lands in the corpus per step 6.
- **Success-only.** The corpus asserts the happy path. Rejection and input-field rows stay in the enum table.
- **Verdict, not slots.** Migrate the `(producer, mapping)` / `TypeVerdict` verdict. Slot detail stays in the pipeline tier (the slot-asserting enum cases).
- **Drift is exact.** The page must contain the rendered block byte-for-byte; re-capture after any fixture change.
- **Coverage is the net.** A leaf can be covered by several enum cases and/or the corpus; trust `VariantCoverageTest`, do not eyeball.
- **Test-only directives** live in `ClassifiedDsl.PRELUDE`, never in production `directives.graphqls`; the classifier ignores them.
