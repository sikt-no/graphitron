---
id: R511
title: "Split-query TableRecord key extraction breaks on @service-returned @table parents"
status: Ready
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Split-query TableRecord key extraction breaks on @service-returned @table parents

A field carrying both `@splitQuery` and `@service` generates a DataLoader-backed child fetcher that reconstructs the parent's batch key from `env.getSource()`. When the service method's key signature is a full jOOQ `TableRecord` (e.g. `Set<BestillingRecord>`), the key wrap is `SourceKey.Wrap.TableRecord` and `GeneratorUtils.buildKeyExtraction`'s `TableRecord` arm rebuilds the record by reading reserved projection aliases off the source row, `source.get("__src_<col>__", ...)` for every `parentTable.allColumns()`. Those `__src_<col>__` aliases exist only because a generated, SQL-projected parent query (`<Type>.$fields`) adds them. The extraction silently assumes the parent row was produced by such a query.

That assumption is false whenever the same `@table` type is handed back raw by a `@service`. A `@service`-backed query (the incident: `Query.minNyesteBestillingNy` returning a bare `BestillingRecord` from `selectFrom(BESTILLING)`) never goes through `$fields`, so its row type carries only the real columns and no `__src_*` aliases. Every `TableRecord`-keyed split-query child then throws at runtime, `IllegalArgumentException: Field "__src_BESTILLINGID__" is not contained in row type ("VIB"."BESTILLING".BESTILLINGID, ...)`. In the incident all five `@splitQuery @service` fields on `Bestilling` (`hoyereUtdannelse`, `fagskole`, `videregaende`, `grunnskole`, `ukjentFagniva`) failed identically whenever the parent `Bestilling` had been returned by a service. The combination worked pre-v10; the reserved-alias key-reconstruction scheme is a v10 generator addition (introduced fixing the `into(Tables.X)` multiset-alias collision) that narrowed the "what a parent source row looks like" contract without accounting for service-returned `@table` parents.

## Why the build-time guard misses it

`ParentProjectionContainmentCheck` (wired at `TypeClassGenerator.generateForType`) enforces the producer/consumer contract only for the SQL parent path: it verifies `<Type>.$fields` flips `reservedFullRow` so the projected query carries the aliases the children read. It has no model of a `@table` type being returned raw by a `@service`, so the service parent path is entirely un-guarded. The two parent kinds coexist in one schema and the generator never reconciles them.

## Subgraph workaround already in the field

Changing the service key signatures from `Set<BestillingRecord>` to `Set<Record1<String>>` flips the child onto the `Wrap.Record`/`Wrap.Row` extraction path (`((Record) env.getSource()).into(Tables.X.COL)` / `.get(Tables.X.COL)`), a base-name read that succeeds on both a raw service record and a generated projected row. This is a correct stopgap but is subgraph-side and per-signature fragile: the natural, documented thing to write is `Set<XRecord>`, so the next author (or the next `@table` type reachable via a service) re-trips it. The upstream fix belongs here.

## Spec findings

Facts pinned while reading the current code (line anchors as of this spec; symbols are the stable reference). Architect-consulted; findings integrated below.

1. **Blast radius: exactly one emit arm.** `SourceKey.Wrap.TableRecord` has two classifier-side producers but only one reaches the broken read. `ServiceCatalog`'s `SourcesShape` resolution (`ServiceCatalog.java:995`, the `Set<XRecord>`/`List<XRecord>` `Sources` parameter) flows to `GeneratorUtils.buildKeyExtraction`'s `TableRecord` arm via `TypeFetcherGenerator.buildServiceDataFetcher` (`TypeFetcherGenerator.java:6700`), and that is the only `buildKeyExtraction` caller that can carry the wrap: the batched and pivot callers construct their keys through `KeyLift.wrap()`, which never derives `TableRecord`, and `buildKeyExtractionWithNullCheck` rejects non-`Row` wraps at entry. The other producer, `FieldBuilder`'s `SingleRecordIdField` (`FieldBuilder.java:5872`), has its own emitter (`FetcherEmitter.buildSingleRecordIdFetcherValue`) reading typed `Tables.X.COL` constants off the in-memory record; it never reads reserved aliases and is already correct on typed parents.

2. **The manual already promises the semantics.** `handle-services.adoc:208-210` documents the typed-record `Sources` shape and explicitly addresses the service-returned parent: "if the parent is itself a `@service` returning a hand-rolled record, the framework has no SELECT to widen and the record is only as populated as the producing service made it. Plan accordingly." The docs promise a working combination with a partial-population caveat; the implementation crashes instead. The fix makes the implementation match the documented contract, including that caveat (a typed parent's values are used as the service populated them).

3. **The reproduction is latent in the sakila example.** `Query.filmsByService` returns `Result<FilmRecord>` straight from `selectFrom(FILM)` ("no framework projection" by documented design), and `Film.titleTitlecase` is a `Wrap.TableRecord` `@service` child. No existing test combines them; `filmsByService(ids: ...) { titleTitlecase }` reproduces the incident's `IllegalArgumentException` on current trunk with zero new schema fixtures.

4. **Parent cardinality is immaterial to the extraction.** The child fetcher's `env.getSource()` is always one record element regardless of whether the producing service returned one record or a list, so the incident's single-cardinality shape (`minNyesteBestillingNy`) and `filmsByService`'s list shape exercise the same read; one service-parent fixture covers both.

5. **The runtime fork has sanctioned precedent.** The parent kind is irreducible runtime variance: graphql-java fuses every arrival path onto one `(type, field)` fetcher, so no classification-time fact can select a single read for the coordinate. The `SourceEnvelope` fork (`DIRECT` vs `OUTCOME_SUCCESS`) and the `Outcome.Success` `instanceof`-narrowing prelude in `buildBatchedDataFetcher` are the established "runtime shape of `env.getSource()` forks the read" pattern (`docs/architecture/explanation/dispatch-axes.adoc`); this is not the defensive-cast smell the acceptances discipline bans, because the classifier genuinely cannot guarantee the parent kind here.

## Design

**The spine: make `buildKeyExtraction`'s `TableRecord` arm runtime-adaptive on the source's own type.** Emit shape (sketch, `Film` standing in for the parent):

```java
Record source = (Record) env.getSource();
FilmRecord key = new FilmRecord();
if (source instanceof FilmRecord typedSource) {
    // Service/DML-produced typed parent: values are the producer's, per the documented contract.
    key.set(Tables.FILM.FILM_ID, typedSource.get(Tables.FILM.FILM_ID));
    // ... one per parentTable.allColumns()
} else {
    // SQL-projected generic row: rebuild from the reserved __src_<col>__ aliases.
    key.set(Tables.FILM.FILM_ID, source.get("__src_film_id__", Integer.class));
    // ... one per parentTable.allColumns()
}
```

Design decisions, each argued:

* **Symmetric arms, fresh key in both.** Both arms mint a new record and populate it per-column over the same `parentTable.allColumns()` enumeration; they differ only in the read expression (typed field read vs reserved-alias read). The typed arm deliberately does not alias the live parent object as the DataLoader key: a fresh copy removes the mutation hazard, makes the two arms produce structurally identical keys by construction, and keeps the emitted code legible as one shape with one forked read. The typed-arm read uses jOOQ field-identity (`get(Tables.X.COL)`), never by-name mapping, so the multiset-alias collision the reserved scheme exists to avoid cannot re-enter through this arm (a typed record's row type is exactly the table's columns).

* **The discriminator's basis becomes a pinned invariant, not prose.** The fork is sound because the `$fields` SQL path materialises generic `Record` implementations, never the typed subclass. That fact has a history of being false (the pre-fix projection did `into(Tables.X)` and produced typed records; the reserved-alias scheme replaced it), so it gets an enforcer in the same commit: a test pinning that a `$fields`-built SELECT yields rows that are not instances of the parent's typed record class, cross-referenced by `{@link}` from the `TableRecord` arm's javadoc so the dependency is discoverable from the read site. The behavioral backstop is the execution-test pair (below): an SQL projection that ever turned typed-but-alias-populated would route to the typed arm with null base columns and fail the SQL-parent execution test on its asserted values.

* **Unconditional fork emission, explicitly chosen.** The alternative is a type-grain classification fact ("this `@table` type is reachable via a Record-producing path") gating fork-vs-single-branch emission, which would source the fork's necessity in the model and avoid a dead arm on single-parent-kind coordinates. Declined for this fix: the gate's correctness would rest on an exhaustive producer-reachability enumeration (services, DML payloads, nested composites, every future producer family), and a missed path in that walk reproduces this very bug with the gate asserting it cannot happen. That is precisely the walk-omission bug family `ParentProjectionContainmentCheck` exists to distrust. The unconditional fork degrades safely: a coordinate only ever fed by one parent kind carries one dead-but-correct arm, not a crash. Costs accepted and named: the fork's necessity lives in this prose (not the model), and the discriminator pin above is therefore mandatory, not optional. If the model later grows a producer-reachability fact for independent reasons, gating the emission is a natural follow-up.

* **The producer side and its guard are untouched.** `<Type>.$fields` keeps projecting the reserved full row (the else arm still needs it), `RequiredProjection.reservedFullRow` keeps flipping, and `ParentProjectionContainmentCheck`'s reasoning is unchanged: it guards the SQL-projected producer path, which remains load-bearing. This fix widens the consumer to the second parent kind; it does not move the producer contract.

* **The trade-off behind the fork, named.** The `Wrap.Row`/`Wrap.Record` arms read base-named columns and are parent-kind-agnostic by construction; only the `TableRecord` wrap needs the reserved-alias scheme and therefore the fork. The fork is the carrying cost of the ergonomic `Set<XRecord>` service signature (a real author-surface goal, and the manual's documented recommendation for "columns beyond the parent's PK"). A future author weighing wrap-hierarchy changes should weigh collapsing the `TableRecord` wrap against carrying this fork; that weighing is out of scope here.

**Alternatives declined.** (a) Build-time rejection of the `Set<XRecord>` signature when the parent type is service-reachable: loud, but bans the natural documented signature, and reachability is per-path while the child classifies per-type, so it over-rejects valid SQL-only schemas. (b) Producer-side re-projection of service returns through `$fields`: contradicts the documented "service hands records straight to graphql-java, no framework projection" contract and costs a query round trip. (c) A `SourceShape.Record` arm on the service child leaves: the axis is per-arrival-path, not per-field; a single-valued per-field fact is wrong in the same way the current `Table` pin is, just in the other direction.

## Tests

* **Execution tier, the spine (both parent kinds, mirroring the subgraph's workaround verification):** a new `GraphQLQueryTest` case querying `filmsByService(ids: ...) { titleTitlecase }` asserts the child resolves on a service-returned parent (pre-fix: the incident's `IllegalArgumentException: Field "__src_..." is not contained in row type`). The SQL-parent kind stays pinned by the existing `films_titleTitlecase_withCollidingMultisetSibling_bothResolve_noMappingException`.
* **Execution/internal tier, the discriminator pin:** a test asserting the `$fields`-built SELECT materialises generic `Record` rows, not the typed record class (the `instanceof` fork's basis), `{@link}`-referenced from the `TableRecord` arm's javadoc. Exact home: the `graphitron-sakila-example` internal test tier, next to the other generated-sources contract tests.
* **Pipeline tier:** `ServiceProjectionPipelineTest`'s reserved-full-row projection assertions stay green unchanged (producer side untouched). Add a `TypeSpecAssertions`-style shape predicate asserting the emitted service-child fetcher body carries both read families (the typed `instanceof` fork and the reserved-alias read), keeping it a shape assertion rather than a full code-string pin.

## Docs

`handle-services.adoc:208-210` already documents the intended two-parent-kind semantics; tighten it to state the mechanism plainly (SQL-projected parents are rebuilt from the reserved aliases; service-returned typed parents are copied as-populated) and drop any implication that the typed-record shape requires a generated parent SELECT. No new manual page.

## Out of scope

* Retiring or reshaping the `TableRecord` wrap (named above as the fork's cause; separate weighing).
* A type-grain producer-reachability model fact (declined above with reasoning; revisit only if the model grows one independently).
* The federation `_entities` dispatch and `QueryNodeFetcher` parent paths: both build their SELECT from `<Type>.$fields`, so they are the SQL parent kind and are covered by the else arm unchanged.
* The LSP/completion surfaces and the `@service` classification itself (signature acceptance is unchanged; only the emitted read widens).

## Acceptance criteria

* `filmsByService { titleTitlecase }` (service-returned parent) and the existing SQL-parent fixture both resolve; the execution pair is green.
* The discriminator invariant ("`$fields` SELECT yields generic `Record`") is pinned by a test and `{@link}`-referenced from the `TableRecord` arm.
* Both fork arms mint fresh, structurally identical keys over the same `allColumns()` enumeration; no aliasing of the live parent as key.
* `ParentProjectionContainmentCheck` and the reserved-full-row producer emission are byte-identical to pre-fix output.
* The manual's typed-record `Sources` paragraph matches the shipped behavior.
