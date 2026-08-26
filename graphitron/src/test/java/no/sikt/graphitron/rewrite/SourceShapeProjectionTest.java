package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-shape mirror. {@link ChildField#sourceShape()} is a leaf-exhaustive switch the
 * model documents as "a projection of the parent's backing": a {@code @table}-backed (catalog)
 * parent puts a table row at {@code env.getSource()}; a {@code @service} / DML payload or DTO parent
 * hands back a domain record. Since
 * {@link no.sikt.graphitron.rewrite.model.OutputField#requiresReFetch()} consumes source-shape (the
 * {@code holds-records} half), a leaf wired with the wrong {@code sourceShape} arm would silently
 * flip a re-fetch verdict with no failing test.
 *
 * <p>The projection's independent source of truth is the parent GraphQL type's classified backing,
 * produced by the <em>type</em>-classification step ({@link GraphitronType}), separately from the
 * <em>field</em>-leaf classification that {@code sourceShape()} switches on. The invariant
 * ({@link #projectedFromParentBacking}) is asserted for <em>every</em> classified {@link ChildField}
 * the spec-by-example corpus demonstrates ({@link CorpusDocuments}), so the leaf-identity switch is
 * cross-checked against a genuinely independent derivation rather than against itself. The walk
 * descends the ridden lists ({@code NestingField.nestedFields()}, {@code PivotSpec.slots()}), whose
 * fields have no top-level coordinate; their independent expectation is the ridden container's
 * hand-off (a nesting leaf passes its table row through; a pivot leaf hands its slots the
 * graphitron-built jOOQ record).
 *
 * <p>{@link #everyChildFieldLeafIsCoveredOrDocumented()} keeps it honest as the leaf set grows:
 * every concrete {@link ChildField} sealed leaf is either observed by the corpus walk or carries a
 * typed {@link Exemption} on the {@code ExemptionRegistry.SOURCE_SHAPE_CORPUS} obligation, so the
 * mirror cannot silently lapse.
 */
@PipelineTier
class SourceShapeProjectionTest {

    /** The independent expectation: a child's source-shape mirrors its parent type's classified backing. */
    private static SourceShape projectedFromParentBacking(GraphitronSchema schema, ChildField c) {
        var parentType = schema.type(c.parentTypeName());
        return parentType instanceof GraphitronType.TableBackedType ? SourceShape.Table : SourceShape.Record;
    }

    @Test
    void everyCorpusChildFieldSourceShapeMirrorsParentBacking() {
        for (var example : CorpusDocuments.documents()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().forEach((coord, field) -> {
                if (field instanceof ChildField c) {
                    assertThat(c.sourceShape())
                        .as("%s (%s): sourceShape() must mirror the parent type's classified backing "
                            + "(TableBackedType -> Table, else Record)", coord, c.getClass().getSimpleName())
                        .isEqualTo(projectedFromParentBacking(schema, c));
                    assertRiddenFieldsMirrorTheirContainer(c);
                }
            });
        }
        // The walk is only a mirror if it actually exercises both projection arms.
        var observedShapes = new HashSet<SourceShape>();
        for (var example : CorpusDocuments.documents()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().values().forEach(f -> {
                if (f instanceof ChildField c) observedShapes.add(c.sourceShape());
            });
        }
        assertThat(observedShapes)
            .as("the corpus must exercise both source-shape arms for the mirror to be meaningful")
            .containsExactlyInAnyOrder(SourceShape.values());
    }

    /**
     * The descent half of the mirror. A field riding another leaf's list has no classified parent
     * backing of its own to project from, so the independent expectation is what the container
     * hands it: a {@code NestingField} is a pass-through of the parent's table-bound projection
     * (its children read the shared table row), and a pivot leaf's slots read the graphitron-built
     * jOOQ record the pivot subselect produces.
     */
    private static void assertRiddenFieldsMirrorTheirContainer(ChildField container) {
        switch (container) {
            case ChildField.NestingField n -> n.nestedFields().forEach(f -> {
                assertThat(f.sourceShape())
                    .as("%s.%s (%s): a nesting child reads the passed-through table row",
                        f.parentTypeName(), f.name(), f.getClass().getSimpleName())
                    .isEqualTo(SourceShape.Table);
                assertRiddenFieldsMirrorTheirContainer(f);
            });
            case ChildField.PivotField p -> p.spec().slots().forEach(s ->
                assertThat(s.sourceShape())
                    .as("%s.%s: a pivot slot reads the pivot subselect's built record",
                        s.parentTypeName(), s.name())
                    .isEqualTo(SourceShape.Record));
            case ChildField.BatchedPivotField p -> p.spec().slots().forEach(s ->
                assertThat(s.sourceShape())
                    .as("%s.%s: a pivot slot reads the scattered per-key record",
                        s.parentTypeName(), s.name())
                    .isEqualTo(SourceShape.Record));
            default -> { }
        }
    }

    @Test
    void everyChildFieldLeafIsCoveredOrDocumented() {
        ExemptionRegistry.assertHonoured(ExemptionRegistry.SOURCE_SHAPE_CORPUS);
    }

    @Test
    void tableBackedParent_projectsToTableSource() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        // Film is table-backed (reached via the catalog read Query.film), so its child column field
        // reads off a catalog table row: SourceShape.Table.
        var title = (ChildField) schema.field("Film", "title");
        assertThat(title.sourceShape()).isEqualTo(SourceShape.Table);
        assertThat(title.sourceShape()).isEqualTo(projectedFromParentBacking(schema, title));
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
        // FilmPayload is record-backed: the @service producer hands back a FilmRecord, so the carrier
        // data field re-projects off a produced record: SourceShape.Record.
        var film = (ChildField) schema.field("FilmPayload", "film");
        assertThat(film.sourceShape()).isEqualTo(SourceShape.Record);
        assertThat(film.sourceShape()).isEqualTo(projectedFromParentBacking(schema, film));
    }
}
