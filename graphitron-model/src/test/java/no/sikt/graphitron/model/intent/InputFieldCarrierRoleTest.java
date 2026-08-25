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
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
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
 * field resolves against does not separate these four: a cross-table node id resolves on its own
 * table, correctly, because the columns it binds are the foreign key's own; and the carrier that
 * has no local columns at all resolves the same way. So the pair that must not agree here are
 * exactly the pair that agree everywhere else, and both get a case.
 *
 * <p>The two same-table shapes are the other pair worth separating. A node id of the classifying
 * table with no reference is that row's own identity, and one carrying a reference is a pointer at
 * a sibling; they name the same type and the same table and differ only in the reference, so a case
 * for each states that the reference is read and not assumed.
 */
class InputFieldCarrierRoleTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * A catalog of three tables. {@code film} points at {@code language} through its node key and at
     * {@code publisher} through an alternate key, which is the pair the lift verdict separates;
     * {@code film} also points at itself, which is the self-FK shape.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");

            seedTable(dsl, PKG, PUBLIC, "language");
            seedColumn(dsl, PKG, PUBLIC, "language", "language_id", 0, "LANGUAGE_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "language", "language_pkey", "language_id");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_id", 0, "PUB_ID");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "alt_key", 1, "ALT_KEY");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "publisher", "publisher_alt_uk", "alt_key");

            foreignKey(dsl, "film", "film_language_fkey", "language", "language_pkey");
            foreignKey(dsl, "film", "film_publisher_alt_fkey", "publisher", "publisher_alt_uk");
            foreignKey(dsl, "film", "film_prequel_fkey", "film", "film_pkey");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");
            body.accept(dsl);
        });
    }

    private static void foreignKey(DSLContext dsl, String declaringTable, String constraintName,
                                   String referencedTable, String referencedConstraint) {
        seedConstraint(dsl, PKG, PUBLIC, declaringTable, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, declaringTable, constraintName,
            PKG, PUBLIC, referencedTable, referencedConstraint);
    }

    /** A node type bound to a table, its key pinned in SDL. */
    private static void nodeType(DSLContext dsl, String typeName, String tableName, String keyColumn) {
        seedType(dsl, GRAPH, typeName, "OBJECT");
        seedTableBinding(dsl, GRAPH, typeName, tableName);
        seedNode(dsl, GRAPH, typeName);
        no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef(dsl, GRAPH, typeName, 0, keyColumn);
    }

    /** One input field on {@code FilmFilter}, reached from the film use site. */
    private static void inputField(DSLContext dsl, String fieldName, String namedType) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, namedType, 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
            new OccurrenceStep("FilmFilter", fieldName, namedType));
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
            seedNode(dsl, GRAPH, "Film");
            no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            inputField(dsl, "filmId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "filmId", "Film");

            assertThat(carriers(dsl)).containsExactly("FilmFilter.filmId@film OWN_COLUMNS");
        });
    }

    /**
     * The same type and the same table, with a reference written beside the node id: the author is
     * naming a key back to this table, so the carrier points at a sibling row and is never this
     * row's identity however the key falls.
     */
    @Test
    void aSameTableNodeIdCarryingAReferenceIsASelfFk() {
        withCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            inputField(dsl, "prequelId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "prequelId", "Film");
            seedFieldReference(dsl, GRAPH, "FilmFilter", "prequelId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "prequelId", 0, 0, "film", null);

            assertThat(carriers(dsl)).containsExactly("FilmFilter.prequelId@film SELF_FK");
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
     * everywhere except here.
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
            inputField(dsl, "languageId", "String");
            seedFieldReference(dsl, GRAPH, "FilmFilter", "languageId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "languageId", 0, 0, "language", null);

            assertThat(carriers(dsl)).containsExactly("FilmFilter.languageId@film REMOTE");
        });
    }
}
