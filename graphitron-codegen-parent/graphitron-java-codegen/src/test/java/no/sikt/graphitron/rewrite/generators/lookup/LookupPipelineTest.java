package no.sikt.graphitron.rewrite.generators.lookup;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full lookup pipeline: SDL schema → {@link GraphitronSchema} →
 * {@link LookupSpec} list → generated {@code toInputRows} Java code.
 *
 * <p>Complements {@link LookupCodeGeneratorTest} (which unit-tests the code generator against
 * hand-crafted {@link LookupSpec}s) by verifying that the spec builder correctly reads the schema
 * and that the generated code contains the constructs callers depend on.
 */
class LookupPipelineTest {

    private static final LookupCodeGenerator GEN = new LookupCodeGenerator();

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            java.util.List.of(), java.util.Set.of(), java.util.List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    // ===== Spec building =====

    @Test
    void flatScalarArg_buildsOneSpec() {
        var specs = buildSpecs("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).typeName()).isEqualTo("Film");
        assertThat(specs.get(0).inputArgName()).isNull();
        assertThat(specs.get(0).fields()).hasSize(1);
        assertThat(specs.get(0).fields().get(0).argName()).isEqualTo("film_id");
        assertThat(specs.get(0).fields().get(0).columnJavaName()).isEqualTo("FILM_ID");
        assertThat(specs.get(0).fields().get(0).list()).isTrue();
    }

    @Test
    void flatMultipleArgs_listArgAndScalarArg() {
        var specs = buildSpecs("""
            type Customer @table(name: "customer") { firstName: String }
            type Query { customerById(customer_id: [ID] @lookupKey, store_id: ID @lookupKey): [Customer!]! }
            """);
        assertThat(specs).hasSize(1);
        var fields = specs.get(0).fields();
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).argName()).isEqualTo("customer_id");
        assertThat(fields.get(0).list()).isTrue();
        assertThat(fields.get(1).argName()).isEqualTo("store_id");
        assertThat(fields.get(1).list()).isFalse();
    }

    @Test
    void explicitTableInputTypeArg_buildsSpecWithInputArgName() {
        var specs = buildSpecs("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).inputArgName()).isEqualTo("key");
        assertThat(specs.get(0).fields()).hasSize(1);
        assertThat(specs.get(0).fields().get(0).argName()).isEqualTo("filmId");
        assertThat(specs.get(0).fields().get(0).columnJavaName()).isEqualTo("FILM_ID");
    }

    @Test
    void implicitTableInputTypeArg_promotedAndBuildsSpec() {
        var specs = buildSpecs("""
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).inputArgName()).isEqualTo("key");
    }

    @Test
    void unresolvedScalarArg_droppedFromSpec() {
        // unknownColumn has no matching DB column → UnboundScalarArg → no spec fields → spec dropped
        var specs = buildSpecs("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(unknownColumn: [String] @lookupKey): [Film!]! }
            """);
        assertThat(specs).isEmpty();
    }

    // ===== Generated code =====

    @Test
    void flatScalarArg_generatesListVarAndGetI() {
        // film_id is Long in jOOQ (integer PK)
        var code = renderFirst("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """);
        assertThat(code)
            .contains("List<Long> film_id = (List<Long>) arguments.get(\"film_id\")")
            .contains("film_id.get(i)")
            .contains("film_id.size()");
    }

    @Test
    void flatMultipleArgs_broadcastsScalar() {
        // customer_id and store_id are integer types in jOOQ; the list arg gets a local var,
        // the scalar arg is read inline — exact Java type varies so we check the pattern not the type
        var code = renderFirst("""
            type Customer @table(name: "customer") { firstName: String }
            type Query { customerById(customer_id: [ID] @lookupKey, store_id: ID @lookupKey): [Customer!]! }
            """);
        assertThat(code)
            .contains("arguments.get(\"customer_id\")")   // list arg declared as local var
            .contains("arguments.get(\"store_id\")")      // scalar arg read inline
            .contains("customer_id.get(i)")               // list arg dereferenced per row
            .doesNotContain("store_id.get(i)");           // scalar NOT per-element
    }

    @Test
    void flatArgs_correctReturnType() {
        // 1 index column + 1 field → Record2<Integer, Long> (film_id is Long in jOOQ)
        var code = renderFirst("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """);
        assertThat(code).contains("Record2<Integer, Long>");
    }

    @Test
    void inputTypeArg_generatesInputListExtractionAndMapGet() {
        // film_id is Long in jOOQ; key is a list input type arg
        var code = renderFirst("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(code)
            .contains("List<Map<String, Object>> key = (List<Map<String, Object>>) arguments.get(\"key\")")
            .contains("var m = key.get(i)")
            .contains("m.get(\"filmId\")")
            .contains("FILM.FILM_ID");
    }

    @Test
    void inputTypeArg_correctReturnType() {
        // 1 index + 1 field (film_id = Long) → Record2<Integer, Long>
        var code = renderFirst("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(code).contains("Record2<Integer, Long>");
    }

    @Test
    void inputTypeArg_usesIntStreamAndDsl() {
        var code = renderFirst("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(code)
            .contains("IntStream.range(0,")
            .contains("DSL.using(")
            .contains(".newRecord(")
            .contains("GRAPHITRON_INPUT_IDX")
            .contains(".toList()");
    }

    // ===== Helpers =====

    private List<LookupSpec> buildSpecs(String sdl) {
        return LookupSpecBuilder.build(buildSchema(sdl));
    }

    private String renderFirst(String sdl) {
        var specs = buildSpecs(sdl);
        assertThat(specs).isNotEmpty();
        return JavaFile.builder("test.pkg", GEN.generate(specs.get(0))).indent("    ").build().toString();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }

}
