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
                .hasMessageContaining("Your schema declares")
                .hasMessageContaining("Remove the manual")
                .hasMessageContaining("directive definition from your schema SDL")
                .hasMessageContaining("schema.graphqls:4");
    }

    @Test
    void manuallyDeclaredFederationTypeReportsTypeKindAndLocation() {
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10",
                    import: ["@key"])
            scalar link__Import
            type Foo { id: ID! }
            """
        ));

        assertThatThrownBy(() -> FederationLinkApplier.apply(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'link__Import'")
                .hasMessageContaining("type is injected")
                .hasMessageContaining("Remove the manual 'link__Import' type definition")
                .hasMessageContaining("schema.graphqls:4");
    }

    @Test
    void federationLibraryDoubleDeclarationOnV26ReportsLibraryBug() {
        // federation-graphql-java-support 6.0.0's bundled definitions_fed2_6.graphqls and
        // definitions_fed2_7.graphqls each declare `directive @tag` twice. When the consumer's
        // @link URL points at v2.6 or v2.7, loadFederationImportedDefinitions returns @tag
        // twice and our second registry.add fails. The error needs to name the library bug
        // and the workaround (move off v2.6 / v2.7), not blame the user's schema.
        var registry = InMemoryRegistry.of(Map.of(
            "schema.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.6",
                    import: ["@tag"])
            type Foo { id: ID! }
            """
        ));

        assertThatThrownBy(() -> FederationLinkApplier.apply(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("federation v2.6")
                .hasMessageContaining("declares '@tag' twice")
                .hasMessageContaining("federation-graphql-java-support bug")
                .hasMessageContaining("v2.8 or later");
    }

    @Test
    void multipleFederationLinksReportEachFileAndLine() {
        var registry = InMemoryRegistry.of(Map.of(
            "first.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.6",
                    import: ["@key", "@shareable"])
            type Foo { id: ID! }
            """,
            "second.graphqls",
            """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.4",
                    import: ["@key", "@override"])
            type Bar { id: ID! }
            """
        ));

        assertThatThrownBy(() -> FederationLinkApplier.apply(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one federation @link")
                .hasMessageContaining("first.graphqls:")
                .hasMessageContaining("second.graphqls:")
                .hasMessageContaining("federation/v2.6")
                .hasMessageContaining("federation/v2.4")
                .hasMessageContaining("@key, @shareable")
                .hasMessageContaining("@key, @override");
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
