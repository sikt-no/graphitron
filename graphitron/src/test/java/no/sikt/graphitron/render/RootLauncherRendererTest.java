package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.TenantStrategy;
import no.sikt.graphitron.command.LaunchSource;
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
        return filmsRow(where, new TenantStrategy.Single(), result);
    }

    private static LauncherCommand filmsRow(GlueCall where, TenantStrategy tenancy, ResultShape result) {
        return new LauncherCommand(
            UNITS.launcherMethod("Query", "films"),
            FieldCoordinates.coordinates("Query", "films"),
            new LaunchSource.AnchorTable(
                filmTable(List.of(col("film_id", "FILM_ID", "java.lang.Integer"))),
                UNITS.typeClass("Film")),
            where, new Invocation.Direct(), tenancy, result);
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
            pkDesc(), 100, UNITS.connectionHelper(), UNITS.connectionResult(), null)));
        assertThat(m.name()).isEqualTo("rowsFilms");
        assertThat(m.returnType().toString())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".util.ConnectionResult");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void fannedInvocation_envOnlySignatureHoistsAndScattersThroughTheCarrierRef() {
        var row = filmsRow(null,
            new TenantStrategy.Fanned(UNITS.tenantConnections()), list(pkDesc()));
        var m = render(row);
        assertThat(m.name()).isEqualTo("rowsFilms");
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<java.lang.Object>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .as("the fanned strategy's acquisition is plural and internal, so no dsl parameter")
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        var body = m.code().toString();
        assertThat(body).contains(".fanOutRows(env, dsl -> dsl");
        assertThat(body)
            .as("env-derived values are hoisted; the lambda closes over locals only")
            .contains("selectFields = ");
    }

    // ===== the discriminated source arm =====

    private static LauncherCommand discriminatedRow(GlueCall where, ResultShape result,
            List<LaunchSource.DiscriminatedTable.BaseSliceTerm> baseSlice,
            List<LaunchSource.DiscriminatedTable.Branch> branches) {
        return new LauncherCommand(
            UNITS.launcherMethod("Query", "allContent"),
            FieldCoordinates.coordinates("Query", "allContent"),
            new LaunchSource.DiscriminatedTable(
                filmTable(List.of(col("film_id", "FILM_ID", "java.lang.Integer"))),
                "film_type", List.of("FILM", "SHORT"), baseSlice, branches),
            where, new Invocation.Direct(), new TenantStrategy.Single(), result);
    }

    private static LaunchSource.DiscriminatedTable.Branch.SingleTable filmContentBranch() {
        return new LaunchSource.DiscriminatedTable.Branch.SingleTable(
            new no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound("FilmContent",
                filmTable(List.of()), "FILM"),
            UNITS.typeClass("FilmContent"));
    }

    @Test
    void discriminatedSource_routingAliasStepLocalAndTheSharedFetchTail() {
        var body = body(discriminatedRow(null, list(null), List.of(), List.of(filmContentBranch())));
        assertThat(body)
            .as("the routing projection qualifies off the FROM table instance under the reserved alias")
            .contains("filmTable.getQualifiedName().append(org.jooq.impl.DSL.name(\"film_type\"))")
            .contains(".as(\"__discriminator__\")");
        assertThat(body)
            .as("the single-table branch projects through its minted unit")
            .contains(DEFAULT_OUTPUT_PACKAGE + ".types.FilmContent.$project(");
        assertThat(body)
            .contains("org.jooq.SelectJoinStep<org.jooq.Record> step = dsl.select(new java.util.ArrayList<>(fields)).from(filmTable)");
        assertThat(body)
            .as("the terminal is the same conditioned fetch tail the anchor arm chains, off the step local")
            .contains("return step")
            .contains(".where(condition)")
            .contains(".fetch();");
    }

    @Test
    void discriminatedSource_inRestrictionRidesTheArm_whereSlotStaysConditionGlue() {
        var where = new GlueCall(UNITS.conditionMethod("Query", "allContent"), false);
        var body = body(discriminatedRow(where, list(null), List.of(), List.of(filmContentBranch())));
        assertThat(body)
            .contains("condition = " + DEFAULT_OUTPUT_PACKAGE
                + ".conditions.QueryConditions.allContentCondition(filmTable, env.getArguments())");
        assertThat(body)
            .as("the source-entailed discriminator restriction ANDs in after the glue, not inside it")
            .contains("condition = condition.and(")
            .contains(".in(\"FILM\", \"SHORT\")");
    }

    @Test
    void discriminatedSource_baseSliceTermsForkOnReaderAddressing() {
        var baseSlice = List.<LaunchSource.DiscriminatedTable.BaseSliceTerm>of(
            new LaunchSource.DiscriminatedTable.BaseSliceTerm.SharedKey(
                col("party_id", "PARTY_ID", "java.lang.Integer"), "party_id"),
            new LaunchSource.DiscriminatedTable.BaseSliceTerm.InheritedRef("displayName",
                col("display_name", "DISPLAY_NAME", "java.lang.String")));
        var body = body(discriminatedRow(null, list(null), baseSlice, List.of()));
        assertThat(body)
            .as("a shared key projects once, statically aliased to the detail column's SQL name")
            .contains("fields.add(filmTable.PARTY_ID.as(\"party_id\"))");
        assertThat(body)
            .as("an inherited reference projects per selected result-key bucket under the reserved prefix")
            .contains("rkEntry.getValue().get(0).getName().equals(\"displayName\")")
            .contains("fields.add(filmTable.DISPLAY_NAME.as(\"__rk_\" + rkEntry.getKey()))");
    }

    @Test
    void discriminatedSource_connectionAndFannedPairsAreUnrepresentable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                discriminatedRow(null, new ResultShape.Connection(pkDesc(), 100,
                    UNITS.connectionHelper(), UNITS.connectionResult(), null),
                    List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never paginates");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new LauncherCommand(
                    UNITS.launcherMethod("Query", "allContent"),
                    FieldCoordinates.coordinates("Query", "allContent"),
                    new LaunchSource.DiscriminatedTable(
                        filmTable(List.of()), "film_type", List.of(), List.of(), List.of()),
                    null, new Invocation.Direct(), new TenantStrategy.Fanned(UNITS.tenantConnections()), list(null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs single-tenant");
    }

    // ===== the keyed-lookup source arm =====

    private static LauncherCommand lookupRow(GlueCall where) {
        var filmIdCol = col("film_id", "FILM_ID", "java.lang.Integer");
        var mapping = new no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping(
            List.of(new no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
                "film_id", filmIdCol,
                new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(), true)),
            filmTable(List.of(filmIdCol)));
        return new LauncherCommand(
            UNITS.lookupMethod("Query", "filmById"),
            FieldCoordinates.coordinates("Query", "filmById"),
            new LaunchSource.KeyedLookup(filmTable(List.of(filmIdCol)),
                UNITS.typeClass("Film"), mapping,
                UNITS.inputRowsMethod(UNITS.fetchers("Query"), "filmById")),
            where, new Invocation.Direct(), new TenantStrategy.Single(), list(null));
    }

    @Test
    void keyedLookupSource_preSeamUnitNameAndTheLauncherSignature() {
        var m = render(lookupRow(null));
        assertThat(m.name()).isEqualTo("lookupFilmById");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void keyedLookupSource_valuesJoinInputOrderedAndTheEmptyInputShortCircuit() {
        var body = body(lookupRow(null));
        assertThat(body)
            .as("the input-rows helper is a same-class call through the minted ref")
            .contains("rows = filmByIdInputRows(env, filmTable)");
        assertThat(body).contains("if (rows.length == 0) return dsl.newResult();");
        assertThat(body)
            .as("the VALUES derived table joins the anchor over the mapping's key columns")
            .contains(".values(rows).as(\"filmByIdInput\", \"idx\", \"film_id\")")
            .contains(".join(input).using(filmTable.FILM_ID)");
        assertThat(body)
            .as("the WHERE stays the condition local; the ordering is the arm's entailed input order")
            .contains(".where(condition)")
            .contains(".orderBy(input.field(\"idx\"))")
            .contains(".fetch();");
    }

    @Test
    void keyedLookupSource_connectionPairIsUnrepresentable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new LauncherCommand(
                    UNITS.lookupMethod("Query", "filmById"),
                    FieldCoordinates.coordinates("Query", "filmById"),
                    lookupRow(null).source(),
                    null, new Invocation.Direct(), new TenantStrategy.Single(),
                    new ResultShape.Connection(pkDesc(), 100,
                        UNITS.connectionHelper(), UNITS.connectionResult(), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never paginates");
    }

    private static MethodSpec render(LauncherCommand row) {
        return RootLauncherRenderer.render(row, CarrierDsl.ENV_ACQUIRED);
    }

    private static String body(LauncherCommand row) {
        return render(row).code().toString();
    }
}
