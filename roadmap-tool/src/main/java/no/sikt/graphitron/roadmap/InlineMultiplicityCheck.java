package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports how many relation instantiations one read of each derived view expands to, computed from
 * the fact schema DDL alone.
 *
 * <p>H2 inlines a view wherever it is named and eliminates no common subexpression, so a relation a
 * derivation names four times is evaluated far more than four times, and the multiplicities compound
 * down a tree of views. The cost is invisible at every call site: a reader sees one {@code SELECT}
 * against one name. It is visible here, because the metric is textual and static, a count of each
 * relation's references in each {@code CREATE VIEW} body multiplied down the tree, needing no
 * database and no profiler.
 *
 * <p>Reports rather than gates. A ceiling would have to be a number somebody could defend, and the
 * metric is a deliberate over-approximation: it counts textual references without knowing which
 * arms a predicate prunes, so the honest ceiling is generous enough to catch order-of-magnitude
 * cases and nothing tighter. Until a few reductions give that number a basis, printing the ranking
 * is what the metric is for. A materialized relation is exempt by construction rather than by
 * exemption: its canonical name is a table, and a table's subtree is itself.
 *
 * <p>Two parsing details are load-bearing and both were learned the hard way. The scan strips
 * {@code --} line comments as well as {@code COMMENT ON} statements, because the schema's prose
 * section headers are line comments and attributing them to the relation whose block precedes them
 * inflates a total by dozens and invents direct children. And a reference is matched on word
 * boundaries that exclude a leading dot, so a column named after a relation is not counted as one.
 */
final class InlineMultiplicityCheck {

    /** The fact schema, relative to the repository root. */
    private static final String DDL =
        "graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql";

    /** How many of the heaviest relations to print. */
    private static final int TOP = 15;

    private static final Pattern CREATE_VIEW =
        Pattern.compile("^CREATE\\s+VIEW\\s+(\\w+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern CREATE_TABLE =
        Pattern.compile("^CREATE\\s+TABLE\\s+(\\w+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private InlineMultiplicityCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root holding the fact
     * schema. Returns 0 on success and 64 on a usage or non-directory-root error; throws
     * {@link BuildFailure} when the DDL is missing or yields no views, which would make the report
     * silently empty rather than absent.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: report-inline-multiplicity <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        Path ddl = root.resolve(DDL);
        if (!Files.isRegularFile(ddl)) {
            throw new BuildFailure("the fact schema is not at " + ddl
                + "; the multiplicity report reads the DDL directly and has nothing to scan");
        }
        Schema schema = parse(Files.readString(ddl));
        if (schema.views().isEmpty()) {
            throw new BuildFailure("no CREATE VIEW statements found in " + ddl
                + "; the report would be vacuous, which reads as a schema with no derived reads");
        }
        report(schema);
        return 0;
    }

    /** The relations the DDL declares, with each view's body kept for reference counting. */
    record Schema(Map<String, String> views, Set<String> tables) {

        Set<String> relations() {
            var all = new LinkedHashSet<>(views.keySet());
            all.addAll(tables);
            return all;
        }
    }

    /**
     * Splits the DDL into its {@code CREATE VIEW} bodies and its table names, with line comments
     * and string literals removed first so neither prose nor a quoted relation name is counted as
     * a reference.
     */
    static Schema parse(String ddl) {
        String stripped = stripCommentsAndLiterals(ddl);
        var views = new LinkedHashMap<String, String>();
        var tables = new LinkedHashSet<String>();
        for (String statement : stripped.split(";\\s*\\n")) {
            String trimmed = statement.strip();
            Matcher view = CREATE_VIEW.matcher(trimmed);
            if (view.find()) {
                views.put(view.group(1).toLowerCase(Locale.ROOT), trimmed);
                continue;
            }
            Matcher table = CREATE_TABLE.matcher(trimmed);
            if (table.find()) {
                tables.add(table.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return new Schema(views, tables);
    }

    /**
     * Blanks {@code --} line comments and the contents of string literals in one linear pass.
     *
     * <p>One pass rather than two regexes because both need the same quote state and because the
     * obvious literal pattern ({@code '(?:''|[^'])*'}) backtracks catastrophically on a schema this
     * size. Quote state spans lines, since a {@code COMMENT ON} literal may; the doubled-quote
     * escape needs no special case, two adjacent quotes toggling through to the same state.
     */
    static String stripCommentsAndLiterals(String ddl) {
        var out = new StringBuilder(ddl.length());
        boolean quoted = false;
        int i = 0;
        while (i < ddl.length()) {
            char c = ddl.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    quoted = false;
                    out.append(c);
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                }
                i++;
            } else if (c == '\'') {
                quoted = true;
                out.append(c);
                i++;
            } else if (c == '-' && i + 1 < ddl.length() && ddl.charAt(i + 1) == '-') {
                while (i < ddl.length() && ddl.charAt(i) != '\n') {
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * How many times each relation is named in {@code body}, excluding the view's own name so a
     * self-reference in a comment header cannot make a view its own child.
     */
    static Map<String, Integer> references(Schema schema, String owner, String body) {
        var counts = new LinkedHashMap<String, Integer>();
        String withoutHeader = body.replaceFirst("(?i)^CREATE\\s+VIEW\\s+\\w+", "");
        for (String relation : schema.relations()) {
            if (relation.equals(owner)) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(relation) + "(?![\\w])")
                .matcher(withoutHeader);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                counts.put(relation, n);
            }
        }
        return counts;
    }

    /**
     * Relation instantiations one read of {@code relation} expands to, itself included. A table is
     * 1, which is what makes a materialized relation exempt by construction. A cycle contributes 1
     * rather than recursing, the schema being acyclic by gate and this being a guard against a
     * future edit rather than an expected shape.
     */
    static int subtree(Schema schema, String relation, Map<String, Integer> memo, Set<String> stack) {
        if (!schema.views().containsKey(relation) || stack.contains(relation)) {
            return 1;
        }
        Integer cached = memo.get(relation);
        if (cached != null) {
            return cached;
        }
        stack.add(relation);
        int total = 1;
        for (var entry : references(schema, relation, schema.views().get(relation)).entrySet()) {
            total += entry.getValue() * subtree(schema, entry.getKey(), memo, stack);
        }
        stack.remove(relation);
        memo.put(relation, total);
        return total;
    }

    private static void report(Schema schema) {
        var memo = new HashMap<String, Integer>();
        var ranked = new ArrayList<Map.Entry<String, Integer>>();
        for (String view : schema.views().keySet()) {
            ranked.add(Map.entry(view, subtree(schema, view, memo, new LinkedHashSet<>())));
        }
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .reversed().thenComparing(Map.Entry::getKey));

        System.out.println("Inline multiplicity: relation instantiations per read, "
            + schema.views().size() + " views over " + schema.tables().size() + " tables.");
        System.out.println("A view names its children textually and H2 inlines each naming, so a "
            + "deep tree compounds.");
        for (var entry : ranked.subList(0, Math.min(TOP, ranked.size()))) {
            System.out.printf("  %6d  %s%n", entry.getValue(), entry.getKey());
        }
        var heaviest = ranked.getFirst();
        System.out.println("Heaviest: " + heaviest.getKey() + " at " + heaviest.getValue()
            + ". Its direct children, by contribution:");
        var children = references(schema, heaviest.getKey(), schema.views().get(heaviest.getKey()));
        children.entrySet().stream()
            .filter(e -> schema.views().containsKey(e.getKey()))
            .map(e -> Map.entry(e.getKey(),
                e.getValue() * subtree(schema, e.getKey(), memo, new LinkedHashSet<>())))
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed().thenComparing(Map.Entry::getKey))
            .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
    }
}
