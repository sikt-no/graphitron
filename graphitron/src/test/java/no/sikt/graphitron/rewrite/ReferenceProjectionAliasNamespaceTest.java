package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference-projection aliases live in the reserved {@code __rk_} result-key namespace
 * ({@code GeneratorUtils.RESERVED_RK_ALIAS_PREFIX}), so a sibling reference field can never shadow a
 * key/correlation column another child reads by <em>base</em> name off the parent record. This
 * retires the earlier residual-collision validator rejection: aliasing the inline reference /
 * computed projections by the runtime result key under a reserved prefix (rather than by the
 * GraphQL field name) moves them out of the client-reachable base-column namespace entirely, so the
 * shadow the old check guarded is structurally impossible and a schema that would previously have
 * been rejected now validates clean.
 */
@PipelineTier
class ReferenceProjectionAliasNamespaceTest {

    @Test
    void siblingReferenceNamedLikeSplitRowKeyColumn_noLongerRejected() {
        // Film.languageSplit is a Wrap.Row @splitQuery child keyed on FILM.LANGUAGE_ID, read by the
        // base name "language_id" off the parent record. The sibling multiset reference field is
        // named "language_id" too, so pre-fix its $fields projection aliased to "language_id" would
        // shadow that base-named key read. Now the projection is aliased "__rk_" + resultKey, out of
        // the base namespace, so there is no shadow and no rejection.
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
            .as("the reserved __rk_ alias namespace makes the base-column shadow impossible, so no rejection")
            .noneMatch(e -> e.message().toLowerCase().contains("shadow"));
    }

    @Test
    void siblingReferenceWithDistinctName_validatesClean() {
        // Same shape, sibling named "languages" (no name match at all): also clean, as before.
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
            .as("no name match, so no shadow rejection")
            .noneMatch(e -> e.message().toLowerCase().contains("shadow"));
    }
}
