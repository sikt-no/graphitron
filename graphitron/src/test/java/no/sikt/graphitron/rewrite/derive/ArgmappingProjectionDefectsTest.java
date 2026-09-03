package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.derive.ArgmappingProjectionDefects;

/**
 * The store-backed home of the {@code argMapping} node-id rules: real SDL captured into a fact
 * store, and the violations {@link ArgmappingProjectionDefects} projects from the detection view
 * and the projection relation beside it. This is the tier that says an author's schema reaches
 * those relations in the shape the rules read, and that the report a consumer meets is minted from
 * what they find.
 *
 * <p>What the view returns given rows is not asked here. That is the relation's own algebra, its
 * four arms and the absences between them, and it lives in the module whose DDL declares it, in
 * {@code no.sikt.graphitron.model.intent.ArgmappingProjectionDefectTest}, against a store seeded
 * row by row. What stands here is the decode: which {@link Rejection} arm each verdict becomes, the
 * prose it carries, the location it points at, and the deferral the unwired sites produce.
 *
 * <p>The rejections these fixtures assert are the ones that close the family's silence: before them
 * a {@code @nodeId} bound through an {@code argMapping} handed the base64 wire id to a routine
 * parameter or a service method verbatim, and the build said nothing. Every fixture below spells a
 * binding that used to compile.
 */
@PipelineTier
class ArgmappingProjectionDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    @TempDir
    Path tmp;

    // ===== The bare form, which is what used to ship base64 =====

    /**
     * The bare binding an author has to fix, which is the one against a key of more than one column.
     * Two columns and one bound parameter leave nothing to infer, so the message states the count
     * and offers the columns. A one-column key is a different outcome and is asserted below: there
     * the sole column is the only projection the binding could mean, so it resolves.
     */
    @Test
    void aNodeIdBoundWithNoKeyColumnIsRejectedNamingTheKeyColumns() {
        var violations = detect("""
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id", "store_id"]) {
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
            """);

        assertThat(messages(violations)).containsExactly(
            "Field 'Mutation.rentFilm': @routine argMapping entry"
            + " 'pInventoryId: input.inventoryId' at Mutation.rentFilm#0 binds the"
            + " @nodeId(typeName: \"Inventory\"), whose key is 2 columns, and one binding carries one"
            + " value; open it with one of them: inventory_id, store_id");
        assertThat(violations.getFirst().rejection())
            .isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(violations.getFirst().location()).isNotNull();
        assertThat(violations.getFirst().location().getSourceName()).endsWith("fixture.graphqls");
    }

    /**
     * A one-column key is not a bare binding to fix. The sole column is the only thing such an entry
     * could project, so it resolves and the author writes nothing: the same schema that draws the
     * rejection above, with one key column instead of two, mints no author error at all.
     *
     * <p>Asserted as the absence of an author error rather than as an empty report, because the site
     * here is {@code @routine}, which is wired, so a resolved projection is emission. The deferral
     * that would appear at an unwired site is a different claim and has its own case below.
     */
    @Test
    void aOneColumnKeyResolvesRatherThanBeingBare() {
        assertThat(detect("""
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
            .as("the author named no column because there was only one it could be")
            .isEmpty();
    }

    /**
     * The same defect with no {@code typeName:} to name, which is the way to have nothing to infer
     * that no arity rescues: with no node type there is no key list to count. The arm fires either
     * way and the remedy differs in a clause rather than the family growing a verdict; two errors
     * for one entry is what a second verdict would have cost.
     */
    @Test
    void aBareNodeIdWithNoTypeNameIsRejectedNamingBothOmissions() {
        var violations = detect("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: ID! @nodeId, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """);

        assertThat(messages(violations)).containsExactly(
            "Field 'Mutation.rentFilm': @routine argMapping entry"
            + " 'pInventoryId: input.inventoryId' at Mutation.rentFilm#0 binds a @nodeId that names"
            + " no node type, so there is no key to decode it against and nothing to infer a column"
            + " from; specify typeName: on the @nodeId, and open it with a key column if that type's"
            + " key is more than one");
    }

    // ===== The projection was asked for and could not resolve =====

    /**
     * Opening a bare {@code @nodeId} cannot infer a node type: there is no containing table at an
     * {@code argMapping} position to infer one from, which is the same condition an input bean's
     * jOOQ-record-typed member states, and the wording converges with it.
     */
    @Test
    void openingABareNodeIdIsRejectedAsAMissingTypeName() {
        var violations = detect("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: ID! @nodeId, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """);

        assertThat(messages(violations)).contains(
            "Field 'Mutation.rentFilm': @routine argMapping entry"
            + " 'pInventoryId: input.inventoryId.inventory_id' at Mutation.rentFilm#0 opens a"
            + " @nodeId with 'inventory_id', but @nodeId must specify typeName: explicitly at an"
            + " argMapping position, there being no containing table here to name the NodeType to"
            + " decode against");
    }

    /**
     * A trailing segment naming no key column is a typed unknown-name rejection carrying the key
     * list, so an editor offers the candidates as a fix rather than an author reading them out of
     * prose. The attempt kind is what tells this lookup space from the six others.
     */
    @Test
    void anUnknownKeyColumnIsATypedUnknownNameCarryingTheCandidates() {
        var violations = detect("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, inventory: Inventory }
            input RentFilmInput { inventoryId: ID! @nodeId(typeName: "Inventory"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId.inventry_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """);

        var unknown = violations.stream()
            .map(ValidationError::rejection)
            .filter(Rejection.AuthorError.UnknownName.class::isInstance)
            .map(Rejection.AuthorError.UnknownName.class::cast)
            .toList();
        assertThat(unknown).hasSize(1);
        assertThat(unknown.getFirst().attemptKind())
            .isEqualTo(Rejection.AttemptKind.NODEID_KEY_COLUMN);
        assertThat(unknown.getFirst().attempt()).isEqualTo("inventry_id");
        assertThat(unknown.getFirst().candidates()).containsExactly("inventory_id");
        assertThat(unknown.getFirst().message())
            .isEqualTo("Field 'Mutation.rentFilm': @routine argMapping entry"
                + " 'pInventoryId: input.inventoryId.inventry_id' at Mutation.rentFilm#0 names"
                + " 'inventry_id', which is not a key column of 'Inventory'"
                + "; did you mean: inventory_id");
    }

    /*
     * The type-mismatch verdict is deliberately not asserted here, and the reason is the harness
     * rather than the arm: CapturedStore captures SDL and no jOOQ catalog, so sql_column is empty and
     * the projected column has no type to compare. That absence is exactly what the candidate
     * relation's outer reach admits, so a projection still resolves here; the arm needs a catalog on
     * both sides and is pinned where there is one, in ArgmappingProjectionRejectionPipelineTest.
     */

    // ===== The site's emitter is the generator's gap, not the author's mistake =====

    /**
     * A projection that resolves at a site whose emitter reads it is not reported at all. The wired
     * set is what separates this from the deferral below, and asserting the silence is what keeps
     * that set from being decoration: if the deferral arm ignored it, every wired site would still
     * fail its own build.
     */
    @Test
    void aResolvingProjectionAtAWiredSiteMintsNothing() {
        assertThat(detect("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID! @nodeId
            }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental, inventory: Inventory }
            input RentFilmInput { inventoryId: ID! @nodeId(typeName: "Inventory"), customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """))
            .as("@routine is wired, so a resolving projection there is emission rather than a report")
            .isEmpty();
    }

    /**
     * A projection that resolves at a site no emitter reads is not an author defect, and the build
     * says so rather than emitting nothing or emitting the raw base64. A deferral rather than an
     * author error is what carries that distinction into the report. The input-field
     * {@code @condition} is the site this uses because it is the one that stays unwired: its head is
     * the input field itself and its emitter reads no projection.
     */
    @Test
    void aResolvingProjectionDefersWhileNoEmitterReadsIt() {
        var violations = detect(UNWIRED_SITE_SDL);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(violations.getFirst().message())
            .contains("resolves a key column of 'Inventory', which no emitter reads at this site yet");
    }

    /**
     * A composite projection is one deferral per bound parameter, not one per key column of the
     * type. The projection relation's grain is the key column, so a read that did not collapse it
     * would report a two-column key twice for one entry.
     */
    @Test
    void oneDeferralPerBoundParameterRatherThanPerKeyColumn() {
        var violations = detect("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["bar_id", "foo_id"]) {
                id: ID! @nodeId
            }
            type Film @table(name: "film") { title: String }
            type Query { films(in: FilmPick!): [Film!]!, bar: Bar }
            input FilmPick {
                barId: ID! @nodeId(typeName: "Bar") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "rentalRateRange",
                    argMapping: "fra: barId.bar_id, til: barId.foo_id"
                })
            }
            """);

        assertThat(violations)
            .as("two entries bound from one node id are two deferrals, not four")
            .hasSize(2);
        assertThat(violations).allSatisfy(v ->
            assertThat(v.rejection()).isInstanceOf(Rejection.Deferred.class));
    }

    /** An input-field {@code @condition} opening a {@code @nodeId} with its node type's key column. */
    private static final String UNWIRED_SITE_SDL = """
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID! @nodeId
        }
        type Film @table(name: "film") { title: String }
        type Query { films(in: FilmPick!): [Film!]!, inventory: Inventory }
        input FilmPick {
            inventoryId: ID! @nodeId(typeName: "Inventory") @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "filmIdCondition",
                argMapping: "filmId: inventoryId.inventory_id"
            })
        }
        """;

    // ===== The boundary: what the family leaves alone =====

    /** An {@code argMapping} with no {@code @nodeId} anywhere in it is nothing to judge. */
    @Test
    void anOrdinaryArgMappingMintsNothing() {
        assertThat(detect("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            input RentFilmInput { inventoryId: Int!, customerId: Int! }
            type Mutation {
                rentFilm(input: RentFilmInput!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                    @reference(path: [{table: "rental"}])
            }
            """)).isEmpty();
    }

    /**
     * The rules are not the {@code @routine} site's. A {@code @service} argMapping binding a node id
     * bare is the same defect for the same reason, and the message names its own directive, which is
     * the whole argument for resolving at the pair's grain rather than per call site.
     */
    @Test
    void aServiceArgMappingReportsUnderItsOwnDirective() {
        var violations = detect(BARE_SERVICE_SDL);

        assertThat(messages(violations)).containsExactly(
            "Field 'Query.films': @service argMapping entry 'id: filter.inventoryId' binds the"
            + " @nodeId(typeName: \"Inventory\"), whose key is 2 columns, and one binding carries one"
            + " value; open it with one of them: inventory_id, store_id");
    }

    /**
     * A non-repeatable field-grain site adds no use-site clause, the coordinate the error already
     * carries saying everything the use site would. A repeatable one does, its ordinal being the
     * only thing that tells two applications of one directive apart in prose.
     */
    @Test
    void theUseSiteClauseAppearsOnlyWhereItSaysMoreThanTheCoordinate() {
        var service = detect(BARE_SERVICE_SDL);

        assertThat(service.getFirst().rejection())
            .as("the subject is the clause, so this has to be the author arm and not a deferral")
            .isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(messages(service).getFirst())
            .as("the @service site is not repeatable, so the coordinate is the whole answer")
            .doesNotContain(" at Query.films");
    }

    /**
     * A {@code @service} entry binding a two-column node id bare: the shape two cases above need,
     * one for the message and one for the use-site clause. Shared because they must be reading the
     * same violation, and a key of two columns is what keeps it an author error rather than the
     * resolution a one-column key would produce.
     */
    private static final String BARE_SERVICE_SDL = """
        type Inventory implements Node @table(name: "inventory")
                @node(keyColumns: ["inventory_id", "store_id"]) {
            id: ID! @nodeId
        }
        type Film @table(name: "film") { title: String }
        input FilmFilter { inventoryId: ID! @nodeId(typeName: "Inventory") }
        type Query {
            inventory: Inventory
            films(filter: FilmFilter!): [Film!]!
                @service(service: {className: "%s", method: "get",
                                   argMapping: "id: filter.inventoryId"})
        }
        """.formatted(SERVICE_STUB);

    // ===== Helpers =====

    /** Captures {@code sdl} and runs the detection over what the capture wrote. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.of(tmp, sdl)) {
            return ArgmappingProjectionDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** The violations' messages, the surface an author actually meets. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }
}
