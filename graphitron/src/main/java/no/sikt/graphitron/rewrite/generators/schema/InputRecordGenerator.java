package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HasInputRecordShape;
import no.sikt.graphitron.rewrite.model.InputRecordShape;
import no.sikt.graphitron.rewrite.model.InputRecordShape.InputComponent;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits one Java class per reachable SDL {@code input} type into
 * {@code <outputPackage>.inputs}. Each emitted class is the validation target the rewired
 * pre-step at {@code TypeFetcherGenerator.validatorPreStep} hands to
 * {@code jakarta.validation.Validator#validate} after materialising via
 * {@code <InputName>.fromMap(env.getArgument(...))}.
 *
 * <p>The emitted shape is intentionally a plain Java class (not a Java {@code record}) and the
 * package contains no sealed marker / {@code package-info.java} pending a
 * {@code graphitron-javapoet} upgrade that can emit them. A build-time audit of service-side
 * references is deferred as a follow-on; in the meantime the "graphitron-internal" intent rides
 * on the package boundary and the per-class Javadoc this generator stamps on every emitted carrier.
 *
 * <p>Reachable closure: every SDL input type referenced by a field argument, transitively
 * through nested input components, gets a class. Non-reachable inputs are dead schema and the
 * generator ignores them.
 *
 * <p>Each emitted class declares a {@code static <Self> fromMap(Map<String,Object>)} factory.
 * Nested input components recurse the factory; SDL list wraps stream through
 * {@code List.stream().map(...).toList()} with element-level recursion when the element is
 * a nested input class.
 */
public final class InputRecordGenerator {

    private static final ClassName MAP        = ClassName.get(Map.class);
    private static final ClassName LIST       = ClassName.get(List.class);
    // List<?> for the intermediate `_raw` local: the element type genuinely arrives erased off
    // the graphql-java argument map (a wire boundary), so the honest decl is the wildcard, not a
    // raw List. The per-element coercion below stays unchecked and is covered by fromMap's
    // method-level @SuppressWarnings("unchecked").
    private static final ParameterizedTypeName LIST_WILDCARD =
        ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class));
    private static final ClassName STRING     = ClassName.get(String.class);
    private static final ClassName OBJECT     = ClassName.get(Object.class);
    private static final ParameterizedTypeName MAP_STRING_OBJECT =
        ParameterizedTypeName.get(MAP, STRING, OBJECT);

    private InputRecordGenerator() {}

    /**
     * Emits the input-record classes for every SDL input type reachable from a field argument
     * (transitively through nested input components). The returned list is sorted by class
     * name for deterministic file output.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled, String outputPackage) {
        Set<String> reachable = reachableInputTypeNames(schema, assembled);
        var specs = new ArrayList<TypeSpec>(reachable.size());
        for (var entry : schema.types().entrySet()) {
            if (!reachable.contains(entry.getKey())) continue;
            if (!(entry.getValue() instanceof HasInputRecordShape carrier)) continue;
            specs.add(buildClassSpec(carrier.recordShape(), outputPackage));
        }
        specs.sort(Comparator.comparing(TypeSpec::name));
        return specs;
    }

    /**
     * Walks every SDL field's argument types in the assembled schema, plus nested input
     * components transitively, collecting the names of every input type that needs an emitted
     * carrier. Seeds from every {@code GraphQLObjectType} in the assembled schema (the rewrite
     * model's {@code RootType} / {@code TableBackedType} variants don't carry a
     * {@code schemaType()} accessor, so the assembled schema is the authoritative source for
     * SDL field-arg walking). Introspection (names starting with {@code __}) is excluded.
     */
    static Set<String> reachableInputTypeNames(GraphitronSchema schema, GraphQLSchema assembled) {
        Set<String> reachable = new LinkedHashSet<>();
        java.util.Deque<String> work = new java.util.ArrayDeque<>();

        for (var namedType : assembled.getAllTypesAsList()) {
            if (namedType.getName().startsWith("__")) continue;
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            for (GraphQLFieldDefinition field : objType.getFieldDefinitions()) {
                for (GraphQLArgument arg : field.getArguments()) {
                    seedInputArg(arg.getType(), reachable, work);
                }
            }
        }

        while (!work.isEmpty()) {
            String name = work.poll();
            GraphitronType t = schema.types().get(name);
            GraphQLInputObjectType schemaType = inputSchemaTypeOf(t);
            if (schemaType == null) continue;
            for (var f : schemaType.getFieldDefinitions()) {
                seedInputArg(f.getType(), reachable, work);
            }
        }

        return reachable;
    }

    private static GraphQLInputObjectType inputSchemaTypeOf(GraphitronType t) {
        return switch (t) {
            case GraphitronType.InputType it -> it.schemaType();
            case GraphitronType.TableInputType tit -> tit.schemaType();
            case null, default -> null;
        };
    }

    private static void seedInputArg(GraphQLType type, Set<String> reachable, java.util.Deque<String> work) {
        var base = GraphQLTypeUtil.unwrapAll(type);
        if (base instanceof GraphQLInputObjectType in) {
            String name = in.getName();
            if (reachable.add(name)) work.add(name);
        }
    }

    private static TypeSpec buildClassSpec(InputRecordShape shape, String outputPackage) {
        String className = shape.recordClass().simpleName();
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Graphitron-internal validation target for SDL input type "
                + "{@code $L}. Materialised at the fetcher boundary via {@link #fromMap},\n"
                + "handed once to {@code jakarta.validation.Validator#validate}, and "
                + "discarded.\n\n"
                + "<p><strong>Do not reference from service code.</strong> This class lives "
                + "under {@code $L.inputs} and exists only so the validator pre-step has an "
                + "annotated walk target. Service code consumes the consumer-bean path or "
                + "the existing {@code Map.get} pattern.\n",
                className, outputPackage);

        var fields = new ArrayList<FieldSpec>(shape.components().size());
        for (InputComponent c : shape.components()) {
            fields.add(FieldSpec.builder(c.javaType(), c.javaComponentName(),
                Modifier.PRIVATE, Modifier.FINAL).build());
        }
        fields.forEach(builder::addField);

        var ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE);
        for (InputComponent c : shape.components()) {
            ctor.addParameter(c.javaType(), c.javaComponentName());
            ctor.addStatement("this.$L = $L", c.javaComponentName(), c.javaComponentName());
        }
        builder.addMethod(ctor.build());

        for (InputComponent c : shape.components()) {
            builder.addMethod(MethodSpec.methodBuilder(c.javaComponentName())
                .addModifiers(Modifier.PUBLIC)
                .returns(c.javaType())
                .addStatement("return this.$L", c.javaComponentName())
                .build());
        }

        builder.addMethod(buildFromMap(shape, outputPackage));

        return builder.build();
    }

    /**
     * Emits the {@code static <Self> fromMap(Map<String,Object>)} factory. Body reads each
     * component off the map by SDL field name and coerces:
     *
     * <ul>
     *   <li>Scalar / enum components: direct cast to the component's Java type.</li>
     *   <li>Nested input components: recurse {@code NestedClass.fromMap((Map) raw)}; an absent
     *       key or explicit {@code null} yields a {@code null} component (symmetric-null
     *       contract).</li>
     *   <li>List components: stream the input list, recurse {@code fromMap} per element when
     *       the element is a nested input, or pass-through otherwise. Wraps a non-null list;
     *       absent / explicit-null list yields {@code null}.</li>
     * </ul>
     *
     * <p>Runtime type mismatches surface as {@code ClassCastException}; graphql-java's default
     * error pipeline handles them.
     */
    private static MethodSpec buildFromMap(InputRecordShape shape, String outputPackage) {
        ClassName selfClass = shape.recordClass();
        var body = CodeBlock.builder();
        body.beginControlFlow("if (in == null)");
        body.addStatement("return null");
        body.endControlFlow();

        var ctorArgs = CodeBlock.builder();
        for (int i = 0; i < shape.components().size(); i++) {
            if (i > 0) ctorArgs.add(",\n");
            InputComponent c = shape.components().get(i);
            String local = "c_" + c.javaComponentName();
            body.add(readComponent(c, outputPackage, local));
            ctorArgs.add("$L", local);
        }
        body.add("return new $T(\n", selfClass);
        body.indent();
        body.add(ctorArgs.build());
        body.unindent();
        body.add("\n);\n");

        return MethodSpec.methodBuilder("fromMap")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Materialises a {@link $T} from the raw graphql-java argument map. "
                + "Returns {@code null} when {@code in} is {@code null} (symmetric-null "
                + "contract: an absent key and an explicit null value collapse upstream of "
                + "this factory). Nested input components recurse into sibling classes.\n",
                selfClass)
            .addParameter(MAP_STRING_OBJECT, "in")
            .returns(selfClass)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build())
            .addCode(body.build())
            .build();
    }

    /**
     * Emits the statement(s) that read one component off the input map into a typed local.
     * Returns a {@link CodeBlock} fragment; the caller threads each local into the
     * constructor call.
     */
    private static CodeBlock readComponent(InputComponent c, String outputPackage, String local) {
        TypeName javaType = c.javaType();
        String sdlName = c.sdlFieldName();
        var b = CodeBlock.builder();
        if (javaType instanceof ParameterizedTypeName pt && pt.rawType().equals(LIST)) {
            // List<X>: stream the raw list with element-wise coercion. fromMap recurses on
            // nested-input element types; scalar / enum elements direct-cast. A null raw list
            // (absent key or explicit null on the wire) yields a null component (symmetric-null
            // contract). The intermediate `_raw` local lives only at codegen scope.
            TypeName element = pt.typeArguments().get(0);
            String rawLocal = local + "_raw";
            b.addStatement("$T $L = ($T) in.get($S)", LIST_WILDCARD, rawLocal, LIST_WILDCARD, sdlName);
            if (isInputClass(element, outputPackage)) {
                ClassName elemClass = (ClassName) element;
                b.addStatement(
                    "$T $L = $L == null ? null : $L.stream().map(element -> $T.fromMap(($T) element)).toList()",
                    javaType, local, rawLocal, rawLocal, elemClass, MAP_STRING_OBJECT);
            } else {
                b.addStatement(
                    "$T $L = $L == null ? null : $L.stream().map(element -> ($T) element).toList()",
                    javaType, local, rawLocal, rawLocal, element);
            }
        } else if (isInputClass(javaType, outputPackage)) {
            // Nested input ref: recurse fromMap. The recursive call's own top-level null
            // guard collapses absent / explicit-null wire shapes to a null component.
            ClassName nested = (ClassName) javaType;
            b.addStatement("$T $L = $T.fromMap(($T) in.get($S))",
                nested, local, nested, MAP_STRING_OBJECT, sdlName);
        } else {
            // Scalar / enum / fallback Object: direct cast.
            b.addStatement("$T $L = ($T) in.get($S)", javaType, local, javaType, sdlName);
        }
        return b.build();
    }

    /**
     * True when {@code type} is a {@link ClassName} pointing at a sibling
     * {@code <outputPackage>.inputs.*} class — the signal that this component is a nested
     * input ref and {@code fromMap} should recurse rather than direct-cast.
     */
    private static boolean isInputClass(TypeName type, String outputPackage) {
        if (!(type instanceof ClassName cn)) return false;
        return (outputPackage + ".inputs").equals(cn.packageName());
    }
}
