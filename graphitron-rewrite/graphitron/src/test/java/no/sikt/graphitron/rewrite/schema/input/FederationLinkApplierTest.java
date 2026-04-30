package no.sikt.graphitron.rewrite.schema.input;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class FederationLinkApplierTest {

    @Test
    void singleImportInjectsDirectiveDeclaration() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@key"])
            type Foo { id: ID! }
            """
        ));

        boolean fedLink = FederationLinkApplier.apply(registry);

        assertThat(fedLink).isTrue();
        assertThat(registry.getDirectiveDefinition("key")).isPresent();
        assertThat(registry.getDirectiveDefinition("link")).isPresent();
    }

    @Test
    void multipleImportsInjectAllDeclarations() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@key", "@shareable", "@inaccessible"])
            type Foo { id: ID! }
            """
        ));

        FederationLinkApplier.apply(registry);

        assertThat(registry.getDirectiveDefinition("key")).isPresent();
        assertThat(registry.getDirectiveDefinition("shareable")).isPresent();
        assertThat(registry.getDirectiveDefinition("inaccessible")).isPresent();
    }

    @Test
    void aliasedImportInjectsUnderAlias() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: [{name: "@key", as: "@primaryKey"}])
            type Foo { id: ID! }
            """
        ));

        FederationLinkApplier.apply(registry);

        // library injects under the alias name
        assertThat(registry.getDirectiveDefinition("primaryKey")).isPresent();
        assertThat(registry.getDirectiveDefinition("key")).isEmpty();
    }

    @Test
    void manuallyDeclaredFederationDirectiveTellsDeveloperToRemoveIt() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@external"])
            directive @external on OBJECT | FIELD_DEFINITION
            type Foo { id: ID! }
            """
        ));

        assertThatThrownBy(() -> FederationLinkApplier.apply(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'@external'")
                .hasMessageContaining("Remove the manual")
                .hasMessageContaining("directive definition from your schema SDL");
    }

    @Test
    void noFederationLinkNoOp() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            "type Foo { id: ID! }"
        ));

        boolean fedLink = FederationLinkApplier.apply(registry);

        assertThat(fedLink).isFalse();
        assertThat(registry.getDirectiveDefinition("key")).isEmpty();
    }
}
