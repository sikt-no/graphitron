package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the "list fields require deterministic ordering" check: it fires
 * through the full SDL → classified model → assembled {@code GraphitronSchema} → validator path,
 * not only when fed a hand-constructed fixture. Pins both the classifier/validator wiring and the
 * error-message contract.
 *
 * <p>The plain arm uses {@code film_list}, a Sakila-fixture no-PK table that produces
 * {@code OrderBySpec.None} from {@code OrderByResolver.resolveDefaultOrderSpec} when no
 * {@code @defaultOrder}/{@code @orderBy} is declared. The {@code @routine} arms below are the
 * other half of one rule: a function result is the standing no-primary-key case, so the two
 * terminus kinds land on opposite outcomes and the message forks to say which remedies exist.
 */
@PipelineTier
class ValidateListRequiresOrderingPipelineTest {

    private static final String TILGANG_TYPE = """
        type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
          organisasjonskode: Int
          rollekode: String
        }
        """;

    private static List<String> messagesFor(String coordinate, String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema).stream()
            .filter(e -> coordinate.equals(e.coordinate()))
            .map(e -> e.rejection().message())
            .toList();
    }

    @Test
    void listFieldOnNoPkTable_withoutOrdering_rejected() {
        var messages = messagesFor("Query.entries", """
            type FilmListEntry @table(name: "film_list") { title: String }
            type Query { entries: [FilmListEntry!]! }
            """);

        assertThat(messages)
            .anyMatch(m -> m.equals(
                "Field 'Query.entries': list fields must have a deterministic order. "
                    + "Add a primary key to the target table, or use @defaultOrder or @orderBy."));
    }

    @Test
    void listFieldOnNoPkTable_withDefaultOrder_admitted() {
        var messages = messagesFor("Query.entries", """
            type FilmListEntry @table(name: "film_list") { title: String }
            type Query {
                entries: [FilmListEntry!]! @defaultOrder(fields: [{name: "title"}])
            }
            """);

        assertThat(messages)
            .as("@defaultOrder resolves to OrderBySpec.Fixed and clears the list-ordering check")
            .noneMatch(m -> m.contains("list fields must have a deterministic order"));
    }

    @Test
    void routineTerminusListWithNoOrderingNamesTheFunctionAndPointsAtFields() {
        // The generic message tells the author to add a primary key to the target table, which
        // on a function result is impossible. The routine arm names the function and the one
        // spelling that works.
        var messages = messagesFor("Query.tilganger", TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0))
            .contains("tilganger_for_feidebruker_med_fs_fiktivt_fnr")
            .contains("no primary key")
            .contains("@defaultOrder(fields: [...])")
            .doesNotContain("Add a primary key to the target table");
    }

    @Test
    void routineTerminusListWithDefaultOrderAdmits() {
        var messages = messagesFor("Query.tilganger", TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                @defaultOrder(fields: [{name: "organisasjonskode"}, {name: "rollekode"}])
            }
            """);
        assertThat(messages).isEmpty();
    }

    @Test
    void catalogTerminusListAdmitsOnThePrimaryKeyFallback() {
        // The hop lands on an ordinary table, so the fallback applies and the author writes
        // nothing. This is the half of the rule that costs existing schemas no edit.
        var messages = messagesFor("Query.recentFilms", """
            type Film @table(name: "film") { title: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """);
        assertThat(messages).isEmpty();
    }

    @Test
    void childRoutineTerminusListWithNoOrderingGetsTheSameMessage() {
        // The rule does not fork on position: a correlated child chain terminating on the
        // function result is the same absent key and the same remedy.
        var messages = messagesFor("Actor.films", """
            type ActorFilm @table(name: "films_for_actor") { filmId: Int @field(name: "film_id") }
            type Actor @table(name: "actor") {
              films(minLength: Int!): [ActorFilm!]
                @routine(name: "films_for_actor", argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
            }
            type Query { actors: [Actor!]! }
            """);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0))
            .contains("films_for_actor")
            .contains("no primary key");
    }
}
