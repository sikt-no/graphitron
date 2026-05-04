package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Function;

/**
 * Emits the per-(type, alternative) {@code select<TypeName>Alt<N>} method that issues the
 * {@code VALUES (idx, col1, col2, ...) JOIN <table> ON ... ORDER BY idx} SELECT for one
 * batched group and scatters result rows back to {@code result[idx]}.
 *
 * <p>Sibling shape to {@code TypeFetcherGenerator.buildQueryLookupRowsMethod} but rooted in
 * representations rather than GraphQL arguments. The shared row-construction core (typed row
 * array, alias-args list) lives in {@link ValuesJoinRowBuilder}; only the dispatcher-specific
 * outer plumbing (per-rep binding tuple decode, {@code result[idx] = r} scatter, the
 * {@code __typename} literal projection, and the join's {@code ON} predicate) lives here. For
 * details, see {@link EntityFetcherDispatchClassGenerator}'s class Javadoc and the
 * {@code federation-via-federation-jvm} plan's "Runtime emission" section.
 *
 * <p><b>Join syntax: {@code .on(...)}, not {@code .using(...)}.</b> The lookup root path uses
 * {@code .using(...)} (its FROM side is the entity's own table only); the dispatcher could in
 * principle do the same, but the SELECT projection here is built from
 * {@code <TypeName>.$fields(env.getSelectionSet(), t, env)} which references {@code t.<col>}
 * directly. {@code USING} collapses joined columns into a single output column at render time,
 * which can interact poorly with {@code $fields}-emitted projections that may include the joined
 * key columns themselves. Keeping the explicit {@code ON} predicate keeps both sides of the join
 * addressable through their original {@code t.<col>} / {@code input.field("<col>", T.class)}
 * references and matches the legacy emission shape exactly. The f-E SQL-shape regression test
 * ({@code GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape}) gates the
 * {@code values}/{@code join}/{@code order by} substring shape across both call sites.
 */
final class SelectMethodBody {

    private static final ClassName LIST       = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ENV        = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD     = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE      = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD      = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION  = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL        = ClassName.get("org.jooq.impl", "DSL");

    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@key";

    private SelectMethodBody() {}

    static MethodSpec buildMethod(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, String jooqPackage
    ) {
        String methodName = "select" + entity.typeName() + "Alt" + altIndex;
        var listOfBindings = ParameterizedTypeName.get(LIST,
            ArrayTypeName.of(ClassName.get(Object.class)));
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(listOfBindings, "bindings")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
            .addCode(body(entity, alt, altIndex, outputPackage, jooqPackage))
            .build();
    }

    private static CodeBlock body(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, String jooqPackage
    ) {
        var jooqTableClass = entity.table().tableClass();
        var typeClass = ClassName.get(outputPackage + ".types", entity.typeName());
        String tableLocal = "t";
        String inputAlias = decap(entity.typeName()) + "Alt" + altIndex + "Input";
        List<ColumnRef> columns = alt.columns();
        Function<ColumnRef, ColumnRef> column = Function.identity();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, jooqTableClass,
            entity.table().javaFieldName());

        // Typed Row<N+1>[] declaration delegated to the shared row-builder. The for-loop body
        // below stays here because it reads the dispatcher-specific (idx, cols) binding tuple.
        ValuesJoinRowBuilder.emitRowArrayDecl(b, columns, column, DIRECTIVE_CONTEXT, "rows", "bindings.size()");
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            columns, column, CodeBlock.of("$T.val(idx, $T.class)", DSL, Integer.class), tableLocal,
            (_, idx) -> CodeBlock.of("cols[$L]", idx));
        b.addStatement("rows[i] = $T.row($L)", DSL, cells);
        b.endControlFlow();

        b.addStatement("$T<?> input = $T.values(rows).as($L)",
            TABLE, DSL, ValuesJoinRowBuilder.aliasArgs(columns, column, inputAlias));

        // Field projection
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        b.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), $L, env))",
            arrayListOfField, arrayListOfField, typeClass, tableLocal);
        b.addStatement("fields.add($T.inline($S).as($S))",
            DSL, entity.typeName(), EntityFetcherDispatchClassGenerator.TYPENAME_COLUMN);

        // The idx column from the input derived table; needed both in the projection and in the
        // ORDER BY clause. Materialise it once so both sites reference the same Field.
        var fieldOfInteger = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        b.addStatement("$T idxCol = input.field($S, $T.class)",
            fieldOfInteger, "idx", Integer.class);
        b.addStatement("fields.add(idxCol)");

        // Empty WHERE clause keeps the chain symmetric with the lookup-site emission and gives
        // future filters a hook to AND into. Currently unused — when nothing AND-s into it,
        // jOOQ folds DSL.noCondition() away at render time so there is no SQL impact. Planned
        // consumer: R36 Track B (per-typename interface filters); remove this placeholder if R36
        // ships with a different shape.
        b.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);

        // ON predicate: t.COL1.eq(input.field("COL1", ColType.class)).and(...). USING would
        // collapse joined columns and risks interacting with $fields-derived projections that
        // reference the same columns as t.COLn — see class Javadoc.
        var on = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var colClass = ClassName.bestGuess(col.columnClass());
            if (i == 0) {
                on.add("$L.$L.eq(input.field($S, $T.class))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            } else {
                on.add(".and($L.$L.eq(input.field($S, $T.class)))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            }
        }

        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).where(condition).orderBy(idxCol).fetch())",
            RECORD, tableLocal, on.build());
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();
        return b.build();
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
