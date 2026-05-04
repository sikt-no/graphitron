package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → fetcher-registration pipeline tests for {@link NestingField}. Covers the three
 * emission sides affected by nesting:
 * <ul>
 *   <li>Outer type's {@code registerFetchers(codeRegistry)} body — {@code Film.details} wired as a
 *       passthrough lambda (lambda kind).</li>
 *   <li>{@code Film.$fields(...)} — switch arm for {@code details} recurses into nested column
 *       names.</li>
 *   <li>Nested type's {@code registerFetchers(codeRegistry)} body — one entry per nested type in
 *       the {@link FetcherRegistrationsEmitter} output, keyed by the nested type's name.</li>
 * </ul>
 */
@PipelineTier
class NestingFieldPipelineTest {

    private static final String SCALAR_NESTING_SDL = """
        type Film @table(name: "film") { details: FilmDetails }
        type FilmDetails { title: String }
        type Query { film: Film }
        """;

    @Test
    void outerFetcherRegistration_wiresNestingFieldAsLambda() {
        var bodies = fetcherBodies(SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Film", "details"))
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
    void fetcherRegistration_emitsOneBodyPerNestedType() {
        var bodies = fetcherBodies(SCALAR_NESTING_SDL);
        assertThat(bodies).containsKey("FilmDetails");
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmDetails", "title"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
    }

    @Test
    void fetcherRegistration_noNestingField_noNestedTypeBody() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies).doesNotContainKey("FilmDetails");
    }

    @Test
    void fetcherRegistration_multiLevelNesting_emitsBodyForEveryNestedType() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String, meta: FilmMeta }
            type FilmMeta { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies.keySet()).contains("FilmDetails", "FilmMeta");
    }

    @Test
    void fetcherRegistration_sharedNestedType_emittedOnlyOnce() {
        var bodies = fetcherBodies("""
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film") { details: FilmDetails }
            type FilmDetails { title: String }
            type Query { film: Film }
            """);
        assertThat(bodies).containsKey("FilmDetails");
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
    void fetcherRegistration_nestedSplitField_referencesNestedFetchersClass() {
        var bodies = fetcherBodies(SPLIT_NESTING_SDL);
        assertThat(bodies).containsKey("FilmInfo");
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "cast"))
            .contains(TypeSpecAssertions.DataFetcherKind.METHOD_REFERENCE);
        assertThat(bodies.get("FilmInfo").toString()).contains("FilmInfoFetchers");
    }

    @Test
    void fetcherRegistration_nestedSplitField_inlineLeavesInSameTypeStillWireInline() {
        var bodies = fetcherBodies("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                title: String
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "title"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmInfo", "cast"))
            .contains(TypeSpecAssertions.DataFetcherKind.METHOD_REFERENCE);
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
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }

    private static Map<String, CodeBlock> fetcherBodies(String sdl) {
        return FetcherRegistrationsEmitter.emit(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE);
    }
}
