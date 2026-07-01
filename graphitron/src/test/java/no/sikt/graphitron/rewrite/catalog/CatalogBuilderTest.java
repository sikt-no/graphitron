package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Verifies that {@link CatalogBuilder#build} surfaces the right shape for
 * the LSP from a real fixture jOOQ catalog and an assembled GraphQL
 * schema. Coverage focuses on the data the LSP actually queries against:
 * tables (with their columns), scalar types (built-in and custom), and
 * the FK reference list per table.
 */
@UnitTier
class CatalogBuilderTest {

    @Test
    void exposesTablesFromTheFixtureCatalog() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        assertThat(data.tables()).extracting(CompletionData.Table::name)
            .contains("film", "actor", "language");
    }

    @Test
    void columnsCarrySqlNameAndNullability() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());
        var film = data.getTable("film").orElseThrow();

        assertThat(film.columns()).extracting(CompletionData.Column::name)
            .contains("FILM_ID", "TITLE");
        var filmId = film.columns().stream()
            .filter(c -> c.name().equals("FILM_ID")).findFirst().orElseThrow();
        // film_id is the PK and not nullable.
        assertThat(filmId.nullable()).isFalse();
    }

    @Test
    void referencesIncludeOutboundForeignKeys() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());
        var film = data.getTable("film").orElseThrow();

        // film has an outbound FK to language (film.language_id -> language.language_id).
        assertThat(film.references())
            .anySatisfy(r -> {
                assertThat(r.targetTable()).isEqualToIgnoringCase("language");
                assertThat(r.inverse()).isFalse();
            });
    }

    @Test
    void referencesIncludeInboundForeignKeys() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());
        var language = data.getTable("language").orElseThrow();

        // language is the FK target; film holds the FK, so language sees an
        // inverse reference back to film.
        assertThat(language.references())
            .anySatisfy(r -> {
                assertThat(r.targetTable()).isEqualToIgnoringCase("film");
                assertThat(r.inverse()).isTrue();
            });
    }

    @Test
    void exposesBuiltInScalarsFromTheParsedSchema() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        // Reference every built-in scalar so graphql-java keeps each in the
        // assembled type list. Built-ins that no field uses are pruned.
        var bundle = TestSchemaHelper.buildBundle("""
            type Query {
              i: Int
              s: String
              b: Boolean
              f: Float
              id: ID
            }
            """);

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        assertThat(data.types()).extracting(CompletionData.TypeData::name)
            .contains("Int", "String", "Boolean", "Float", "ID");
    }

    @Test
    void exposesCustomScalarsAndPicksUpTheirSourceLocation() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("""
            scalar DateTime
            type Query { now: DateTime }
            """);

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        var dateTime = data.getType("DateTime").orElseThrow();
        assertThat(dateTime.name()).isEqualTo("DateTime");
        // Custom scalars from inline SDL get a non-zero source location;
        // built-ins do not, so this proves the location wiring is live.
        assertThat(dateTime.definition().line()).isGreaterThan(0);
    }

    @Test
    void doesNotLeakIntrospectionScalars() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        // GraphQL's __schema / __type / etc. introspection types contribute
        // some scalars; they should never appear in completion lists.
        assertThat(data.types()).extracting(CompletionData.TypeData::name)
            .noneMatch(n -> n.startsWith("__"));
    }

    @Test
    void externalReferencesEmptyWhenBasedirHasNoTargetClasses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path empty) {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");
        var ctx = new no.sikt.graphitron.rewrite.RewriteContext(
            java.util.List.of(), empty, empty.resolve("out"),
            "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of()
        );

        var data = CatalogBuilder.build(jooq, bundle.assembled(), ctx);

        assertThat(data.externalReferences()).isEmpty();
    }

    @Test
    void nodeMetadata_capturesAuthorSuppliedTypeIdAndKeyColumns() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("""
            type Film implements Node @table(name: "film") @node(typeId: "F", keyColumns: ["FILM_ID"]) {
              id: ID! @nodeId
              title: String
            }
            type Query { x: Int }
            """);

        var data = CatalogBuilder.build(jooq, bundle.assembled(),
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        assertThat(data.nodeMetadata()).containsKey("Film");
        var meta = data.nodeMetadata().get("Film");
        assertThat(meta.typeId()).isEqualTo("F");
        assertThat(meta.keyColumns()).containsExactly("FILM_ID");
    }

    @Test
    void nodeMetadata_omittedAxesStayNullToCapturePreDeductionState() {
        // Author wrote @node with no typeId, no keyColumns. The LSP catalog
        // captures pre-deduction state, so both axes stay null even though the
        // classifier will fill them in at build time.
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("""
            type Film implements Node @table(name: "film") @node {
              id: ID! @nodeId
            }
            type Query { x: Int }
            """);

        var data = CatalogBuilder.build(jooq, bundle.assembled(),
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        var meta = data.nodeMetadata().get("Film");
        assertThat(meta).isNotNull();
        assertThat(meta.typeId()).isNull();
        assertThat(meta.keyColumns()).isNull();
    }

    @Test
    void nodeMetadata_omitsTypesWithoutNodeDirective() {
        // Plain @table types are not nodes and don't get a NodeMetadata entry.
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("""
            type Film @table(name: "film") {
              title: String
            }
            type Query { x: Int }
            """);

        var data = CatalogBuilder.build(jooq, bundle.assembled(),
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        assertThat(data.nodeMetadata()).doesNotContainKey("Film");
    }
}
