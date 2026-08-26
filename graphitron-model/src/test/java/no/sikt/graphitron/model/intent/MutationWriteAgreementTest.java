package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_AGREEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_DESTINATION;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
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
 * What {@code intent_mutation_write_agreement} states: which two of an UPDATE's contributions
 * decode a value for one column and must therefore be checked equal before the statement runs.
 *
 * <p>The relation is a reduction over {@code intent_mutation_write_destination} rather than a fact
 * beside it, so the cases here are the cases that relation already distinguishes, read as pairs.
 * One side is always the occurrence the WHERE clause reads. The other is one of the two carriers
 * that can land a second value on a key column: a self-referencing foreign key, which writes the
 * column as well, and a straddling cross-table reference whose in-key column something else pins,
 * which neither writes nor filters it.
 *
 * <p>The absences carry as much as the pairs. A carrier that is the sole contributor to a column
 * is not an obligation, a check against nothing being no check; a DELETE has no obligations at all,
 * every column of one being a predicate; and a refused payload has none because it has no
 * statement.
 */
class MutationWriteAgreementTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * The same {@code film} catalog the destination's own cases use: a primary key on
     * {@code film_id} and unique keys the match walks down, a self-referencing foreign key on
     * {@code parent_id}, and a two-column foreign key to {@code publisher} of which the
     * {@code film_pub_a_uk} unique key claims exactly one column, which is what makes a straddle
     * expressible.
     */
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

    /** An UPDATE write surface over {@code film}, taking its payload through {@code in}. */
    private static void updateSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
        seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
        seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", inputTypeName);
    }

    /** A DELETE write surface over the same table, which names it on the field. */
    private static void deleteSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
        seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
        seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", inputTypeName);
    }

    /** One nullable input field on a payload type, reached from the named write surface. */
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

    /** {@code Film} as a node type of its own table, keyed on its primary key column. */
    private static void filmIsANode(DSLContext dsl) {
        seedNode(dsl, GRAPH, "Film");
        seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
    }

    /** {@code Publisher} as a node type, keyed on the pair its primary key declares. */
    private static void publisherIsANode(DSLContext dsl) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
    }

    /**
     * A {@code @nodeId(typeName: Film) @reference} following the self-referencing foreign key: one
     * column, {@code parent_id}, pointing at a sibling row rather than at this one.
     */
    private static void selfFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                    String fieldName, int ordinal) {
        filmIsANode(dsl);
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Film");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_parent_fkey");
    }

    /**
     * A {@code @nodeId(typeName: Publisher) @reference} following the foreign key to
     * {@code publisher}: two lifted columns, {@code pub_a_ref} and {@code pub_b_ref}, of which the
     * {@code film_pub_a_uk} unique key claims the first.
     */
    private static void crossTableFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                          String fieldName, int ordinal, boolean nonNull) {
        publisherIsANode(dsl);
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal, nonNull);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Publisher");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_pub_fkey");
    }

    private static List<String> obligations(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_WRITE_AGREEMENT.fields())
            .from(INTENT_MUTATION_WRITE_AGREEMENT)
            .where(INTENT_MUTATION_WRITE_AGREEMENT.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_WRITE_AGREEMENT.COLUMN_NAME,
                     INTENT_MUTATION_WRITE_AGREEMENT.REFERENCE_PATH,
                     INTENT_MUTATION_WRITE_AGREEMENT.REFERENCE_POSITION)
            .fetch()
            .map(MutationWriteAgreementTest::render);
    }

    /**
     * The column, then each side as the field name and the decode slot of its own carrier that
     * holds this column's value, then what the reference side's column is for in the statement.
     * The predicate side's role rides along because it is the one thing about the two sides that
     * varies: the reference side is always a decode and the predicate side may not be.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_WRITE_AGREEMENT.COLUMN_NAME) + " "
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.KEY_INPUT_FIELD_NAME) + ":"
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.KEY_POSITION) + " "
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.KEY_ROLE) + " = "
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.REFERENCE_INPUT_FIELD_NAME) + ":"
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.REFERENCE_POSITION) + " "
            + row.get(INTENT_MUTATION_WRITE_AGREEMENT.REFERENCE_DESTINATION);
    }

    // ===== The two carriers that reach the reference side =====

    /**
     * The self-referencing foreign key overlap. Its column is a pointer at a sibling row, so it is
     * written however it falls, and where a plain carrier supplies the same column as the
     * statement's predicate the two values both arrive on the wire and both reach the same column.
     * The foreign key forces them equal for well-formed input and nothing forces the input to be
     * well formed, so the pair is an obligation and the reference side is the one that also writes.
     */
    @Test
    void aSelfFkWritingAPinnedKeyColumnMustAgreeWithThePredicate() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 0);
            selfFkField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", 1);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 2);

            assertThat(obligations(dsl)).containsExactly(
                "parent_id parent_id:0 NAME_MATCHED = parentRef:0 VALUE");
        });
    }

    /**
     * The straddler's checked column. A cross-table reference whose in-key half something else
     * already pins neither filters nor writes that column, and its only contribution to the
     * statement is this obligation, which is why the destination has a third value at all. Its
     * out-of-key half is an ordinary assignment and is not an obligation: nothing else supplies it.
     */
    @Test
    void aStraddlerWhoseKeyColumnIsPinnedElsewhereMustAgreeWithThePredicate() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "pub_a_ref", "String", 0);
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 1, true);

            assertThat(obligations(dsl)).containsExactly(
                "pub_a_ref pub_a_ref:0 NAME_MATCHED = publisherRef:0 CHECKED");
        });
    }

    /**
     * The decode slot on the reference side is a column of this relation rather than an implicit
     * ordering, and this is the case that shows why. The straddler's checked column sits at slot
     * zero of a decode record whose other slot went to the assignment half, so neither half's own
     * ordering recovers it; here the pinned column is the second of the pair instead of the first,
     * and the obligation names slot one.
     */
    @Test
    void theReferenceSideNamesItsOwnDecodeSlot() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "crate");
            seedColumn(dsl, PKG, PUBLIC, "crate", "p", 0, "P");
            seedColumn(dsl, PKG, PUBLIC, "crate", "q", 1, "Q");
            seedUniqueKey(dsl, PKG, PUBLIC, "crate", "crate_q_uk", "q");
            seedForeignKey(dsl, PKG, PUBLIC, "crate", "crate_fkey",
                "publisher", "publisher_pkey", "p", "q");
            seedTableBinding(dsl, GRAPH, "Crate", "crate");
            publisherIsANode(dsl);

            seedType(dsl, GRAPH, "CrateUpdateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateCrate", "Crate", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateCrate", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateCrate", "in", "CrateUpdateInput");
            payloadField(dsl, "updateCrate", "CrateUpdateInput", "q", "String", 0);
            seedInputField(dsl, GRAPH, "CrateUpdateInput", "crateRef", "ID", 1, true, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateCrate", "in", "CrateUpdateInput",
                new OccurrenceStep("CrateUpdateInput", "crateRef", "ID"));
            seedFieldNodeId(dsl, GRAPH, "CrateUpdateInput", "crateRef", "Publisher");
            seedFieldReference(dsl, GRAPH, "CrateUpdateInput", "crateRef", 0);
            seedFieldReferenceStep(dsl, GRAPH, "CrateUpdateInput", "crateRef", 0, 0, null, "crate_fkey");

            assertThat(obligations(dsl)).containsExactly(
                "q q:0 NAME_MATCHED = crateRef:1 CHECKED");
        });
    }

    // ===== Which occurrence is the predicate side =====

    /**
     * Where two straddlers claim one key column and nothing else pins it, the one declared first
     * supplies the predicate and the other is the reference side. The predicate side of an
     * obligation is therefore not always a plain field, and its role says so.
     */
    @Test
    void thePredicateSideOfTwoContendingStraddlersIsTheFirstDeclared() {
        withContendingStraddlers(dsl ->
            assertThat(obligations(dsl)).containsExactly(
                "a refOne:0 NODE_ID = refTwo:0 CHECKED"),
            "refOne", "refTwo");
    }

    /**
     * The mirror of the pair above, with the two fields declared the other way round. Without it
     * the case above would pass on whatever order this relation happened to produce, and which
     * field's decode the emitted predicate reads is exactly what the order decides.
     */
    @Test
    void reversingTheDeclarationOrderSwapsTheTwoSides() {
        withContendingStraddlers(dsl ->
            assertThat(obligations(dsl)).containsExactly(
                "a refTwo:0 NODE_ID = refOne:0 CHECKED"),
            "refTwo", "refOne");
    }

    /**
     * A {@code shelf} table keyed on {@code a} alone, with two foreign keys to {@code publisher}
     * lifting {@code a} with {@code b} and {@code a} with {@code d}. Both references straddle the
     * key, both claim {@code a}, and nothing else supplies it, which is the only shape where the
     * order the payload declares its fields in is observable. {@code declared} names the two
     * payload fields in the order they are declared in.
     */
    private static void withContendingStraddlers(Consumer<DSLContext> body, String... declared) {
        withCatalog(dsl -> {
            shelfCatalog(dsl);
            shelfSurface(dsl);
            for (int ordinal = 0; ordinal < declared.length; ordinal++) {
                var name = declared[ordinal];
                seedInputField(dsl, GRAPH, "ShelfUpdateInput", name, "ID", ordinal, true, false, null);
                seedOccurrencePath(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput",
                    new OccurrenceStep("ShelfUpdateInput", name, "ID"));
                shelfReference(dsl, "ShelfUpdateInput", name, name.equals("refOne"));
            }
            body.accept(dsl);
        });
    }

    /** The two-reference {@code shelf} table and the {@code publisher} node the pair points at. */
    private static void shelfCatalog(DSLContext dsl) {
        seedTable(dsl, PKG, PUBLIC, "shelf");
        seedColumn(dsl, PKG, PUBLIC, "shelf", "a", 0, "A");
        seedColumn(dsl, PKG, PUBLIC, "shelf", "b", 1, "B");
        seedColumn(dsl, PKG, PUBLIC, "shelf", "d", 2, "D");
        seedPrimaryKey(dsl, PKG, PUBLIC, "shelf", "shelf_pkey", "a");
        seedForeignKey(dsl, PKG, PUBLIC, "shelf", "shelf_one_fkey",
            "publisher", "publisher_pkey", "a", "b");
        seedForeignKey(dsl, PKG, PUBLIC, "shelf", "shelf_two_fkey",
            "publisher", "publisher_pkey", "a", "d");
        seedTableBinding(dsl, GRAPH, "Shelf", "shelf");
        publisherIsANode(dsl);
    }

    /** An UPDATE write surface over {@code shelf}. */
    private static void shelfSurface(DSLContext dsl) {
        seedType(dsl, GRAPH, "ShelfUpdateInput", "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "updateShelf", "Shelf", false);
        seedMutation(dsl, GRAPH, "Mutation", "updateShelf", "UPDATE");
        seedArgument(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput");
    }

    /** One of {@code shelf}'s two references, on the input type and field named. */
    private static void shelfReference(DSLContext dsl, String inputTypeName, String fieldName,
                                       boolean first) {
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Publisher");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null,
            first ? "shelf_one_fkey" : "shelf_two_fkey");
    }

    /**
     * Two occurrences of one field name produce no obligation, whatever else they do. The check
     * this lowers to names two input fields, and a field cannot be reported as disagreeing with
     * itself, so the walker skips the pair rather than emitting a check nobody could act on. Here
     * two nested groupings each declare a field called {@code ref}, both straddling the same key
     * column: the destination still disposes them, the first pinning it and the second checked,
     * and no obligation is minted over the two.
     */
    @Test
    void twoOccurrencesOfOneFieldNameAreNotAnObligation() {
        withCatalog(dsl -> {
            shelfCatalog(dsl);
            shelfSurface(dsl);
            seedType(dsl, GRAPH, "ShelfOneNest", "INPUT_OBJECT");
            seedType(dsl, GRAPH, "ShelfTwoNest", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "ShelfUpdateInput", "one", "ShelfOneNest", 0, true, false, null);
            seedInputField(dsl, GRAPH, "ShelfUpdateInput", "two", "ShelfTwoNest", 1, true, false, null);
            seedInputField(dsl, GRAPH, "ShelfOneNest", "ref", "ID", 0, true, false, null);
            seedInputField(dsl, GRAPH, "ShelfTwoNest", "ref", "ID", 0, true, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput",
                new OccurrenceStep("ShelfUpdateInput", "one", "ShelfOneNest"),
                new OccurrenceStep("ShelfOneNest", "ref", "ID"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput",
                new OccurrenceStep("ShelfUpdateInput", "two", "ShelfTwoNest"),
                new OccurrenceStep("ShelfTwoNest", "ref", "ID"));
            shelfReference(dsl, "ShelfOneNest", "ref", true);
            shelfReference(dsl, "ShelfTwoNest", "ref", false);

            assertThat(obligations(dsl)).isEmpty();
            assertThat(dispositionsOfTheContestedColumn(dsl))
                .as("the two occurrences the pair would have been drawn from")
                .containsExactly("Mutation.updateShelf(in)/one/ref -> PREDICATE",
                                 "Mutation.updateShelf(in)/two/ref -> CHECKED");
        });
    }

    /**
     * What the destination made of the two same-named occurrences, so the absence above is a rule
     * about pairs rather than a payload that produced nothing to pair.
     */
    private static List<String> dispositionsOfTheContestedColumn(DSLContext dsl) {
        return dsl.select(INTENT_MUTATION_WRITE_DESTINATION.PATH,
                          INTENT_MUTATION_WRITE_DESTINATION.DESTINATION)
            .from(INTENT_MUTATION_WRITE_DESTINATION)
            .where(INTENT_MUTATION_WRITE_DESTINATION.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_MUTATION_WRITE_DESTINATION.COLUMN_NAME.eq("a"))
            .orderBy(INTENT_MUTATION_WRITE_DESTINATION.PATH)
            .fetch(r -> r.value1() + " -> " + r.value2());
    }

    // ===== What is not an obligation =====

    /**
     * An ordinary UPDATE has none. Every column of it has one contributor, so there is nothing for
     * any value to be checked against, and a relation of pairs over a payload of singletons is
     * empty rather than degenerate.
     */
    @Test
    void anOrdinaryUpdateHasNoObligations() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(obligations(dsl)).isEmpty();
        });
    }

    /**
     * A straddler that is the sole contributor to its in-key column supplies the predicate itself,
     * and a predicate has nothing to agree with. The same carrier that produces an obligation in
     * the case above produces none here, which is what makes the obligation a fact about the pair
     * rather than about the carrier.
     */
    @Test
    void aSoleStraddlerPinsItsColumnAndOwesNoAgreement() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 0, true);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(obligations(dsl)).isEmpty();
        });
    }

    /**
     * A DELETE has none, self-referencing foreign key or not. Every admitted column of one is a
     * predicate, so there is no second side for a pair to have: the carrier that would be checked
     * under UPDATE filters here alongside the plain column, and both appear in the WHERE clause.
     */
    @Test
    void aDeleteHasNoObligations() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "film_id", "String", 0);
            selfFkField(dsl, "deleteFilm", "FilmDeleteInput", "parentRef", 1);

            assertThat(obligations(dsl)).isEmpty();
        });
    }

    /**
     * A refused payload has none. This is the checked-straddler case above with one word changed,
     * the reference spelled nullable rather than non-null: the overlap that would be an obligation
     * is still in the input, and the statement is never emitted, so there is nothing for a check to
     * run before.
     */
    @Test
    void aRefusedPayloadOwesNoAgreement() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "pub_a_ref", "String", 0);
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 1, false);

            assertThat(obligations(dsl)).isEmpty();
        });
    }
}
