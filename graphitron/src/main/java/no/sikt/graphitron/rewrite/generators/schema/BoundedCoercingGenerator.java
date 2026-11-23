package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;

/**
 * Generates {@code BoundedCoercing}, the class nested in the generated schema class that every
 * name-bound scalar's {@code Coercing} is wrapped in (see {@link HelperMethodSink}).
 *
 * <p>A name-bound scalar's coercing is the consumer's, and what it does with its input is its own:
 * one that reads a number may read it from text as readily as from a number. The wrapper sits in
 * front of {@code parseValue} and {@code parseLiteral} and refuses a number whose plain decimal form
 * has more than {@code MAX_DIGITS} digits, however the client wrote it: as a number, as an integer
 * or float literal, or as text that is a number and nothing else. It decides that from the text's
 * characters and the number's scale and bit length, so its own cost is linear in the input and it
 * never builds the number it refuses. Text that is no number, and every other input, reaches the
 * consumer's coercing untouched, as does everything on the way out ({@code serialize},
 * {@code valueToLiteral}).
 *
 * <p>The bound is the one the generated {@code ColumnValues} applies to a column's value, restated
 * here so the schema class needs nothing outside itself. Spec built-in scalars are registered as
 * they are and are not wrapped: they read no text as a number.
 */
final class BoundedCoercingGenerator {

    static final String CLASS_NAME = "BoundedCoercing";

    /** The wrapper as the helper methods of its enclosing class name it. */
    static final ClassName NESTED = ClassName.get("", CLASS_NAME);

    private static final ClassName COERCING = ClassName.get("graphql.schema", "Coercing");
    private static final ClassName CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName COERCED_VARIABLES = ClassName.get("graphql.execution", "CoercedVariables");
    private static final ClassName VALUE = ClassName.get("graphql.language", "Value");
    private static final ClassName STRING_VALUE = ClassName.get("graphql.language", "StringValue");
    private static final ClassName INT_VALUE = ClassName.get("graphql.language", "IntValue");
    private static final ClassName FLOAT_VALUE = ClassName.get("graphql.language", "FloatValue");
    private static final ClassName PARSE_VALUE_EXCEPTION = ClassName.get("graphql.schema", "CoercingParseValueException");
    private static final ClassName PARSE_LITERAL_EXCEPTION = ClassName.get("graphql.schema", "CoercingParseLiteralException");
    private static final ClassName BIG_DECIMAL = ClassName.get(BigDecimal.class);
    private static final ClassName BIG_INTEGER = ClassName.get(BigInteger.class);

    private BoundedCoercingGenerator() {}

    static TypeSpec build() {
        var i = TypeVariableName.get("I");
        var o = TypeVariableName.get("O");
        TypeName coercingOfIO = ParameterizedTypeName.get(COERCING, i, o);
        TypeName valueWildcard = ParameterizedTypeName.get(VALUE, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        TypeName variablesMap = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
            ClassName.OBJECT);

        var of = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.STATIC)
            .addTypeVariable(i)
            .addTypeVariable(o)
            .returns(coercingOfIO)
            .addParameter(coercingOfIO, "inner")
            .addStatement("return new $T<>(inner)", NESTED)
            .build();

        var serialize = override("serialize", o)
            .addParameter(Object.class, "result")
            .addStatement("return inner.serialize(result)")
            .build();
        var serializeIn = override("serialize", o)
            .addParameter(Object.class, "result")
            .addParameter(CONTEXT, "context")
            .addParameter(Locale.class, "locale")
            .addStatement("return inner.serialize(result, context, locale)")
            .build();
        var parseValue = override("parseValue", i)
            .addParameter(Object.class, "input")
            .addStatement("requireValue(input)")
            .addStatement("return inner.parseValue(input)")
            .build();
        var parseValueIn = override("parseValue", i)
            .addParameter(Object.class, "input")
            .addParameter(CONTEXT, "context")
            .addParameter(Locale.class, "locale")
            .addStatement("requireValue(input)")
            .addStatement("return inner.parseValue(input, context, locale)")
            .build();
        var parseLiteral = override("parseLiteral", i)
            .addParameter(Object.class, "input")
            .addStatement("requireLiteral(input)")
            .addStatement("return inner.parseLiteral(input)")
            .build();
        var parseLiteralWithVariables = override("parseLiteral", i)
            .addParameter(Object.class, "input")
            .addParameter(variablesMap, "variables")
            .addStatement("requireLiteral(input)")
            .addStatement("return inner.parseLiteral(input, variables)")
            .build();
        var parseLiteralIn = override("parseLiteral", i)
            .addParameter(valueWildcard, "input")
            .addParameter(COERCED_VARIABLES, "variables")
            .addParameter(CONTEXT, "context")
            .addParameter(Locale.class, "locale")
            .addStatement("requireLiteral(input)")
            .addStatement("return inner.parseLiteral(input, variables, context, locale)")
            .build();
        var valueToLiteral = override("valueToLiteral", VALUE)
            .addParameter(Object.class, "input")
            .addStatement("return inner.valueToLiteral(input)")
            .build();
        var valueToLiteralIn = override("valueToLiteral", valueWildcard)
            .addParameter(Object.class, "input")
            .addParameter(CONTEXT, "context")
            .addParameter(Locale.class, "locale")
            .addStatement("return inner.valueToLiteral(input, context, locale)")
            .build();

        var requireValue = MethodSpec.methodBuilder("requireValue")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(Object.class, "input")
            .addStatement("if (!withinBounds(input)) throw new $T(REFUSAL)", PARSE_VALUE_EXCEPTION)
            .build();
        var requireLiteral = MethodSpec.methodBuilder("requireLiteral")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(Object.class, "input")
            .addStatement("if (!withinBounds(input)) throw new $T(REFUSAL)", PARSE_LITERAL_EXCEPTION)
            .build();

        var withinBounds = MethodSpec.methodBuilder("withinBounds")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Object.class, "input")
            .addJavadoc("Whether {@code input}, where it is a number or text that is one, has at most\n"
                + "{@code MAX_DIGITS} digits written out in full. A literal is read through to what it holds.\n")
            .addStatement("Object value = input")
            .beginControlFlow("if (value instanceof $T stringLiteral)", STRING_VALUE)
            .addStatement("value = stringLiteral.getValue()")
            .nextControlFlow("else if (value instanceof $T intLiteral)", INT_VALUE)
            .addStatement("value = intLiteral.getValue()")
            .nextControlFlow("else if (value instanceof $T floatLiteral)", FLOAT_VALUE)
            .addStatement("value = floatLiteral.getValue()")
            .endControlFlow()
            .addStatement("if (value instanceof $T text) return textWithinBounds(text)", CharSequence.class)
            .addStatement("if (value instanceof $T decimal) return decimalWithinBounds(decimal)", BIG_DECIMAL)
            .beginControlFlow("if (value instanceof $T integer)", BIG_INTEGER)
            .addStatement("return integer.bitLength() <= MAX_BITS && decimalWithinBounds(new $T(integer))", BIG_DECIMAL)
            .endControlFlow()
            .addStatement("return true")
            .build();

        var decimalWithinBounds = MethodSpec.methodBuilder("decimalWithinBounds")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(BIG_DECIMAL, "value")
            .addCode("// The scale and the bit length first, so nothing proportional to the exponent is computed.\n")
            .addStatement("long scale = value.scale()")
            .addStatement("if (scale < -MAX_DIGITS || scale > MAX_DIGITS) return false")
            .addStatement("if (value.unscaledValue().bitLength() > MAX_BITS) return false")
            .addStatement("long precision = value.precision()")
            .addStatement("return (scale <= 0 ? precision - scale : $T.max(precision, scale)) <= MAX_DIGITS", Math.class)
            .build();

        var textWithinBounds = MethodSpec.methodBuilder("textWithinBounds")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(CharSequence.class, "text")
            .addJavadoc("Whether {@code text} is within the bound: {@code true} unless the whole of it, less\n"
                + "surrounding whitespace, is one number (a sign, digits with at most one point, an exponent)\n"
                + "whose plain decimal form has more than {@code MAX_DIGITS} digits. Read off the characters\n"
                + "in one pass; no number is built.\n")
            .addStatement("int from = 0")
            .addStatement("int to = text.length()")
            .addStatement("while (from < to && $T.isWhitespace(text.charAt(from))) from++", Character.class)
            .addStatement("while (to > from && $T.isWhitespace(text.charAt(to - 1))) to--", Character.class)
            .addStatement("int at = from")
            .addStatement("if (at < to && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++")
            .addStatement("long mantissa = 0")
            .addStatement("long significant = 0")
            .addStatement("long fraction = 0")
            .addStatement("boolean point = false")
            .beginControlFlow("for (; at < to; at++)")
            .addStatement("char c = text.charAt(at)")
            .beginControlFlow("if (c == '.' && !point)")
            .addStatement("point = true")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("if (!$T.isDigit(c)) break", Character.class)
            .addStatement("mantissa++")
            .addStatement("if (significant > 0 || $T.digit(c, 10) != 0) significant++", Character.class)
            .addStatement("if (point) fraction++")
            .endControlFlow()
            .addStatement("if (mantissa == 0) return true")
            .addStatement("long exponent = 0")
            .addStatement("boolean negative = false")
            .beginControlFlow("if (at < to && (text.charAt(at) == 'e' || text.charAt(at) == 'E'))")
            .addStatement("at++")
            .beginControlFlow("if (at < to && (text.charAt(at) == '-' || text.charAt(at) == '+'))")
            .addStatement("negative = text.charAt(at) == '-'")
            .addStatement("at++")
            .endControlFlow()
            .addStatement("int digitsFrom = at")
            .beginControlFlow("for (; at < to && $T.isDigit(text.charAt(at)); at++)", Character.class)
            .addStatement("if (exponent <= $T.MAX_VALUE) exponent = exponent * 10 + $T.digit(text.charAt(at), 10)",
                Integer.class, Character.class)
            .endControlFlow()
            .addStatement("if (at == digitsFrom) return true")
            .endControlFlow()
            .addCode("// Something else follows, so this is text and not a number.\n")
            .addStatement("if (at < to) return true")
            .addStatement("if (significant > MAX_DIGITS || exponent > $T.MAX_VALUE) return false", Integer.class)
            .addStatement("long scale = fraction - (negative ? -exponent : exponent)")
            .addStatement("if (scale < -MAX_DIGITS || scale > MAX_DIGITS) return false")
            .addStatement("long precision = $T.max(1, significant)", Math.class)
            .addStatement("return (scale <= 0 ? precision - scale : $T.max(precision, scale)) <= MAX_DIGITS", Math.class)
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(i)
            .addTypeVariable(o)
            .addSuperinterface(coercingOfIO)
            // The interface keeps its earlier, deprecated arities, and a caller holding the scalar may
            // still use them, so each is overridden and handed to the same arity of the wrapped coercing.
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "{$S, $S}", "deprecation", "rawtypes")
                .build())
            .addJavadoc("A name-bound scalar's coercing behind a bound on the numbers it is handed: a number\n"
                + "whose plain decimal form has more than {@code MAX_DIGITS} digits is refused before the\n"
                + "wrapped coercing sees it, whether the client wrote it as a number, as a literal, or as text\n"
                + "that is a number and nothing else. Everything else passes through unchanged.\n")
            .addField(FieldSpec.builder(int.class, "MAX_DIGITS", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("1000").build())
            .addField(FieldSpec.builder(int.class, "MAX_BITS", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("A bit length past which a value has more than {@code MAX_DIGITS} digits: a digit carries\n"
                    + "under four bits.\n")
                .initializer("4 * (MAX_DIGITS + 1)").build())
            .addField(FieldSpec.builder(String.class, "REFUSAL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S + MAX_DIGITS + $S", "A number with more than ", " digits written out in full is not accepted.")
                .build())
            .addField(FieldSpec.builder(coercingOfIO, "inner", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(coercingOfIO, "inner")
                .addStatement("this.inner = inner")
                .build())
            .addMethod(of)
            .addMethod(serialize)
            .addMethod(serializeIn)
            .addMethod(parseValue)
            .addMethod(parseValueIn)
            .addMethod(parseLiteral)
            .addMethod(parseLiteralWithVariables)
            .addMethod(parseLiteralIn)
            .addMethod(valueToLiteral)
            .addMethod(valueToLiteralIn)
            .addMethod(requireValue)
            .addMethod(requireLiteral)
            .addMethod(withinBounds)
            .addMethod(decimalWithinBounds)
            .addMethod(textWithinBounds)
            .build();
    }

    private static MethodSpec.Builder override(String name, TypeName returns) {
        return MethodSpec.methodBuilder(name)
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(returns);
    }
}
