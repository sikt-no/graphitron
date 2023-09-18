package no.fellesstudentsystem.graphql.helpers.arguments;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A helper class for generated code. Helps with flattening the arguments found in a GraphQL {@link graphql.schema.DataFetchingEnvironment}
 * for the checks used in mutation code.
 */
public class Arguments {
    public static Set<String> flattenArgumentKeys(Map<String, Object> arguments) {
        return flattenArgumentKeys(arguments, "");
    }

    private static Set<String> flattenArgumentKeys(Map<String, Object> arguments, String path) {
        var result = new HashSet<String>();
        for (var arg : arguments.entrySet()) {
            var key = arg.getKey();
            var value = arg.getValue();
            var nextPath = path.isEmpty() ? key : path + "/" + key;
            result.add(nextPath);

            if (value instanceof Map) {
                result.addAll(flattenArgumentKeys((Map<String, Object>) value, nextPath));
            }

            if (value instanceof List) {
                var listValue = (List<?>) value;
                var first = listValue.stream().findFirst();
                if (first.isPresent() && first.get() instanceof Map) {
                    result.addAll(flattenArgumentKeys((Map<String, Object>) first.get(), nextPath));
                }
            }
        }

        return result;
    }
}
