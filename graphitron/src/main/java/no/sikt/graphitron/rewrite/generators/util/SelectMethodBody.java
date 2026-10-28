package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.render.ProjectionCall;
import no.sikt.graphitron.render.ValuesJoinRowBuilder;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.TenantBinding;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Function;

/**
 * Emits the per-(type, alternative) {@code select<TypeName>Alt<N>} method that issues the
 * {@code VALUES (idx, col1, col2, ...) JOIN <table> ON ... ORDER BY idx} SELECT for one
 * batched group and scatters result rows back to {@code result[idx]}.
 *
 * <p>Sibling shape to the root {@code @lookupKey} launcher (the keyed-lookup arm of
 * {@link no.sikt.graphitron.render.RootLauncherRenderer}) but rooted in
 * representations rather than GraphQL arguments. The shared row-construction core (typed row
 * array, alias-args list) lives in {@link ValuesJoinRowBuilder}; only the dispatcher-specific
 * outer plumbing (per-rep binding tuple decode, {@code result[idx] = r} scatter, the
 * {@code __typename} literal projection, and the join's {@code ON} predicate) lives here. See
 * {@link EntityFetcherDispatchClassGenerator}'s class Javadoc for the surrounding dispatch shape.
 *
 * <p>The join reads the derived table back through {@link ValuesJoinRowBuilder#onCondition}. The
 * SQL-shape regression test ({@code GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape})
 * gates the {@code values}/{@code join}/{@code order by} substring shape across both call sites.
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

    /**
     * {@code source} is where the alternative's representations read their tenant, or
     * {@code null} when the entity is not tenant-routed (a single-tenant build, or a global
     * entity).
     */
    static MethodSpec buildMethod(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, TenantBinding.EntityRepBound.TenantSource source
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
            .addCode(body(entity, alt, altIndex, outputPackage, source))
            .build();
    }

    private static CodeBlock body(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, TenantBinding.EntityRepBound.TenantSource source
    ) {
        var jooqTableClass = CatalogRefs.tableClass(entity.table());
        var typeClass = ClassName.get(outputPackage + ".types", entity.typeName());
        String tableLocal = "t";
        String inputAlias = decap(entity.typeName()) + "Alt" + altIndex + "Input";
        List<ColumnRef> columns = switch (source) {
            case null -> alt.columns();
            case TenantBinding.EntityRepBound.TenantSource.KeyColumn ignored -> alt.columns();
            // The key names no tenant, so the dispatcher appends the routed tenant as one more
            // cell of each binding, and the join matches it against the tenant column: the
            // lookup resolves exactly as a key carrying the tenant would.
            case TenantBinding.EntityRepBound.TenantSource.RequestEntityDefault requestEntityDefault -> {
                var withTenant = new java.util.ArrayList<>(alt.columns());
                withTenant.add(requestEntityDefault.tenantColumn());
                yield List.copyOf(withTenant);
            }
        };
        Function<ColumnRef, ColumnRef> column = Function.identity();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, jooqTableClass,
            entity.table().javaFieldName());

        // The for-loop body stays here (not in the shared row-builder) because it decodes the
        // dispatcher-specific (idx, cols) binding tuple.
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

        // Field projection. The env is the dispatching field's (_entities, Query.node or
        // Query.nodes), whose immediate fields carry every concrete type's fragment fields, so the
        // selection is restricted to this type: a key another type's fragment reuses for a
        // different field is not this type's.
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        var occurrences = ProjectionCall.selectionOccurrencesClass(outputPackage);
        b.addStatement("$T fields = new $T($L)",
            arrayListOfField, arrayListOfField,
            ProjectionCall.fromGrouped(typeClass,
                CodeBlock.of("$T.restrictTo($L, $S)", occurrences,
                    ProjectionCall.envSelection(occurrences, ProjectionCall.SelectionScope.OWN_FIELDS),
                    entity.typeName()),
                CodeBlock.of("$L", tableLocal)));
        b.addStatement("fields.add($T.inline($S).as($S))",
            DSL, entity.typeName(), EntityFetcherDispatchClassGenerator.TYPENAME_COLUMN);

        // The input derived table's idx column is referenced by both the projection and the
        // ORDER BY clause; materialise it once so both sites share the same Field.
        var fieldOfInteger = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        b.addStatement("$T idxCol = input.field($S, $T.class)",
            fieldOfInteger, "idx", Integer.class);
        b.addStatement("fields.add(idxCol)");

        // Empty WHERE clause keeps the chain symmetric with the lookup-site emission; when
        // nothing ANDs into it, jOOQ folds DSL.noCondition() away at render time, so there is
        // no SQL impact.
        b.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);

        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).where(condition).orderBy(idxCol).fetch())",
            RECORD, tableLocal, ValuesJoinRowBuilder.onCondition(columns, column, tableLocal, "input"));
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();
        return b.build();
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
