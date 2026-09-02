package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_SLOT;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedListArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_slot} returns: which GraphQL slots a condition method's parameters
 * may bind at one application of the directive.
 *
 * <p>Three spellings admit three different sets, which is the whole of the rule: a field condition
 * sees every argument of its field, an argument condition sees the one it sits on, an input-field
 * condition sees the input field itself. A path-step condition sees nothing, and so does every
 * non-condition site the method reference also carries.
 */
class ConditionSlotTest {

    // ===== The three spellings =====

    /** A field condition binds against the arguments of its own field, all of them. */
    @Test
    void aFieldConditionSeesEveryArgumentOfItsField() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");
            seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle", null);

            assertThat(slots(dsl)).containsExactly("FIELD_CONDITION Query.films rating ARGUMENT",
                "FIELD_CONDITION Query.films title ARGUMENT");
        });
    }

    /** A field declaring no arguments puts nothing in scope, the directive notwithstanding. */
    @Test
    void aFieldWithNoArgumentsPutsNothingInScope() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle", null);

            assertThat(slots(dsl)).isEmpty();
        });
    }

    /** An argument condition sees the argument it sits on and not its siblings. */
    @Test
    void anArgumentConditionSeesOnlyItsOwnArgument() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "rating", CONDITIONS, "byRating", null);

            assertThat(slots(dsl))
                .containsExactly("ARGUMENT_CONDITION Query.films(rating) rating ARGUMENT");
        });
    }

    /** An input-field condition sees the input field itself, which is the slot it binds. */
    @Test
    void anInputFieldConditionSeesTheInputFieldItself() {
        withGraph(dsl -> {
            seedInputField(dsl, GRAPH, "FilmInput", "title", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "FilmInput", "rating", "String", 1, false, false, null);
            seedFieldCondition(dsl, GRAPH, "FilmInput", "title", CONDITIONS, "byTitle", null);

            assertThat(slots(dsl))
                .containsExactly("INPUT_FIELD_CONDITION FilmInput.title title INPUT_FIELD");
        });
    }

    // ===== The sites that admit nothing =====

    /**
     * A path-step condition binds nothing: its method is called with no GraphQL value in scope, so
     * the site draws no row at all rather than an empty-looking one.
     */
    @Test
    void aPathStepConditionPutsNothingInScope() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedFieldReference(dsl, GRAPH, "Query", "films", 0);
            seedFieldReferenceCall(dsl, GRAPH, "Query", "films", 0, 0, CONDITIONS, "byTitle");

            assertThat(slots(dsl)).isEmpty();
        });
    }

    /** A site that names a method without being a condition contributes nothing either. */
    @Test
    void aServiceSiteIsNotACondition() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedService(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle");

            assertThat(slots(dsl)).isEmpty();
        });
    }

    // ===== Grain and payload =====

    /**
     * Two applications on one field are two sets, kept apart by the site and the use site: the
     * field's condition sees both arguments, the argument's condition sees one.
     */
    @Test
    void twoApplicationsOnOneFieldAnswerSeparately() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");
            seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle", null);
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "rating", CONDITIONS, "byRating", null);

            assertThat(slots(dsl)).containsExactly(
                "ARGUMENT_CONDITION Query.films(rating) rating ARGUMENT",
                "FIELD_CONDITION Query.films rating ARGUMENT",
                "FIELD_CONDITION Query.films title ARGUMENT");
        });
    }

    /** The slot's declared type rides along, which is what the pairing inference reads it for. */
    @Test
    void theSlotCarriesItsDeclaredType() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedListArgument(dsl, GRAPH, "Query", "films", "ratings", "Rating");
            seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle", null);

            assertThat(types(dsl)).containsExactly("ratings Rating list", "title String single");
        });
    }

    /** Another graph's application is another graph's, the partition being the leading key. */
    @Test
    void anotherGraphSeesNothing() {
        withGraph(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, "byTitle", null);

            assertThat(slotsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String CONDITIONS = "com.example.Conditions";

    private static void withGraph(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, body);
    }

    /** One row rendered as its key plus the kind: the site, the application, the slot. */
    private static List<String> slots(DSLContext dsl) {
        return slotsIn(dsl, GRAPH);
    }

    private static List<String> slotsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        var t = INTENT_CONDITION_SLOT;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .orderBy(t.SITE, t.USE_SITE, t.SLOT_NAME)
            .fetch(row -> row.get(t.SITE) + " " + row.get(t.USE_SITE) + " "
                + row.get(t.SLOT_NAME) + " " + row.get(t.SLOT_KIND));
    }

    /** The payload projection: the slot's name against the type it carries. */
    private static List<String> types(DSLContext dsl) {
        derive(dsl);
        var t = INTENT_CONDITION_SLOT;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.SLOT_NAME)
            .fetch(row -> row.get(t.SLOT_NAME) + " " + row.get(t.NAMED_TYPE) + " "
                + (Boolean.TRUE.equals(row.get(t.IS_LIST)) ? "list" : "single"));
    }
}
