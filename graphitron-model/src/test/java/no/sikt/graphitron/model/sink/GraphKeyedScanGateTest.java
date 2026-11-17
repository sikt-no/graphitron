package no.sikt.graphitron.model.sink;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No statement a capture runs scans a graph-keyed table without comparing that table's graph name:
 * one graph's capture reads its own rows, whatever else the store holds.
 *
 * <p>Read from plans, not timed. Every statement the fact documents' captures ran is planned with
 * {@code EXPLAIN ANALYZE} and the binds it first ran with, on the store that ran it, each rolled
 * back before the next; executed rather than only explained, because H2 optimises a view per set of
 * index conditions when it runs, so a bare {@code EXPLAIN} over a view can show a plan the
 * statement never ran. A statement that meets a key in the warm store, an insert whose rows are
 * already there or a delete whose dependents still are, cannot be executed again, so for one of
 * those the query it reads from, the {@code SELECT} of an {@code INSERT ... SELECT}, the source of a
 * {@code MERGE ... USING} or a delete's own {@code FROM ... WHERE}, is planned on its own with its
 * share of the binds, which executes without writing. Only a statement with no such query to lift out, and so no executed
 * plan to read, is explained bare, and counted.
 *
 * <p>A plan is read one table reference at a time, within its own query block: a parenthesis
 * opening a {@code SELECT} or {@code WITH}, or the whole plan. A full scan of a graph-keyed table,
 * of the table itself or of an index walked with no condition (which is how H2 reads a disjunction
 * it cannot split), is filtered when its block narrows that reference's graph name by equality or
 * {@code IN}, in a join, a {@code WHERE} or an index condition, and a finding when it does not. A
 * filtered full scan is the planner passing over an index, a cost rather than a leak, and is not
 * this gate's to fail.
 *
 * <p>As wide as the documents: a branch no document's binds take is a plan nobody ran.
 */
class GraphKeyedScanGateTest {

    /**
     * A table reference in a plan: the table, its alias if any, an index hint if any, and the access
     * comment H2 writes after it, {@code PUBLIC.T.tableScan} or an index with its condition.
     */
    private static final Pattern REFERENCE = Pattern.compile(
        "\"?PUBLIC\"?\\.\"?(\\w+)\"?(?:\\s+(?!USE\\b)\"?(\\w+)\"?)?(?:\\s+USE\\s+INDEX\\s*\\([^)]*\\))?"
            + "\\s*/\\*\\s*PUBLIC\\.([^*]*?)\\s*\\*/");

    private static final Set<String> PLANNED = Set.of("SELECT", "WITH", "INSERT", "MERGE", "UPDATE", "DELETE");

    /**
     * H2's SQL states for a unique or primary key violation and for a foreign key one: what a
     * statement meets when the warm store already holds the rows it wrote, or still holds rows its
     * capture had removed first.
     */
    private static final Set<String> KEY_CONFLICTS = Set.of("23505", "23503");

    record Scan(String table, String alias, boolean filtered) {}


    @Test
    @DisplayName("no statement a capture runs scans a graph-keyed table without comparing its graph name")
    void noCaptureStatementScansAnotherGraphsRows() {
        var graphKeyed = graphKeyed();
        var findings = new TreeSet<String>();
        var passedOver = new TreeSet<String>();
        int[] counts = new int[4]; // planned, lifted, bare, unplannable
        var unplannable = new java.util.TreeMap<String, String>();
        var held = new ArrayList<String>();
        FactGraphs.eachCaptureStatement((dsl, statements) -> dsl.connection(conn -> {
            // What the recording keeps: two bytes a character of text, and a rough sixteen a bind.
            long bytes = statements.entrySet().stream()
                .mapToLong(e -> 2L * e.getKey().length() + 16L * e.getValue().size()).sum();
            held.add(statements.size() + " texts ~" + bytes / 1024 + " KiB");
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                statements.forEach((sql, binds) -> {
                    if (!PLANNED.contains(firstWord(sql))) {
                        return;
                    }
                    String plan = plan(conn, sql, binds, counts, unplannable);
                    if (plan == null) {
                        return;
                    }
                    for (var scan : read(plan, graphKeyed)) {
                        (scan.filtered() ? passedOver : findings).add(scan.table() + " in " + brief(sql));
                    }
                });
            } finally {
                conn.rollback();
                conn.setAutoCommit(auto);
            }
        }));
        System.out.println("graph-keyed scans: planned " + counts[0] + ", lifted " + counts[1] + ", bare "
            + counts[2] + ", unplannable " + counts[3] + "; filtered full scans " + passedOver.size()
            + "; recorded per store, texts not handed over before: " + held);

        assertThat(counts[0]).as("the captures' statements reached the gate at all").isPositive();
        assertThat(findings)
            .as("full scans of a graph-keyed table no condition in their block narrows to one graph:"
                + " carry the graph into the statement")
            .isEmpty();
        assertThat(unplannable).as("statements neither executed, lifted nor explained, with why").isEmpty();
    }

    @Test
    @DisplayName("an unfiltered read of a graph-keyed table is a finding, and the same read filtered is not")
    void theReadingTellsAFilteredScanFromAnUnfilteredOne() {
        var graphKeyed = graphKeyed();
        String unfiltered = "SELECT * FROM graphitron_field";
        // The hint keeps the index out of the plan, so the read is a full scan the condition narrows.
        String filtered = "SELECT * FROM graphitron_field f USE INDEX () WHERE f.graph_name = ?";
        String other = "SELECT * FROM graphitron_field f USE INDEX () WHERE f.graph_name <> ?";
        // A disjunction over two columns walks the primary key whole rather than scanning the table.
        String walked = "SELECT * FROM graphitron_field f WHERE f.graph_name = ? OR f.field_name = ?";
        var readings = new ArrayList<List<Scan>>();
        var unplannable = new java.util.TreeMap<String, String>();
        FactGraphs.eachCaptureStatement((dsl, statements) -> {
            if (readings.isEmpty()) {
                dsl.connection(conn -> {
                    conn.setAutoCommit(false);
                    readings.add(read(plan(conn, unfiltered, List.of(), new int[4], unplannable), graphKeyed));
                    readings.add(read(plan(conn, filtered, List.of("g"), new int[4], unplannable), graphKeyed));
                    readings.add(read(plan(conn, other, List.of("g"), new int[4], unplannable), graphKeyed));
                    readings.add(read(plan(conn, walked, List.of("g", "f"), new int[4], unplannable), graphKeyed));
                    conn.rollback();
                    conn.setAutoCommit(true);
                });
            }
        });
        assertThat(unplannable).as("the controls planned").isEmpty();
        assertThat(readings.get(0)).as("an unfiltered read")
            .singleElement().isEqualTo(new Scan("GRAPHITRON_FIELD", "GRAPHITRON_FIELD", false));
        assertThat(readings.get(1)).as("the same read narrowed to one graph")
            .singleElement().isEqualTo(new Scan("GRAPHITRON_FIELD", "F", true));
        assertThat(readings.get(2)).as("and narrowed to every graph but one")
            .singleElement().isEqualTo(new Scan("GRAPHITRON_FIELD", "F", false));
        assertThat(readings.get(3)).as("an index walked whole is a full scan too")
            .singleElement().extracting(Scan::table).isEqualTo("GRAPHITRON_FIELD");
    }

    /** The base tables keyed by graph name. */
    private static Set<String> graphKeyed() {
        var tables = new TreeSet<String>();
        for (var table : Public.PUBLIC.getTables()) {
            if (table.field("GRAPH_NAME") != null && table.getOptions().type() != TableOptions.TableType.VIEW) {
                tables.add(table.getName().toUpperCase());
            }
        }
        return tables;
    }

    /**
     * {@code sql}'s executed plan, rolled back after: the whole statement, or the query an insert
     * reads from where the statement's own rows are already in the store, or failing that the bare
     * plan. Null where none of the three could be had, counted in {@code counts[3]} and said why in
     * {@code unplannable}.
     */
    private static String plan(Connection conn, String sql, List<Object> binds, int[] counts,
                               Map<String, String> unplannable) {
        try {
            String plan = explain(conn, "EXPLAIN ANALYZE ", sql, binds);
            counts[0]++;
            return plan;
        } catch (SQLException executed) {
            if (!KEY_CONFLICTS.contains(executed.getSQLState())) {
                counts[3]++;
                unplannable.put(brief(sql), String.valueOf(executed.getMessage()));
                return null;
            }
            int[] source = source(sql);
            try {
                if (source != null) {
                    String read = source[0] == 0 ? "SELECT * " + sql.substring("DELETE ".length())
                        : sql.substring(source[0], source[1]);
                    String plan = explain(conn, "EXPLAIN ANALYZE ", read,
                        binds.subList(markersBefore(sql, source[0]), markersBefore(sql, source[1])));
                    counts[0]++;
                    counts[1]++;
                    return plan;
                }
                String plan = explain(conn, "EXPLAIN ", sql, binds);
                counts[0]++;
                counts[2]++;
                return plan;
            } catch (SQLException lifted) {
                counts[3]++;
                unplannable.put(brief(sql), String.valueOf(lifted.getMessage()));
                return null;
            }
        }
    }

    private static String explain(Connection conn, String prefix, String sql, List<Object> binds)
        throws SQLException {
        var savepoint = conn.setSavepoint();
        try (var statement = conn.prepareStatement(prefix + sql)) {
            for (int i = 0; i < binds.size(); i++) {
                Object value = binds.get(i);
                statement.setObject(i + 1, value instanceof Enum<?> e ? e.name() : value);
            }
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } finally {
            conn.rollback(savepoint);
        }
    }

    /**
     * Where the query {@code sql} reads from starts and ends: the {@code SELECT} after an insert's
     * column list, the parenthesised source of a {@code MERGE ... USING}, or the whole of a
     * {@code DELETE}, whose read is its own {@code FROM ... WHERE}. Null for anything else, an insert
     * of literal values among them.
     */
    static int[] source(String sql) {
        String upper = sql.toUpperCase();
        if (upper.startsWith("DELETE FROM ")) {
            return new int[] {0, sql.length()};
        }
        if (upper.startsWith("INSERT")) {
            int columns = upper.indexOf('(');
            int after = columns < 0 ? -1 : closing(sql, columns) + 1;
            if (after <= 0) {
                return null;
            }
            String rest = upper.substring(after).stripLeading();
            if (!rest.startsWith("SELECT") && !rest.startsWith("WITH")) {
                return null;
            }
            int start = upper.indexOf(rest.substring(0, 4), after);
            int end = upper.indexOf(" ON CONFLICT", start);
            return new int[] {start, end < 0 ? sql.length() : end};
        }
        if (upper.startsWith("MERGE")) {
            int using = upper.indexOf(" USING ");
            if (using < 0) {
                return null;
            }
            int open = upper.indexOf('(', using);
            if (open < 0 || !upper.substring(using + 7, open).isBlank()) {
                return null;
            }
            int close = closing(sql, open);
            return close < 0 ? null : new int[] {open + 1, close};
        }
        return null;
    }

    /** The parenthesis closing the one at {@code open}, skipping quoted text; -1 when there is none. */
    private static int closing(String sql, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** How many bind markers {@code sql} holds before {@code end}, outside quoted text. */
    private static int markersBefore(String sql, int end) {
        int markers = 0;
        char quote = 0;
        for (int i = 0; i < end; i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '?') {
                markers++;
            }
        }
        return markers;
    }

    /**
     * Each full scan of a graph-keyed table in {@code plan}, filtered when its own block compares its
     * alias's graph name; see the class comment for what a block is.
     */
    static List<Scan> read(String plan, Set<String> graphKeyed) {
        if (plan == null) {
            return List.of();
        }
        int[] block = new int[plan.length()];
        var own = new HashMap<Integer, StringBuilder>();
        var open = new ArrayDeque<int[]>(); // each block's id and its unclosed plain parentheses
        open.push(new int[] {0, 0});
        int blocks = 1;
        for (int i = 0; i < plan.length(); i++) {
            char c = plan.charAt(i);
            int id = open.peek()[0];
            if (c == '(') {
                String ahead = plan.substring(i + 1, Math.min(plan.length(), i + 12)).stripLeading().toUpperCase();
                if (ahead.startsWith("SELECT") || ahead.startsWith("WITH")) {
                    open.push(new int[] {blocks++, 0});
                } else {
                    open.peek()[1]++;
                }
            } else if (c == ')') {
                if (open.peek()[1] > 0) {
                    open.peek()[1]--;
                } else if (open.size() > 1) {
                    open.pop();
                }
            }
            block[i] = id;
            own.computeIfAbsent(id, k -> new StringBuilder()).append(c);
        }
        var scans = new ArrayList<Scan>();
        var m = REFERENCE.matcher(plan);
        while (m.find()) {
            String table = m.group(1).toUpperCase();
            // A full scan: the table's own, or an index walked whole, which H2 writes with no condition.
            String access = m.group(3);
            boolean whole = access.endsWith(".tableScan") || !access.contains(":");
            if (!whole || !graphKeyed.contains(table)) {
                continue;
            }
            String alias = m.group(2) == null ? table : m.group(2).toUpperCase();
            // Narrowed, not merely selected: a select list names every column, graph_name among
            // them, and an inequality reads every other graph.
            String column = "\"?" + Pattern.quote(alias) + "\"?\\.\"?GRAPH_NAME\"?";
            var compared = Pattern.compile("(?i)" + column + "\\s*(=|IN\\b)|(=|\\bIN\\s*\\()\\s*" + column);
            scans.add(new Scan(table, alias, compared.matcher(own.get(block[m.start()])).find()));
        }
        return scans;
    }

    private static String firstWord(String sql) {
        String upper = sql.stripLeading().toUpperCase();
        int end = 0;
        while (end < upper.length() && Character.isLetter(upper.charAt(end))) {
            end++;
        }
        return upper.substring(0, end);
    }

    private static String brief(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.substring(0, Math.min(160, flat.length()));
    }
}
