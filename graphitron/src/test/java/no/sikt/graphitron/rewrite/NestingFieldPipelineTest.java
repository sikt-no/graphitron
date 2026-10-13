package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → fetcher-registration pipeline tests for {@link NestingField}. Covers the three
 * emission sides affected by nesting:
 * <ul>
 *   <li>Outer type's {@code registerFetchers(codeRegistry)} body — {@code Film.details} wired as a
 *       passthrough lambda (lambda kind).</li>
 *   <li>{@code Film.$project(...)} — switch arm for {@code details} recurses into nested column
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
    void outerFetcherRegistration_wiresNestingFieldAsLightFetcher() {
        // The source-passthrough NestingField read (return source) is reified onto
        // FilmFetchers and registered wrapped in LightFetcher (COLUMN_FETCHER kind), not an
        // inline lambda.
        var bodies = fetcherBodies(SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Film", "details"))
            .contains(TypeSpecAssertions.DataFetcherKind.COLUMN_FETCHER);
    }

    @Test
    void outerTypeClass_fieldsSwitchProjectsNestingArm() {
        var filmType = findType("Film", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(filmType, "details")).isTrue();
    }

    @Test
    void nestedUnit_projectsTheNestedScalar() {
        // Nesting types are projection units of their own, keyed (anchor, typeName) and
        // anchor-prefixed; the anchor's arm splices the nested unit's $project rather than
        // inlining the nested switch.
        var nestedUnit = findType("FilmFilmDetails", SCALAR_NESTING_SDL);
        assertThat(TypeSpecAssertions.hasFieldsArm(nestedUnit, "title")).isTrue();
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
    void typeClass_nestedSplitField_projectsOuterParentSourceKeyColumn() {
        // Film.info.cast's SourceKey column (FILM.FILM_ID) is projected by the "cast" arm on
        // the nested unit FilmFilmInfo, whose $project shares the anchor's table context; the
        // anchor's splice lands it in Film's select list whenever cast is selected. Without the
        // arm, the fixture still compiles; the emitted
        // DSL.row(((Record) env.getSource()).get(Tables.FILM.FILM_ID)) then reads a field the
        // SELECT omitted, which jOOQ rejects at request time.
        var nestedUnit = findType("FilmFilmInfo", SPLIT_NESTING_SDL);
        assertThat(TypeSpecAssertions.armProjectsColumn(nestedUnit, "cast", "FILM_ID")).isTrue();
    }

    // ===== Null contract =====

    private static final String RELEASE_SDL = """
        type Film @table(name: "film") {
            release: FilmRelease
            releaseRequired: FilmRelease!
        }
        type FilmRelease {
            year: Int @field(name: "RELEASE_YEAR")
            minutes: Int @field(name: "LENGTH")
        }
        type Query { film: Film }
        """;

    @Test
    void nullableWrapper_fetcherChecksEveryLeafBeforeReturningTheSource() {
        var body = fetcherMethodBody("FilmFetchers", "release", RELEASE_SDL);
        assertThat(body)
            .contains("row.get(no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.RELEASE_YEAR) != null) return source;")
            .contains("row.get(no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.LENGTH) != null) return source;")
            .endsWith("return null;\n");
    }

    @Test
    void nonNullWrapper_fetcherReturnsTheSource() {
        assertThat(fetcherMethodBody("FilmFetchers", "releaseRequired", RELEASE_SDL))
            .isEqualTo("return source;\n");
    }

    @Test
    void nullableWrapper_spliceProjectsTheDemandedColumns() {
        // The check reads every presence column whatever the query selects under the wrapper, so
        // the anchor's splice arm projects them whenever the field is selected.
        var filmType = findType("Film", RELEASE_SDL);
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "release", "RELEASE_YEAR")).isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "release", "LENGTH")).isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(filmType, "releaseRequired", "RELEASE_YEAR")).isFalse();
    }

    @Test
    void referenceChild_blocksTheCollapse() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type FilmInfo {
                year: Int @field(name: "RELEASE_YEAR")
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        var nf = (NestingField) schema.field("Film", "info");
        assertThat(nf.nullContract()).isInstanceOfSatisfying(NestingField.NullContract.AlwaysObject.class,
            c -> assertThat(c.reason()).isInstanceOfSatisfying(NestingField.NullContract.Reason.BlockedBy.class,
                b -> assertThat(b.blocker()).isInstanceOf(ChildField.TableField.class)));
        assertThat(fetcherMethodBody("FilmFetchers", "info", schema)).isEqualTo("return source;\n");
    }

    @Test
    void splitQueryChild_blocksTheCollapse() {
        var nf = (NestingField) TestSchemaHelper.buildSchema(SPLIT_NESTING_SDL).field("Film", "info");
        assertThat(nf.nullContract()).isInstanceOfSatisfying(NestingField.NullContract.AlwaysObject.class,
            c -> assertThat(c.reason()).isInstanceOfSatisfying(NestingField.NullContract.Reason.BlockedBy.class,
                b -> assertThat(b.blocker()).isInstanceOf(ChildField.BatchedTableField.class)));
    }

    // ===== Helpers =====

    private static String fetcherMethodBody(String fetchersClass, String method, String sdl) {
        return fetcherMethodBody(fetchersClass, method, TestSchemaHelper.buildSchema(sdl));
    }

    private static String fetcherMethodBody(String fetchersClass, String method, GraphitronSchema schema) {
        return TypeFetcherRenderTestSupport.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(fetchersClass))
            .flatMap(t -> t.methodSpecs().stream())
            .filter(m -> m.name().equals(method))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Fetcher method not found: " + fetchersClass + "." + method))
            .code().toString();
    }

    private static TypeSpec findType(String className, String sdl) {
        return ProjectionRenderTestSupport.renderProjections(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }

    private static Map<String, CodeBlock> fetcherBodies(String sdl) {
        return SchemaShapeRenderTestSupport.fetcherRegistrations(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE);
    }
}
