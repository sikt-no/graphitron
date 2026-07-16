package no.sikt.graphitron.rewrite.generators;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.TestFixtures.languageIdCol;
import static no.sikt.graphitron.rewrite.TestFixtures.languageTableWithPk;
import static no.sikt.graphitron.rewrite.TestFixtures.nonNullList;
import static no.sikt.graphitron.rewrite.TestFixtures.tableBoundFilm;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ParentProjectionContainmentCheck} — the parent-projection
 * containment invariant, called directly with a hand-built guarantee so the fire cases simulate
 * a projection walk that omitted a demand.
 *
 * <p>The load-bearing fire case is the <em>nesting-omission</em> shape: the
 * demanding {@code BatchKeyField} sits inside a {@link ChildField.NestingField} sub-tree, so a
 * requirement enumeration that fails to descend nesting sub-trees (the audited walk's own
 * recursion is exactly where the omission lived) does not find the demand and this test goes
 * red. A bare top-level set mismatch would not pin that.
 */
@UnitTier
class ParentProjectionContainmentCheckTest {

    private static final TableRef LANGUAGE_TABLE = languageTableWithPk();

    private static ChildField.BatchedTableField splitField(String parentType, String name, SourceKey sourceKey) {
        var rt = tableBoundFilm(nonNullList());
        var path = List.<JoinStep>of(TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE, List.of(),
            TestFixtures.filmTable(), List.of(), null, name + "_0"));
        return new ChildField.BatchedTableField(parentType, name, null, rt, path,
            List.of(), new OrderBySpec.None(), null,
            SourceShape.Table,
            sourceKey,
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(rt, false, false),
            TestFixtures.pcFor(path, LANGUAGE_TABLE));
    }

    private static ChildField.NestingField nesting(String parentType, String name, ChildField nested) {
        return new ChildField.NestingField(parentType, name, null,
            new ReturnTypeRef.TableBoundReturnType("LanguageDetails", LANGUAGE_TABLE, new FieldWrapper.Single(true)),
            List.of(nested));
    }

    /**
     * A Table-sourced {@code BatchKeyField} whose key wrap is caller-chosen — the
     * {@code Wrap.TableRecord} shape is authored on the {@code @service Sources} signature
     * ({@link ChildField.ServiceTableField}); the batched leaf's Table arm is pinned to the
     * {@code FkColumns}/{@code Row} pairing by its constructor and can never carry it.
     */
    private static ChildField.ServiceTableField serviceField(String parentType, String name, SourceKey.Wrap wrap) {
        var rt = tableBoundFilm(new FieldWrapper.Single(true));
        var keyCols = List.of(languageIdCol());
        var method = TestFixtures.staticServiceMethodRef(
            "no.example.FilmService", "getFilm", ClassName.get("org.jooq", "Record"),
            List.of(TestFixtures.sourced("keys", wrap, keyCols,
                no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.POSITIONAL_LIST)));
        return new ChildField.ServiceTableField(parentType, name, null, rt,
            List.of(), List.of(), new OrderBySpec.None(), null, method,
            TestFixtures.serviceSourceKey(wrap, keyCols),
            TestFixtures.loaderRegistration(rt, false, false),
            java.util.Optional.empty());
    }

    private static GraphitronSchema schemaWith(GraphitronField... fields) {
        var map = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        for (var f : fields) {
            map.put(FieldCoordinates.coordinates(f.parentTypeName(), f.name()), f);
        }
        return new GraphitronSchema(Map.of(), map);
    }

    // ===== The nesting-omission fire case: the demand sits inside a NestingField sub-tree =====

    @Test
    void nestedSplitKeyColumnMissingFromProjection_throwsGeneratorInvariant() {
        var schema = schemaWith(nesting("Language", "details",
            splitField("LanguageDetails", "films", TestFixtures.splitSourceKey(List.of(languageIdCol())))));
        var walkOmittedNestingRecursion = new TypeClassGenerator.RequiredProjection(false, List.of());
        assertThatThrownBy(() ->
            ParentProjectionContainmentCheck.check(schema, "Language", walkOmittedNestingRecursion))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LanguageDetails.films")
            .hasMessageContaining("language_id")
            .hasMessageContaining("generator bug");
    }

    @Test
    void nestedSplitKeyColumnPresentInProjection_passes() {
        var schema = schemaWith(nesting("Language", "details",
            splitField("LanguageDetails", "films", TestFixtures.splitSourceKey(List.of(languageIdCol())))));
        var projected = new TypeClassGenerator.RequiredProjection(false, List.of(languageIdCol()));
        assertThatCode(() ->
            ParentProjectionContainmentCheck.check(schema, "Language", projected))
            .doesNotThrowAnyException();
    }

    // ===== The table-record axis: a TableRecord key wrap demands the reserved full parent row =====

    @Test
    void nestedTableRecordWrapWithoutReservedFullRow_throwsGeneratorInvariant() {
        var tableRecordWrap = new SourceKey.Wrap.TableRecord(ClassName.get("com.example.records", "LanguageRecord"));
        var schema = schemaWith(nesting("Language", "details",
            serviceField("LanguageDetails", "films", tableRecordWrap)));
        var walkOmittedNestingRecursion = new TypeClassGenerator.RequiredProjection(false, List.of());
        assertThatThrownBy(() ->
            ParentProjectionContainmentCheck.check(schema, "Language", walkOmittedNestingRecursion))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LanguageDetails.films")
            .hasMessageContaining("reservedFullRow");
    }

    @Test
    void nestedTableRecordWrapWithReservedFullRow_passes() {
        var tableRecordWrap = new SourceKey.Wrap.TableRecord(ClassName.get("com.example.records", "LanguageRecord"));
        var schema = schemaWith(nesting("Language", "details",
            serviceField("LanguageDetails", "films", tableRecordWrap)));
        var projected = new TypeClassGenerator.RequiredProjection(true, List.of());
        assertThatCode(() ->
            ParentProjectionContainmentCheck.check(schema, "Language", projected))
            .doesNotThrowAnyException();
    }

    // ===== Exclusions: record-sourced keys ride the held object, not the parent SELECT =====

    @Test
    void recordSourcedBatchKeyField_isNotDemandedFromTheParentProjection() {
        var rt = tableBoundFilm(new FieldWrapper.Single(true));
        var recordLeaf = new ChildField.BatchedTableField("Language", "film", null, rt,
            List.of(), List.of(), new OrderBySpec.None(), null,
            SourceShape.Record,
            TestFixtures.recordParentRowSourceKey(List.of(languageIdCol())),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(rt, false, false),
            null);
        var schema = schemaWith(recordLeaf);
        assertThatCode(() ->
            ParentProjectionContainmentCheck.check(schema, "Language",
                new TypeClassGenerator.RequiredProjection(false, List.of())))
            .doesNotThrowAnyException();
    }

    @Test
    void anchorScoping_demandsOnOtherTypesAreIgnored() {
        var schema = schemaWith(
            splitField("Film", "sequels", TestFixtures.splitSourceKey(List.of(languageIdCol()))));
        assertThatCode(() ->
            ParentProjectionContainmentCheck.check(schema, "Language",
                new TypeClassGenerator.RequiredProjection(false, List.of())))
            .doesNotThrowAnyException();
    }
}
