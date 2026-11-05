package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits a producer's lift from one row of {@link LiftQuery}: the rows of the table its target
 * binds, for the records its call handed back, in the order it handed them back.
 *
 * <p>The records' keys become the derived source table, {@code VALUES (idx, key...)}, one row per
 * record with its position; the lifted table joins onto it by key and the rows come back ordered by
 * the position, so the payload stands in the call's order whatever order the database reads in. A
 * null the call handed back lifts nothing. One record needs no derived table: its key is compared
 * directly.
 *
 * <pre>{@code
 * public static List<Record> liftFilmsByIds(List<? extends Record> lifted,
 *         DataFetchingEnvironment env, DSLContext dsl) {
 *     List<Row2<Integer, Integer>> keys = new ArrayList<>();
 *     for (int i = 0; i < lifted.size(); i++) {
 *         Record r = lifted.get(i);
 *         if (r == null) continue;
 *         keys.add(DSL.row(DSL.val(i), DSL.val(r.get(Tables.FILM.FILM_ID), Tables.FILM.FILM_ID.getDataType())));
 *     }
 *     if (keys.isEmpty()) return List.of();
 *     Table<Record2<Integer, Integer>> source = DSL.values(keys.toArray(new Row2[0])).as("source", "idx", "film_id");
 *     Film target = Tables.FILM.as("target");
 *     return dsl.select(Film.$project(SelectionOccurrences.ownFields(env.getSelectionSet()), target, env))
 *         .from(source)
 *         .join(target).on(target.FILM_ID.eq(source.field("film_id", Tables.FILM.FILM_ID.getDataType())))
 *         .orderBy(source.field("idx", Integer.class))
 *         .fetch(r -> r);
 * }
 * }</pre>
 *
 * <p>The generated names the method reads, the target type's projection unit and the selection
 * scaffold, are the caller's, minted where every other reference to them is.
 */
public final class LiftEmitter {

    private LiftEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    private static final String SOURCE = "source";
    private static final String TARGET = "target";

    /**
     * Renders the lift for {@code row}, a root producer's: the lifted rows as a list, or the one
     * lifted row.
     *
     * @param methodName           the method's generated name
     * @param projection           the target type's projection unit, whose {@code $project} selects
     *                             the type's fields off the lifted table
     * @param selectionOccurrences the generated selection scaffold the projection reads the
     *                             fetched field's own fields through
     */
    public static MethodSpec render(LiftQuery.Row row, String methodName, ClassName projection,
                                    ClassName selectionOccurrences) {
        if (!row.root()) {
            throw new IllegalArgumentException(row.coordinate()
                + " lifts under an object, whose rows scatter per parent; this renders a root's");
        }
        if (row.key().isEmpty()) {
            throw new IllegalArgumentException(row.coordinate() + " lifts onto "
                + row.table().sqlName() + ", which has no primary key to lift by");
        }
        var table = row.table();
        var tableClass = ClassName.bestGuess(table.tableClassName());
        var constants = CodeBlock.of("$T.$L", ClassName.bestGuess(table.constantsClassName()),
            table.javaFieldName());
        var project = CodeBlock.of("$T.$$project($T.ownFields(env.getSelectionSet()), $L, env)",
            projection, selectionOccurrences, TARGET);
        return row.list()
            ? list(methodName, row, tableClass, constants, project)
            : single(methodName, row, tableClass, constants, project);
    }

    private static MethodSpec list(String methodName, LiftQuery.Row row, ClassName tableClass,
                                   CodeBlock constants, CodeBlock project) {
        var key = row.key();
        TypeName[] cellTypes = new TypeName[key.size() + 1];
        cellTypes[0] = ClassName.get(Integer.class);
        for (int i = 0; i < key.size(); i++) {
            cellTypes[i + 1] = ClassName.bestGuess(key.get(i).bindingType());
        }
        var rowType = ParameterizedTypeName.get(ClassName.get("org.jooq", "Row" + cellTypes.length),
            cellTypes);
        var recordType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Record" + cellTypes.length), cellTypes);

        var cells = CodeBlock.builder().add("$T.val(i)", DSL);
        var labels = CodeBlock.builder().add("$S, $S", SOURCE, "idx");
        var on = new ArrayList<CodeBlock>();
        for (var column : key) {
            cells.add(", $T.val(r.get($L.$L), $L.$L.getDataType())", DSL, constants,
                column.javaName(), constants, column.javaName());
            labels.add(", $S", column.sqlName());
            on.add(CodeBlock.of("$L.$L.eq($L.field($S, $L.$L.getDataType()))", TARGET,
                column.javaName(), SOURCE, column.sqlName(), constants, column.javaName()));
        }

        var listOfRecords = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "{$S, $S}", "unchecked", "rawtypes").build())
            .returns(listOfRecords)
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "lifted")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addStatement("$T<$T> keys = new $T<>()", LIST, rowType, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < lifted.size(); i++)")
            .addStatement("$T r = lifted.get(i)", RECORD)
            .addStatement("if (r == null) continue")
            .addStatement("keys.add($T.row($L))", DSL, cells.build())
            .endControlFlow()
            .addStatement("if (keys.isEmpty()) return $T.of()", LIST)
            .addStatement("$T<$T> $L = $T.values(keys.toArray(new $T[0])).as($L)", TABLE, recordType,
                SOURCE, DSL, ClassName.get("org.jooq", "Row" + cellTypes.length), labels.build())
            .addStatement("$T $L = $L.as($S)", tableClass, TARGET, constants, TARGET)
            .addCode(CodeBlock.builder()
                .add("return dsl.select($L)\n", project)
                .indent()
                .add(".from($L)\n", SOURCE)
                .add(".join($L).on($L)\n", TARGET, chained(on))
                .add(".orderBy($L.field($S, $T.class))\n", SOURCE, "idx", Integer.class)
                .add(".fetch(r -> r);\n")
                .unindent()
                .build())
            .build();
    }

    private static MethodSpec single(String methodName, LiftQuery.Row row, ClassName tableClass,
                                     CodeBlock constants, CodeBlock project) {
        var where = new ArrayList<CodeBlock>();
        for (var column : row.key()) {
            where.add(CodeBlock.of("$L.$L.eq(lifted.get($L.$L))", TARGET, column.javaName(),
                constants, column.javaName()));
        }
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(RECORD, "lifted")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addStatement("if (lifted == null) return null")
            .addStatement("$T $L = $L.as($S)", tableClass, TARGET, constants, TARGET)
            .addCode(CodeBlock.builder()
                .add("return dsl.select($L)\n", project)
                .indent()
                .add(".from($L)\n", TARGET)
                .add(".where($L)\n", chained(where))
                .add(".fetchOne(r -> r);\n")
                .unindent()
                .build())
            .build();
    }

    /** The key's comparisons, one per column, as {@code a.and(b).and(c)}. */
    private static CodeBlock chained(List<CodeBlock> conditions) {
        var b = CodeBlock.builder().add("$L", conditions.getFirst());
        conditions.stream().skip(1).forEach(c -> b.add(".and($L)", c));
        return b.build();
    }
}
