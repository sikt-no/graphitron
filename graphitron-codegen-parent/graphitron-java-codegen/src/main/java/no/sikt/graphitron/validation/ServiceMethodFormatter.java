package no.sikt.graphitron.validation;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formats service method signatures and types for use in validation error messages.
 */
class ServiceMethodFormatter {

    /**
     * Formats the expected parameter type, e.g. "CustomerRecord" or "List&lt;CustomerRecord&gt;".
     */
    static String formatExpectedListableType(Class<?> inputClass, boolean isListed) {
        return isListed ? "List<" + inputClass.getSimpleName() + ">" : inputClass.getSimpleName();
    }

    /**
     * Formats a list of method overloads as an indented, newline-separated string for error messages.
     */
    static String formatOverloads(List<Method> overloads, String methodName) {
        if (overloads.isEmpty()) {
            return "  (no overloads found)";
        }
        return overloads.stream()
                .map(m -> "  " + formatMethodSignature(m, methodName))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Formats a single method signature, e.g. "methodName(String, List&lt;CustomerRecord&gt;)".
     */
    static String formatMethodSignature(Method method, String methodName) {
        var params = Arrays.stream(method.getGenericParameterTypes())
                .map(ServiceMethodFormatter::formatTypeName)
                .collect(Collectors.joining(", "));
        return methodName + "(" + params + ")";
    }

    /**
     * Formats a reflected type as a readable simple name, resolving generic type arguments recursively.
     */
    static String formatTypeName(Type type) {
        if (type instanceof ParameterizedType pt) {
            var raw = ((Class<?>) pt.getRawType()).getSimpleName();
            var args = Arrays.stream(pt.getActualTypeArguments())
                    .map(ServiceMethodFormatter::formatTypeName)
                    .collect(Collectors.joining(", "));
            return raw + "<" + args + ">";
        }
        if (type instanceof Class<?> cls) {
            return cls.getSimpleName();
        }
        return type.getTypeName();
    }
}
