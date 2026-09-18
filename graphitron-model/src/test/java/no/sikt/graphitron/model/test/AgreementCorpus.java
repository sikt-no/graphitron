package no.sikt.graphitron.model.test;

/**
 * The corpus the agreement cases capture, held where both of them can read it.
 *
 * <p>One schema rather than two, because the cases that compare the store against itself and the
 * cases that compare it against the generator's model are asking about the same reading. It moved
 * out of the generator's test class when the first of those moved down a module, and a copy on each
 * side would be two corpora drifting apart under one name.
 */
public final class AgreementCorpus {

    private AgreementCorpus() {}

    public static final String SDL = """
        directive @audit(note: String) repeatable on OBJECT | FIELD_DEFINITION

        type Query {
          films(title: String): [Film!]!
          film(id: ID!): Film
        }

        type Film @table(name: "film") @audit(note: "one") @audit(note: "two") {
          id: ID! @field(name: "film_id")
          title: String
          rating: Rating
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
          name: String @field(name: "name")
        }

        enum Rating { G PG @field(name: "PG") }

        input FilmFilter { title: String = "any" }
        """;
}
