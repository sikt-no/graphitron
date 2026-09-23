package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * Every stage-written table holds exactly the rows the view stating its rule computes, on a store a
 * capture filled.
 *
 * <p>The oracle a conversion rests on. A stage moves who evaluates a rule and when, and its whole
 * safety argument is that it moves nothing else; {@code EXCEPT} in both directions between the
 * table and the rule view is that argument as a query. It is a standing assertion rather than a
 * one-commit check because the rule survives the conversion: as long as both relations exist the
 * comparison is runnable, which is the property that keeping the rule a stored view buys and
 * moving its text into the stage would spend.
 *
 * <p>Both directions, because they fail differently and each on its own is half an oracle. Rows in
 * the table and not in the rule are rows a previous capture left behind, which is what an appending
 * stage or a mis-scoped {@code DELETE} produces; rows in the rule and not in the table are rows the
 * stage did not write, which is what a predicate in the wrong place produces.
 *
 * <p>Over a captured store rather than a seeded one, and over a fixture that reaches every arm of
 * the rule: a comparison between two empty relations passes while asserting nothing, and one over a
 * single arm passes while asserting a third of the rule. The non-vacuity cases below are what say
 * the fixture did its job.
 */
class StageAnswerAgreementTest {

    @TempDir
    Path tmp;

    /** One stage: the table it writes and the view stating the rule it inserts from. */
    private record Stage(String target, String ruleView) {}

    /** The stage-written tables and their rules, in the stratum's order. */
    private static final List<Stage> STAGES = List.of(
        new Stage("graphitron_field_column_scope", "graphitron_field_column_scope_rule"),
        new Stage("graphitron_carrier_data_field", "graphitron_carrier_data_field_rule"),
        new Stage("graphitron_field_scope_table", "graphitron_field_scope_table_rule"),
        new Stage("graphitron_argument_scope_table", "graphitron_argument_scope_table_rule"),
        new Stage("graphitron_input_field_resolving_table",
            "graphitron_input_field_resolving_table_rule"),
        new Stage("graphitron_argument_column_scope", "graphitron_argument_column_scope_rule"),
        new Stage("graphitron_argument_column_match", "graphitron_argument_column_match_rule"),
        new Stage("graphitron_mutation_write_payload", "graphitron_mutation_write_payload_rule"),
        new Stage("graphitron_node_id_instruction", "graphitron_node_id_instruction_rule"),
        new Stage("graphitron_node_id_decode_hop", "graphitron_node_id_decode_hop_rule"),
        new Stage("graphitron_node_id_decode_hop_column", "graphitron_node_id_decode_hop_column_rule"),
        new Stage("graphitron_node_id_decode_column", "graphitron_node_id_decode_column_rule"),
        new Stage("graphitron_input_field_column_match", "graphitron_input_field_column_match_rule"),
        new Stage("graphitron_input_field_filter_role", "graphitron_input_field_filter_role_rule"),
        new Stage("graphitron_input_field_carrier_role", "graphitron_input_field_carrier_role_rule"),
        new Stage("graphitron_mutation_payload_refusal", "graphitron_mutation_payload_refusal_rule"),
        new Stage("graphitron_mutation_payload_column", "graphitron_mutation_payload_column_rule"),
        new Stage("graphitron_mutation_payload_key_membership", "graphitron_mutation_payload_key_membership_rule"),
        new Stage("graphitron_mutation_write_destination", "graphitron_mutation_write_destination_rule"));

    @Test
    @DisplayName("every stage-written table holds exactly its rule's rows, both directions")
    void everyStageAgreesWithTheRuleItInsertsFrom() {
        for (var fixture : Fixture.values()) {
            withCapturedStore(fixture, dsl -> {
                for (Stage stage : STAGES) {
                    assertThat(difference(dsl, stage.target(), stage.ruleView()))
                        .as(stage.target() + " holds rows " + stage.ruleView() + " does not compute"
                            + " on the " + fixture + " fixture; a previous capture's rows the stage"
                            + " did not clear, or rows written twice")
                        .isEmpty();
                    assertThat(difference(dsl, stage.ruleView(), stage.target()))
                        .as(stage.ruleView() + " computes rows " + stage.target() + " does not hold"
                            + " on the " + fixture + " fixture; the stage did not write what the"
                            + " rule states")
                        .isEmpty();
                }
            });
        }
    }

    /**
     * Every stage's table holds rows on at least one fixture, so no agreement above is between two
     * empty relations. The per-rule cases below say which arms a fixture reaches; this one says only
     * that no stage is compared over nothing, which is the floor every conversion owes and the one a
     * new stage cannot land without meeting.
     */
    @Test
    @DisplayName("every stage-written table holds rows on at least one fixture")
    void noStageIsComparedOverAnEmptyRelation() {
        var populated = new java.util.HashSet<String>();
        for (var fixture : Fixture.values()) {
            withCapturedStore(fixture, dsl -> STAGES.forEach(stage -> {
                if (dsl.fetchCount(table(name(stage.target().toUpperCase()))) > 0) {
                    populated.add(stage.target());
                }
            }));
        }
        assertThat(STAGES.stream().map(Stage::target).filter(t -> !populated.contains(t)).toList())
            .as("stage-written tables no fixture populates; extend a fixture until one does")
            .isEmpty();
    }

    /**
     * The fixture reaches every rule the column-scope stage states, so the agreement above is over
     * a populated relation and over all three of its arms rather than whichever one a thin schema
     * happened to hit.
     */
    @Test
    @DisplayName("the fixture reaches all three bases of the field-site column scope")
    void theFixtureReachesEveryArmOfTheRule() {
        withCapturedStore(dsl -> assertThat(dsl
                .selectDistinct(org.jooq.impl.DSL.field(name("BASIS"), String.class))
                .from(table(name("GRAPHITRON_FIELD_COLUMN_SCOPE")))
                .fetch(0, String.class))
            .as("the bases the fixture reaches; an agreement over one arm asserts a third of the"
                + " rule and passes all the same")
            .containsExactlyInAnyOrder("PATH_TERMINAL", "NAMED_TYPE_TABLE", "PARENT_BINDING"));
    }

    /**
     * The carrier stage's own non-vacuity case, on the axes its rule actually branches on: the
     * producing family, which decides two of the rule's refusals, and the element kind, which is
     * the three-armed walk the rule performs per channel. A comparison over an empty relation
     * passes while asserting nothing, and one over a single family asserts one family's refusals.
     */
    @Test
    @DisplayName("the fixture reaches two families and two element kinds of the carrier channel")
    void theFixtureReachesMoreThanOneCarrierFamily() {
        withCapturedStore(dsl -> assertThat(dsl
                .select(org.jooq.impl.DSL.field(name("FAMILY"), String.class),
                    org.jooq.impl.DSL.field(name("ELEMENT_KIND"), String.class))
                .from(table(name("GRAPHITRON_CARRIER_DATA_FIELD")))
                .fetch().map(row -> row.value1() + " " + row.value2()))
            .as("the family and element kind pairs the fixture reaches; an agreement over one"
                + " family asserts one family's refusals and passes all the same")
            .containsExactlyInAnyOrder("SERVICE TABLE", "DML TABLE", "DML ID"));
    }

    /**
     * The field-site scope stage's own non-vacuity case, over all four bases: the three ranked
     * rungs and the participant arm that sits beside them. A comparison over one basis asserts one
     * rung of a rule whose whole shape is which rung answers where.
     */
    @Test
    @DisplayName("the fixture reaches all four bases of the field-site scope")
    void theFixtureReachesEveryBasisOfTheFieldScope() {
        withCapturedStore(dsl -> assertThat(dsl
                .selectDistinct(org.jooq.impl.DSL.field(name("BASIS"), String.class))
                .from(table(name("GRAPHITRON_FIELD_SCOPE_TABLE")))
                .fetch(0, String.class))
            .as("the bases the fixture reaches; an agreement over one rung asserts a quarter of"
                + " the rule and passes all the same")
            .containsExactlyInAnyOrder("NAMED_TYPE_TABLE", "PAYLOAD_TABLE", "MUTATION_TABLE",
                "PARTICIPANT_TABLE"));
    }

    /**
     * The argument-site fan-out's own non-vacuity case. It is a fan-out and not a rule, so what it
     * can get wrong is which arguments a field's rows reach rather than which rung answered; the
     * bases are asserted all the same, because a fan-out over one basis is a fan-out over one arm
     * of the relation it fans.
     *
     * <p>Three of the four rather than all four, and the missing one is the participant arm: it
     * answers at a field returning a multi-table container, and reaching it through the fan-out
     * needs such a field to declare an argument. The case above pins that arm on the relation whose
     * rule it is, which is where the arm is stated.
     */
    @Test
    @DisplayName("the fixture fans three bases of the field-site scope out over arguments")
    void theFixtureReachesMoreThanOneBasisOfTheArgumentScope() {
        withCapturedStore(dsl -> assertThat(dsl
                .selectDistinct(org.jooq.impl.DSL.field(name("BASIS"), String.class))
                .from(table(name("GRAPHITRON_ARGUMENT_SCOPE_TABLE")))
                .fetch(0, String.class))
            .as("the bases the fan-out reaches; an agreement over one of them is an agreement over"
                + " one arm of the relation being fanned out")
            .containsExactlyInAnyOrder("NAMED_TYPE_TABLE", "PAYLOAD_TABLE", "MUTATION_TABLE"));
    }

    /**
     * The input-field resolving table's own non-vacuity case, on the property its key exists for: a
     * field resolves against a table it is handed, so the relation is only asserting anything once
     * the fixture reaches an input type from an argument whose field is rooted somewhere.
     */
    @Test
    @DisplayName("the fixture reaches an input field under a resolving table")
    void theFixtureReachesAnInputFieldResolvingTable() {
        withCapturedStore(dsl -> assertThat(dsl
                .selectDistinct(org.jooq.impl.DSL.field(name("TABLE_NAME"), String.class))
                .from(table(name("GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE")))
                .fetch(0, String.class))
            .as("the tables the fixture classifies an input field against; an agreement over an"
                + " empty relation asserts nothing at all")
            .containsExactly("film"));
    }

    private static List<String> difference(DSLContext dsl, String left, String right) {
        return dsl.select(asterisk()).from(table(name(left.toUpperCase())))
            .except(select(asterisk()).from(table(name(right.toUpperCase()))))
            .fetch().stream()
            .map(Object::toString)
            .toList();
    }

    /**
     * The captured schemas the agreement runs over. {@link #sdl()} reaches every arm the per-rule
     * cases name; the scaled fixture reaches the {@code @nodeId} decode chain and the
     * input-field roles with rows to compare; {@link #mutationSdl()} reaches the write payload's
     * refusal, key membership and destination, which neither of the other two populates.
     */
    private enum Fixture { ARMS, NODE_ID, MUTATION }

    private void withCapturedStore(java.util.function.Consumer<DSLContext> body) {
        withCapturedStore(Fixture.ARMS, body);
    }

    private void withCapturedStore(Fixture fixture, java.util.function.Consumer<DSLContext> body) {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        String schema = switch (fixture) {
            case ARMS -> sdl();
            case NODE_ID -> no.sikt.graphitron.model.test.ScaledSchemaFixture.scaledSdl(2);
            case MUTATION -> mutationSdl();
        };
        try (var store = CapturedStore.ownStoreOfCatalog(
                tmp.resolve("stages-" + fixture.name().toLowerCase()), schema, jooq)) {
            body.accept(store.dsl());
        }
    }

    /**
     * Three UPDATEs and a DELETE over film: one whose payload the walkers admit whole and match
     * through the primary key, one carrying a field that names no column, one carrying a
     * {@code @nodeId} field decoding through a key film declares on language, and a DELETE by key.
     * Between them they put rows in every relation of the write chain, the refusal and the key
     * membership among them.
     */
    private static String mutationSdl() {
        return """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
              name: String
            }
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String
            }
            input FilmLanguageInput {
              filmId: Int! @field(name: "film_id")
              language: ID @nodeId(typeName: "Language")
            }
            input FilmBadInput {
              filmId: Int! @field(name: "film_id")
              nonsense: String
            }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            type Query { films: [Film!]! }
            type Mutation {
              updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE)
              updateFilmBad(in: FilmBadInput!): Film @mutation(typeName: UPDATE)
              updateFilmLanguage(in: FilmLanguageInput!): Film @mutation(typeName: UPDATE)
              deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
            }
            """;
    }

    /**
     * One schema reaching every arm each stage above branches on. For the field-site column scope,
     * all three navigation rules: an authored {@code @reference} path whose terminal element names
     * a table (PATH_TERMINAL), an object-typed field whose named type carries its own binding
     * (NAMED_TYPE_TABLE), and leaf fields resolving in their parent's binding (PARENT_BINDING). For
     * the carrier data channel, two of the three producing families and two of the three element
     * kinds: a {@code @service} carrier wrapping a bound type, an INSERT carrier wrapping the same,
     * and a DELETE echo wrapping the {@code ID} scalar. For the field-site scope, all four bases:
     * the INSERT carrier is the payload rung, the DELETE echo the {@code @mutation(table:)} rung,
     * every object-typed field into a bound type the named-type rung, and the interface over two
     * bound implementers the participant arm beside them.
     */
    private static String sdl() {
        return """
            type Film implements Media @table(name: "film") {
              title: String
              language: Language
              actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey"},
                                                 {key: "film_actor_actor_id_fkey"}])
            }
            type Language @table(name: "language") {
              name: String
            }
            type Actor implements Media @table(name: "actor") {
              title: String @field(name: "first_name")
            }
            interface Media {
              title: String
            }
            type Query {
              films(title: String): [Film!]!
              media: [Media!]!
            }
            input FilmInput {
              title: String
            }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
              path: [String!]!
              message: String!
            }
            union WriteError = DbErr
            type CreateFilmPayload {
              film: Film
              errors: [WriteError]
            }
            type InsertFilmPayload {
              film: Film
              errors: [WriteError]
            }
            type DeleteFilmPayload {
              deletedId: ID
              errors: [WriteError]
            }
            type Mutation {
              createFilm: CreateFilmPayload
                @service(service: {className: "com.example.FilmService", method: "create"})
              insertFilm(in: FilmInput!): InsertFilmPayload
                @mutation(typeName: INSERT, table: "film")
              deleteFilm(filmId: Int): DeleteFilmPayload
                @mutation(typeName: DELETE, table: "film")
            }
            """;
    }
}
