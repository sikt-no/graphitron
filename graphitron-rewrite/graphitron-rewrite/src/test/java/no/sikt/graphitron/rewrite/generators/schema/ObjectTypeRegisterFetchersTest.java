package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectTypeRegisterFetchersTest {

    private static final String SDL = """
        type Query { film: Film }
        type Film { id: ID! title: String }
        """;

    @BeforeEach
    void setUp() {
        RewriteConfig.setProperties(Set.of(), "/tmp", "com.example", "com.example.jooq", Map.of());
    }

    @AfterEach
    void tearDown() {
        RewriteConfig.clear();
    }

    @Test
    void noBridgeMethod_whenTypeNotInWiringSet() {
        var spec = findByName(ObjectTypeGenerator.generate(
            TestSchemaHelper.buildBundle(SDL).assembled()), "FilmType");
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    @Test
    void bridgeMethod_emitted_whenTypeInWiringSet() {
        var spec = findByName(ObjectTypeGenerator.generate(
            TestSchemaHelper.buildBundle(SDL).assembled(), Set.of("Film")), "FilmType");
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactlyInAnyOrder("type", "registerFetchers");
    }

    @Test
    void bridgeMethod_signatureTakesCodeRegistryBuilder_returnsVoid() {
        var spec = findByName(ObjectTypeGenerator.generate(
            TestSchemaHelper.buildBundle(SDL).assembled(), Set.of("Film")), "FilmType");
        var method = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("registerFetchers"))
            .findFirst().orElseThrow();
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(method.returnType().toString()).isEqualTo("void");
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("graphql.schema.GraphQLCodeRegistry.Builder");
        assertThat(method.parameters().get(0).name()).isEqualTo("codeRegistry");
    }

    @Test
    void bridgeMethod_delegatesToLegacyWiringAndCopiesFetchersByCoordinate() {
        var spec = findByName(ObjectTypeGenerator.generate(
            TestSchemaHelper.buildBundle(SDL).assembled(), Set.of("Film")), "FilmType");
        var body = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("registerFetchers"))
            .findFirst().orElseThrow()
            .code().toString();
        assertThat(body)
            .contains("com.example.rewrite.wiring.FilmWiring.wiring().build()")
            .contains("typeWiring.getFieldDataFetchers()")
            .contains("graphql.schema.FieldCoordinates.coordinates(\"Film\", fieldName)")
            .contains("codeRegistry.dataFetcher(");
    }

    @Test
    void bridgeMethod_notEmitted_forInterfaceOrUnionTypes() {
        String withInterface = SDL + "interface Node { id: ID! }\nunion Hit = Film";
        var specs = ObjectTypeGenerator.generate(
            TestSchemaHelper.buildBundle(withInterface).assembled(),
            Set.of("Film", "Node", "Hit"));
        var nodeSpec = findByName(specs, "NodeType");
        var hitSpec = findByName(specs, "HitType");
        assertThat(nodeSpec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
        assertThat(hitSpec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
