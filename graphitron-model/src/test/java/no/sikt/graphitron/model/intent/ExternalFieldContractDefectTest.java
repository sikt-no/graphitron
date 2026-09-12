package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_EXTERNAL_FIELD_CONTRACT_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalFieldMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_external_field_contract_defect} returns: where an {@code @externalField} names
 * a method that exists and cannot do the job.
 *
 * <p>Every case is a pair, an accused coordinate beside a silent one over the same class, because a
 * rule that accused every reference would pass any test that only read the rows it produced. The
 * silences are the harder half here: three of them have different causes, and a reader has to be
 * able to tell a method that was admitted from one the arm never read.
 *
 * <p>Both halves are stated as rows, which is what lets one fixture hold an admitted method, a
 * declared one that was not admitted, an overloaded name that is both at once, and a reference into
 * a jar. Whether a given method satisfies the directive is the arm's decision and not this
 * relation's, so a fixture states the decision rather than the classfile it would have been read
 * from.
 */
class ExternalFieldContractDefectTest {

    // ===== The accusation =====

    /**
     * The case the relation exists for: the reference resolves, and the arm did not admit what it
     * resolved to. {@code fromName} takes a String and returns a Field, which is a lifting method's
     * whole observable shape and is not one.
     */
    @Test
    void aResolvedMethodTheArmDidNotAdmitIsAccused() {
        withReferences(dsl -> assertThat(defects(dsl, "byName"))
            .containsExactly("app.FilmFields fromName (Ljava/lang/String;)Lorg/jooq/Field;"));
    }

    // ===== Silence, and its four causes =====

    /** An admitted method: the reference resolves and the contract holds. */
    @Test
    void anAdmittedMethodIsNotAccused() {
        withReferences(dsl -> assertThat(defects(dsl, "title")).isEmpty());
    }

    /**
     * The population is one arm of the reference relation. The same unadmitted method named by
     * {@code @service} is no complaint of this relation's, that directive having no such contract.
     */
    @Test
    void aServiceReferenceToTheSameMethodIsNotAccused() {
        withReferences(dsl -> {
            assertThat(defects(dsl, "served")).isEmpty();
            assertThat(resolves(dsl, "served"))
                .as("and the reference did resolve, so the silence is the population's")
                .isTrue();
        });
    }

    /**
     * A reference into a jar. The arm reads the modules a build compiles, so a method it never read
     * is neither admitted nor accused, and the silence is the relation's stated limit rather than a
     * verdict that the method is fine.
     */
    @Test
    void aReferenceIntoAJarIsNeitherAdmittedNorAccused() {
        withReferences(dsl -> {
            assertThat(defects(dsl, "vendor")).isEmpty();
            assertThat(resolves(dsl, "vendor"))
                .as("and the reference did resolve, so the silence is the scoping's")
                .isTrue();
        });
    }

    /**
     * An entry whose origin was never recorded. The column is nullable and absence is
     * not-classified rather than not-reactor, so the scoping tests for the reactor positively; a
     * negated test would accuse this coordinate.
     */
    @Test
    void anEntryWithNoRecordedOriginIsNotAccused() {
        withReferences(dsl -> {
            assertThat(defects(dsl, "unrecorded")).isEmpty();
            assertThat(resolves(dsl, "unrecorded"))
                .as("and the reference did resolve, so the silence is the null origin's")
                .isTrue();
        });
    }

    /** A reference matching no method at all: there is nothing to accuse. */
    @Test
    void aReferenceThatResolvesToNothingIsNotAccused() {
        withReferences(dsl -> {
            assertThat(defects(dsl, "missing")).isEmpty();
            assertThat(resolves(dsl, "missing")).isFalse();
        });
    }

    // ===== Overloads =====

    /**
     * A reference matching two methods is two resolutions, and this relation judges each on its
     * own: the admitted overload draws no row and the other one does. Which method the reference
     * means is the resolution's open question and not this relation's to settle.
     */
    @Test
    void anOverloadedNameIsJudgedPerOverload() {
        withReferences(dsl -> assertThat(defects(dsl, "rate"))
            .containsExactly("app.FilmFields rate (Ljava/lang/String;)Lorg/jooq/Field;"));
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String APP = "app/target/classes";
    private static final String UNRECORDED = "legacy/target/classes";
    private static final String JAR = "/libs/vendor.jar";
    private static final String FIELDS = "app.FilmFields";
    private static final String LEGACY = "legacy.LegacyFields";
    private static final String VENDOR = "vendor.VendorFields";

    private static final String FROM_TABLE = "(Lapp/tables/Film;)Lorg/jooq/Field;";
    private static final String FROM_STRING = "(Ljava/lang/String;)Lorg/jooq/Field;";

    /**
     * One class in the reactor declaring four methods, of which the arm admitted two, beside a jar
     * class and an origin-less entry that the arm read neither of.
     *
     * <p>{@code rate} is declared twice under one name, once at each descriptor, and admitted at
     * one of them. That pair is the overload case and is also what keeps the relation honest about
     * its key: a rule correlating on the method name alone would let the admitted overload excuse
     * the other.
     */
    private static void withReferences(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY", "PROJECT");
            seedSource(dsl, UNRECORDED, "DIRECTORY", null);
            seedSource(dsl, JAR, "JAR", "DECLARED");
            seedGraphSource(dsl, GRAPH, APP);
            seedGraphSource(dsl, GRAPH, UNRECORDED);
            seedGraphSource(dsl, GRAPH, JAR);
            seedType(dsl, GRAPH, "Film", "OBJECT");
            seedType(dsl, GRAPH, "String", "SCALAR");

            seedClass(dsl, APP, FIELDS, "CLASS");
            seedMethod(dsl, APP, FIELDS, "titleUpper", FROM_TABLE);
            seedMethod(dsl, APP, FIELDS, "fromName", FROM_STRING);
            seedMethod(dsl, APP, FIELDS, "rate", FROM_TABLE);
            seedMethod(dsl, APP, FIELDS, "rate", FROM_STRING);
            seedExternalFieldMethod(dsl, APP, FIELDS, "titleUpper", FROM_TABLE, "app.tables.Film");
            seedExternalFieldMethod(dsl, APP, FIELDS, "rate", FROM_TABLE, "app.tables.Film");

            seedClass(dsl, UNRECORDED, LEGACY, "CLASS");
            seedMethod(dsl, UNRECORDED, LEGACY, "fromName", FROM_STRING);

            seedClass(dsl, JAR, VENDOR, "CLASS");
            seedMethod(dsl, JAR, VENDOR, "fromName", FROM_STRING);

            lifts(dsl, "title", FIELDS, "titleUpper");
            lifts(dsl, "byName", FIELDS, "fromName");
            lifts(dsl, "rate", FIELDS, "rate");
            lifts(dsl, "missing", FIELDS, "absent");
            lifts(dsl, "unrecorded", LEGACY, "fromName");
            lifts(dsl, "vendor", VENDOR, "fromName");

            // The same unadmitted method under the other directive, which this relation ignores.
            seedField(dsl, GRAPH, "Film", "served", "String", false);
            seedService(dsl, GRAPH, "Film", "served", FIELDS, "fromName");

            body.accept(dsl);
        });
    }

    /** A {@code Film} field whose value an {@code @externalField} reference lifts. */
    private static void lifts(DSLContext dsl, String fieldName, String className, String method) {
        seedField(dsl, GRAPH, "Film", fieldName, "String", false);
        seedExternalField(dsl, GRAPH, "Film", fieldName, className, method);
    }

    /** The accused method, named so a case reads which resolution drew the row. */
    private static List<String> defects(DSLContext dsl, String fieldName) {
        var d = INTENT_EXTERNAL_FIELD_CONTRACT_DEFECT;
        return dsl.select(d.CLASS_NAME, d.METHOD_NAME, d.DESCRIPTOR)
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH).and(d.TYPE_NAME.eq("Film"))
                .and(d.FIELD_NAME.eq(fieldName)))
            .orderBy(d.CLASS_NAME, d.METHOD_NAME, d.DESCRIPTOR)
            .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3());
    }

    /** Whether the reference matched any method at all, which separates the causes of a silence. */
    private static boolean resolves(DSLContext dsl, String fieldName) {
        var p = INTENT_FIELD_PRODUCER_METHOD;
        return dsl.fetchCount(p, p.GRAPH_NAME.eq(GRAPH).and(p.TYPE_NAME.eq("Film"))
            .and(p.FIELD_NAME.eq(fieldName))) > 0;
    }
}
