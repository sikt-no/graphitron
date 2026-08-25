package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_CARRIER_ROLE;
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
 * What {@code intent_input_field_carrier_role} states: what the thing at the end of an input
 * field's column resolution is, which is what decides how its columns may be partitioned and
 * whether they may be used at all.
 *
 * <p>The cases are chosen against the reading this relation exists to replace. The table an input
 * field resolves against does not separate the four answers: a cross-table node id resolves on its
 * own table, correctly, because the columns it binds are the foreign key's own; and the carrier
 * that has no local columns at all resolves the same way. So the pair that must not agree here are
 * exactly the pair that agree everywhere else, and both get a case.
 *
 * <p>Every node-id case is stated twice over, once where the decoded key lands on this table and
 * once where it does not, because those two differ in nothing a reader can see at the coordinate:
 * the same directive, the same reference, the same resolving table, and a different foreign key
 * underneath. That pairing is what a relation asking the catalog "could some key of this table
 * reach that type directly?" gets wrong, and the same-table pair is where it used to be wrong
 * silently, calling a self-reference through a translating key a usable carrier.
 *
 * <p>The three sites that resolve no column at all get a case each, stated beside a carrier so an
 * empty result cannot pass for the right answer. They are the sites the sibling role relation names
 * as nesting, unbound and condition-owned, and none of them is a carrier however its name reads
 * against the table's columns.
 */
class InputFieldCarrierRoleTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * A catalog of three tables. {@code film} points at {@code language} through its node key and at
     * {@code publisher} through an alternate key, which is the pair the landing separates; it points
     * at itself twice, once through its own primary key and once through an alternate one, which is
     * the same pair at the self-reference.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "alt_code", 2, "ALT_CODE");
            seedColumn(dsl, PKG, PUBLIC, "film", "language_id", 3, "LANGUAGE_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_alt", 4, "PUB_ALT");
            seedColumn(dsl, PKG, PUBLIC, "film", "prequel_id", 5, "PREQUEL_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "variant_code", 6, "VARIANT_CODE");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_alt_uk", "alt_code");

            seedTable(dsl, PKG, PUBLIC, "language");
            seedColumn(dsl, PKG, PUBLIC, "language", "language_id", 0, "LANGUAGE_ID");
            seedColumn(dsl, PKG, PUBLIC, "language", "name", 1, "NAME");
            seedPrimaryKey(dsl, PKG, PUBLIC, "language", "language_pkey", "language_id");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_id", 0, "PUB_ID");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "alt_key", 1, "ALT_KEY");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "publisher", "publisher_alt_uk", "alt_key");

            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_language_fkey",
                "language", "language_pkey", "language_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_publisher_alt_fkey",
                "publisher", "publisher_alt_uk", "pub_alt");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_prequel_fkey",
                "film", "film_pkey", "prequel_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_variant_fkey",
                "film", "film_alt_uk", "variant_code");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");
            body.accept(dsl);
        });
    }

    /** A node type bound to a table, its key pinned in SDL. */
    private static void nodeType(DSLContext dsl, String typeName, String tableName, String keyColumn) {
        seedType(dsl, GRAPH, typeName, "OBJECT");
        seedTableBinding(dsl, GRAPH, typeName, tableName);
        seedNode(dsl, GRAPH, typeName);
        seedNodeKeyColumnRef(dsl, GRAPH, typeName, 0, keyColumn);
    }

    /** {@code Film} as a node type of its own table, which the two same-table cases decode against. */
    private static void filmIsANode(DSLContext dsl) {
        seedNode(dsl, GRAPH, "Film");
        seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
    }

    /** One input field on {@code FilmFilter}, reached from the film use site. */
    private static void inputField(DSLContext dsl, String fieldName, String namedType) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, namedType, 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
            new OccurrenceStep("FilmFilter", fieldName, namedType));
    }

    /** A {@code @reference} naming one foreign key by name. */
    private static void referenceKey(DSLContext dsl, String fieldName, String keyName) {
        seedFieldReference(dsl, GRAPH, "FilmFilter", fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", fieldName, 0, 0, null, keyName);
    }

    private static List<String> carriers(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_INPUT_FIELD_CARRIER_ROLE.fields())
            .from(INTENT_INPUT_FIELD_CARRIER_ROLE)
            .where(INTENT_INPUT_FIELD_CARRIER_ROLE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_INPUT_FIELD_CARRIER_ROLE.TYPE_NAME,
                INTENT_INPUT_FIELD_CARRIER_ROLE.FIELD_NAME)
            .fetch()
            .map(InputFieldCarrierRoleTest::render);
    }

    private static String render(Record row) {
        return row.get(INTENT_INPUT_FIELD_CARRIER_ROLE.TYPE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_CARRIER_ROLE.FIELD_NAME) + "@"
            + row.get(INTENT_INPUT_FIELD_CARRIER_ROLE.RESOLVING_TABLE) + " "
            + row.get(INTENT_INPUT_FIELD_CARRIER_ROLE.CARRIER_ROLE);
    }

    // ===== The four answers =====

    /** A plain name reaching a column of the classifying table carries that row's own columns. */
    @Test
    void aPlainNameCarriesOwnColumns() {
        withCatalog(dsl -> {
            inputField(dsl, "title", "String");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.title@film OWN_COLUMNS");
        });
    }

    /**
     * A node id of the classifying table's own type, with no reference, is that row's identity
     * rather than a pointer. The short circuit the resolver takes only when no reference is written.
     */
    @Test
    void aSameTableNodeIdWithNoReferenceCarriesOwnColumns() {
        withCatalog(dsl -> {
            filmIsANode(dsl);
            inputField(dsl, "filmId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "filmId", "Film");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.filmId@film OWN_COLUMNS");
        });
    }

    /**
     * The same type and the same table, with a reference naming a key back to this table whose
     * referenced columns are the node's own key: every decoded position lands on this row's foreign
     * key column, so the carrier points at a sibling row and is never this row's identity.
     */
    @Test
    void aSameTableNodeIdWhoseKeyLandsIsASelfFk() {
        withCatalog(dsl -> {
            filmIsANode(dsl);
            inputField(dsl, "prequelId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "prequelId", "Film");
            referenceKey(dsl, "prequelId", "film_prequel_fkey");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.prequelId@film SELF_FK");
        });
    }

    /**
     * The same shape where the self-reference names a key pointing at an alternate unique key rather
     * than the node's own. The decoded id is a film id and nothing on this row holds one, so there
     * is nothing local to compare and the write rails refuse it. The case a relation asking only
     * "does some key of this table reach Film directly?" answers wrong, that question being about
     * the catalog where this one is about the key the author named.
     */
    @Test
    void aSameTableNodeIdWhoseKeyTranslatesIsRemote() {
        withCatalog(dsl -> {
            filmIsANode(dsl);
            inputField(dsl, "variantId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "variantId", "Film");
            referenceKey(dsl, "variantId", "film_variant_fkey");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.variantId@film REMOTE");
        });
    }

    /**
     * A node id of another type whose key the classifying table's foreign key references: the
     * decoded value lands on this table's own foreign-key column, so the carrier is usable and
     * partitions per column.
     */
    @Test
    void aNodeIdOfAReferencedTypeIsACrossTableFk() {
        withCatalog(dsl -> {
            nodeType(dsl, "Language", "language", "language_id");
            inputField(dsl, "languageId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "languageId", "Language");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.languageId@film CROSS_TABLE_FK");
        });
    }

    /**
     * The same shape where the foreign key points at an alternate key rather than the node key.
     * Nothing on the classifying table holds the decoded value, so there is nothing local to
     * compare and every write rail refuses it. The case that reads identically to the one above
     * everywhere except in where the decode landed.
     */
    @Test
    void aNodeIdReachedThroughAnAlternateKeyIsRemote() {
        withCatalog(dsl -> {
            nodeType(dsl, "Publisher", "publisher", "pub_id");
            inputField(dsl, "publisherId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "publisherId", "Publisher");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.publisherId@film REMOTE");
        });
    }

    /**
     * A plain reference path with no node id behind it reaches its value through the join, which
     * the resolver binds remotely whatever the path names. Stated so the REMOTE arm is not read as
     * a node-id-only answer.
     */
    @Test
    void aPlainReferencePathIsRemote() {
        withCatalog(dsl -> {
            inputField(dsl, "name", "String");
            seedFieldReference(dsl, GRAPH, "FilmFilter", "name", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "name", 0, 0, "language", null);

            assertThat(carriers(dsl)).containsExactly("FilmFilter.name@film REMOTE");
        });
    }

    // ===== The sites that carry nothing =====

    /**
     * A nested input object is where the contribution continues, not where one is made, however its
     * own name reads against the table. This field is named for a column of {@code film} on purpose:
     * a relation reading the column scope directly, which answers for every site whether or not the
     * classifier resolves a name there, would call it a carrier.
     */
    @Test
    void aNestedInputObjectIsNoCarrier() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "TitleFilter", "INPUT_OBJECT");
            inputField(dsl, "title", "TitleFilter");
            seedInputField(dsl, GRAPH, "TitleFilter", "alt_code", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "TitleFilter"),
                new OccurrenceStep("TitleFilter", "alt_code", "String"));

            assertThat(carriers(dsl)).containsExactly("TitleFilter.alt_code@film OWN_COLUMNS");
        });
    }

    /** A name reaching no column of the table is a site with nothing to point at, so no carrier. */
    @Test
    void anUnboundFieldIsNoCarrier() {
        withCatalog(dsl -> {
            inputField(dsl, "title", "String");
            seedInputField(dsl, GRAPH, "FilmFilter", "nowhere", "String", 1, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "nowhere", "String"));

            assertThat(carriers(dsl)).containsExactly("FilmFilter.title@film OWN_COLUMNS");
        });
    }

    /**
     * A field whose {@code @condition(override: true)} owns the whole contribution records no
     * column, so there is nothing for a carrier to point at. Named for a real column, again, so the
     * absence is the classifier's fork and not a failed lookup.
     */
    @Test
    void aConditionOwnedFieldIsNoCarrier() {
        withCatalog(dsl -> {
            inputField(dsl, "title", "String");
            seedInputField(dsl, GRAPH, "FilmFilter", "alt_code", "String", 1, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "alt_code", "String"));
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "alt_code", true);

            assertThat(carriers(dsl)).containsExactly("FilmFilter.title@film OWN_COLUMNS");
        });
    }

    // ===== The grain =====

    /**
     * One input field reached under two arguments on the same table is one row and not two. The
     * decode is keyed by the occurrence path, so the same directive read through two occurrences
     * answers twice; those answers cannot disagree, both being read off the coordinate's own
     * directive, so they are counted together rather than surfaced as a pair.
     */
    @Test
    void twoOccurrencesOnOneTableAreOneRow() {
        withCatalog(dsl -> {
            nodeType(dsl, "Language", "language", "language_id");
            inputField(dsl, "languageId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "languageId", "Language");
            seedField(dsl, GRAPH, "Query", "otherFilms", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter");
            seedOccurrencePath(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "languageId", "ID"));

            assertThat(carriers(dsl)).containsExactly("FilmFilter.languageId@film CROSS_TABLE_FK");
        });
    }
}
