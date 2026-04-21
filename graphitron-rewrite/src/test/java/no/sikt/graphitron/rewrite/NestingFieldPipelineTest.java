package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → generated {@link TypeSpec} pipeline tests for {@link NestingField}. Covers the three
 * emission sides affected by nesting:
 * <ul>
 *   <li>{@code FilmFetchers.wiring()} — outer {@code .dataFetcher("details", env -> env.getSource())}
 *       passthrough (lambda kind).</li>
 *   <li>{@code Film.$fields(...)} — switch arm for {@code details} recurses into nested column
 *       names.</li>
 *   <li>{@code GraphitronWiring.build()} — one {@code TypeRuntimeWiring.newTypeWiring("FilmDetails")}
 *       per nested type, collected from {@code GraphQLRewriteGenerator.collectNestedTypes} and
 *       passed to {@link GraphitronWiringClassGenerator}.</li>
 * </ul>
 */
class NestingFieldPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    private static final String SCALAR_NESTING_SDL = """
        type Film @table(name: "film") { details: FilmDetails }
        type FilmDetails { title: String }
        type Query { film: Film }
        """;

    @Test
    void outerFetchersClass_wiresNestingFieldAsLambda() {
        var fetchers = findFetcher("FilmFetchers", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "details"))
            .contains(TypeSpecAssertions.DataFetcherKind.LAMBDA);
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestingArm() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        // The outer switch contains a case for "details" that recurses into the nested selection.
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "details")).isTrue();
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestedScalar() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        // The nested emitSelectionSwitch adds a case "title" at depth=1 reading from the same
        // outer table alias (nesting is transparent to the projection).
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "title")).isTrue();
    }

    @Test
    void wiringClass_emitsOneTypeRuntimeWiringPerNestedType() {
        var wiring = buildWiring(SCALAR_NESTING_SDL);
        String body = wiringBuildBody(wiring);
        assertThat(body).contains("newTypeWiring(\"FilmDetails\")");
        assertThat(body).contains(".dataFetcher(\"title\"");
    }

    @Test
    void wiringClass_noNestingField_noNestedTypeWiring() {
        // Control: without a NestingField nothing shows up in the wiring.
        var wiring = buildWiring("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        assertThat(wiringBuildBody(wiring)).doesNotContain("FilmDetails");
    }

    @Test
    void wiringClass_multiLevelNesting_emitsWiringForEveryNestedType() {
        // FilmDetails contains a further NestingField (meta: FilmMeta) — the aggregator walks
        // nestedFields recursively, producing wiring for both nested types.
        var wiring = buildWiring("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String, meta: FilmMeta }
            type FilmMeta { title: String }
            type Query { film: Film }
            """);
        String body = wiringBuildBody(wiring);
        assertThat(body).contains("newTypeWiring(\"FilmDetails\")");
        assertThat(body).contains("newTypeWiring(\"FilmMeta\")");
    }

    @Test
    void wiringClass_sharedNestedType_emittedOnlyOnce() {
        // Two @table parents reference the same nested type — dedupe via LinkedHashMap in
        // collectNestedTypes means FilmDetails wiring appears exactly once.
        var wiring = buildWiring("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String }
            type Query { film: Film }
            """);
        String body = wiringBuildBody(wiring);
        long occurrences = body.lines().filter(l -> l.contains("newTypeWiring(\"FilmDetails\")")).count();
        assertThat(occurrences).isEqualTo(1L);
    }

    // ===== Helpers =====

    private static TypeSpec findFetcher(String className, String sdl) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Fetcher class not found: " + className));
    }

    private static TypeSpec findType(String className, String sdl) {
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }

    /**
     * Mirrors the nested-type collection loop in {@link GraphQLRewriteGenerator#generate()} — the
     * wiring aggregator is fed from this walk. Kept private and local so a signature drift in the
     * production aggregator path trips this test at compile time.
     */
    private static TypeSpec buildWiring(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var fetcherClassNames = TypeFetcherGenerator.generate(schema).stream()
            .map(TypeSpec::name).toList();
        var nestedTypeMap = new LinkedHashMap<String, GraphitronWiringClassGenerator.NestedTypeWiring>();
        schema.fields().values().forEach(f -> collectNestedTypes(f, nestedTypeMap));
        return GraphitronWiringClassGenerator.generate(fetcherClassNames, List.of(),
            List.copyOf(nestedTypeMap.values()));
    }

    private static void collectNestedTypes(no.sikt.graphitron.rewrite.model.GraphitronField field,
            java.util.Map<String, GraphitronWiringClassGenerator.NestedTypeWiring> out) {
        if (!(field instanceof NestingField nf)) {
            return;
        }
        var nestedTypeName = nf.returnType().returnTypeName();
        TableRef parentTable = nf.returnType().table();
        out.putIfAbsent(nestedTypeName,
            new GraphitronWiringClassGenerator.NestedTypeWiring(nestedTypeName, nf.nestedFields(), parentTable));
        for (ChildField nested : nf.nestedFields()) {
            collectNestedTypes(nested, out);
        }
    }

    private static String wiringBuildBody(TypeSpec wiring) {
        return wiring.methodSpecs().stream()
            .filter(m -> m.name().equals("build"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("build() method not found on GraphitronWiring"))
            .code().toString();
    }
}
