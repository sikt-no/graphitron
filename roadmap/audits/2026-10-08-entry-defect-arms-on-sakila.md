# The type, node and column rules against the sakila example

Taken 2026-10-08 for the R876 commit that moves the first of the walk's rules into
`graphitron_entry_defect`: eleven codes, in the defect model's shape, `basis` and a position in
`graphql_ast_entry`. The question is the agreement check `2026-10-02-sakila-entry-defect-false-positives.md`
sets for entry defects: an example that generates and passes its tests draws no error-severity defect
it does not deserve.

## Result

None of the eleven codes draws a row. What the example draws is exactly the 44 rows that audit
lists, in its five codes and counts, every one of them `GRAPHITRON` and every one resolving to an
element through `graphitron_entry_defect_site`:

| code | rows |
|---|---|
| `CHAIN_WITHOUT_TARGET` | 19 |
| `NO_ROUTE_FROM_DEPARTURE` | 11 |
| `NO_ROUTE_TO_TARGET` | 8 |
| `ELEMENT_UNRESOLVED` | 4 |
| `CODE_REFERENCE_METHOD_AMBIGUOUS` | 2 |

The populations the new codes judged were real: 78 `@table` applications, 19 `@node` applications
and 20 resolved nodes, one pinned key column, two `@scalarType` references, 346 types the generator
writes a file for, and 188 scalar fields whose column the parent binding or a path's terminal names.

## Method

One goal and one query, `graphitron:capture` running no pass that can refuse:

```
mvn -pl graphitron-sakila-example no.sikt:graphitron-maven-plugin:10-SNAPSHOT:capture
java -cp h2.jar org.h2.tools.RunScript \
  -url "jdbc:h2:file:<module>/target/graphitron-model/<stamp>/store;ACCESS_MODE_DATA=r" \
  -user "" -password "" -showResults -script q.sql
```

with `q.sql` counting `graphitron_entry_defect` by code and `graphitron_entry_defect_site` rows with
and without a coordinate.
