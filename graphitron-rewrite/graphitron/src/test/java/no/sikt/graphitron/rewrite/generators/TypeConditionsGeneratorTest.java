package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural tests for {@link TypeConditionsGenerator#buildConditionMethod}. The pipeline-level
 * pairing of input fields to body params is exercised by {@code FetcherPipelineTest}; these tests
 * instead cover the body emitter's variant dispatch in isolation.
 */
class TypeConditionsGeneratorTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef FILM_TITLE = new ColumnRef("title", "TITLE", "java.lang.String");

    private static GeneratedConditionFilter filter(List<BodyParam> bodyParams) {
        var callParams = bodyParams.stream()
            .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), bp.javaType()))
            .toList();
        return new GeneratedConditionFilter("FilmConditions", "filmCondition", FILM_TABLE,
            callParams, bodyParams);
    }

    private static BodyParam.NodeIdIn nodeIdIn(String name, String typeId, List<ColumnRef> keyCols) {
        return new BodyParam.NodeIdIn(name, typeId, keyCols, false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", name)));
    }

    private static BodyParam.ColumnEq columnEq(String name, ColumnRef col, boolean list) {
        return new BodyParam.ColumnEq(name, col, col.columnClass(), false, list,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", name)));
    }

    @Test
    void nodeIdInFilter_singleColumn_emitsHasIdsWithOneCol() {
        var gcf = filter(List.of(nodeIdIn("ids", "Film", List.of(FILM_ID))));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method.code().toString();
        assertThat(body).contains("NodeIdEncoder.hasIds(\"Film\", ids, table.FILM_ID)");
        assertThat(body).contains("ids == null || ids.isEmpty()");
    }

    @Test
    void nodeIdInFilter_compositeColumns_emitsHasIdsWithAllColsInOrder() {
        var col1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
        var col2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");
        var gcf = filter(List.of(nodeIdIn("ids", "Bar", List.of(col1, col2))));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method.code().toString();
        assertThat(body).contains("NodeIdEncoder.hasIds(\"Bar\", ids, table.ID_1, table.ID_2)");
    }

    @Test
    void nodeIdInFilter_methodParamIsListOfString() {
        var gcf = filter(List.of(nodeIdIn("ids", "Film", List.of(FILM_ID))));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var idsParam = method.parameters().stream()
            .filter(p -> p.name().equals("ids")).findFirst().orElseThrow();
        // The parameter type is the column-type-agnostic List<String>, regardless of the
        // PK column's Java class.
        assertThat(idsParam.type().toString()).isEqualTo("java.util.List<java.lang.String>");
    }

    @Test
    void mixedFilter_columnEqAndNodeIdIn_bothEmitted() {
        var gcf = filter(List.of(
            columnEq("title", FILM_TITLE, false),
            nodeIdIn("ids", "Film", List.of(FILM_ID))));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method.code().toString();
        // ColumnEq still emits its scalar-eq form
        assertThat(body).contains("table.TITLE.eq");
        // NodeIdIn emits its hasIds form
        assertThat(body).contains("NodeIdEncoder.hasIds(\"Film\", ids, table.FILM_ID)");
        // Declaration order is preserved
        assertThat(body.indexOf("table.TITLE.eq"))
            .isLessThan(body.indexOf("NodeIdEncoder.hasIds"));
    }
}
