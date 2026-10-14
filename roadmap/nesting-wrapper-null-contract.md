---
id: R1026
title: "A nullable nesting wrapper whose columns are all null resolves to an object, not null"
status: In Review
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-13
last-updated: 2026-10-14
---

# A nullable nesting wrapper whose columns are all null resolves to an object, not null

## Goal

A nullable field that returns a *nesting type* (an object type without `@table` whose fields read columns of the enclosing `@table` type's row) resolves to `null` when every value the wrapper would carry is null, as Graphitron 9 did, and the manual says so. Today it always resolves to an object, so a wrapper with nothing in it reaches the client as `{antall: null, tidsenhet: null}`. A client that tests `varighet != null` breaks on migration, and nothing in the manual says the behaviour changed.

From sis:

```graphql
type EmneUndervisningsoversikt @table(name: "EMNE") {
    varighet: Emneundervisningsvarighet
}
type Emneundervisningsvarighet {
    antall: Int @field(name: "TALL_VARIGHET_UND")
    tidsenhet: Tidsenhet
}
```

For an emne with no duration, Graphitron 9 returns `"varighet": null`; Graphitron 10 returns `"varighet": {"antall": null, "tidsenhet": null}`. After this item both return `null`. A non-null `varighet: Emneundervisningsvarighet!` keeps resolving to an object, as it did in Graphitron 9. sis's `EmnerQueryIT.fetchEmnerQuery` (asserting every present `varighet.antall` is an `Integer`) and the `studieoppbygningQuery` approval (`maksimumsvekting: null` became `{verdi: null}`, `beskrivelseAlleSprak: null` became `{en: null, nb: null, nn: null}`) are the consumer-side reproductions.

The rule is restored for wrappers whose contents are columns of the parent row, or single references joined from columns of the parent row. That covers both reported sis wrappers: `tidsenhet` above joins from `EMNE.TIDSENHET_VARIGHET_UND`, and `maksimumsvekting.vektingstype` from `EMNEKOMBINASJON.VEKTTYPEKODE_MAX`. A wrapper that also contains a list, an `@externalField` or a `@splitQuery` child always resolves to an object, and the migration guide names that as a divergence from Graphitron 9.

## The contract

Terms used below. The *wrapper* is the nesting type's value on one row. A *column leaf* is a wrapper field whose value is one or more columns of the parent row read as-is: a plain or `@field`-renamed column, an enum-mapped column, or a `@nodeId` field encoding key columns. A *reference leaf* is a wrapper field reached through a reference whose value is a single row or a single value (a `@reference` or implicit-FK object field, or a scalar read through `@reference`), and whose path's first join step pairs columns of the parent row with columns of the next table (an FK or name-matched key, not an authored `condition:` predicate). Its *join columns* are those parent-row columns. The *presence leaves* of a wrapper are its column leaves and reference leaves plus, recursively, those of every nesting field inside it.

1. **Nullable wrapper, columns and parent-keyed references only.** A nullable nesting field whose wrapper (recursively) contains only column leaves, reference leaves and nesting fields resolves to `null` exactly when every presence leaf is null. A column leaf over one column is null when the column is; a `@nodeId` leaf is null when any of its key columns is, because the generated `NodeIdEncoder.encode` returns `null` on any null value. So "every leaf null" is evaluated per leaf, not per column. A reference leaf counts as null when any of its join columns is null: SQL equality never matches a null, so the first join finds no row and the reference resolves to `null` whatever the rest of its path. The check reads the parent row, never the reference's subquery.
2. **Independent of the selection.** Presence is decided over all presence leaves, not the ones the query selects. `{ varighet { antall } }` and `{ varighet { tidsenhet } }` agree on whether `varighet` is null for the same row. A field's nullness that depends on its own sub-selection would corrupt normalized client caches and differ from Graphitron 9, which built the wrapper from all its fields.
3. **Non-null wrapper.** A `T!` nesting field always resolves to an object. Its leaves still count toward an enclosing nullable wrapper's presence (rule 1 recurses through it), which matches Graphitron 9's treatment of an all-null non-null inner object as null-equivalent for the outer one.
4. **Other children block the collapse.** A wrapper containing any field that is not a column leaf, a reference leaf or a nesting field always resolves to an object. That covers a list reference, an interface or union reference, a reference whose first step joins on an authored predicate or laterally to a routine, an `@externalField`, and a `@splitQuery` child. Their values are either never null (a list, a batch key) or can only be known by running a subquery the client did not select, and dropping a non-null value to collapse the wrapper would lose data. A nesting field whose subtree contains such a child is itself blocked, and blocks every enclosing wrapper.

A reference leaf errs in one direction only. When its join columns are all non-null, the leaf counts as present even if the reference still resolves to `null`: a `condition:` on the reference or a later step filtered the row out, an unenforced FK has no target row, or the first step enters from the far end of an FK, so the join columns are the parent's own key. The wrapper then stays an object where Graphitron 9 returned `null`. It is never null where a value exists.

The divergences from Graphitron 9 are rule 4 and the paragraph above. Graphitron 9 counted every single `@reference` object and every correlated subquery toward the collapse, by its actual value.

## Implementation

Shipped in two rounds; the contract above is what both deliver.

- **Round 1, column leaves**, shipped at `7c25d20fb`..`a3cadd6c8`. `ChildField.NestingField` derives `subtreePresence()` (sealed `Leaves` | `Blocked`) and `nullContract()` (sealed `CollapsesWhenAbsent` | `AlwaysObject` with `Reason.{NonNull, BlockedBy, NotSingle}`) from its own components, so classification and the fact store are untouched. It implements `ParentRowDemand`; `parentRowColumns()` rides `CallWrap.Splice.callerDemand` into the anchor's projection as base-name columns, and `FetcherEmitter.nestingBody` switches over the contract, emitting one presence check per leaf against the parent's table constants. Docs: a "Grouping columns into a nested object" section in `map-types-to-tables.adoc`, and the restored rule plus the divergence in `migrating-from-legacy.adoc`.
- **Round 2, reference leaves**, shipped at `2a4f0a4b5`..`55e6782cc`. A single-row `TableField` and a `ColumnBackedReferenceField` count through `referenceLeaf(joinPath)`: hop 0's `On.ColumnPairs.sourceSideColumns()`, or `Blocked` on an empty path, `On.Predicate` or `On.Lateral`. No emitter or projection change was needed. `GraphitronSchemaValidator.compareNestedFieldsShape` holds an inner collapsing nesting field to the same per-leaf column names at every reuse site (`comparePresenceColumns`), rejecting structurally with a message that says to split the nested type; this is the fork's recommended branch, and sis has no such shape. Both manual pages were rewritten for reference leaves.
- **Round 3, test tier.** The pipeline tests that matched generated fetcher bodies as strings were replaced by model assertions; see Tests and Implementation notes.

## Tests

Each tier carries what it can observe; none matches generated method bodies as strings.

- **Model unit**, `NestingFieldNullContractTest`: columns only, `@nodeId` leaf over several columns, nullable and non-null inner nesting fields, a `ComputedField` (`@externalField`) blocker propagating through every enclosing wrapper, non-null and list-wrapped outer fields; single and composite FK reference leaves, list and `On.Predicate` references blocked, multi-hop `ColumnBackedReferenceField` over hop 0's columns only, a reference leaf reaching through an inner nesting field, and `parentRowColumns()` including join columns.
- **Pipeline**, `NestingFieldPipelineTest`: the contract and `parentRowColumns()` of SDL-classified fields (column leaves, non-null wrapper, list `@reference` and `@splitQuery` blockers, single FK, two-hop scalar `@reference`, reverse FK), the anchor's splice arm projecting the demanded columns (`TypeSpecAssertions.armProjectsColumn`), the nesting fetcher still wired as a `LightFetcher`, and the shared-nested-type rejection with its passing negative.
- **Execution**, `NestingNullContractExecutionTest` over `PivotNestingHost` (hosts 1 to 3 in `init.sql`): `labels: HostLabels` resolves to `null` on an all-null row and to an object otherwise, `labelsRequired: HostLabels!` stays an object, selecting a subset agrees with selecting all (rule 2); `origin: HostOrigin`, holding only references joined from `pivot_nesting_host.language_id`, resolves to `null` when that column is null and to an object when it is set, whichever reference the query selects. This tier owns what the generated fetcher does at run time.
- **Consumer**: sis's `EmnerQueryIT.fetchEmnerQuery` and the `studieoppbygningQuery` approval, which define the goal. Not run against round 2 or 3 yet.

## Implementation notes

- *Round 1.* `FetcherEmitter.nestingBody` switches exhaustively over `NullContract` rather than testing one arm with `instanceof`, because `CommandSeamRatchetTest.leafDispatchSitesInGenerators` counts any `instanceof ChildField…` as leaf dispatch. The record's first javadoc line is unchanged, so `supported-schema-shapes.adoc` needs no regeneration. `PivotNestingHost.texts` now collapses too; `PivotExecutionTest` reads only host 1, which carries values. In the web sandbox, reseed `rewrite_test` after this item's `init.sql` changes (see `.claude/web-environment.md`).
- *Round 2.* `Hop.filter()` is deliberately not read: a hop-0 `condition:` is the stays-an-object case, not a blocker. `comparePresenceColumns` runs only when the inner recursion added no error, so a divergent inner column is not reported twice. The rejection fixture is `content.film_id` against `film_endorsement.endorsed_film`; `content` against `inventory` is the passing negative.
- *Round 3.* `NestingFieldPipelineTest`'s `fetcherMethodBody` helper and its five uses are gone, each replaced by `nullContract()` / `parentRowColumns()` assertions or by `armProjectsColumn`. `ProjectionUnitRendererTest.splicedNestingArm_projectsTheCallersDemandUnderBaseNamesAheadOfTheCallee` is deleted without replacement: the pipeline tier pins that the splice projects the demand, and the execution tier's `labels { nn nb }` exercises a demanded column the query also selects.

## Reviewer findings

### In Review → Ready, round 2 (reviewed at `9e6f1fa49`)

The delivery matches the amended contract and the verification build passes; the execution tier demonstrates the goal on sakila. Rework is for the test tier and the spec body only.

- *Code-string assertions on generated method bodies.* `NestingFieldPipelineTest.fetcherMethodBody` reads `MethodSpec.code().toString()`, and five tests assert on it (one with an exact `isEqualTo` over the whole body); `ProjectionUnitRendererTest.splicedNestingArm_projectsTheCallersDemandUnderBaseNamesAheadOfTheCallee` does the same with `indexOf`. `development-principles.adoc` bans this at every tier. The model assertions in those tests (`nullContract()`, `parentRowColumns()`, the blocker class), `TypeSpecAssertions.armProjectsColumn` and `NestingNullContractExecutionTest` already carry what the pins claimed, so they can go without replacement. The spec's own Tests wording asked for the pins; reword it so the next pass is not told to write them.
- *Spec body is stale.* `## Implementation` still calls the Amendment "the delta still to build". Collapse the shipped plan sections to "shipped at" notes.
- *Non-blocking, for the next Done gate.* The goal is defined by sis's `EmnerQueryIT.fetchEmnerQuery` and `studieoppbygningQuery` approval, and sis has not run against round 2. A sis IT run on the delivered tree is the strongest evidence the next reviewer can ask for.

## Provenance

Reported by the sis Oracle IT run (fs-plattform, branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md` § "Third IT run"), on a pre-R1019 snapshot not re-checked on trunk. No manual page states the null contract of a nesting field either way (checked at `1cb2e1b04`). Graphitron 9 (`FetchDBMethodGenerator.createMappingFunction` at tag `v9.3.0`) mapped every nested object with jOOQ's `Functions.nullOnAllNull`, over all the type's fields, and used a plain constructor for a non-null nested field.

Round 1's spec claimed sis's reported wrappers held no reference. They both do (`Emneundervisningsvarighet.tidsenhet`, `MaksimumVektingForStudieoppbygningsdel.vektingstype`). The sis IT run against `a3cadd6c8` caught this: 207 sis wrappers got the presence check, but both reported ones still emitted `return source;`. A script over sis's generated fetchers, its SDL and its jOOQ `Keys.java` sized the amendment against the 113 wrappers still passing through:

- 69 become collapsible. Every non-column field in them is a single reference whose first step is a forward FK from the parent row, with no hop-0 condition. 62 hold only object references, including both reported cases; 7 hold a scalar read through `@reference`, 5 of them multi-hop.
- 30 are non-null, so nothing changes for them.
- 13 stay blocked: 10 by `@splitQuery` and 3 by `@externalField`.
- 1 (`Termin.studiesemesterPeriode`) has a reverse FK at hop 0 and stays an object.

None of them holds a list reference.
