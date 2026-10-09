# What keying a condition reading to its method costs on sis

Measured 2026-10-13 on a copy of `sis` (`sis/upgrade-graphitron`), `sis-graphql-spec`'s one
`graphitron:generate` execution run offline, once with a plugin built from R876's tip at `05f85de3c`
and once with the same tip plus the condition reading keyed to its method, each from a local
repository of its own. Filed as an audit so the numbers survive the item.

The change replaced a stored relation, the catalog table each table-typed method parameter names,
with a join made inline where it is read: `graphitron_field_path_reading` and
`graphitron_condition_method_route` match the parameter's erased class to `sql_table.class_fqn`, the
route a second time inside its overload check.

## Whole capture

Per gatherer, from `store_graph_progress`, alternating the two plugins on a quiet machine (load
average between 2.1 and 2.8), in milliseconds:

| run | capture | graphitron | derivation | graphitron-ast |
|---|---|---|---|---|
| before, first | 87,188 | 32,707 | 31,491 | 5,976 |
| after, first | 89,749 | 34,138 | 31,406 | 6,551 |
| before, second | 90,852 | 35,744 | 30,780 | 7,911 |
| after, second | 92,045 | 35,068 | 33,359 | 6,471 |

The two stores hold the same facts to within a handful of rows: 2,593 classes, 828 path links, 2,500
field tables and 147 entry defects in both. The build reports 714 author errors after the change
against 1,004 before, the difference being the workspace's own modules read in place of their
installed copies rather than anything this change does.

## The views the change touched

Timed one relation at a time on the stores, three sweeps each, the two stores interleaved, with the
probe in `2026-10-09-argmapping-and-derivation-timings.md`:

| relation | before | after, no index | after, with the index |
|---|---|---|---|
| `graphitron_field_path_reading` | 955 ms | 662 ms | |
| `graphitron_field_path_resolution_rule` | 5,127 ms | 4,403 ms | |
| `graphitron_condition_method_route` | 2,194 ms | 6,704 ms | 2,301 ms |
| `graphitron_field_column_scope_rule` | 11,847 ms | 10,756 ms | |

Without an index on `sql_table.class_fqn` the route read three times slower, every match a scan.
`ix_sql_table_class_fqn` takes it back to where the stored relation had it, and the whole-capture
figures above are with the index. Earlier whole-capture runs taken while other builds shared the
machine swung by 30 s between identical runs and are not reported.
