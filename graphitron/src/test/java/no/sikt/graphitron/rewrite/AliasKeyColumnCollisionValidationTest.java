package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R436 (Defect 1, residual collision): the narrow build-time validator that rejects a sibling
 * field whose parent projection is aliased to a name shadowing a <em>key/correlation</em> column
 * another child reads by base name off the parent record. The broad whole-row collision is fixed
 * by reserved {@code __src_<col>__} aliases and stays legal; only the base-named
 * {@code Wrap.Row}/{@code Wrap.Record}/{@code TableMethodField} reads remain shadowable, so those
 * are the ones this check guards.
 */
@PipelineTier
class AliasKeyColumnCollisionValidationTest {

    @Test
    void siblingAliasShadowingSplitRowKeyColumn_isRejectedWithFieldColumnAndRemedy() {
        // Film.languageSplit is a Wrap.Row @splitQuery child keyed on FILM.LANGUAGE_ID. The sibling
        // multiset object field is named "language_id", so its $fields projection is aliased to
        // "language_id" — case-insensitively shadowing the split field's base-named key read.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
                title: String
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                language_id: [Language!]! @reference(path: [{key: "film_language_id_fkey"}]) @defaultOrder(primaryKey: true)
            }
            type Language @table(name: "language") { name: String }
            """);

        var errors = new GraphitronSchemaValidator().validate(schema);

        assertThat(errors)
            .as("a sibling alias shadowing a Wrap.Row key column must be rejected at build time")
            .anyMatch(e -> e.message().contains("Film.language_id")
                && e.message().toLowerCase().contains("language_id")
                && e.message().contains("Rename"));
    }

    @Test
    void noCollision_whenSiblingNamesDoNotShadowKeyColumns() {
        // Same shape, but the multiset sibling is named "languages" (no shadow). The reserved-alias
        // full-row fix means an arbitrary-column collision would not error anyway; here there is no
        // key-column collision at all, so the validator stays silent.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
                title: String
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                languages: [Language!]! @reference(path: [{key: "film_language_id_fkey"}]) @defaultOrder(primaryKey: true)
            }
            type Language @table(name: "language") { name: String }
            """);

        var errors = new GraphitronSchemaValidator().validate(schema);

        assertThat(errors)
            .as("no sibling shadows a key/correlation column, so the collision validator is silent")
            .noneMatch(e -> e.message().contains("shadow"));
    }
}
