package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two build-time guards that make the discriminator's typed comparison bind total, plus the
 * resolved {@link no.sikt.graphitron.model.jooq.ColumnRef} the verdict carries.
 *
 * <p>The comparison sites emit {@code DSL.val("<value>", <table>.<COL>.getDataType())}, which needs
 * two things classification must supply. It needs the column to exist as a generated field, so an
 * unresolvable {@code @discriminate(on:)} can no longer fall back to the raw directive string.
 * And on a column whose value domain is closed (a jOOQ-generated enum) it needs every
 * {@code @discriminator(value:)} to be in that domain: {@code DSL.val} converts an unknown literal
 * to {@code null} silently, so an unguarded typo would trade a loud database error for a query that
 * returns nothing.
 */
@PipelineTier
class DiscriminatorColumnGuardPipelineTest {

    // ===== the resolved column the verdict carries =====

    @Test
    void enumDiscriminatedInterface_carriesTheResolvedColumnWithItsEnumClass() {
        // film.rating is Postgres mpaa_rating, the reproduction shape this coverage exists for.
        var schema = build("""
            interface FilmKind @table(name: "film") @discriminate(on: "rating") {
                filmId: Int!    @field(name: "film_id")
                title:  String! @field(name: "title")
            }
            type GFilm implements FilmKind @table(name: "film") @discriminator(value: "G") {
                filmId: Int!    @field(name: "film_id")
                title:  String! @field(name: "title")
            }
            type Query { filmKinds: [FilmKind!]! }
            """);

        var iface = (GraphitronType.TableInterfaceType) schema.type("FilmKind");
        var column = iface.discriminatorColumn();
        assertThat(column.sqlName()).isEqualTo("rating");
        assertThat(column.javaName())
            .as("the renderer spells <tableLocal>.<javaName>.getDataType() off this")
            .isEqualTo("RATING");
        assertThat(column.columnClass())
            .as("the enum class is carried end to end rather than collapsed to a bare SQL name; "
                + "it is what closes the value domain")
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating");
    }

    @Test
    void enumDiscriminatedInterface_acceptsADashedDatabaseLiteral() {
        // 'PG-13' is a database literal that is not a Java identifier (the constant is PG_13).
        // @discriminator(value:) names the literal, so this classifies.
        var schema = build("""
            interface FilmKind @table(name: "film") @discriminate(on: "rating") {
                filmId: Int! @field(name: "film_id")
            }
            type Pg13Film implements FilmKind @table(name: "film") @discriminator(value: "PG-13") {
                filmId: Int! @field(name: "film_id")
            }
            type Query { filmKinds: [FilmKind!]! }
            """);

        assertThat(schema.type("FilmKind")).isInstanceOf(GraphitronType.TableInterfaceType.class);
    }

    // ===== Guard 1: the on: column must resolve =====

    @Test
    void unresolvableDiscriminatorColumn_rejectsWithCandidates() {
        var schema = build("""
            interface MediaItem @table(name: "film") @discriminate(on: "kind") {
                title: String
            }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") {
                title: String
            }
            type Query { media: [MediaItem!]! }
            """);

        var verdict = schema.type("MediaItem");
        assertThat(verdict)
            .as("no raw-string fallback: the comparison cannot be emitted without a generated field, "
                + "and the fallback only ever produced code that failed at query time")
            .isInstanceOf(GraphitronType.UnclassifiedType.class);
        var rejection = ((GraphitronType.UnclassifiedType) verdict).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("kind");
        assertThat(unknown.candidates())
            .as("the candidate space is the interface's own table columns")
            .contains("RATING", "TEXT_RATING", "TITLE");
        assertThat(rejection.message()).contains("@discriminate(on: \"kind\")").contains("film");
    }

    // ===== Guard 2: closed value domain =====

    @Test
    void unknownLiteralOnAnEnumColumn_rejectsNamingColumnTypeValueAndLiteralSet() {
        var schema = build("""
            interface FilmKind @table(name: "film") @discriminate(on: "rating") {
                filmId: Int! @field(name: "film_id")
            }
            type GFilm implements FilmKind @table(name: "film") @discriminator(value: "G") {
                filmId: Int! @field(name: "film_id")
            }
            type BogusFilm implements FilmKind @table(name: "film") @discriminator(value: "PG13") {
                filmId: Int! @field(name: "film_id")
            }
            type Query { filmKinds: [FilmKind!]! }
            """);

        var verdict = schema.type("FilmKind");
        assertThat(verdict).isInstanceOf(GraphitronType.UnclassifiedType.class);
        assertThat(((GraphitronType.UnclassifiedType) verdict).rejection().message())
            .as("the message has to name the column, the Postgres enum type, the offending value "
                + "and the valid literal set: a silent NULL bind is what it exists to prevent")
            .contains("rating")
            .contains("MpaaRating")
            .contains("'PG13'")
            .contains("PG-13")
            .contains("NC-17");
    }

    @Test
    void openValueDomain_keepsNoLiteralCheck() {
        // film.text_rating is varchar: nothing closes its domain, so any literal classifies. The
        // check is enum-ness, not a general value whitelist.
        var schema = build("""
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") {
                title: String
            }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "anything at all") {
                title: String
            }
            type Query { media: [MediaItem!]! }
            """);

        assertThat(schema.type("MediaItem")).isInstanceOf(GraphitronType.TableInterfaceType.class);
    }

    private static GraphitronSchema build(String sdl) {
        return TestSchemaHelper.buildSchema(sdl);
    }
}
