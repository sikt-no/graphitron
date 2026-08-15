package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
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

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_field_separate_fetch}: which fields are fetched
 * by a statement of their own rather than projected out of the enclosing SELECT.
 *
 * <p>The two marker arms are bound to the walk's own gathered delivery relation
 * ({@code GatheredFacts.delivery}), which is the independent evaluation of the same question: the
 * visitor reads the directive applications off the assembled schema, the view reads the captured
 * rows, and the two must name the same coordinates. The service and root arms have no walk-side
 * relation to bind to (the split they describe is a property of the emitted fetcher rather than a
 * gathered marker), so they are pinned against the authored coordinates directly.
 *
 * <p>Cases that pin <em>absence</em> carry their weight here: a field whose value comes out of its
 * parent's row is the population this relation exists to exclude, and the boundary is the claim.
 */
@PipelineTier
class SeparateFetchTest {

    @TempDir
    Path tmp;

    /**
     * The marker arms against the walk's delivery gather. Both markers on one schema, plus fields
     * carrying neither, so an over-reaching arm fails as loudly as a missing one.
     */
    @Test
    void theMarkerArmsNameTheCoordinatesTheDeliveryGatherDoes() {
        String sdl = """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                title: String
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                languageInline: Language @reference(path: [{key: "film_language_id_fkey"}])
                fannedLanguages: [Language!]! @tenantFanOut
            }
            type Query { films: [Film!]! }
            """;
        withCapturedStore(sdl, dsl -> {
            var gathered = gather(sdl);
            assertThat(gathered.delivery().rows())
                .as("the fixture authors both markers, so neither arm is vacuous")
                .isNotEmpty();
            assertThat(rules(dsl, "SPLIT_QUERY"))
                .containsExactlyInAnyOrderElementsOf(gathered.delivery().rows().stream()
                    .filter(r -> r.splitQuery())
                    .map(r -> r.parentTypeName() + "." + r.fieldName())
                    .toList());
            assertThat(rules(dsl, "TENANT_FAN_OUT"))
                .containsExactlyInAnyOrderElementsOf(gathered.delivery().rows().stream()
                    .filter(r -> r.tenantFanOut())
                    .map(r -> r.parentTypeName() + "." + r.fieldName())
                    .toList());
        });
    }

    /**
     * The non-root {@code @service} contract: the service fetches independently of the parent's
     * SELECT, which is why the split is required there rather than optional.
     */
    @Test
    void aNonRootServiceFieldIsFetchedIndependently() {
        withCapturedStore("""
            type Film @table(name: "film") {
                rating: String @service(service: {className: "%s", method: "get"})
            }
            type Query { films: [Film!]! }
            """.formatted(SERVICE_STUB),
            dsl -> assertThat(rulesFor(dsl, "Film", "rating")).containsExactly("SERVICE"));
    }

    /**
     * A root {@code @service} field is a root field, not a second service split: the root arm
     * already says its fetch is its own, and the service arm is masked there rather than adding a
     * second reason for the same thing.
     */
    @Test
    void aRootServiceFieldIsNamedOnceByTheRootArm() {
        withCapturedStore("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @service(service: {className: "%s", method: "get"})
            }
            """.formatted(SERVICE_STUB),
            dsl -> assertThat(rulesFor(dsl, "Query", "films")).containsExactly("ROOT_OPERATION"));
    }

    /** Every field of a bound root type, whether or not anything else reaches it. */
    @Test
    void everyRootFieldIsItsOwnEntryPoint() {
        withCapturedStore("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            type Mutation { touch(id: ID!): ID @mutation(typeName: DELETE, table: "film") }
            """, dsl -> {
            assertThat(rulesFor(dsl, "Query", "films")).containsExactly("ROOT_OPERATION");
            assertThat(rulesFor(dsl, "Mutation", "touch")).containsExactly("ROOT_OPERATION");
        });
    }

    /**
     * A coordinate several rules reach is several rows. The arity is the answer, so no rule wins a
     * precedence contest this view would have to hold an opinion about.
     */
    @Test
    void aCoordinateSeveralRulesReachIsSeveralRows() {
        withCapturedStore("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                languages: [Language!]! @splitQuery
                    @service(service: {className: "%s", method: "get"})
            }
            type Query { films: [Film!]! }
            """.formatted(SERVICE_STUB),
            dsl -> assertThat(rulesFor(dsl, "Film", "languages"))
                .containsExactlyInAnyOrder("SERVICE", "SPLIT_QUERY"));
    }

    /**
     * A column projected out of the parent's own row contributes nothing. That absence is the
     * relation's central claim about cost, and the population it excludes is most of a schema.
     */
    @Test
    void aColumnOfTheParentsOwnRowContributesNoRow() {
        withCapturedStore("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """, dsl -> assertThat(rulesFor(dsl, "Film", "title")).isEmpty());
    }

    /**
     * A table-typed child with no marker is inlined into the parent's statement, and contributes
     * nothing. The pair with the marker case above is what makes the marker arms load-bearing.
     */
    @Test
    void anInlinedChildReferenceContributesNoRow() {
        withCapturedStore("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { films: [Film!]! }
            """, dsl -> assertThat(rulesFor(dsl, "Film", "language")).isEmpty());
    }

    /** The graph partition: one workspace's graphs do not read each other's rules. */
    @Test
    void aSiblingGraphReadsNone() {
        withCapturedStore("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """, dsl -> {
            assertThat(rulesFor(dsl, "Query", "films")).isNotEmpty();
            assertThat(dsl.fetchCount(INTENT_FIELD_SEPARATE_FETCH,
                INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq("other"))).isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "SeparateFetchTest";
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    /** The coordinates one rule reaches, as {@code Type.field}. */
    private static List<String> rules(DSLContext dsl, String rule) {
        return dsl.select(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME, INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME)
            .from(INTENT_FIELD_SEPARATE_FETCH)
            .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_SEPARATE_FETCH.RULE.eq(rule))
            .fetch(r -> r.value1() + "." + r.value2());
    }

    /** Every rule reaching one coordinate. */
    private static List<String> rulesFor(DSLContext dsl, String typeName, String fieldName) {
        return dsl.select(INTENT_FIELD_SEPARATE_FETCH.RULE)
            .from(INTENT_FIELD_SEPARATE_FETCH)
            .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME.eq(fieldName))
            .orderBy(INTENT_FIELD_SEPARATE_FETCH.RULE)
            .fetch(INTENT_FIELD_SEPARATE_FETCH.RULE);
    }

    private static GatheredFacts gather(String sdl) {
        var nodes = TestSchemaHelper.nodeDeclaration();
        return GatheredFacts.gather(TestSchemaHelper.buildBundle(sdl).assembled(),
            (s, v) -> SchemaReachability.walk(s, nodes, v));
    }

    private void withCapturedStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, List.of(), new NodeDeclaration(null));
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
