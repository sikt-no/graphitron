---
id: R513
title: "Fetchers helper names collide when two same-named classes come from different schema packages"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Fetchers helper names collide when two same-named classes come from different schema packages

When a database exposes two schemas that both contain a table of the same
name (e.g. `opptak.OPPTAK` and `opptak_v2.OPPTAK`), jOOQ generates two record
classes with an identical simple name but distinct packages
(`...jooq.opptak.tables.records.OpptakRecord` vs
`...jooq.opptak_v2.tables.records.OpptakRecord`). If a schema declares
mutations against both, the generated `*Fetchers` class (e.g.
`MutationFetchers`) emits two helpers with the same name and signature and
fails to compile: the author cannot express cross-schema mutations at all.

The helper-name resolvers key the method-name *stem* on `ClassName.simpleName()`
and dedup only *within* a full-`ClassName` group, never *across* two distinct
classes that share a simple name. `JooqRecordHelperNames.of()` groups carriers
by full `ClassName` (so the two `OpptakRecord` classes correctly land in
separate groups), then derives each group's stem from `simpleName()` alone, so
both groups emit `createOpptakRecord(...)` / `createOpptakRecordList(...)`. The
existing contention machinery covers only the opposite case (one class reached
by several binding shapes, disambiguated with ordinal suffixes); there is no
cross-group check for two classes with the same simple name.

The same simple-name-keyed pattern recurs in the sibling helper families on the
same `*Fetchers` classes: the `@record`-POJO / bean input helpers
(`ServiceMethodCallEmitter.singularHelperName`/`pluralHelperName`, keyed on
`beanClass.simpleName()`) and the `@nodeId` `decode*Record` node-ID helpers.
Distinct from R512 (`@reference(key:)` FK-name resolution across schemas),
which is a catalog-lookup concern, not a generated-helper-naming one. The
reactor's multi-schema fixture already carries colliding table names
(`multischema_a.event` / `multischema_b.event`, both generating an
`EventRecord`), so this is reproducible in-repo.

## Design

**The invariant.** All private static helper method names emitted on one
`*Fetchers` class must be unique per Java signature, and every call site must
agree with the emission site on each name. Today three families write into
that class's method namespace with stems derived independently from
`simpleName()`:

1. jOOQ-record `create<Record>` / `create<Record>List` helpers, resolved
   centrally by `JooqRecordHelperNames` (built up front in
   `TypeFetcherGenerator`, stashed on the emission context, consulted by the
   emission drain, `ArgCallEmitter`, and `ServiceMethodCallEmitter`).
2. `@record`-POJO / bean `create<Bean>` / `create<Bean>List` helpers, named by
   *uncoordinated static string derivation* at three sites:
   `ServiceMethodCallEmitter.singularHelperName`/`pluralHelperName` (call
   sites), `InputBeanInstantiationEmitter.buildSingularHelper`/
   `buildPluralHelper` (emission), and
   `InputBeanInstantiationEmitter.nestedBeanExpr` (nested-bean calls inside
   helper bodies). Dedup is a per-full-`ClassName` map in
   `TypeFetcherGenerator`'s drain.
3. `@nodeId` record-decode `decode<Record>` / `decode<Record>List` helpers
   (`InputBeanInstantiationEmitter.recordDecodeHelperName`), dedup again
   per-full-`ClassName` in `TypeFetcherGenerator`'s drain.

(`CompositeDecodeHelperRegistry` is *not* affected: its helper names derive
from the GraphQL type name, which is unique per schema document.)

**One resolver per method-name prefix namespace, not per binding family.**
Families 1 and 2 both emit `create<stem>` into the same class, so computing
their stem sets independently re-buries the bug one level up: a consumer bean
class named `EventRecord` and a jOOQ `EventRecord` on the same class would
still collide even after each family disambiguates internally. The fix
therefore builds, per `*Fetchers` class and *before any field body emits*, one
helper-name resolver (working name `FetchersHelperNames`) covering:

- the **`create*` namespace**, whose stem set is computed over the **union**
  of the jOOQ-record carrier classes and the collected bean classes. The
  existing `JooqRecordHelperNames` shape-contention machinery (D1-D3: dedup by
  carrier structural equality, ordinal suffixes for one class reached by
  several binding shapes, the populated/bare split, the routing-hole throw)
  survives unchanged as the jOOQ-record arm; its per-class *base stem* now
  comes from the cross-class stem map instead of `simpleName()`. Cross-class
  disambiguation ("which class") and within-class shape contention ("which
  binding shape of that class") are orthogonal axes and compose:
  `create<stem><ordinal>`.
- the **`decode*` namespace**, whose stem set is computed over the decoder
  record-class set (scalar and list variants share one stem, as today).

The bean-class collection walk in `TypeFetcherGenerator` (today done at drain
time) moves up front next to the existing jOOQ-carrier walk so the resolver
can be built before emission starts. The resolver is stashed on
`TypeFetcherEmissionContext` alongside (or subsuming)
`jooqRecordHelperNames()` and threaded to every naming site; the three static
`"create"/"decode" + simpleName()` derivations in `ServiceMethodCallEmitter`
and `InputBeanInstantiationEmitter` are deleted, so no naming decision
survives outside the single home. The default (bare) resolver keeps today's
simpleName-based answers for schema-free / unit / out-of-band contexts, which
by construction carry at most one class per simple name.

**Stem rule.** A class whose simple name is unique within its namespace keeps
`simpleName()` as its stem: the overwhelmingly common case is byte-for-byte
unchanged. Colliding classes get a per-class disambiguator *prefixed* to the
simple name, derived from the class's own package (a stable per-class fact,
deliberately independent of which other classes happen to collide with it):

- jOOQ-layout package (ends `…<schema>.tables.records`): the segment
  immediately before `tables.records`, i.e. the schema segment. Pascal-cased
  via the existing `GeneratorUtils.toCamelCase` + capitalize:
  `multischema_a` gives `MultischemaAEventRecord`, so
  `createMultischemaAEventRecord(...)` / `createMultischemaAEventRecordList(...)`;
  the motivating case reads `createOpptakV2OpptakRecord(...)`.
- any other package (bean POJOs): the last package segment, pascal-cased the
  same way.

Uniqueness is enforced over the *emitted method names* (singular and plural
forms both), not just stems, so the pathological `create<A>List`-vs-bean-named-
`AList` overlap is also caught. If the rule still yields duplicates (distinct
package segments that pascal-case to the same token, or degenerate bean
layouts), extend deterministically with further package segments right-to-left;
if that exhausts, a 1-based ordinal ordered by full class name guarantees
termination. The disambiguator is derived from the already-resolved javapoet
`ClassName.packageName()` only — the utility must not reach back into raw
jOOQ (`Table.getSchema()` stays behind the `JooqCatalog` parse boundary).

**Generation-time backstop.** After the drain completes, assert on the built
`*Fetchers* TypeSpec` that no two methods share (name, parameter-type list).
With the union-namespace resolver in place this never fires on valid input; it
is the invariant's enforcer against a future naming site that bypasses the
resolver, turning silently-uncompilable emitted output into a loud generator
failure. This mirrors the existing routing-hole throw in
`JooqRecordHelperNames.stem()` and correctly stays at generation time (it
guards a generator naming decision, not a classifier fact, so no validator
mirror or LSP code is owed).

## Tests

- **Unit tier (the stem utility):** unique simple names answer bare;
  jOOQ-layout collision answers schema-segment-prefixed stems; bean-package
  collision answers last-segment-prefixed stems; the cross-family union case
  (bean simple name == jOOQ record simple name) disambiguates; derived-name
  (List-suffix) overlap is caught; the deterministic fallback chain
  terminates; output is order-independent for the same input set.
- **Pipeline tier (real multischemafixture catalog):** an SDL declaring
  `@service` fields binding both `multischema_a.event` and
  `multischema_b.event` records on one type (mirroring
  `JooqRecordServiceParamPipelineTest`'s fixture shape) asserts both
  disambiguated helper pairs exist on the one `*Fetchers` TypeSpec and that
  single-schema fixtures keep their bare names (byte-for-byte pin). A
  collision-times-contention case (two binding shapes of the A-schema record
  plus one of the B-schema record) pins the orthogonal composition:
  `createMultischemaAEventRecord1`/`...2` alongside
  `createMultischemaBEventRecord`. Sibling cases cover the bean family (two
  same-simple-name test POJOs in different test packages) and the
  `decode<Record>` family (record-typed `@nodeId` bean members reaching both
  event records).
- **Compile tier (`graphitron-sakila-example`):** the
  `rewrite-generate-multischema` slice gains `@service` mutations against
  both `EventRecord`s (service methods added in `graphitron-sakila-service`
  taking each record type), so the Java-17 compile of the generated
  multischema package proves the class actually compiles — the tier that owns
  compilability, which pipeline TypeSpec assertions cannot fully pin.
- **Full reactor build** pins byte-for-byte stability of every existing
  single-schema output (no stem changes, no churn).

## Out of scope

- R512 (`schema-qualified-reference-key`): `@reference(key:)` FK-name
  resolution across schemas is a catalog-lookup concern; no shared code.
- `CompositeDecodeHelperRegistry` and the other GraphQL-type-name-keyed
  helper registries (`QueryConditionsGenerator`, `TypeClassGenerator` hosts):
  their names cannot collide across schemas.
- Any rename of non-colliding output; any author-facing directive surface or
  LSP change (helper names are private generated internals).
- Execution-tier coverage: the bug is a compile-time name collision; compile
  tier is the behaviour-owning tier here.

## Cross-references

- **R512** is the sibling cross-schema item filed from the same consumer
  report (Samordna opptak); the two are independently landable.
- The multischemafixture catalog (schemas `multischema_a` / `multischema_b`
  with colliding `event` and `note` tables) was built up by R83 / R440 / R442
  and is reused as-is; no new DDL is needed.
- `JooqRecordHelperNames`'s D1-D3 decisions (shape-keyed dedup, ordinal
  contention, populated/bare split) are preserved verbatim; this item only
  replaces where the per-class *base stem* comes from and widens the stem
  namespace to the class union.
