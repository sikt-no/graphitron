package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link RowsMethodSkeleton}'s framing dispatch — one test per
 * {@link RowsMethodBody} permit. Each test pins the framing the skeleton owns (signature,
 * empty-input gate, {@code DSLContext dsl} resolution, body paste-through), not the body
 * content (the permit's content is opaque {@link CodeBlock}).
 */
@UnitTier
class RowsMethodSkeletonTest {

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName LIST   = ClassName.get("java.util", "List");
    private static final ClassName SET    = ClassName.get("java.util", "Set");
    private static final TypeName  KEY    = ClassName.bestGuess("java.lang.Integer");
    private static final TypeName  LIST_OF_LIST_OF_RECORD =
        ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD));
    private static final TypeName  LIST_OF_KEY = ParameterizedTypeName.get(LIST, KEY);
    private static final TypeName  SET_OF_KEY  = ParameterizedTypeName.get(SET, KEY);

    private static final CodeBlock CTX_CALL = CodeBlock.of("graphitronContext(env)");
    private static final CodeBlock SQL_BODY = CodeBlock.builder()
        .addStatement("return $T.of()", LIST)  // body content placeholder
        .build();

    @Test
    void sqlSplitTable_emitsGateAndDslLineBeforeContent() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "rowsFilms",
            LIST_OF_LIST_OF_RECORD,
            LIST_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.SqlSplitTable(SQL_BODY));

        String src = spec.toString();
        assertThat(src).contains("public static java.util.List<java.util.List<org.jooq.Record>> rowsFilms(");
        assertThat(src).contains("java.util.List<java.lang.Integer> keys");
        assertThat(src).contains("graphql.schema.DataFetchingEnvironment env");
        assertThat(src).contains("if (keys.isEmpty())");
        assertThat(src).contains("return java.util.List.of();");
        assertThat(src).contains("org.jooq.DSLContext dsl = graphitronContext(env).getDslContext(env);");
    }

    @Test
    void sqlSplitLookupTable_emitsSqlFraming() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "rowsFilms",
            LIST_OF_LIST_OF_RECORD,
            LIST_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.SqlSplitLookupTable(SQL_BODY));

        String src = spec.toString();
        assertThat(src).contains("if (keys.isEmpty())");
        assertThat(src).contains("DSLContext dsl");
    }

    @Test
    void sqlRecordTable_emitsSqlFraming() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "rowsFilms",
            LIST_OF_LIST_OF_RECORD,
            LIST_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.SqlRecordTable(SQL_BODY));

        String src = spec.toString();
        assertThat(src).contains("if (keys.isEmpty())");
        assertThat(src).contains("DSLContext dsl");
    }

    @Test
    void sqlRecordLookupTable_emitsSqlFraming() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "rowsFilms",
            LIST_OF_LIST_OF_RECORD,
            LIST_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.SqlRecordLookupTable(SQL_BODY));

        String src = spec.toString();
        assertThat(src).contains("if (keys.isEmpty())");
        assertThat(src).contains("DSLContext dsl");
    }

    @Test
    void service_needsDslTrue_emitsDslLineButNoGate() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "loadFilms",
            LIST_OF_LIST_OF_RECORD,
            SET_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.Service(
                CodeBlock.of("return com.example.Service.loadFilms(keys, dsl);\n"),
                /* needsDsl */ true));

        String src = spec.toString();
        assertThat(src).contains("public static java.util.List<java.util.List<org.jooq.Record>> loadFilms(");
        assertThat(src).contains("java.util.Set<java.lang.Integer> keys");
        assertThat(src).contains("DSLContext dsl");
        assertThat(src)
            .as("Service permit omits the empty-input gate (preserved per spec's Out-of-scope carve-out)")
            .doesNotContain("if (keys.isEmpty())");
        assertThat(src).contains("com.example.Service.loadFilms(keys, dsl)");
    }

    @Test
    void service_needsDslFalse_omitsDslLineAndGate() {
        MethodSpec spec = RowsMethodSkeleton.build(
            "loadFilms",
            LIST_OF_LIST_OF_RECORD,
            SET_OF_KEY,
            CTX_CALL,
            new RowsMethodBody.Service(
                CodeBlock.of("return com.example.Service.loadFilms(keys);\n"),
                /* needsDsl */ false));

        String src = spec.toString();
        assertThat(src).doesNotContain("if (keys.isEmpty())");
        assertThat(src).doesNotContain("DSLContext");
        assertThat(src).contains("com.example.Service.loadFilms(keys)");
    }

    @Test
    void rowsMethodBody_sealedSwitchIsExhaustive() {
        Class<?>[] permitted = RowsMethodBody.class.getPermittedSubclasses();
        assertThat(permitted)
            .as("RowsMethodBody permits exactly the six body shapes (five spec shapes + SqlRecordTableMethod, R43 commit 5)")
            .containsExactlyInAnyOrder(
                RowsMethodBody.SqlSplitTable.class,
                RowsMethodBody.SqlSplitLookupTable.class,
                RowsMethodBody.SqlRecordTable.class,
                RowsMethodBody.SqlRecordLookupTable.class,
                RowsMethodBody.SqlRecordTableMethod.class,
                RowsMethodBody.Service.class);
    }
}
