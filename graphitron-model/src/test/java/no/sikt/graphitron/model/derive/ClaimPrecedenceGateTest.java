package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * A claim relation's rules are its precedence relation's rows: each rule a precedence relation
 * declares has the rule view stating its claims, and each such rule view a rule declared, so a
 * writer reading its rules from the relation reaches every view and a view no writer reads cannot
 * be added.
 */
class ClaimPrecedenceGateTest {

    @Test
    @DisplayName("each occurrence source rule is declared once and has its view")
    void sourceRules() {
        ThreadConfinedStore.run(dsl -> assertThat(views(dsl, "graphitron_occurrence_source_claim_"))
            .containsExactlyInAnyOrderElementsOf(declared(dsl, "graphitron_occurrence_source_precedence",
                "graphitron_occurrence_source_claim_")));
    }

    @Test
    @DisplayName("each path need rule is declared once and has its view")
    void needRules() {
        ThreadConfinedStore.run(dsl -> assertThat(views(dsl, "graphitron_path_need_claim_"))
            .containsExactlyInAnyOrderElementsOf(declared(dsl, "graphitron_path_need_precedence",
                "graphitron_path_need_claim_")));
    }

    /** The rule views stating claims, by the prefix every one of them carries. */
    private static List<String> views(DSLContext dsl, String prefix) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).like(prefix.toUpperCase(Locale.ROOT) + "%\\_RULE"))
            .fetch(0, String.class);
    }

    /** The view each declared rule's claims are stated by: the prefix, the rule in snake case, _rule. */
    private static List<String> declared(DSLContext dsl, String precedence, String prefix) {
        return dsl.select(field(name("RULE"), String.class)).from(table(name(precedence.toUpperCase(Locale.ROOT))))
            .fetch(0, String.class).stream()
            .map(rule -> (prefix + rule.replaceAll("([A-Z])", "_$1") + "_rule").toUpperCase(Locale.ROOT))
            .toList();
    }
}
