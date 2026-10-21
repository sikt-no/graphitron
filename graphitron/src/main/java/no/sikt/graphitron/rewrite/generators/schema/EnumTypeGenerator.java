package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CarriesObjectForm;
import no.sikt.graphitron.rewrite.model.EnumValueSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashSet;

/**
 * Emits one {@code <TypeName>Type} class per GraphQL enum type into
 * {@code <outputPackage>.rewrite.schema}. Each class exposes a single
 * {@code public static GraphQLEnumType type()} method that rebuilds the enum as a
 * programmatic graphql-java type at runtime. Reads from
 * {@link GraphitronSchema#types()} only — introspection and federation-injected enums don't
 * appear there because the classifier skips them.
 *
 * <p>The {@code type()} method body is statement-shaped; each enum value reaches the local
 * builder via a {@code private static} {@code enumValueDef_<sdlName>()} factory method
 * collected through {@link HelperMethodSink}.
 *
 * <p>Each class also carries the enum's runtime values and the {@link #KNOWN_OR_NULL_METHOD}
 * helper a database read of the enum passes through
 * ({@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.EnumKnownOrNull}): it returns a
 * value the enum names and {@code null} for any other string, logging the latter at DEBUG under
 * the one {@link #UNMAPPED_LOG_CATEGORY} category, so a column value outside the enum resolves
 * to {@code null} rather than to a serialization error that echoes the value to the client.
 */
public final class EnumTypeGenerator {

    private static final ClassName ENUM_TYPE     = ClassName.get("graphql.schema", "GraphQLEnumType");
    private static final ClassName ENUM_VALUE    = ClassName.get("graphql.schema", "GraphQLEnumValueDefinition");
    private static final ClassName SET           = ClassName.get("java.util", "Set");
    private static final ClassName LOGGER        = ClassName.get("org.slf4j", "Logger");
    private static final ClassName LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    /**
     * The static generic {@code <T> T knownOrNull(T value, String coordinate)} method on every
     * {@code <Name>Type} class. Read by the classifier when it builds the reference a wrapped
     * read calls, so the two cannot disagree on the name.
     */
    public static final String KNOWN_OR_NULL_METHOD = "knownOrNull";

    /**
     * The simple name of the logger category, under the schema sub-package, that every enum class
     * logs an unmapped database value to. One category for all enums, so an operator enables
     * DEBUG once.
     */
    public static final String UNMAPPED_LOG_CATEGORY = "UnmappedEnumValue";

    private static final String RUNTIME_VALUES = "RUNTIME_VALUES";
    private static final String UNMAPPED_LOG = "UNMAPPED_LOG";

    private EnumTypeGenerator() {}

    /**
     * Renders the {@code <Name>Type} class for one enum row of the type-keyed command relation.
     * Membership (which enums get a class) lives on the relation's schema-shape rows; this
     * method only renders content.
     *
     * <p>The descriptions and directives come from {@code assembled}'s form of the enum, so a value
     * carries what the post-synthesis schema gives it, a configured {@code @tag} or note among them,
     * rather than what the walk read off the schema as written.
     */
    public static TypeSpec generateFor(GraphitronType.EnumType et, GraphQLSchema assembled, String outputPackage) {
        var schemaType = CarriesObjectForm.formOf(et, et.name(), assembled) instanceof GraphQLEnumType form
            ? form : et.schemaType();
        return buildEnumTypeSpec(et, schemaType, outputPackage);
    }

    private static TypeSpec buildEnumTypeSpec(GraphitronType.EnumType et, GraphQLEnumType schemaType,
                                              String outputPackage) {
        var unit = new GeneratedUnits(outputPackage).schemaShape(et.name());
        // Two values may map to one runtime string (an alias, or a deprecated spelling beside its
        // replacement); graphql-java serializes the first match, and Set.of throws on a repeat.
        var runtimeValues = new LinkedHashSet<String>();
        var sink = new HelperMethodSink();
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newEnum()", ENUM_TYPE, ENUM_TYPE);
        body.addStatement("b.name($S)", et.name());
        var enumDescription = schemaType.getDescription();
        if (enumDescription != null && !enumDescription.isEmpty()) {
            body.addStatement("b.description($S)", enumDescription);
        }
        for (var value : et.values()) {
            var source = schemaType.getValue(value.sdlName());
            String helper = sink.addEnumValueDef(source == null ? value
                : new EnumValueSpec(value.sdlName(), value.runtimeValue(),
                    source.getDescription() == null || source.getDescription().isEmpty()
                        ? null : source.getDescription(),
                    value.deprecationReason(), source));
            body.addStatement("b.value($L())", helper);
            runtimeValues.add(value.runtimeValue());
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", schemaType, sink);
        body.addStatement("return b.build()");

        var typeMethod = MethodSpec.methodBuilder("type")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ENUM_TYPE)
            .addCode(body.build())
            .build();

        var classBuilder = TypeSpec.classBuilder(unit.simpleName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addField(runtimeValuesField(runtimeValues))
            .addField(FieldSpec.builder(LOGGER, UNMAPPED_LOG, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($S)", LOGGER_FACTORY, unit.packageName() + "." + UNMAPPED_LOG_CATEGORY)
                .build())
            .addMethod(typeMethod)
            .addMethod(knownOrNullMethod(et.name()));
        sink.contributeTo(classBuilder);
        return classBuilder.build();
    }

    private static FieldSpec runtimeValuesField(LinkedHashSet<String> runtimeValues) {
        var init = CodeBlock.builder().add("$T.of(", SET);
        boolean first = true;
        for (String value : runtimeValues) {
            if (!first) init.add(", ");
            init.add("$S", value);
            first = false;
        }
        return FieldSpec.builder(ParameterizedTypeName.get(SET, ClassName.get(String.class)), RUNTIME_VALUES,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(init.add(")").build())
            .build();
    }

    /**
     * {@code public static <T> T knownOrNull(T value, String coordinate)}: nulls a {@code String}
     * outside the runtime values and passes anything else through, so a value graphql-java would
     * have accepted is never changed. Generic, so a wrapped read keeps its static type.
     */
    private static MethodSpec knownOrNullMethod(String enumName) {
        var t = TypeVariableName.get("T");
        return MethodSpec.methodBuilder(KNOWN_OR_NULL_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(t, "value")
            .addParameter(String.class, "coordinate")
            .beginControlFlow("if (value instanceof $T s && !$L.contains(s))", String.class, RUNTIME_VALUES)
            .addStatement("$L.debug($S, coordinate, s)", UNMAPPED_LOG,
                "{}: database value '{}' is not a value of enum " + enumName + "; resolving null")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("return value")
            .build();
    }

    /**
     * Builds a {@code private static GraphQLEnumValueDefinition <methodName>()} factory method
     * whose body is statement-flattened.
     */
    static MethodSpec buildValueDefinitionMethod(String methodName, EnumValueSpec value,
                                                  HelperMethodSink sink) {
        // Set the runtime value alongside the name. SchemaGenerator's SDL path defaults runtime
        // value to the name string; `newEnumValueDefinition().name(x).build()` alone leaves it
        // null, which makes every serialization of a "string-matching-enum-name" blow up with
        // "Unknown value 'X'" at the Coercing layer. The runtime string comes pre-resolved off
        // EnumValueSpec — @field(name:) when present, sdlName otherwise.
        var body = CodeBlock.builder();
        body.addStatement("$T.Builder b = $T.newEnumValueDefinition()", ENUM_VALUE, ENUM_VALUE);
        body.addStatement("b.name($S)", value.sdlName());
        body.addStatement("b.value($S)", value.runtimeValue());
        if (value.description() != null) {
            body.addStatement("b.description($S)", value.description());
        }
        if (value.deprecationReason() != null) {
            body.addStatement("b.deprecationReason($S)", value.deprecationReason());
        }
        AppliedDirectiveEmitter.emitApplications(body, "b", value.source(), sink);
        body.addStatement("return b.build()");
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ENUM_VALUE)
            .addCode(body.build())
            .build();
    }
}
