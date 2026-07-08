---
id: R446
title: "Codegen crashes on array-typed columns: ClassName.bestGuess rejects jOOQ array class descriptors"
status: Backlog
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Codegen crashes on array-typed columns: ClassName.bestGuess rejects jOOQ array class descriptors

Code generation crashes on any array-typed database column. This is **not** part of the schema-qualified `@table` bug class (fully closed by R396/R440/R441/R442/R422/R445); it is a separate, pre-existing codegen bug that only became reachable once opptak's schema stopped producing author-errors and the emit phase actually ran. It hard-blocks a consuming team's `graphitron:dev` / `generate`, with no author-side workaround.

## Symptom

`graphitron:dev` (and `generate`) abort with:

```
java.lang.IllegalArgumentException: couldn't make a guess for [Ljava.lang.Boolean;
```

The `IllegalArgumentException` is raised by `ClassName.bestGuess` (`graphitron-javapoet/.../ClassName.java:229`, via `Util.checkArgument` at `Util.java:56`): for the descriptor `[Ljava.lang.Boolean;` the leading `[` is not an uppercase simple-name start, so the second `checkArgument` fails. The `validate` goal passes; only the emit goals crash.

Observed stack: `TypeFetcherGenerator.buildServiceDataFetcher` (`TypeFetcherGenerator.java:6141`, calling `buildKeyExtraction` at `:6165`) -> `GeneratorUtils.buildKeyExtraction` (`GeneratorUtils.java:537`), crashing at the `SourceKey.Wrap.TableRecord` arm's `ClassName.bestGuess(col.columnClass())` (`GeneratorUtils.java:572`).

## Root cause

`ColumnRef.columnClass()` carries jOOQ's `col.getType().getName()` (`JooqCatalog.java:940` and `:1053`, plus the `ColumnRef` path at `:1240`). For a scalar column that is a source-form FQCN (`java.lang.Boolean`), which `bestGuess` accepts. For an **array-typed** column `getType().getName()` returns the JVM **binary descriptor**, e.g. a Postgres `boolean[]` -> `"[Ljava.lang.Boolean;"`, a `text[]` -> `"[Ljava.lang.String;"`. `ClassName.bestGuess` only accepts source-form FQCNs, so any array column blows it up.

There is no author-side workaround. In `buildKeyExtraction`'s `SourceKey.Wrap.TableRecord` arm the loop is over `parentTable.allColumns()` (`GeneratorUtils.java:568`), i.e. every GraphQL-mapped field of the node type, not just the key columns. Hiding the array field in the schema does not matter; the whole node type fails to emit as soon as any of its mapped columns is array-typed.

## Blast radius (audit all before fixing)

The `ClassName.bestGuess(<ColumnRef>.columnClass())` pattern appears at the following sites (verified against trunk). The all-columns iterators are the ones **currently reachable** by this crash; the remaining sites are **latent** (an array-typed key/PK column is unusual but not impossible). Fix them consistently.

Reachable (iterate the full mapped column set):

* `generators/GeneratorUtils.java:572` (TableRecord key-extraction arm, over `parentTable.allColumns()`) - the crash site
* `generators/GeneratorUtils.java:487` (`buildKeyExtractionWithNullCheck`, Row arm)
* `generators/SplitRowsMethodEmitter.java:879`
* `generators/MultiTablePolymorphicEmitter.java:1853`

Latent (key / PK / participant / decode / lookup column classes):

* `generators/FetcherEmitter.java:501`, `:657`, `:670`
* `generators/SplitRowsMethodEmitter.java:269`, `:673`, `:837`, `:1385`
* `generators/MultiTablePolymorphicEmitter.java:909`, `:1060`, `:1168`, `:1556`, `:1905`
* `generators/TypeFetcherGenerator.java:1689`, `:2779`
* `generators/TypeConditionsGenerator.java:282`
* `generators/CompositeDecodeHelperRegistry.java:119`, `:232`, `:242`
* `generators/InlineLookupTableFieldEmitter.java:123`
* `generators/util/SelectMethodBody.java:137`
* `generators/util/NodeIdEncoderClassGenerator.java:219`
* `generators/util/ValuesJoinRowBuilder.java:104`
* `model/SourceKey.java:207`
* `model/ChildField.java:285`, `:314`, `:351`, `:1072`

(All confirmed via `grep -rn "bestGuess" graphitron/src/main/java | grep "columnClass()"` on trunk; re-verify before touching, line numbers drift.)

## Suggested fix

Replace `ClassName.bestGuess(columnClass)` with a shared helper `TypeName typeNameForColumnClass(String)` that:

1. counts and strips the leading `[` dimensions,
2. resolves the element type: strip the `L...;` reference form to the source-form FQCN, and defensively map the primitive component codes (`Z`->`boolean`, `B`->`byte`, `C`->`char`, `D`->`double`, `F`->`float`, `I`->`int`, `J`->`long`, `S`->`short`),
3. wraps the element in `ArrayTypeName.of(...)` once per dimension,
4. falls back to `ClassName.bestGuess` for the plain (non-array) scalar case.

`TypeName` and `ArrayTypeName` are both in `graphitron-javapoet` and `GeneratorUtils` already imports `TypeName`. The call sites emit the value as a javapoet `$T` (including `$T.class`), and `ArrayTypeName` renders the `.class` form correctly (`Boolean[].class`), so the generated Java 17 output stays valid.

Alternative root-cause fix, if the codegen classloader is reachable at these sites: build the `TypeName` via `TypeName.get(Class.forName(columnClass))`, which handles arrays natively. The string-parse helper is self-contained and avoids threading the loader, so it is the preferred surface unless the loader is already in scope.

## Reproduction

Real-world witness: opptak branch `feature/SHIIT-767-opptak-v2-skjema` -> node type `Utdanningstilbud @table(name: "opptak.utdanningstilbud")` has array-typed columns (`mulighet_niva_koder`, `fagomrade_koder`, `undervisningstyper`); `vitnemalskalkulator.karakterkolonne_valgt` is the `boolean[]` named in the crash message.

Graphitron-side test: add an array-typed column to a fixture table that flows through the `SourceKey.Wrap.TableRecord` key-extraction path (single-schema is fine, this is unrelated to multi-schema), bumping the jOOQ fixture schema version. Assert that the node type emits and that the generated `$T.class` argument is `Boolean[].class` (or `String[].class`). Add a unit test pinning the helper across scalar, single-dimension array, and (if supported) multi-dimension inputs.
