package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.WiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → generated {@link TypeSpec} pipeline tests for {@link NestingField}. Covers the three
 * emission sides affected by nesting:
 * <ul>
 *   <li>{@code FilmWiring.wiring()} — outer {@code .dataFetcher("details", env -> env.getSource())}
 *       passthrough (lambda kind).</li>
 *   <li>{@code Film.$fields(...)} — switch arm for {@code details} recurses into nested column
 *       names.</li>
 *   <li>{@code FilmDetailsWiring.wiring()} — one {@code TypeRuntimeWiring.newTypeWiring("FilmDetails")}
 *       per nested type, produced by {@link WiringClassGenerator}.</li>
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
    void outerWiringClass_wiresNestingFieldAsLambda() {
        var wirings = buildWiringClasses(SCALAR_NESTING_SDL);
        var filmWiring = findWiring("Film", wirings);
        assertThat(TypeSpecAssertions.wiringFor(filmWiring, "details"))
            .contains(TypeSpecAssertions.DataFetcherKind.LAMBDA);
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestingArm() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "details")).isTrue();
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestedScalar() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "title")).isTrue();
    }

    @Test
    void wiringClass_emitsOneWiringClassPerNestedType() {
        var wirings = buildWiringClasses(SCALAR_NESTING_SDL);
        var filmDetailsWiring = findWiring("FilmDetails", wirings);
        String body = wiringMethodBody(filmDetailsWiring);
        assertThat(body).contains("newTypeWiring(\"FilmDetails\")");
        assertThat(body).contains(".dataFetcher(\"title\"");
    }

    @Test
    void wiringClass_noNestingField_noNestedTypeWiringClass() {
        var wirings = buildWiringClasses("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        assertThat(wirings.stream().map(TypeSpec::name)).doesNotContain("FilmDetailsWiring");
    }

    @Test
    void wiringClass_multiLevelNesting_emitsWiringClassForEveryNestedType() {
        var wirings = buildWiringClasses("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String, meta: FilmMeta }
            type FilmMeta { title: String }
            type Query { film: Film }
            """);
        assertThat(wirings.stream().map(TypeSpec::name))
            .contains("FilmDetailsWiring", "FilmMetaWiring");
    }

    @Test
    void wiringClass_sharedNestedType_emittedOnlyOnce() {
        var wirings = buildWiringClasses("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String }
            type Query { film: Film }
            """);
        long occurrences = wirings.stream()
            .filter(t -> t.name().equals("FilmDetailsWiring"))
            .count();
        assertThat(occurrences).isEqualTo(1L);
    }

    private static final String SPLIT_NESTING_SDL = """
        type Actor @table(name: "actor") { name: String }
        type FilmInfo {
            cast: [Actor!]! @splitQuery
                @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Film @table(name: "film") { info: FilmInfo }
        type Query { film: Film }
        """;

    @Test
    void wiringClass_nestedSplitField_referencesNestedFetchersClass() {
        var wirings = buildWiringClasses(SPLIT_NESTING_SDL);
        String body = wiringMethodBody(findWiring("FilmInfo", wirings));
        assertThat(body).contains("newTypeWiring(\"FilmInfo\")");
        assertThat(body).contains("FilmInfoFetchers::cast");
    }

    @Test
    void wiringClass_nestedSplitField_inlineLeavesInSameTypeStillWireInline() {
        var wirings = buildWiringClasses("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                title: String
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        String body = wiringMethodBody(findWiring("FilmInfo", wirings));
        assertThat(body).contains("ColumnFetcher");
        assertThat(body).contains("FilmInfoFetchers::cast");
    }

    @Test
    void typeClass_nestedSplitField_projectsOuterParentBatchKeyColumn() {
        // The recursive collectBatchKeyColumns walk must surface Film.info.cast's RowKeyed
        // BatchKey column (FILM.FILM_ID) into Film.$fields so key extraction reads a non-null
        // FK off env.getSource() at request time. Without the recursion, the fixture compiles
        // and runs but every batch hits a NullPointerException reading FILM_ID from a Record
        // whose SELECT omitted it.
        var filmType = findType("Film", SPLIT_NESTING_SDL);
        assertThat(TypeSpecAssertions.appendsRequiredColumn(filmType, "FILM_ID")).isTrue();
    }

    // ===== Helpers =====

    private static TypeSpec findType(String className, String sdl) {
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }

    private static List<TypeSpec> buildWiringClasses(String sdl) {
        return WiringClassGenerator.generate(TestSchemaHelper.buildSchema(sdl));
    }

    private static TypeSpec findWiring(String typeName, List<TypeSpec> wirings) {
        return wirings.stream()
            .filter(t -> t.name().equals(typeName + "Wiring"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Wiring class not found: " + typeName + "Wiring"));
    }

    private static String wiringMethodBody(TypeSpec wiring) {
        return wiring.methodSpecs().stream()
            .filter(m -> m.name().equals("wiring"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("wiring() method not found on " + wiring.name()))
            .code().toString();
    }
}
