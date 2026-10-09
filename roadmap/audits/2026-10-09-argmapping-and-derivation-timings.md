# How the argMapping family and the derivation stages cost, on sakila and on sis

Measured 2026-10-09 on trunk-side `192088aca`, to decide whether flipping the argMapping readers
onto `graphitron_ast_argmapping_pair_entry` carries a performance risk, and to find where a
consumer-size capture actually spends its time. Read-only throughout: every figure was taken on a
copy of a store a real plugin run wrote, and nothing in either tree changed to take them.

## Getting a store

**sakila.** `graphitron-sakila-example` runs `generate` three times, the main schema, the federated
fixture and the multitenant fixture, and all three write one graph name into one store directory.
The store a build leaves under `graphitron-sakila-example/target/graphitron-model/<stamp>-dev/` is
therefore the last fixture's, 27 fields. To measure the main schema, run its execution alone after
an install of the plugin:

```bash
mvn -pl graphitron-sakila-example \
  no.sikt:graphitron-maven-plugin:10-SNAPSHOT:generate@rewrite-generate \
  -Dmaven.repo.local=... -Dmaven.repo.local.tail=...
```

That store held 929 fields, 23,583 `code_method` rows and 51 argMapping entries.

**sis.** In `/home/ai/temp/fs-plattform_claude-a/sis`, the `sis-graphql-spec` module's one
execution, offline so nothing reaches sis's remote repositories, with this session's repository so
the plugin is the one built from the tip being measured:

```bash
mvn -o -pl sis-graphql-spec \
  no.sikt:graphitron-maven-plugin:10-SNAPSHOT:generate@generate \
  -Dmaven.repo.local=... -Dmaven.repo.local.tail=...
```

It ends in validation errors, the porting state `GRAPHITRON10_STATUS.md` tracks, after capture has
written the store, which is all a measurement needs. The store lands under
`sis-graphql-spec/target/graphitron-model/<stamp>-dev/`, a new stamp directory beside older ones.
It held 7,134 fields, 2,511 `code_method` rows, 50 graph sources and 121 argMapping entries.

Copy `store.mv.db` out before querying it; the copies were never written to.

## The probe

A single-file program run as `java -cp h2-2.4.240.jar Probe.java <store-path-without-.mv.db>
<sweeps> <relation-or-query>...`. A bare name is timed as `SELECT count(*) FROM <name>`; an argument
starting with `SELECT` or `WITH` is run as given. The store opens with empty credentials, read-only.
It turns result reuse off and query statistics on, runs the list in interleaved sweeps rather than
adjacent repeats, and prints H2's own `INFORMATION_SCHEMA.QUERY_STATISTICS`, each row mapped back to
its argument's position by its full text.

```java
import java.sql.*;
import java.util.*;

public class Probe {
    public static void main(String[] a) throws Exception {
        String db = a[0];
        int sweeps = Integer.parseInt(a[1]);
        List<String> queries = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            queries.add(a[i].startsWith("SELECT") || a[i].startsWith("WITH") ? a[i]
                : "SELECT count(*) FROM " + a[i]);
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + db + ";ACCESS_MODE_DATA=r", "", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("SET OPTIMIZE_REUSE_RESULTS FALSE");
                s.execute("SET QUERY_STATISTICS_MAX_ENTRIES 2000");
                s.execute("SET QUERY_STATISTICS TRUE");
            }
            for (int sweep = 0; sweep < sweeps; sweep++) {
                for (String q : queries) {
                    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(q)) {
                        if (sweep == 0 && r.next()) {
                            System.out.println("ROWS q=" + (queries.indexOf(q) + 1) + " " + r.getString(1));
                        }
                    }
                }
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT SQL_STATEMENT, EXECUTION_COUNT, CUMULATIVE_EXECUTION_TIME, MAX_EXECUTION_TIME,"
                    + " AVERAGE_EXECUTION_TIME, STD_DEV_EXECUTION_TIME FROM INFORMATION_SCHEMA.QUERY_STATISTICS"
                    + " ORDER BY CUMULATIVE_EXECUTION_TIME DESC")) {
                while (r.next()) {
                    String sql = r.getString(1);
                    if (sql.startsWith("SET ")) continue;
                    System.out.printf("STAT q=%d n=%d cum=%.1f max=%.1f avg=%.1f sd=%.1f%n",
                        queries.indexOf(sql) + 1, r.getInt(2), r.getDouble(3), r.getDouble(4),
                        r.getDouble(5), r.getDouble(6));
                }
            }
        }
    }
}
```

## Stage timings

The derivation stratum prints a line per stage at debug level, before its statements and after
them, so the same sis command with `-X` added names every stage's cost; grep the log for
`graphitron:  N/28`. That run reused a warm store, the single-transaction cadence.

## Bisecting a stage's rule

`EntryDefects` inserts `graphitron_entry_defect_rule`, one view of a `WITH` block and 21 top-level
`UNION ALL` branches. Each branch was timed alone as the rule's own `WITH` block followed by
`SELECT count(*) FROM (<branch>) armN (<the view's column list>)`, the text cut out of the DDL with
SQL comments stripped and the split made at `UNION ALL` lines at parenthesis depth zero. The 21
queries ran as one probe invocation, three sweeps.

## Figures

All are averages over the sweeps named, in milliseconds, with the standard deviation H2 reported.

**argMapping family, sakila, five sweeps.**

| relation | rows | avg | sd |
|---|---|---|---|
| `intent_node_id_decode_slot` | 21 | 144 | 49 |
| `intent_resolved_node_key_projection` | 7 | 52 | 20 |
| `intent_argmapping_key_column_candidate` | 7 | 19 | 15 |
| `intent_type_backing_seed` | 57 | 18 | 9 |
| `intent_argmapping_projection_defect` | 0 | 17 | 10 |
| `intent_argmapping_bound_parameter_type` | 51 | 10 | 10 |
| `graphitron_argmapping_match` | 51 | 6 | 10 |
| `graphitron_argmapping_entry` | 51 | <1 | |
| `graphitron_ast_argmapping_pair_entry` | 51 | <1 | |
| `graphitron_argmapping_candidate` | 1,779 | <1 | |

**argMapping family, sis, three sweeps.**

| relation | rows | avg | sd |
|---|---|---|---|
| `intent_node_id_decode_slot` | 65 | 800 | 70 |
| `intent_argmapping_key_column_candidate` | 0 | 58 | 35 |
| `intent_resolved_node_key_projection` | 0 | 36 | 9 |
| `intent_argmapping_projection_defect` | 0 | 19 | 9 |
| `intent_argmapping_bound_parameter_type` | 121 | 15 | 13 |
| `intent_type_backing_seed` | 106 | 13 | 6 |
| `graphitron_argmapping_match` | 121 | 10 | 11 |
| `graphitron_argmapping_entry` | 121 | <1 | |
| `graphitron_ast_argmapping_pair_entry` | 121 | <1 | |
| `graphitron_argmapping_candidate` | 13,278 | <1 | |

So the argMapping family is not where a capture's time goes, and its replacement costs nothing to
read: the flip moves its readers from one 121-row table onto another.

**Derivation stages on sis**, one `-X` run, 37.2 s in all. Four stages carry 30.5 s:
`InputOccurrencePaths` 10.8 s (6,555 rows), `FieldColumnScopes` 8.9 s (3,208), `EntryDefects`
6.7 s (147), `NodeIdDecodeColumns` 4.1 s (1,322). `ArgMappingCandidates` is 0.82 s for 13,278 rows,
and no other stage reaches 1.3 s.

**`graphitron_entry_defect_rule` on sis**, three sweeps: the whole view 6,059 ms (sd 121), against
which the branches sum. Two carry it.

| branch | rows | avg | sd |
|---|---|---|---|
| 7, where a chain broke: `NO_ROUTE_TO_TARGET`, `ELEMENT_UNRESOLVED`, `NO_ROUTE_FROM_DEPARTURE` | 99 | 3,431 | 94 |
| 20, `TYPE_NAME_CASE_COLLISION` | 0 | 2,514 | 79 |
| 21, `COLUMN_UNRESOLVED` | 0 | 64 | 10 |
| 9, the code-reference defects | 0 | 54 | 3 |
| the other seventeen | | under 45 each | |

The 99 rows of branch 7 are the sis false positives `2026-10-01-sis-entry-defect-false-positives.md`
counts. Neither expensive branch has been bisected further; the next axes are each CTE on its own
and one join dropped at a time.

## Traps met

* A store from the example's full build is the multitenant fixture's, so every argMapping relation
  reads empty and every timing reads cheap. Check row counts before reading a timing.
* Splitting the rule by text and matching statistics back by prefix assigns every branch to the
  first, all of them opening with the same `WITH` block; match on the whole statement.
* The store's connection has no user, so `sa` is refused.
