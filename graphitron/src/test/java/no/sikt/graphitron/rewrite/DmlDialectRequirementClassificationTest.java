package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Pipeline-tier coverage that {@link MutationField.DmlTableField}'s typed
 * {@link DialectRequirement} derives correctly from the write arm and its input cardinality.
 * INSERT, DELETE, and single-row UPDATE derive {@link DialectRequirement.None}; the bulk-UPDATE
 * combination derives {@link DialectRequirement.RequiresFamily}({@code POSTGRES}) because
 * {@code UPDATE ... FROM (VALUES ...)} is a Postgres extension.
 *
 * <p>UPSERT ({@link DialectRequirement.RejectsFamily}({@code ORACLE})) is not exercised here: it
 * is refused at the classifier's verb dispatch under the cardinality-safety regime (deferred),
 * so no Upsert arm classifies through the pipeline today. The derivation's Upsert branch, and
 * the emitter that renders the guard, are covered by {@code TypeFetcherGeneratorTest} against a
 * directly-constructed field.
 */
@PipelineTier
class DmlDialectRequirementClassificationTest {

    @Test
    void insert_carriesNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput {
                title: String! @field(name: "title")
                languageId: Int! @field(name: "language_id")
            }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }

    @Test
    void singleRowUpdate_carriesNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);
        var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }

    @Test
    void bulkUpdate_requiresPostgresFamily() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
            }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """);
        var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilms");
        assertThat(f.dialectRequirement())
            .isInstanceOfSatisfying(DialectRequirement.RequiresFamily.class, r -> {
                assertThat(r.family()).isEqualTo(SqlDialectFamily.POSTGRES);
                assertThat(r.reason())
                    .contains("requires PostgreSQL")
                    .contains("UPDATE ... FROM (VALUES ...)");
            });
    }

    @Test
    void delete_carriesNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """);
        var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }
}
