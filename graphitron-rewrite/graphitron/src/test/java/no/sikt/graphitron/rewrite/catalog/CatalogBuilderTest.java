package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link CatalogBuilder#build} surfaces the right shape for
 * the LSP from a real fixture jOOQ catalog and an assembled GraphQL
 * schema. Coverage focuses on the data the LSP actually queries against:
 * tables (with their columns), scalar types (built-in and custom), and
 * the FK reference list per table.
 */
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
            .contains("film_id", "title");
        var filmId = film.columns().stream()
            .filter(c -> c.name().equals("film_id")).findFirst().orElseThrow();
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
    void externalReferencesStaysEmptyInPhase2() {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
        var bundle = TestSchemaHelper.buildBundle("type Query { x: Int }");

        var data = CatalogBuilder.build(jooq, bundle.assembled(), no.sikt.graphitron.common.configuration.TestConfiguration.testContext());

        // Service-method enumeration is Phase 5 work; Phase 2 deliberately
        // leaves this slot empty (see plan, OQ A1/Phase 5).
        assertThat(data.externalReferences()).isEmpty();
    }
}
