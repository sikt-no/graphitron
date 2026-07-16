package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>This test converts that javadoc invariant into a pinned one. The projection's independent
 * source of truth is the parent GraphQL type's classified backing, produced by the
 * <em>type</em>-classification step ({@link GraphitronType}), separately from the
 * <em>field</em>-leaf classification that {@code sourceShape()} switches on. The invariant
 * ({@link #projectedFromParentBacking}):
 *
 * <pre>{@code c.sourceShape() == Table  iff  schema.type(c.parentTypeName()) is a TableBackedType
 *                          == Record otherwise}</pre>
 *
 * is asserted for <em>every</em> classified {@link ChildField} the spec-by-example corpus
 * demonstrates ({@link ClassifiedCorpus}), so the leaf-identity switch is cross-checked against the
 * independently-classified parent backing rather than against itself. This is the source-shape
 * analogue of the validator's retired {@code dispatchPerformsReFetch} mirror (replaced by the
 * reentry implementedness guard once the reentry emit was reshaped to route on the model facts
 * directly); unlike that mirror, the parent-backing walk here is a genuinely independent
 * derivation, so this cross-check keeps earning its keep.
 *
 * <p>{@link #everyChildFieldLeafIsCoveredOrDocumented()} keeps it honest as the leaf set grows: every
 * concrete {@link ChildField} sealed leaf is either exercised by the corpus walk (and thus verified
 * above) or carries a one-line entry in {@link #NOT_CORPUS_COVERED}. A new leaf added with no corpus
 * fixture fails that guard until it is covered or documented, so the mirror cannot silently lapse.
 */
@PipelineTier
class SourceShapeProjectionTest {

    /**
     * Concrete {@link ChildField} leaves the corpus does not reach, each with the reason. Kept
     * deliberately small: the goal is for the corpus to demonstrate every schema-reachable leaf.
     */
    private static final Map<Class<?>, String> NOT_CORPUS_COVERED = Map.ofEntries(
        Map.entry(ChildField.CompositeColumnField.class,
            "Composite (RowN) column projection; no corpus fixture declares a composite scalar yet. "
            + "Catalog column carrier — SourceShape.Table by the same parent-backing projection."),
        Map.entry(ChildField.CompositeColumnReferenceField.class,
            "Composite (RowN) reference projection; not in the corpus. Catalog reference carrier — "
            + "SourceShape.Table by the parent-backing projection."),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class,
            "R156 payload-returning DELETE: the only admissible data field is an encoded-PK ID off "
            + "RETURNING, which needs the synthesised __NODE_TYPE_ID metadata absent from the corpus "
            + "catalog (see VariantCoverageTest.NO_CASE_REQUIRED). Record-backed payload parent — "
            + "SourceShape.Record."));

    /** The independent expectation: a child's source-shape mirrors its parent type's classified backing. */
    private static SourceShape projectedFromParentBacking(GraphitronSchema schema, ChildField c) {
        var parentType = schema.type(c.parentTypeName());
        return parentType instanceof GraphitronType.TableBackedType ? SourceShape.Table : SourceShape.Record;
    }

    @Test
    void everyCorpusChildFieldSourceShapeMirrorsParentBacking() {
        var observedLeaves = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().forEach((coord, field) -> {
                if (field instanceof ChildField c) {
                    observedLeaves.add(c.getClass());
                    assertThat(c.sourceShape())
                        .as("%s (%s): sourceShape() must mirror the parent type's classified backing "
                            + "(TableBackedType -> Table, else Record)", coord, c.getClass().getSimpleName())
                        .isEqualTo(projectedFromParentBacking(schema, c));
                }
            });
        }
        // The walk is only a mirror if it actually exercises both projection arms.
        var observedShapes = new HashSet<SourceShape>();
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().values().forEach(f -> {
                if (f instanceof ChildField c) observedShapes.add(c.sourceShape());
            });
        }
        assertThat(observedShapes)
            .as("the corpus must exercise both source-shape arms for the mirror to be meaningful")
            .containsExactlyInAnyOrder(SourceShape.values());
    }

    @Test
    void everyChildFieldLeafIsCoveredOrDocumented() {
        Set<Class<?>> allLeaves = GeneratorCoverageTest.sealedLeaves(ChildField.class);

        var covered = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().values().forEach(f -> {
                if (f instanceof ChildField c) covered.add(c.getClass());
            });
        }
        covered.addAll(NOT_CORPUS_COVERED.keySet());

        var uncovered = new TreeSet<String>();
        allLeaves.stream().filter(leaf -> !covered.contains(leaf))
            .forEach(leaf -> uncovered.add(leaf.getSimpleName()));
        assertThat(uncovered)
            .as("every concrete ChildField leaf must be exercised by the corpus source-shape mirror "
                + "or carry a documented NOT_CORPUS_COVERED entry; a new leaf landed in neither")
            .isEmpty();

        // The escape hatch must not rot: every documented exemption must still be a real, uncovered leaf.
        assertThat(NOT_CORPUS_COVERED.keySet())
            .as("NOT_CORPUS_COVERED must only list real ChildField leaves")
            .allMatch(allLeaves::contains);
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
