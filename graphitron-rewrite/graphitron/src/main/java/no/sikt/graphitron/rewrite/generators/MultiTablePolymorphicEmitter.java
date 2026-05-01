package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ValuesJoinRowBuilder;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

/**
 * Emits R36 Track B's two-stage fetcher methods for {@link QueryField.QueryInterfaceField}
 * and {@link QueryField.QueryUnionField} (multi-table polymorphism).
 *
 * <p>Stage 1 is one SQL statement: a narrow UNION ALL across participant tables projecting
 * {@code (__typename, __pk0__, ..., __sortN__)} per branch. The database does ORDER BY in one
 * shot. Stage 2 dispatches per {@code __typename} using the post-R55 {@link ValuesJoinRowBuilder}
 * primitive (the same shape the federation {@code _entities} dispatcher uses): one
 * {@code SELECT <Type>.$fields(...) FROM t JOIN VALUES(...) ON t.PK = input.PK ORDER BY idx} per
 * non-empty group. Result records carry the synthetic {@code __typename} column projected as a
 * literal so the schema-class TypeResolver routes each row to the correct concrete GraphQL type.
 *
 * <p><b>Join syntax: {@code .on(...)}, not {@code .using(...)}.</b> Inherits the dispatcher
 * constraint from R55's reviewer pivot: stage 2's projection includes
 * {@code <TypeName>.$fields(env.getSelectionSet(), t, env)}, which references {@code t.<col>}
 * directly; USING would collapse the joined PK columns and risk colliding with $fields-emitted
 * projections. See {@link no.sikt.graphitron.rewrite.generators.util.SelectMethodBody}'s
 * Javadoc for the same rationale on the federation dispatch path.
 *
 * <p>v1 scope: PK-bearing participants with uniform PK arity per interface or union. Validation
 * (in {@code GraphitronSchemaValidator.validateInterfaceType} / {@code validateUnionType})
 * rejects participants without a PK and arity mismatches before code generation runs;
 * collision-resolution and mixed-arity NULL-padding are tracked as follow-ups.
 */
public final class MultiTablePolymorphicEmitter {

    /** Synthetic stage-1 projection column carrying the participant typename literal. */
    public static final String TYPENAME_COLUMN = "__typename";
    /** Stage-1 sort key column alias. Single PK projects the column directly; composite PKs use {@code DSL.jsonbArray(...)}. */
    public static final String SORT_COLUMN = "__sort__";
    /** Stage-1 PK projection alias prefix; per-slot index appended ({@code __pk0__}, {@code __pk1__}, …). */
    public static final String PK_COLUMN_PREFIX = "__pk";
    /** Stage-1 PK projection alias suffix. */
    public static final String PK_COLUMN_SUFFIX = "__";

    private static final ClassName ARRAY_LIST       = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP  = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP              = ClassName.get("java.util", "Map");
    private static final ClassName FIELD            = ClassName.get("org.jooq", "Field");
    private static final ClassName TABLE            = ClassName.get("org.jooq", "Table");
    private static final ClassName DSL_CONTEXT      = ClassName.get("org.jooq", "DSLContext");
    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@interface participant PK";

    private MultiTablePolymorphicEmitter() {}

    /**
     * Emits the methods for one polymorphic root fetcher (interface or union):
     * the public main fetcher, and one private {@code select<Participant>For<Field>} helper
     * per table-bound participant.
     */
    public static List<MethodSpec> emitMethods(
            String fieldName,
            List<ParticipantRef> participants,
            boolean isList,
            String outputPackage, String jooqPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        methods.add(buildMainFetcher(fieldName, tableBoundParticipants, isList, outputPackage, jooqPackage));
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, outputPackage, jooqPackage));
        }
        return methods;
    }

    /**
     * The main fetcher method. Runs stage 1 (narrow UNION ALL of per-branch
     * {@code (typename, pk0..pkN, sort)} projections), groups results by
     * {@code __typename} into binding tuples, dispatches per typename to the per-branch
     * stage-2 helper, and merges the typed Records back in stage-1 order via the
     * {@code Object[] result} scatter pattern shared with the federation dispatcher.
     */
    private static MethodSpec buildMainFetcher(
            String fieldName, List<ParticipantRef.TableBound> participants,
            boolean isList, String outputPackage, String jooqPackage) {

        // Return shape: List<Record> for both list and single cardinality. Per-branch
        // typed Records project different field shapes; graphql-java traverses the
        // collection element-by-element, so List<Record> is the correct uniform carrier.
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        if (participants.isEmpty()) {
            // Empty participant set is rejected by the validator, but emit a defensive empty
            // result so the generator output type-checks regardless of upstream classifier
            // bugs. Equivalent to "no rows" without firing any SQL.
            if (isList) {
                builder.addStatement("$T payload = $T.of()", listOfRecord, LIST);
            } else {
                builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            }
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(redactCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        // Stage 1: narrow UNION ALL of (typename, pk0..pkN, sort) per branch.
        builder.addCode(buildStage1Block(participants, jooqPackage));

        // Stage 1.5: group stage-1 rows by __typename into (idx, pks) bindings.
        int pkArity = participants.get(0).table().primaryKeyColumns().size();
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        builder.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        builder.beginControlFlow("for (int i = 0; i < stage1.size(); i++)");
        builder.addStatement("$T r = stage1.get(i)", RECORD);
        builder.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        // Pull each PK slot by alias and pack into Object[] for the per-typename helper.
        var pksBuilder = CodeBlock.builder().add("new Object[]{");
        for (int s = 0; s < pkArity; s++) {
            if (s > 0) pksBuilder.add(", ");
            pksBuilder.add("r.get($S)", PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        pksBuilder.add("}");
        builder.addStatement("Object[] pks = $L", pksBuilder.build());
        builder.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{i, pks})", ARRAY_LIST);
        builder.endControlFlow();

        // Stage 2: per-typename dispatch — one method call per participant typename.
        for (var participant : participants) {
            String typeName = participant.typeName();
            builder.beginControlFlow("if (byType.containsKey($S))", typeName);
            builder.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            builder.endControlFlow();
        }

        // Merge: walk result[] in stage-1 order, dropping any unresolved slot. Non-null
        // entries are jOOQ Records carrying the synthetic __typename column; the schema-class
        // TypeResolver reads it back to route each element to its concrete GraphQL type.
        if (isList) {
            builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
            builder.beginControlFlow("for (Object o : result)");
            builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
            builder.endControlFlow();
        } else {
            builder.addStatement("$T payload = result.length == 0 ? null : ($T) result[0]", RECORD, RECORD);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Emits the stage-1 SELECT chain: one branch per participant, glued by {@code .unionAll(...)}.
     * Each branch projects {@code DSL.inline("<TypeName>").as("__typename")} plus the participant's
     * PK columns aliased to {@code __pk0__..__pkN__}, plus a {@code __sort__} key. The composite-PK
     * sort key uses {@code DSL.jsonbArray(...)}; single-column PKs project the column directly.
     */
    private static CodeBlock buildStage1Block(List<ParticipantRef.TableBound> participants, String jooqPackage) {
        var b = CodeBlock.builder();
        var tablesClass = ClassName.get(jooqPackage, "Tables");

        // Declare per-participant table aliases for stage 1. Stage-1 aliases are distinct from
        // any stage-2 locals (the stage-2 helpers declare their own t inside their method body).
        for (var participant : participants) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, tablesClass, participant.table().javaFieldName());
        }

        var resultOfRecord = ParameterizedTypeName.get(RESULT, RECORD);
        b.add("$T stage1 = ", resultOfRecord);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            if (p == 0) {
                b.add("dsl.select($L)\n", branchProjection(participant, alias));
                b.add("    .from($L)\n", alias);
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                b.add("        .from($L))\n", alias);
            }
        }
        b.add("    .orderBy($T.field($T.name($S)))\n", DSL, DSL, SORT_COLUMN);
        b.add("    .fetch();\n");
        return b.build();
    }

    /**
     * Builds the per-branch projection list (typename literal + PK columns + sort key) used
     * inside one {@code dsl.select(...)} clause of the stage-1 union.
     */
    private static CodeBlock branchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        for (int s = 0; s < pks.size(); s++) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(s).javaName(), PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        // Sort key: single column projects the PK directly; composite uses jsonbArray for
        // element-wise comparison in PostgreSQL.
        if (pks.size() == 1) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), SORT_COLUMN);
        } else {
            var jsonbArgs = CodeBlock.builder();
            for (int s = 0; s < pks.size(); s++) {
                if (s > 0) jsonbArgs.add(", ");
                jsonbArgs.add("$L.$L", tableAlias, pks.get(s).javaName());
            }
            b.add(", $T.jsonbArray($L).as($S)", DSL, jsonbArgs.build(), SORT_COLUMN);
        }
        return b.build();
    }

    /**
     * Stage-2 per-typename SELECT helper: takes the stage-1 binding tuples for one typename,
     * issues the {@code VALUES (idx, pk0, ..., pkN) JOIN <table> ON t.PK = input.pk0 ... ORDER BY idx}
     * SELECT, and scatters each typed Record back into {@code result[idx]}. Inherits the
     * dispatcher-shape {@code .on(...)} (not {@code .using(...)}) per R55's class-Javadoc rationale
     * on {@code SelectMethodBody}.
     */
    private static MethodSpec buildPerTypenameSelect(
            String fieldName, ParticipantRef.TableBound participant,
            String outputPackage, String jooqPackage) {
        var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
        var typeClass = ClassName.get(outputPackage + ".types", participant.typeName());
        var tablesClass = ClassName.get(jooqPackage, "Tables");

        var listOfBindings = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        String tableLocal = "t";
        String inputAlias = decap(participant.typeName()) + "Input";
        List<ColumnRef> columns = participant.table().primaryKeyColumns();
        Function<ColumnRef, ColumnRef> columnFn = Function.identity();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, tablesClass, participant.table().javaFieldName());

        // Typed Row<N+1>[] declaration delegated to ValuesJoinRowBuilder. The for-loop body
        // unpacks the dispatcher's (idx, pks) binding tuple into row cells.
        ValuesJoinRowBuilder.emitRowArrayDecl(b, columns, columnFn, DIRECTIVE_CONTEXT, "rows", "bindings.size()");
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            columns, columnFn, CodeBlock.of("$T.val(idx, $T.class)", DSL, Integer.class), tableLocal,
            (_, idx) -> CodeBlock.of("cols[$L]", idx));
        b.addStatement("rows[i] = $T.row($L)", DSL, cells);
        b.endControlFlow();

        b.addStatement("$T<?> input = $T.values(rows).as($L)",
            TABLE, DSL, ValuesJoinRowBuilder.aliasArgs(columns, columnFn, inputAlias));

        // Field projection: <TypeName>.$fields(...) plus the synthetic __typename literal so
        // the schema-class TypeResolver routes each row back to its concrete GraphQL type.
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        b.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), $L, env))",
            arrayListOfField, arrayListOfField, typeClass, tableLocal);
        b.addStatement("fields.add($T.inline($S).as($S))", DSL, participant.typeName(), TYPENAME_COLUMN);

        // idx column from the input derived table — needed both for the projection and the
        // ORDER BY, so materialise once.
        var fieldOfInteger = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        b.addStatement("$T idxCol = input.field($S, $T.class)", fieldOfInteger, "idx", Integer.class);
        b.addStatement("fields.add(idxCol)");

        // ON predicate: per-PK-slot equality. Mirrors the dispatcher in SelectMethodBody.
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
        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).orderBy(idxCol).fetch())",
            RECORD, tableLocal, on.build());
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();

        return MethodSpec.methodBuilder(perTypenameMethodName(fieldName, participant.typeName()))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(listOfBindings, "bindings")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
            .addCode(b.build())
            .build();
    }

    private static String perTypenameMethodName(String fieldName, String typeName) {
        return "select" + typeName + "For" + cap(fieldName);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // -- Mirror of TypeFetcherGenerator's private helpers; copied to keep this emitter
    // -- self-contained while we tune the cross-class coupling. If a third consumer
    // -- emerges, lift these to GeneratorUtils.

    private static TypeName syncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(
            ClassName.get("graphql.execution", "DataFetcherResult"),
            valueType.isPrimitive() ? valueType.box() : valueType);
    }

    private static CodeBlock returnSyncSuccess(TypeName valueType, String payloadLocal) {
        TypeName boxed = valueType.isPrimitive() ? valueType.box() : valueType;
        return CodeBlock.of("return $T.<$T>newResult().data($L).build();\n",
            ClassName.get("graphql.execution", "DataFetcherResult"), boxed, payloadLocal);
    }

    private static CodeBlock redactCatchArm(String outputPackage) {
        var errorRouter = ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
        return CodeBlock.of("return $T.redact(e, env);\n", errorRouter);
    }
}
