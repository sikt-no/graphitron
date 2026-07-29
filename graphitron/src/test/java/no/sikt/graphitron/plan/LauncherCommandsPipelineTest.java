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
        var anchor = (no.sikt.graphitron.command.LaunchSource.AnchorTable) filtered.source();
        assertThat(anchor.table().tableName()).isEqualTo("language");
        assertThat(anchor.projection().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".types.Language");
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
    void fannedRoot_invocationArmCarriesTheScatterCarrier_pairDiffersInExactlyOneSlot() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film] @tenantFanOut
                filmsAgain: [Film] @tenantFanOut
            }
            """, no.sikt.graphitron.common.configuration.TestConfiguration.testContext()
                .withTenantColumn("film_id"));

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);

        // The fanned coordinate mints a row (no dial entry left for it); its strategy arm
        // carries the scatter carrier's ref, and the run's carrier fact is ROUTED.
        var fanned = relation.rowFor("Query", "films").orElseThrow();
        var carrier = (no.sikt.graphitron.command.Invocation.FannedOverTenants) fanned.invocation();
        assertThat(carrier.carrier().fqcn())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".schema.TenantConnections");
        assertThat(fanned.result()).isInstanceOf(ResultShape.RecordList.class);
        assertThat(relation.carrierDsl()).isEqualTo(CarrierDsl.ROUTED);

        // Two fanned coordinates over one table: their rows are identical but for coordinate
        // and unit, which pins that element-nullability strictness and the fan-out domain are
        // not launcher facts (they ride SDL and the collapse, not the composition).
        var sibling = relation.rowFor("Query", "filmsAgain").orElseThrow();
        assertThat(sibling.source()).isEqualTo(fanned.source());
        assertThat(sibling.invocation()).isEqualTo(fanned.invocation());
        assertThat(sibling.result()).isEqualTo(fanned.result());
    }

    @Test
    void routineRoot_sourceArmCarriesTheChainAndTheTerminusProjection() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                recent(actorId: Int!, minLength: Int!): [Film!]!
                    @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                    @reference(path: [{table: "film"}])
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "recent").orElseThrow();
        var chain = (no.sikt.graphitron.command.LaunchSource.RoutineChain) row.source();
        assertThat(chain.hops()).hasSize(1);
        assertThat(chain.projection().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".types.Film");
        // No filter surface on the leaf, so no WHERE slot; unordered by classification (the
        // @orderBy surface is deferred on the chain), so the list shape carries no ordering.
        assertThat(row.where()).isNull();
        assertThat(((ResultShape.RecordList) row.result()).ordering()).isNull();
        assertThat(row.invocation()).isInstanceOf(no.sikt.graphitron.command.Invocation.Direct.class);
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

        // The migrated coordinates: the plain root and both connection halves. The dial-excluded
        // lookup shape and the fact-excluded polymorphic root mint nothing.
        assertThat(relation.rows()).hasSize(3);
        assertThat(relation.rowFor("Query", "plain")).isPresent();
        assertThat(relation.rowFor("Query", "connectionShaped")).isPresent();
        assertThat(relation.rowFor("Query", "facetedConnection")).isPresent();
        assertThat(relation.rowFor("Query", "lookupShaped")).isEmpty();
        assertThat(relation.rowFor("Query", "search")).isEmpty();

        // The faceted row's plan: the base fragment plus one entry per facet, glue refs into the
        // condition relation's masked variants, decode data borrowed off the model spec; the
        // non-faceted row's plan is absent, forking the carrier construction.
        var faceted = (ResultShape.Connection) relation.rowFor("Query", "facetedConnection")
            .orElseThrow().result();
        assertThat(faceted.facets()).isNotNull();
        assertThat(faceted.facets().base().method().methodName())
            .isEqualTo("facetedConnectionFacetBaseCondition");
        assertThat(faceted.facets().facets()).singleElement().satisfies(entry -> {
            assertThat(entry.spec().inputFieldName()).isEqualTo("rating");
            assertThat(entry.condition().method().methodName())
                .isEqualTo("facetedConnectionFacet_ratingCondition");
        });
        var nonFaceted = (ResultShape.Connection) relation.rowFor("Query", "connectionShaped")
            .orElseThrow().result();
        assertThat(nonFaceted.facets()).isNull();
    }
}
