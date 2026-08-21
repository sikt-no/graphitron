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
     * base64 wire id to {@code rent_film}'s parameter; now it fails the build naming the count and
     * the columns the author has to choose between.
     *
     * <p>Two key columns, because one is no longer a rejection at all: a one-column key is inferred
     * and the projection resolves, which is the case below. A gate about failing the build has to
     * stand on a schema that still fails it.
     */
    @Test
    void aBareNodeIdBindingFailsTheBuild(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Inventory @table(name: "inventory") @node(keyColumns: ["inventory_id", "store_id"]) {
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
                .anyMatch(m -> m.contains("whose key is 2 columns")
                    && m.contains("open it with one of them: inventory_id, store_id")));
    }

    /**
     * The same shape against a one-column key builds clean, and this is the half of the arity rule
     * only this tier can state. The tiers below say the relation resolves and the detection reports
     * nothing; neither says the build then completes.
     *
     * <p>Two gates had to agree for this to be true, and the second is why the case is here rather
     * than beside the detection. The walk's own routine leaf-type gate compares graphql-java's
     * coercion output for the SDL leaf against the parameter's declared Java type, and an
     * {@code ID!} bound to an {@code Integer} parameter is exactly what it rejects. It now stands
     * aside on a {@code @nodeId} leaf, that value being decoded before the parameter sees it, so the
     * comparison was between two things that never meet. The authored spelling escaped that gate
     * only because a path descending past a scalar resolves no leaf type, which is an accident of
     * path shape rather than a rule, and the bare spelling has no such accident to rely on.
     */
    @Test
    void aBareNodeIdAgainstAOneColumnKeyBuildsClean(@TempDir Path tmp) throws IOException {
        assertThatCode(() -> validate(tmp, """
            interface Node { id: ID! }
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
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
            .as("the sole key column is inferred, so there is nothing for the build to refuse")
            .doesNotThrowAnyException();
    }

    /**
     * The gate that stood aside above still rejects where no decode is in play. An {@code ID!} bound
     * to an {@code Integer} routine parameter with no {@code @nodeId} on it is the coercion failure
     * that gate exists for, and its message offers the decode as one of the remedies. Without this
     * case the stand-aside would be indistinguishable from removing the gate.
     */
    @Test
    void anIdBoundToAnIntegerParameterWithNoDecodeStillFailsTheBuild(@TempDir Path tmp)
            throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: ID!, customerId: Int! }
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
                .as("the coercion gate is intact where the leaf carries no decode")
                .anyMatch(m -> m.contains("@routine parameter 'pInventoryId'")
                    && m.contains("cannot be cast to the declared Java type")));
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
     * Each fixture spells a key column the node type does not have, which is one of six things the
     * store decides about such a path. The walk decides none of them: it carries every segment it
     * cannot resolve against SDL, so each of these reaches capture and is rejected by a view arm.
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
     * An {@code ID} declaring no {@code @nodeId} has nothing to open. Same obligation as the per-site
     * cases above and the same owner: the walk carries the segment, the store's
     * {@code UNDECLARED_NODE_ID} arm judges it, and the build fails. One fixture rather than six,
     * because the rule sits on a relation keyed by the pair rather than on any one site's emitter.
     */
    @Test
    void anIdDeclaringNoNodeIdFailsTheBuild(@TempDir Path tmp) throws IOException {
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
                .as("what opens is a node id, and the store says so about one that is not")
                .anyMatch(m -> m.contains("has nothing to open")
                    && m.contains("that ID declares no @nodeId")));
    }

    /**
     * A column the node type does have, whose Java type the routine parameter cannot take. The path
     * resolves, the segment names a real key column, and the only thing wrong is a type comparison
     * the shared coercion gate cannot make, that gate reading an SDL leaf type a path descending past
     * a scalar never resolves. It used to reach the consumer as a javac error inside generated code;
     * it fails the build here, naming both types.
     *
     * <p>The message is pinned here in full rather than in the derive tier beside the other three
     * verdicts, and the reason is the harness: {@code CapturedStore} captures SDL and no jOOQ
     * catalog, so a projected column has no type there to compare. This is the tier with a catalog
     * on both sides.
     */
    @Test
    void aKeyColumnTheParameterCannotTakeFailsTheBuild(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Customer @table(name: "customer") @node(keyColumns: ["first_name"]) {
                id: ID! @nodeId
            }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, customer: Customer }
            input RentFilmInput { customerRef: ID! @nodeId(typeName: "Customer"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: input.customerRef.first_name, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the projected column's type and the parameter's are both named")
                .contains("Field 'Mutation.rentFilm': @routine argMapping entry"
                    + " 'pInventoryId: input.customerRef.first_name' at Mutation.rentFilm#0 projects"
                    + " 'first_name' of 'Customer', which jOOQ binds as String, but the parameter it"
                    + " binds to takes Integer; bind a parameter of the column's own type"));
    }

    /**
     * The same mismatch reached without the author naming the column, which is what makes lifting the
     * bare rejection at arity 1 safe rather than lenient. The entry spells no key column, the sole
     * one is inferred, and the type gate on it still fails the build. Without this case the arity
     * rule would read as trading a rejection for silence.
     *
     * <p>The message names the inferred column rather than quoting a segment the author never wrote,
     * and it offers only the one remedy: with one key column there is no other to project instead.
     */
    @Test
    void anInferredKeyColumnTheParameterCannotTakeFailsTheBuild(@TempDir Path tmp)
            throws IOException {
        assertThatThrownBy(() -> validate(tmp, """
            type Customer @table(name: "customer") @node(keyColumns: ["first_name"]) {
                id: ID! @nodeId
            }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, customer: Customer }
            input RentFilmInput { customerRef: ID! @nodeId(typeName: "Customer"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film", argMapping: "pInventoryId: input.customerRef, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the inferred column is named, and the second remedy has nothing to offer")
                .contains("Field 'Mutation.rentFilm': @routine argMapping entry"
                    + " 'pInventoryId: input.customerRef' at Mutation.rentFilm#0 binds the"
                    + " @nodeId(typeName: \"Customer\"), whose key column 'first_name' jOOQ binds as"
                    + " String, but the parameter it binds to takes Integer; bind a parameter of the"
                    + " column's own type"));
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
