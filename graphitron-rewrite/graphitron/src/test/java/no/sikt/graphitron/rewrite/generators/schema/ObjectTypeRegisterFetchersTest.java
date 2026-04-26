package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectTypeRegisterFetchersTest {

    private static final String SDL = """
        type Query { film: Film }
        type Film { id: ID! title: String }
        """;

    @Test
    void noRegisterFetchers_whenTypeNotInFetcherBodies() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var spec = findByName(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled()), "FilmType");
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    @Test
    void registerFetchers_emitted_whenTypeHasBody() {
        var body = CodeBlock.of("codeRegistry.dataFetcher($S, null);\n", "Film");
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var spec = findByName(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled(), Map.of("Film", body)), "FilmType");
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactlyInAnyOrder("type", "registerFetchers");
    }

    @Test
    void registerFetchers_signatureTakesCodeRegistryBuilder_returnsVoid() {
        var body = CodeBlock.of("codeRegistry.dataFetcher($S, null);\n", "Film");
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var spec = findByName(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled(), Map.of("Film", body)), "FilmType");
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
    void registerFetchers_bodyMatchesInput() {
        var body = CodeBlock.of("codeRegistry.dataFetcher($S, null);\n", "marker-value");
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var spec = findByName(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled(), Map.of("Film", body)), "FilmType");
        var emitted = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("registerFetchers"))
            .findFirst().orElseThrow()
            .code().toString();
        assertThat(emitted).contains("marker-value");
    }

    @Test
    void registerFetchers_notEmitted_forInterfaceOrUnionTypes() {
        String withInterface = SDL + "interface Node { id: ID! }\nunion Hit = Film";
        var body = CodeBlock.of("codeRegistry.dataFetcher($S, null);\n", "x");
        var bundle = TestSchemaHelper.buildBundle(withInterface);
        var specs = ObjectTypeGenerator.generate(
            bundle.model(), bundle.assembled(),
            Map.of("Film", body, "Node", body, "Hit", body));
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
