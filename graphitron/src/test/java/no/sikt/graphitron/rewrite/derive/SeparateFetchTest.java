package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.SchemaReachability;
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

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
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
 * gathered marker), so they are pinned against the authored coordinates directly. The record-handed
 * arm has no authored coordinate either, its whole content being what a producer's Java signature
 * implies, so it is pinned against the shapes a captured census puts in the store.
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

    // ===== The record-handed parent =====

    /**
     * The implicit split, the one arm no author writes a marker for. {@code Payload} is grounded on
     * a class a producer hands back, so nothing arrives for its fields to be projected out of, and
     * the child that names a {@code @table} type is a trip of its own.
     */
    @Test
    void aTableTypedChildOfARecordHandedParentIsFetchedSeparately() {
        withBackedStore(dsl ->
            assertThat(rulesFor(dsl, "Payload", "film")).containsExactly("RECORD_HANDED_PARENT"));
    }

    /**
     * The arm's other side, on the same parent, which is what keeps it from being a claim about
     * class-backed parents as such: a scalar is read off the member it came with, and a child whose
     * own type is bound to no table is another object in the same handed graph. Neither costs a
     * trip, and the parent they share does.
     */
    @Test
    void aChildOfARecordHandedParentNamingNoTableContributesNoRow() {
        withBackedStore(dsl -> {
            assertThat(rulesFor(dsl, "Payload", "name")).isEmpty();
            assertThat(rulesFor(dsl, "Payload", "plain")).isEmpty();
        });
    }

    /**
     * A parent both populations answer is read as a table row. {@code Film} carries {@code @table}
     * and the closure also reaches it through the handed record's own member, so its table-typed
     * child would split if the arm read the closure alone. The walk resolves that pair by reading
     * the binding and never consulting the class; the anti-join is that precedence transcribed,
     * which is why the disagreement stays observable on {@code intent_type_backing_conflict}
     * instead of being folded in here.
     */
    @Test
    void aParentBothPopulationsBackIsReadAsATableRow() {
        withBackedStore(dsl -> {
            assertThat(dsl.select(INTENT_TYPE_BACKING_CLASS.CLASS_NAME)
                .from(INTENT_TYPE_BACKING_CLASS)
                .where(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_TYPE_BACKING_CLASS.TYPE_NAME.eq("Film"))
                .fetch(0, String.class))
                .as("the premise: without a closure row the silence below would be vacuous")
                .containsExactly("app.FilmRow");
            assertThat(rulesFor(dsl, "Film", "language")).isEmpty();
            assertThat(rulesFor(dsl, "Film", "title")).isEmpty();
        });
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
        withCapturedStore(sdl, List.of(), body);
    }

    private void withCapturedStore(String sdl, List<CompletionData.ExternalReference> census,
                                   java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, census, new NodeDeclaration(null));
            body.accept(store.dsl());
        }
    }

    // ===== The record-handed fixture =====

    /**
     * One producer handing back a class, and under it the three shapes the arm has to tell apart: a
     * child bound to a table, a child bound to none, and a scalar. {@code Film} carries
     * {@code @table} and is also reached off the handed record's own member, so the fixture holds
     * the contested parent too.
     */
    private static final String BACKED_SDL = """
        type Query {
            payload: Payload @service(service: {className: "app.Producer", method: "make"})
        }
        type Payload {
            name: String
            film: Film
            plain: Plain
        }
        type Film @table(name: "film") {
            title: String
            language: Language
        }
        type Language @table(name: "language") { name: String }
        type Plain { id: ID }
        """;

    /**
     * The census the closure grounds on: the producer's return, and the members the reached records
     * declare. Hand-built rather than scanned, the split every derivation test over the census makes
     * and for its reason: a census row is a name, a descriptor and a decomposed declared type, which
     * is all these rules read.
     */
    private static List<CompletionData.ExternalReference> backedCensus() {
        String entry = "app/target/classes";
        return List.of(
            reference(entry, "app.Producer",
                method("make", "()Lapp/PayloadDto;", ref("app.PayloadDto"))),
            record(entry, "app.PayloadDto",
                component("name", ref("java.lang.String")),
                component("film", ref("app.FilmRow")),
                component("plain", ref("app.PlainRow"))),
            record(entry, "app.FilmRow",
                component("title", ref("java.lang.String")),
                component("language", ref("app.LangRow"))),
            record(entry, "app.LangRow", component("name", ref("java.lang.String"))),
            record(entry, "app.PlainRow", component("id", ref("java.lang.String"))));
    }

    private void withBackedStore(java.util.function.Consumer<DSLContext> body) {
        withCapturedStore(BACKED_SDL, backedCensus(), body);
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

    /** The qualified name a declared type mentions at its own root, which is what the peel reads. */
    private static CompletionData.TypeRef ref(String referencedClass) {
        return new CompletionData.TypeRef("", referencedClass, "NONE");
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
