package no.sikt.graphitron.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits a producer's lift from {@link LiftQuery}'s rows: the rows of the table its target binds,
 * for the records its call handed back, one for each, in the order it handed them back.
 *
 * <p>Two units. A lifted table's derived source table, {@link #sourceTable}, is a class of its own
 * in jOOQ's style: the {@code idx} and {@code parent} fields and one field per key column as
 * constants, and {@code of(records)} and {@code perParent(records)}, the
 * {@code VALUES (idx, parent, key...)} table of the records' positions, their parents' and their keys.
 * A lift, {@link #lift}, selects off it: the lifted table left-joins onto it by key, and the rows
 * come back ordered by position, so the payload stands in the call's order whatever order the
 * database reads in, and a record that is null, or whose row is gone, lifts a null in its slot.
 *
 * <pre>{@code
 * public static List<Record> liftFilmsByIds(List<? extends Record> lifted,
 *         DataFetchingEnvironment env, DSLContext dsl) {
 *     Film target = Tables.FILM.as("target");
 *     return dsl.select(Film.$project(SelectionOccurrences.ownFields(env.getSelectionSet()), target, env))
 *         .select(target.FILM_ID.as(FilmSource.FOUND))
 *         .from(FilmSource.of(lifted))
 *         .leftJoin(target).on(target.FILM_ID.eq(FilmSource.FILM_ID))
 *         .orderBy(FilmSource.IDX)
 *         .fetch(r -> r.get(FilmSource.FOUND) == null ? null : r);
 * }
 * }</pre>
 *
 * <p>The generated names, the source table's class, the target type's projection unit and the
 * selection scaffold, are the caller's, minted where every other reference to them is.
 */
public final class LiftEmitter {

    private LiftEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    /** The derived table's alias, and the label of its position column. */
    private static final String ALIAS = "source";
    private static final String IDX = "idx";
    /** The label of the derived table's parent column: the position of the parent a record is for. */
    private static final String PARENT = "parent";
    /** The label the lifted table's first key column is read back under, null where no row joined. */
    private static final String FOUND_LABEL = "__found";
    private static final String TARGET = "target";

    /**
     * The derived source table over {@code table}'s key, as the class {@code name}: the position
     * and key fields as constants, and {@code of(records)}, the table of the records' positions and
     * keys, a null record a row of nulls at its position.
     */
    public static TypeSpec sourceTable(ClassName name, LiftQuery.Table table,
                                       List<LiftQuery.KeyColumn> key) {
        requireKey(table, key);
        var constants = constants(table);
        TypeName[] cellTypes = cellTypes(key);
        var rowClass = ClassName.get("org.jooq", "Row" + cellTypes.length);
        var recordType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Record" + cellTypes.length), cellTypes);

        var type = TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The derived source table over $L's key: one row per lifted record, its position and"
                + " key.\n", table.sqlName())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class)),
                    "IDX", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $T.class)", DSL, DSL, ALIAS, IDX, Integer.class)
                .build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class)),
                    "PARENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $T.class)", DSL, DSL, ALIAS, PARENT, Integer.class)
                .build())
            .addField(FieldSpec.builder(String.class, "FOUND", Modifier.PUBLIC, Modifier.STATIC,
                    Modifier.FINAL)
                .initializer("$S", FOUND_LABEL)
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        var cells = CodeBlock.builder().add("$T.val(i), $T.val(i)", DSL, DSL);
        var parentCells = CodeBlock.builder().add("$T.val(rows.size()), $T.val(p)", DSL, DSL);
        var labels = CodeBlock.builder().add("$S, $S, $S", ALIAS, IDX, PARENT);
        for (int i = 0; i < key.size(); i++) {
            var column = key.get(i);
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, cellTypes[i + 2]),
                    column.javaName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $L.$L.getDataType())", DSL, DSL, ALIAS,
                    column.sqlName(), constants, column.javaName())
                .build());
            cells.add(", $T.val(r == null ? null : r.get($L.$L), $L.$L.getDataType())", DSL,
                constants, column.javaName(), constants, column.javaName());
            parentCells.add(", $T.val(r == null ? null : r.get($L.$L), $L.$L.getDataType())", DSL,
                constants, column.javaName(), constants, column.javaName());
            labels.add(", $S", column.sqlName());
        }
        var suppress = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "{$S, $S}", "unchecked", "rawtypes").build();
        type.addMethod(MethodSpec.methodBuilder("of")
            .addJavadoc("One row per record, its position its own and its parent's.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(suppress)
            .returns(ParameterizedTypeName.get(TABLE, recordType))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "records")
            .addStatement("$T[] rows = new $T[records.size()]", rowClass, rowClass)
            .beginControlFlow("for (int i = 0; i < rows.length; i++)")
            .addStatement("$T r = records.get(i)", RECORD)
            .addStatement("rows[i] = $T.row($L)", DSL, cells.build())
            .endControlFlow()
            .addStatement("return $T.values(rows).as($L)", DSL, labels.build())
            .build());
        var perParent = ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(
            ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD))));
        type.addMethod(MethodSpec.methodBuilder("perParent")
            .addJavadoc("One row per record of every parent, in order: its position, and its parent's.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(suppress)
            .returns(ParameterizedTypeName.get(TABLE, recordType))
            .addParameter(perParent, "records")
            .addStatement("$T<$T> rows = new $T<>()", LIST, rowClass, ClassName.get("java.util", "ArrayList"))
            .beginControlFlow("for (int p = 0; p < records.size(); p++)")
            .addStatement("if (records.get(p) == null) continue")
            .beginControlFlow("for ($T r : records.get(p))", RECORD)
            .addStatement("rows.add($T.row($L))", DSL, parentCells.build())
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.values(rows.toArray(new $T[0])).as($L)", DSL, rowClass, labels.build())
            .build());
        return type.build();
    }

    /**
     * The lift for {@code row}. At a root, the lifted rows as a list, one per record handed back,
     * or the one lifted row. Under an object, where a call answers for a batch of parents, one record
     * per parent lifts as a list, a parent's position being its record's, and a list per parent lifts
     * as a list of lists, each record keeping its slot in its parent's and a parent with no list
     * keeping none.
     *
     * @param methodName           the method's generated name
     * @param source               the class {@link #sourceTable} rendered for the row's table
     * @param projection           the target type's projection unit, whose {@code $project} selects
     *                             the type's fields off the lifted table
     * @param selectionOccurrences the generated selection scaffold the projection reads the
     *                             fetched field's own fields through
     */
    public static MethodSpec lift(LiftQuery.Row row, String methodName, ClassName source,
                                  ClassName projection, ClassName selectionOccurrences) {
        requireKey(row.table(), row.key());
        var table = row.table();
        var project = CodeBlock.of("$T.$$project($T.ownFields(env.getSelectionSet()), $L, env)",
            projection, selectionOccurrences, TARGET);
        var on = new ArrayList<CodeBlock>();
        for (var column : row.key()) {
            on.add(CodeBlock.of("$L.$L.eq($T.$L)", TARGET, column.javaName(), source, column.javaName()));
        }
        var found = CodeBlock.of(".select($L.$L.as($T.FOUND))", TARGET, row.key().getFirst().javaName(),
            source);
        var joined = CodeBlock.of(".leftJoin($L).on($L)", TARGET, chained(on));
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl");
        var listOfRecords = ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD));
        var targetDecl = CodeBlock.of("$T $L = $L.as($S);\n", ClassName.bestGuess(table.tableClassName()),
            TARGET, constants(table), TARGET);

        if (row.list() && !row.root()) {
            var arrayList = ClassName.get("java.util", "ArrayList");
            var perParentType = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD));
            return builder
                .returns(perParentType)
                .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(listOfRecords)), "lifted")
                .addStatement("$T result = new $T<>(lifted.size())", perParentType, arrayList)
                .addStatement("boolean any = false")
                .beginControlFlow("for ($T records : lifted)", listOfRecords)
                .addStatement("result.add(records == null ? null : new $T<>())", arrayList)
                .addStatement("any |= records != null && !records.isEmpty()")
                .endControlFlow()
                .addStatement("if (!any) return result")
                .addCode(targetDecl)
                .addCode(CodeBlock.builder()
                    .add("dsl.select($L)\n", project)
                    .indent()
                    .add("$L\n", found)
                    .add(".select($T.PARENT)\n", source)
                    .add(".from($T.perParent(lifted))\n", source)
                    .add("$L\n", joined)
                    .add(".orderBy($T.IDX)\n", source)
                    .add(".fetch()\n")
                    .add(".forEach(r -> result.get(r.get($T.PARENT)).add(r.get($T.FOUND) == null ? null : r));\n",
                        source, source)
                    .unindent()
                    .build())
                .addStatement("return result")
                .build();
        }

        var select = CodeBlock.builder()
            .add("return dsl.select($L)\n", project)
            .indent()
            .add("$L\n", found);
        if (row.list() || !row.root()) {
            builder.returns(ParameterizedTypeName.get(LIST, RECORD))
                .addParameter(listOfRecords, "lifted")
                .addStatement("if (lifted.isEmpty()) return $T.of()", LIST);
            select.add(".from($T.of(lifted))\n", source);
        } else {
            builder.returns(RECORD).addParameter(RECORD, "lifted")
                .addStatement("if (lifted == null) return null");
            select.add(".from($T.of($T.of(lifted)))\n", source, LIST);
        }
        select.add("$L\n", joined).add(".orderBy($T.IDX)\n", source);
        select.add(row.list() || !row.root()
            ? CodeBlock.of(".fetch(r -> r.get($T.FOUND) == null ? null : r);\n", source)
            : CodeBlock.of(".fetchOptional(r -> r.get($T.FOUND) == null ? null : r).orElse(null);\n", source));
        return builder.addCode(targetDecl).addCode(select.unindent().build()).build();
    }

    private static void requireKey(LiftQuery.Table table, List<LiftQuery.KeyColumn> key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException(table.sqlName() + " has no primary key to lift by");
        }
    }

    /** {@code Tables.FILM}: the table read through its constants class. */
    private static CodeBlock constants(LiftQuery.Table table) {
        return CodeBlock.of("$T.$L", ClassName.bestGuess(table.constantsClassName()),
            table.javaFieldName());
    }

    /** The derived table's cell types: the position, the parent's, then each key column's bound type. */
    private static TypeName[] cellTypes(List<LiftQuery.KeyColumn> key) {
        TypeName[] types = new TypeName[key.size() + 2];
        types[0] = ClassName.get(Integer.class);
        types[1] = ClassName.get(Integer.class);
        for (int i = 0; i < key.size(); i++) {
            types[i + 2] = ClassName.bestGuess(key.get(i).bindingType());
        }
        return types;
    }

    /** The key's comparisons, one per column, as {@code a.and(b).and(c)}. */
    private static CodeBlock chained(List<CodeBlock> conditions) {
        var b = CodeBlock.builder().add("$L", conditions.getFirst());
        conditions.stream().skip(1).forEach(c -> b.add(".and($L)", c));
        return b.build();
    }
}
