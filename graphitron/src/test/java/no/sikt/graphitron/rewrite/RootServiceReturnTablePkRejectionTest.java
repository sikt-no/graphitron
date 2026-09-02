package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * A root {@code @service} whose GraphQL return type carries {@code @table} reads the records the
 * method hands back as key carriers: it lifts each record's primary key and re-selects the
 * requested fields from the table by that key. A table with no primary key gives it nothing to
 * key on, so the coordinate is rejected by name at classify time rather than emitting a fetcher
 * that cannot work.
 *
 * <p>The same invariant the child table-bound {@code @service} arm already carries, at the root
 * coordinate. {@code film_list} is the tree's primary-key-less table.
 *
 * <p>Reject-plus-control: the controls below pin that the rejection is keyed on the returned
 * table's missing primary key and not on the coordinate, the cardinality, or the {@code @table}
 * binding itself. A return type <em>without</em> {@code @table} keeps the direct record read and
 * needs no key, which is the escape hatch the rejection's own wording offers.
 */
@PipelineTier
class RootServiceReturnTablePkRejectionTest {

    @Test
    void rootServiceReturningPkLessTable_isRejectedNamingTheTable() {
        var field = TestSchemaHelper.buildSchema("""
            type FilmList @table(name: "film_list") {
                title: String @field(name: "title")
            }
            type Query {
                filmLists: [FilmList!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmLists"})
            }
            """).field("Query", "filmLists");

        assertThat(field)
            .as("a root @service return whose table has no primary key cannot be classified")
            .isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .as("the rejection names the table, the missing key, and the escape hatch")
            .contains("film_list")
            .contains("primary key")
            .contains("drop @table");
    }

    @Test
    void rootServiceReturningPkLessTable_surfacesAsValidationError() {
        assertThat(validate("""
            type FilmList @table(name: "film_list") {
                title: String @field(name: "title")
            }
            type Query {
                filmLists: [FilmList!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmLists"})
            }
            """).stream().map(e -> e.rejection().message()).toList())
            .as("the classify rejection reaches the build boundary as a validation error")
            .anyMatch(m -> m.contains("Query.filmLists") && m.contains("film_list")
                && m.contains("primary key"));
    }

    @Test
    void mutationServiceReturningPkLessTable_isRejectedToo() {
        // The mutation twin re-selects through the same companion, so it carries the same guard.
        var field = TestSchemaHelper.buildSchema("""
            type FilmList @table(name: "film_list") {
                title: String @field(name: "title")
            }
            type Query { dummy: String }
            type Mutation {
                touchFilmList: FilmList
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmList"})
            }
            """).field("Mutation", "touchFilmList");

        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("film_list")
            .contains("primary key");
    }

    @Test
    void rootServiceReturningKeyedTable_classifies() {
        // Control: the same shape on a keyed table is the ordinary supported case.
        assertThat(TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """).field("Query", "films"))
            .as("a keyed return table classifies the same shape without complaint")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void rootServiceReturningPkLessTableWithoutTableBinding_classifies() {
        // Control, and the escape hatch the rejection names: the same records bound to a type
        // with no @table keep the direct record read, which needs no key at all. This is the
        // shape an author is steered to when the table they return has no primary key.
        assertThat(TestSchemaHelper.buildSchema("""
            type FilmListCarrier {
                title: String @field(name: "title")
            }
            type Query {
                filmListCarriers: [FilmListCarrier!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmListsAsList"})
            }
            """).field("Query", "filmListCarriers"))
            .as("dropping @table keeps the direct record read, key or no key")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    private static List<ValidationError> validate(String sdl) {
        return new GraphitronSchemaValidator().validate(TestSchemaHelper.buildSchema(sdl));
    }
}
