package no.sikt.graphitron.model;

import no.sikt.graphitron.model.lint.LintRule;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.LINT_RULE;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule catalogue against the enum it states, in both directions.
 *
 * <p>Two vocabularies for one set of rules is the risk a stated relation runs, and it is the only
 * one worth a gate here: the rows say what the schema's constraints may reference and the enum says
 * what the code may mint, so a rule present in one and missing from the other is a rule that either
 * cannot be recorded or cannot be produced. Neither failure is loud on its own. A missing row
 * surfaces as a foreign key violation on whichever consumer's schema first trips the rule, which is
 * to say in the field rather than here.
 *
 * <p>Severity is asserted uniform rather than per rule, which is the honest shape of what is known:
 * every rule is a warning, the enum carries no severity of its own, and the column exists so that
 * changing one is a row rather than a schema change. A per-rule expectation here would be this test
 * restating the INSERT.
 */
class LintRuleCatalogueTest {

    @Test
    @DisplayName("every declared rule is stated, and every stated rule is declared")
    void theCatalogueAndTheEnumAgree() {
        withStore(dsl -> {
            var stated = statedRules(dsl);
            var declared = Arrays.stream(LintRule.values())
                .collect(Collectors.toMap(LintRule::id, rule -> rule.source().name(),
                    (a, b) -> a, LinkedHashMap::new));

            assertThat(stated.keySet())
                .as("rule ids the enum declares and the catalogue does not state, or the reverse")
                .containsExactlyInAnyOrderElementsOf(declared.keySet());

            assertThat(stated)
                .as("rules whose stated source disagrees with the enum's")
                .containsExactlyInAnyOrderEntriesOf(declared);
        });
    }

    @Test
    @DisplayName("every rule is a warning, which is what the enum says and what the column holds")
    void severityIsUniformToday() {
        withStore(dsl -> assertThat(dsl.select(LINT_RULE.SEVERITY).from(LINT_RULE)
                .fetchSet(LINT_RULE.SEVERITY))
            .as("the severities the catalogue holds")
            .containsExactly("WARNING"));
    }

    /** Each stated rule against the producer the row names. */
    private static Map<String, String> statedRules(DSLContext dsl) {
        var stated = new LinkedHashMap<String, String>();
        dsl.select(LINT_RULE.RULE_ID, LINT_RULE.SOURCE)
            .from(LINT_RULE)
            .orderBy(LINT_RULE.RULE_ID)
            .fetch()
            .forEach(row -> stated.put(row.value1(), row.value2()));
        return stated;
    }

    /**
     * Through the shared funnel, on a store no case here seeds and none captures into: these rows
     * are the file's, so they are present the moment the schema executes and they survive the clear
     * between cases, which is the property that lets this read them off a store somebody else
     * booted.
     */
    private static void withStore(java.util.function.Consumer<DSLContext> body) {
        withSeededStore(dsl -> body.accept(dsl));
    }
}
