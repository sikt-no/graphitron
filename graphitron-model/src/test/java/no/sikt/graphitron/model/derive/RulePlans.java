package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What H2 plans for each rule view as its stage reads it, filtered to one graph, and the lookups in
 * those plans that read a whole partition per row.
 *
 * <p>A plan prints each table it reads on a {@code FROM} or {@code JOIN} line, followed by a comment
 * naming how: {@code tableScan}, or an index with the columns the lookup binds. Every table carries
 * an index leading with {@code graph_name} or {@code source_name}, its primary key or the key into its
 * partition, so a lookup whose own index is missing does not scan: it probes that index with the
 * partition alone bound, which on a store holding one graph reads the whole table, once for every row
 * of whatever drives it. That is the shape every capture-time regression on sis has had.
 */
final class RulePlans {

    private RulePlans() {}

    /** One lookup that reads a partition per row: the rule it sits in, the table, and how. */
    record PartitionRead(String rule, String table, String how) {}

    private static final Set<String> PARTITION = Set.of("GRAPH_NAME", "SOURCE_NAME");
    private static final Pattern ITEM = Pattern.compile("(?:FROM|JOIN) \"?(?:PUBLIC\"?\\.\"?)?(\\w+)\"?");
    private static final Pattern ACCESS = Pattern.compile("/\\* PUBLIC\\.(\\w+?)(?:\\.(tableScan)| ?: (.*))$");
    private static final Pattern BOUND = Pattern.compile(
        "^\"?(\\w+)\"? (?:=|IN\\(|>=|<=|>|<|IS NOT DISTINCT FROM) ?(\"?\\w+\"?\\.\"?\\w+\"?)?");

    /** Every view whose name ends in {@code _rule}, which is every rule a stage stores. */
    static List<String> rules(DSLContext dsl) {
        return dsl.fetch("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'PUBLIC' AND table_type = 'VIEW' AND table_name LIKE '%\\_RULE'
                 ORDER BY table_name""")
            .getValues(0, String.class);
    }

    /**
     * How many columns each unique index has, by name: a probe binding all of them is a key lookup,
     * and one binding fewer, or probing an index that is not unique, reads every row it matches.
     */
    static Map<String, Integer> keyWidths(DSLContext dsl) {
        return dsl.fetch("""
                SELECT c.index_name, COUNT(*) FROM information_schema.index_columns c
                  JOIN information_schema.indexes i
                    ON i.index_schema = c.index_schema AND i.index_name = c.index_name
                 WHERE c.table_schema = 'PUBLIC' AND i.index_type_name IN ('PRIMARY KEY', 'UNIQUE INDEX')
                 GROUP BY c.index_name""")
            .intoMap(r -> r.get(0, String.class), r -> r.get(1, Integer.class));
    }

    /** The plan for {@code rule} as its stage reads it, filtered to {@code graphName}. */
    static String plan(DSLContext dsl, String rule, String graphName) {
        return dsl.fetchValue("EXPLAIN SELECT * FROM " + rule + " WHERE graph_name = ?", graphName)
            .toString();
    }

    /**
     * The lookups in {@code plan} that read a whole partition per row: a table scanned in a join, or
     * an index probed per row of another table with nothing but {@code graph_name} and
     * {@code source_name} bound, unless they are the whole of a unique index. A table driving its
     * block, its partition bound by the graph filter or not at all, is read once and is not one; nor
     * is a probe binding every column of a unique index, which is a key lookup.
     */
    static List<PartitionRead> partitionReads(String rule, String plan, Map<String, Integer> keyWidths) {
        var found = new ArrayList<PartitionRead>();
        String[] lines = plan.split("\n");
        for (int i = 1; i < lines.length; i++) {
            Matcher access = ACCESS.matcher(lines[i].trim());
            Matcher item = ITEM.matcher(lines[i - 1].trim());
            if (!access.find() || !item.find()) {
                continue;
            }
            String table = item.group(1);
            boolean join = lines[i - 1].contains("JOIN ");
            if (access.group(2) != null) {
                if (join) {
                    found.add(new PartitionRead(rule, table, "scanned in a join"));
                }
                continue;
            }
            var bound = new LinkedHashSet<String>();
            boolean perRow = false;
            for (String part : condition(lines, i, access.group(3)).split(" AND ")) {
                Matcher b = BOUND.matcher(part.trim());
                if (b.find()) {
                    bound.add(b.group(1));
                    perRow |= b.group(2) != null;
                }
            }
            boolean prefix = bound.size() < keyWidths.getOrDefault(access.group(1), Integer.MAX_VALUE);
            if ((join || perRow) && !bound.isEmpty() && PARTITION.containsAll(bound) && prefix) {
                found.add(new PartitionRead(rule, table, "probed by " + String.join(", ", bound)));
            }
        }
        return found;
    }

    /** An index condition, which H2 continues over lines of its own until the comment closes. */
    private static String condition(String[] lines, int at, String first) {
        var text = new StringBuilder(first);
        for (int k = at + 1; !text.toString().contains("*/") && k < lines.length; k++) {
            String more = lines[k].trim();
            if (more.startsWith("/*")) {
                break;
            }
            text.append(' ').append(more);
        }
        return text.toString().replace("*/", "").trim();
    }
}
