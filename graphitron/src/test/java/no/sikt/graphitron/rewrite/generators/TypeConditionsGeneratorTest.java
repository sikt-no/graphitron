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
            case BodyParam.RemoteColumnPredicate r -> callTypeName(r.inner());
        };
    }

    private static HelperRef.Decode decodeHelper(String typeName, List<ColumnRef> outputCols) {
        return new HelperRef.Decode(
            ClassName.get(DEFAULT_OUTPUT_PACKAGE + ".util", "NodeIdEncoder"),
            "decode" + typeName, outputCols, typeName);
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
        // R375: empty list narrows by nothing (DSL.noCondition() identity) instead of
        // emitting IN () = false. The nullable arm folds the empty guard into its null check.
        assertThat(body).contains("ids != null && !ids.isEmpty()");
    }

    @Test
    void inFilter_nonNullList_emitsEmptyGuardWithoutNullCheck() {
        // R375: a non-null list still gets the empty guard, but no null check (the type
        // guarantees non-null). Pins the non-null In arm, which the nodeId helpers don't reach.
        var ext = new CallSiteExtraction.NestedInputField("filter", List.of("filter", "ids"));
        var nonNullIn = new BodyParam.In("ids", FILM_ID, FILM_ID.columnClass(), true, ext);
        var gcf = filter(List.of(nonNullIn));
        var method = TypeConditionsGenerator.buildConditionMethod(
            gcf, DEFAULT_OUTPUT_PACKAGE);
        var body = method.code().toString();
        assertThat(body).contains("if (!ids.isEmpty()) condition = condition.and(table.FILM_ID.in(ids))");
        assertThat(body).doesNotContain("ids != null");
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
        // R375: composite-key empty list (a [] composite @nodeId filter decodes to an empty
        // row list) narrows by nothing rather than rendering IN () = false.
        assertThat(body).contains("ids != null && !ids.isEmpty()");
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

    // ===== R380: RemoteColumnPredicate (correlated EXISTS over a @reference join path) =====
    //
    // These exercise the body emitter's RemoteColumnPredicate arm directly. The classifier only
    // produces single-column (Eq/In) remote predicates in v1 (a plain @reference resolves one
    // terminal column); the composite RowIn case below proves the emitter already supports a
    // composite terminal tuple, so folding in a composite reference filter later is additive.

    private static final ColumnRef LANGUAGE_ID = new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef LANGUAGE_NAME = new ColumnRef("name", "NAME", "java.lang.String");
    private static final TableRef LANGUAGE_TABLE = TestFixtures.tableRef("language", "LANGUAGE", "Language", List.of());
    private static final TableRef CITY_TABLE = TestFixtures.tableRef("city", "CITY", "City", List.of());
    private static final TableRef COUNTRY_TABLE = TestFixtures.tableRef("country", "COUNTRY", "Country", List.of());
    private static final ColumnRef CITY_ID = new ColumnRef("city_id", "CITY_ID", "java.lang.Integer");
    private static final ColumnRef COUNTRY_ID = new ColumnRef("country_id", "COUNTRY_ID", "java.lang.Integer");
    private static final ColumnRef COUNTRY_NAME = new ColumnRef("country", "COUNTRY", "java.lang.String");

    @Test
    void remoteEq_singleHop_emitsCorrelatedExistsAgainstTerminalAlias() {
        // Filter Film by language.name reached through film.language_id -> language. The predicate
        // must NOT bind table.NAME (film has no `name`); it binds the terminal alias's column inside
        // a correlated EXISTS, with the correlation tying the terminal alias back to `table`.
        var fk = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_language_id_fkey"), FILM_TABLE,
            List.of(LANGUAGE_ID), LANGUAGE_TABLE, List.of(LANGUAGE_ID), null, "l0");
        var inner = new BodyParam.Eq("languageName", LANGUAGE_NAME, "java.lang.String", false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", "languageName")));
        var remote = new BodyParam.RemoteColumnPredicate(List.of(fk), inner);
        var body = TypeConditionsGenerator.buildConditionMethod(filter(List.of(remote)), DEFAULT_OUTPUT_PACKAGE)
            .code().toString();

        assertThat(body).contains("org.jooq.impl.DSL.exists(");
        assertThat(body).contains(".from(languageName_ref0)");
        // Correlation back to the method's own `table` alias.
        assertThat(body).contains("languageName_ref0.LANGUAGE_ID.eq(table.LANGUAGE_ID)");
        // Inner predicate binds the TERMINAL alias, never `table`.
        assertThat(body).contains("languageName_ref0.NAME.eq(org.jooq.impl.DSL.val(languageName, languageName_ref0.NAME))");
        assertThat(body).doesNotContain("table.NAME");
        // Nullable scalar: guarded by a null check around the whole EXISTS term.
        assertThat(body).contains("if (languageName != null) condition = condition.and(org.jooq.impl.DSL.exists(");
    }

    @Test
    void remoteIn_emitsEmptyListGuardedExists() {
        var fk = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_language_id_fkey"), FILM_TABLE,
            List.of(LANGUAGE_ID), LANGUAGE_TABLE, List.of(LANGUAGE_ID), null, "l0");
        var inner = new BodyParam.In("languageNames", LANGUAGE_NAME, "java.lang.String", false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", "languageNames")));
        var remote = new BodyParam.RemoteColumnPredicate(List.of(fk), inner);
        var body = TypeConditionsGenerator.buildConditionMethod(filter(List.of(remote)), DEFAULT_OUTPUT_PACKAGE)
            .code().toString();

        // Empty-list guard wraps the whole EXISTS, identical to the local In arm.
        assertThat(body).contains("if (languageNames != null && !languageNames.isEmpty()) condition = condition.and(org.jooq.impl.DSL.exists(");
        assertThat(body).contains("languageNames_ref0.NAME.in(languageNames)");
    }

    @Test
    void remoteEq_multiHop_emitsJoinChainBackTowardStepZero() {
        // Filter (table = Address) by country.country via address.city_id -> city.country_id -> country.
        var hop0 = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("address_city_id_fkey"), TestFixtures.tableRef("address", "ADDRESS", "Address", List.of()),
            List.of(CITY_ID), CITY_TABLE, List.of(CITY_ID), null, "c0");
        var hop1 = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("city_country_id_fkey"), CITY_TABLE,
            List.of(COUNTRY_ID), COUNTRY_TABLE, List.of(COUNTRY_ID), null, "c1");
        var inner = new BodyParam.Eq("countryName", COUNTRY_NAME, "java.lang.String", false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", "countryName")));
        var remote = new BodyParam.RemoteColumnPredicate(List.of(hop0, hop1), inner);
        var body = TypeConditionsGenerator.buildConditionMethod(filter(List.of(remote)), DEFAULT_OUTPUT_PACKAGE)
            .code().toString();

        // FROM the terminal (country) alias; JOIN the previous (city) alias back toward step 0.
        assertThat(body).contains(".from(countryName_ref1)");
        assertThat(body).contains(".join(countryName_ref0).onKey(");
        // Step-0 correlation ties the first hop's alias to the method's own table.
        assertThat(body).contains("countryName_ref0.CITY_ID.eq(table.CITY_ID)");
        // Terminal predicate binds the country alias.
        assertThat(body).contains("countryName_ref1.COUNTRY.eq(org.jooq.impl.DSL.val(countryName, countryName_ref1.COUNTRY))");
    }

    @Test
    void remoteRowIn_compositeTerminal_emitsRowInAgainstTerminalAliasAndCompositeCorrelation() {
        // Emitter capacity for a composite terminal tuple (RowIn) reached through a composite FK.
        // Not classifier-reachable in v1, but the wrapper accepts any inner ColumnPredicate.
        var a = new ColumnRef("a", "A", "java.lang.String");
        var b = new ColumnRef("b", "B", "java.lang.String");
        var t = TestFixtures.tableRef("t", "T", "T", List.of());
        var c1 = new ColumnRef("c1", "C1", "java.lang.String");
        var c2 = new ColumnRef("c2", "C2", "java.lang.String");
        var fk = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_t_fkey"), FILM_TABLE,
            List.of(a, b), t, List.of(a, b), null, "t0");
        var inner = new BodyParam.RowIn("keys", List.of(c1, c2), false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", "keys")));
        var remote = new BodyParam.RemoteColumnPredicate(List.of(fk), inner);
        var body = TypeConditionsGenerator.buildConditionMethod(filter(List.of(remote)), DEFAULT_OUTPUT_PACKAGE)
            .code().toString();

        assertThat(body).contains("org.jooq.impl.DSL.row(keys_ref0.C1, keys_ref0.C2).in(keys)");
        // Composite FK correlation ANDs each slot pair.
        assertThat(body).contains("keys_ref0.A.eq(table.A)");
        assertThat(body).contains("keys_ref0.B.eq(table.B)");
        assertThat(body).contains("if (keys != null && !keys.isEmpty()) condition = condition.and(org.jooq.impl.DSL.exists(");
    }

    @Test
    void remotePredicate_methodParamType_delegatesToInner() {
        var fk = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_language_id_fkey"), FILM_TABLE,
            List.of(LANGUAGE_ID), LANGUAGE_TABLE, List.of(LANGUAGE_ID), null, "l0");
        var inner = new BodyParam.Eq("languageName", LANGUAGE_NAME, "java.lang.String", false,
            new CallSiteExtraction.NestedInputField("filter", List.of("filter", "languageName")));
        var remote = new BodyParam.RemoteColumnPredicate(List.of(fk), inner);
        var method = TypeConditionsGenerator.buildConditionMethod(filter(List.of(remote)), DEFAULT_OUTPUT_PACKAGE);
        var param = method.parameters().stream()
            .filter(p -> p.name().equals("languageName")).findFirst().orElseThrow();
        assertThat(param.type().toString()).isEqualTo("java.lang.String");
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
