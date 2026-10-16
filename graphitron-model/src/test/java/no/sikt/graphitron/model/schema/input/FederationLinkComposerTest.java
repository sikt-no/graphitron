package no.sikt.graphitron.model.schema.input;

import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.Directive;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link the composition hands federation-jvm, from the link the store settled on: the author's
 * rewritten in place, or one of graphitron's own where only the configuration opts in. Driven
 * through {@link LoadingRewrites#apply}, so what is pinned is the definitions the library injects
 * for that link as well as its shape.
 */
class FederationLinkComposerTest {

    private static final String AUTHORED = """
        extend schema @link(url: "%s", import: ["@key", {name: "@shareable", as: "@fedShareable"}])
        type Query { film: Film }
        type Film { id: ID! }
        extend type Film { title: String }
        """.formatted(FederationSpec.URL);

    private static final FederationLinkComposer.Link SETTLED = new FederationLinkComposer.Link(false,
        List.of(new FederationLinkComposer.Import("@key", null),
            new FederationLinkComposer.Import("@shareable", "@fedShareable"),
            new FederationLinkComposer.Import("@tag", null)));

    @Test
    @DisplayName("the author's link carries the settled imports, at the author's position, the rest intact")
    void theAuthoredLinkIsRewritten() {
        var applied = applied(new SchemaParser().parse(AUTHORED), Optional.of(SETTLED));
        var registry = applied.registry();

        assertThat(registry.getSchemaExtensionDefinitions()).singleElement().satisfies(extension -> {
            Directive link = extension.getDirectives("link").getFirst();
            assertThat(link.getSourceLocation().getLine()).isEqualTo(1);
            assertThat(AstPrinter.printAst(link.getArgument("import").getValue()))
                .isEqualTo("[\"@key\", {name : \"@shareable\", as : \"@fedShareable\"}, \"@tag\"]");
            assertThat(AstPrinter.printAst(link.getArgument("url").getValue()))
                .isEqualTo("\"" + FederationSpec.URL + "\"");
        });
        assertThat(registry.getDirectiveDefinition("tag")).as("the added import is injected").isPresent();
        assertThat(registry.getDirectiveDefinition("fedShareable")).as("the alias kept").isPresent();
        assertThat(registry.types()).containsKey("Film");
        assertThat(registry.objectTypeExtensions().get("Film")).hasSize(1);
        assertThat(registry.scalars()).containsKey("String");
    }

    @Test
    @DisplayName("a configured link is graphitron's own, importing what the store settled on")
    void aConfiguredLinkIsAdded() {
        var settled = new FederationLinkComposer.Link(true,
            List.of(new FederationLinkComposer.Import("@key", null),
                new FederationLinkComposer.Import("@tag", null)));
        var applied = applied(new SchemaParser().parse("type Query { film: String }"),
            Optional.of(settled));

        assertThat(applied.registry().getSchemaExtensionDefinitions()).singleElement()
            .satisfies(extension -> {
                assertThat(extension.getSourceLocation().getSourceName())
                    .isEqualTo(FederationLinkComposer.CONFIGURED_SOURCE_NAME);
                var imports = (ArrayValue) extension.getDirectives("link").getFirst()
                    .getArgument("import").getValue();
                assertThat(imports.getValues()).extracting(AstPrinter::printAst)
                    .containsExactly("\"@key\"", "\"@tag\"");
            });
        assertThat(applied.registry().getDirectiveDefinition("key")).isPresent();
        assertThat(applied.registry().getDirectiveDefinition("tag")).isPresent();
    }

    @Test
    @DisplayName("a graph that does not opt in is composed as written")
    void noLinkNoChange() {
        var registry = new SchemaParser().parse("type Query { film: String }");
        var applied = applied(registry, Optional.empty());

        assertThat(applied.registry()).isSameAs(registry);
        assertThat(applied.injectedNames()).isEmpty();
    }

    private static LoadingRewrites.Outcome.Applied applied(TypeDefinitionRegistry registry,
                                                           Optional<FederationLinkComposer.Link> link) {
        var outcome = LoadingRewrites.apply(registry, List.of(), link);
        assertThat(outcome).isInstanceOf(LoadingRewrites.Outcome.Applied.class);
        return (LoadingRewrites.Outcome.Applied) outcome;
    }
}
