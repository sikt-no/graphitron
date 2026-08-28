package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_PARAM_DECODE;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedListArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_param_decode} returns: where a {@code @condition} parameter bound to
 * a slot is exempted from the declared-type extraction rule and receives the slot's decoded node key
 * instead, and what shape that key has.
 *
 * <p>The relation is an override, so half the cases are about absence. A {@code @nodeId} slot with
 * no {@code @condition} and a {@code @condition} on a slot carrying no {@code @nodeId} both have to
 * land nothing, because a row is the whole of what says the exemption applies and either half alone
 * would make the standing rule unreadable.
 *
 * <p>The other half is the shape. Arity and list-ness are independent facts arriving from different
 * places, the node type's key columns and the slot's own declaration, so a case that fixed both at
 * once could not tell a relation that carried one through from one that carried the other.
 */
class ConditionParamDecodeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String PUBLIC = "public";
    private static final String COND = "com.example.FilmConditions";

    // ===== The two sites =====

    /** An argument slot: the coordinate is the argument's own, and the shape is the node type's. */
    @Test
    void anArgumentCarryingBothDirectivesIsAnOverrideRowNamingItsMethod() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filmId", COND, "byFilm", true);

            derive(dsl);
            assertThat(rows(dsl)).containsExactly(
                "ARGUMENT Query.films(filmId) com.example.FilmConditions.byFilm Film 1 false");
        });
    }

    /**
     * An input-field slot. The directive is captured at the shared field coordinate rather than at a
     * three-part one, so the two sites reach different captured relations and the case is not the
     * argument one restated.
     */
    @Test
    void anInputFieldCarryingBothDirectivesIsAnOverrideRowAtItsUseSite() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "filmId", "ID", 0, false, false, null);
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "filmId", "Film");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "filmId", COND, "byFilm", true);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "filmId", "ID"));

            derive(dsl);
            assertThat(rows(dsl)).containsExactly(
                "INPUT_FIELD Query.films(filter)/filmId com.example.FilmConditions.byFilm Film 1 false");
        });
    }

    // ===== The two absences =====

    /**
     * A {@code @nodeId} slot with no {@code @condition} of its own lands nothing. Nothing is bound
     * into an authored method at that coordinate, so there is no parameter to exempt, and a row would
     * assert an exemption over a rule no one is applying.
     */
    @Test
    void aNodeIdSlotWithNoConditionLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");

            derive(dsl);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A {@code @condition} on a slot carrying no {@code @nodeId} lands nothing, which is the half
     * that keeps the standing rule readable: absence here has to mean the declared type decides, and
     * it would mean nothing at all if every condition were a row.
     */
    @Test
    void aConditionOnAPlainSlotLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", COND, "byTitle", false);

            derive(dsl);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A directive naming no method is not an exemption either. Capture records a reference the author
     * left incomplete, and there is no method for a decoded key to reach.
     */
    @Test
    void aConditionNamingNoMethodLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filmId", COND, null, true);

            derive(dsl);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The shape's two axes =====

    /**
     * A composite key raises the arity and nothing else. The parameter type a reader composes from
     * this row is the typed jOOQ {@code Row} of the key columns rather than one column's own type,
     * and the arity is the whole of what says so.
     */
    @Test
    void aCompositeKeyRaisesTheArityWithoutTouchingTheListAxis() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "project");
            seedColumn(dsl, PKG, PUBLIC, "project", "org_id", 0, "ORG_ID");
            seedColumn(dsl, PKG, PUBLIC, "project", "project_id", 1, "PROJECT_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "project", "project_pkey", "org_id", "project_id");
            seedTableBinding(dsl, GRAPH, "Project", "project");
            seedNode(dsl, GRAPH, "Project");
            seedField(dsl, GRAPH, "Query", "notes", "Note", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "notes", "projectId", "Project");
            seedArgumentCondition(dsl, GRAPH, "Query", "notes", "projectId", COND, "inProject", true);

            derive(dsl);
            assertThat(rows(dsl)).containsExactly(
                "ARGUMENT Query.notes(projectId) com.example.FilmConditions.inProject Project 2 false");
        });
    }

    /**
     * A list-shaped slot raises the list flag and nothing else. The two axes come from different
     * places, the slot's own declaration and the node type's key, so the pair is what names the
     * parameter type and neither alone does.
     */
    @Test
    void aListShapedSlotRaisesTheListFlagWithoutTouchingTheArity() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedListArgument(dsl, GRAPH, "Query", "films", "filmIds", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmIds", "Film");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filmIds", COND, "inFilms", true);

            derive(dsl);
            assertThat(rows(dsl)).containsExactly(
                "ARGUMENT Query.films(filmIds) com.example.FilmConditions.inFilms Film 1 true");
        });
    }

    // ===== The population's own edge =====

    /**
     * A node type no tier resolves key columns for has no row here, arity zero being a shape no
     * parameter can be declared as. The coordinate meets a shipped rejection naming the type, so the
     * absence is that relation's statement rather than a silence invented here.
     */
    @Test
    void aSlotWhoseNodeTypeResolvesNoKeyIsNotInThePopulation() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "ledger");
            seedColumn(dsl, PKG, PUBLIC, "ledger", "ledger_id", 0, "LEDGER_ID");
            seedTableBinding(dsl, GRAPH, "Ledger", "ledger");
            seedNode(dsl, GRAPH, "Ledger");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "onLedger", "Ledger");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "onLedger", COND, "onLedger", true);

            derive(dsl);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds. */
    @Test
    void aSiblingGraphLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film", "film_id");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "filmId", COND, "byFilm", true);

            derive(dsl);
            assertThat(dsl.selectFrom(INTENT_CONDITION_PARAM_DECODE)
                .where(INTENT_CONDITION_PARAM_DECODE.GRAPH_NAME.eq("other"))
                .fetch()).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            body.accept(dsl);
        });
    }

    /** A node type over a table with one key column, the shape most cases here only need to exist. */
    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef, String keyColumn) {
        seedTable(dsl, PKG, PUBLIC, tableRef);
        seedColumn(dsl, PKG, PUBLIC, tableRef, keyColumn, 0, keyColumn.toUpperCase());
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
        seedNodeKeyColumnRef(dsl, GRAPH, typeName, 0, keyColumn);
    }

    /** One rendered row per line, so a case reads as the sentence the relation makes. */
    private static List<String> rows(DSLContext dsl) {
        return dsl.selectFrom(INTENT_CONDITION_PARAM_DECODE)
            .where(INTENT_CONDITION_PARAM_DECODE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_CONDITION_PARAM_DECODE.USE_SITE)
            .fetch(r -> r.get(INTENT_CONDITION_PARAM_DECODE.SITE)
                + " " + r.get(INTENT_CONDITION_PARAM_DECODE.USE_SITE)
                + " " + r.get(INTENT_CONDITION_PARAM_DECODE.CLASS_NAME)
                + "." + r.get(INTENT_CONDITION_PARAM_DECODE.METHOD_NAME)
                + " " + r.get(INTENT_CONDITION_PARAM_DECODE.NODE_TYPE_NAME)
                + " " + r.get(INTENT_CONDITION_PARAM_DECODE.KEY_ARITY)
                + " " + r.get(INTENT_CONDITION_PARAM_DECODE.LIST_VALUED));
    }
}
