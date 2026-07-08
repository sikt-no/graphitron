---
id: R446
title: "Codegen crashes on array-typed columns: ClassName.bestGuess rejects jOOQ array class descriptors"
status: Spec
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

The real audit boundary is **every reader of `columnClass()`**, not just the `bestGuess` sites, because the fix must not disturb the consumers that depend on the *binary* descriptor form:

* `EnumMappingResolver.java:185` - `Class.forName(column.columnClass(), …)`; the loader needs the JVM descriptor for arrays.
* `SourceRowDirectiveResolver.java:326` - `argClass.getName().equals(col.columnClass())`; `getName()` is binary form, so the compare only matches if `columnClass` stays binary.
* `GraphitronSchemaValidator.java:1069` - `rcf.column().columnClass().equals(ocf.column().columnClass())`; form-agnostic *as long as both sides keep the same form*.

These are the reason the descriptor String must be left intact (see Design).

## Design

**Decide the type once at the catalog boundary; carry it as a `TypeName` on the model. Leave the binary `columnClass` string intact.**

The crash is a symptom of a deeper shape problem: the codegen sites are each re-deciding a fact (`what Java type is this column?`) that should be decided once, where the information is richest. Today that decision is deferred to 31 downstream sites, each of which takes `col.getType().getName()` (a lossy binary string), then tries to parse it back into a javapoet type with `ClassName.bestGuess`. The three `JooqCatalog` reflection sites that produce that string (`:953`, `:1066`, `:1253`) hold the live `col.getType()` `Class<?>` in hand; `TypeName.get(Class)` decodes it, arrays included, natively. So the round-trip `Class -> getName() String -> re-parse` is the smell, and the boundary is the place to cut it.

There is direct precedent: `RoutineParam` already stores `TypeName.get(p.getType())` decoded at the same catalog boundary (`JooqCatalog.java:225`). Columns are the same case, with one twist: unlike routine params, columns genuinely need the *binary* string too, so this is a dual-fact split, not a replacement.

**Why the string stays.** `columnClass` is read as a raw `String` in ~20 places beyond the 31 javapoet sites, and several depend on the *binary* descriptor form (see the extended audit under Blast radius):

* `EnumMappingResolver.java:185` - `Class.forName(column.columnClass(), …)`; the loader requires the JVM descriptor (`[Ljava.lang.Boolean;`) for arrays. A source-form string would throw `ClassNotFoundException` and silently degrade the column to `NOT_ENUM`.
* `SourceRowDirectiveResolver.java:326` - `argClass.getName().equals(col.columnClass())`; `Class.getName()` yields the binary form, so the compare only matches if `columnClass` stays binary.
* `GraphitronSchemaValidator.java:1069` - a `.equals` compare of two `columnClass()` strings; safe only while both sides keep the same form.

A single string cannot be both the binary form these consumers need and the source form `bestGuess` needs. That is precisely the tell to split it into a typed fact for codegen plus the retained raw string for the binary-form consumers. Normalizing the stored string to source form (the naive "fix at source") would break the two consumers above; it is rejected for that reason.

**The change.** Add a `TypeName columnType` component to the column-carrying records and populate it once at the boundary:

* `JooqCatalog.ColumnEntry` gains `TypeName columnType`, set at the two reflection sites (`:953`, `:1066`) via `TypeName.get(col.getType())`.
* `ColumnRef` gains `TypeName columnType()`, threaded from `ColumnEntry.columnType()` at the `ColumnEntry -> ColumnRef` mapping sites, and set directly via `TypeName.get(col.getType())` at the one reflection-built `ColumnRef` (`JooqCatalog.java:1253`). `columnType` is a pure function of the same `Class` that produced `columnClass`, so it never changes record-equality outcomes (both are derived from one source); it is a denormalized *view*, retained because the live `Class` is only available at the boundary and re-deriving it downstream would mean threading the codegen classloader.
* The 31 `ClassName.bestGuess(<ref>.columnClass())` sites become `<ref>.columnType()`.

`.class` rendering stays valid: sites such as `GeneratorUtils:569` emit the value as `$T.class`. For an array `columnType` is an `ArrayTypeName`, which emits `Boolean[]`, so `$T.class` renders `Boolean[].class` (multi-dimension `[][]`), valid Java 17. The scalar case is a `ClassName`, behaviourally identical to today.

**Return-type widening.** Six sites declare the `bestGuess` result as `ClassName` (`MultiTablePolymorphicEmitter` 909/1060/1168/1556, `TypeFetcherGenerator:1735`, and locals in `SelectMethodBody`/`GeneratorUtils`). `columnType()` returns `TypeName`, so those declarations widen `ClassName -> TypeName`. Each is consumed only as a javapoet `$T` argument or as a *type argument* to `ParameterizedTypeName.get(raw, …)`, both of which accept `TypeName`; none call a `ClassName`-only method. No certainty is lost: there is no classifier guarantee today that these columns are non-array (`columnClass` is a bare `String` everywhere), so the `ClassName` local was an unenforced assumption, not a carried fact.

**Rejected alternatives.**

* *String-parse helper on `ColumnRef`* (strip `[` dimensions, unwrap `L…;`, map primitive component codes, wrap in `ArrayTypeName`, fall back to `bestGuess`): smaller diff (no record-shape change, no constructor threading), but it re-parses a string we just produced from the live `Class` via `getName()`, i.e. it keeps the decode-encode-decode round-trip the boundary lift removes, and it re-runs per call. The boundary lift is the in-grain choice (matches `RoutineParam`); the string parser is the fallback only if the boundary threading proves disproportionate at implementation time.
* *`TypeName.get(Class.forName(columnClass))` at each call site*: handles arrays natively but needs the codegen classloader threaded to 31 sites; the boundary already holds the `Class`, so no loader threading is needed there.

## Validation: array columns as batch / NodeId keys (a decision, not just a crash fix)

The reachable crash is over *ordinary* columns (the `TableRecord` arm reconstructs the full record from `allColumns()`); arrays there are legitimate and the type-lift handles them. But the *latent* sites iterate DataLoader key tuples, participant PKs, and NodeId key columns. Java arrays have identity `equals`/`hashCode`, so an array-typed value used as a `RowN`/`Set<K>` batch key or a NodeId key would dedupe and match *by reference*: the emitted code compiles cleanly but mis-batches on a live request. Making those latent sites merely "not throw" converts a build-time crash into a silent runtime correctness bug, which is worse.

Decision: add a validate-time rejection for an array-typed column used as a **key element** (DataLoader `@splitQuery` key / `SourceKey` column) or a **`@node` key column**, mirroring the classifier invariant, so such schemas fail at `validate` with a clear message rather than emitting mis-batching code. Ordinary (non-key) array columns are accepted and flow through the type-lift unchanged. If the rejection turns out to need classifier plumbing that materially enlarges the change, split it into a fast-follow item and land the crash fix first; the Spec's position is that best-effort emission at the key sites is *not* an acceptable end state.

## Implementation

Flat file-by-file; the type-lift plus its call sites compile as one unit.

* `JooqCatalog.java` - add `TypeName columnType` to the `ColumnEntry` record (`:1321`); populate via `TypeName.get(col.getType())` at `:953` and `:1066`; at the reflection-built `ColumnRef` (`:1253`) pass `TypeName.get(col.getType())`.
* `model/ColumnRef.java` - add a `TypeName columnType` component (keep `columnClass`); update the javadoc.
* Thread `columnType` through every `new ColumnRef(…)` / `new ColumnEntry(…)` construction site. The `ColumnEntry -> ColumnRef` passthroughs (`ServiceCatalog` 70/109, `JooqCatalog` 892/1236, `OrderByResolver` 136/226/263/286, `InputBeanResolver:412`, `TypeBuilder:1354`, `MatchedKeys:56`, `BuildContext` 1176/2500/2773, `CatalogBuilder:1117`) forward `e.columnType()`; the `ColumnRef -> ColumnRef` passthrough (`FieldBuilder:1409`) forwards `col.get().columnType()`. Re-grep `new ColumnRef(` / `new ColumnEntry(` before editing; the compiler enforces completeness once the component is added.
* Replace all 31 `ClassName.bestGuess(<ref>.columnClass())` occurrences with `<ref>.columnType()`:
    * reachable (full mapped-column iterators): `generators/GeneratorUtils.java` 487/572, `generators/SplitRowsMethodEmitter.java:879`, `generators/MultiTablePolymorphicEmitter.java:1853`;
    * latent (key / PK / participant / decode / lookup columns): `generators/FetcherEmitter.java` 501/657/670; `generators/SplitRowsMethodEmitter.java` 269/673/837/1385; `generators/MultiTablePolymorphicEmitter.java` 909/1060/1168/1556/1905; `generators/TypeFetcherGenerator.java` 1735/2825; `generators/TypeConditionsGenerator.java:282`; `generators/CompositeDecodeHelperRegistry.java` 119/232/242; `generators/InlineLookupTableFieldEmitter.java:123`; `generators/util/SelectMethodBody.java:137`; `generators/util/NodeIdEncoderClassGenerator.java:219`; `generators/util/ValuesJoinRowBuilder.java:104`; `model/SourceKey.java:207`; `model/ChildField.java` 285/314/351/1072.
* Widen the six `ClassName`-typed locals/vars listed under *Return-type widening* to `TypeName`.
* `model/SourceKey.java:207` already casts `(TypeName)`; drop the now-redundant cast.
* Add the key/NodeId array-column rejection (see Validation) in the relevant classifier/validator arm.
* Leave the raw-`String` consumers (`EnumMappingResolver`, `GraphitronSchemaValidator`, `SourceRowDirectiveResolver`, `FieldBuilder`) reading `columnClass()` untouched.

Line numbers drift; re-run `grep -rn "bestGuess" graphitron/src/main/java | grep "columnClass()"` and `grep -rn "new ColumnRef\|new ColumnEntry" graphitron/src/main/java` before editing and reconcile against the 31-site / construction-site counts.

## Tests

Prefer behaviour-tier pins over asserting the emitted type-argument string (a code-string assertion on generated output tests the emitter's spelling, not behaviour, and breaks on refactor).

* **Boundary decode (unit):** `TypeName.get(Class)` at the catalog boundary is javapoet's own contract, so pin our *use* of it rather than re-testing javapoet: a small unit over `columnType` for a scalar column, a single-dim array column, and (if the fixture supports it) a multi-dim column, asserting the `TypeName` is a `ClassName` / `ArrayTypeName` of the right element and dimension. Keep it a decode test, not a generated-string test.
* **Regression pin (pipeline tier):** add an array-typed column to a fixture table that flows through the `SourceKey.Wrap.TableRecord` key-extraction path (single-schema is fine; unrelated to multi-schema), bumping the jOOQ fixture schema version. Assert the node type emits without the `IllegalArgumentException`. This is the direct pin for the reported crash.
* **Validity (compilation tier):** `graphitron-sakila-example` compiles the generated output at Java 17; a wrong element/dimension form (e.g. emitting the raw descriptor) fails that compile, proving `Boolean[].class` is valid without asserting on the string.
* **Round-trip (execution tier), if the fixture reaches it:** the array column reads back through a generated fetcher.
* **Rejection (pipeline tier):** an array-typed key / `@node` key column produces the `validate`-time author error from the Validation section (not a crash, not silent emission).
* Tier placement (unit vs pipeline vs compilation vs execution): see `docs/architecture/explanation/development-principles.adoc`.

## Roadmap / docs

Bug fix with no user-facing surface (no new directive, Mojo goal, or output format), so no user-docs draft is required. The new author-error message from the Validation section is user-facing text but not a doc chapter; keep it clear and self-explanatory. On Done, a one-line `changelog.md` entry if worth keeping as history.

## Reproduction

Real-world witness: opptak branch `feature/SHIIT-767-opptak-v2-skjema` -> node type `Utdanningstilbud @table(name: "opptak.utdanningstilbud")` has array-typed columns (`mulighet_niva_koder`, `fagomrade_koder`, `undervisningstyper`); `vitnemalskalkulator.karakterkolonne_valgt` is the `boolean[]` named in the crash message.

The graphitron-side fixture reproduction and its assertions are specified under Tests above.
