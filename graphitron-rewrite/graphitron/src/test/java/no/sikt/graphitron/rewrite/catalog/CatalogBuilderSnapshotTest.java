package no.sikt.graphitron.rewrite.catalog;

import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogBuilder#buildSnapshot} unit tests against hand-crafted
 * {@link graphql.schema.idl.TypeDefinitionRegistry} fixtures. The
 * {@code snapshot-built-implies-clean-parse} invariant is upstream of
 * {@code buildSnapshot} (parse failures throw before we get here), so the
 * tests below assume a successful parse and verify the directive shape
 * round-trips faithfully.
 */
@UnitTier
class CatalogBuilderSnapshotTest {

    @Test
    void userDeclaredDirectiveLandsInTheSnapshot() {
        var registry = new SchemaParser().parse("""
            directive @auth(role: String!) on FIELD_DEFINITION
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        assertThat(snapshot.directives()).extracting(DirectiveShape::name).contains("auth");
        var auth = snapshot.directive("auth").orElseThrow();
        assertThat(auth.args()).hasSize(1);
        var roleArg = auth.args().get(0);
        assertThat(roleArg.name()).isEqualTo("role");
        assertThat(roleArg.type())
            .isInstanceOfSatisfying(TypeShape.Named.class, named -> {
                assertThat(named.typeName()).isEqualTo("String");
                assertThat(named.nonNull()).isTrue();
            });
    }

    @Test
    void listAndNonNullWrappingIsPreservedAsSealedShape() {
        var registry = new SchemaParser().parse("""
            directive @composite(ids: [ID!]!) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        var composite = snapshot.directive("composite").orElseThrow();
        var idsArg = composite.args().get(0);
        // [ID!]! — outer non-null list of non-null ID.
        assertThat(idsArg.type())
            .isInstanceOfSatisfying(TypeShape.List.class, list -> {
                assertThat(list.nonNull()).isTrue();
                assertThat(list.inner())
                    .isInstanceOfSatisfying(TypeShape.Named.class, named -> {
                        assertThat(named.typeName()).isEqualTo("ID");
                        assertThat(named.nonNull()).isTrue();
                    });
            });
    }

    @Test
    void bundledDirectiveNamesPassThroughWithoutFilter() {
        // No producer-side filter: the resolver's bundled-shadows-snapshot
        // precedence handles collisions. The snapshot ships every directive
        // in the registry, including names that happen to coincide with
        // graphitron's bundled set.
        var registry = new SchemaParser().parse("""
            directive @table(name: String) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        assertThat(snapshot.directive("table")).isPresent();
    }

    @Test
    void descriptionRoundTripsWhenPresent() {
        var registry = new SchemaParser().parse("""
            "Marker for federation entities"
            directive @key(fields: String!) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        var key = snapshot.directive("key").orElseThrow();
        assertThat(key.description()).isPresent();
        assertThat(key.description().get()).contains("federation entities");
    }
}
