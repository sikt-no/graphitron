package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.FetcherEdgeCommand;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fetcher edge relation's membership enforcer, in the launcher closure oracle's shape: the
 * relation's coordinate keys equal exactly the declared families' coordinates derived from the
 * model (never a hand tag), the key set is disjoint from the launcher relation's (one coordinate,
 * one owning relation), and the family witnesses pin each target derivation. Deliberately no
 * edge-count pin: the enforcer is the key set plus per-row target facts.
 *
 * <p>The condition relation's key space overlaps by design and is not comparable here: a
 * polymorphic root's coordinate carries condition rows (the glue this relation's row
 * <em>consumes</em> as targets) as well as a fetcher edge row, which is production on two
 * different relations' columns, not double production of one fact.
 */
@PipelineTier
class FetcherEdgeRelationTest {

    private static final String SDL = """
        interface Occupant { firstName: String }
        type Customer implements Occupant @table(name: "customer") { firstName: String @field(name: "first_name") }
        type Staff implements Occupant @table(name: "staff") { firstName: String @field(name: "first_name") }
        union Person = Customer | Staff

        type Address @table(name: "address") {
          occupants: [Occupant!]!
        }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
        }

        type Query {
          occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
          people: [Person!]!
          node(id: ID!): Node
          films: [Film!]!
          addresses: [Address!]!
        }

        input FilmInput { title: String }
        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    private static GraphitronSchema model;
    private static EmitPlan plan;

    @BeforeAll
    static void producePlan() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        model = bundle.model();
        plan = EmitPlan.produce(model, bundle.federationLink(), bundle.usesOneOf(),
            DEFAULT_OUTPUT_PACKAGE);
    }

    /**
     * The covered-family boundary, restated per family from the model's leaf kinds. The DML and
     * payload mutation arms are members exactly when the condition relation carries rows for
     * their coordinate (their targets are derived from it), which today is never: no mutation
     * leaf is SQL-generating, so the join is empty and the boundary check proves it stays so.
     */
    private static Set<FieldCoordinates> coveredCoordinates() {
        return model.fields().values().stream()
            .filter(FetcherEdgeRelationTest::isCoveredFamilyMember)
            .map(f -> FieldCoordinates.coordinates(f.parentTypeName(), f.name()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isCoveredFamilyMember(GraphitronField f) {
        return switch (f) {
            case QueryField.QueryInterfaceField ignored -> true;
            case QueryField.QueryUnionField ignored -> true;
            case QueryField.QueryNodeField ignored -> true;
            case QueryField.QueryNodesField ignored -> true;
            case QueryField.QueryServicePolymorphicField ignored -> true;
            case QueryField.QueryServiceTableInterfaceField ignored -> true;
            case ChildField.InterfaceField ignored -> true;
            case ChildField.UnionField ignored -> true;
            case ChildField.BatchedInterfaceField ignored -> true;
            case ChildField.BatchedUnionField ignored -> true;
            case ChildField.TableInterfaceField ignored -> true;
            case MutationField.MutationRoutineWriteField ignored -> true;
            case MutationField.MutationServicePolymorphicField ignored -> true;
            case MutationField.MutationServiceTableInterfaceField ignored -> true;
            // Condition-derived families: a member exactly when the coordinate has a condition
            // row (see the producer's javadoc; empty today).
            case MutationField.DmlTableField dml -> hasConditionRow(dml.parentTypeName(), dml.name());
            case MutationField.MutationDmlRecordField p -> hasConditionRow(p.parentTypeName(), p.name());
            case MutationField.MutationBulkDmlRecordField p -> hasConditionRow(p.parentTypeName(), p.name());
            default -> false;
        };
    }

    private static boolean hasConditionRow(String parentTypeName, String fieldName) {
        var coordinate = FieldCoordinates.coordinates(parentTypeName, fieldName);
        return plan.conditions().rows().stream().anyMatch(r -> r.coordinate().equals(coordinate));
    }

    /** Model -> row: the relation's keys are exactly the fact-derived covered families. */
    @Test
    void keySetEqualsTheDeclaredFamiliesCoordinates() {
        assertThat(plan.fetcherEdges().coordinates())
            .as("relation coordinates == the coordinates the covered families' leaf kinds claim"
                + " (a missing entry means a family member bypassed production; an extra one"
                + " means production minted outside the declared families)")
            .containsExactlyInAnyOrderElementsOf(coveredCoordinates());
    }

    /** One coordinate, one owning relation: no fetcher edge row shadows a launcher row. */
    @Test
    void keySetIsDisjointFromTheLauncherRelation() {
        var launcherKeys = plan.launchers().rows().stream()
            .map(no.sikt.graphitron.command.LauncherCommand::coordinate)
            .collect(Collectors.toSet());
        assertThat(plan.fetcherEdges().coordinates())
            .noneMatch(launcherKeys::contains);
    }

    @Test
    void polymorphicRootCarriesParticipantProjectionsAndGlue() {
        var row = rowFor("Query", "occupants");
        // Stage-2 selects project each table-bound participant's $project, and the filtered
        // stage-1 branches call the participant glue minted onto the root's conditions class;
        // the glue targets are derived from the condition relation's rows, never re-evaluated.
        assertThat(row.targets()).containsExactlyInAnyOrder(
            UNITS.typeClass("Customer"),
            UNITS.typeClass("Staff"),
            UNITS.conditions("Query"));
        assertThat(row.owner()).isEqualTo(UNITS.fetchers("Query"));
    }

    @Test
    void unfilteredUnionRootCarriesParticipantProjectionsOnly() {
        assertThat(rowFor("Query", "people").targets()).containsExactlyInAnyOrder(
            UNITS.typeClass("Customer"),
            UNITS.typeClass("Staff"));
    }

    @Test
    void polymorphicChildCarriesParticipantProjections() {
        var row = rowFor("Address", "occupants");
        assertThat(row.owner()).isEqualTo(UNITS.fetchers("Address"));
        assertThat(row.targets()).containsExactlyInAnyOrder(
            UNITS.typeClass("Customer"),
            UNITS.typeClass("Staff"));
    }

    @Test
    void nodeLookupTargetsTheNodeFetcherUnit() {
        assertThat(rowFor("Query", "node").targets())
            .containsExactly(UNITS.queryNodeFetcher());
    }

    /**
     * The DML write gets no row: its re-select projection rides the reentry launcher row, its
     * encode/decode plumbing is the edge view's leaf-derived concern, and its WHERE surface
     * rides walker carriers rendered inline (no condition row exists to reference).
     */
    @Test
    void dmlWriteCoordinateHasNoRow() {
        assertThat(rowFor("Mutation", "createFilm")).isNull();
        assertThat(plan.launchers().rowFor("Mutation", "createFilm")).isPresent();
    }

    private static FetcherEdgeCommand rowFor(String typeName, String fieldName) {
        var coordinate = FieldCoordinates.coordinates(typeName, fieldName);
        return plan.fetcherEdges().rows().stream()
            .filter(r -> r.coordinate().equals(coordinate))
            .findFirst().orElse(null);
    }

    /** Every target a row carries is a committed unit of another relation, never a phantom ref. */
    @Test
    void everyTargetIsACommittedUnit() {
        var committed = new LinkedHashSet<UnitRef>();
        plan.globals().forEach(g -> committed.addAll(g.units()));
        committed.addAll(plan.conditions().units());
        committed.addAll(plan.projections().units());
        committed.addAll(plan.typeUnits().fetchersUnits());
        committed.addAll(plan.typeUnits().schemaShapeUnits());
        committed.addAll(plan.typeUnits().inputRecordUnits());
        for (var row : plan.fetcherEdges().rows()) {
            assertThat(row.targets())
                .as("targets of %s", row.coordinate())
                .allSatisfy(target -> assertThat(committed).contains(target));
        }
    }
}
