package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R305 — source-shape mirror. {@link ChildField#sourceShape()} is a leaf-exhaustive switch whose
 * javadoc claims it is "a projection of the parent producer's {@code domainReturnType}". Since R305
 * makes {@link no.sikt.graphitron.rewrite.model.OutputField#requiresReFetch()} consume source-shape
 * (the {@code holds-records} half), a leaf wired with the wrong {@code sourceShape} arm would
 * silently flip a re-fetch verdict. This test pins the projection on both arms:
 *
 * <ul>
 *   <li>a child field on a <strong>table-backed</strong> parent (the parent producer puts a catalog
 *       table row at {@code env.getSource()}) projects to {@link SourceShape#Table};</li>
 *   <li>a child field on a <strong>record-backed</strong> parent (an {@code @service} / DML payload
 *       carrier whose producer hands back a domain record) projects to {@link SourceShape#Record}.</li>
 * </ul>
 *
 * <p>The {@code @classified} corpus ({@code ClassifiedDslTest}) asserts the source-shape value set
 * SDL-side; this test pins the catalog-vs-domain projection structurally so the two arms cannot
 * silently diverge from the parent backing they claim to mirror.
 */
@PipelineTier
class SourceShapeProjectionTest {

    @Test
    void tableBackedParent_projectsToTableSource() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);

        // Film is table-backed (reached via the catalog read Query.film), so its child column field
        // reads off a catalog table row: SourceShape.Table.
        var title = (ChildField) schema.field("Film", "title");
        assertThat(title.sourceShape())
            .as("a column on a table-backed parent reads off a catalog table row")
            .isEqualTo(SourceShape.Table);
    }

    @Test
    void recordBackedParent_projectsToRecordSource() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            type Mutation {
                runFilm: FilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """);

        // FilmPayload is record-backed: the @service producer hands back a FilmRecord, so the
        // carrier data field re-projects off a produced record: SourceShape.Record.
        var film = (ChildField) schema.field("FilmPayload", "film");
        assertThat(film.sourceShape())
            .as("an @service payload carrier data field reads off a produced domain record")
            .isEqualTo(SourceShape.Record);
    }
}
