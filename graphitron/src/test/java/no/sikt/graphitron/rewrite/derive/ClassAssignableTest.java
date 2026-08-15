package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_ASSIGNABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_class_assignable}: which types a class on the
 * classpath can stand in for, once the declarations it makes are followed as far as they go.
 *
 * <p>The census here is built reference by reference rather than scanned off compiled fixtures,
 * which is the opposite choice from {@link ClassMemberSlotTest} and made on the same test. That
 * rule reads a class's declared form, so a fixture stating its own would assert a census no
 * compiler produces. This rule reads nothing but the supertype edges, and a supertype edge is a
 * name and a clause, which is all a hand-built reference states. What compiled fixtures could not
 * arrange is exactly what a closure has to get right: a chain that continues into another
 * classpath entry's declarations, a chain that ends at a name no entry declares, and a type two
 * chains reach. The scanner's own production of these edges is pinned where it belongs, in
 * {@code ClasspathScannerTest}.
 */
@PipelineTier
class ClassAssignableTest {

    @TempDir
    Path tmp;

    // ===== The closure follows what the declarations say =====

    /** The one-hop case: a declared supertype is a row, both clauses alike. */
    @Test
    void aDeclaredSupertypeIsARow() {
        withCapturedStore(dsl -> {
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
        withCapturedStore(dsl -> {
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
        withCapturedStore(dsl ->
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
        withCapturedStore(dsl ->
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
        withCapturedStore(dsl ->
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)))
                .isZero());
    }

    /** A class that declares nothing contributes nothing, rather than a row naming Object. */
    @Test
    void aClassThatDeclaresNothingReachesNothing() {
        withCapturedStore(dsl -> assertThat(reaches(dsl, "app.Plain")).isEmpty());
    }

    // ===== Partition =====

    /**
     * The relation carries no graph column, so the partition is the reader's semi-join through
     * {@code store_graph_source}, as on the sibling census derivation.
     */
    @Test
    void aGraphThatReadNoneOfTheseEntriesSeesNothing() {
        withCapturedStore(dsl -> {
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                new StoreHandle(dsl, GRAPH).reads(INTENT_CLASS_ASSIGNABLE.SOURCE_NAME)))
                .isPositive();
            assertThat(dsl.fetchCount(INTENT_CLASS_ASSIGNABLE,
                new StoreHandle(dsl, "other").reads(INTENT_CLASS_ASSIGNABLE.SOURCE_NAME)))
                .isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "ClassAssignableTest";

    /** The SDL is beside the point here; the subject is the classpath. */
    private static final String SDL = "type Query { placeholder: Int }\n";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";

    /**
     * Four consumer classes over one library, arranged so each case has a chain of its own: one
     * reaching through the library into a name nothing declares, one reaching a library type by two
     * routes, one stopping at the first unscanned name, and one declaring nothing at all.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(
            reference(APP, "app.FilmService", implementsOf("lib.Audited")),
            reference(APP, "app.Auditable", implementsOf("lib.Audited", "lib.Logged")),
            reference(APP, "app.FilmList", extendsOf("java.util.ArrayList")),
            reference(APP, "app.Plain"),
            reference(LIB, "lib.Audited", extendsOf("lib.Timestamped")),
            reference(LIB, "lib.Logged", extendsOf("lib.Timestamped")),
            reference(LIB, "lib.Timestamped", implementsOf("java.io.Serializable")));
    }

    /**
     * Every name here is the fully qualified one the scan would write, on both sides of a
     * supertype edge and in the reference's own name, which a scan sets to the class name rather
     * than the simple one. The closure joins a declared name against a declaring class's name, so
     * a census spelling either differently would assert a hop no classfile offers.
     */
    private static CompletionData.ExternalReference reference(
        String sourceName, String className, CompletionData.Supertype... supertypes) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(), List.of(), List.of(), "CLASS", sourceName, List.of(supertypes));
    }

    private static CompletionData.Supertype[] extendsOf(String... classNames) {
        return declared("EXTENDS", classNames);
    }

    private static CompletionData.Supertype[] implementsOf(String... classNames) {
        return declared("IMPLEMENTS", classNames);
    }

    private static CompletionData.Supertype[] declared(String clause, String... classNames) {
        return java.util.Arrays.stream(classNames)
            .map(className -> new CompletionData.Supertype(className, clause))
            .toArray(CompletionData.Supertype[]::new);
    }

    /** Every type the named class can stand in for, ordered so a case can state the whole set. */
    private static List<String> reaches(DSLContext dsl, String className) {
        return dsl.select(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)
            .from(INTENT_CLASS_ASSIGNABLE)
            .where(INTENT_CLASS_ASSIGNABLE.CLASS_NAME.eq(className))
            .orderBy(INTENT_CLASS_ASSIGNABLE.SUPERTYPE_NAME)
            .fetch(0, String.class);
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, SDL);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, census(), new NodeDeclaration(null));
            body.accept(store.dsl());
        }
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
