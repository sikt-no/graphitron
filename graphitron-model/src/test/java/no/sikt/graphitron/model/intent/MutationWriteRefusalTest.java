package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_REFUSAL;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
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
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_mutation_write_refusal} states: why an UPDATE the walker admitted field by
 * field is refused anyway, once the matched key has partitioned its columns.
 *
 * <p>Four causes and they are not one set. Two of them are about a carrier that falls on both sides
 * of the key boundary, and which of the two a straddler draws depends on what it points at and how
 * it is spelled. One is about two plain writers landing on one assignment. The last is about a
 * statement with nothing left to assign at all.
 *
 * <p>The cases divide into the four causes, what separates them from the shapes that are admitted,
 * and the ranking. That last is load-bearing rather than cosmetic: the walker returns after the
 * first stage that collected anything, so a later cause on the same coordinate is not merely
 * unreported but computed over a partition the walker abandoned.
 */
class MutationWriteRefusalTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /** The catalog {@link MutationWriteDestinationTest} partitions over, refused here instead. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "alt_code", 2, "ALT_CODE");
            seedColumn(dsl, PKG, PUBLIC, "film", "parent_id", 3, "PARENT_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_a_ref", 4, "PUB_A_REF");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_b_ref", 5, "PUB_B_REF");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_alt_uk", "alt_code");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_parent_uk", "parent_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_pub_a_uk", "pub_a_ref");

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
            body.accept(dsl);
        });
    }

    private static void updateSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
        seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
        seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", inputTypeName);
    }

    private static void deleteSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
        seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
        seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", inputTypeName);
    }

    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal) {
        payloadField(dsl, mutationField, inputTypeName, fieldName, namedType, ordinal, false);
    }

    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal,
                                     boolean nonNull) {
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, namedType, ordinal, nonNull, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", mutationField, "in", inputTypeName,
            new OccurrenceStep(inputTypeName, fieldName, namedType));
    }

    /**
     * A {@code @nodeId(typeName: FilmPair)} field with no {@code @reference}: {@code FilmPair} is a
     * second node type over {@code film} itself, keyed on two of its columns, so the decode lands on
     * the row's own columns and the carrier is one whole identity rather than a pointer.
     */
    private static void sameTableNodeIdField(DSLContext dsl, String inputTypeName,
                                             String fieldName, int ordinal) {
        seedType(dsl, GRAPH, "FilmPair", "OBJECT");
        seedTableBinding(dsl, GRAPH, "FilmPair", "film");
        seedNode(dsl, GRAPH, "FilmPair");
        seedNodeKeyColumnRef(dsl, GRAPH, "FilmPair", 0, "film_id");
        seedNodeKeyColumnRef(dsl, GRAPH, "FilmPair", 1, "alt_code");
        payloadField(dsl, "updateFilm", inputTypeName, fieldName, "ID", ordinal);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "FilmPair");
    }

    /**
     * A {@code @nodeId(typeName: Publisher) @reference} lifting {@code pub_a_ref} and
     * {@code pub_b_ref}, of which {@code film_pub_a_uk} claims the first: the straddler whose
     * spelling decides whether the payload is admitted.
     */
    private static void crossTableFkField(DSLContext dsl, String inputTypeName, String fieldName,
                                          int ordinal, boolean nonNull) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
        payloadField(dsl, "updateFilm", inputTypeName, fieldName, "ID", ordinal, nonNull);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Publisher");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_pub_fkey");
    }

    /** A second plain writer on a column the payload already writes, named by {@code @field}. */
    private static void aliasField(DSLContext dsl, String mutationField, String inputTypeName,
                                   String fieldName, String columnName, int ordinal) {
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, "String", ordinal, false, false, null);
        seedFieldBinding(dsl, GRAPH, inputTypeName, fieldName, columnName);
        seedOccurrencePath(dsl, GRAPH, "Mutation", mutationField, "in", inputTypeName,
            new OccurrenceStep(inputTypeName, fieldName, "String"));
    }

    private static List<String> refusals(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_WRITE_REFUSAL.fields())
            .from(INTENT_MUTATION_WRITE_REFUSAL)
            .where(INTENT_MUTATION_WRITE_REFUSAL.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_WRITE_REFUSAL.CAUSE,
                     INTENT_MUTATION_WRITE_REFUSAL.PATH)
            .fetch()
            .map(MutationWriteRefusalTest::render);
    }

    /**
     * The cause, where it is located and which column it is about. Two of the three vary by cause
     * rather than by row, which is the point: a straddle is a fact about one occurrence and carries
     * no column, a collision is about one column and carries every occurrence writing it, and an
     * empty assignment is about the statement and carries neither.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_WRITE_REFUSAL.CAUSE) + " "
            + row.get(INTENT_MUTATION_WRITE_REFUSAL.PATH) + " "
            + row.get(INTENT_MUTATION_WRITE_REFUSAL.COLUMN_NAME);
    }

    // ===== The carrier that falls on both sides =====

    /**
     * A carrier of the row's own columns may not be split. Half of it is the identity the statement
     * finds the row by and half is a value it writes, which is moving the row rather than updating
     * it, so the whole payload is refused at the carrier that straddles.
     */
    @Test
    void anOwnColumnsCarrierStraddlingTheKeyIsRefused() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            sameTableNodeIdField(dsl, "FilmUpdateInput", "pairId", 0);

            assertThat(refusals(dsl)).containsExactly(
                "MIXED_CARRIER_KEY_MEMBERSHIP Mutation.updateFilm(in)/pairId null");
        });
    }

    /**
     * A nullable cross-table reference straddling the key is refused for a different reason: the
     * split is legitimate, but clearing a nullable pointer would write half of a foreign key and
     * leave the other half where the predicate put it.
     */
    @Test
    void aNullableStraddlingReferenceIsRefused() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "FilmUpdateInput", "publisherRef", 0, false);

            assertThat(refusals(dsl)).containsExactly(
                "NULLABLE_STRADDLING_REFERENCE Mutation.updateFilm(in)/publisherRef null");
        });
    }

    /**
     * The same reference spelled non-null is admitted. The pair is the whole content of that cause:
     * what is refused is the nullable spelling of a straddle and not the straddle.
     */
    @Test
    void aNonNullStraddlingReferenceIsNotRefused() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "FilmUpdateInput", "publisherRef", 0, true);

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    /**
     * A self-referencing foreign key never straddles, whatever its columns do against the key. It
     * routes wholly to the assignment half before the boundary is consulted, so a payload whose
     * only key-column contributor besides the identity is a self-FK is admitted.
     */
    @Test
    void aSelfFkIsNeverAStraddler() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 0);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", "ID", 1);
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "parentRef", "Film");
            seedFieldReference(dsl, GRAPH, "FilmUpdateInput", "parentRef", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmUpdateInput", "parentRef", 0, 0, null,
                "film_parent_fkey");

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    // ===== Two writers on one assignment =====

    /**
     * Two plain carriers assigning one column would silently last-write-win, so the payload is
     * refused. Every occurrence writing the column is named, the walker's own diagnostic quoting
     * two of them being an artefact of the order it built the assignment half in.
     */
    @Test
    void twoPlainWritersOnOneColumnAreRefused() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);
            aliasField(dsl, "updateFilm", "FilmUpdateInput", "alsoTitle", "title", 2);

            assertThat(refusals(dsl)).containsExactly(
                "PLAIN_COLUMN_COLLISION Mutation.updateFilm(in)/alsoTitle title",
                "PLAIN_COLUMN_COLLISION Mutation.updateFilm(in)/title title");
        });
    }

    /**
     * An overlap one of whose writers decodes its value is admitted instead, and reconciled at
     * runtime by the agreement check rather than at build time. That is why the cause is about
     * plain writers rather than about writers.
     */
    @Test
    void anOverlapInvolvingADecodeIsNotACollision() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 1);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", "ID", 2);
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "parentRef", "Film");
            seedFieldReference(dsl, GRAPH, "FilmUpdateInput", "parentRef", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmUpdateInput", "parentRef", 0, 0, null,
                "film_parent_fkey");

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    // ===== Nothing left to assign =====

    /**
     * An UPDATE whose every column is the key it filters on has no assignment to make, which is
     * structurally ill-formed rather than a no-op. The refusal is about the statement, so it is
     * located at the mutation and carries neither an occurrence nor a column.
     */
    @Test
    void anUpdateWithNothingToAssignIsRefused() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);

            assertThat(refusals(dsl)).containsExactly(
                "NO_SET_FIELDS null null");
        });
    }

    // ===== The ranking =====

    /**
     * A payload that straddles and collides reports the straddle alone. The walker returns after
     * the stage that collected anything, so the collision is not merely unreported: it would have
     * been computed over an assignment half the refused carrier never contributed to.
     */
    @Test
    void aStraddleOutranksACollisionOnTheSameCoordinate() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "FilmUpdateInput", "publisherRef", 0, false);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);
            aliasField(dsl, "updateFilm", "FilmUpdateInput", "alsoTitle", "title", 2);

            assertThat(refusals(dsl)).containsExactly(
                "NULLABLE_STRADDLING_REFERENCE Mutation.updateFilm(in)/publisherRef null");
        });
    }

    /**
     * Two straddlers on one coordinate are two rows, the two causes being collected in one stage
     * rather than ranked against each other. An author fixing one of them meets the other, and the
     * walker reports both at once for that reason.
     *
     * <p>Both carriers straddle one key here, which takes a table whose key spans two columns each
     * of a different carrier: a compound primary key over {@code a} and {@code c}, an own-columns
     * carrier lifting {@code a} and {@code b}, and a nullable reference lifting {@code c} and
     * {@code d}. On {@code film} a single-column candidate always outranks such a key, so no one
     * key there is straddled twice.
     */
    @Test
    void twoStraddlersOnOneCoordinateAreBothReported() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "shelf");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "a", 0, "A");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "b", 1, "B");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "c", 2, "C");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "d", 3, "D");
            seedPrimaryKey(dsl, PKG, PUBLIC, "shelf", "shelf_pkey", "a", "c");
            seedForeignKey(dsl, PKG, PUBLIC, "shelf", "shelf_pub_fkey",
                "publisher", "publisher_pkey", "c", "d");
            seedTableBinding(dsl, GRAPH, "Shelf", "shelf");
            seedType(dsl, GRAPH, "ShelfPair", "OBJECT");
            seedTableBinding(dsl, GRAPH, "ShelfPair", "shelf");
            seedNode(dsl, GRAPH, "ShelfPair");
            seedNodeKeyColumnRef(dsl, GRAPH, "ShelfPair", 0, "a");
            seedNodeKeyColumnRef(dsl, GRAPH, "ShelfPair", 1, "b");
            seedType(dsl, GRAPH, "Publisher", "OBJECT");
            seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
            seedNode(dsl, GRAPH, "Publisher");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");

            seedType(dsl, GRAPH, "ShelfUpdateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateShelf", "Shelf", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateShelf", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput");

            payloadField(dsl, "updateShelf", "ShelfUpdateInput", "pairId", "ID", 0);
            seedFieldNodeId(dsl, GRAPH, "ShelfUpdateInput", "pairId", "ShelfPair");
            payloadField(dsl, "updateShelf", "ShelfUpdateInput", "publisherRef", "ID", 1);
            seedFieldNodeId(dsl, GRAPH, "ShelfUpdateInput", "publisherRef", "Publisher");
            seedFieldReference(dsl, GRAPH, "ShelfUpdateInput", "publisherRef", 0);
            seedFieldReferenceStep(dsl, GRAPH, "ShelfUpdateInput", "publisherRef", 0, 0, null,
                "shelf_pub_fkey");

            assertThat(refusals(dsl)).containsExactly(
                "MIXED_CARRIER_KEY_MEMBERSHIP Mutation.updateShelf(in)/pairId null",
                "NULLABLE_STRADDLING_REFERENCE Mutation.updateShelf(in)/publisherRef null");
        });
    }

    // ===== What has no refusal here at all =====

    /**
     * A DELETE has no row here whatever its payload does. Every one of its columns is a predicate,
     * so there is no assignment half for a carrier to straddle into, nothing for two writers to
     * collide over and nothing that must be non-empty.
     */
    @Test
    void aDeleteIsNeverRefusedHere() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "film_id", "String", 0);

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    /**
     * An UPDATE whose input pins no key has no row here either. The partition these causes are
     * measured against does not exist, and that refusal is the matched key's to report.
     */
    @Test
    void anUncoveredUpdateIsRefusedElsewhere() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 0);

            assertThat(refusals(dsl)).isEmpty();
        });
    }
}
