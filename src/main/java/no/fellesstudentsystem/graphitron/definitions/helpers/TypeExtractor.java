package no.fellesstudentsystem.graphitron.definitions.helpers;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

public class TypeExtractor {
    /**
     * @return If the type is a parameterized type such as a List or Set, return the class of the parameter, otherwise return the class of the argument.
     */
    public static Class<?> extractType(Type type) {

        if (type == null) {
            return null;
        }

        if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            Type[] actualTypeArguments = ((ParameterizedType) type).getActualTypeArguments();

            if (actualTypeArguments.length > 0) {
                Type actualTypeArgument = actualTypeArguments[0];

                if (actualTypeArgument instanceof Class<?>) {
                    return (Class<?>) actualTypeArgument;
                } else if (actualTypeArgument instanceof ParameterizedType) {
                    return (Class<?>) ((ParameterizedType) actualTypeArgument).getRawType();
                } else if (actualTypeArgument instanceof WildcardType) {
                    WildcardType wildcardType = (WildcardType) actualTypeArgument;
                    Type[] upperBounds = wildcardType.getUpperBounds();
                    if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?>) {
                        return (Class<?>) upperBounds[0];
                    }
                }
            }
        }
        throw new IllegalArgumentException("Cannot extract class from type: " + type);
    }
}
