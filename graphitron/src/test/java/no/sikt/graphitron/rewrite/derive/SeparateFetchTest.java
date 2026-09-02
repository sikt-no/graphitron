package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * <p>The subject here is the capture side of that relation, and it is two questions. The first is
 * agreement: the two marker arms are bound to the walk's own gathered delivery relation
 * ({@link GatheredFacts#delivery()}), which is the independent evaluation of the same question, the
 * visitor reading the directive applications off the assembled schema while the view reads the
 * captured rows. The second is reach: the record-handed arm stands on a closure a derivation writer
 * materializes rather than on anything an author wrote, so a fixture pins that a producer's return,
 * scanned as a census and closed over by that writer, arrives as rows the arm actually joins to.
 *
 * <p>What the relation returns given rows is not asked here. Each arm's own population, the
 * boundary the record-handed one draws around a parent both populations answer, the parent's kind
 * guard, and the arity a coordinate several rules reach carries are the view's algebra, and they
 * live in the module whose DDL declares it, in
 * {@code no.sikt.graphitron.model.intent.SeparateFetchRuleTest}, against a store seeded row by row.
 * The absence cases carry their weight there for the same reason: a field whose value comes out of
 * its parent's row is the population this relation exists to exclude, and stating that boundary
 * takes rows rather than a schema.
 */
@PipelineTier
class SeparateFetchTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String PRODUCER = "app.Producer";

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
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var dsl = store.dsl();
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
        }
    }

    /**
     * The implicit split's reach: the one arm no author writes a marker for, and the one standing on
     * a relation a writer materializes rather than on a directive application. {@code Payload} is
     * grounded on the class a producer hands back, so nothing arrives for its fields to be projected
     * out of, and the child that names a {@code @table} type is a trip of its own.
     *
     * <p>The closure row is the subject rather than the scenery. Read here off a captured census, it
     * is the thing that keeps the seeded half's arm from joining to a relation that nothing in a
     * real pipeline populates in the shape it reads.
     */
    @Test
    void aProducerHandedParentReachesTheArmThroughTheClosureAWriterDerived() {
        String sdl = """
            type Query {
                payload: Payload @service(service: {className: "%s", method: "make"})
            }
            type Payload {
                name: String
                film: Film
            }
            type Film @table(name: "film") { title: String }
            """.formatted(PRODUCER);
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq(), handedCensus())) {
            var dsl = store.dsl();
            assertThat(dsl.select(INTENT_TYPE_BACKING_CLASS.CLASS_NAME)
                .from(INTENT_TYPE_BACKING_CLASS)
                .where(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_TYPE_BACKING_CLASS.TYPE_NAME.eq("Payload"))
                .fetch(0, String.class))
                .as("the premise: the closure the arm joins to is a writer's row, not a fixture's")
                .containsExactly("app.PayloadDto");
            assertThat(rulesFor(dsl, "Payload", "film")).containsExactly("RECORD_HANDED_PARENT");
        }
    }

    // ===== Helpers =====

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

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }

    /**
     * The census the closure grounds on: the producer's return, and the members the reached record
     * declares. Hand-built rather than scanned, the split every derivation test over the census
     * makes and for its reason: a census row is a name, a descriptor and a decomposed declared type,
     * which is all these rules read.
     */
    private static List<CompletionData.ExternalReference> handedCensus() {
        String entry = "app/target/classes";
        return List.of(
            reference(entry, PRODUCER, method("make", "()Lapp/PayloadDto;", ref("app.PayloadDto"))),
            record(entry, "app.PayloadDto",
                component("name", ref("java.lang.String")),
                component("film", ref("app.FilmRow"))));
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
}
