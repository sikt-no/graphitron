package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import java.util.List;

/**
 * How a client's value is read off the fetch into a local, from plain values: the local's type, its
 * name and the path of argument and input field names the value sits at. Shared by every emitter of a
 * call, a service's fill, a routine write's and a routine read's argument alike; which read a binding
 * takes is the store's to state, and each caller's own query reads that, so nothing here reads the
 * store.
 */
final class ArgumentReads {

    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName STRING = ClassName.get("java.lang", "String");

    private ArgumentReads() {}

    /**
     * The value as it arrives: the argument itself target-typed, an input field below it through each
     * input object on the way, {@code null} wherever one is absent.
     */
    static CodeBlock value(TypeName type, String local, List<String> path) {
        if (path.size() == 1) {
            return CodeBlock.of("$T $L = env.getArgument($S)", type, local, path.getFirst());
        }
        var read = CodeBlock.builder().add("env.getArgument($S) instanceof $T<?, ?> map1", path.getFirst(), MAP);
        for (int i = 1; i < path.size() - 1; i++) {
            read.add(" && map$L.get($S) instanceof $T<?, ?> map$L", i, path.get(i), MAP, i + 1);
        }
        int last = path.size() - 1;
        // Boxed, since an absent level reads null; a primitive parameter unboxes it at the call.
        var boxed = type.box();
        var value = CodeBlock.of("$L ? ($T) map$L.get($S) : null", read.build(), boxed, last, path.get(last));
        return declare(boxed, local, value);
    }

    /**
     * An enum argument into a Java enum. graphql-java hands an enum value over as its runtime value,
     * the string the schema gives it, so the string is read off the fetch and turned into the
     * parameter's constant of the same name, or into a list of them.
     */
    static List<CodeBlock> enumConstant(TypeName type, String local, List<String> path) {
        var wire = local + "Wire";
        if (type instanceof ParameterizedTypeName list && LIST.equals(list.rawType())) {
            var element = list.typeArguments().getFirst();
            return List.of(value(ParameterizedTypeName.get(LIST, STRING), wire, path),
                CodeBlock.of("$T $L = $L == null ? null : $L.stream().map($T::valueOf).toList()",
                    type, local, wire, wire, element));
        }
        return List.of(value(STRING, wire, path),
            CodeBlock.of("$T $L = $L == null ? null : $T.valueOf($L)", type, local, wire, type, wire));
    }

    /**
     * A list copied into an array of the parameter's component type, in the list's order: graphql-java
     * hands a list over as a {@code java.util.List}, which a routine's array parameter does not take.
     */
    static List<CodeBlock> array(ArrayTypeName type, String local, List<String> path) {
        var elements = ParameterizedTypeName.get(LIST, type.componentType());
        return List.of(value(elements, local + "Elements", path),
            CodeBlock.of("$T $L = $LElements == null ? null : $LElements.toArray(new $T[0])",
                type, local, local, local, type.componentType()));
    }

    /**
     * A node id read as it arrives and handed to {@code helper}, which decodes it into what the
     * parameter takes, a malformed id a client error.
     */
    static List<CodeBlock> decoded(TypeName type, String local, List<String> path, String helper) {
        return List.of(value(ClassName.get(Object.class), local + "Wire", path),
            CodeBlock.of("$T $L = $L($LWire)", type, local, helper, local));
    }

    /**
     * A local of the parameter's type. A cast from {@code Object} to a generic type cannot be
     * checked, which the build treats as an error, so such a local suppresses that one warning at the
     * narrowest scope it can, the declaration itself.
     */
    static CodeBlock declare(TypeName type, String local, CodeBlock value) {
        return type instanceof ParameterizedTypeName
            ? CodeBlock.of("@$T($S) $T $L = $L", SuppressWarnings.class, "unchecked", type, local, value)
            : CodeBlock.of("$T $L = $L", type, local, value);
    }
}
