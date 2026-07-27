package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @service} SOURCES batch parameter needs a batch key, and the batch key is the parent
 * table's primary key. On a parent table that declares none there is nothing to key on, so the
 * coordinate is rejected by name rather than left to a diagnostic about something else.
 *
 * <p>The case is worth its own fixture because two very different coordinates arrive at the
 * classifier with an empty parent-PK list: a root operation type, which has no parent table at
 * all, and a child on a table that simply has no primary key. Before the PK-only key contract
 * both fell through to the argument-name-mismatch arm, which tells the author to rename a Java
 * parameter, advice that cannot fix either one. The root arm has its own diagnostic; this pins
 * the other, and the control below pins that adding it did not swallow the root arm.
 *
 * <p>{@code film_list} is the tree's PK-less table.
 */
@PipelineTier
class PkLessParentServiceSourcesRejectionTest {

    @Test
    void sourcesBatchParam_onPkLessParentTable_isRejectedNamingTheTable() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmList @table(name: "film_list") {
                title: String @field(name: "title")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmListRankByRecord"}
                )
            }
            type Query { filmList: FilmList }
            """);

        var field = schema.field("FilmList", "rank");
        assertThat(field)
            .as("a SOURCES batch parameter on a PK-less parent cannot be classified")
            .isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .as("the rejection names the parent type, the table, and the missing primary key")
            .contains("FilmList")
            .contains("film_list")
            .contains("no primary key");
    }

    @Test
    void sourcesBatchParam_atRoot_keepsItsOwnDiagnostic() {
        // Control: the root coordinate also arrives with an empty parent-PK list, and must still
        // reach the root-specific diagnostic rather than the PK-less-table one.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"}
                )
            }
            """);

        var field = schema.field("Query", "films");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .as("root keeps the batch-at-root wording, not the PK-less-table wording")
            .contains("@service at the root does not support")
            .doesNotContain("no primary key");
    }

    @Test
    void sourcesBatchParam_onParentWithPrimaryKey_classifies() {
        // Control: the same shape on a keyed parent is the ordinary supported case.
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(schema.field("Language", "rank"))
            .as("a keyed parent classifies the same shape without complaint")
            .isNotInstanceOf(UnclassifiedField.class);
    }
}
