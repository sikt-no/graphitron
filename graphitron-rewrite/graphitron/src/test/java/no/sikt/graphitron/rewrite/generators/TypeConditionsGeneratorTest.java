package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * Structural tests for {@link TypeConditionsGenerator#buildConditionMethod}. The pipeline-level
 * pairing of input fields to body params is exercised by {@code FetcherPipelineTest}; these tests
 * instead cover the body emitter's variant dispatch in isolation.
 */
@UnitTier
class TypeConditionsGeneratorTest {

    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef FILM_TITLE = new ColumnRef("title", "TITLE", "java.lang.String");

    private static GeneratedConditionFilter filter(List<BodyParam> bodyParams) {
        var callParams = bodyParams.stream()
            .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), callTypeName(bp)))
            .toList();
        return new GeneratedConditionFilter("FilmConditions", "filmCondition", FILM_TABLE,
            callParams, bodyParams);
    }

    private static String callTypeName(BodyParam bp) {
        return switch (bp) {
            case BodyParam.Eq eq -> eq.javaType();
            case BodyParam.In in -> in.javaType();
            case BodyParam.RowEq req -> "org.jooq.Row" + req.columns().size();
            case BodyParam.RowIn rin -> "org.jooq.Row" + rin.columns().size();
        };
    }

    private static HelperRef.Decode decodeHelper(String typeName, List<ColumnRef> outputCols) {
        return new HelperRef.Decode(
            ClassName.get(DEFAULT_OUTPUT_PACKAGE + ".util", "NodeIdEncoder"),
            "decode" + typeName, outputCols);
    }

    private static BodyParam.In nodeIdInList(String name, ColumnRef col, String typeName) {
        var leaf = new CallSiteExtraction.SkipMismatchedElement(decodeHelper(typeName, List.of(col)));
        var ext = new CallSiteExtraction.NestedInputField("filter", List.of("filter", name), leaf);
        return new BodyParam.In(name, col, col.columnClass(), false, ext);
    }

    private static BodyParam.RowIn nodeIdRowIn(String name, List<ColumnRef> cols, String typeName) {
        var leaf = new CallSiteExtraction.SkipMismatchedElement(decodeHelper(typeName, cols));
        var ext = new CallSiteExtraction.NestedInputField("filter", List.of("filter", name), leaf);
        return new BodyParam.RowIn(name, cols, false, ext);
    }

    private static BodyParam columnEq(String name, ColumnRef col, boolean list) {
        var ext = new CallSiteExtraction.NestedInputField("filter", List.of("filter", name));
        return list
            ? new BodyParam.In(name, col, col.columnClass(), false, ext)
            : new BodyParam.Eq(name, col, col.columnClass(), false, ext);
    }

    @Test
    void nodeIdInFilter_singleColumn_emitsColumnInWithDecodedList() {
        var gcf = filter(List.of(nodeIdInList("ids", FILM_ID, "Film")));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var body = method.code().toString();
        assertThat(body).contains("table.FILM_ID.in(ids)");
    }

    @Test
    void nodeIdInFilter_compositeColumns_emitsTypedRowIn() {
        var col1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
        var col2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");
        var gcf = filter(List.of(nodeIdRowIn("ids", List.of(col1, col2), "Bar")));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var body = method.code().toString();
        // The typed Field<T> overload of DSL.row(...) returns Row<N><T1, ..., TN>, matching the
        // method parameter exactly — no Field<?>[] erasure trick.
        assertThat(body).contains("DSL.row(table.ID_1, table.ID_2).in(ids)");
    }

    @Test
    void nodeIdInFilter_compositeColumns_methodParamIsListOfTypedRowN() {
        var col1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
        var col2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");
        var gcf = filter(List.of(nodeIdRowIn("ids", List.of(col1, col2), "Bar")));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var idsParam = method.parameters().stream()
            .filter(p -> p.name().equals("ids")).findFirst().orElseThrow();
        // R79: the parameter is now List<Row2<Integer, Integer>>, not List<RowN>.
        assertThat(idsParam.type().toString())
            .isEqualTo("java.util.List<org.jooq.Row2<java.lang.Integer, java.lang.Integer>>");
    }

    @Test
    void nodeIdInFilter_singleColumn_methodParamIsListOfTypedColumnClass() {
        var gcf = filter(List.of(nodeIdInList("ids", FILM_ID, "Film")));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var idsParam = method.parameters().stream()
            .filter(p -> p.name().equals("ids")).findFirst().orElseThrow();
        // Post-R50 the call-site decodes wire-format String -> column-typed scalar; the
        // condition method receives the typed list directly.
        assertThat(idsParam.type().toString()).isEqualTo("java.util.List<java.lang.Integer>");
    }

    @Test
    void mixedFilter_columnEqAndNodeIdIn_bothEmitted() {
        var gcf = filter(List.of(
            columnEq("title", FILM_TITLE, false),
            nodeIdInList("ids", FILM_ID, "Film")));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var body = method.code().toString();
        // ColumnEq still emits its scalar-eq form
        assertThat(body).contains("table.TITLE.eq");
        // NodeId list filter emits column.in form over the decoded typed list
        assertThat(body).contains("table.FILM_ID.in(ids)");
        // Declaration order is preserved
        assertThat(body.indexOf("table.TITLE.eq"))
            .isLessThan(body.indexOf("table.FILM_ID.in"));
    }
}
