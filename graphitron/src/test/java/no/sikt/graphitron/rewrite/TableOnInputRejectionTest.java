package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retired-location rejection for {@code @table} on an {@code input} type. The SDL
 * declaration keeps the {@code INPUT_OBJECT} location so the parser does not fail with a
 * generic "unknown directive location"; the classifier rejects any input application with a
 * migration message instead ({@code TypeBuilder.buildInputType}), following the
 * {@code @notGenerated} / retired-{@code @lookupKey} convention. These tests pin that the
 * rejection fires on the type (one verdict, however many consumers), and that the message
 * carries the per-verb migration guidance the retired deprecation warning used to give.
 */
@PipelineTier
class TableOnInputRejectionTest {

    private static final String RETIRED_FRAGMENT = "no longer supported";

    @Test
    void tableOnInput_rejectsTheTypeWithMigrationGuidance() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var uc = (UnclassifiedType) schema.type("FilmInput");
        assertThat(uc.rejection().message())
            .as("the rejection names the directive, the type, and every migration path")
            .contains("`@table` on input type 'FilmInput'")
            .contains(RETIRED_FRAGMENT)
            .contains("remove the")
            .contains("@mutation(typeName: DELETE)")
            .contains("@mutation(table:")
            .contains("derived from the field's return type");
    }

    @Test
    void tableOnFilterInput_rejectsWithTheSameTypeLevelMessage() {
        // The filter case: no mutation consumer at all. The rejection is the type's verdict,
        // not a per-consumer one, so the same message fires with only a query-side consumer.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") { title: String }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        var uc = (UnclassifiedType) schema.type("FilmFilter");
        assertThat(uc.rejection().message())
            .contains("`@table` on input type 'FilmFilter'")
            .contains(RETIRED_FRAGMENT)
            .contains("resolve against each consuming field's table");
    }

    @Test
    void tableOnInput_reusedAcrossConsumers_rejectsOnceOnTheType() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input Shared @table(name: "film") { title: String }
            type Query {
                filmsA(filter: Shared): [Film!]!
                filmsB(filter: Shared): [Film!]!
            }
            """);

        assertThat(schema.type("Shared"))
            .as("the verdict lives on the type; consumer count does not multiply or suppress it")
            .isInstanceOf(UnclassifiedType.class);
    }

    @Test
    void tableOnInput_unknownTableName_stillRejectsAsRetiredLocation() {
        // The retired-location rejection precedes any table resolution: a bogus name changes
        // nothing, because the directive is rejected before its argument is read.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "no_such_table") { title: String }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        var uc = (UnclassifiedType) schema.type("FilmFilter");
        assertThat(uc.rejection().message()).contains(RETIRED_FRAGMENT);
    }
}
