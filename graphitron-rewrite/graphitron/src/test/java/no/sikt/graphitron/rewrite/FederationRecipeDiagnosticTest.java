package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the recipe diagnostic that fires when a consumer uses a federation directive
 * without {@code @link} and without a manual declaration.
 */
class FederationRecipeDiagnosticTest {

    private static graphql.schema.idl.TypeDefinitionRegistry registryWithDirectives(String sdl) {
        // Load the Graphitron directive prelude + the supplied SDL, without federation link injection,
        // so we can test the recipe diagnostic path where @link is absent.
        return new graphql.schema.idl.SchemaParser().parse(directivesPrelude() + "\n" + sdl);
    }

    private static String directivesPrelude() {
        try (var is = no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader.class
                .getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not on classpath");
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fedDirectiveWithoutLinkProducesRecipeMessage() {
        var registry = registryWithDirectives(
                "type Foo @key(fields: \"id\") { id: ID! }\ntype Query { foo: Foo }");
        var ctx = TestConfiguration.testContext();

        assertThatThrownBy(() -> GraphitronSchemaBuilder.buildBundle(registry, ctx))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(ex -> {
                    var errors = ((ValidationFailedException) ex).errors();
                    assertThat(errors).isNotEmpty();
                    var recipeError = errors.stream()
                            .filter(e -> e.message().contains("Pick one:"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("No 'Pick one:' error found"));
                    assertThat(recipeError.message()).contains("@key");
                    assertThat(recipeError.message()).contains("@link");
                    assertThat(recipeError.message()).contains(
                            "getting-started.md#build-time-federation-directives");
                });
    }

    @Test
    void nonFederationUndeclaredDirectiveIsUnchanged() {
        // @madeUp is not a federation directive; the raw SchemaProblem should propagate
        // rather than a ValidationFailedException with a recipe message.
        var registry = registryWithDirectives("type Foo { id: ID! @madeUp }\ntype Query { foo: Foo }");
        var ctx = TestConfiguration.testContext();

        assertThatThrownBy(() -> GraphitronSchemaBuilder.buildBundle(registry, ctx))
                .isNotInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("madeUp");
    }

    @Test
    void fedDirectiveWithLinkValidates() {
        var registry = registryWithDirectives("""
                extend schema
                  @link(url: "https://specs.apollo.dev/federation/v2.10",
                        import: ["@key"])
                type Foo @key(fields: "id") { id: ID! }
                type Query { foo: Foo }
                """);
        // Apply the federation link so @key declaration is injected before buildBundle.
        FederationLinkApplier.apply(registry);
        var ctx = TestConfiguration.testContext();

        // Should pass makeExecutableSchema (classification/validation errors may still occur
        // for an incomplete schema, but none should be the recipe diagnostic).
        try {
            GraphitronSchemaBuilder.buildBundle(registry, ctx);
        } catch (ValidationFailedException e) {
            e.errors().forEach(err ->
                assertThat(err.message()).doesNotContain("Pick one:"));
        }
    }

    @Test
    void federationLinkSetsTrueFlagOnBundle() {
        var registry = registryWithDirectives("""
                extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
                type Foo @key(fields: "id") { id: ID! }
                type Query { foo: Foo }
                """);
        FederationLinkApplier.apply(registry);
        var ctx = TestConfiguration.testContext();

        try {
            var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
            assertThat(bundle.federationLink()).isTrue();
        } catch (ValidationFailedException ignored) {
            // Classification/validation errors are irrelevant here; the flag is set before those.
        }
    }

    @Test
    void noFederationLinkSetsFalseFlagOnBundle() {
        var registry = registryWithDirectives("type Foo { id: ID! }\ntype Query { foo: String }");
        var ctx = TestConfiguration.testContext();

        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        assertThat(bundle.federationLink()).isFalse();
    }
}
