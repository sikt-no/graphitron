---
id: R180
title: Centralize ResultType column-read emission for @record parents
status: Spec
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# Centralize ResultType column-read emission for @record parents

Every emitter that needs to "read a named column off the parent record"
reconstructs its own switch over `ResultType` and emits subtly different
code per arm: `JooqTableRecordType` → `.get(Tables.X.COL)`,
`JooqRecordType` → `.get(sqlName)`, `JavaRecordType` → `.camelCase()`,
`PojoResultType.Backed` → `.getCamelCase()`. The same predicate (which
kind of result parent? which Java syntax reads a column?) is evaluated
at every site, and each site picks its own subset of arms:
`FetcherEmitter.propertyOrRecordValue` collapses two arms with `||`,
`GeneratorUtils.buildFkRowKey` keeps all four,
`GeneratorUtils.backingClassOf` throws on two of them.

The duplication is a drift hazard: when a fifth `ResultType` variant is
added (or a current arm's emission shape changes), each site has to be
audited and patched independently, and the subset-selection asymmetry
makes it easy to miss one.

## Audit findings

`ResultType` is a five-arm sealed interface (`GraphitronType.java:91`):
`JooqTableRecordType`, `JooqRecordType`, `JavaRecordType`,
`PojoResultType.Backed`, `PojoResultType.NoBacking`. Three predicates
sit on top of it today, asked at distinct sites:

* **"Emit a column read off the parent."** The shared one.
  `FetcherEmitter.propertyOrRecordValue` (FetcherEmitter.java:802) and
  `GeneratorUtils.buildFkRowKey` (GeneratorUtils.java:210). Five arms
  on the fetcher path (NoBacking falls back to
  `PropertyDataFetcher.fetching(name)`); four arms on the row-key path
  (NoBacking is rejected upstream by
  `deriveFkRecordParentSource` at FieldBuilder.java:4184). The jOOQ
  arms vary by whether a table reference is in hand: with a table,
  `.get(Tables.X.COL)`; without one, `.get(sqlName)`.
* **"Give me the parent's backing class."** A different question.
  `GeneratorUtils.backingClassOf` (line 339) and
  `SourceRowDirectiveResolver.parentBackingClass` (line 450). Both
  accept only `PojoResultType.Backed` + `JavaRecordType`; jOOQ arms
  are rejected. R180 leaves these alone.
* **"Does the parent admit a backing-class cast?"** Single-site
  predicate (`BuildContext.java:580`, `target instanceof NoBacking`
  exclusion). Not column-read; out of scope.

The per-site arm-set variation on the column-read predicate is
**meaningful policy**, not incidental drift: NoBacking has no column
to read, so the row-key site's upstream rejection and the fetcher
site's property-name fallback are deliberate. R180 centralizes the
*emission shape*; per-site guards stay where they are.

A second asymmetry the audit surfaces, deferred (see Non-goals):
`propertyOrRecordValue` consumes a pre-resolved
`AccessorResolution.Resolved` for the Java/Pojo arms (the builder
reflected on the backing class at classification time and picked the
right `Method` or `Field`). `buildFkRowKey` does not: it synthesizes
`get<Camel>(sqlName)` for `Pojo.Backed` and `<camel>(sqlName)` for
`JavaRecordType`, riding on the assumption that the backing class's
accessor names follow that convention. The dispatcher API surfaces
this asymmetry as distinct arms so it is visible at the type level
rather than hidden inside two near-duplicate `if` chains.

## Implementation

Introduce a sealed `ColumnReadShape` value type and a single dispatcher
that produces it from `(ResultType parent, ColumnRef column, String
sqlName, AccessorResolution.Resolved accessor)`. Each callsite
exhaustively switches on the shape and wraps it in its own surrounding
syntactic context (DataFetcher factory vs. inline expression inside
`DSL.row(...)`).

### New type and dispatcher

Add to `no.sikt.graphitron.rewrite.generators` (sibling of
`FetcherEmitter` and `GeneratorUtils`):

```java
sealed interface ColumnReadShape {
    record TableColumnRef(TableRef table, ColumnRef column)
        implements ColumnReadShape {}                    // JooqTableRecord + table known
    record ColumnByName(String sqlName)
        implements ColumnReadShape {}                    // JooqRecord, or JooqTableRecord without table
    record ResolvedAccessor(AccessorResolution.Resolved accessor, ClassName backingClass)
        implements ColumnReadShape {}                    // pre-resolved Java/Pojo accessor
    record SyntheticGetter(ClassName backingClass, String getterName)
        implements ColumnReadShape {}                    // Pojo.Backed via "get<Camel>" convention
    record SyntheticRecordAccessor(ClassName backingClass, String camelName)
        implements ColumnReadShape {}                    // JavaRecord via "<camel>()" convention
    record PropertyByName(String name)
        implements ColumnReadShape {}                    // NoBacking (fetcher-only fallback)
}

final class RecordColumnReads {
    static ColumnReadShape shapeOf(
            GraphitronType.ResultType parent,
            ColumnRef column,                // nullable (column ref may be unresolved)
            String sqlName,                  // always present
            AccessorResolution.Resolved accessor // nullable; present on the fetcher path
    ) {
        return switch (parent) {
            case GraphitronType.JooqTableRecordType jtrt when column != null && jtrt.table() != null
                -> new TableColumnRef(jtrt.table(), column);
            case GraphitronType.JooqTableRecordType ignored -> new ColumnByName(sqlName);
            case GraphitronType.JooqRecordType ignored      -> new ColumnByName(sqlName);
            case GraphitronType.JavaRecordType jrt -> accessor != null
                ? new ResolvedAccessor(accessor, ClassName.bestGuess(jrt.fqClassName()))
                : new SyntheticRecordAccessor(ClassName.bestGuess(jrt.fqClassName()), toCamelCase(sqlName));
            case GraphitronType.PojoResultType.Backed b -> accessor != null
                ? new ResolvedAccessor(accessor, ClassName.bestGuess(b.fqClassName()))
                : new SyntheticGetter(ClassName.bestGuess(b.fqClassName()),
                                      "get" + capitalize(toCamelCase(sqlName)));
            case GraphitronType.PojoResultType.NoBacking ignored -> new PropertyByName(sqlName);
        };
    }
}
```

The switch is the only `instanceof ResultType` over the column-read
predicate after the lift. The exhaustiveness check is what keeps the
sites in lockstep when a sixth variant is added.

### Callsite migration

* `FetcherEmitter.propertyOrRecordValue` (FetcherEmitter.java:802)
  becomes a thin formatter: call `shapeOf(resultType, column, columnName,
  accessor)`, switch on the result, wrap each shape into the existing
  fetcher-registration shape (`ColumnFetcher<>(...)` for the jOOQ arms,
  `PropertyDataFetcher.fetching(...)` for `PropertyByName`, lambda over
  `env.getSource()` for the accessor arms). The accessor-arm formatter
  retains `methodCallExpr`'s three injection forms (zero-arg,
  full-environment, per-argument) verbatim.
* `GeneratorUtils.buildFkRowKey` (GeneratorUtils.java:210) becomes:
  call `shapeOf(resultType, col, col.sqlName(), null)` per FK column,
  switch on the result rejecting `PropertyByName` with an
  `IllegalStateException` (NoBacking is excluded upstream, so this arm
  is structurally unreachable), and emit `(($T) env.getSource()).<expr>`
  where `<expr>` is the per-shape access call.

### Load-bearing classifier check

The `buildFkRowKey` callsite relies on the classifier guarantee that
`PojoResultType.NoBacking` never reaches this emission path
(`deriveFkRecordParentSource` rejects it at FieldBuilder.java:4184).
The dispatcher's exhaustive switch admits `PropertyByName`; the
row-key formatter rejects it. Mark the guarantee with paired
annotations so a future relaxation surfaces as an orphaned check, not
a runtime `IllegalStateException` for an end user:

* `@LoadBearingClassifierCheck(key = "fk-record-parent-source-rejects-nobacking", ...)`
  on `FieldBuilder.deriveFkRecordParentSource`'s `NoBacking` early
  return.
* `@DependsOnClassifierCheck(key = "fk-record-parent-source-rejects-nobacking", ...)`
  on `buildFkRowKey`'s `PropertyByName` rejection.

The existing `DependsOnClassifierCheck` annotations on
`buildLifterRowKey`, `buildAccessorKeySingle`, and `buildAccessorKeyMany`
(GeneratorUtils.java:239+) are the pattern.

### Helper hygiene

Place `RecordColumnReads` and `ColumnReadShape` in the existing
`generators` package next to their two consumers. The dispatcher is
package-private; the shape's permits are package-private records.
Both formatters live as `private static` methods in their respective
emitter classes — no new shared formatter class.

`backingClassOf` (GeneratorUtils.java:339) and
`SourceRowDirectiveResolver.parentBackingClass` are explicitly *not*
folded into the dispatcher. They answer a different question and the
audit above documents why; collapsing them into `RecordColumnReads`
would couple two unrelated predicates onto the same surface and force
column-read sites to handle a "no backing class" reject path they
already don't need.

## Tests

R180 is a refactor with no SDL-visible behaviour change. The signals
that it landed cleanly:

* Existing pipeline tier on `FetcherEmitter` and `GeneratorUtils`'s
  callsites continues to pass (carrier-field role coverage, FK
  splitQuery dispatch, record-parent key extraction).
* `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` stays green
  end-to-end (build-fixtures → test → compile-spec → execute-spec).

Per `rewrite-design-principles.adoc`, no per-dispatcher unit test
asserting `CodeBlock` string equality on `.get(Tables.X.COL)` etc.;
that pattern is banned at every tier. The pipeline tier already
verifies the four callsites' emission shapes by compiling generated
code against the sakila catalog.

## Validator mirror

R180 introduces no new classifier branch (the dispatcher is a
mechanical lift of switches that already exist). The validator's
existing coverage of `@record` parent shapes is unchanged; no new
validator rule is required.

## Non-goals (follow-up Backlog items)

* **Carry resolved accessors through `SourceKey`.** The
  `SyntheticGetter` and `SyntheticRecordAccessor` arms encode an
  unverified "name follows convention" assumption. The right fix is to
  pre-resolve a backing-class accessor handle at classification time
  (mirroring what `ChildField.PropertyField.accessor()` already does)
  and have the row-key path carry it through `SourceKey`. R180 surfaces
  the asymmetry at the type level (two arms vs. one) so it is visible;
  a separate Backlog item should propose the lift.
* **Collapse `backingClassOf` and `parentBackingClass`.** These are
  near-duplicates of each other but a different predicate from
  column-read. They are candidates for their own helper lift; out of
  scope here.

## Roadmap entries

None to add; the follow-up items above will be filed as their own
Backlog stubs after R180 lands.
