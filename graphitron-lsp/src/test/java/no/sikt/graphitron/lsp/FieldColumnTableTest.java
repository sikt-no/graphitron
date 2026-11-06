package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.FieldColumnTable;
import no.sikt.graphitron.lsp.facts.FieldColumnTable.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which table a column name written at a field's site resolves against, read off the field's join
 * paths, their stops, its path claim and its producer claims over one captured graph: a table where
 * one answers, a silence where the parent must not stand in, and nothing where the parent's own
 * binding is the answer.
 */
class FieldColumnTableTest {

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          actorName: String @reference(path: [{table: "film_actor"}, {table: "actor"}])
          languageName: String @reference(path: [{table: "language"}])
          nowhere: String @reference(path: [{table: "no_such_table"}])
          credits: Actor @reference(path: [{table: "film_actor"}])
          languages: [Language]
          pagedLanguages: [Language] @asConnection
          doubled: [Language]
            @service(service: {className: "no.example.Svc", method: "langs"})
            @routine(name: "films_for_actor")
        }
        type Language @table(name: "language") { name: String }
        type Actor @table(name: "actor") { name: String @field(name: "first_name") }
        input FilmFilter { languageName: String @reference(path: [{key: "film_language_id_fkey"}]) }
        type Query { films(filter: FilmFilter): [Film] }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.held().ofCatalog(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    private static Optional<Scope> scope(String type, String field) {
        return FieldColumnTable.of(store.handle(), type, field);
    }

    @Test
    void aScalarsWrittenPathResolvesWhereItsWalkArrives() {
        assertThat(scope("Film", "actorName")).get()
            .isInstanceOfSatisfying(Scope.Resolved.class, r -> assertThat(r.table().tableName()).isEqualTo("actor"));
    }

    @Test
    void aWrittenPathThatStopsIsSilent() {
        assertThat(scope("Film", "languageName")).containsInstanceOf(Scope.Silent.class);
        assertThat(scope("Film", "nowhere")).containsInstanceOf(Scope.Silent.class);
    }

    @Test
    void aWrittenPathNoJoinPathReachesIsSilent() {
        assertThat(scope("Film", "credits")).containsInstanceOf(Scope.Silent.class);
    }

    @Test
    void anInputFieldsWrittenPathResolvesWhereItsJoinPathLands() {
        assertThat(scope("FilmFilter", "languageName")).get()
            .isInstanceOfSatisfying(Scope.Resolved.class, r -> assertThat(r.table().tableName()).isEqualTo("language"));
    }

    @Test
    void anObjectFieldWithNothingWrittenResolvesOnItsTargetsTable() {
        assertThat(scope("Film", "languages")).get()
            .isInstanceOfSatisfying(Scope.Resolved.class, r -> assertThat(r.table().tableName()).isEqualTo("language"));
        assertThat(scope("Film", "pagedLanguages")).get()
            .isInstanceOfSatisfying(Scope.Resolved.class, r -> assertThat(r.table().tableName()).isEqualTo("language"));
    }

    @Test
    void aScalarWithNothingWrittenLeavesItToItsParent() {
        assertThat(scope("Film", "title")).isEmpty();
    }

    @Test
    void producerClaimsThatDisagreeAreSilent() {
        assertThat(scope("Film", "doubled")).containsInstanceOf(Scope.Silent.class);
    }
}
