package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Generates one {@code <TypeName>Conditions.java} per type that has fields with filters.
 *
 * <p>Each condition method is a pure function: it takes the jOOQ table alias and typed argument
 * values, and returns a {@code Condition}. No dependency on graphql-java runtime types.
 *
 * <p>Text enum lookup maps are generated as static final fields on the same class.
 */
public class TypeConditionsGenerator {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        // Collect QueryTableFields with filters, grouped by return type name
        var fieldsByReturnType = new LinkedHashMap<String, List<QueryField.QueryTableField>>();
        for (var type : schema.types().values()) {
            if (!(type instanceof GraphitronType.RootType)) continue;
            for (var field : schema.fieldsOf(type.name())) {
                if (field instanceof QueryField.QueryTableField qtf && !qtf.filters().isEmpty()) {
                    fieldsByReturnType
                        .computeIfAbsent(qtf.returnType().returnTypeName(), k -> new ArrayList<>())
                        .add(qtf);
                }
            }
        }

        return fieldsByReturnType.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey()))
            .map(e -> generateConditionsClass(e.getKey(), e.getValue()))
            .toList();
    }

    private static TypeSpec generateConditionsClass(String typeName, List<QueryField.QueryTableField> fields) {
        var builder = TypeSpec.classBuilder(typeName + "Conditions")
            .addModifiers(Modifier.PUBLIC);

        for (var qtf : fields) {
            builder.addMethod(buildConditionMethod(qtf));
            for (var filter : qtf.filters()) {
                if (filter instanceof WhereFilter.TextEnumColumnFilter tf) {
                    builder.addField(buildTextEnumMapField(tf));
                }
            }
        }

        return builder.build();
    }

    static MethodSpec buildConditionMethod(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var jooqTableClass = ClassName.get(GeneratorConfig.getGeneratedJooqPackage() + ".tables",
            tableRef.javaClassName());
        var builder = MethodSpec.methodBuilder(qtf.name() + "Condition")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get("org.jooq", "Condition"))
            .addParameter(jooqTableClass, "table");

        for (var filter : qtf.filters()) {
            switch (filter) {
                case WhereFilter.ColumnFilter cf ->
                    builder.addParameter(ClassName.bestGuess(cf.column().columnClass()), cf.name());
                case WhereFilter.EnumColumnFilter ef ->
                    builder.addParameter(ClassName.bestGuess(ef.enumClassName()), ef.name());
                case WhereFilter.TextEnumColumnFilter tf ->
                    builder.addParameter(String.class, tf.name());
                default -> {}
            }
        }

        builder.addStatement("var condition = $T.noCondition()", DSL);
        for (var filter : qtf.filters()) {
            String col = switch (filter) {
                case WhereFilter.ColumnFilter cf -> cf.column().javaName();
                case WhereFilter.EnumColumnFilter ef -> ef.column().javaName();
                case WhereFilter.TextEnumColumnFilter tf -> tf.column().javaName();
                default -> null;
            };
            String name = switch (filter) {
                case WhereFilter.ColumnFilter cf -> cf.name();
                case WhereFilter.EnumColumnFilter ef -> ef.name();
                case WhereFilter.TextEnumColumnFilter tf -> tf.name();
                default -> null;
            };
            boolean nonNull = switch (filter) {
                case WhereFilter.ColumnFilter cf -> cf.nonNull();
                case WhereFilter.EnumColumnFilter ef -> ef.nonNull();
                case WhereFilter.TextEnumColumnFilter tf -> tf.nonNull();
                default -> false;
            };
            if (col == null) continue;
            if (nonNull) {
                builder.addStatement("condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                    col, DSL, name, col);
            } else {
                builder.addStatement("if ($L != null) condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                    name, col, DSL, name, col);
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    private static FieldSpec buildTextEnumMapField(WhereFilter.TextEnumColumnFilter tf) {
        var MAP = ClassName.get(java.util.Map.class);
        var mapType = ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(String.class));
        var mapEntries = CodeBlock.builder();
        boolean first = true;
        for (var entry : tf.valueMapping().entrySet()) {
            if (!first) mapEntries.add(", ");
            mapEntries.add("$S, $S", entry.getKey(), entry.getValue());
            first = false;
        }
        return FieldSpec.builder(mapType, tf.mapFieldName())
            .addModifiers(Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.of($L)", MAP, mapEntries.build())
            .build();
    }
}
