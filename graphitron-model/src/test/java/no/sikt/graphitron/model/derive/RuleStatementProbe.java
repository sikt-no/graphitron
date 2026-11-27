package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a stage's rule runs as well from its text as from its view: one fact document captured, the
 * outbox stage's defect rows read, then deleted, the rule's view dropped and the stage's write run
 * again over the rule's DDL text as a derived table, and the rows compared. A probe for moving rule
 * views out of the store's schema, inert unless {@value #PROPERTY} is set.
 */
class RuleStatementProbe {

    static final String PROPERTY = "rule.statement.probe";

    private static final String DOCUMENT = "defects/outbox-payload-defects";
    private static final String VIEW = "graphitron_outbox_entry_defect_rule";

    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = "true")
    void aRuleRunsFromItsText() throws Exception {
        var stated = ruleText(VIEW);
        var text = stated[1];
        var names = stated[0] == null ? new String[0] : stated[0].replaceAll("\\s+", "").split(",");
        var tmp = Files.createTempDirectory("rule-statement-probe");
        try (var store = CapturedStore.ownStoreOfDocument(tmp, DOCUMENT, "g",
                CatalogInput.of(TestRunContext.of()), ClasspathCorpus.entries())) {
            var dsl = store.dsl();
            var d = GRAPHITRON_ENTRY_DEFECT;
            var stage = EntryDefects.OUTBOX.stage();
            var fromView = rows(dsl, stage);
            assertThat(fromView).as("the document raises outbox defects for the comparison to mean anything")
                .isNotEmpty();

            dsl.deleteFrom(d).where(d.GRAPH_NAME.eq("g")).and(d.STAGE.eq(stage)).execute();
            dsl.execute("DROP VIEW " + VIEW + " RESTRICT");
            // The view's own column list names the columns; the body's names need not match it.
            var asText = DSL.table("(" + text + ")").as(VIEW.toUpperCase(Locale.ROOT),
                java.util.Arrays.stream(names).map(n -> n.toUpperCase(Locale.ROOT)).toArray(String[]::new));
            EntryDefects.derive(dsl, "g", LocalDateTime.now(), new EntryDefects.Rule(stage, asText));
            var fromText = rows(dsl, stage);

            // Typed: the rule's text coerced to the table it writes, fetched as its records, and written.
            dsl.deleteFrom(d).where(d.GRAPH_NAME.eq("g")).and(d.STAGE.eq(stage)).execute();
            String typed;
            try {
                // coerce lines columns up by position, so the projection names the table's columns in
                // the table's order, and the mark, which no rule states, is bound.
                var now = LocalDateTime.now();
                var projection = java.util.Arrays.stream(d.fields())
                    .map(f -> f.equals(d.TOUCHED_AT) ? "CAST(? AS TIMESTAMP) AS touched_at" : "r." + f.getName())
                    .collect(java.util.stream.Collectors.joining(", "));
                var records = dsl.resultQuery("SELECT " + projection + " FROM (" + text + ") r"
                        + (names.length == 0 ? "" : "(" + String.join(", ", names) + ")") + " WHERE r.graph_name = ?",
                        now, "g")
                    .coerce(d).fetch();
                // A fetched record is not marked touched, and an insert writes only touched fields.
                records.forEach(r -> r.touched(true));
                dsl.batchInsert(records).execute();
                var fromCoerced = rows(dsl, stage);
                typed = "coerced " + records.size() + " records, equal " + fromView.equals(fromCoerced)
                    + (fromView.equals(fromCoerced) ? "" : "\n  coerced rows:\n  " + String.join("\n  ", fromCoerced));
            } catch (RuntimeException e) {
                typed = "coerce failed: " + e;
            }

            System.out.println("RULE-STATEMENT " + VIEW + ": from the view " + fromView.size() + " rows, from its text "
                + fromText.size() + ", equal " + fromView.equals(fromText) + "; " + typed + "\n"
                + String.join("\n", fromView));
            assertThat(fromText).as("the stage's rows from the rule's text").isEqualTo(fromView);
        }
    }

    /**
     * Every registered rule's columns against the relation it writes, as a gate that a rule matches
     * its table would read them: the same names in the same order with the same types, the same
     * names in another order, the table's names less its mark, or neither.
     */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = "true")
    void everyRuleAgainstItsTable() {
        try (var store = FactStores.inMemory()) {
            var dsl = store.dsl();
            var columns = new java.util.HashMap<String, List<String>>();
            dsl.fetch("SELECT LOWER(TABLE_NAME), LOWER(COLUMN_NAME), DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                    + " WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, ORDINAL_POSITION")
                .forEach(r -> columns.computeIfAbsent(r.get(0, String.class), t -> new java.util.ArrayList<>())
                    .add(r.get(1, String.class) + " " + r.get(2, String.class)));
            var verdicts = new java.util.TreeMap<String, java.util.List<String>>();
            dsl.fetch("SELECT DISTINCT LOWER(rule_view), LOWER(relation_name) FROM meta_stage_rule").forEach(r -> {
                var rule = columns.getOrDefault(r.get(0, String.class), List.of());
                var table = columns.getOrDefault(r.get(1, String.class), List.of());
                String verdict;
                if (rule.equals(table)) {
                    verdict = "exact";
                } else if (new java.util.HashSet<>(rule).equals(new java.util.HashSet<>(table))) {
                    verdict = "same columns, other order";
                } else {
                    var unmarked = table.stream().filter(c -> !c.startsWith("touched_at ")).toList();
                    if (rule.equals(unmarked)) {
                        verdict = "table less touched_at, same order";
                    } else if (new java.util.HashSet<>(rule).equals(new java.util.HashSet<>(unmarked))) {
                        verdict = "table less touched_at, other order";
                    } else {
                        var names = rule.stream().map(c -> c.split(" ")[0]).toList();
                        var tableNames = unmarked.stream().map(c -> c.split(" ")[0]).toList();
                        if (new java.util.HashSet<>(names).equals(new java.util.HashSet<>(tableNames))) {
                            verdict = "same names, other types";
                        } else {
                            var extra = new java.util.TreeSet<>(names);
                            extra.removeAll(tableNames);
                            var missing = new java.util.TreeSet<>(tableNames);
                            missing.removeAll(names);
                            verdict = "other columns";
                            System.out.println("RULE-DIFF " + r.get(0, String.class) + " -> " + r.get(1, String.class)
                                + " rule-only " + extra + " table-only " + missing);
                        }
                    }
                }
                verdicts.computeIfAbsent(verdict, v -> new java.util.ArrayList<>())
                    .add(r.get(0, String.class) + " -> " + r.get(1, String.class));
            });
            verdicts.forEach((verdict, rules) -> System.out.println("RULE-COLUMNS " + verdict + ": " + rules.size()
                + (verdict.startsWith("exact") ? "" : "\n  " + String.join("\n  ", rules.subList(0, Math.min(12, rules.size()))))));
        }
    }

    private static List<String> rows(org.jooq.DSLContext dsl, String stage) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        return dsl.select(d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE, d.DETAIL)
            .from(d).where(d.GRAPH_NAME.eq("g")).and(d.STAGE.eq(stage))
            .orderBy(d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE)
            .fetch(r -> r.formatCSV(false).strip());
    }

    /** The view's column list, or null, and its body, as the DDL states them: what a named SQL file would hold. */
    private static String[] ruleText(String view) {
        var ddl = FactStores.schemaText().replaceAll("(?m)--[^\\n]*$", "");
        var m = Pattern.compile("(?is)CREATE\\s+VIEW\\s+" + view + "\\s*(?:\\(([^)]*)\\))?\\s+AS\\s+(.*?);\\s*\\n")
            .matcher(ddl);
        assertThat(m.find()).as("the DDL creates " + view).isTrue();
        return new String[] {m.group(1), m.group(2)};
    }
}
