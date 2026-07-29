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
 * <p>The boundary pins assert the completed membership: every covered coordinate mints a row
 * (the migration dial emptied with the lookup fold and is deleted; the one
 * membership-and-production switch is total with no default, which is the compile-time
 * enforcer), and shapes outside the covered family by the fact (polymorphic, node, service
 * roots) appear zero times forever.
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
    void interfaceRoot_sourceArmCarriesTheDiscriminatedFactsAndTheWhereHandshake() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              title: String! @field(name: "TITLE")
              rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              title: String! @field(name: "TITLE")
            }
            type Query {
                allContent(title: String @field(name: "TITLE")): [Content!]! @defaultOrder(primaryKey: true)
            }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);
        var row = relation.rowFor("Query", "allContent").orElseThrow();

        var source = (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable) row.source();
        assertThat(source.table().sameTable("content")).isTrue();
        assertThat(source.discriminatorColumn()).isEqualToIgnoringCase("content_type");
        assertThat(source.knownValues()).containsExactlyInAnyOrder("FILM", "SHORT");
        // Single-table participants only: no joined detail, so the whole-query base slice is
        // empty; each branch embeds the borrowed ref and the minted projection unit.
        assertThat(source.baseSlice()).isEmpty();
        assertThat(source.branches()).hasSize(2);
        var filmBranch = source.branches().stream()
            .map(b -> (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.Branch.SingleTable) b)
            .filter(b -> b.participant().typeName().equals("FilmContent"))
            .findFirst().orElseThrow();
        assertThat(filmBranch.participant().crossTableFields()).hasSize(1);
        assertThat(filmBranch.projection().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".types.FilmContent");
        // The WHERE handshake and the invocation/result axes work exactly as the plain root's:
        // glue copied off the condition relation, direct invocation, ordered list shape.
        var conditionRow = conditions.rows().get(0);
        assertThat(row.where().method()).isEqualTo(conditionRow.glue());
        assertThat(row.invocation()).isInstanceOf(no.sikt.graphitron.command.Invocation.Direct.class);
        assertThat(((ResultShape.RecordList) row.result()).ordering())
            .isInstanceOf(Ordering.Columns.class);
    }

    @Test
    void interfaceRoot_joinedParticipants_baseSliceAndDetailFieldsRideTheArm() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Party @table(name: "party") @discriminate(on: "party_kind") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @field(name: "display_name")
            }
            type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @reference(path: [{key: "party_individual_party_id_fkey"}]) @field(name: "display_name")
                birthDate:   String  @field(name: "birth_date")
            }
            type Company implements Party @table(name: "party_company") @discriminator(value: "COMPANY") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @reference(path: [{key: "party_company_party_id_fkey"}]) @field(name: "display_name")
                orgNumber:   String  @field(name: "org_number")
            }
            type Query { allParties: [Party!]! }
            """);

        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var row = LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE)
            .rowFor("Query", "allParties").orElseThrow();
        var source = (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable) row.source();

        // The base slice is one whole-query fact: schema field order within each participant
        // (the shared key partyId, then the inherited displayName), deduplicated first-wins
        // across participants (Company's identical terms claim nothing new).
        assertThat(source.baseSlice()).hasSize(2);
        var sharedKey = (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.BaseSliceTerm.SharedKey)
            source.baseSlice().get(0);
        assertThat(sharedKey.alias()).isEqualTo("party_id");
        assertThat(sharedKey.baseColumn().sqlName()).isEqualTo("party_id");
        var inherited = (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.BaseSliceTerm.InheritedRef)
            source.baseSlice().get(1);
        assertThat(inherited.fieldName()).isEqualTo("displayName");
        assertThat(inherited.baseColumn().sqlName()).isEqualTo("display_name");
        // Detail-exclusive columns stay per-branch, never deduplicated across participants.
        // Branch order follows the participant registry, not SDL order, so look up by name.
        var individual = source.branches().stream()
            .map(b -> (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.Branch.JoinedDetail) b)
            .filter(b -> b.participant().typeName().equals("Individual"))
            .findFirst().orElseThrow();
        assertThat(individual.detailFields()).singleElement().satisfies(df -> {
            assertThat(df.fieldName()).isEqualTo("birthDate");
            assertThat(df.column().sqlName()).isEqualTo("birth_date");
        });
        var company = source.branches().stream()
            .map(b -> (no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.Branch.JoinedDetail) b)
            .filter(b -> b.participant().typeName().equals("Company"))
            .findFirst().orElseThrow();
        assertThat(company.detailFields()).singleElement().satisfies(df ->
            assertThat(df.fieldName()).isEqualTo("orgNumber"));
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

        // Every covered coordinate mints a row (the migration dial is gone; the one
        // membership-and-production switch is total); the fact-excluded polymorphic root
        // mints nothing, forever.
        assertThat(relation.rows()).hasSize(4);
        assertThat(relation.rowFor("Query", "plain")).isPresent();
        assertThat(relation.rowFor("Query", "connectionShaped")).isPresent();
        assertThat(relation.rowFor("Query", "facetedConnection")).isPresent();
        assertThat(relation.rowFor("Query", "lookupShaped")).isPresent();
        assertThat(relation.rowFor("Query", "search")).isEmpty();

        // The lookup row: the pre-seam unit name through its own minting scheme, the borrowed
        // key mapping and the input-rows ref on the source arm, and an absent ordering slot
        // (the input order is source-entailed, never classifier-derived).
        var lookup = relation.rowFor("Query", "lookupShaped").orElseThrow();
        assertThat(lookup.unit().methodName()).isEqualTo("lookupLookupShaped");
        var keyed = (no.sikt.graphitron.command.LaunchSource.KeyedLookup) lookup.source();
        assertThat(keyed.table().sameTable("film")).isTrue();
        assertThat(keyed.projection().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".types.Film");
        assertThat(keyed.inputRows().methodName()).isEqualTo("lookupShapedInputRows");
        assertThat(((ResultShape.RecordList) lookup.result()).ordering()).isNull();

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
