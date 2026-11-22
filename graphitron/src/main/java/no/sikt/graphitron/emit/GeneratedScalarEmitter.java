package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.Locale;

/**
 * Writes the {@code GeneratedScalars} class: one {@code GraphQLScalarType} per scalar the graph asks
 * Graphitron to generate, registered under the author's name, and the one {@code register} method the
 * schema class calls to add them. The store owns which scalars are generated
 * ({@link GeneratedScalarQuery}), so neither the set nor the registration waits on the classified
 * model.
 *
 * <p>On the wire every generated scalar is a string, and its coercion accepts and serializes a string
 * and nothing else. A value is not checked here: what a value means, and whether it names something
 * in the served schema, is decided where an argument of one is decoded at the call that takes it, so
 * a wrong value is answered at that argument rather than failing the whole request in coercion.
 */
public final class GeneratedScalarEmitter {

    private static final ClassName SCHEMA_BUILDER = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
    private static final ClassName SCALAR_TYPE = ClassName.get("graphql.schema", "GraphQLScalarType");
    private static final ClassName COERCING = ClassName.get("graphql.schema", "Coercing");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName COERCED_VARIABLES = ClassName.get("graphql.execution", "CoercedVariables");
    private static final ClassName VALUE = ClassName.get("graphql.language", "Value");
    private static final ClassName STRING_VALUE = ClassName.get("graphql.language", "StringValue");
    private static final ClassName SERIALIZE_EXCEPTION = ClassName.get("graphql.schema", "CoercingSerializeException");
    private static final ClassName PARSE_VALUE_EXCEPTION = ClassName.get("graphql.schema", "CoercingParseValueException");
    private static final ClassName PARSE_LITERAL_EXCEPTION = ClassName.get("graphql.schema", "CoercingParseLiteralException");

    /** The class's simple name. */
    public static final String CLASS_NAME = "GeneratedScalars";

    /** The nested coercion every generated scalar shares. */
    static final String WIRE_STRING = "WireString";

    private GeneratedScalarEmitter() {}

    /** The {@code GeneratedScalars} class over {@code rows}, under {@code outputPackage}'s schema package. */
    public static TypeSpec render(GeneratedScalarQuery.Rows rows, String outputPackage) {
        var self = ClassName.get(outputPackage + ".schema", CLASS_NAME);
        var wireString = self.nestedClass(WIRE_STRING);
        var register = MethodSpec.methodBuilder("register")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(SCHEMA_BUILDER, "schemaBuilder")
            .addJavadoc("Adds every generated scalar to the schema, each under its own name.\n");
        for (var scalar : rows.scalars()) {
            register.addStatement("schemaBuilder.additionalType($T.newScalar().name($S).coercing(new $T()).build())",
                SCALAR_TYPE, scalar.name(), wireString);
        }
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The scalars Graphitron generates for this schema, and their registration.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(register.build())
            .addType(wireString())
            .build();
    }

    /** A string on the wire, and nothing else: no number or boolean is turned into one. */
    private static TypeSpec wireString() {
        var string = ClassName.get(String.class);
        var locale = ClassName.get(Locale.class);
        TypeName anyValue = ParameterizedTypeName.get(VALUE, WildcardTypeName.subtypeOf(Object.class));
        var serialize = MethodSpec.methodBuilder("serialize")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(string)
            .addParameter(Object.class, "dataFetcherResult")
            .addParameter(GRAPHQL_CONTEXT, "graphQLContext")
            .addParameter(locale, "locale")
            .beginControlFlow("if (dataFetcherResult instanceof $T)", string)
            .addStatement("return ($T) dataFetcherResult", string)
            .endControlFlow()
            .addStatement("throw new $T($S + dataFetcherResult.getClass().getName())",
                SERIALIZE_EXCEPTION, "a generated scalar is a string, not a ")
            .build();
        var parseValue = MethodSpec.methodBuilder("parseValue")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(string)
            .addParameter(Object.class, "input")
            .addParameter(GRAPHQL_CONTEXT, "graphQLContext")
            .addParameter(locale, "locale")
            .beginControlFlow("if (input instanceof $T)", string)
            .addStatement("return ($T) input", string)
            .endControlFlow()
            .addStatement("throw new $T($S + input.getClass().getName())",
                PARSE_VALUE_EXCEPTION, "a generated scalar is a string, not a ")
            .build();
        var parseLiteral = MethodSpec.methodBuilder("parseLiteral")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(string)
            .addParameter(anyValue, "input")
            .addParameter(COERCED_VARIABLES, "variables")
            .addParameter(GRAPHQL_CONTEXT, "graphQLContext")
            .addParameter(locale, "locale")
            .beginControlFlow("if (input instanceof $T)", STRING_VALUE)
            .addStatement("return (($T) input).getValue()", STRING_VALUE)
            .endControlFlow()
            .addStatement("throw new $T($S + input.getClass().getSimpleName())",
                PARSE_LITERAL_EXCEPTION, "a generated scalar is a string literal, not a ")
            .build();
        var valueToLiteral = MethodSpec.methodBuilder("valueToLiteral")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(anyValue)
            .addParameter(Object.class, "input")
            .addParameter(GRAPHQL_CONTEXT, "graphQLContext")
            .addParameter(locale, "locale")
            .addStatement("return $T.of(serialize(input, graphQLContext, locale))", STRING_VALUE)
            .build();
        return TypeSpec.classBuilder(WIRE_STRING)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(COERCING, string, string))
            .addMethod(serialize)
            .addMethod(parseValue)
            .addMethod(parseLiteral)
            .addMethod(valueToLiteral)
            .build();
    }
}
