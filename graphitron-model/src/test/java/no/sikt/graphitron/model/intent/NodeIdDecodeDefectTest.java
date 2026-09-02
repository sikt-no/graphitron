package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_PROJECTION_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_DEFECT;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
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
 * Why a decoding {@code @nodeId} instruction was not carried out where its value reaches a Java
 * parameter: {@code intent_node_id_decode_defect}, one row per refused instruction, in a closed
 * verdict vocabulary of two.
 *
 * <p>Every case states the resolution beside the refusal, because this relation is defined as the
 * population the decode does not claim. A case asserting a verdict alone would pass equally well if
 * the decode had resolved the same coordinate, which is the one outcome that must not happen: an
 * instruction is carried out or refused, never both. So the cases read the destination relation too,
 * and the negative controls are the ones that carry the weight.
 *
 * <p>The population's edges get more cases than its two verdicts do, and deliberately. The verdicts
 * are one arity test and one type comparison; what is hard to get right is which slot rows this
 * relation may judge at all. Three families of row are excluded for three different reasons: a
 * mapped parameter, which the pair-grain {@code argMapping} family judges already and with a better
 * message; an input-field slot, where the value lands inside a container and no comparison this
 * relation can make is an author error; and an operand no census or catalog could read, where
 * refusing would close a coordinate on the strength of a missing fact. Each has a case, and the
 * mapped one asserts the other family's row as well, so the boundary is pinned from both sides
 * rather than asserted as an absence here.
 */
class NodeIdDecodeDefectTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";
    private static final String CLASSES = "classes";
    private static final String SVC = "no.example.Svc";

    // ===== The two verdicts =====

    /**
     * A composite key at a parameter that is not the node type's own record: the count is the whole
     * refusal, and the parameter's type agrees with the first key column's here, so the case cannot
     * pass by accident on a type comparison.
     */
    @Test
    void aCompositeKeyAtASingleValuedParameterIsRefusedNamingTheCount() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedProducer(dsl, "Query", "pairings", "ids", "java.lang.String");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.pairings(ids) FilmCategory KEY_ARITY_EXCEEDS_SLOT arity 2"
                    + " column (none) (none) slot java.lang.String");
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * One key column whose type the parameter cannot take. Both operands are on the row, which is
     * what lets the message name a correct column name and still be right about the fault.
     */
    @Test
    void aTypeDisagreementAtTheSoleKeyColumnIsRefusedNamingBothTypes() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.Long");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.films(ids) Film KEY_COLUMN_TYPE_DISAGREEMENT arity 1"
                    + " column film_id java.lang.String slot java.lang.Long");
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    // ===== What resolves draws no verdict =====

    /** The agreeing case: the decode is carried out, so there is nothing to refuse. */
    @Test
    void anAgreeingTypeIsNoDefect() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.String");

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    /**
     * A parameter typed as the node type's own generated record takes the whole tuple, so a
     * composite key there is not a defect. Stated because the arity verdict would otherwise read as
     * "a key of two columns cannot reach Java", which is the wrong rule: what refuses is a key of
     * two columns at a slot that holds one value.
     */
    @Test
    void aRecordOfTheNodeTypesOwnTableIsNoDefectAtAnyArity() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedProducer(dsl, "Query", "pairings", "ids", recordClass("film_category"));

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.pairings(ids) FilmCategory JOOQ_RECORD 2");
        });
    }

    /** No parameter at all is a table predicate, which is a resolution and not a defect. */
    @Test
    void anArgumentBindingATablePredicateIsNoDefect() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film OWN_TABLE_COLUMNS 1");
        });
    }

    // ===== The stand-aside: a refusal needs the operands it names =====

    /**
     * A key column the catalog cannot type draws no type verdict. The decode is carried out on the
     * arity with javac as the backstop, so refusing here would close a coordinate on the strength of
     * a fact nobody could read, at the coordinate least able to report it.
     */
    @Test
    void aKeyColumnTheCatalogCannotTypeDrawsNoTypeVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "not_a_column");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.Long");

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    /** A parameter the census cannot type stands aside on the same terms, from the other side. */
    @Test
    void aParameterTheCensusCannotTypeDrawsNoTypeVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedUntypedProducer(dsl, "Query", "films", "ids");

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl))
                .containsExactly("Query.films(ids) Film SINGLE_KEY_COLUMN 1");
        });
    }

    /**
     * The same untypeable parameter at a composite key is still refused, and this case is what the
     * arity verdict's reading of that absence is for. The verdict does not need to know the
     * parameter's type; it needs to know the parameter is not the tuple's own row type, and a
     * position naming no class is a primitive or a type variable, which is neither a record nor
     * anywhere two values fit.
     */
    @Test
    void anUntypeableParameterAtACompositeKeyIsStillRefusedOnTheArity() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedUntypedProducer(dsl, "Query", "pairings", "ids");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.pairings(ids) FilmCategory KEY_ARITY_EXCEEDS_SLOT arity 2"
                    + " column (none) (none) slot (none)");
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    // ===== The populations this relation may not judge =====

    /**
     * The same type disagreement reached through an {@code argMapping} entry is the pair-grain
     * family's, and the case asserts both halves of that boundary: no row here, and the row there.
     * Two verdicts for one fact is the failure this exclusion exists to prevent, and it would be
     * invisible in a case that only asserted the absence.
     */
    @Test
    void aMappedParameterIsTheArgmappingFamilysToJudge() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "filmId", "java.lang.Long");
            seedServiceArgmappingEntry(dsl, GRAPH, "Query", "films", 0, "filmId", "ids");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "ids");

            assertThat(rows(dsl)).isEmpty();
            // The other family's use site is the directive application's coordinate and this one's
            // is the argument, which is why the boundary is drawn on the carrier rather than by
            // joining the two use sites: the same fault is keyed differently on each side.
            assertThat(argmappingVerdicts(dsl))
                .containsExactly("Query.films KEY_COLUMN_TYPE_MISMATCH");
        });
    }

    /**
     * An overloaded producer resolves several candidate slots and no destination, and draws no
     * verdict here either. Not a silence: the reference itself is refused where references are
     * resolved, so the coordinate never reaches emission, and a verdict here would answer a question
     * already settled. What this case pins is that the relation declines rather than picks, the
     * composite key below being one an arity verdict would otherwise have claimed.
     */
    @Test
    void anOverloadedProducerDrawsNoVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedField(dsl, GRAPH, "Query", "pairings", "FilmCategory", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "pairings", "ids", "FilmCategory");
            seedProducer(dsl, "Query", "pairings", "ids", "java.lang.String");
            seedMethod(dsl, CLASSES, SVC, "get", "(I)V");
            seedMethodParameter(dsl, CLASSES, SVC, "get", "(I)V", 0, "ids",
                Map.of("", "java.lang.String"));

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * An input field whose root argument is fed to a parameter draws no verdict, however that
     * parameter is typed. The parameter receives the whole input object and the decoded value goes
     * to a member of it, so the parameter's own type is not the value's and comparing them would
     * refuse a declaration the author was right to write. The shape is owed the walk into the class,
     * not a refusal.
     */
    @Test
    void anInputFieldSlotDrawsNoVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedType(dsl, GRAPH, "PairingInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "PairingInput", "pairingId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "PairingInput", "pairingId", "FilmCategory");
            seedField(dsl, GRAPH, "Query", "notes", "String", false);
            seedArgument(dsl, GRAPH, "Query", "notes", "in", "PairingInput");
            seedOccurrencePath(dsl, GRAPH, "Query", "notes", "in", "PairingInput",
                new OccurrenceStep("PairingInput", "pairingId", "ID"));
            seedProducer(dsl, "Query", "notes", "in", "no.example.PairingFilter");

            assertThat(rows(dsl)).isEmpty();
            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /** The graph partition holds. */
    @Test
    void aSiblingGraphIsRefusedNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");
            seedProducer(dsl, "Query", "films", "ids", "java.lang.Long");

            derive(dsl);
            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, CLASSES, "JAR");
            seedGraphSource(dsl, GRAPH, CLASSES);
            for (String table : new String[]{"film", "category", "film_category"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "category", "category_id", 0, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "category_id", 1, "CATEGORY_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "category", "category_pkey", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category", "film_category_pkey",
                "film_id", "category_id");
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

    /**
     * A {@code @service} on the field whose method declares one parameter of the given name and
     * type: the whole classpath side of a slot, in one call.
     */
    private static void seedProducer(DSLContext dsl, String typeName, String fieldName,
                                     String paramName, String paramClass) {
        seedService(dsl, GRAPH, typeName, fieldName, SVC, "get");
        seedClass(dsl, CLASSES, SVC, "CLASS");
        seedMethod(dsl, CLASSES, SVC, "get", "()V");
        seedMethodParameter(dsl, CLASSES, SVC, "get", "()V", 0, paramName,
            Map.of("", paramClass));
    }

    /**
     * The same producer whose parameter names no class at all, which is what the census records for
     * a primitive or a type variable: the parameter row is there and the type reference is not.
     */
    private static void seedUntypedProducer(DSLContext dsl, String typeName, String fieldName,
                                            String paramName) {
        seedService(dsl, GRAPH, typeName, fieldName, SVC, "get");
        seedClass(dsl, CLASSES, SVC, "CLASS");
        seedMethod(dsl, CLASSES, SVC, "get", "()V");
        seedMethodParameter(dsl, CLASSES, SVC, "get", "()V", 0, paramName, Map.of());
    }

    private static List<String> verdicts(DSLContext dsl) {
        return rows(dsl).map(NodeIdDecodeDefectTest::render);
    }

    private static Result<Record> rows(DSLContext dsl) {
        derive(dsl);
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        var v = INTENT_NODE_ID_DECODE_DEFECT;
        return dsl.select(v.fields())
            .from(v)
            .where(v.GRAPH_NAME.eq(graphName))
            .orderBy(v.USE_SITE, v.NODE_TYPE_NAME)
            .fetch();
    }

    /** The coordinate, what it names, which precondition stopped it, and the operands it quotes. */
    private static String render(Record row) {
        var v = INTENT_NODE_ID_DECODE_DEFECT;
        return row.get(v.USE_SITE) + " " + row.get(v.NODE_TYPE_NAME) + " "
            + row.get(v.VERDICT) + " arity " + row.get(v.ARITY)
            + " column " + orNone(row.get(v.KEY_COLUMN_NAME))
            + " " + orNone(row.get(v.COLUMN_JAVA_TYPE))
            + " slot " + orNone(row.get(v.SLOT_JAVA_TYPE));
    }

    private static String orNone(Object value) {
        return value == null ? "(none)" : value.toString();
    }

    /** The destination the decode resolved, read beside every verdict. */
    private static List<String> destinations(DSLContext dsl) {
        derive(dsl);
        var d = INTENT_NODE_ID_DECODE;
        return dsl.select(d.USE_SITE, d.NODE_TYPE_NAME, d.DESTINATION, d.ARITY)
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH))
            .orderBy(d.USE_SITE, d.NODE_TYPE_NAME)
            .fetch()
            .map(row -> row.get(d.USE_SITE) + " " + row.get(d.NODE_TYPE_NAME) + " "
                + row.get(d.DESTINATION) + " " + row.get(d.ARITY));
    }

    /** The pair-grain family's own verdicts, for the one case whose subject is the boundary. */
    private static List<String> argmappingVerdicts(DSLContext dsl) {
        derive(dsl);
        var p = INTENT_ARGMAPPING_PROJECTION_DEFECT;
        return dsl.select(p.USE_SITE, p.VERDICT)
            .from(p)
            .where(p.GRAPH_NAME.eq(GRAPH))
            .orderBy(p.USE_SITE, p.VERDICT)
            .fetch()
            .map(row -> row.get(p.USE_SITE) + " " + row.get(p.VERDICT));
    }
}
