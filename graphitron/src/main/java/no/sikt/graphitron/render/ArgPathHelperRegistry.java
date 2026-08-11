package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-class collector for the nested-argument descents a {@code @routine} call needs, the
 * routine-side sibling of {@link CompositeDecodeHelperRegistry}. A routine IN parameter bound to a
 * dot-path ({@code argMapping: "pBrukernavn: input.brukernavn"}) reads its value out of the outer
 * argument's {@code Map}; instead of inlining that walk as an {@code instanceof Map<?, ?>} ternary
 * chain inside the {@code Routines.<method>(...)} argument list, the call site registers a helper
 * here and collapses to {@code <name>(<root>)}.
 *
 * <p>Helpers are private static methods on the host class, and the body is a statement sequence:
 * one guarded rebind per level, an early {@code return null} when a level is absent or is not a
 * {@code Map}, and the leaf cast last. That shape is the point rather than an incidental style
 * choice; see the "statement form over expression tricks" rule in
 * {@code docs/architecture/explanation/development-principles.adoc}, whose stated reason is that a
 * developer cannot breakpoint a ternary arm.
 *
 * <p>The root map arrives as a <em>parameter</em> rather than being read inside the helper body.
 * The two {@link ArgumentValueSource} forks are two call paths with two different roots
 * ({@code env.getArgument(outer)} and {@code <sf>.getArguments().get(outer)}), and a
 * locally-declared root would force one fork's read on the other; this is the same reasoning
 * {@code docs/architecture/reference/emitter-conventions.adoc#helper-locality} gives for passing
 * the aliased {@code Table} in.
 */
public final class ArgPathHelperRegistry {

    /**
     * Two bindings share a helper when they walk the same tail out of an equally-named outer slot
     * to the same Java type. The head name is part of the key only because it names the helper;
     * the body never mentions it.
     */
    private record Key(String headName, List<String> tail, TypeName leafType) {}

    private final Map<Key, String> helperNames = new LinkedHashMap<>();
    private final Map<Key, MethodSpec> helpers = new LinkedHashMap<>();

    /**
     * Brackets construct-register-drain so a registered helper can never be silently dropped:
     * constructs a fresh registry, hands it to {@code body}, then drains every collected helper
     * onto {@code classBuilder}. A dropped drain would surface as a dangling
     * {@code arg<Param>(...)} reference in the generated source and a consumer compile error
     * rather than a generator failure.
     */
    public static void collectInto(TypeSpec.Builder classBuilder,
            java.util.function.Consumer<ArgPathHelperRegistry> body) {
        var registry = new ArgPathHelperRegistry();
        body.accept(registry);
        registry.emit().forEach(classBuilder::addMethod);
    }

    /**
     * Registers the descent for one dot-path binding and returns the helper's method name.
     *
     * @param headName the outer slot the caller's root expression reads; names the helper
     * @param tail     the segment names below the head, depth-ordered and non-empty
     * @param leafType the routine parameter's boxed Java type, which the helper casts to and returns
     */
    public String register(String headName, List<String> tail, TypeName leafType) {
        var key = new Key(headName, List.copyOf(tail), leafType);
        String existing = helperNames.get(key);
        if (existing != null) return existing;
        String name = uniqueName(key);
        helperNames.put(key, name);
        helpers.put(key, buildHelper(key, name));
        return name;
    }

    /** All collected helper specs in registration order. Empty when nothing was registered. */
    public Collection<MethodSpec> emit() {
        return helpers.values();
    }

    /**
     * {@code arg<Head><Segment>...}, disambiguated by a numeric suffix on the rare collision
     * (two bindings walking the same names to different leaf types).
     */
    private String uniqueName(Key key) {
        var stem = new StringBuilder("arg").append(capitalize(key.headName()));
        key.tail().forEach(segment -> stem.append(capitalize(segment)));
        String base = stem.toString();
        String candidate = base;
        int suffix = 2;
        while (helperNames.containsValue(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private static MethodSpec buildHelper(Key key, String name) {
        var body = CodeBlock.builder();
        String current = "root";
        for (int depth = 0; depth < key.tail().size(); depth++) {
            String level = "level" + (depth + 1);
            body.beginControlFlow("if (!($L instanceof $T<?, ?> $L))", current, Map.class, level)
                .addStatement("return null")
                .endControlFlow();
            String value = "value" + (depth + 1);
            body.addStatement("$T $L = $L.get($S)", Object.class, value, level, key.tail().get(depth));
            current = value;
        }
        body.addStatement("return ($T) $L", key.leafType(), current);
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(key.leafType())
            .addParameter(ClassName.get(Object.class), "root")
            .addCode(body.build())
            .build();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
