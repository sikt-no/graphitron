package no.sikt.graphitron.rewrite.generators;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full fetcher class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link TypeFetcherClassGenerator} produces exactly one {@code *Fetchers} class
 * per GraphQL type that is a {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType},
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}, or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.RootType}.
 */
class FetcherPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void singleTableType_producesOneFetchersClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers");
    }

    @Test
    void multipleTableTypes_producesOneFetchersClassEach() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers", "ActorFetchers");
    }

    @Test
    void classNameIsTypeNamePlusFetchers() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers");
        assertThat(classes).doesNotContain("Film");
    }

    @Test
    void rootType_producesAFetchersClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("QueryFetchers");
    }

    @Test
    void nonTableType_notIncluded() {
        var classes = generate("""
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(classes).doesNotContain("ContainerFetchers");
    }

    // ===== wiring() method via pipeline =====

    @Test
    void wiring_usesMethodReference() {
        var fetchersSpec = findSpec("FilmFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        var wiringCode = fetchersSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("wiring"))
            .findFirst().orElseThrow().code().toString();
        assertThat(wiringCode).contains("FilmFetchers::title");
    }

    // ===== Helpers =====

    private List<String> generate(String sdl) {
        return TypeFetcherClassGenerator.generate(buildSchema(sdl)).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherClassGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            Paths.get("../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
