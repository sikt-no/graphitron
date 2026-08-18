package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CLASS_ASSIGNABLE;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSupertype;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_class_assignable} returns: which types a class in the census can stand in
 * for, once the declarations it makes are followed as far as they go.
 *
 * <p>The census here is stated row by row rather than scanned off compiled fixtures, which is the
 * opposite choice from the rule over {@code intent_class_member_slot} and made on the same test.
 * That rule reads a class's declared form, so a fixture stating its own would assert a census no
 * compiler produces. This rule reads nothing but the supertype edges, and a supertype edge is a
 * name and a clause, which is all a stated row is. What compiled fixtures could not arrange is
 * exactly what a closure has to get right: a chain that continues into another classpath entry's
 * declarations, a chain that ends at a name no entry declares, and a type two chains reach. That
 * the generator's scanner really produces these edges is pinned where it belongs, beside the
 * scanner.
 */
class ClassAssignableTest {

    // ===== The closure follows what the declarations say =====

    /** The one-hop case: a declared supertype is a row, both clauses alike. */
    @Test
    void aDeclaredSupertypeIsARow() {
        withCensus(dsl -> {
            assertThat(reaches(dsl, "app.FilmService")).contains("lib.Audited");
            assertThat(reaches(dsl, "lib.Audited")).contains("lib.Timestamped");
        });
    }

    /**
     * The chain continues into the entry that declares the next hop. A consumer class implementing
     * an interface a jar declares is the ordinary shape, and the hop joins on the name alone; the
     * rows stay under the subtype's own entry, the question being about a class standing there.
     */
    @Test
    void aChainClosesAcrossClasspathEntries() {
        withCensus(dsl -> {
            assertThat(reaches(dsl, "app.FilmService"))
                .containsExactly("java.io.Serializable", "lib.Audited", "lib.Timestamped");
            assertThat(dsl.select(INTENT_CLASS_ASSIGNABLE.SOURCE_NAME)
                .from(INTENT_CLASS_ASSIGNABLE)
                .where(INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq("app.FilmService"))
                .fetchSet(0, String.class))
                .as("every row carries the subtype's entry, not the entry that declared the hop")
                .containsExactly(APP);
        });
    }

    /**
     * A type two chains reach is one row. The pair is what the relation asserts, so an arity here
     * would be counting paths, and a consumer joining on it would multiply its own rows by however
     * many interfaces happened to converge.
     */
    @Test
    void aTypeReachedTwoWaysIsOneRow() {
        withCensus(dsl ->
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq("app.Auditable")
                    .and(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME.eq("lib.Timestamped"))))
                .isOne());
    }

    /**
     * The disclosed limit: the chain stops at the first name no entry declares. Nothing ships the
     * JDK as a classpath entry, so a class extending {@code java.util.ArrayList} reaches that name
     * and nothing above it, where a live loader would keep going. A reader has to take the absence
     * as not-known-to-be-assignable, never as not-assignable.
     */
    @Test
    void aChainEndsAtANameNoEntryDeclares() {
        withCensus(dsl ->
            assertThat(reaches(dsl, "app.FilmList")).containsExactly("java.util.ArrayList"));
    }

    // ===== What the closure does not say =====

    /**
     * No class is assignable to itself here. The closure is over declarations and identity is not
     * one, and the row could not be stated where it would matter anyway: the reflexive pair for
     * {@code java.util.List} would need a classpath entry for {@code java.util.List}, which is
     * exactly the kind of name no scan reaches. So a container test compares the name itself and
     * reads this relation for everything above it.
     */
    @Test
    void aClassIsNotAssignableToItself() {
        withCensus(dsl ->
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)))
                .isZero());
    }

    /** A class that declares nothing contributes nothing, rather than a row naming Object. */
    @Test
    void aClassThatDeclaresNothingReachesNothing() {
        withCensus(dsl -> assertThat(reaches(dsl, "app.Plain")).isEmpty());
    }

    // ===== Partition =====

    /**
     * The relation carries no graph column, so the partition is the reader's semi-join through
     * {@code store_graph_source}, as on the sibling census derivation.
     */
    @Test
    void aGraphThatReadNoneOfTheseEntriesSeesNothing() {
        withCensus(dsl -> {
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                new StoreHandle(dsl, GRAPH).reads(INTENT_CLASS_ASSIGNABLE.SOURCE_NAME)))
                .isPositive();
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                new StoreHandle(dsl, "other").reads(INTENT_CLASS_ASSIGNABLE.SOURCE_NAME)))
                .isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";

    /**
     * Four consumer classes over one library, arranged so each case has a chain of its own: one
     * reaching through the library into a name nothing declares, one reaching a library type by two
     * routes, one stopping at the first unscanned name, and one declaring nothing at all.
     *
     * <p>Every name is the fully qualified one a scan would write, on both sides of a supertype
     * edge and in the class row itself. The closure joins a declared name against a declaring
     * class's name, so a census spelling either differently would assert a hop no classfile offers.
     */
    private static void withCensus(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, APP, "DIRECTORY");
            seedSource(dsl, LIB, "JAR");
            seedGraphSource(dsl, GRAPH, APP);
            seedGraphSource(dsl, GRAPH, LIB);

            seedClass(dsl, APP, "app.FilmService", "CLASS");
            seedClass(dsl, APP, "app.Auditable", "CLASS");
            seedClass(dsl, APP, "app.FilmList", "CLASS");
            seedClass(dsl, APP, "app.Plain", "CLASS");
            seedClass(dsl, LIB, "lib.Audited", "INTERFACE");
            seedClass(dsl, LIB, "lib.Logged", "INTERFACE");
            seedClass(dsl, LIB, "lib.Timestamped", "INTERFACE");

            seedSupertype(dsl, APP, "app.FilmService", "lib.Audited", "IMPLEMENTS");
            seedSupertype(dsl, APP, "app.Auditable", "lib.Audited", "IMPLEMENTS");
            seedSupertype(dsl, APP, "app.Auditable", "lib.Logged", "IMPLEMENTS");
            seedSupertype(dsl, APP, "app.FilmList", "java.util.ArrayList", "EXTENDS");
            seedSupertype(dsl, LIB, "lib.Audited", "lib.Timestamped", "EXTENDS");
            seedSupertype(dsl, LIB, "lib.Logged", "lib.Timestamped", "EXTENDS");
            seedSupertype(dsl, LIB, "lib.Timestamped", "java.io.Serializable", "IMPLEMENTS");

            body.accept(dsl);
        });
    }

    /** Every type the named class can stand in for, ordered so a case can state the whole set. */
    private static List<String> reaches(DSLContext dsl, String className) {
        return dsl.select(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)
            .from(INTENT_CLASS_ASSIGNABLE)
            .where(INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq(className))
            .orderBy(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)
            .fetch(0, String.class);
    }
}
