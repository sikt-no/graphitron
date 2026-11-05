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
 * in jOOQ's style: the {@code idx} field and one field per key column as constants, and
 * {@code of(records)}, the {@code VALUES (idx, key...)} table of the records' positions and keys.
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
            .addField(FieldSpec.builder(String.class, "FOUND", Modifier.PUBLIC, Modifier.STATIC,
                    Modifier.FINAL)
                .initializer("$S", FOUND_LABEL)
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        var cells = CodeBlock.builder().add("$T.val(i)", DSL);
        var labels = CodeBlock.builder().add("$S, $S", ALIAS, IDX);
        for (int i = 0; i < key.size(); i++) {
            var column = key.get(i);
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, cellTypes[i + 1]),
                    column.javaName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $L.$L.getDataType())", DSL, DSL, ALIAS,
                    column.sqlName(), constants, column.javaName())
                .build());
            cells.add(", $T.val(r == null ? null : r.get($L.$L), $L.$L.getDataType())", DSL,
                constants, column.javaName(), constants, column.javaName());
            labels.add(", $S", column.sqlName());
        }
        type.addMethod(MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "{$S, $S}", "unchecked", "rawtypes").build())
            .returns(ParameterizedTypeName.get(TABLE, recordType))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "records")
            .addStatement("$T[] rows = new $T[records.size()]", rowClass, rowClass)
            .beginControlFlow("for (int i = 0; i < rows.length; i++)")
            .addStatement("$T r = records.get(i)", RECORD)
            .addStatement("rows[i] = $T.row($L)", DSL, cells.build())
            .endControlFlow()
            .addStatement("return $T.values(rows).as($L)", DSL, labels.build())
            .build());
        return type.build();
    }

    /**
     * The lift for {@code row}, a root producer's: the lifted rows as a list, one per record handed
     * back, or the one lifted row.
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
        if (!row.root()) {
            throw new IllegalArgumentException(row.coordinate()
                + " lifts under an object, whose rows scatter per parent; this renders a root's");
        }
        requireKey(row.table(), row.key());
        var table = row.table();
        var constants = constants(table);
        var project = CodeBlock.of("$T.$$project($T.ownFields(env.getSelectionSet()), $L, env)",
            projection, selectionOccurrences, TARGET);
        var firstKey = row.key().getFirst().javaName();
        var on = new ArrayList<CodeBlock>();
        for (var column : row.key()) {
            on.add(CodeBlock.of("$L.$L.eq($T.$L)", TARGET, column.javaName(), source, column.javaName()));
        }
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl");
        var select = CodeBlock.builder()
            .add("return dsl.select($L)\n", project)
            .indent()
            .add(".select($L.$L.as($T.FOUND))\n", TARGET, firstKey, source)
            .add(".from($T.of($L))\n", source,
                row.list() ? CodeBlock.of("lifted") : CodeBlock.of("$T.of(lifted)", LIST))
            .add(".leftJoin($L).on($L)\n", TARGET, chained(on))
            .add(".orderBy($T.IDX)\n", source);
        if (row.list()) {
            builder.returns(ParameterizedTypeName.get(LIST, RECORD))
                .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "lifted")
                .addStatement("if (lifted.isEmpty()) return $T.of()", LIST);
            select.add(".fetch(r -> r.get($T.FOUND) == null ? null : r);\n", source);
        } else {
            builder.returns(RECORD).addParameter(RECORD, "lifted")
                .addStatement("if (lifted == null) return null");
            select.add(".fetchOptional(r -> r.get($T.FOUND) == null ? null : r).orElse(null);\n", source);
        }
        var tableClass = ClassName.bestGuess(table.tableClassName());
        return builder
            .addStatement("$T $L = $L.as($S)", tableClass, TARGET, constants, TARGET)
            .addCode(select.unindent().build())
            .build();
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

    /** The derived table's cell types: the position, then each key column's bound type. */
    private static TypeName[] cellTypes(List<LiftQuery.KeyColumn> key) {
        TypeName[] types = new TypeName[key.size() + 1];
        types[0] = ClassName.get(Integer.class);
        for (int i = 0; i < key.size(); i++) {
            types[i + 1] = ClassName.bestGuess(key.get(i).bindingType());
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
