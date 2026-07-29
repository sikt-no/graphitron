package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
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
}
