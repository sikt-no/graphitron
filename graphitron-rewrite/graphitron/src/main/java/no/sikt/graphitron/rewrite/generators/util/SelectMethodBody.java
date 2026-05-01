package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits the per-(type, alternative) {@code select<TypeName>Alt<N>} method that issues the
 * {@code VALUES (idx, col1, col2, ...) JOIN <table> USING (...) ORDER BY idx} SELECT for one
 * batched group and scatters result rows back to {@code result[idx]}.
 *
 * <p>Sibling shape to {@code TypeFetcherGenerator.buildQueryLookupRowsMethod} but rooted in
 * representations rather than GraphQL arguments. The shared row-construction core lives in
 * {@link ValuesJoinRowBuilder}; only the dispatcher-specific outer plumbing (per-rep binding
 * tuple decode, {@code result[idx] = r} scatter, {@code __typename} literal projection) lives
 * here. For details, see {@link EntityFetcherDispatchClassGenerator}'s class Javadoc and the
 * {@code federation-via-federation-jvm} plan's "Runtime emission" section.
 *
 * <p><b>Join syntax.</b> Uses {@code .join(input).using(...)}; the dispatcher's FROM side is
 * the entity's own jOOQ table only (no FK chain), so quoted-name collisions are not a risk.
 * R55 standardised both this dispatcher path and the lookup root path on {@code .using(...)};
 * the f-E SQL-shape regression test
 * ({@code GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape}) gates the
 * shared shape.
 */
final class SelectMethodBody {

    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ENV        = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD     = ClassName.get("org.jooq", "Record");
    private static final ClassName FIELD      = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION  = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL        = ClassName.get("org.jooq.impl", "DSL");

    private SelectMethodBody() {}

    static MethodSpec buildMethod(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, String jooqPackage
    ) {
        String methodName = "select" + entity.typeName() + "Alt" + altIndex;
        var listOfBindings = ParameterizedTypeName.get(ClassName.get(List.class),
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
        var jooqTableClass = ClassName.get(jooqPackage + ".tables", entity.table().javaClassName());
        var typeClass = ClassName.get(outputPackage + ".types", entity.typeName());
        String tableLocal = "t";
        String inputAlias = decap(entity.typeName()) + "Alt" + altIndex + "Input";
        List<ValuesJoinRowBuilder.Slot> rbSlots = alt.columns().stream()
            .map(ValuesJoinRowBuilder.Slot::new)
            .toList();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, jooqTableClass,
            entity.table().javaFieldName());

        // Typed Row<N+1>[] declaration delegated to the shared row-builder. The for-loop body
        // below stays here because it reads the dispatcher-specific (idx, cols) binding tuple.
        ValuesJoinRowBuilder.emitRowArrayDecl(b, rbSlots, "rows", "bindings.size()");
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            rbSlots, CodeBlock.of("$T.val(idx, $T.class)", DSL, Integer.class), tableLocal,
            (slot, idx) -> CodeBlock.of("cols[$L]", idx));
        b.addStatement("rows[i] = $T.row($L)", DSL, cells);
        b.endControlFlow();

        b.addStatement("$T<?> input = $T.values(rows).as($L)",
            ClassName.get("org.jooq", "Table"), DSL,
            ValuesJoinRowBuilder.aliasArgs(rbSlots, inputAlias));

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

        // Empty WHERE clause keeps the chain symmetric with the lookup-site emission and lets
        // future filters (e.g. R36 Track B's per-typename interface filters) attach by anding into
        // this local. jOOQ folds DSL.noCondition() away at render time.
        b.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);

        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).using($L).where(condition).orderBy(idxCol).fetch())",
            RECORD, tableLocal, ValuesJoinRowBuilder.usingArgs(rbSlots, tableLocal));
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();
        return b.build();
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
