# What the entry defects report on sis that the generator does not

Observed 2026-10-01 on the sis schema (`sis-graphql-spec`, `mvn graphitron:dev`) on a jar built
from trunk at `68dfa4ceb`. The generator's own run reported 636 errors, all of them tenancy. The
MCP `diagnostics` tool reported 906. The difference is 270 entries with no kind: 161 from
`graphitron_entry_defect` and 108 undeclared-directive rows from `graphql_schema_problem`, plus one
bare message. The 108 are the capture assembling without the federation `@link`, filed as R985
because no branch of this item touches the document gatherers. This audit covers the 161.

Each class was checked by hand against the catalog and the consumer sources by the session that
found them. The mechanisms below were read from the SQL and the Java and not confirmed against the
store, the store console being off in that session. Filed as an audit for the reason the others
state: the item's file dies at Done and the evidence has to survive it.

## The four classes

| code | count | example | mechanism |
|---|---|---|---|
| `NO_ROUTE_FROM_DEPARTURE` | 99 | `VurderingsperiodeinformasjonForVurderingsoppbygningsdelGjelderITerminPeriode.fraTermin: Termin @reference(path: [{key: "VURDERINGSKOMBINASJONSTID__FRA__TERMIN__FK"}])`, on a nested type whose parent is `@table(name: "VURDERINGSKOMBINASJONSTID")` | `FieldEndpoints` left-joins `graphitron_tabletype` on the enclosing type itself, so a type with no `@table` of its own gives a null departure, which the column defines as a root field's. The key does depart from the parent's table. |
| `CHAIN_WITHOUT_TARGET` | 48 | `Studieprogramkull.skalEksporteresTilUtdanningsregisteret: Boolean @reference(path: [{table: "KULL"}, {table: "STUDIEPROGRAM"}]) @field(name: "STATUS_EKSPORT_UTDREG")` | `graphitron_field_table.target_basis` has three rules, all reading a table-bound return or a routine result. A scalar read through a path has neither, so it has no row and the rule reads that absence as an unbound return. |
| `ROUTE_AMBIGUOUS` | 5 | `EvuKurs.folgerUndervisningForKurs: EvuKurs @reference(path: [{key: "EVU_KURS__FELLES__EVU_KURS__FK"}])`; also `EvuKursaktivitet.parent`, `Organisasjonsenhet.parent` | The KEY arm of `graphitron_field_chain_link_reading` reads both hops of the named constraint from `sql_constraint_hop`. On a self-reference both hops depart and arrive at one table, so head and tail reach both and `routes` counts two. |
| `ELEMENT_UNRESOLVED` | 9 | `EmneSoknadRegistrert.emnesoknad @reference(path: [{condition: {className: "...EmneSoknadshendelseConditions", method: "apiHendelseEmneSoknadJoinCondition"}}])`, the method being `public static Field<Boolean> apiHendelseEmneSoknadJoinCondition(ApiHendelse, Emnesoknad)` in a sibling module | The CONDITION arm joins `code_condition_method` and requires a `code_condition_method_parameter_table` row at position 1. One of the two is missing for this method. Which one is not established. |

## Where each came from

All four codes reached the surface on 2026-09-27: `d3fc317cb` (the ambiguous route is a row),
`5eaa3b0ec` (a broken chain says where it broke) and `3874671fe` (the defects reach the surface).
The readings they judge are older. The departure and the target bases are from `2614b1745`
(2026-09-04, where a field's rows come from and where it departs from), with the routine arm added in
`6195e01a0`. The KEY and CONDITION arms are from `100e80269` (2026-09-25, the chain resolution is a
walk in the catalog). `1d1a6117b` and `890c72284` store these relations rather than change them,
and were not tested for equivalence on sis.

Separating a reading that was always wrong from one that regressed would need sis run against jars
built at `5eaa3b0ec~1` and at `3874671fe`. That was not done.

## What the generator does instead

Read from the generator's sources on 2026-10-01, a day after the classes above were found. File
and line references are to trunk at `f5a571e`.

* **Departure.** A type with no `@table` is never classified on its own. Its fields are classified
  from the field that embeds it, which passes the enclosing `TableBackedType` down unchanged
  (`FieldBuilder.java` nesting arm, 1843-1912), so a path on a nested field departs from the
  nearest `@table` ancestor's table, through any number of nesting levels. A nested type reached
  from several parents is classified once per parent; `GraphitronSchemaValidator`'s
  `validateNestingParentCompat` then requires the parents to agree field by field, including on a
  scalar `@reference`'s terminal table. So the store's departure is a set per nested type, and the
  generator only accepts a set whose members agree on what each field reads.
* **Scalar target.** A scalar `@reference` is a `ColumnBackedReferenceField`; its table is
  `ServiceCatalog.terminalTableForReference`, the last hop's target of the path walked from the
  departure. A `{table:}` step arrives at its table, a `{key:}` step at the key's other end, a
  `{condition:}` step at the method's second parameter. The column named by `@field(name:)` is then
  looked up there.
* **Self-referencing key.** `JooqCatalog.foreignKeyOnSource` returns a cardinality hint when the
  key's two ends are one table class: `!isList`, so a single-valued field runs along the key to
  its parent and a list or connection runs against it to the children. Scalar fields always pass
  `isList = false`. The hint governs `{key:}` and `{table:}` steps alike. The store's older walk
  (`FieldReferenceStepHops.crossesTables`) always keeps the forward hop, which agrees with the
  generator on single-valued fields and not on list ones.
* **Condition-only element.** The generator does not check a condition method's return type at
  all: `ServiceCatalog.admitConditionShape` requires overloads to agree on it and nothing else, and
  the call is rendered verbatim into `.on(...)` or `.where(...)`, both of which jOOQ overloads for
  `Field<Boolean>`. `CodeCapture.isConditionMethod` admits only an erased return of exactly
  `org.jooq.Condition`, so `apiHendelseEmneSoknadJoinCondition`, returning `Field<Boolean>`, has no
  `code_condition_method` row and the CONDITION arm has nothing to join. That settles the open
  question above: the method row is the missing one. Where the rendered call is the first term of
  a chain (`ConditionGlueRenderer.reachExists` appending `.and(...)`), a `Field<Boolean>` would not
  compile, `org.jooq.Field` declaring no `and`; read from the jOOQ signatures, not built.

## What was changed

* `ROUTE_AMBIGUOUS`: the chain-link reading keeps the self-referencing hop the field's cardinality
  names (`f147ce6`).
* `ELEMENT_UNRESOLVED`: `CodeCapture.isConditionMethod` admits a `Field<Boolean>` return beside
  `Condition`, the type argument read from the signature so a raw or non-boolean `Field` stays out.
  The manual is unchanged and documents `Condition` only: the generator tolerating `Field<Boolean>`
  is not the same as every placement of the call compiling, which the note on `reachExists` above
  leaves unverified.
