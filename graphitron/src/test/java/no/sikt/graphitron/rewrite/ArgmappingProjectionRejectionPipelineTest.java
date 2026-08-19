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
     * The grammar widening admits one segment past an ID at every site that spells an argMapping,
     * and the audit of the six call sites found no site-local gate that would stop an uninterpreted
     * one: each site's post-resolution leaf check reads ServiceCatalog.resolvePathLeafType, which
     * returns null the moment a path descends through a non-input-object, and every consumer of a
     * null leaf type passes through rather than rejecting. So the widening's safety is entirely the
     * detections', and these are the cases that hold them to it.
     *
     * Each fixture opens an ID that declares no @nodeId, which is the shape with no other judge: a
     * declared decode is rejected whether or not its site emits, whereas this one used to be the
     * walk's traversal rejection and is now nobody's but the store's. The five sites that resolve a
     * leaf assert the verdict reaches the build; the sixth resolves against an empty slot map and so
     * mints no binding at all, which is the same obligation met one step earlier.
     */

    /** The {@code @routine} site, and the motivating case's own shape. */
    @Test
    void anUndeclaredNodeIdFailsTheBuildAtTheRoutineSite(@TempDir Path tmp) throws IOException {
        assertUndeclared(tmp, """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: ID!, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """);
    }

    /** The {@code @service} site. */
    @Test
    void anUndeclaredNodeIdFailsTheBuildAtTheServiceSite(@TempDir Path tmp) throws IOException {
        assertUndeclared(tmp, """
            input GreetInput { actorId: ID! }
            type Query {
                greeting(in: GreetInput!): String @service(service: {
                    className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                    method: "greet",
                    argMapping: "userId: in.actorId.actor_id"
                })
            }
            """);
    }

    /** The output-field {@code @condition} site. */
    @Test
    void anUndeclaredNodeIdFailsTheBuildAtTheFieldConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUndeclared(tmp, """
            type Film @table(name: "film") { title: String }
            input FilmPick { filmId: ID! }
            type Query {
                films(in: FilmPick!): [Film!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: in.filmId.film_id"
                })
            }
            """);
    }

    /** The argument-site {@code @condition}, whose one slot is the argument the directive sits on. */
    @Test
    void anUndeclaredNodeIdFailsTheBuildAtTheArgumentConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUndeclared(tmp, """
            type Film @table(name: "film") { title: String }
            input FilmPick { filmId: ID! }
            type Query {
                films(in: FilmPick! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: in.filmId.film_id"
                })): [Film!]!
            }
            """);
    }

    /** The input-field {@code @condition}, whose one slot is the input field itself. */
    @Test
    void anUndeclaredNodeIdFailsTheBuildAtTheInputFieldConditionSite(@TempDir Path tmp)
            throws IOException {
        assertUndeclared(tmp, """
            type Film @table(name: "film") { title: String }
            input FilmPick {
                filmId: ID! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "filmIdCondition",
                    argMapping: "filmId: filmId.film_id"
                })
            }
            type Query { films(in: FilmPick!): [Film!]! }
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

    /** That the undeclared-decode verdict reaches the build's own verdict, whatever the site. */
    private static void assertUndeclared(Path tmp, String sdl) {
        assertThatThrownBy(() -> validate(tmp, sdl))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the widened segment is admitted by the walk and rejected by the store")
                .anyMatch(m -> m.contains("declares no @nodeId")
                    && m.contains("no node identity to project a key column out of")));
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
