package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.col;
import static no.sikt.graphitron.rewrite.TestFixtures.filmTable;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-arm unit tests for {@link RootLauncherRenderer}: a total function whose input is a record
 * literal, needing no schema, fixture, or catalog plumbing. Structural properties only (method
 * name and signature from the row's minted ref, arm presence per slot); code correctness is
 * verified by compiling the generated output against real jOOQ classes in
 * {@code graphitron-sakila-example}, and SQL behaviour by the execution tier's
 * {@code RootLauncherSqlBaselineTest}.
 */
@UnitTier
class RootLauncherRendererTest {

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    private static LauncherCommand filmsRow(GlueCall where, Ordering orderBy, ResultShape result) {
        return new LauncherCommand(
            UNITS.launcherMethod("Query", "films"),
            FieldCoordinates.coordinates("Query", "films"),
            filmTable(List.of(col("film_id", "FILM_ID", "java.lang.Integer"))),
            UNITS.typeClass("Film"),
            where, orderBy, result);
    }

    @Test
    void launcherSignature_nameFromTheMintedRef_dslAndEnvParameters() {
        var m = RootLauncherRenderer.render(filmsRow(null, null, ResultShape.RECORD_LIST));
        assertThat(m.name()).isEqualTo("rowsFilms");
        assertThat(m.modifiers()).contains(
            javax.lang.model.element.Modifier.PUBLIC,
            javax.lang.model.element.Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void recordListShape_fetchesUnderTheProjectionUnitsSelectList() {
        var body = body(filmsRow(null, null, ResultShape.RECORD_LIST));
        assertThat(body).contains(
            ".$project(env.getSelectionSet().getFieldsGroupedByResultKey(), filmTable, env)");
        assertThat(body).contains(".fetch();");
    }

    @Test
    void singleRecordShape_fetchOneAndNoOrderBy() {
        var m = RootLauncherRenderer.render(filmsRow(null, null, ResultShape.SINGLE_RECORD));
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(m.code().toString()).contains(".fetchOne();");
        assertThat(m.code().toString()).doesNotContain(".orderBy(");
    }

    @Test
    void absentWhereSlot_composesTheNeutralCondition() {
        assertThat(body(filmsRow(null, null, ResultShape.RECORD_LIST)))
            .contains("condition = org.jooq.impl.DSL.noCondition()");
    }

    @Test
    void whereSlot_rendersTheGlueCallAgainstTheArgumentsMap() {
        var where = new GlueCall(UNITS.conditionMethod("Query", "films"), false);
        assertThat(body(filmsRow(where, null, ResultShape.RECORD_LIST)))
            .contains("condition = " + DEFAULT_OUTPUT_PACKAGE
                + ".conditions.QueryConditions.filmsCondition(filmTable, env.getArguments())");
    }

    @Test
    void envAppendingWhereSlot_appendsEnvAfterTheMap() {
        var where = new GlueCall(UNITS.conditionMethod("Query", "films"), true);
        assertThat(body(filmsRow(where, null, ResultShape.RECORD_LIST)))
            .contains("condition = " + DEFAULT_OUTPUT_PACKAGE
                + ".conditions.QueryConditions.filmsCondition(filmTable, env.getArguments(), env)");
    }

    @Test
    void columnsOrdering_rendersTheInlineSortListThroughTheSharedFragment() {
        var ordering = new Ordering.Columns(new OrderBySpec.Fixed(List.of(
            new OrderBySpec.ColumnOrderEntry(col("film_id", "FILM_ID", "java.lang.Integer"),
                null, OrderBySpec.SortDirection.DESC)), false));
        var body = body(filmsRow(null, ordering, ResultShape.RECORD_LIST));
        assertThat(body).contains(".of(filmTable.FILM_ID.desc())");
        assertThat(body).contains(".orderBy(orderBy)");
    }

    @Test
    void helperOrdering_dispatchesUnqualifiedThroughTheMintedHelperRef() {
        var ordering = new Ordering.Helper(UNITS.orderByHelperMethod("Query", "films"));
        assertThat(body(filmsRow(null, ordering, ResultShape.RECORD_LIST)))
            .contains("orderBy = filmsOrderBy(env, filmTable).sortFields()");
    }

    @Test
    void absentOrderingOnAList_rendersNoOrderByClause() {
        assertThat(body(filmsRow(null, null, ResultShape.RECORD_LIST)))
            .doesNotContain(".orderBy(");
    }

    private static String body(LauncherCommand row) {
        MethodSpec m = RootLauncherRenderer.render(row);
        return m.code().toString();
    }
}
