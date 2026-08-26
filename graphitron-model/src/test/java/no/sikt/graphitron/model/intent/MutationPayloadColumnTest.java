package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_PAYLOAD_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_mutation_payload_column} states: which columns of the write table a payload
 * actually puts a value on, at which decode slot, through a carrier of which kind.
 *
 * <p>The cases divide by what varies. The two arms differ in arity rather than in kind, so the
 * name-match cases pin one column at slot zero and the node-id cases pin a compound key arriving as
 * several rows in key order. The carrier role varies independently of both and is what the two
 * consumers fork on, so each of its three values gets a case. And the admission is the refusal
 * relation's complement rather than a rule of its own, so the refusal cases assert an absence beside
 * a contribution: an empty result here would otherwise pass for the right answer.
 */
class MutationPayloadColumnTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * One UPDATE write surface over {@code film}, whose catalog gives each carrier role a shape:
     * {@code film} points at itself through its own primary key, and at {@code publisher} through a
     * two-column key, which is what makes a decoded contribution more than one row.
     */
    private static void withWriteSurface(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "rating", 2, "RATING");
            seedColumn(dsl, PKG, PUBLIC, "film", "parent_id", 3, "PARENT_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_a_ref", 4, "PUB_A_REF");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_b_ref", 5, "PUB_B_REF");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_a", 0, "PUB_A");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_b", 1, "PUB_B");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_a", "pub_b");

            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_parent_fkey",
                "film", "film_pkey", "parent_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_pub_fkey",
                "publisher", "publisher_pkey", "pub_a_ref", "pub_b_ref");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "FilmUpdateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput");
            body.accept(dsl);
        });
    }

    /** One input field on the payload type, reached from the write surface's own argument. */
    private static void payloadField(DSLContext dsl, String fieldName, String namedType) {
        seedInputField(dsl, GRAPH, "FilmUpdateInput", fieldName, namedType, 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
            new OccurrenceStep("FilmUpdateInput", fieldName, namedType));
    }

    /** {@code Film} as a node type of its own table, which the two same-table cases decode against. */
    private static void filmIsANode(DSLContext dsl) {
        seedNode(dsl, GRAPH, "Film");
        seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
    }

    /** {@code Publisher} as a node type whose key is two columns wide. */
    private static void publisherIsANode(DSLContext dsl) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
    }

    private static List<String> columns(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_PAYLOAD_COLUMN.fields())
            .from(INTENT_MUTATION_PAYLOAD_COLUMN)
            .where(INTENT_MUTATION_PAYLOAD_COLUMN.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_PAYLOAD_COLUMN.PATH,
                INTENT_MUTATION_PAYLOAD_COLUMN.POSITION)
            .fetch()
            .map(MutationPayloadColumnTest::render);
    }

    /** The occurrence, the slot, the column, and the two classifications the consumers fork on. */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_PAYLOAD_COLUMN.PATH) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_COLUMN.ROLE) + "/"
            + row.get(INTENT_MUTATION_PAYLOAD_COLUMN.CARRIER_ROLE) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_COLUMN.POSITION) + ":"
            + row.get(INTENT_MUTATION_PAYLOAD_COLUMN.WRITE_TABLE) + "."
            + row.get(INTENT_MUTATION_PAYLOAD_COLUMN.COLUMN_NAME);
    }

    // ===== The name-match arm =====

    /** A plain name reaching a column of the write table is one column at slot zero. */
    @Test
    void aPlainNameIsOneColumnAtSlotZero() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    /**
     * A nested grouping contributes nothing of its own and its leaf contributes at its own
     * occurrence, against the same write table the grouping was handed.
     */
    @Test
    void aNestedLeafContributesAtItsOwnOccurrence() {
        withWriteSurface(dsl -> {
            seedType(dsl, GRAPH, "FilmExtrasInput", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "extras", "FilmExtrasInput", 0, false, false, null);
            seedInputField(dsl, GRAPH, "FilmExtrasInput", "rating", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "extras", "FilmExtrasInput"),
                new OccurrenceStep("FilmExtrasInput", "rating", "String"));

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/extras/rating NAME_MATCHED/OWN_COLUMNS 0:film.rating");
        });
    }

    // ===== The node-id arm, where a contribution can be wider than one column =====

    /** A node id of the write table's own type binds that row's identity, one column at slot zero. */
    @Test
    void aSameTableNodeIdBindsTheRowsOwnKey() {
        withWriteSurface(dsl -> {
            filmIsANode(dsl);
            payloadField(dsl, "filmId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "filmId", "Film");

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/filmId NODE_ID/OWN_COLUMNS 0:film.film_id");
        });
    }

    /**
     * A node id of a type whose key is two columns wide is two rows, one per decode slot, in key
     * order. The case position exists for: the two rows are one contribution, and an emitter reading
     * a value for the second column has to know it comes out of the decode's second slot.
     */
    @Test
    void aCompoundNodeIdIsOneRowPerDecodeSlot() {
        withWriteSurface(dsl -> {
            publisherIsANode(dsl);
            payloadField(dsl, "publisherId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "publisherId", "Publisher");

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherId NODE_ID/CROSS_TABLE_FK 0:film.pub_a_ref",
                "Mutation.updateFilm(in)/publisherId NODE_ID/CROSS_TABLE_FK 1:film.pub_b_ref");
        });
    }

    /**
     * A node id following a foreign key back to the write table itself is a self-FK, and the row
     * says so. The value it binds is a sibling row's identity rather than this row's, which is the
     * one fact about a contribution that no column of it reveals.
     */
    @Test
    void aSelfReferencingNodeIdCarriesTheSelfFkRole() {
        withWriteSurface(dsl -> {
            filmIsANode(dsl);
            payloadField(dsl, "parentId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "parentId", "Film");
            seedFieldReference(dsl, GRAPH, "FilmUpdateInput", "parentId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmUpdateInput", "parentId", 0, 0, null, "film_parent_fkey");

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/parentId NODE_ID/SELF_FK 0:film.parent_id");
        });
    }

    // ===== Admission, which is the refusal relation's complement =====

    /** A refused occurrence contributes nothing, stated beside one that contributes. */
    @Test
    void aRefusedOccurrenceContributesNothing() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "rating", "String", 1, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "rating", "String"));
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "rating", false);

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    /**
     * A leaf under a refused grouping contributes nothing either, though nothing is wrong with the
     * leaf: the walker never descends into a refused grouping, so the leaf is never classified. The
     * cut is inherited from the refusal relation rather than restated, and this is what shows it
     * arriving.
     */
    @Test
    void aLeafUnderARefusedNestingContributesNothing() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            seedType(dsl, GRAPH, "FilmExtrasInput", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "extras", "FilmExtrasInput", 1, false, false, null);
            seedInputField(dsl, GRAPH, "FilmExtrasInput", "rating", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "extras", "FilmExtrasInput"),
                new OccurrenceStep("FilmExtrasInput", "rating", "String"));
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "extras", false);

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    /**
     * An unbound field contributes nothing, which is the one refusal whose upstream row exists and
     * reads as an ordinary classification. Absence here is the role, not a failed column lookup.
     */
    @Test
    void anUnboundFieldContributesNothing() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "nowhere", "String", 1, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "nowhere", "String"));

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    // ===== The population =====

    /** A DELETE's payload contributes the same way, the two walkers flattening identically. */
    @Test
    void aDeletePayloadContributesTheSameWay() {
        withWriteSurface(dsl -> {
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
            seedType(dsl, GRAPH, "FilmDeleteInput", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", "FilmDeleteInput");
            seedInputField(dsl, GRAPH, "FilmDeleteInput", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "deleteFilm", "in", "FilmDeleteInput",
                new OccurrenceStep("FilmDeleteInput", "title", "String"));

            assertThat(columns(dsl)).containsExactly(
                "Mutation.deleteFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    /**
     * One input type shared by two write surfaces contributes once under each. The grain is the
     * occurrence rather than the input field for the reason the whole family is: a consumer building
     * one statement needs that statement's columns and not every statement's.
     */
    @Test
    void aSharedPayloadTypeContributesOncePerWriteSurface() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            seedField(dsl, GRAPH, "Mutation", "updateFilmToo", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilmToo", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilmToo", "in", "FilmUpdateInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilmToo", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "title", "String"));

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title",
                "Mutation.updateFilmToo(in)/title NAME_MATCHED/OWN_COLUMNS 0:film.title");
        });
    }

    /** The graph partition holds: a sibling graph reads none of this one's contributions. */
    @Test
    void aSiblingGraphReadsNoColumns() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");

            derive(dsl);
            assertThat(dsl.selectFrom(INTENT_MUTATION_PAYLOAD_COLUMN)
                .where(INTENT_MUTATION_PAYLOAD_COLUMN.GRAPH_NAME.eq("other"))
                .fetch()).isEmpty();
        });
    }
}
