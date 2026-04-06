package no.sikt.graphitron.rewrite.generators.conditions;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Generates a {@link TypeSpec} for a conditions class from a {@link FieldArgConditionSpec}.
 *
 * <p>The generated class contains a single {@code public static Condition conditions(...)} method
 * with one parameter per filterable argument. Each parameter is null-checked; a non-null value
 * appends the corresponding jOOQ condition with {@code and()}. A null value is a no-op.
 *
 * <p>The generated class has no fields and is not meant to be instantiated.
 */
public class FieldArgConditionCodeGenerator {

    private static final Set<String> BUILTIN_SCALARS = Set.of("Boolean", "Int", "Float", "String", "ID");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName BIG_DECIMAL = ClassName.get(BigDecimal.class);

    public TypeSpec generate(FieldArgConditionSpec spec) {
        return TypeSpec.classBuilder(spec.typeName() + "Conditions")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildConditionsMethod(spec))
            .build();
    }

    private MethodSpec buildConditionsMethod(FieldArgConditionSpec spec) {
        var method = MethodSpec.methodBuilder("conditions")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION);

        for (var arg : spec.args()) {
            method.addParameter(paramTypeName(arg), arg.argName());
        }

        var body = CodeBlock.builder()
            .addStatement("$T c = $T.noCondition()", CONDITION, DSL);

        for (var arg : spec.args()) {
            body.beginControlFlow("if ($L != null)", arg.argName());
            body.add(conditionStatement(spec.tableJavaFieldName(), arg));
            body.endControlFlow();
        }

        body.addStatement("return c");
        method.addCode(body.build());
        return method.build();
    }

    /** Returns the JavaPoet {@link TypeName} for the method parameter of this argument. */
    private TypeName paramTypeName(ArgConditionSpec arg) {
        if (BUILTIN_SCALARS.contains(arg.graphqlTypeName())) {
            return switch (arg.graphqlTypeName()) {
                case "Boolean" -> ClassName.get(Boolean.class);
                case "Int" -> ClassName.get(Integer.class);
                case "Float" -> ClassName.get(Double.class);
                default -> ClassName.get(String.class);  // String, ID
            };
        }
        // Non-scalar → use the jOOQ column type (e.g. the generated enum class)
        int lastDot = arg.columnClassName().lastIndexOf('.');
        String pkg = arg.columnClassName().substring(0, lastDot);
        String simpleName = arg.columnClassName().substring(lastDot + 1);
        return ClassName.get(pkg, simpleName);
    }

    /**
     * Builds the {@code c = c.and(TABLE.COLUMN.op(value))} statement.
     * For Float arguments mapped to a BigDecimal column the value is wrapped in
     * {@code BigDecimal.valueOf(...)}.
     */
    private CodeBlock conditionStatement(String tableJavaFieldName, ArgConditionSpec arg) {
        boolean needsBigDecimalConversion =
            "Float".equals(arg.graphqlTypeName())
            && "java.math.BigDecimal".equals(arg.columnClassName());

        if (needsBigDecimalConversion) {
            return CodeBlock.of("c = c.and($L.$L.$L($T.valueOf($L)));\n",
                tableJavaFieldName, arg.columnJavaName(), arg.op(), BIG_DECIMAL, arg.argName());
        }
        return CodeBlock.of("c = c.and($L.$L.$L($L));\n",
            tableJavaFieldName, arg.columnJavaName(), arg.op(), arg.argName());
    }
}
