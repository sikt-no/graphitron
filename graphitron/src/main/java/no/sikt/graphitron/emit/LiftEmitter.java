package no.sikt.graphitron.emit;

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
 * public static List<Record> filmsByIds(DataFetchingEnvironment env, DSLContext dsl,
 *         List<? extends Record> lifted) {
 *     if (lifted.isEmpty()) return List.of();
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
    /** The column a polymorphic row's type name is read back under. */
    static final String TYPENAME = "__typename";

    /**
     * The derived source table over {@code table}'s key, as the class {@code name}: the position,
     * parent and key fields as constants, the {@code FOUND} label the lifted table's first key column
     * is read back under, and the two factories, {@code of(records)}, one row per record, and
     * {@code perParent(records)}, one row per record of every parent. A null record is a row of null
     * keys at its position.
     */
    public static TypeSpec sourceTable(ClassName name, LiftQuery.Table table,
                                       List<LiftQuery.KeyColumn> key) {
        requireKey(table, key);
        var constants = constants(table);
        TypeName[] cellTypes = cellTypes(key);
        var recordType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Record" + cellTypes.length), cellTypes);
        var integerField = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        var rows = ClassName.get("org.jooq", "Rows");
        var intStream = ClassName.get("java.util.stream", "IntStream");
        var arrayList = ClassName.get("java.util", "ArrayList");

        var type = TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The derived source table over $L's key: one row per lifted record, its position,"
                + " its parent's and its key.\n", table.sqlName())
            .addField(FieldSpec.builder(integerField, "IDX", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $T.class)", DSL, DSL, ALIAS, IDX, Integer.class)
                .build())
            .addField(FieldSpec.builder(integerField, "PARENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $T.class)", DSL, DSL, ALIAS, PARENT, Integer.class)
                .build());
        var first = key.getFirst();
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, cellTypes[2]), "FOUND",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The lifted table's first key column, read back to tell a joined row from none.\n")
            .initializer("$T.field($T.name($S), $L.$L.getDataType())", DSL, DSL, FOUND_LABEL, constants,
                first.javaName())
            .build());
        var ofColumns = new ArrayList<CodeBlock>();
        var perParentColumns = new ArrayList<CodeBlock>();
        ofColumns.add(CodeBlock.of("i -> $T.val(i)", DSL));
        ofColumns.add(CodeBlock.of("i -> $T.val(i)", DSL));
        perParentColumns.add(CodeBlock.of("i -> $T.val(i)", DSL));
        perParentColumns.add(CodeBlock.of("i -> $T.val(parents.get(i))", DSL));
        var labels = CodeBlock.builder().add("$S, $S, $S", ALIAS, IDX, PARENT);
        for (int i = 0; i < key.size(); i++) {
            var column = key.get(i);
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(FIELD, cellTypes[i + 2]),
                    column.javaName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.field($T.name($S, $S), $L.$L.getDataType())", DSL, DSL, ALIAS,
                    column.sqlName(), constants, column.javaName())
                .build());
            ofColumns.add(CodeBlock.of("i -> key(records.get(i), $L.$L)", constants, column.javaName()));
            perParentColumns.add(CodeBlock.of("i -> key(flat.get(i), $L.$L)", constants, column.javaName()));
            labels.add(", $S", column.sqlName());
        }
        var tableType = ParameterizedTypeName.get(TABLE, recordType);
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(MethodSpec.methodBuilder("of")
            .addJavadoc("One row per record, its position its own and its parent's.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tableType)
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "records")
            .addStatement("return $T.values($T.range(0, records.size()).boxed().collect($T.toRowArray($L)))\n.as($L)",
                DSL, intStream, rows, CodeBlock.join(ofColumns, ", "), labels.build())
            .build());
        type.addMethod(MethodSpec.methodBuilder("perParent")
            .addJavadoc("One row per record of every parent, in order: its position, and its parent's.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tableType)
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(
                ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)))), "records")
            .addStatement("$T<$T> flat = new $T<>()", LIST, RECORD, arrayList)
            .addStatement("$T<$T> parents = new $T<>()", LIST, Integer.class, arrayList)
            .beginControlFlow("for (int p = 0; p < records.size(); p++)")
            .addComment("A parent the call handed no list puts no rows here; the lift gives it its null back,")
            .addComment("reading it off the records themselves rather than off this table.")
            .addStatement("if (records.get(p) == null) continue")
            .beginControlFlow("for ($T r : records.get(p))", RECORD)
            .addStatement("flat.add(r)")
            .addStatement("parents.add(p)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.values($T.range(0, flat.size()).boxed().collect($T.toRowArray($L)))\n.as($L)",
                DSL, intStream, rows, CodeBlock.join(perParentColumns, ", "), labels.build())
            .build());
        var v = com.palantir.javapoet.TypeVariableName.get("V");
        type.addMethod(MethodSpec.methodBuilder("key")
            .addJavadoc("A record's value of a key column, bound as the column binds it; null for no record.\n")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(v)
            .returns(ParameterizedTypeName.get(FIELD, v))
            .addParameter(RECORD, "record")
            .addParameter(ParameterizedTypeName.get(FIELD, v), "column")
            .addStatement("return $T.val(record == null ? null : record.get(column), column.getDataType())", DSL)
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
        return lift(row, methodName, source, projection, selectionOccurrences, java.util.Optional.empty());
    }

    /**
     * {@link #lift}, each lifted row also carrying {@code typeName} as the constant column
     * {@code __typename}, which the generated type resolver reads to tell a polymorphic row's type,
     * and projecting only the occurrences of the fetched field's selection that are {@code typeName}'s.
     */
    public static MethodSpec lift(LiftQuery.Row row, String methodName, ClassName source,
                                  ClassName projection, ClassName selectionOccurrences,
                                  java.util.Optional<String> typeName) {
        requireKey(row.table(), row.key());
        var table = row.table();
        // A polymorphic row projects only its own type's occurrences, so a field another member's
        // fragment selects under the same key never reaches this member's projection.
        var selection = typeName
            .map(name -> CodeBlock.of("$T.restrictTo($T.ownFields(env.getSelectionSet()), $S)",
                selectionOccurrences, selectionOccurrences, name))
            .orElse(CodeBlock.of("$T.ownFields(env.getSelectionSet())", selectionOccurrences));
        var project = CodeBlock.of("$T.$$project($L, $L, env)", projection, selection, TARGET);
        var on = new ArrayList<CodeBlock>();
        for (var column : row.key()) {
            on.add(CodeBlock.of("$L.$L.eq($T.$L)", TARGET, column.javaName(), source, column.javaName()));
        }
        var found = typeName
            .map(name -> CodeBlock.of(".select($L.$L.as($T.FOUND))\n.select($T.inline($S).as($S))", TARGET,
                row.key().getFirst().javaName(), source, DSL, name, TYPENAME))
            .orElse(CodeBlock.of(".select($L.$L.as($T.FOUND))", TARGET, row.key().getFirst().javaName(), source));
        var joined = CodeBlock.of(".leftJoin($L).on($L)", TARGET, chained(on));
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl");
        var listOfRecords = ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD));
        var targetDecl = CodeBlock.of("$T $L = $L.as($S);\n", ClassName.bestGuess(table.tableClassName()),
            TARGET, constants(table), TARGET);

        if (row.list() && !row.root()) {
            var perParentType = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD));
            var byParentType = ParameterizedTypeName.get(ClassName.get("java.util", "Map"),
                ClassName.get(Integer.class), ParameterizedTypeName.get(LIST, RECORD));
            return builder
                .returns(perParentType)
                .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(listOfRecords)), "lifted")
                // An empty VALUES is no table, so a batch handing back nothing runs no statement.
                .beginControlFlow("if (lifted.stream().allMatch(records -> records == null || records.isEmpty()))")
                .addStatement("return lifted.stream().map(records -> records == null ? null : $T.<$T>of()).toList()",
                    LIST, RECORD)
                .endControlFlow()
                .addCode(targetDecl)
                .addCode(CodeBlock.builder()
                    .add("$T byParent = dsl.select($L)\n", byParentType, project)
                    .indent()
                    .add("$L\n", found)
                    .add(".select($T.PARENT)\n", source)
                    .add(".from($T.perParent(lifted))\n", source)
                    .add("$L\n", joined)
                    .add(".orderBy($T.IDX)\n", source)
                    .add(".fetchGroups($T.PARENT, r -> r.get($T.FOUND) == null ? null : r);\n", source, source)
                    .unindent()
                    .build())
                .addStatement("$T result = new $T<>(lifted.size())", perParentType,
                    ClassName.get("java.util", "ArrayList"))
                .beginControlFlow("for (int p = 0; p < lifted.size(); p++)")
                .addComment("A parent with no rows in the derived table had no list, which stays null, or an")
                .addComment("empty one, which stays empty.")
                .addStatement("result.add(lifted.get(p) == null ? null : byParent.getOrDefault(p, $T.of()))", LIST)
                .endControlFlow()
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
