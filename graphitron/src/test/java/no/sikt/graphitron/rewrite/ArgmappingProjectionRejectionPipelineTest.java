package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the {@code argMapping} node-id rejections actually fail the build, which is the only claim
 * the tiers below this one cannot make. {@code ArgmappingProjectionDefectsTest} proves the
 * detection mints the violation from a captured store, and the model module's suite proves the view
 * finds the coordinate; neither says the violation reaches
 * {@link GraphQLRewriteGenerator#validate()}'s verdict. A store-backed family whose violations do
 * not is a rule nobody is subject to, and this test is the wiring's gate.
 *
 * <p>Both halves of that wiring matter, so both are asserted: a defective binding throws, and an
 * ordinary one over the same shape does not, which is what keeps the gate from passing because
 * everything fails.
 */
@PipelineTier
class ArgmappingProjectionRejectionPipelineTest {

    /**
     * The silence this family closes. Before the detection, this schema compiled and shipped the
     * base64 wire id to {@code rent_film}'s parameter; now it fails the build naming the key column
     * the author should have opened.
     */
    @Test
    void aBareNodeIdBindingFailsTheBuild(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Inventory @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, inventory: Inventory }
            input RentFilmInput { inventoryId: ID! @nodeId(typeName: "Inventory"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the detection's violation reaches the build's verdict")
                .anyMatch(m -> m.contains("names no key column")
                    && m.contains("open it with one of the key columns of 'Inventory': inventory_id")));
    }

    /**
     * The same shape with no {@code @nodeId} on the bound input field builds clean. Without this the
     * case above would pass for any schema the walk happens to reject, and the gate would say
     * nothing about the rule it is meant to guard.
     */
    @Test
    void theSameShapeWithoutANodeIdBuildsClean(@TempDir Path tmp) throws IOException {
        assertThatCode(() -> validate(tmp, """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: Int!, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """)).doesNotThrowAnyException();
    }

    /** Runs the build-time validate pass over one SDL fixture, capture and detections included. */
    private static void validate(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        new GraphQLRewriteGenerator(new RewriteContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "ArgmappingProjectionRejectionPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        )).validate();
    }
}
