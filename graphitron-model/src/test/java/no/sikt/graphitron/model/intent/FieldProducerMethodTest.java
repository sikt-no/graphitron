package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.tables.records.IntentFieldProducerMethodRecord;
import no.sikt.graphitron.model.tables.records.IntentFieldProducerReferenceRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_REFERENCE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the producer pair returns: {@code intent_field_producer_reference}, the method an
 * {@code @service} or {@code @externalField} names, and {@code intent_field_producer_method}, which
 * of the census's methods that resolves to.
 *
 * <p>Both sides are stated as rows, because both sides are names. A reference is a class name and a
 * method name exactly as an author spelled them, and the census matches a reference on a class name,
 * a method name and a descriptor and on nothing else. Stating them is what lets one fixture hold an
 * overload, a class no scan reached, a class declared by a second classpath entry and a directive
 * naming no method at all, which are the four shapes the resolution's answers are made of. That a
 * real capture writes the directive rows and a real scan writes the census rows is pinned beside
 * each of those, in the module that does the writing.
 *
 * <p>Half of these cases assert that a coordinate produces no row. That is the relation's claim
 * rather than a gap in it: a reference the census cannot match is unresolved, and the boundary
 * between the two causes of absence (a class outside the census, a class declaring no such method)
 * is a fact a reader has to be able to reach.
 */
class FieldProducerMethodTest {

    // ===== The reference resolves =====

    /** The ordinary case: a service reference names one method and the row carries its descriptor. */
    @Test
    void aServiceReferenceResolvesToItsMethod() {
        withProducers(dsl -> {
            var rows = rowsAt(dsl, "Query", "films");
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getDeclaredVia()).isEqualTo("SERVICE");
            assertThat(row.getSourceName()).isEqualTo(APP);
            assertThat(row.getClassName()).isEqualTo("app.FilmService");
            assertThat(row.getMethodName()).isEqualTo("findAll");
            assertThat(row.getDescriptor()).isEqualTo("()Ljava/util/List;");
            assertThat(row.getCandidates()).isOne();
        });
    }

    /**
     * The second arm, and the reason a row says which directive named it: the same shape of
     * reference under a different clause, reaching a class on a different classpath entry.
     */
    @Test
    void anExternalFieldReferenceResolvesToItsMethod() {
        withProducers(dsl -> {
            var rows = rowsAt(dsl, "Film", "rating");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getDeclaredVia()).isEqualTo("EXTERNAL_FIELD");
            assertThat(rows.getFirst().getSourceName()).isEqualTo(LIB);
            assertThat(rows.getFirst().getMethodName()).isEqualTo("rating");
        });
    }

    /**
     * The fallback the base relation's comment defers to a derivation, landing here: an
     * {@code @externalField} with no method argument names the SDL field's own name. Its
     * {@code @service} sibling has none, which the case below pins.
     */
    @Test
    void anOmittedExternalFieldMethodFallsBackToTheFieldName() {
        withProducers(dsl ->
            assertThat(rowsAt(dsl, "Film", "isEnglish"))
                .extracting(IntentFieldProducerMethodRecord::getMethodName)
                .containsExactly("isEnglish"));
    }

    // ===== What the resolution refuses to decide =====

    /**
     * An overloaded name is every match and a count, never a pick. The walk this replaces takes
     * whichever method the reflection API hands back first, in an order the JVM does not specify;
     * the arity is what a rejection needs and what a reader that must choose reads.
     */
    @Test
    void anOverloadedNameIsEveryMatchAndAnArity() {
        withProducers(dsl -> {
            var rows = rowsAt(dsl, "Query", "ranked");
            assertThat(rows).extracting(IntentFieldProducerMethodRecord::getDescriptor)
                .containsExactlyInAnyOrder("(I)Ljava/util/List;", "(Ljava/lang/String;)Ljava/util/List;");
            assertThat(rows).extracting(IntentFieldProducerMethodRecord::getCandidates).containsOnly(2);
        });
    }

    /**
     * The first cause of absence: the class is in the census and declares no method of that name.
     * The join that tells this apart from the second cause is the assertion, absence alone saying
     * only that the reference did not resolve.
     */
    @Test
    void aNameTheClassDoesNotDeclareResolvesToNothing() {
        withProducers(dsl -> {
            assertThat(rowsAt(dsl, "Query", "missing")).isEmpty();
            assertThat(dsl.fetchCount(JVM_CLASS, JVM_CLASS.CLASS_NAME.eq("app.FilmService")))
                .as("the class is present, so the absent row is about the method name")
                .isOne();
        });
    }

    /** The second cause: nothing the graph read declares the class at all. */
    @Test
    void aClassOutsideTheCensusResolvesToNothing() {
        withProducers(dsl -> {
            assertThat(rowsAt(dsl, "Query", "unscanned")).isEmpty();
            assertThat(dsl.fetchCount(JVM_CLASS, JVM_CLASS.CLASS_NAME.eq("app.NotScanned")))
                .isZero();
        });
    }

    // ===== What survives an unresolved reference =====

    /**
     * The reason the reference is a relation of its own: both causes of absence above leave the
     * authored reference standing, and a surface naming the declaration a field binds to wants it.
     * A class the scan skipped is ordinary rather than exotic, so resolving only what the census
     * matched would decline at coordinates whose method the author named plainly.
     */
    @Test
    void anUnresolvedReferenceIsStillAReference() {
        withProducers(dsl -> {
            assertThat(referencesAt(dsl, "Query", "unscanned"))
                .extracting(r -> r.getClassName() + "#" + r.getMethodName())
                .containsExactly("app.NotScanned#findAll");
            assertThat(referencesAt(dsl, "Query", "missing"))
                .extracting(IntentFieldProducerReferenceRecord::getMethodName)
                .containsExactly("absent");
        });
    }

    /**
     * The fallback is applied once, where the reference is stated, so the resolution above inherits it
     * rather than repeating it and no reader has to know the default at all.
     */
    @Test
    void theOmittedMethodFallbackIsAppliedInTheReference() {
        withProducers(dsl ->
            assertThat(referencesAt(dsl, "Film", "isEnglish"))
                .extracting(r -> r.getDeclaredVia() + ":" + r.getMethodName())
                .containsExactly("EXTERNAL_FIELD:isEnglish"));
    }

    /**
     * A service naming no method resolves to nothing rather than falling back to the field name.
     * The two directives differ here and the difference is authored, not incidental: the fallback
     * is the external field's contract alone.
     *
     * <p>The reference is where that difference lives, and where the absence has to be read: give
     * the service arm the fallback and the coordinate names a method, whether or not the census
     * then matches it. So the resolution's silence alone would be satisfied by a fixture whose
     * field name happens to match nothing, which is every fixture that did not set out to.
     */
    @Test
    void aServiceThatNamesNoMethodResolvesToNothing() {
        withProducers(dsl -> {
            assertThat(rowsAt(dsl, "Query", "unnamed")).isEmpty();
            assertThat(referencesAt(dsl, "Query", "unnamed"))
                .as("and it names nothing to resolve, rather than naming the field")
                .isEmpty();
        });
    }

    // ===== Partition =====

    /**
     * Two graphs in one store, each with its own classpath entry declaring the same class and
     * method. The census join runs through store_graph_source, so neither graph's reference
     * resolves against the other's entry; without that hop each coordinate would carry both rows
     * and report an ambiguity neither graph has.
     */
    @Test
    void siblingGraphsResolveThroughTheirOwnMembership() {
        withProducers(dsl -> {
            seedGraph(dsl, SIBLING);
            seedSource(dsl, OTHER, "DIRECTORY");
            seedGraphSource(dsl, SIBLING, OTHER);
            seedClass(dsl, OTHER, "app.FilmService", "CLASS");
            seedMethod(dsl, OTHER, "app.FilmService", "findAll", "()Ljava/util/List;");
            seedType(dsl, SIBLING, "Film", "OBJECT");
            seedField(dsl, SIBLING, "Query", "films", "Film", true);
            seedService(dsl, SIBLING, "Query", "films", "app.FilmService", "findAll");

            assertThat(dsl.select(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.SOURCE_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.CANDIDATES)
                .from(INTENT_FIELD_PRODUCER_METHOD)
                .where(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME.eq("Query")
                    .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME.eq("films")))
                .fetch())
                .extracting(r -> r.value1() + " " + r.value2() + " " + r.value3())
                .containsExactlyInAnyOrder(GRAPH + " " + APP + " 1", SIBLING + " " + OTHER + " 1");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String SIBLING = "sibling";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";
    private static final String OTHER = "other/target/classes";

    /**
     * One reference per case: one that resolves, one overloaded, one naming a method the class
     * does not declare, one naming a class nothing scanned, one naming no method at all, and the
     * external field's two spellings of its own reference.
     *
     * <p>Two classes on two entries answer them. {@code byRank} is declared twice, which is the one
     * shape the census has to hold for the arity to mean anything, and the descriptors are what
     * tell the two rows apart.
     */
    private static void withProducers(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedSource(dsl, LIB, "JAR");
            seedGraphSource(dsl, GRAPH, APP);
            seedGraphSource(dsl, GRAPH, LIB);

            seedClass(dsl, APP, "app.FilmService", "CLASS");
            seedMethod(dsl, APP, "app.FilmService", "findAll", "()Ljava/util/List;");
            seedMethod(dsl, APP, "app.FilmService", "byRank", "(I)Ljava/util/List;");
            seedMethod(dsl, APP, "app.FilmService", "byRank", "(Ljava/lang/String;)Ljava/util/List;");
            seedClass(dsl, LIB, "app.FilmFields", "CLASS");
            seedMethod(dsl, LIB, "app.FilmFields", "rating", "()Lorg/jooq/Field;");
            seedMethod(dsl, LIB, "app.FilmFields", "isEnglish", "()Lorg/jooq/Field;");

            seedType(dsl, GRAPH, "Film", "OBJECT");
            service(dsl, "films", "app.FilmService", "findAll");
            service(dsl, "ranked", "app.FilmService", "byRank");
            service(dsl, "missing", "app.FilmService", "absent");
            service(dsl, "unscanned", "app.NotScanned", "findAll");
            service(dsl, "unnamed", "app.FilmService", null);

            seedField(dsl, GRAPH, "Film", "rating");
            seedExternalField(dsl, GRAPH, "Film", "rating", "app.FilmFields", "rating");
            seedField(dsl, GRAPH, "Film", "isEnglish");
            seedExternalField(dsl, GRAPH, "Film", "isEnglish", "app.FilmFields", null);

            body.accept(dsl);
        });
    }

    /** A {@code Query} field returning films, and the service reference that names its producer. */
    private static void service(DSLContext dsl, String fieldName, String className, String method) {
        seedField(dsl, GRAPH, "Query", fieldName, "Film", true);
        seedService(dsl, GRAPH, "Query", fieldName, className, method);
    }

    private static List<IntentFieldProducerMethodRecord> rowsAt(DSLContext dsl, String typeName,
                                                                String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_PRODUCER_METHOD)
            .where(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME.eq(fieldName)))
            .fetch();
    }

    private static List<IntentFieldProducerReferenceRecord> referencesAt(DSLContext dsl, String typeName,
                                                                         String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_PRODUCER_REFERENCE)
            .where(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(fieldName)))
            .fetch();
    }
}
