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

    // ===== The widening's blast radius: one case per ArgBindingMap.of call site =====

    /*
     * The grammar widening admits one segment past a node id at every site that spells an argMapping,
     * and the audit of the six call sites found no site-local gate that would stop an uninterpreted
     * one: each site's post-resolution leaf check reads ServiceCatalog.resolvePathLeafType, which
     * returns null the moment a path descends through a non-input-object, and every consumer of a
     * null leaf type passes through rather than rejecting. So the widening's safety is entirely the
     * store detections', and these are the cases that hold them to it.
     *
     * Each fixture spells a key column the node type does not have. That is the shape with no other
     * judge: the walk resolves the path (the leaf is a declared node id, so the dot opens) and cannot
     * tell a real key column from a typo, which is a resolution against the node type's key list and
     * therefore the store's. An undeclared @nodeId is deliberately not the fixture here, that being
     * the walk's own rejection, pinned per site by the case at the end.
     *
     * The sixth site resolves against an empty slot map and so mints no binding at all, which is the
     * same obligation met one step earlier.
     */

    /** The node type every fixture below opens, and the one key column it actually resolves. */
    private static final String INVENTORY_NODE = """
        type Inventory @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID! @nodeId
        }
        """;

    /** The {@code @routine} site, and the motivating case's own shape. */
    @Test
    void anUnknownKeyColumnFailsTheBuildAtTheRoutineSite(@TempDir Path tmp) throws IOException {
        assertUnknownColumn(tmp, INVENTORY_NODE + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, inventory: Inventory }
            input RentFilmInput { inventoryId: ID! @nodeId(typeName: "Inventory"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventry_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """);
    }

    /** The {@code @service} site. */
    @Test
    void anUnknownKeyColumnFailsTheBuildAtTheServiceSite(@TempDir Path tmp) throws IOException {
        assertUnknownColumn(tmp, INVENTORY_NODE + """
            input GreetInput { inventoryId: ID! @nodeId(typeName: "Inventory") }
            type Query {
                inventory: Inventory
                greeting(in: GreetInput!): String @service(service: {
                    className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                    method: "greet",
                    argMapping: "userId: in.inventoryId.inventry_id"
                })
            }
            """);
    }

    /** The output-field {@code @condition} site. */
    @Test
    void anUnknownKeyColumnFailsTheBuildAtTheFieldConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUnknownColumn(tmp, INVENTORY_NODE + """
            type Film @table(name: "film") { title: String }
            input FilmPick { inventoryId: ID! @nodeId(typeName: "Inventory") }
            type Query {
                inventory: Inventory
                films(in: FilmPick!): [Film!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: in.inventoryId.inventry_id"
                })
            }
            """);
    }

    /** The argument-site {@code @condition}, whose one slot is the argument the directive sits on. */
    @Test
    void anUnknownKeyColumnFailsTheBuildAtTheArgumentConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUnknownColumn(tmp, INVENTORY_NODE + """
            type Film @table(name: "film") { title: String }
            input FilmPick { inventoryId: ID! @nodeId(typeName: "Inventory") }
            type Query {
                inventory: Inventory
                films(in: FilmPick! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: in.inventoryId.inventry_id"
                })): [Film!]!
            }
            """);
    }

    /** The input-field {@code @condition}, whose one slot is the input field itself. */
    @Test
    void anUnknownKeyColumnFailsTheBuildAtTheInputFieldConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUnknownColumn(tmp, INVENTORY_NODE + """
            type Film @table(name: "film") { title: String }
            input FilmPick {
                inventoryId: ID! @nodeId(typeName: "Inventory") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: inventoryId.inventry_id"
                })
            }
            type Query { inventory: Inventory, films(in: FilmPick!): [Film!]! }
            """);
    }

    /**
     * The path-step {@code @condition}, the sixth call site, which resolves against an empty slot
     * map. No head can name a slot there, so the widened tail is never reached and no binding is
     * minted: the obligation is met by the walk's own unknown-slot rejection rather than by the
     * store, which sees a pair row with no leaf and correctly says nothing about it.
     */
    @Test
    void aPathStepConditionRejectsTheHeadBeforeTheTailIsReached(@TempDir Path tmp)
            throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") {
                films: [Film!]! @reference(path: [{table: "film_actor", condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.ReferencePathConditionFixtures",
                    method: "filmActorsViaCondition",
                    argMapping: "filmId: in.filmId.film_id"
                }}])
            }
            type Query { actor: Actor }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("no GraphQL arguments are in scope at a path-step @condition, so the head is"
                    + " what fails and the tail is never interpreted")
                .anyMatch(m -> m.contains("references GraphQL argument 'in'")));
    }

    /**
     * The walk's own half of the same obligation: an {@code ID} declaring no {@code @nodeId} has
     * nothing to open, so the path never resolves and no emitter can see it. One fixture rather than
     * six, because the rule belongs to the shared factory and not to each site; what the per-site
     * cases above pin is the half the walk cannot decide, a spelling resolved against the node type's
     * own key list.
     */
    @Test
    void anIdDeclaringNoNodeIdIsRejectedByTheWalk(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: ID!, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("what opens is a node id, so the grammar refuses an ID that is not one")
                .anyMatch(m -> m.contains("has nothing to open")
                    && m.contains("that ID declares no @nodeId")));
    }

    /**
     * That the unknown-key-column verdict reaches the build's own verdict, whatever the site. The
     * walk admits the path and cannot judge the spelling, so this is the store's answer arriving.
     */
    private static void assertUnknownColumn(Path tmp, String sdl) {
        assertThatThrownBy(() -> validate(tmp, sdl))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the widened segment is admitted by the walk and resolved by the store")
                .anyMatch(m -> m.contains("which is not a key column of 'Inventory'")));
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
