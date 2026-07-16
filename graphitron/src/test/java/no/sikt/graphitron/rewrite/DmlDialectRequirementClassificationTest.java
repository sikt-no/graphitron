package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Pipeline-tier coverage that the classifier populates {@link MutationField.DmlTableField}'s
 * typed {@link DialectRequirement} at construction. INSERT, DELETE, and single-row UPDATE carry
 * {@link DialectRequirement.None}; the bulk-UPDATE arm carries
 * {@link DialectRequirement.RequiresFamily}({@code POSTGRES}) because {@code UPDATE ... FROM (VALUES
 * ...)} is a Postgres extension.
 *
 * <p>UPSERT ({@link DialectRequirement.RejectsFamily}({@code ORACLE})) is not exercised here: it is
 * refused at {@code MutationInputResolver.resolveInput} under R144's cardinality-safety regime
 * (deferred to R145), so no {@link MutationField.MutationUpsertTableField} classifies through the
 * pipeline today. The shared {@code @mutation}-switch arm that stamps
 * {@code RejectsFamily(ORACLE)} onto it, and the emitter that renders the guard, are covered by
 * {@code TypeFetcherGeneratorTest} against a directly-constructed field.
 */
@PipelineTier
class DmlDialectRequirementClassificationTest {

    @Test
    void insert_carriesNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                title: String! @field(name: "title")
                languageId: Int! @field(name: "language_id")
            }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }

    @Test
    void singleRowUpdate_carriesNone() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);
        var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }

    @Test
    void bulkUpdate_requiresPostgresFamily() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
            }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """);
        var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilms");
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
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """);
        var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteFilm");
        assertThat(f.dialectRequirement()).isEqualTo(DialectRequirement.None.INSTANCE);
    }
}
