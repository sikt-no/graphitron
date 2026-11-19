package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactStores;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How hard every view in the fact schema is for H2 to plan, measured from the DDL alone and written
 * to {@code target/view-complexity/} on every build: {@code views.tsv}, one row per view, and
 * {@code arms.tsv}, one row per top-level {@code UNION} arm of each.
 *
 * <p>The measures follow what H2's planner does with a statement. It plans each query block, the
 * top-level select of an arm and every subquery inside it, on its own; within a block it searches
 * every join order of up to seven inputs and samples beyond that, and it re-plans a view a block
 * reads for each order it tries. So a block's {@code inputs} (the tables, views and derived tables
 * in its {@code FROM}) is the number that grows its cost, and {@code joinSearch} weighs each block
 * by the orders a search of that many inputs considers ({@code n!} up to seven, a flat increment per
 * input past it). It is a ranking weight, not a time: measured against the first execution of the
 * entry-defect rules in a fresh store, it picked the three that cost most and misranked one that
 * cost little. A view a block reads is counted in {@code viewRefs}, since its planning repeats per
 * order.
 *
 * <p>The views are read with jOOQ's parser. A view whose body is a {@code VALUES} list is named as
 * such, having no plan to weigh. An arm the parser refuses is named with its reason rather than
 * skipped, so no view leaves the report without saying why. jOOQ's query model does not expose a
 * select's {@code UNION} arms, so a view's text is split at its top-level {@code UNION}s and each arm
 * parsed alone.
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
               long joinSearch, int viewRefs, String reason, String head) {}

    private static List<String> views;
    private static List<Arm> arms;

    @BeforeAll
    static void measure() throws IOException {
        var bodies = new java.util.LinkedHashMap<String, String>();
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
            int index = 0;
            for (var arm : topLevelArms(body)) {
                arms.add(measure(view, index++, arm, names));
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
            return new Arm(view, index, "values", 0, 0, 0, 0, 0, "", head);
        }
        Select<?> select;
        try {
            select = PARSER.parser().parseSelect(text);
        } catch (RuntimeException e) {
            var reason = String.valueOf(e.getMessage()).lines().findFirst().orElse("").replace('\t', ' ');
            return new Arm(view, index, "unparsed", 0, 0, 0, 0, 0, reason, head);
        }
        var tally = new Tally(views);
        tally.walk(select);
        return new Arm(view, index, "query", tally.blocks, tally.maxInputs, tally.wideBlocks,
            tally.joinSearch, tally.viewRefs, "", head);
    }

    /** The query blocks under one arm, and the inputs each joins. */
    private static final class Tally {
        private final Set<String> views;
        int blocks;
        int maxInputs;
        int wideBlocks;
        long joinSearch;
        int viewRefs;

        Tally(Set<String> views) {
            this.views = views;
        }

        void walk(Object part) {
            switch (part) {
                case null -> { }
                case Select<?> select -> {
                    var inputs = new ArrayList<Table<?>>();
                    select.$from().forEach(table -> flatten(table, inputs));
                    if (!inputs.isEmpty()) {
                        block(inputs);
                    }
                    walk(select.$where());
                    walk(select.$having());
                    select.$select().forEach(this::walk);
                    if (select.$with() != null) {
                        select.$with().$commonTableExpressions().forEach(cte -> walk(cte.$query()));
                    }
                }
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

        private void block(List<Table<?>> inputs) {
            int n = inputs.size();
            blocks++;
            maxInputs = Math.max(maxInputs, n);
            if (n >= 7) {
                wideBlocks++;
            }
            joinSearch += n <= 7 ? factorial(n) : factorial(7) + 2000L * (n - 7);
            for (var input : inputs) {
                var base = input instanceof QOM.TableAlias<?> alias ? alias.$table() : input;
                if (base != null && views.contains(base.getName().toLowerCase(Locale.ROOT))) {
                    viewRefs++;
                }
            }
        }

        private void flatten(Table<?> table, List<Table<?>> inputs) {
            switch (table) {
                case QOM.JoinTable<?, ?> join -> {
                    flatten(join.$table1(), inputs);
                    flatten(join.$table2(), inputs);
                    if (join instanceof QOM.Join<?> on) {
                        walk(on.$on());
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

    private static boolean boundary(String text, int at) {
        return at < 0 || at >= text.length()
            || !(Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_');
    }

    private static void write() throws IOException {
        Files.createDirectories(REPORT);
        var armRows = new ArrayList<String>();
        armRows.add("view\tarm\tkind\tblocks\tmaxInputs\twideBlocks\tjoinSearch\tviewRefs\treason\thead");
        arms.forEach(arm -> armRows.add(String.join("\t", arm.view(), String.valueOf(arm.index()), arm.kind(),
            String.valueOf(arm.blocks()), String.valueOf(arm.maxInputs()), String.valueOf(arm.wideBlocks()),
            String.valueOf(arm.joinSearch()), String.valueOf(arm.viewRefs()), arm.reason(), arm.head())));
        Files.write(REPORT.resolve("arms.tsv"), armRows);

        record ViewRow(String view, int arms, int unparsed, boolean values, int blocks, int maxInputs,
                       int wideBlocks, long joinSearch, int viewRefs) {}
        var rows = new ArrayList<ViewRow>();
        for (var view : views) {
            var own = arms.stream().filter(arm -> arm.view().equals(view)).toList();
            rows.add(new ViewRow(view, own.size(),
                (int) own.stream().filter(arm -> arm.kind().equals("unparsed")).count(),
                own.stream().allMatch(arm -> arm.kind().equals("values")),
                own.stream().mapToInt(Arm::blocks).sum(),
                own.stream().mapToInt(Arm::maxInputs).max().orElse(0),
                own.stream().mapToInt(Arm::wideBlocks).sum(),
                own.stream().mapToLong(Arm::joinSearch).sum(),
                own.stream().mapToInt(Arm::viewRefs).sum()));
        }
        rows.sort(Comparator.comparingLong(ViewRow::joinSearch).reversed().thenComparing(ViewRow::view));
        var viewRows = new ArrayList<String>();
        viewRows.add("view\tarms\tunparsedArms\tvalues\tblocks\tmaxInputs\twideBlocks\tjoinSearch\tviewRefs");
        rows.forEach(row -> viewRows.add(String.join("\t", row.view(), String.valueOf(row.arms()),
            String.valueOf(row.unparsed()), String.valueOf(row.values()), String.valueOf(row.blocks()),
            String.valueOf(row.maxInputs()), String.valueOf(row.wideBlocks()), String.valueOf(row.joinSearch()),
            String.valueOf(row.viewRefs()))));
        Files.write(REPORT.resolve("views.tsv"), viewRows);
    }
}
