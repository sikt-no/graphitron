# R281 retirement inventory: the enum-row deletion whitelist

Companion to [`../classification-test-dsl.md`](../classification-test-dsl.md) (R281),
satisfying pre-migration-hardening **item 1** ("commit the retirement inventory") of that
spec's *Pre-migration hardening* section.

This file is the **deletion whitelist** for the slice-2/3 grind. The rule the gate sets:

> A row not listed here does not retire, full stop. Each migration commit ticks its row off
> (changes `[ ]` to `[x]` and names the corpus example that picked the verdict up).

It is **candidates**, not a licence: every deletion is re-verified at migration time per the
`classified-corpus` skill step 6 (the new corpus coordinate must classify to the exact leaf the
row asserts; confirm against the harness's per-coordinate leaf record, not a green
`VariantCoverageTest`, whose union net is one-way).

> Location note: this lives under `roadmap/audits/` rather than `roadmap/` directly because the
> roadmap-tool's `verify` step (bound to the Maven `verify` phase) parses every `*.md` in
> `roadmap/` as a roadmap item and hard-fails on a missing/duplicate `id:`. `Files.list` is
> non-recursive, so the `audits/` subdir is the established home for front-matter-less companion
> docs (see the staleness audit alongside this file). The `classified-corpus` skill and the R281
> spec point here.

## Methodology and totals

Every `enum *Case implements ClassificationCase` constant in
`graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` was read and
bucketed by its assertion lambda:

- **PURE** = asserts only that the coordinate is an instance of a specific sealed leaf, no slot
  reads. Retirement-eligible.
- **SLOT** = also reads an accessor (`joinPath()`, `returnType().wrapper()`, `columnName()`,
  `sourceKey()`, `filters()`, `participantTypeNames()`, `.warnings()`, ...). Stays: slots are the
  pipeline tier's job.
- **REJECTION** = asserts `UnclassifiedField` / `UnclassifiedType` / `RejectionKind` / a directive
  conflict / a null (excluded) field. Stays: the failure path is out of scope.
- **INPUT** = an `InputField.*` leaf or a constant in an input-type enum. Stays: input-side
  classification is its own game, out of scope.

| Bucket | Count |
| --- | --- |
| Total constants | 407 |
| PURE-eligible (this whitelist) | 35 |
| SLOT (stay) | 170 |
| REJECTION (stay) | 178 |
| INPUT (stay) | 24 |

35 + 170 + 178 + 24 = 407. The eligible pool (~35) matches the spec's "~42 pure-verdict rows, some
of which are input-side and stay" recalibration.

## Operational split

Field-side / catalog-side rows can land a **doc example now** (the renderer handles query-as-view
over object/interface fields). Type-side and mutation-side rows migrate **corpus-only** (no `query`,
no doc block) until pre-migration-hardening **item 3** (renderer input-type closure + fragment
selection) lands; see the skill step 2 note. Both still retire their enum row against a corpus
coordinate.

## Field-side checklist (doc example allowed now)

- [ ] `ColumnFieldCase.ENUM_RETURN_TYPE` (L131) -> `ChildField.ColumnField` | enum-typed scalar on a `@table` parent is still a column. *Nuance: edge of the Column verdict (return type is a GraphQL enum), not the bare Column case.*
- [ ] `TableFieldCase.SPLIT_LOOKUP_TABLE_FIELD` (L856) -> `ChildField.SplitLookupTableField` | `@splitQuery` + `@lookupKey` list child on a `@table` parent
- [x] `NestingFieldCase.PLAIN_OBJECT_TYPE` (L1561) -> `ChildField.NestingField` | field returning a plain (no-`@table`/`@record`) object on a `@table` parent | migrated to the `nesting` corpus example (`Film.details`), corpus-only (inline-Table already taught by the producer minimal pair)
- [x] `NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` (L1570) -> `ChildField.NestingField` | list-wrapped plain object on a `@table` parent | same `NestingField` leaf, retired alongside `PLAIN_OBJECT_TYPE` via the `nesting` corpus example
- [x] `ServiceFieldCase.ON_TABLE_TYPE_SCALAR_RETURN` (L1721) -> `ChildField.ServiceRecordField` | `@service` on a `@table` parent returning a scalar | already covered by the `service` corpus example (`Film.rating`, producer [Service], mapping Record); corpus-only
- [ ] `InterfaceUnionFieldCase.TABLE_INTERFACE_FIELD` (L1971) -> `ChildField.TableInterfaceField` | child returning a `@table`+`@discriminate` interface
- [ ] `InterfaceUnionFieldCase.INTERFACE_FIELD` (L1983) -> `ChildField.InterfaceField` | child returning a plain (no-`@table`) interface
- [ ] `InterfaceUnionFieldCase.UNION_FIELD` (L2023) -> `ChildField.UnionField` | child returning a union
- [x] `NonTableParentCase.RECORD_TABLE_FIELD` (L2209) -> `ChildField.RecordTableField` | `@record` parent + `@table` return, no `@lookupKey` | migrated to the `record-table` corpus example (`FilmDetails.language`), rendered in code-generation-triggers.adoc §"The record-handoff boundary"
- [x] `NonTableParentCase.RECORD_FIELD` (L2290) -> `ChildField.RecordField` | `@record` parent + non-table object return | migrated to the `mapping` corpus example (`FilmDetails.stats`), rendered in code-generation-triggers.adoc §"`mapping`: Column vs. Field"
- [x] `NonTableParentCase.SERVICE_TABLE_FIELD_ON_RECORD_PARENT` (L2328) -> `ChildField.ServiceTableField` | `@record` parent + `@service` + `@table` return | already covered by the `service` corpus example (`Film.language`, producer [Service, Query], mapping Table); corpus-only
- [x] `NonTableParentCase.CONSTRUCTOR_FIELD` (L2343) -> `ChildField.ConstructorField` | `@table` parent + `@record` child type (constructor passthrough) | migrated to the `constructor` corpus example (`Film.details`, producer [], mapping Record); corpus-only
- [ ] `ErrorFieldCase.PATH_AND_MESSAGE_CLASSIFY_AS_PROPERTY_FIELDS` (L6031) -> `ChildField.PropertyField` | `path`/`message` fields on an `@error` parent
- [x] `RootFieldCase.TABLE_QUERY_FIELD` (L6453) -> `QueryField.QueryTableField` | root query field returning a `@table` type | already covered by the `catalog` (`Query.film`, `Query.films`) and `child-table` (`Query.city`) corpus examples, producer [Query], mapping Table/TableConnection; rendered in code-generation-triggers.adoc
- [ ] `RootFieldCase.NODE_QUERY_FIELD` (L6502) -> `QueryField.QueryNodeField` | root field returning a single `Node`
- [ ] `RootFieldCase.NODES_QUERY_FIELD` (L6513) -> `QueryField.QueryNodesField` | root field returning `[Node]`
- [ ] `RootFieldCase.ALIASED_NODE_QUERY_FIELD` (L6524) -> `QueryField.QueryNodeField` | non-`node`-named root field returning `Node`
- [ ] `RootFieldCase.TABLE_INTERFACE_QUERY_FIELD` (L6547) -> `QueryField.QueryTableInterfaceField` | root field returning a table-interface type
- [ ] `RootFieldCase.INTERFACE_QUERY_FIELD` (L6558) -> `QueryField.QueryInterfaceField` | root field returning a plain interface
- [ ] `RootFieldCase.UNION_QUERY_FIELD` (L6569) -> `QueryField.QueryUnionField` | root field returning a union
- [ ] `RootFieldCase.QUERY_SERVICE_RECORD_FIELD` (L6598) -> `QueryField.QueryServiceRecordField` | `@service` root field, non-table return

## Type-side and mutation-side checklist (corpus-only until renderer item 3)

- [ ] `RootFieldCase.INSERT_MUTATION_FIELD` (L6610) -> `MutationField.MutationInsertTableField` | `@mutation(typeName: INSERT)`
- [ ] `RootFieldCase.UPDATE_MUTATION_FIELD` (L6622) -> `MutationField.MutationUpdateTableField` | `@mutation(typeName: UPDATE)`
- [ ] `RootFieldCase.SERVICE_MUTATION_FIELD` (L6708) -> `MutationField.MutationServiceTableField` | `@service` mutation, `@table` return
- [ ] `RootFieldCase.MUTATION_SERVICE_RECORD_FIELD` (L6721) -> `MutationField.MutationServiceRecordField` | `@service` mutation, non-table return
- [ ] `RootFieldCase.MUTATION_DML_RECORD_FIELD` (L6734) -> `MutationField.MutationDmlRecordField` | INSERT single `@table` input + single-record DML payload
- [ ] `MutationDmlCase.R144_DELETE_PK_COVERED_ADMIT` (L7966) -> `MutationField.MutationDeleteTableField` | DELETE with a PK-covering filter input
- [ ] `MutationDmlCase.R144_DELETE_MULTIROW_ADMIT` (L7976) -> `MutationField.MutationDeleteTableField` | DELETE without a PK filter but `multiRow: true`
- [ ] `ResultTypeBackingCase.BACKED_POJO` (L3459) -> `GraphitronType.PojoResultType.Backed` | `@service` producing a plain Java class
- [ ] `ResultTypeBackingCase.JAVA_RECORD` (L3471) -> `GraphitronType.JavaRecordType` | `@service` producing a Java record
- [ ] `ResultTypeBackingCase.JOOQ_TABLE_RECORD` (L3483) -> `GraphitronType.JooqTableRecordType` | `@service` producing a jOOQ TableRecord
- [ ] `TypeClassificationCase.ROOT_TYPE` (L5419) -> `GraphitronType.RootType` | the `Query` / `Mutation` root type
- [ ] `ErrorTypeCase.ADMIT_EXTRA_FIELD` (L5904) -> `GraphitronType.ErrorType` | `@error` type carrying a field beyond `path`/`message`
- [ ] `ErrorTypeCase.ERROR_PLUS_RECORD_IGNORES_RECORD` (L5979) -> `GraphitronType.ErrorType` | `@error` co-located with an (ignored) `@record`
- [ ] `NestingTypeCase.DOES_NOT_OVERRIDE_DIRECTIVE_CLASSIFICATION` (L9586) -> `GraphitronType.TableType` | a `@table` type classifies as `TableType`, not `NestingType`. *Nuance: carries an `isNotInstanceOf(NestingType)` negative beyond the bare verdict; confirm the corpus's positive `@classifiedType(as: TableType)` coverage suffices before retiring, or keep the negative as a dedicated robustness case.*

## Excluded by rule (do not retire from these)

- All **SLOT** rows (170): the slot detail they assert is the pipeline tier's job, not the corpus's.
- All **REJECTION** rows (178): `UnclassifiedField` / `UnclassifiedType` / directive-conflict /
  case-clash / null-field outcomes. A separate mechanism replaces these; out of scope for R281.
- All **INPUT** rows (24): `InputField.*` leaves and the `InputTypeCase` / `TableInputTypeCase`
  enums. Input-side classification stays in the enum table as its own game.

## Per-row re-verification (the second gate)

Before deleting any row above, the migrating session must confirm, from the `ClassifiedHarness`
per-coordinate leaf record (the `FieldCase.leaf()` / `TypeCase.leaf()` the step-3 run produces),
that the new corpus example's coordinate classifies to **exactly** the leaf this row names. A green
`VariantCoverageTest` is necessary but not sufficient: its union net also counts the remaining enum
rows, so it never proves the corpus picked the verdict up.
