package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import java.util.stream.Collectors;

/**
 * Renders a javapoet {@link TypeName} using simple class names, recursively. For human-readable
 * rejection messages where fully qualified names (e.g.
 * {@code java.util.Map<org.jooq.Row3<java.lang.String, ...>>}) bury the actual mismatch.
 */
public final class TypeNames {

    private TypeNames() {}

    public static String simple(TypeName type) {
        if (type instanceof ParameterizedTypeName pt) {
            var args = pt.typeArguments().stream()
                    .map(TypeNames::simple)
                    .collect(Collectors.joining(", "));
            return pt.rawType().simpleName() + "<" + args + ">";
        }
        if (type instanceof ClassName cn) {
            return cn.simpleName();
        }
        if (type instanceof ArrayTypeName at) {
            return simple(at.componentType()) + "[]";
        }
        return type.toString();
    }
}
