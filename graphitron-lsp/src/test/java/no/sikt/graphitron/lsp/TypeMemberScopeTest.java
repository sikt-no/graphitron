package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.TypeBackingClass;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read behind every surface that has to say what a member name written inside a type resolves
 * against. Two relations can answer and the order between them is the reader's, so what is under
 * test here is that ordering, plus the one case where the two meet in the middle: a class that
 * turns out to be a table's row type.
 *
 * <p>Each case captures its own graph over the generated catalog and the fixture classes, so both
 * arms answer from a real capture rather than from a shape a hand-built store could have.
 */
class TypeMemberScopeTest {

    private static final String SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";
    private static final String RECORD = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";

    @TempDir
    Path tmp;

    @Test
    void aTableBindingScopesTheTypeToItsTable() {
        String sdl = """
            type Query { film: Film }
            type Film @table(name: "film") { title: String }
            """;

        try (var fixture = capture(sdl)) {
            assertThat(TypeMemberScope.of(fixture.handle(), "Film"))
                .get()
                .isInstanceOfSatisfying(TypeMemberScope.Scope.Tables.class, tables ->
                    assertThat(tables.candidates()).singleElement()
                        .extracting(t -> t.tableName()).isEqualTo("film"));
        }
    }

    /**
     * The precedence this reader owns, and the case that shows it is a precedence rather than an
     * agreement: both relations answer, they answer differently, and the binding wins. The class
     * reader is asked alongside so the disagreement is visible rather than inferred.
     */
    @Test
    void aTableBindingBeatsTheClassAProducerGrounds() {
        String sdl = """
            type Query {
                film: Film @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            type Film @table(name: "film") { title: String }
            """.formatted(SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeBackingClass.of(fixture.handle(), "Film"))
                .as("the producer grounds the type on its own return, and the store says so")
                .contains(RECORD);
            assertThat(TypeMemberScope.of(fixture.handle(), "Film"))
                .get()
                .isInstanceOf(TypeMemberScope.Scope.Tables.class);
        }
    }

    @Test
    void aProducerGroundsTheTypeToItsClass() {
        String sdl = """
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            type FilmCard { title: String }
            """.formatted(SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeMemberScope.of(fixture.handle(), "FilmCard"))
                .contains(new TypeMemberScope.Scope.Members(RECORD));
        }
    }

    /**
     * Nothing binds this type and a producer grounds it on a class, so the class arm answers, and
     * the class is the row type jOOQ generated for {@code film}. Reading it for member slots would
     * find none, the classpath census excluding the generated package; the table it is the record of
     * is what an author writing a column name here means.
     */
    @Test
    void aClassThatIsATablesRecordScopesToTheTable() {
        String sdl = """
            type Query {
                row: FilmRow @service(service: {className: "%s", method: "makeFilmRow"})
            }
            type FilmRow { title: String }
            """.formatted(SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeMemberScope.of(fixture.handle(), "FilmRow"))
                .get()
                .isInstanceOfSatisfying(TypeMemberScope.Scope.Tables.class, tables ->
                    assertThat(tables.candidates()).singleElement()
                        .extracting(t -> t.tableName()).isEqualTo("film"));
        }
    }

    @Test
    void aTypeNothingBindsAndNoClassStandsForHasNoScope() {
        String sdl = """
            type Query { unbacked: Unbacked }
            type Unbacked { title: String }
            """;

        try (var fixture = capture(sdl)) {
            assertThat(TypeMemberScope.of(fixture.handle(), "Unbacked")).isEmpty();
            assertThat(TypeMemberScope.of(fixture.handle(), "NoSuchType")).isEmpty();
        }
    }

    private StoreFixture capture(String sdl) {
        return StoreFixture.ofCatalog(tmp, sdl, StoreFixture.backingClasses());
    }
}
