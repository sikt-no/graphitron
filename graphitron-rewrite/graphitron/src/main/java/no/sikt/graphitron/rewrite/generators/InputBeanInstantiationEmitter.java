package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Emits the {@code create<TypeName>} / {@code create<TypeName>s} helper methods that populate a
 * consumer-authored Java bean from a GraphQL input-object {@code Map}. Used by
 * {@link TypeFetcherGenerator} at the {@code *Fetchers} class assembly site for any
 * {@link CallSiteExtraction.InputBean} that appears in a method-backed field's
 * {@code callParams()}.
 *
 * <p>One emission per unique {@code beanClass} (the bean-helper queue in
 * {@link TypeFetcherGenerator} performs that dedup). The plural helper is emitted alongside the
 * singular helper unconditionally — it is cheap, and the call-site emitter chooses between them
 * based on the param's Java list-shape.
 *
 * <p>Helper signatures (R150 spec):
 * <pre>
 *   private static Bean createBean(Map&lt;String, Object&gt; raw);
 *   private static List&lt;Bean&gt; createBeanList(Object raw);
 * </pre>
 *
 * <p>Cycle-prevention: the helpers reference only JDK types and the consumer's bean class. They
 * never import a graphitron-emitted record.
 */
final class InputBeanInstantiationEmitter {

    private static final ClassName MAP_STRING_OBJECT_RAW = ClassName.get(Map.class);
    private static final ClassName LIST = ClassName.get(List.class);

    private InputBeanInstantiationEmitter() {}

    /**
     * Emits {@code private static Bean createBean(Map<String, Object> raw)}: null in → null out,
     * otherwise instantiate the bean by populating each field from {@code raw.get(sdlFieldName)}
     * through its per-field transform.
     */
    static MethodSpec buildSingularHelper(CallSiteExtraction.InputBean ib) {
        ClassName bean = ib.beanClass();
        TypeName mapStringObject = ParameterizedTypeName.get(MAP_STRING_OBJECT_RAW,
            ClassName.get(String.class), ClassName.get(Object.class));
        var b = MethodSpec.methodBuilder("create" + bean.simpleName())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(bean)
            .addParameter(mapStringObject, "raw")
            .addStatement("if (raw == null) return null");

        // Declare a typed local per field via the per-field extraction expression.
        for (var fb : ib.fields()) {
            b.addStatement("$T $L = $L",
                fieldLocalType(fb), fb.javaFieldName(), perFieldValueExpr(fb));
        }
        // Populate the bean: positional ctor for records, no-arg + setters for JavaBeans.
        switch (ib.target()) {
            case RECORD -> {
                var args = CodeBlock.builder();
                boolean first = true;
                for (var fb : ib.fields()) {
                    if (!first) args.add(", ");
                    first = false;
                    args.add("$L", fb.javaFieldName());
                }
                b.addStatement("return new $T($L)", bean, args.build());
            }
            case JAVA_BEAN -> {
                b.addStatement("$T bean = new $T()", bean, bean);
                for (var fb : ib.fields()) {
                    b.addStatement("bean.$L($L)",
                        "set" + capitalize(fb.javaFieldName()), fb.javaFieldName());
                }
                b.addStatement("return bean");
            }
        }
        return b.build();
    }

    /**
     * Emits {@code private static List<Bean> createBeanList(Object raw)}: null in → null out,
     * otherwise downcast the {@code Object} to {@code List<Map<String, Object>>}, reject null
     * elements (a non-null SDL element type forbids them), and map each element through the
     * singular helper. The {@code List} suffix is used unconditionally — appending a literal
     * {@code "s"} produces ugly names ({@code createDetailss}) for types already ending in
     * {@code s}, and consumers commonly use such names.
     */
    static MethodSpec buildPluralHelper(CallSiteExtraction.InputBean ib, ClassName enclosingClass) {
        ClassName bean = ib.beanClass();
        TypeName listOfBean = ParameterizedTypeName.get(LIST, bean);
        String pluralName = "create" + bean.simpleName() + "List";
        String singularName = "create" + bean.simpleName();
        var b = MethodSpec.methodBuilder(pluralName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfBean)
            .addParameter(Object.class, "raw")
            .addStatement("if (raw == null) return null")
            .addStatement("$T<?> list = ($T<?>) raw", LIST, LIST)
            .addStatement("return list.stream().map(e -> {\n"
                + "  if (e == null) throw new IllegalArgumentException(\"$L: null element not allowed in list argument\");\n"
                + "  @SuppressWarnings(\"unchecked\")\n"
                + "  $T<$T, $T> m = ($T<$T, $T>) e;\n"
                + "  return $L(m);\n"
                + "}).toList()",
                pluralName,
                MAP_STRING_OBJECT_RAW, ClassName.get(String.class), ClassName.get(Object.class),
                MAP_STRING_OBJECT_RAW, ClassName.get(String.class), ClassName.get(Object.class),
                singularName);
        return b.build();
    }

    /** The Java type of the per-field local variable (with {@code List<...>} when list-shaped). */
    private static TypeName fieldLocalType(CallSiteExtraction.FieldBinding fb) {
        ClassName elt = ClassName.bestGuess(fb.javaElementTypeName());
        return fb.list() ? ParameterizedTypeName.get(LIST, elt) : elt;
    }

    /**
     * Expression that produces the typed value to populate the bean's field. Routes by leaf
     * extraction: Direct gives a cast; EnumValueOf decodes via {@code valueOf}; nested InputBean
     * delegates to the recursive {@code createNested(...)} helper.
     */
    private static CodeBlock perFieldValueExpr(CallSiteExtraction.FieldBinding fb) {
        String sdl = fb.sdlFieldName();
        return switch (fb.leaf()) {
            case CallSiteExtraction.Direct ignored -> directExpr(fb, sdl);
            case CallSiteExtraction.EnumValueOf ev -> enumExpr(fb, ev, sdl);
            case CallSiteExtraction.InputBean nested -> nestedBeanExpr(fb, nested, sdl);
            default -> throw new IllegalStateException(
                "Unsupported FieldBinding leaf in InputBean: " + fb.leaf().getClass().getSimpleName());
        };
    }

    private static CodeBlock directExpr(CallSiteExtraction.FieldBinding fb, String sdl) {
        ClassName elt = ClassName.bestGuess(fb.javaElementTypeName());
        if (fb.list()) {
            // List-shaped Direct: cast the raw Map.get value to List<T>. Null in → null out.
            return CodeBlock.of("raw.get($S) == null ? null : ($T<$T>) raw.get($S)",
                sdl, LIST, elt, sdl);
        }
        return CodeBlock.of("($T) raw.get($S)", elt, sdl);
    }

    private static CodeBlock enumExpr(CallSiteExtraction.FieldBinding fb,
                                       CallSiteExtraction.EnumValueOf ev, String sdl) {
        ClassName enumClass = ClassName.bestGuess(ev.enumClassName());
        if (fb.list()) {
            // List of enums: stream the raw List<String>, valueOf each, collect.
            return CodeBlock.of(
                "raw.get($S) == null ? null : (($T<?>) raw.get($S)).stream()"
                + ".map(s -> s == null ? null : $T.valueOf((String) s)).toList()",
                sdl, LIST, sdl, enumClass);
        }
        return CodeBlock.of("raw.get($S) == null ? null : $T.valueOf((String) raw.get($S))",
            sdl, enumClass, sdl);
    }

    private static CodeBlock nestedBeanExpr(CallSiteExtraction.FieldBinding fb,
                                             CallSiteExtraction.InputBean nested, String sdl) {
        String singular = "create" + nested.beanClass().simpleName();
        String plural = singular + "List";
        if (fb.list()) {
            return CodeBlock.of("$L(raw.get($S))", plural, sdl);
        }
        // Singular nested bean: downcast Map.get value to Map<String, Object> and delegate.
        return CodeBlock.of(
            "raw.get($S) == null ? null : $L(($T<$T, $T>) raw.get($S))",
            sdl, singular,
            MAP_STRING_OBJECT_RAW, ClassName.get(String.class), ClassName.get(Object.class),
            sdl);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Walks a {@link CallSiteExtraction.InputBean} and collects all unique {@code InputBean}s it
     * transitively references — itself plus any nested {@code InputBean} leaves on its field
     * bindings. Used by the call site to drive helper emission breadth-first.
     *
     * <p>Dedup is by {@link CallSiteExtraction.InputBean#beanClass()}: two top-level
     * {@code InputBean}s carrying the same bean class are assumed structurally equal (the
     * resolver maps the same Java class to the same SDL input-object type, by construction).
     * The first occurrence wins.
     */
    static void collectTransitively(CallSiteExtraction.InputBean ib,
                                     java.util.Map<ClassName, CallSiteExtraction.InputBean> out) {
        if (out.putIfAbsent(ib.beanClass(), ib) != null) return;
        for (var fb : ib.fields()) {
            if (fb.leaf() instanceof CallSiteExtraction.InputBean nested) {
                collectTransitively(nested, out);
            }
        }
    }
}
