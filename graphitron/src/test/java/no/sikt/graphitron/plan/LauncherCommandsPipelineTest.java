package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier assertions on the launcher command relation over classified fixture schemas: the
 * produced rows, their key, and the producer-decided data (the minted unit ref, the WHERE slot
 * copied off the condition relation, the ordering arms riding the result shape, the connection
 * arm's page size and runtime refs, the run's carrier fact). Renderer behaviour is covered by
 * {@code RootLauncherRendererTest} and the execution-tier SQL baseline; this class pins what the
 * producer mints, so it asserts on rows, never on emitted code.
 *
 * <p>The boundary pins are two-tiered per the migration-dial design: shapes outside the covered
 * family by the fact (polymorphic, node, service roots) appear zero times forever, and shapes
 * excluded by a named {@code NotYetMigrated} dial entry (the faceted connection, fanned, routine,
 * single-table-interface, lookup) appear zero times while their entries exist; a dial entry's
 * deletion flips its pin from boundary to membership in the slice that lands the shape.
 */
@PipelineTier
class LauncherCommandsPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    @Test
    void plainRoots_oneRowEach_whereSlotCopiedOffTheConditionRelation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(name: String @field(name: "name")): [Language!]!
                unfiltered: [Language!]!
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);

        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.carrierDsl()).isEqualTo(CarrierDsl.ENV_ACQUIRED);

        var filtered = relation.rowFor("Query", "languages").orElseThrow();
        assertThat(filtered.unit().owner().fqcn())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".fetchers.QueryFetchers");
        assertThat(filtered.unit().methodName()).isEqualTo("rowsLanguages");
        assertThat(filtered.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "languages"));
        assertThat(filtered.table().tableName()).isEqualTo("language");
        assertThat(filtered.projection().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".types.Language");
        assertThat(filtered.result()).isInstanceOf(ResultShape.RecordList.class);
        // The handshake: the WHERE slot is the condition row's glue ref and its env-appending
        // answer, copied, never recomputed from filters.
        var conditionRow = conditions.rows().get(0);
        assertThat(filtered.where()).isNotNull();
        assertThat(filtered.where().method()).isEqualTo(conditionRow.glue());
        assertThat(filtered.where().takesEnv()).isEqualTo(conditionRow.readsRequestContext());

        // Absence in the condition relation is the absence: no row, no glue, neutral condition.
        var unfiltered = relation.rowFor("Query", "unfiltered").orElseThrow();
        assertThat(unfiltered.where()).isNull();
        // The synthesised primary-key default order arrives as the inline Columns arm, riding
        // the list shape.
        assertThat(((ResultShape.RecordList) unfiltered.result()).ordering())
            .isInstanceOf(Ordering.Columns.class);
    }

    @Test
    void envBoundCondition_takesEnvRidesTheCopiedGlueCall() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                seen(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argConditionWithContext"}, contextArguments: ["tenantId"])): [Language!]!
            }
            """.formatted(STUB));

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "seen").orElseThrow();
        assertThat(row.where().takesEnv()).isTrue();
    }

    @Test
    void singleCardinalityRoot_singleRecordShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                language(language_id: Int @field(name: "language_id")): Language
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "language").orElseThrow();
        assertThat(row.result()).isInstanceOf(ResultShape.SingleRecord.class);
    }

    @Test
    void argumentOrderedRoot_helperArmCarriesTheMintedRefs() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            enum FilmSort {
                FILM_ID @order(primaryKey: true)
                TITLE   @order(fields: [{name: "title"}])
            }
            input FilmOrderBy { field: FilmSort! direction: SortDirection }
            type Query {
                films(order: [FilmOrderBy] @orderBy): [Film!]! @defaultOrder(primaryKey: true)
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "films").orElseThrow();
        var helper = (Ordering.Helper) ((ResultShape.RecordList) row.result()).ordering();
        assertThat(helper.method().owner().fqcn())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".fetchers.QueryFetchers");
        assertThat(helper.method().methodName()).isEqualTo("filmsOrderBy");
        assertThat(helper.resultType().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".util.OrderByResult");
    }

    @Test
    void connectionRoot_carriesOrderingPageSizeAndTheRuntimeRefs() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "films").orElseThrow();
        var connection = (ResultShape.Connection) row.result();
        assertThat(connection.ordering()).isInstanceOf(Ordering.Columns.class);
        assertThat(connection.defaultPageSize()).isPositive();
        assertThat(connection.helper().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".util.ConnectionHelper");
        assertThat(connection.carrier().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".util.ConnectionResult");
    }

    @Test
    void boundaries_factExcludedAndDialExcludedShapesProduceNoRows() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            interface Searchable { name: String }
            type NamedActor implements Searchable @table(name: "actor") { name: String @field(name: "first_name") }
            input FilmFacetFilter {
                rating: [String!] @field(name: "rating") @asFacet
            }
            type Query {
                plain: [Film!]!
                connectionShaped: [Film!]! @asConnection @defaultOrder(primaryKey: true)
                facetedConnection(filter: FilmFacetFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
                lookupShaped(film_id: [Int!] @lookupKey): [Film!]!
                search: [Searchable!]!
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);

        // The migrated coordinates: the plain root and the non-faceted connection. The
        // dial-excluded shapes (the faceted connection, lookup) and the fact-excluded
        // polymorphic root mint nothing.
        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.rowFor("Query", "plain")).isPresent();
        assertThat(relation.rowFor("Query", "connectionShaped")).isPresent();
        assertThat(relation.rowFor("Query", "facetedConnection")).isEmpty();
        assertThat(relation.rowFor("Query", "lookupShaped")).isEmpty();
        assertThat(relation.rowFor("Query", "search")).isEmpty();
    }
}
