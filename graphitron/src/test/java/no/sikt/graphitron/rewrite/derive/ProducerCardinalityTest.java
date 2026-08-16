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
import static no.sikt.graphitron.model.Tables.INTENT_DECLARED_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_PRODUCER_CARDINALITY_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_producer_cardinality_conflict}: where a field
 * and the method producing its value disagree about how many.
 *
 * <p>Every case is a pair, one coordinate that disagrees beside one that agrees over the same
 * producer or the same SDL cardinality. A detection is only as good as its silence, and a rule that
 * reported every coordinate would pass any test that only looked at the rows it produced.
 *
 * <p>The comparison existed before this relation did, as a clause inside the walk whose result was
 * a decision to stop. What is new is that it is observable, so the cases here pin both the rows and
 * which way each disagreement runs.
 */
@PipelineTier
class ProducerCardinalityTest {

    @TempDir
    Path tmp;

    // ===== The two directions of disagreement =====

    /** A single-valued field whose producer hands back a collection. */
    @Test
    void aSingleFieldProducedByManyDisagrees() {
        withCapturedStore(dsl ->
            assertThat(conflict(dsl, "one")).containsExactly("SERVICE list=false many=true"));
    }

    /** The same disagreement the other way, which a rule comparing one direction would miss. */
    @Test
    void aListFieldProducedByOneDisagrees() {
        withCapturedStore(dsl ->
            assertThat(conflict(dsl, "many")).containsExactly("SERVICE list=true many=false"));
    }

    // ===== Agreement is silence =====

    /** Both ways of agreeing, over the same two producers the disagreements above are built on. */
    @Test
    void agreementProducesNoRow() {
        withCapturedStore(dsl -> {
            assertThat(conflict(dsl, "films")).as("a list from a collection").isEmpty();
            assertThat(conflict(dsl, "solo")).as("a single from a single").isEmpty();
        });
    }

    /**
     * A map follows its value rather than counting as a collection itself. Both coordinates are
     * single-valued and both producers return a map, so the map is not what decides either.
     */
    @Test
    void aMapFollowsItsValue() {
        withCapturedStore(dsl -> {
            assertThat(conflict(dsl, "mapped")).as("a map to one value delivers one").isEmpty();
            assertThat(conflict(dsl, "mappedMany"))
                .containsExactly("SERVICE list=false many=true");
        });
    }

    /**
     * A raw container delivers one of itself, so a single-valued field standing on it agrees. The
     * descent never happened and there is nothing to multiply.
     */
    @Test
    void aRawContainerAgreesWithASingleField() {
        withCapturedStore(dsl -> assertThat(conflict(dsl, "raw")).isEmpty());
    }

    // ===== Where the comparison cannot be made =====

    /**
     * A producer whose declared return names no class at its root has no row, rather than a row
     * asserting agreement. There is no peel to compare against, and a primitive return is a
     * different complaint from a cardinality one.
     */
    @Test
    void aProducerNamingNoClassHasNoRowEitherWay() {
        withCapturedStore(dsl -> {
            assertThat(conflict(dsl, "counted")).isEmpty();
            assertThat(dsl.fetchCount(INTENT_DECLARED_TYPE_ELEMENT,
                INTENT_DECLARED_TYPE_ELEMENT.CLASS_NAME.eq("app.FilmService")
                    .and(INTENT_DECLARED_TYPE_ELEMENT.OWNER_NAME.eq("count"))))
                .as("and the absence is the peel's, not this relation's")
                .isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "ProducerCardinalityTest";
    private static final String APP = "app/target/classes";

    /**
     * One producer per delivery shape, each read at both SDL cardinalities where the pairing is
     * what the case turns on.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "app.FilmService", method: "findAll"})
            one: Film @service(service: {className: "app.FilmService", method: "findAll"})
            solo: Film @service(service: {className: "app.FilmService", method: "findOne"})
            many: [Film] @service(service: {className: "app.FilmService", method: "findOne"})
            mapped: Film @service(service: {className: "app.FilmService", method: "byKey"})
            mappedMany: Film @service(service: {className: "app.FilmService", method: "byKeyMany"})
            raw: Film @service(service: {className: "app.FilmService", method: "raw"})
            counted: Int @service(service: {className: "app.FilmService", method: "count"})
        }
        type Film { title: String }
        """;

    private static List<CompletionData.ExternalReference> census() {
        return List.of(
            reference(APP, "app.FilmService",
                method("findAll", "()Ljava/util/List;",
                    ref("", "java.util.List"), ref("0", "app.FilmRecord")),
                method("findOne", "()Lapp/FilmRecord;", ref("", "app.FilmRecord")),
                method("byKey", "()Ljava/util/Map;",
                    ref("", "java.util.Map"), ref("0", "java.lang.String"),
                    ref("1", "app.FilmRecord")),
                method("byKeyMany", "()Ljava/util/Map;",
                    ref("", "java.util.Map"), ref("0", "java.lang.String"),
                    ref("1", "java.util.List"), ref("1.0", "app.FilmRecord")),
                method("raw", "()Ljava/util/List;", ref("", "java.util.List")),
                method("count", "()I")),
            record(APP, "app.FilmRecord", component("title", ref("", "java.lang.String"))));
    }

    private static CompletionData.ExternalReference reference(
        String sourceName, String className, CompletionData.Method... methods) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(methods), List.of(), List.of(), "CLASS", sourceName, List.of());
    }

    private static CompletionData.ExternalReference record(
        String sourceName, String className, CompletionData.RecordComponent... components) {
        return new CompletionData.ExternalReference(className, className, "",
            List.of(), List.of(components), List.of(), "RECORD", sourceName, List.of());
    }

    private static CompletionData.Method method(
        String name, String descriptor, CompletionData.TypeRef... refs) {
        return new CompletionData.Method(name, "Object", "", List.of(), false, descriptor,
            "Object", List.of(refs));
    }

    private static CompletionData.RecordComponent component(
        String name, CompletionData.TypeRef... refs) {
        return new CompletionData.RecordComponent(name, "Object", "Object", List.of(refs));
    }

    private static CompletionData.TypeRef ref(String path, String referencedClass) {
        return new CompletionData.TypeRef(path, referencedClass, "NONE");
    }

    /** The row with both halves of the disagreement, so a case states which way it runs. */
    private static List<String> conflict(DSLContext dsl, String fieldName) {
        var c = INTENT_PRODUCER_CARDINALITY_CONFLICT;
        return dsl.select(c.DECLARED_VIA, c.FIELD_IS_LIST, c.PRODUCER_DELIVERS_MANY)
            .from(c)
            .where(c.GRAPH_NAME.eq(GRAPH).and(c.TYPE_NAME.eq("Query"))
                .and(c.FIELD_NAME.eq(fieldName)))
            .fetch(r -> r.value1() + " list=" + r.value2() + " many=" + r.value3());
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, SDL);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry,
                TestSchemaHelper.attribution(schemaFile), jooq, census(),
                new NodeDeclaration(null));
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
