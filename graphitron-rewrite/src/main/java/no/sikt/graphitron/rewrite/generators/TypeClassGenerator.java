package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Produces one type class per table-mapped GraphQL type in the schema.
 *
 * <p>Class names follow the GraphQL type name (e.g. {@code Film} for GraphQL type {@code Film}).
 * If two GraphQL types map to the same SQL table, each gets its own type class.
 *
 * <p>Each class contains a single {@code $fields(sel, table, env)} method that assembles the
 * SELECT list from a {@link graphql.schema.DataFetchingFieldSelectionSet}. The caller supplies
 * the table alias as a parameter — this is the prerequisite for G5 inline nested fields, which
 * need the parent alias for correlated join conditions. Execution (DSL context, query building,
 * pagination) is the responsibility of the calling {@code *Fetchers} class.
 *
 * <p>Generated files are placed in the {@code rewrite.types} sub-package of the configured
 * output package.
 */
public class TypeClassGenerator {

    // Cross-generator constants (LIST, ENV, SELECTED_FIELD) come from GeneratorUtils via static import.
    private static final ClassName FIELD          = ClassName.get("org.jooq", "Field");
    private static final ClassName SELECTION_SET  = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName ARRAY_LIST     = ClassName.get(ArrayList.class);

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName) {
        var type = (GraphitronType.TableBackedType) schema.type(typeName);
        var columnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnField)
            .map(f -> (ChildField.ColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var platformIdFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.PlatformIdField)
            .map(f -> (ChildField.PlatformIdField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        return buildTypeSpec(typeName, type.table(), columnFields, platformIdFields);
    }

    /**
     * @param typeName        the GraphQL type name (used as the class name)
     * @param tableRef        the resolved table reference with jOOQ field/class names
     * @param columnFields    the scalar column fields to include in {@code $fields()}, in declaration order
     * @param platformIdFields the legacy platform-id fields whose getters return {@code SelectField<String>}
     */
    static TypeSpec buildTypeSpec(String typeName, TableRef tableRef,
            List<ChildField.ColumnField> columnFields,
            List<ChildField.PlatformIdField> platformIdFields) {
        return TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(build$FieldsMethod(tableRef, columnFields, platformIdFields))
            .build();
    }

    /**
     * Generates a {@code $fields(sel, table, env)} method that assembles the SELECT list for one
     * level of the query from a {@link graphql.schema.DataFetchingFieldSelectionSet}.
     *
     * <p>{@code public static} — called cross-class from the {@code *Fetchers} classes.
     * The {@code $} prefix is chosen because GraphQL field names match {@code /[_A-Za-z][_0-9A-Za-z]&#42;/}
     * by spec, so {@code $fields} can never collide with a GraphQL field name.
     *
     * <p>{@code table} is the caller-supplied alias — the prerequisite for G5 inline nested fields,
     * which need the parent alias for correlated join conditions.
     *
     * <p>{@code env} is included now rather than deferred to G5. G5 is the immediate next roadmap
     * item; omitting it here would require a second signature migration one step later.
     */
    private static MethodSpec build$FieldsMethod(TableRef tableRef,
            List<ChildField.ColumnField> columnFields,
            List<ChildField.PlatformIdField> platformIdFields) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);
        var entryType = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"),
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));

        var builder = MethodSpec.methodBuilder("$fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfField)
            .addParameter(SELECTION_SET, "sel")
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("$T<$T> fields = new $T<>()", ARRAY_LIST, fieldWildcard, ARRAY_LIST);

        builder.addCode("for ($T entry : sel.getFieldsGroupedByResultKey().entrySet()) {\n", entryType);
        builder.addCode("    $T sf = entry.getValue().get(0);\n", SELECTED_FIELD);
        builder.addCode("    switch (sf.getName()) {\n");
        for (var cf : columnFields) {
            builder.addCode("        case $S -> fields.add(table.$L);\n",
                cf.name(), cf.column().javaName());
        }
        for (var pf : platformIdFields) {
            builder.addCode("        case $S -> fields.add(table.$L());\n",
                pf.name(), pf.getterName());
        }
        builder.addCode("        default -> { } // nested/unhandled fields\n");
        builder.addCode("    }\n");
        builder.addCode("}\n");

        builder.addStatement("return fields");
        return builder.build();
    }
}
