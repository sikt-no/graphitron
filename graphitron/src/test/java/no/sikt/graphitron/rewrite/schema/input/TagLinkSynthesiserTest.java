package no.sikt.graphitron.rewrite.schema.input;

import no.sikt.graphitron.rewrite.ValidationFailedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class TagLinkSynthesiserTest {

    private static Map<String, SchemaInput> inputs(String source, boolean withTag) {
        return SchemaInputAttribution.build(List.of(new SchemaInput(
                source,
                withTag ? Optional.of("myTag") : Optional.empty(),
                Optional.empty())));
    }

    @Test
    void noTaggedInputsNoOp() {
        var registry = InMemoryRegistry.of(Map.of("a.graphqls", "type Foo { id: ID! }"));
        var bySource = inputs("a.graphqls", false);

        assertThatCode(() -> TagLinkSynthesiser.apply(registry, bySource))
                .doesNotThrowAnyException();

        // No synthesis occurred.
        assertThat(registry.getSchemaExtensionDefinitions()).isEmpty();
    }

    @Test
    void taggedInputNoLinkSynthesisesLinkExtension() {
        var registry = InMemoryRegistry.of(Map.of("a.graphqls", "type Foo { id: ID! }"));
        var bySource = inputs("a.graphqls", true);

        TagLinkSynthesiser.apply(registry, bySource);

        var extensions = registry.getSchemaExtensionDefinitions();
        assertThat(extensions).hasSize(1);
        var ext = extensions.getFirst();
        assertThat(ext.getSourceLocation().getSourceName())
                .isEqualTo(TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME);
        var link = ext.getDirectives("link").getFirst();
        var url = link.getArgument("url").getValue().toString();
        assertThat(url).contains("federation");
    }

    @Test
    void taggedInputNoLinkAndFederationLinkApplierInjectTagDeclaration() {
        var registry = InMemoryRegistry.of(Map.of("a.graphqls", "type Foo { id: ID! }"));
        var bySource = inputs("a.graphqls", true);

        TagLinkSynthesiser.apply(registry, bySource);
        FederationLinkApplier.apply(registry);

        assertThat(registry.getDirectiveDefinition("tag")).isPresent();
    }

    @Test
    void taggedInputWithLinkHavingTagIsNoOp() {
        var registry = InMemoryRegistry.of(Map.of(
            "a.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@key", "@tag"])
            type Foo { id: ID! }
            """
        ));
        var bySource = inputs("a.graphqls", true);
        int extensionsBefore = registry.getSchemaExtensionDefinitions().size();

        assertThatCode(() -> TagLinkSynthesiser.apply(registry, bySource))
                .doesNotThrowAnyException();

        assertThat(registry.getSchemaExtensionDefinitions()).hasSize(extensionsBefore);
    }

    @Test
    void taggedInputWithLinkHavingTagAliasIsNoOp() {
        var registry = InMemoryRegistry.of(Map.of(
            "a.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: [{name: "@tag", as: "@maturity"}])
            type Foo { id: ID! }
            """
        ));
        var bySource = inputs("a.graphqls", true);

        assertThatCode(() -> TagLinkSynthesiser.apply(registry, bySource))
                .doesNotThrowAnyException();
    }

    @Test
    void taggedInputWithLinkMissingTagThrowsValidationError() {
        var registry = InMemoryRegistry.of(Map.of(
            "a.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@key"])
            type Foo { id: ID! }
            """
        ));
        var bySource = inputs("a.graphqls", true);

        assertThatThrownBy(() -> TagLinkSynthesiser.apply(registry, bySource))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(ex -> {
                    var errors = ((ValidationFailedException) ex).errors();
                    assertThat(errors).hasSize(1);
                    var msg = errors.getFirst().message();
                    assertThat(msg).contains("<schemaInput tag> is configured");
                    assertThat(msg).contains("@tag");
                    assertThat(msg).contains("import");
                });
    }

    @Test
    void twoTaggedInputsNoLinkProducesOneSynthesisedExtension() {
        var registry = InMemoryRegistry.of(Map.of("a.graphqls", "type Foo { id: ID! }"));
        var bySource = SchemaInputAttribution.build(List.of(
                new SchemaInput("a.graphqls", Optional.of("t1"), Optional.empty()),
                new SchemaInput("b.graphqls", Optional.of("t2"), Optional.empty())));

        TagLinkSynthesiser.apply(registry, bySource);

        assertThat(registry.getSchemaExtensionDefinitions()).hasSize(1);
    }
}
