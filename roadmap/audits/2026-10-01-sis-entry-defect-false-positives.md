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
