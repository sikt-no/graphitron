package no.sikt.graphitron.model.classpath;

import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Reads the Java type a scalar constant accepts: the {@code I} of the {@link Coercing} the
 * constant's {@link GraphQLScalarType} holds, which is what graphql-java's argument-coercion path
 * hands a resolver for a value of that scalar.
 *
 * <p>Reflective rather than parsed, and that is forced by where the answer is written. The
 * constant's declared type is {@code GraphQLScalarType} whatever it coerces to, so a classfile
 * parse sees the same descriptor for every scalar on the classpath; which {@code Coercing}
 * implementation the constant holds is decided by the class initialiser, so the value has to be
 * read from a loaded class. This is why {@link ClasspathScanner}, which stays parse-only, records
 * that a constant exists and leaves what it coerces to here.
 */
public final class ScalarConstantInput {

    private ScalarConstantInput() {}

    /**
     * The fully-qualified Java type a value of the named constant's scalar arrives as, or
     * {@code null} when the constant does not resolve to one. Null covers every way the read can
     * come up empty, deliberately as one answer: the class does not load, the field is not a
     * public static {@code GraphQLScalarType}, the class initialiser throws, or the coercing's
     * {@code I} parameter is erased. A caller that has to tell those apart asks the resolver that
     * rejects, not this.
     */
    public static String of(String className, String fieldName, ClassLoader loader) {
        try {
            Class<?> owner = Class.forName(className, true,
                loader == null ? Thread.currentThread().getContextClassLoader() : loader);
            Field field = owner.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers())) return null;
            if (!GraphQLScalarType.class.isAssignableFrom(field.getType())) return null;
            Object value = field.get(null);
            if (!(value instanceof GraphQLScalarType scalar)) return null;
            Class<?> input = inputOf(scalar.getCoercing().getClass());
            return input == null || input == Object.class ? null : box(input).getName();
        } catch (Throwable t) {
            // A consumer's class initialiser is arbitrary code and a census pass is not the place
            // for it to end a build. The build's own resolver runs later against the constants the
            // schema names, and it is what reports a constant that cannot be read.
            return null;
        }
    }

    /**
     * Walks the interface and supertype edges of a coercing implementation for the first concrete
     * {@code Coercing<I, ?>} binding, so a subclass of an abstract typed coercing resolves through
     * its parent's declaration.
     */
    private static Class<?> inputOf(Class<?> start) {
        for (Class<?> cls = start; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Type iface : cls.getGenericInterfaces()) {
                Class<?> found = inputArgument(iface);
                if (found != null) return found;
            }
            Class<?> fromSuper = inputArgument(cls.getGenericSuperclass());
            if (fromSuper != null) return fromSuper;
        }
        return null;
    }

    private static Class<?> inputArgument(Type t) {
        if (t instanceof ParameterizedType pt && pt.getRawType() == Coercing.class) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 2 && args[0] instanceof Class<?> input) return input;
        }
        return null;
    }

    /** The wrapper for a primitive, which is what a coerced value is handed as. */
    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }
}
