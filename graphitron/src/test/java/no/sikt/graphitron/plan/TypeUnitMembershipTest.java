package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HasInputRecordShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type-unit relation's membership enforcers, one per kind, each two-directional over a
 * fixture exercising the kind's boundary shapes (per the migration's rule that every kind
 * states its own derivation and pins it; a single blanket claim would leave the reader guessing
 * which derivation is enforced).
 */
@PipelineTier
class TypeUnitMembershipTest {

    @Test
    void inputRecords_argumentReachabilityIntersectedWithTheRecordShapeCapability() {
        // Boundary shapes: a directly argument-referenced input, an input reached only
        // transitively through a nested component, and a declared-but-unreachable input (dead
        // schema, no row).
        var schema = TestSchemaHelper.buildSchema("""
            input FilmFilter { title: String nested: NestedFilter }
            input NestedFilter { rating: String }
            input OrphanInput { unused: String }
            type Film @table(name: "film") { title: String }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        var relation = TypeUnitCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Forward: every row is argument-reachable and carries the capability, and its ref is
        // the minted inputs address.
        for (var row : relation.inputRecords()) {
            assertThat(schema.argumentReachableInputs()).contains(row.typeName());
            assertThat(schema.type(row.typeName())).isInstanceOf(HasInputRecordShape.class);
            assertThat(row.unit().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".inputs." + row.typeName());
        }
        // Backward: every reachable capability carrier has exactly one row; the unreachable
        // input has none.
        assertThat(relation.inputRecords())
            .extracting(TypeUnitCommand.InputRecordUnit::typeName)
            .containsExactly("FilmFilter", "NestedFilter");
        // The reach fold itself: transitive through nested components, dead schema excluded.
        assertThat(schema.argumentReachableInputs())
            .contains("FilmFilter", "NestedFilter")
            .doesNotContain("OrphanInput");
    }

    @Test
    void fetchers_hostingVariantsPlusReachFoldMinusHostingNames_andTheConnectionPair() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails { note: String @field(name: "title") }
            type Film @table(name: "film") { title: String details: FilmDetails }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);

        var relation = TypeUnitCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Forward: the hosting classifications (Query the root, Film the table) and the
        // nesting-reached FilmDetails each have exactly one plain row at the fetchers address.
        assertThat(relation.fetchers())
            .extracting(TypeUnitCommand.FetchersUnit::typeName)
            .contains("Query", "Film", "FilmDetails");
        for (var row : relation.fetchers()) {
            assertThat(row.unit().fqcn())
                .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".fetchers." + row.typeName() + "Fetchers");
        }
        // The nested membership reads the schema's reach fold, gated on owning a fetcher.
        assertThat(schema.nestingReach().reachedTypeNames()).contains("FilmDetails");

        // The connection pair: one row per synthesised carrier, its two refs in named roles.
        assertThat(relation.connectionFetchers()).singleElement().satisfies(pair -> {
            var ct = (no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType)
                schema.type(pair.typeName());
            assertThat(pair.connection().simpleName()).isEqualTo(ct.name() + "Fetchers");
            assertThat(pair.edge().simpleName()).isEqualTo(ct.edgeTypeName() + "Fetchers");
        });
        // The family's write set is the plain refs plus both refs of every pair.
        assertThat(relation.fetchersUnits())
            .hasSize(relation.fetchers().size() + 2 * relation.connectionFetchers().size());
    }

    @Test
    void schemaShapes_nearTotalVariantMembership_withFormSwitchAndRegistersFetchersFlag() {
        // Boundary shapes: a root, a table, a nesting-reached type, the synthesised connection
        // carriers (connection, edge, PageInfo), an enum, an argument-reachable input, and the
        // scalar population (no row).
        var schema = TestSchemaHelper.buildSchema("""
            input FilmFilter { title: String }
            enum Rating { G PG }
            type FilmDetails { note: String @field(name: "title") }
            type Film @table(name: "film") { title: String details: FilmDetails }
            type Query { films(filter: FilmFilter, rating: Rating): [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);

        var relation = TypeUnitCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var byName = relation.schemaShapes().stream()
            .collect(java.util.stream.Collectors.toMap(
                TypeUnitCommand.SchemaShapeUnit::typeName, row -> row));

        // Forward: every row's ref is the minted schema address, and no row names a scalar,
        // an unclassified verdict, or an underscore-internal type.
        for (var row : relation.schemaShapes()) {
            assertThat(row.unit().fqcn())
                .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".schema." + row.typeName() + "Type");
            assertThat(schema.type(row.typeName()))
                .isNotInstanceOf(GraphitronType.ScalarType.class)
                .isNotInstanceOf(GraphitronType.UnclassifiedType.class);
            assertThat(row.typeName()).doesNotStartWith("_");
        }

        // Backward: the total form switch routes each classification family.
        assertThat(byName.get("Query").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.OBJECT);
        assertThat(byName.get("Film").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.OBJECT);
        assertThat(byName.get("FilmDetails").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.OBJECT);
        assertThat(byName.get("PageInfo").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.OBJECT);
        assertThat(byName.get("Rating").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.ENUM);
        assertThat(byName.get("FilmFilter").form()).isEqualTo(TypeUnitCommand.SchemaShapeForm.INPUT);
        assertThat(byName).doesNotContainKey("String"); // scalars register off constants, no row

        // The registersFetchers flag: hosting classifications with a classified coordinate and
        // the reach-fold owner are true; connection and edge carriers are unconditionally true;
        // PageInfo, enums, inputs and plain interfaces carry no registration body.
        var connection = relation.schemaShapes().stream()
            .filter(row -> schema.type(row.typeName())
                instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType)
            .findFirst().orElseThrow();
        var edge = relation.schemaShapes().stream()
            .filter(row -> schema.type(row.typeName())
                instanceof no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType)
            .findFirst().orElseThrow();
        assertThat(byName.get("Query").registersFetchers()).isTrue();
        assertThat(byName.get("Film").registersFetchers()).isTrue();
        assertThat(byName.get("FilmDetails").registersFetchers()).isTrue();
        assertThat(connection.registersFetchers()).isTrue();
        assertThat(edge.registersFetchers()).isTrue();
        assertThat(byName.get("PageInfo").registersFetchers()).isFalse();
        assertThat(byName.get("Rating").registersFetchers()).isFalse();
        assertThat(byName.get("FilmFilter").registersFetchers()).isFalse();
    }
}
