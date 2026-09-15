package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code @routine} spends, and what survives it. A {@code @routine} binds its IN parameters
 * from the field's arguments, and the question this pins is at which grain that consumption
 * happens: the leaves the {@code argMapping} names, or the whole arguments those leaves sit in.
 *
 * <p>The distinction is not academic. At argument grain every other field of an input object one
 * binding reaches into is published in the emitted SDL, accepted from a client, and discarded
 * without a verdict, so a client that filters gets rows the filter named. At leaf grain only the
 * bound leaves are spent, every other leaf is an ordinary filter against the chain terminus, and a
 * leaf naming no column of it fails the build exactly as it would on a table-backed field.
 *
 * <p>The first case below is the shape the whole design turns on, and it is written first because
 * every plan that drops the spent leaves <em>after</em> classification fails it: a spent
 * {@code @nodeId} leaf cannot key against a routine result table, which is neither a node type's
 * table nor foreign-key-reachable from one, so classifying it and dropping it afterwards rejects
 * the whole argument before anything is dropped. What the cases assert is therefore not only the
 * verdicts but that the withholding happens before the gate.
 *
 * <p>Emission is asked of the classified filter set rather than of rendered code: whether the
 * predicate reaches SQL and excludes rows is a row-level fact and belongs to the execution tier,
 * where {@code RoutineFieldExecutionTest} asks it of a running query.
 */
@PipelineTier
class RoutineSpentInputPipelineTest {

    /**
     * The node type the projected binding decodes against, and the routine's own result as a
     * {@code @table} type. With no {@code @reference} hop the chain terminus is the function
     * result, so {@code film_id} and {@code title} are the columns a surviving leaf can name.
     */
    private static final String PRELUDE = """
        type Actor implements Node @table(name: "actor") @node(keyColumns: ["actor_id"]) { id: ID! }

        type ActorFilm @table(name: "films_for_actor") {
          filmId: Int @field(name: "film_id")
          title:  String
        }
        """;

    // ===== Reads =====

    /**
     * The Goal's own schema, asserted green. Two leaves are spent and they are spent two different
     * ways: {@code actorId} by the key projection that hands {@code pActorId} a decoded
     * {@code actor_id}, {@code minLength} as an ordinary scalar read into {@code pMinLength}. The
     * third leaf survives and filters the function's result by name.
     */
    @Test
    void spentLeavesLeaveTheirSiblingsToFilterTheResult() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              title:     String
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(bundle.model().diagnostics())
            .as("the spent @nodeId leaf never meets the result table, so nothing is rejected")
            .isEmpty();
        assertThat(field(bundle, "Query", "actorFilms"))
            .isInstanceOf(QueryField.QueryTableField.class);
        assertThat(filteredColumns(bundle, "Query", "actorFilms"))
            .as("the survivor's predicate, and only the survivor's")
            .containsExactly("title");
    }

    /** A surviving leaf whose name differs from its column binds through {@code @field(name:)}. */
    @Test
    void aSurvivingLeafBindsThroughFieldName() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              named:     String @field(name: "title")
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(bundle.model().diagnostics()).isEmpty();
        assertThat(filteredColumns(bundle, "Query", "actorFilms")).containsExactly("title");
    }

    /**
     * A surviving leaf naming no column of the result lands the rejection it already lands on a
     * table-backed field. No new diagnostic was needed for it: the rule that governs every other
     * filter in the tree now reaches inside a routine argument too.
     */
    @Test
    void aSurvivingLeafNamingNothingLandsTheExistingNoBindingRejection() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              foo:       String
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(field(bundle, "Query", "actorFilms")).isInstanceOf(UnclassifiedField.class);
        assertThat(bundle.model().diagnostics())
            .as("located at the leaf, naming the result table it was looked for in")
            .anySatisfy(d -> {
                assertThat(d.coordinate()).isEqualTo("ActorFilmFilter.foo");
                assertThat(d.rejection().message())
                    .contains("Query.actorFilms(filter)/foo")
                    .contains("films_for_actor");
            });
    }

    /**
     * A leaf bound one level below the argument is withheld too, which is what pins the nesting
     * enumerator's skip rather than only the argument's own top level. The input tree expands as
     * deep as it nests and an author may reach into any of it.
     */
    @Test
    void aSpentLeafNestedBelowTheArgumentIsWithheldToo() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input Bounds { minLength: Int }
            input ActorFilmFilter {
              actorId: ID! @nodeId(typeName: "Actor")
              bounds:  Bounds
              title:   String
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.bounds.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(bundle.model().diagnostics())
            .as("the nested leaf names no result column, and is not asked to")
            .isEmpty();
        assertThat(filteredColumns(bundle, "Query", "actorFilms")).containsExactly("title");
    }

    /**
     * A spent leaf asking for a filter is a directive conflict: the routine parameter consumes it
     * before any read surface is reached, so the WHERE clause it asks for would silently do
     * nothing, which is this item's own failure class one level down.
     */
    @Test
    void aSpentLeafCarryingAFilterDirectiveIsAConflict() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int @field(name: "title")
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(rejectionOf(bundle, "Query", "actorFilms"))
            .isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(rejectionOf(bundle, "Query", "actorFilms").message())
            .contains("Query.actorFilms(filter)/minLength")
            .contains("pMinLength");
    }

    /**
     * {@code @nodeId} on a spent leaf is not the conflict above, the projection being its consumer
     * rather than a filter it never gets. Asserted on a field that otherwise classifies, so the
     * case cannot pass by the argument having been rejected for some other reason.
     */
    @Test
    void aSpentLeafCarryingNodeIdIsNotAConflict() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              title:     String
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(field(bundle, "Query", "actorFilms"))
            .isInstanceOf(QueryField.QueryTableField.class);
        assertThat(bundle.model().diagnostics()).isEmpty();
    }

    /**
     * The other direction of the same change. An <em>unspent</em> {@code @nodeId} leaf is no
     * longer hidden behind the argument-grain skip: it meets the result table like any other
     * filter leaf, cannot key against it, and rejects the argument. That is the honest verdict (a
     * {@code @nodeId} leaf that cannot key a table cannot filter it), and it is the same gate the
     * spent leaf is withheld from, so both directions are pinned rather than left to be noticed.
     */
    @Test
    void anUnspentNodeIdLeafThatKeysNothingRejectsTheArgument() {
        var bundle = TestSchemaHelper.buildBundle(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              other:     ID  @nodeId(typeName: "Actor")
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);

        assertThat(field(bundle, "Query", "actorFilms")).isInstanceOf(UnclassifiedField.class);
        assertThat(bundle.model().diagnostics())
            .anySatisfy(d -> assertThat(d.coordinate()).isEqualTo("ActorFilmFilter.other"));
        assertThat(bundle.decodeLedger().rows())
            .as("the sibling the argument-grain skip hid now has a disposition of its own")
            .containsKey(new NodeIdDecodeCoordinate.InputField("Query", "actorFilms", "filter",
                List.of(new NodeIdDecodeCoordinate.Step("ActorFilmFilter", "other"))));
    }

    // ===== Writes =====

    private static final String WRITE_PRELUDE = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        """;

    /**
     * A Mutation {@code @routine} field resolves no filter surface at all, so an input leaf the
     * bindings do not name reaches nothing and is a build error, as it already is on a DML
     * mutation input. This is the chain seat.
     */
    @Test
    void anUnreadLeafOnAMutationRoutineChainIsRejected() {
        var bundle = TestSchemaHelper.buildBundle(WRITE_PRELUDE + """
            input RentFilmInput { inventoryId: Int! customerId: Int! neverRead: String }
            type Mutation {
              rentFilm(input: RentFilmInput!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                @reference(path: [{table: "rental"}])
            }
            """);

        assertThat(field(bundle, "Mutation", "rentFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(unreadVerdicts(bundle)).containsExactly("Mutation.rentFilm(input)/neverRead");
    }

    /**
     * The carrier seat, reached by a different dispatch and sharing no classifier with the chain
     * seat above. Asserted against the same fixture with its unread leaf removed, which classifies
     * to {@link MutationField.MutationRoutineWriteRecordField}, so the rejected case cannot pass
     * by the field having been refused for some other reason.
     */
    @Test
    void anUnreadLeafOnAMutationRoutineCarrierIsRejected() {
        String carrier = """
            type RentFilmPayload { rental: Rental }
            type Mutation {
              rentFilm(input: RentFilmInput!): RentFilmPayload
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
            }
            """;
        var bound = TestSchemaHelper.buildBundle(WRITE_PRELUDE
            + "input RentFilmInput { inventoryId: Int! customerId: Int! }\n" + carrier);
        assertThat(field(bound, "Mutation", "rentFilm"))
            .as("the same field with every leaf bound reaches the carrier leaf")
            .isInstanceOf(MutationField.MutationRoutineWriteRecordField.class);

        var unread = TestSchemaHelper.buildBundle(WRITE_PRELUDE
            + "input RentFilmInput { inventoryId: Int! customerId: Int! neverRead: String }\n" + carrier);
        assertThat(field(unread, "Mutation", "rentFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(unreadVerdicts(unread)).containsExactly("Mutation.rentFilm(input)/neverRead");
    }

    /**
     * The zero-depth arm of the same walk: an argument slot the bindings never name is its own
     * leaf, so an unread flat argument is the same rejection rather than a second rule.
     */
    @Test
    void anUnreadFlatArgumentOnAMutationRoutineIsRejected() {
        var bundle = TestSchemaHelper.buildBundle(WRITE_PRELUDE + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!, note: String): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);

        assertThat(field(bundle, "Mutation", "rentFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(unreadVerdicts(bundle)).containsExactly("Mutation.rentFilm(note)");
    }

    // ===== Validator reach =====

    /**
     * Both new rejections reach the validator, and they reach it through the diagnostic drain
     * rather than through a rule of the validator's own: {@code drainBuildDiagnostics} re-surfaces
     * what the classification walk minted, coordinate, typed rejection and location unchanged, so
     * what an author sees in a build and what the LSP shows at a leaf are the same value. A second
     * spelling in {@code GraphitronSchemaValidator} would show up here as a duplicate.
     */
    @Test
    void bothNewRejectionsReachTheValidatorThroughTheDrain() {
        var leftover = TestSchemaHelper.buildSchema(PRELUDE + """
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
              foo:       String
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """);
        assertThat(new GraphitronSchemaValidator().validate(leftover))
            .filteredOn(e -> "ActorFilmFilter.foo".equals(e.coordinate()))
            .as("one verdict, drained rather than restated")
            .singleElement()
            .satisfies(e -> assertThat(e.location()).isNotNull());

        var unread = TestSchemaHelper.buildSchema(WRITE_PRELUDE + """
            input RentFilmInput { inventoryId: Int! customerId: Int! neverRead: String }
            type Mutation {
              rentFilm(input: RentFilmInput!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        assertThat(new GraphitronSchemaValidator().validate(unread))
            .filteredOn(e -> e.rejection().message().contains("is read by nothing"))
            .as("the write seat's verdict likewise arrives once, located at the leaf it names")
            .singleElement()
            .satisfies(e -> {
                assertThat(e.rejection().message()).contains("Mutation.rentFilm(input)/neverRead");
                assertThat(e.location()).isNotNull();
            });
    }

    // ===== Helpers =====

    private static GraphitronField field(GraphitronSchemaBuilder.Bundle bundle,
                                        String typeName, String fieldName) {
        return bundle.model().field(typeName, fieldName);
    }

    private static Rejection rejectionOf(GraphitronSchemaBuilder.Bundle bundle,
                                         String typeName, String fieldName) {
        return ((UnclassifiedField) field(bundle, typeName, fieldName)).rejection();
    }

    /** The SQL column names the field's classified filters compare, in declaration order. */
    private static List<String> filteredColumns(GraphitronSchemaBuilder.Bundle bundle,
                                                String typeName, String fieldName) {
        return ((QueryField.QueryTableField) field(bundle, typeName, fieldName)).filters().stream()
            .flatMap(f -> ((no.sikt.graphitron.rewrite.model.GeneratedConditionFilter) f)
                .bodyParams().stream())
            .map(b -> ((BodyParam.Eq) b).column().sqlName())
            .toList();
    }

    /** The coordinates the write seat's unread-input verdicts name, one per unread leaf. */
    private static List<String> unreadVerdicts(GraphitronSchemaBuilder.Bundle bundle) {
        return bundle.model().diagnostics().stream()
            .map(d -> d.rejection().message())
            .filter(m -> m.contains("is read by nothing"))
            .map(m -> m.substring(m.indexOf('\'') + 1, m.indexOf('\'', m.indexOf('\'') + 1)))
            .toList();
    }
}
