package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactStores;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.conf.ParseUnknownFunctions;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How hard every view in the fact schema is for H2 to plan, measured from the DDL alone and written
 * to {@code target/view-complexity/} on every build: {@code views.tsv}, one row per view, and
 * {@code arms.tsv}, one row per top-level {@code UNION} arm of each.
 *
 * <p>The measures follow what H2's planner does with a statement. It plans each query block, the
 * top-level select of an arm and every subquery inside it, on its own. Within a block it searches
 * join orders over the block's inputs (the tables, views, CTEs and derived tables in its
 * {@code FROM}), every order up to seven inputs and a bounded search beyond, and for every order it
 * tries it re-checks which of the block's conditions can be evaluated at each step. So a block's
 * {@code planWeight} is the orders searched ({@code inputs!} up to seven, about 2,500 past it, which is
 * fewer than seven's 5,040), times one plus its conditions
 * (the leaf predicates of its {@code WHERE} and its joins' {@code ON}s); a view or CTE an input names
 * adds its own weight once more per reference, since H2 inlines it there. {@code guards} counts the
 * conditions that are subqueries ({@code EXISTS}, {@code IN} over a select, a scalar subquery), which
 * cost the most to re-check. The weight ranks, it does not time: measured against the cold first
 * execution of every top-level arm of the three costliest entry-defect rules, and of those rules
 * whole, it is a ranking with misses.
 *
 * <p>The views are read with jOOQ's parser. A view whose body is a {@code VALUES} list is named as
 * such, having no plan to weigh. An arm the parser refuses is named with its reason rather than
 * skipped, so no view leaves the report without saying why. The open-source jOOQ query model does not
 * expose a select's later {@code UNION} arms, so a view's text is split at its top-level
 * {@code UNION}s, a leading {@code WITH} carried onto every arm, and each arm parsed alone; if that
 * split ever mis-reads a view, the arms it produces fail to parse and are named here.
 *
 * <p>The report is for reading; a gate over it is a separate test.
 */
class ViewComplexityTest {

    private static final Path REPORT = Path.of("target", "view-complexity");

    private static final Pattern CREATE_VIEW = Pattern.compile(
        "(?is)^CREATE\\s+VIEW\\s+(\\w+)\\s*(?:\\([^)]*\\))?\\s+AS\\s+(.*)$");

    private static final DSLContext PARSER = DSL.using(SQLDialect.H2, new Settings()
        .withParseDialect(SQLDialect.H2)
        .withParseUnknownFunctions(ParseUnknownFunctions.IGNORE));

    /** One arm's measures, or why it has none. */
    record Arm(String view, int index, String kind, int blocks, int maxInputs, int wideBlocks,
               int conditions, int guards, long planWeight, int viewRefs, String reason, String head) {}

    private static List<String> views;
    private static List<Arm> arms;

    @BeforeAll
    static void measure() throws IOException {
        var bodies = new LinkedHashMap<String, String>();
        for (var statement : statements(FactStores.schemaText())) {
            var view = CREATE_VIEW.matcher(statement);
            if (view.matches()) {
                bodies.put(view.group(1).toLowerCase(Locale.ROOT), view.group(2));
            }
        }
        views = List.copyOf(bodies.keySet());
        var names = Set.copyOf(views);
        arms = new ArrayList<>();
        bodies.forEach((view, body) -> {
            var parts = topLevelArms(body);
            var with = withPrefix(parts.getFirst());
            for (int index = 0; index < parts.size(); index++) {
                var arm = measure(view, index, index == 0 ? parts.get(index) : with + parts.get(index), names);
                if (arm.kind().equals("unparsed") && index > 0 && !with.isEmpty()) {
                    // An arm that will not parse beside the shared WITH is measured alone.
                    var alone = measure(view, index, parts.get(index), names);
                    arm = alone.kind().equals("unparsed") ? arm : alone;
                }
                arms.add(arm);
            }
        });
        write();
    }

    @Test
    @DisplayName("every view in the fact schema is in the report, measured or named with the reason it is not")
    void everyViewIsReported() throws IOException {
        assertThat(views).as("views the DDL creates").isNotEmpty();
        var reported = new LinkedHashSet<String>();
        arms.forEach(arm -> reported.add(arm.view()));
        assertThat(reported).as("views with at least one arm in the report").containsExactlyElementsOf(views);
        assertThat(arms).as("an arm the parser refused carries its reason")
            .filteredOn(arm -> arm.kind().equals("unparsed"))
            .allSatisfy(arm -> assertThat(arm.reason()).isNotBlank());
        assertThat(Files.readAllLines(REPORT.resolve("views.tsv"))).as("the view report's rows")
            .hasSize(views.size() + 1);
    }

    private static Arm measure(String view, int index, String text, Set<String> views) {
        var head = text.strip().replaceAll("\\s+", " ");
        head = head.substring(0, Math.min(100, head.length()));
        if (text.strip().toUpperCase(Locale.ROOT).startsWith("VALUES")) {
            return new Arm(view, index, "values", 0, 0, 0, 0, 0, 0, 0, "", head);
        }
        Select<?> select;
        try {
            select = PARSER.parser().parseSelect(text);
        } catch (RuntimeException e) {
            var reason = String.valueOf(e.getMessage()).lines().findFirst().orElse("").replace('\t', ' ');
            return new Arm(view, index, "unparsed", 0, 0, 0, 0, 0, 0, 0, reason, head);
        }
        var tally = new Tally(views, Map.of());
        tally.walk(select);
        return new Arm(view, index, "query", tally.blocks, tally.maxInputs, tally.wideBlocks,
            tally.conditions, tally.guards, tally.weight, tally.viewRefs, "", head);
    }

    /** The query blocks under one select, and what planning each costs. */
    private static final class Tally {
        private final Set<String> views;
        /** The weight of each CTE in scope, added once per reference. */
        private final Map<String, Long> ctes;
        int blocks;
        int maxInputs;
        int wideBlocks;
        int conditions;
        int guards;
        long weight;
        int viewRefs;

        Tally(Set<String> views, Map<String, Long> ctes) {
            this.views = views;
            this.ctes = ctes;
        }

        void walk(Object part) {
            switch (part) {
                case null -> { }
                case Select<?> select -> select(select);
                case QOM.And and -> { walk(and.$arg1()); walk(and.$arg2()); }
                case QOM.Or or -> { walk(or.$arg1()); walk(or.$arg2()); }
                case QOM.Not not -> walk(not.$arg1());
                case QOM.Exists exists -> walk(exists.$query());
                case QOM.In<?> in -> walk(in.$arg2());
                case QOM.ScalarSubquery<?> subquery -> walk(subquery.$arg1());
                case QOM.FieldAlias<?> alias -> walk(alias.$field());
                case Collection<?> parts -> parts.forEach(this::walk);
                default -> { }
            }
        }

        private void select(Select<?> select) {
            var scope = ctes;
            if (select.$with() != null) {
                // Each CTE weighed on its own, then charged wherever it is named.
                var own = new HashMap<>(ctes);
                for (var cte : select.$with().$commonTableExpressions()) {
                    var inner = new Tally(views, own);
                    inner.walk(cte.$query());
                    own.put(cte.$derivedColumnList().$tableName().last().toLowerCase(Locale.ROOT), inner.weight);
                }
                scope = own;
            }
            var block = new Tally(views, scope);
            var inputs = new ArrayList<Table<?>>();
            var predicates = new ArrayList<Condition>();
            select.$from().forEach(table -> block.flatten(table, inputs, predicates));
            if (select.$where() != null) {
                predicates.add(select.$where());
            }
            int leaves = 0;
            int subqueries = 0;
            for (var predicate : predicates) {
                leaves += leaves(predicate);
                subqueries += subqueryLeaves(predicate);
            }
            if (!inputs.isEmpty()) {
                int n = inputs.size();
                blocks++;
                maxInputs = Math.max(maxInputs, n);
                if (n >= 7) {
                    wideBlocks++;
                }
                conditions += leaves;
                guards += subqueries;
                weight += orders(n) * (1L + leaves);
                for (var input : inputs) {
                    var base = input instanceof QOM.TableAlias<?> alias ? alias.$table() : input;
                    var name = base == null ? "" : base.getName().toLowerCase(Locale.ROOT);
                    if (scope.containsKey(name)) {
                        viewRefs++;
                        weight += scope.get(name);
                    } else if (views.contains(name)) {
                        viewRefs++;
                    }
                }
            }
            // Subqueries are blocks of their own, planned on their own.
            predicates.forEach(block::walk);
            block.walk(select.$having());
            select.$select().forEach(block::walk);
            absorb(block);
        }

        private void absorb(Tally other) {
            blocks += other.blocks;
            maxInputs = Math.max(maxInputs, other.maxInputs);
            wideBlocks += other.wideBlocks;
            conditions += other.conditions;
            guards += other.guards;
            weight += other.weight;
            viewRefs += other.viewRefs;
        }

        private void flatten(Table<?> table, List<Table<?>> inputs, List<Condition> predicates) {
            switch (table) {
                case QOM.JoinTable<?, ?> join -> {
                    flatten(join.$table1(), inputs, predicates);
                    flatten(join.$table2(), inputs, predicates);
                    if (join instanceof QOM.Join<?> on && on.$on() != null) {
                        predicates.add(on.$on());
                    }
                }
                case QOM.DerivedTable<?> derived -> {
                    inputs.add(table);
                    walk(derived.$arg1());
                }
                case QOM.TableAlias<?> alias when alias.$table() instanceof QOM.DerivedTable<?> derived -> {
                    inputs.add(table);
                    walk(derived.$arg1());
                }
                default -> inputs.add(table);
            }
        }

        /** The leaf predicates of a condition, each one H2 re-checks per join order. */
        private static int leaves(Condition condition) {
            return switch (condition) {
                case QOM.And and -> leaves(and.$arg1()) + leaves(and.$arg2());
                case QOM.Or or -> leaves(or.$arg1()) + leaves(or.$arg2());
                case QOM.Not not -> leaves(not.$arg1());
                default -> 1;
            };
        }

        /** The leaf predicates of a condition that are subqueries. */
        private static int subqueryLeaves(Condition condition) {
            return switch (condition) {
                case QOM.And and -> subqueryLeaves(and.$arg1()) + subqueryLeaves(and.$arg2());
                case QOM.Or or -> subqueryLeaves(or.$arg1()) + subqueryLeaves(or.$arg2());
                case QOM.Not not -> subqueryLeaves(not.$arg1());
                case QOM.Exists exists -> 1;
                case QOM.In<?> in when in.$arg2() instanceof Select<?> -> 1;
                default -> 0;
            };
        }

        /**
         * The join orders H2 2.5's optimizer tries for a block of {@code n} inputs: every one up to
         * {@code MAX_BRUTE_FORCE_FILTERS} (7), and past that a bounded search of at most
         * {@code MAX_BRUTE_FORCE} (2,000) partial orders and {@code MAX_GENETIC} (500) genetic rounds,
         * so a block of eight inputs costs less to plan than one of seven.
         */
        private static long orders(int n) {
            return n <= 7 ? factorial(n) : 2_500;
        }

        private static long factorial(int n) {
            long f = 1;
            for (int i = 2; i <= n; i++) {
                f *= i;
            }
            return f;
        }
    }

    /**
     * The DDL's statements, comments removed first: a comment inside a view's body may end a line
     * with a semicolon, which would otherwise cut the view in two.
     */
    private static List<String> statements(String ddl) {
        var statements = new ArrayList<String>();
        for (var part : ddl.replaceAll("(?m)--[^\\n]*$", "").split(";\\s*\\n")) {
            var statement = part.strip();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /** A view body's arms at its top-level {@code UNION} and {@code UNION ALL}. */
    static List<String> topLevelArms(String body) {
        var arms = new ArrayList<String>();
        var upper = body.toUpperCase(Locale.ROOT);
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("UNION", i) && boundary(body, i - 1)
                && boundary(body, i + 5)) {
                arms.add(body.substring(start, i));
                int next = i + 5;
                while (next < body.length() && Character.isWhitespace(body.charAt(next))) {
                    next++;
                }
                if (upper.startsWith("ALL", next) && boundary(body, next + 3)) {
                    next += 3;
                } else if (upper.startsWith("DISTINCT", next) && boundary(body, next + 8)) {
                    next += 8;
                }
                start = next;
                i = next - 1;
            }
        }
        arms.add(body.substring(start));
        return arms;
    }

    /** The leading {@code WITH ...} of a first arm, up to its top-level {@code SELECT}; empty if none. */
    static String withPrefix(String arm) {
        var upper = arm.toUpperCase(Locale.ROOT);
        int with = upper.indexOf("WITH");
        if (!upper.strip().startsWith("WITH")) {
            return "";
        }
        int depth = 0;
        boolean quoted = false;
        for (int i = with + 4; i < arm.length(); i++) {
            char c = arm.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("SELECT", i) && boundary(arm, i - 1)) {
                return arm.substring(0, i);
            }
        }
        return "";
    }

    private static boolean boundary(String text, int at) {
        return at < 0 || at >= text.length()
            || !(Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_');
    }

    private static void write() throws IOException {
        Files.createDirectories(REPORT);
        var armRows = new ArrayList<String>();
        armRows.add("view\tarm\tkind\tblocks\tmaxInputs\twideBlocks\tconditions\tguards\tplanWeight\tviewRefs\treason\thead");
        arms.forEach(arm -> armRows.add(String.join("\t", arm.view(), String.valueOf(arm.index()), arm.kind(),
            String.valueOf(arm.blocks()), String.valueOf(arm.maxInputs()), String.valueOf(arm.wideBlocks()),
            String.valueOf(arm.conditions()), String.valueOf(arm.guards()), String.valueOf(arm.planWeight()),
            String.valueOf(arm.viewRefs()), arm.reason(), arm.head())));
        Files.write(REPORT.resolve("arms.tsv"), armRows);

        record ViewRow(String view, int arms, int unparsed, boolean values, int blocks, int maxInputs,
                       int wideBlocks, int conditions, int guards, long planWeight, int viewRefs) {}
        var rows = new ArrayList<ViewRow>();
        for (var view : views) {
            var own = arms.stream().filter(arm -> arm.view().equals(view)).toList();
            rows.add(new ViewRow(view, own.size(),
                (int) own.stream().filter(arm -> arm.kind().equals("unparsed")).count(),
                own.stream().allMatch(arm -> arm.kind().equals("values")),
                own.stream().mapToInt(Arm::blocks).sum(),
                own.stream().mapToInt(Arm::maxInputs).max().orElse(0),
                own.stream().mapToInt(Arm::wideBlocks).sum(),
                own.stream().mapToInt(Arm::conditions).sum(),
                own.stream().mapToInt(Arm::guards).sum(),
                own.stream().mapToLong(Arm::planWeight).sum(),
                own.stream().mapToInt(Arm::viewRefs).sum()));
        }
        rows.sort(Comparator.comparingLong(ViewRow::planWeight).reversed().thenComparing(ViewRow::view));
        var viewRows = new ArrayList<String>();
        viewRows.add("view\tarms\tunparsedArms\tvalues\tblocks\tmaxInputs\twideBlocks\tconditions\tguards\tplanWeight\tviewRefs");
        rows.forEach(row -> viewRows.add(String.join("\t", row.view(), String.valueOf(row.arms()),
            String.valueOf(row.unparsed()), String.valueOf(row.values()), String.valueOf(row.blocks()),
            String.valueOf(row.maxInputs()), String.valueOf(row.wideBlocks()), String.valueOf(row.conditions()),
            String.valueOf(row.guards()), String.valueOf(row.planWeight()), String.valueOf(row.viewRefs()))));
        Files.write(REPORT.resolve("views.tsv"), viewRows);
    }
}
