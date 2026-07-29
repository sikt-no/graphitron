package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
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
 * literal plus the run's carrier fact, needing no schema, fixture, or catalog plumbing.
 * Structural properties only (method name and signature from the row's minted refs, arm presence
 * per slot); the connection arm is deliberately pinned by signature alone, because its body is
 * the largest this renderer emits and body strings there would break on every later slice
 * without asserting behaviour. Code correctness is verified by compiling the generated output
 * against real jOOQ classes in {@code graphitron-sakila-example}, and SQL behaviour by the
 * execution tier's {@code RootLauncherSqlBaselineTest} plus the connection behaviour suite.
 */
@UnitTier
class RootLauncherRendererTest {

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    private static LauncherCommand filmsRow(GlueCall where, ResultShape result) {
        return new LauncherCommand(
            UNITS.launcherMethod("Query", "films"),
            FieldCoordinates.coordinates("Query", "films"),
            filmTable(List.of(col("film_id", "FILM_ID", "java.lang.Integer"))),
            UNITS.typeClass("Film"),
            where, result);
    }

    private static ResultShape.RecordList list(Ordering ordering) {
        return new ResultShape.RecordList(ordering);
    }

    private static Ordering.Columns pkDesc() {
        return new Ordering.Columns(new OrderBySpec.Fixed(List.of(
            new OrderBySpec.ColumnOrderEntry(col("film_id", "FILM_ID", "java.lang.Integer"),
                null, OrderBySpec.SortDirection.DESC)), false));
    }

    @Test
    void launcherSignature_nameFromTheMintedRef_dslAndEnvParameters() {
        var m = render(filmsRow(null, list(null)));
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
        var body = body(filmsRow(null, list(null)));
        assertThat(body).contains(
            ".$project(env.getSelectionSet().getFieldsGroupedByResultKey(), filmTable, env)");
        assertThat(body).contains(".fetch();");
    }

    @Test
    void singleRecordShape_fetchOneAndNoOrderBy() {
        var m = render(filmsRow(null, new ResultShape.SingleRecord()));
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(m.code().toString()).contains(".fetchOne();");
        assertThat(m.code().toString()).doesNotContain(".orderBy(");
    }

    @Test
    void absentWhereSlot_composesTheNeutralCondition() {
        assertThat(body(filmsRow(null, list(null))))
            .contains("condition = org.jooq.impl.DSL.noCondition()");
    }

    @Test
    void whereSlot_rendersTheGlueCallAgainstTheArgumentsMap() {
        var where = new GlueCall(UNITS.conditionMethod("Query", "films"), false);
        assertThat(body(filmsRow(where, list(null))))
            .contains("condition = " + DEFAULT_OUTPUT_PACKAGE
                + ".conditions.QueryConditions.filmsCondition(filmTable, env.getArguments())");
    }

    @Test
    void envAppendingWhereSlot_appendsEnvAfterTheMap() {
        var where = new GlueCall(UNITS.conditionMethod("Query", "films"), true);
        assertThat(body(filmsRow(where, list(null))))
            .contains("condition = " + DEFAULT_OUTPUT_PACKAGE
                + ".conditions.QueryConditions.filmsCondition(filmTable, env.getArguments(), env)");
    }

    @Test
    void columnsOrdering_rendersTheInlineSortListThroughTheSharedFragment() {
        var body = body(filmsRow(null, list(pkDesc())));
        assertThat(body).contains(".of(filmTable.FILM_ID.desc())");
        assertThat(body).contains(".orderBy(orderBy)");
    }

    @Test
    void helperOrdering_dispatchesUnqualifiedThroughTheMintedHelperRef() {
        var ordering = new Ordering.Helper(
            UNITS.orderByHelperMethod("Query", "films"), UNITS.orderByResult());
        assertThat(body(filmsRow(null, list(ordering))))
            .contains("orderBy = filmsOrderBy(env, filmTable).sortFields()");
    }

    @Test
    void absentOrderingOnAList_rendersNoOrderByClause() {
        // Reachable only from the schema-free unit tier (a classified schema cannot carry an
        // unordered list coordinate past validation); the renderer keeps the arm renderable
        // and it renders no ORDER BY, the same SQL an empty sort list produces.
        assertThat(body(filmsRow(null, list(null))))
            .doesNotContain(".orderBy(");
    }

    @Test
    void connectionShape_returnsTheCarrierRefAndKeepsTheLauncherSignature() {
        var m = render(filmsRow(null, new ResultShape.Connection(
            pkDesc(), 100, UNITS.connectionHelper(), UNITS.connectionResult())));
        assertThat(m.name()).isEqualTo("rowsFilms");
        assertThat(m.returnType().toString())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".util.ConnectionResult");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "graphql.schema.DataFetchingEnvironment");
    }

    private static MethodSpec render(LauncherCommand row) {
        return RootLauncherRenderer.render(row, CarrierDsl.ENV_ACQUIRED);
    }

    private static String body(LauncherCommand row) {
        return render(row).code().toString();
    }
}
