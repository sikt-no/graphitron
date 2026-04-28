package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits the per-(type, alternative) {@code select<TypeName>Alt<N>} method that issues the
 * {@code VALUES (idx, col1, col2, ...) JOIN <table> ORDER BY idx} SELECT for one batched
 * group and scatters result rows back to {@code result[idx]}.
 *
 * <p>Sibling shape to {@code TypeFetcherGenerator.buildQueryLookupRowsMethod} but rooted in
 * representations rather than GraphQL arguments. For details, see
 * {@link EntityFetcherDispatchClassGenerator}'s class Javadoc and the
 * {@code federation-via-federation-jvm} plan's "Runtime emission" section.
 */
final class SelectMethodBody {

    private static final ClassName LIST       = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ENV        = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD     = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE      = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD      = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL        = ClassName.get("org.jooq.impl", "DSL");

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
        var jooqTableClass = ClassName.get(jooqPackage + ".tables", entity.table().javaClassName());
        var typeClass = ClassName.get(outputPackage + ".types", entity.typeName());
        String tableLocal = "t";
        String inputAlias = decap(entity.typeName()) + "Alt" + altIndex + "Input";

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, jooqTableClass,
            entity.table().javaFieldName());
        emitRowArray(b, alt, tableLocal);
        b.addStatement("$T<?> input = $T.values(rows).as($S$L)",
            TABLE, DSL, inputAlias, columnNamesList(alt.columns()));

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

        emitJoinSelect(b, alt, tableLocal);
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow(); // for r
        return b.build();
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String columnNamesList(List<ColumnRef> columns) {
        // Used as raw Java source after a comma — JavaPoet's $S would only emit one literal, and
        // we need a comma-separated list of literals. Emit them inline; safe because column SQL
        // names are jOOQ-derived and contain no quotes.
        var sb = new StringBuilder();
        sb.append(", \"idx\"");
        for (var c : columns) {
            sb.append(", ").append("\"").append(c.sqlName()).append("\"");
        }
        return sb.toString();
    }

    private static void emitRowArray(CodeBlock.Builder b, KeyAlternative alt, String tableLocal) {
        int arity = alt.columns().size() + 1; // +1 for idx
        if (arity > 22) {
            throw new IllegalStateException(
                "@key arity " + alt.columns().size() + " + idx exceeds jOOQ's typed Row arity limit (22); "
                + "compound keys with >21 fields are not supported by this dispatcher");
        }
        var rowClass = ClassName.get("org.jooq", "Row" + arity);
        TypeName[] typeArgs = new TypeName[arity];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < alt.columns().size(); i++) {
            typeArgs[i + 1] = ClassName.bestGuess(alt.columns().get(i).columnClass());
        }
        var paramRow = ParameterizedTypeName.get(rowClass, typeArgs);
        // Generic array creation requires the unchecked + rawtypes cast; same pattern as
        // LookupValuesJoinEmitter. Emitted shape:
        //     @SuppressWarnings({"unchecked", "rawtypes"})
        //     Row<N+1><Integer, ...>[] rows = (Row<N+1><Integer, ...>[]) new Row<N+1>[bindings.size()];
        b.add("@$T({$S, $S})\n", SuppressWarnings.class, "unchecked", "rawtypes");
        b.addStatement("$T[] rows = ($T[]) new $T[bindings.size()]",
            paramRow, paramRow, rowClass);
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        var rowArgs = CodeBlock.builder().add("$T.val(idx, $T.class)", DSL, Integer.class);
        for (int i = 0; i < alt.columns().size(); i++) {
            rowArgs.add(", $T.val(cols[$L], $L.$L.getDataType())",
                DSL, i, tableLocal, alt.columns().get(i).javaName());
        }
        b.addStatement("rows[i] = $T.row($L)", DSL, rowArgs.build());
        b.endControlFlow();
    }

    private static void emitJoinSelect(CodeBlock.Builder b, KeyAlternative alt, String tableLocal) {
        // ON clause: t.COL1.eq(input.field("COL1", ColType.class)).and(...)
        var on = CodeBlock.builder();
        for (int i = 0; i < alt.columns().size(); i++) {
            var col = alt.columns().get(i);
            var colClass = ClassName.bestGuess(col.columnClass());
            if (i == 0) {
                on.add("$L.$L.eq(input.field($S, $T.class))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            } else {
                on.add(".and($L.$L.eq(input.field($S, $T.class)))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            }
        }
        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).orderBy(idxCol).fetch())",
            RECORD, tableLocal, on.build());
    }
}
