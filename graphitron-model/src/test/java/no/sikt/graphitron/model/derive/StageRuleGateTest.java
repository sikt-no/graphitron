package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.META_STAGE_RULE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * {@code meta_stage_rule} states every stage's rule views: each row names a view the catalog has,
 * and each view named a rule has a row, so the gates reading the relation cover every stage that
 * writes from a rule. Read against the catalog's own list of views rather than the name alone, which
 * a relation could carry too.
 *
 * <p>No view reads a view, a rule view included (Alf, 2026-11-15). The rule views another view still
 * reads are listed here as debt, each to be stored or folded into its reader, and the list only
 * shrinks: a view it names that no view reads any more is a line to delete.
 */
class StageRuleGateTest {

    /** Rule views other views read, each with its readers: debt, each to be stored or folded in. */
    private static final Map<String, String> READ_BY_VIEWS = Map.of(
        "graphitron_occurrence_call_parameter_rule", "graphitron_occurrence_call_pin",
        "graphitron_occurrence_connection_rule", "graphitron_occurrence_route_entry_defect_rule");

    /** Views named a rule that no stage writes from, each with why: debt, each to be written or renamed. */
    private static final Map<String, String> NOT_WRITTEN = Map.of(
        "graphitron_entry_defect_parameter_rule",
        "the members of a parameter defect, read by fact documents alone; a detail relation by its"
            + " grain, which no stage stores yet");

    @Test
    @DisplayName("every meta_stage_rule row names a view the catalog has")
    void everyRowNamesAView() {
        ThreadConfinedStore.run(dsl -> {
            var views = views(dsl);
            var missing = new TreeSet<String>();
            dsl.selectFrom(META_STAGE_RULE).fetch().forEach(r -> {
                if (!views.contains(r.getRuleView())) {
                    missing.add(r.getStageName() + " " + r.getRelationName() + " " + r.getRuleView());
                }
            });
            assertThat(missing).as("rows naming no view: rename the row, or restore the view").isEmpty();
        });
    }

    @Test
    @DisplayName("every view named a rule has a meta_stage_rule row, or is listed as not written")
    void everyRuleViewHasARow() {
        ThreadConfinedStore.run(dsl -> {
            var stated = new TreeSet<>(dsl.selectDistinct(META_STAGE_RULE.RULE_VIEW).from(META_STAGE_RULE)
                .fetch(META_STAGE_RULE.RULE_VIEW));
            var unstated = new TreeSet<String>();
            for (var view : views(dsl)) {
                if (view.endsWith("_rule") && !stated.contains(view)) {
                    unstated.add(view);
                }
            }
            assertThat(unstated).as("rule views no meta_stage_rule row names, against the list of views no"
                    + " stage writes from; a new one wants its stage's row, and a listed one that gained a"
                    + " row is a line to delete")
                .isEqualTo(NOT_WRITTEN.keySet());
        });
    }

    @Test
    @DisplayName("the rule views other views read are exactly the listed debt")
    void ruleViewsReadByViewsAreTheListedDebt() {
        ThreadConfinedStore.run(dsl -> {
            var views = views(dsl);
            var readers = new TreeMap<String, Set<String>>();
            for (var reader : views) {
                String definition = definition(dsl, reader);
                for (var view : views) {
                    if (view.endsWith("_rule") && !view.equals(reader)
                            && Pattern.compile("\\b" + view.toUpperCase(Locale.ROOT) + "\\b").matcher(definition).find()) {
                        readers.computeIfAbsent(view, x -> new TreeSet<>()).add(reader);
                    }
                }
            }
            assertThat(readers.keySet()).as("rule views another view reads " + readers + ": no view reads a"
                    + " view, so a new one is refused, and a listed one no view reads any more is a line to"
                    + " delete")
                .isEqualTo(READ_BY_VIEWS.keySet());
        });
    }

    /** The catalog's views, lower case. */
    private static Set<String> views(DSLContext dsl) {
        var out = new TreeSet<String>();
        dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch(0, String.class)
            .forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String definition(DSLContext dsl, String view) {
        return dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(view.toUpperCase(Locale.ROOT)))
            .fetchSingle(0, String.class);
    }
}
