package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.Locale;

/**
 * Writes the {@code GeneratedScalars} class: one {@code GraphQLScalarType} per scalar the graph asks
 * Graphitron to generate, registered under the author's name, and the one {@code register} method the
 * schema class calls to add them. The store owns which scalars are generated
 * ({@link GeneratedScalarQuery}), so neither the set nor the registration waits on the classified
 * model.
 *
 * <p>On the wire every generated scalar is a string, and its coercion accepts and serializes a string
 * and nothing else. A value is not checked in coercion: what a value means, and whether it names
 * something in the served schema, is decided where an argument of one is decoded at the call that
 * takes it, so a wrong value is answered at that argument rather than failing the whole request.
 *
 * <p>The class also holds what that decode calls, which {@link GeneratedScalarDecodeEmitter} renders
 * at each call: per kind a record of the value's components, {@code SchemaPath} and
 * {@code TypeName}, and a decoder from the wire string checked against the served schema, a wrong
 * value thrown as the generated {@code GraphitronClientException} naming the argument; and the two
 * helpers a requirement is checked with.
 *
 * <pre>{@code
 * GeneratedScalars.SchemaPath decodedInput_Sti = GeneratedScalars.schemaPath(env.getGraphQLSchema(), "input.sti", wire);
 * GeneratedScalars.require(decodedInput_Sti.endType() == null || "Soker".equals(decodedInput_Sti.endType()), ...);
 * }</pre>
 *
 * <p>The record a kind decodes into is named here, not in the store: its name depends on the output
 * package, which is configuration.
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

    private static final ClassName SCHEMA = ClassName.get("graphql.schema", "GraphQLSchema");
    private static final ClassName GRAPHQL_TYPE = ClassName.get("graphql.schema", "GraphQLType");
    private static final ClassName NAMED_TYPE = ClassName.get("graphql.schema", "GraphQLNamedType");
    private static final ClassName NAMED_OUTPUT_TYPE = ClassName.get("graphql.schema", "GraphQLNamedOutputType");
    private static final ClassName OBJECT_TYPE = ClassName.get("graphql.schema", "GraphQLObjectType");
    private static final ClassName INTERFACE_TYPE = ClassName.get("graphql.schema", "GraphQLInterfaceType");
    private static final ClassName FIELDS_CONTAINER = ClassName.get("graphql.schema", "GraphQLFieldsContainer");
    private static final ClassName IMPLEMENTING_TYPE = ClassName.get("graphql.schema", "GraphQLImplementingType");
    private static final ClassName FIELD_DEFINITION = ClassName.get("graphql.schema", "GraphQLFieldDefinition");
    private static final ClassName TYPE_UTIL = ClassName.get("graphql.schema", "GraphQLTypeUtil");

    /** The decoder of a {@code SCHEMA_PATH} value, and the record it decodes into. */
    static final String SCHEMA_PATH_DECODER = "schemaPath";
    static final String SCHEMA_PATH_RECORD = "SchemaPath";

    /** The decoder of a {@code TYPE_NAME} value, and the record it decodes into. */
    static final String TYPE_NAME_DECODER = "typeName";
    static final String TYPE_NAME_RECORD = "TypeName";

    /** The check a requirement is put through, and the transitive interface test an atom calls. */
    static final String REQUIRE = "require";
    static final String IMPLEMENTS_INTERFACE = "implementsInterface";

    private GeneratedScalarEmitter() {}

    /** The rendered class, under {@code outputPackage}'s schema package. */
    public static ClassName className(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", CLASS_NAME);
    }

    /** The record a value of {@code kind} decodes into, nested in {@code scalars}. */
    static ClassName recordClass(ClassName scalars, String kind) {
        return switch (kind) {
            case "SCHEMA_PATH" -> scalars.nestedClass(SCHEMA_PATH_RECORD);
            case "TYPE_NAME" -> scalars.nestedClass(TYPE_NAME_RECORD);
            default -> throw new IllegalArgumentException("no generated scalar kind " + kind);
        };
    }

    /** The method decoding a value of {@code kind} from its wire string. */
    static String decoder(String kind) {
        return switch (kind) {
            case "SCHEMA_PATH" -> SCHEMA_PATH_DECODER;
            case "TYPE_NAME" -> TYPE_NAME_DECODER;
            default -> throw new IllegalArgumentException("no generated scalar kind " + kind);
        };
    }

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
        var clientException = ClassName.get(outputPackage + ".schema", "GraphitronClientException");
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The scalars Graphitron generates for this schema, their registration, and the decode of a\n"
                + "value of each kind into its components, checked against the schema the service serves.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(register.build())
            .addType(record(SCHEMA_PATH_RECORD, "A path through this schema, decoded: the value as provided, the type it starts at,\n"
                + "its field steps joined by dots, and the named type its last step returns.\n",
                "text", "startType", "path", "endType"))
            .addType(record(TYPE_NAME_RECORD, "The name of an object type of this schema, decoded: the value as provided, and the name.\n",
                "text", "name"))
            .addMethod(schemaPath(self, clientException))
            .addMethod(typeName(self, clientException))
            .addMethod(require(clientException))
            .addMethod(implementsInterface())
            .addMethod(reaches())
            .addType(wireString())
            .build();
    }

    /** A record of a kind's components, every one a {@code String}. */
    private static TypeSpec record(String name, String javadoc, String... components) {
        var constructor = MethodSpec.constructorBuilder();
        for (var component : components) {
            constructor.addParameter(ParameterSpec.builder(String.class, component).build());
        }
        return TypeSpec.recordBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc(javadoc)
            .recordConstructor(constructor.build())
            .build();
    }

    /**
     * Decodes a {@code SCHEMA_PATH} value: a type name followed by zero or more field names, separated
     * by dots. The start is an object or interface type of the served schema whose name does not
     * start with {@code _}; each field is looked up on the named type the step before it returns,
     * lists and non-null wrappers stepped through, so a step past a union, a scalar or an enum, which
     * hold no fields, is refused. A null value decodes to a record of nulls without checking.
     */
    private static MethodSpec schemaPath(ClassName self, ClassName clientException) {
        var record = self.nestedClass(SCHEMA_PATH_RECORD);
        var string = ClassName.get(String.class);
        return MethodSpec.methodBuilder(SCHEMA_PATH_DECODER)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(record)
            .addParameter(SCHEMA, "schema")
            .addParameter(string, "argument")
            .addParameter(string, "value")
            .addJavadoc("Decodes the path {@code value} given at {@code argument}, refusing one this schema does not have.\n")
            .beginControlFlow("if (value == null)")
            .addStatement("return new $T(null, null, null, null)", record)
            .endControlFlow()
            .addStatement("$T[] names = value.split($S, -1)", string, "\\.")
            .beginControlFlow("for ($T name : names)", string)
            .beginControlFlow("if (name.isEmpty())")
            .addStatement("throw new $T(argument + $S + value + $S)", clientException, ": \"",
                "\" is not a path: a type name followed by field names, separated by dots")
            .endControlFlow()
            .endControlFlow()
            .addStatement("$T start = schema.getType(names[0])", GRAPHQL_TYPE)
            .beginControlFlow("if (names[0].startsWith($S) || !(start instanceof $T || start instanceof $T))", "_",
                OBJECT_TYPE, INTERFACE_TYPE)
            .addStatement("throw new $T(argument + $S + names[0] + $S)", clientException, ": ",
                " is no object or interface type of this schema")
            .endControlFlow()
            .addStatement("$T at = ($T) start", NAMED_TYPE, NAMED_TYPE)
            .beginControlFlow("for (int i = 1; i < names.length; i++)")
            .beginControlFlow("if (!(at instanceof $T container))", FIELDS_CONTAINER)
            .addStatement("throw new $T(argument + $S + at.getName() + $S + names[i])", clientException, ": ",
                " has no fields, so the path cannot step to ")
            .endControlFlow()
            .addStatement("$T field = container.getFieldDefinition(names[i])", FIELD_DEFINITION)
            .beginControlFlow("if (field == null)")
            .addStatement("throw new $T(argument + $S + at.getName() + $S + names[i])", clientException, ": ",
                " has no field ")
            .endControlFlow()
            .addStatement("at = $T.unwrapAll(field.getType())", TYPE_UTIL)
            .endControlFlow()
            .addStatement("return new $T(value, names[0], $T.join($S, $T.asList(names).subList(1, names.length)), at.getName())",
                record, string, ".", Arrays.class)
            .build();
    }

    /**
     * Decodes a {@code TYPE_NAME} value: the name of an object type of the served schema, as
     * {@code __typename} gives it, whose name does not start with {@code _}. A null value decodes to a
     * record of nulls without checking.
     */
    private static MethodSpec typeName(ClassName self, ClassName clientException) {
        var record = self.nestedClass(TYPE_NAME_RECORD);
        var string = ClassName.get(String.class);
        return MethodSpec.methodBuilder(TYPE_NAME_DECODER)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(record)
            .addParameter(SCHEMA, "schema")
            .addParameter(string, "argument")
            .addParameter(string, "value")
            .addJavadoc("Decodes the type name {@code value} given at {@code argument}, refusing one this schema does not have.\n")
            .beginControlFlow("if (value == null)")
            .addStatement("return new $T(null, null)", record)
            .endControlFlow()
            .beginControlFlow("if (value.startsWith($S) || !(schema.getType(value) instanceof $T))", "_", OBJECT_TYPE)
            .addStatement("throw new $T(argument + $S + value + $S)", clientException, ": ",
                " is no object type of this schema")
            .endControlFlow()
            .addStatement("return new $T(value, value)", record)
            .build();
    }

    /** Refuses a value a requirement on its argument does not hold for, with {@code message}. */
    private static MethodSpec require(ClassName clientException) {
        return MethodSpec.methodBuilder(REQUIRE)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(boolean.class, "holds")
            .addParameter(String.class, "message")
            .addJavadoc("Refuses the value an {@code @assertType} does not hold for, naming the argument and the requirement.\n")
            .beginControlFlow("if (!holds)")
            .addStatement("throw new $T(message)", clientException)
            .endControlFlow()
            .build();
    }

    /**
     * Whether the type named {@code typeName} implements the interface named {@code interfaceName},
     * directly or through another interface, in the served schema.
     */
    private static MethodSpec implementsInterface() {
        return MethodSpec.methodBuilder(IMPLEMENTS_INTERFACE)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(SCHEMA, "schema")
            .addParameter(String.class, "typeName")
            .addParameter(String.class, "interfaceName")
            .addJavadoc("Whether {@code typeName} implements {@code interfaceName}, directly or through another interface.\n")
            .addStatement("return schema.getType(typeName) instanceof $T type && reaches(type, interfaceName)",
                IMPLEMENTING_TYPE)
            .build();
    }

    /** The transitive step of the interface test: whether {@code type} reaches the interface through its own. */
    private static MethodSpec reaches() {
        return MethodSpec.methodBuilder("reaches")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(IMPLEMENTING_TYPE, "type")
            .addParameter(String.class, "interfaceName")
            .beginControlFlow("for ($T each : type.getInterfaces())", NAMED_OUTPUT_TYPE)
            .beginControlFlow("if (each.getName().equals(interfaceName) || each instanceof $T inner && reaches(inner, interfaceName))",
                IMPLEMENTING_TYPE)
            .addStatement("return true")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return false")
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
