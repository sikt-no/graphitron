package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_SLOT;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedServiceArgmappingEntry;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What receives a decoded node id: {@code intent_node_id_decode} for the destination, and
 * {@code intent_node_id_decode_slot} for the fork that decides whether there is a destination here
 * at all.
 *
 * <p>The two relations are pinned together, because one is defined as the population the other does
 * not claim. A case that asserted only the destination would pass just as well if the slot relation
 * saw nothing, and a decode routed to a table predicate when its value goes to a Java parameter is
 * exactly the bug that shape would hide. So every case about the fork states both sides: which parameter
 * the value descends into, or that none does, and the destination that follows. The cases whose
 * subject is which slot destination answers state the destination alone, the fork already being
 * settled by the section above them.
 *
 * <p>The slot cases are keyed at the root of the use site rather than at the coordinate the author
 * annotated, and that is the claim most worth pinning: an input field two steps inside an argument
 * reaches Java because the argument does, so the same two rules answer at one coordinate for both
 * sites. The input-field cases below are the ones that would fail if the fork were asked at the
 * annotated coordinate instead.
 */
class NodeIdDecodeDestinationTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";
    private static final String CLASSES = "classes";
    private static final String SVC = "no.example.Svc";

    // ===== No Java slot: the two table destinations =====

    /**
     * Own-row identity lifts every key position onto the row's own table, so the predicate binds
     * locally and nothing descends into Java.
     */
    @Test
    void anIdentityDecodeBindsTheRowsOwnColumns() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film OWN_TABLE_COLUMNS 1");
        });
    }

    /**
     * The junction chain. Its second hop departs a column the first never arrived at, so no position
     * lifts, and the destination is the node type's own table inside a correlated {@code EXISTS}
     * rather than a rejection. The whole of the case this relation exists to turn from an error into
     * an answer.
     */
    @Test
    void aJunctionChainBindsTheNodeTypesOwnTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_category_category_id_fkey");

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(inCategory) Category TARGET_TABLE_COLUMNS 1");
        });
    }

    /**
     * A composite key whose every position lifts is still local, and the arity travels with the row.
     * Stated because the arity is what a later refusal at a single-valued slot names, and a relation
     * that only carried the destination would leave the count to be recounted.
     */
    @Test
    void aCompositeKeyThatLiftsWhollyIsLocalAndCarriesItsCount() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedTableBinding(dsl, GRAPH, "CategoryNote", "film_category_note");
            seedField(dsl, GRAPH, "Query", "notes", "CategoryNote", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "notes", "ofPairing", "FilmCategory");

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.notes(ofPairing) FilmCategory OWN_TABLE_COLUMNS 2");
        });
    }

    /**
     * A partial lift is remote. A reverse hop arrives on the key's own columns and reaches whichever
     * of the node type's key columns the departure happens to share, which here is one of two. One
     * position arriving and another not is not a third destination: a tuple predicate over the row's
     * own table needs every position, so anything short of that binds on the node type's table. Which
     * position failed is the key-column child's answer and not this relation's, so the case asserts
     * the reading and not the arithmetic behind it.
     */
    @Test
    void aPartialLiftIsRemoteRatherThanAThirdDestination() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmActor", "film_actor");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ofPair", "FilmActor");
            seedArgumentPath(dsl, "Query", "films", "ofPair", "film_actor_film_id_fkey");

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ofPair) FilmActor TARGET_TABLE_COLUMNS 2");
        });
    }

    // ===== A Java slot at the argument's own coordinate =====

    /**
     * The coordinate the reporter met: a plain argument on a field a Java method produces, matched to
     * a parameter by its own name with no {@code argMapping} written at all. The value descends into
     * that parameter rather than into a table predicate, the key is one column, and that column's
     * type is the parameter's, so the destination is the single column and the consumer receives a
     * decoded value where today it receives the base64.
     */
    @Test
    void anArgumentMatchedToAParameterByNameDescendsIntoIt() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.String");

            assertThat(slots(dsl)).containsExactly(
                "Query.films(ids) NAMED_PARAMETER ids java.lang.String"
                    + " root Query.films(ids) candidates 1");
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    /**
     * The same coordinate reached the other way: an {@code argMapping} entry names the parameter, so
     * the pair answers rather than the name match. Both carriers are one destination and the case
     * pins that they do not both fire, the name match standing aside where a pair claims the
     * parameter.
     */
    @Test
    void anArgumentMappedToAParameterDescendsIntoItOnce() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "filmId", "java.lang.String");
            seedServiceArgmappingEntry(dsl, GRAPH, "Query", "films", 0, "filmId", "ids");

            assertThat(slots(dsl)).containsExactly(
                "Query.films(ids) MAPPED_PARAMETER filmId java.lang.String"
                    + " root Query.films(ids) candidates 1");
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    /**
     * A parameter an {@code argMapping} entry redirects to some other argument is not the slot for
     * this one, even though its name matches. The absence is read on the parameter rather than on the
     * argument for exactly this shape, and without that reading the decode would claim a parameter
     * fed from somewhere else.
     */
    @Test
    void aParameterRedirectedToAnotherArgumentIsNotThisArgumentsSlot() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedArgument(dsl, GRAPH, "Query", "films", "other", "ID");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.String");
            seedServiceArgmappingEntry(dsl, GRAPH, "Query", "films", 0, "ids", "other");

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film OWN_TABLE_COLUMNS 1");
        });
    }

    /**
     * A parameter the census cannot type is still a slot, and the decode is still carried out. The
     * row stands with a null type, because absence here would mean the decode binds a predicate and
     * would hand the consumer the wire format at the one coordinate this whole reading exists to
     * close; the destination then follows from the arity alone and the type gate stands aside, with
     * javac as the backstop. Refusing on an operand nobody could read is what would reinstate the
     * bug at exactly the coordinate least able to report it.
     */
    @Test
    void anUntypeableParameterIsStillTheSlot() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedService(dsl, GRAPH, "Query", "films", SVC, "get");
            seedClass(dsl, CLASSES, SVC, "CLASS");
            seedMethod(dsl, CLASSES, SVC, "get", "()V");
            // A primitive parameter names no class, so the census records the parameter and no
            // type reference for it.
            seedMethodParameter(dsl, CLASSES, SVC, "get", "()V", 0, "ids", Map.of());

            assertThat(slots(dsl)).containsExactly(
                "Query.films(ids) NAMED_PARAMETER ids (untyped)"
                    + " root Query.films(ids) candidates 1");
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    // ===== Which slot destination, and where neither answers =====

    /**
     * A parameter typed as the generated record of the node type's own table receives the whole
     * decoded tuple, whatever the key's arity. Stated at arity two because that is where the record
     * destination is doing something the single-column one cannot: two values into one row type.
     */
    @Test
    void aRecordOfTheNodeTypesOwnTableTakesTheWholeTuple() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedProducer(dsl, "Query", "pairings", "ids", recordClass("film_category"));

            assertThat(destinations(dsl))
                .containsExactly("Query.pairings(ids) FilmCategory JOOQ_RECORD 2");
        });
    }

    /**
     * At arity one both slot destinations could read the row, so the precedence is pinned: a record
     * is a record. Read the other way round a single-column key reaching its own table's record would
     * bind one column value into a row type, which is the reading this ordering exists to prevent.
     */
    @Test
    void aRecordWinsOverTheSingleColumnAtArityOne() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", recordClass("film"));

            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film JOOQ_RECORD 1");
        });
    }

    /**
     * A record of some other table is not this node type's tuple, and it is deliberately no
     * destination rather than falling through to the single-column one. Falling through is the
     * failure worth a case of its own: it would bind one decoded value into a row type, and the
     * record standing for an enclosing input type is exactly the shape that would reach it.
     */
    @Test
    void aRecordOfSomeOtherTableIsNoDestinationHere() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", recordClass("actor"));

            assertThat(slots(dsl)).containsExactly(
                "Query.films(ids) NAMED_PARAMETER ids " + recordClass("actor")
                    + " root Query.films(ids) candidates 1");
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * Both types known and disagreeing is no destination, which is what makes the gate a gate. The
     * refusal naming both types is the defect view's, and its population is exactly this absence.
     */
    @Test
    void aTypeDisagreementAtASingleColumnIsNoDestination() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.Long");

            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * A pinned key column the table does not have is no destination, where an untypeable parameter
     * stands aside and leaves one. The two used to agree: a pin resolved whether or not the table
     * had the column, so the only thing missing was the type and the decode went ahead on the arity.
     * A pin the catalog cannot answer now resolves to no key column at all, so there is no arity to
     * carry it and nothing to emit against a column that is not there.
     */
    @Test
    void aKeyColumnTheCatalogCannotTypeIsNoDestination() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "not_a_column");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.Long");

            assertThat(destinations(dsl))
                .as("the pin resolved to nothing, so there is no key to decode into")
                .isEmpty();
        });
    }

    /**
     * A composite key into a slot that is not a record is no destination. The count is what refuses
     * it and the defect view quotes that count, so the case states the absence at the arity rather
     * than at a type: the parameter's own type agrees with the first key column's here, and it is
     * still not somewhere two values fit.
     */
    @Test
    void aCompositeKeyIntoASingleValuedSlotIsNoDestination() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedProducer(dsl, "Query", "pairings", "ids", "java.lang.String");

            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * An overloaded {@code @service} resolves candidate slots and no destination, and the two halves
     * of that are one claim. The slot rows have to survive the ambiguity, because a use site with no
     * slot row reads as binding a table predicate, and an author whose service names two overloads
     * would then get the wire format bound into SQL. The destination then declines to pick, so the
     * instruction is carried out nowhere rather than carried out wrongly.
     */
    @Test
    void anOverloadedProducerResolvesCandidateSlotsAndNoDestination() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.String");
            seedMethod(dsl, CLASSES, SVC, "get", "(I)V");
            seedMethodParameter(dsl, CLASSES, SVC, "get", "(I)V", 0, "ids",
                Map.of("", "java.lang.String"));

            assertThat(slots(dsl)).hasSize(2);
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    // ===== A Java slot reached from the root of an input field's use site =====

    /**
     * The case the root keying exists for. The instruction sits on an input field two steps from any
     * argument, and nothing about that field or its type says whether its value reaches Java; what
     * says so is that the argument its occurrence path descends from is fed to a parameter. Asked at
     * the annotated coordinate the fork would find nothing and route the decode to a predicate.
     *
     * <p>No destination resolves, and that is this case's second statement rather than an oversight.
     * The parameter takes a consumer bean, so what receives the decoded value is a member of that
     * bean and not the parameter itself, and which member that is needs a walk into the class that
     * the slot relation deliberately does not perform. The value reaching Java is settled here; where
     * inside Java it lands is the piece still owed. What matters is that the fork already keeps this
     * coordinate out of the table population, so nothing binds a predicate against a value bound for
     * a bean.
     */
    @Test
    void anInputFieldReachesJavaBecauseItsRootArgumentDoes() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedTableBinding(dsl, GRAPH, "Filter", "film");
            seedField(dsl, GRAPH, "Filter", "filmId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Filter", "filmId", "Film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "where", "Filter");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "where", "Filter",
                new OccurrenceStep("Filter", "filmId", "ID"));
            seedProducer(dsl, "Query", "films", "where", "no.example.Bean");

            assertThat(slots(dsl)).containsExactly(
                "Query.films(where)/filmId NAMED_PARAMETER where no.example.Bean"
                    + " root Query.films(where) candidates 1");
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * The same input field where nothing consumes the argument in Java binds a predicate, which is
     * what makes the case above a fork and not a blanket exemption for the input-field site.
     */
    @Test
    void theSameInputFieldBindsAPredicateWhereNoParameterIsFedFromIt() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedTableBinding(dsl, GRAPH, "Filter", "film");
            seedField(dsl, GRAPH, "Filter", "filmId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Filter", "filmId", "Film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "where", "Filter");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "where", "Filter",
                new OccurrenceStep("Filter", "filmId", "ID"));

            assertThat(slots(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(where)/filmId Film OWN_TABLE_COLUMNS 1");
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, CLASSES, "JAR");
            seedGraphSource(dsl, GRAPH, CLASSES);
            for (String table : new String[]{"film", "actor", "category", "film_actor",
                                             "film_category", "film_category_note"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "actor", "actor_id", 0, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "category", "category_id", 0, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "actor_id", 1, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "category_id", 1, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category_note", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category_note", "category_id", 1, "CATEGORY_ID");

            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "category", "category_pkey", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "film_id", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category", "film_category_pkey",
                "film_id", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category_note", "film_category_note_pkey",
                "film_id", "category_id");
            // The junction, whose two keys depart different columns, so no identity carries.
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_category_id_fkey",
                "category", "category_pkey", "category_id");
            // The reverse hop: film_actor declares the key and film is the departure, so the walk
            // arrives on one of the pairing's two key columns and the other never lifts.
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category_note", "film_category_note_fkey",
                "film_category", "film_category_pkey", "film_id", "category_id");

            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** The generated record class the catalog fixture names for a table. */
    private static String recordClass(String tableName) {
        return PKG + ".tables.records." + tableName + "Record";
    }

    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
    }

    /** An argument-site {@code @reference} whose elements each name a key, in written order. */
    private static void seedArgumentPath(DSLContext dsl, String typeName, String fieldName,
                                        String argumentName, String... keyRefs) {
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, 0);
        for (int position = 0; position < keyRefs.length; position++) {
            seedArgumentReferenceStep(dsl, GRAPH, typeName, fieldName, argumentName,
                0, position, null, keyRefs[position]);
        }
    }

    /**
     * A {@code @service} on the field with the method behind it declaring one parameter of the given
     * name and type. The whole classpath side of a slot, stated in one call because every case that
     * wants a parameter wants the four rows under it.
     */
    private static void seedProducer(DSLContext dsl, String typeName, String fieldName,
                                    String paramName, String paramClass) {
        seedService(dsl, GRAPH, typeName, fieldName, SVC, "get");
        seedClass(dsl, CLASSES, SVC, "CLASS");
        seedMethod(dsl, CLASSES, SVC, "get", "()V");
        seedMethodParameter(dsl, CLASSES, SVC, "get", "()V", 0, paramName,
            Map.of("", paramClass));
    }

    private static List<String> slots(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_NODE_ID_DECODE_SLOT.fields())
            .from(INTENT_NODE_ID_DECODE_SLOT)
            .where(INTENT_NODE_ID_DECODE_SLOT.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_NODE_ID_DECODE_SLOT.USE_SITE, INTENT_NODE_ID_DECODE_SLOT.CARRIER,
                INTENT_NODE_ID_DECODE_SLOT.PARAM_NAME)
            .fetch()
            .map(NodeIdDecodeDestinationTest::renderSlot);
    }

    private static List<String> destinations(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_NODE_ID_DECODE.fields())
            .from(INTENT_NODE_ID_DECODE)
            .where(INTENT_NODE_ID_DECODE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_NODE_ID_DECODE.USE_SITE, INTENT_NODE_ID_DECODE.NODE_TYPE_NAME)
            .fetch()
            .map(NodeIdDecodeDestinationTest::renderDestination);
    }

    /** Use site, node type, destination, and the key arity the destination was decided against. */
    private static String renderDestination(Record row) {
        return row.get(INTENT_NODE_ID_DECODE.USE_SITE) + " "
            + row.get(INTENT_NODE_ID_DECODE.NODE_TYPE_NAME) + " "
            + row.get(INTENT_NODE_ID_DECODE.DESTINATION) + " "
            + row.get(INTENT_NODE_ID_DECODE.ARITY);
    }

    /**
     * Use site, which rule found the parameter, its name and type, and the root coordinate the rule
     * answered at. The root is rendered rather than left implicit because it is the whole of what
     * makes an input-field row correct.
     */
    private static String renderSlot(Record row) {
        String type = row.get(INTENT_NODE_ID_DECODE_SLOT.JAVA_TYPE);
        return row.get(INTENT_NODE_ID_DECODE_SLOT.USE_SITE) + " "
            + row.get(INTENT_NODE_ID_DECODE_SLOT.CARRIER) + " "
            + row.get(INTENT_NODE_ID_DECODE_SLOT.PARAM_NAME) + " "
            + (type != null ? type : "(untyped)")
            + " root " + row.get(INTENT_NODE_ID_DECODE_SLOT.ROOT_TYPE_NAME) + "."
            + row.get(INTENT_NODE_ID_DECODE_SLOT.ROOT_FIELD_NAME) + "("
            + row.get(INTENT_NODE_ID_DECODE_SLOT.ROOT_ARGUMENT_NAME) + ")"
            + " candidates " + row.get(INTENT_NODE_ID_DECODE_SLOT.CANDIDATES);
    }
}
