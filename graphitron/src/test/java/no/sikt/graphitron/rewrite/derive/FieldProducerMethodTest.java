package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
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
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_REFERENCE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the producer pair: {@code intent_field_producer_reference},
 * the method an {@code @service} or {@code @externalField} names, and
 * {@code intent_field_producer_method}, which of the census's methods that resolves to.
 *
 * <p>The SDL is captured for real and the census is hand-built, which is the split the two sides
 * earn separately. A directive application is a fact capture produces, so writing the rows by hand
 * could assert a coordinate no schema authors. The census side states nothing but a class name, a
 * method name and a descriptor, which is all a reference is matched against, and building it by
 * hand is what lets one fixture hold an overload, a class the scan never reached and a second
 * classpath entry declaring the same class. The scan's own production of those rows is pinned in
 * {@code ClasspathScannerTest}.
 *
 * <p>Half of these cases assert that a coordinate produces no row. That is the relation's claim
 * rather than a gap in it: a reference the census cannot match is unresolved, and the boundary
 * between the two causes of absence (a class outside the census, a class declaring no such method)
 * is a fact a reader has to be able to reach.
 */
@PipelineTier
class FieldProducerMethodTest {

    @TempDir
    Path tmp;

    // ===== The reference resolves =====

    /** The ordinary case: a service reference names one method and the row carries its descriptor. */
    @Test
    void aServiceReferenceResolvesToItsMethod() {
        withCapturedStore(dsl -> {
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
        withCapturedStore(dsl -> {
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
        withCapturedStore(dsl ->
            assertThat(rowsAt(dsl, "Film", "isEnglish"))
                .extracting(r -> r.getMethodName())
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
        withCapturedStore(dsl -> {
            var rows = rowsAt(dsl, "Query", "ranked");
            assertThat(rows).extracting(r -> r.getDescriptor())
                .containsExactlyInAnyOrder("(I)Ljava/util/List;", "(Ljava/lang/String;)Ljava/util/List;");
            assertThat(rows).extracting(r -> r.getCandidates()).containsOnly(2);
        });
    }

    /**
     * The first cause of absence: the class is in the census and declares no method of that name.
     * The join that tells this apart from the second cause is the assertion, absence alone saying
     * only that the reference did not resolve.
     */
    @Test
    void aNameTheClassDoesNotDeclareResolvesToNothing() {
        withCapturedStore(dsl -> {
            assertThat(rowsAt(dsl, "Query", "missing")).isEmpty();
            assertThat(dsl.fetchCount(JVM_CLASS, JVM_CLASS.CLASS_NAME.eq("app.FilmService")))
                .as("the class is present, so the absent row is about the method name")
                .isOne();
        });
    }

    /** The second cause: nothing the graph read declares the class at all. */
    @Test
    void aClassOutsideTheCensusResolvesToNothing() {
        withCapturedStore(dsl -> {
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
        withCapturedStore(dsl -> {
            assertThat(referencesAt(dsl, "Query", "unscanned"))
                .extracting(r -> r.getClassName() + "#" + r.getMethodName())
                .containsExactly("app.NotScanned#findAll");
            assertThat(referencesAt(dsl, "Query", "missing"))
                .extracting(r -> r.getMethodName())
                .containsExactly("absent");
        });
    }

    /**
     * The fallback is applied once, where the reference is stated, so the resolution above inherits it
     * rather than repeating it and no reader has to know the default at all.
     */
    @Test
    void theOmittedMethodFallbackIsAppliedInTheReference() {
        withCapturedStore(dsl ->
            assertThat(referencesAt(dsl, "Film", "isEnglish"))
                .extracting(r -> r.getDeclaredVia() + ":" + r.getMethodName())
                .containsExactly("EXTERNAL_FIELD:isEnglish"));
    }

    /**
     * A service naming no method resolves to nothing rather than falling back to the field name.
     * The two directives differ here and the difference is authored, not incidental: the fallback
     * is the external field's contract alone.
     */
    @Test
    void aServiceThatNamesNoMethodResolvesToNothing() {
        withCapturedStore(dsl -> assertThat(rowsAt(dsl, "Query", "unnamed")).isEmpty());
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
        withCapturedStore(dsl -> {
            capture(dsl, SIBLING, tmp.resolve("sibling"),
                List.of(reference(OTHER, "app.FilmService",
                    method("findAll", "()Ljava/util/List;"))));

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

    private static final String GRAPH = "FieldProducerMethodTest";
    private static final String SIBLING = "FieldProducerMethodTestSibling";

    private static final String APP = "app/target/classes";
    private static final String LIB = "lib.jar";
    private static final String OTHER = "other/target/classes";

    /**
     * One reference per case: one that resolves, one overloaded, one naming a method the class
     * does not declare, one naming a class nothing scanned, one naming no method at all, and the
     * external field's two spellings of its own reference.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "app.FilmService", method: "findAll"})
            ranked: [Film] @service(service: {className: "app.FilmService", method: "byRank"})
            missing: [Film] @service(service: {className: "app.FilmService", method: "absent"})
            unscanned: [Film] @service(service: {className: "app.NotScanned", method: "findAll"})
            unnamed: [Film] @service(service: {className: "app.FilmService"})
        }
        type Film {
            title: String
            rating: String @externalField(reference: {className: "app.FilmFields", method: "rating"})
            isEnglish: Boolean @externalField(reference: {className: "app.FilmFields"})
        }
        """;

    /**
     * Two classes on two entries. {@code byRank} is declared twice, which is the one shape the
     * census has to hold for the arity to mean anything, and the descriptors are what tell the two
     * rows apart.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(
            reference(APP, "app.FilmService",
                method("findAll", "()Ljava/util/List;"),
                method("byRank", "(I)Ljava/util/List;"),
                method("byRank", "(Ljava/lang/String;)Ljava/util/List;")),
            reference(LIB, "app.FilmFields",
                method("rating", "()Lorg/jooq/Field;"),
                method("isEnglish", "()Lorg/jooq/Field;")));
    }

    /**
     * The reference's own name is the class name, which is what a scan writes into both, so a
     * census spelling it otherwise would state a class no classfile produces.
     */
    private static CompletionData.ExternalReference reference(
        String sourceName, String className, CompletionData.Method... methods) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(methods), List.of(), List.of(), "CLASS", sourceName, List.of());
    }

    /** Only the name and the descriptor are matched against, so nothing else is stated. */
    private static CompletionData.Method method(String name, String descriptor) {
        return new CompletionData.Method(name, "List", "", List.of(), false, descriptor, "List");
    }

    private static List<no.sikt.graphitron.model.tables.records.IntentFieldProducerMethodRecord>
        rowsAt(DSLContext dsl, String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_PRODUCER_METHOD)
            .where(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME.eq(fieldName)))
            .fetch();
    }

    private static List<no.sikt.graphitron.model.tables.records.IntentFieldProducerReferenceRecord>
        referencesAt(DSLContext dsl, String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_PRODUCER_REFERENCE)
            .where(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(fieldName)))
            .fetch();
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        try (var store = GraphitronModelStore.open()) {
            capture(store.dsl(), GRAPH, tmp, census());
            body.accept(store.dsl());
        }
    }

    private static void capture(DSLContext dsl, String graphName, Path root,
                                List<CompletionData.ExternalReference> census) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var schemaFile = write(root, SDL);
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
        FactCapture.capture(dsl, new FactCapture.GraphIdentity(graphName, root),
            FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
            jooq, census, new NodeDeclaration(null));
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
