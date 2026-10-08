package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.diagnostics.Rejection;

/**
 * The one decode of a consumer class read off the codegen classloader: {@link Loaded},
 * {@link NotLoaded}, or {@link Unlinkable}. The sites that load a consumer class to reflect on it,
 * or a type read off its signatures, go through here rather than catching
 * {@link ClassNotFoundException} beside a {@code Class.forName}, so a class that is found but
 * cannot be linked becomes a typed
 * {@link ReflectionError.ClassUnlinkable} instead of a raw {@link LinkageError} escaping the
 * build, or a {@code null} its caller reads as "no such type".
 *
 * <p>Linking is a real failure mode because the codegen classloader delegates parent-first to
 * graphitron's own plugin classloader: every {@code org.jooq} class graphitron's jOOQ carries
 * comes from there, and only the ones it lacks (a commercial-edition-only type) from the
 * consumer's jar. Such a type's package-private superclass then lives in another classloader,
 * and the JVM refuses the access.
 */
sealed interface ConsumerClassLoad {

    /** The class loaded, and where it was {@linkplain #reflect reflected}, linked its declared methods. */
    record Loaded(Class<?> cls) implements ConsumerClassLoad {}

    /** No class of that name is on the codegen classpath. */
    record NotLoaded(String className) implements ConsumerClassLoad {}

    /** The class is there but cannot be linked; carries the typed rejection. */
    record Unlinkable(ReflectionError.ClassUnlinkable rejection) implements ConsumerClassLoad {}

    /** The loaded class, or {@code null} on either failure arm. */
    default Class<?> loadedOrNull() {
        return this instanceof Loaded loaded ? loaded.cls() : null;
    }

    /** The rejection for a failure arm: {@link ReflectionError.ClassNotLoaded} or the unlinkable one. */
    default Rejection rejection() {
        return switch (this) {
            case Loaded ignored -> throw new IllegalStateException("a loaded class carries no rejection");
            case NotLoaded n -> new ReflectionError.ClassNotLoaded(n.className());
            case Unlinkable u -> u.rejection();
        };
    }

    /**
     * Loads {@code className} without initialising it. A supertype that cannot be linked fails
     * here already.
     */
    static ConsumerClassLoad load(String className, ClassLoader loader) {
        try {
            // nameability: exempt (the decode itself; each caller marks its own site with the rule it applies)
            return new Loaded(Class.forName(className, false, loader));
        } catch (ClassNotFoundException e) {
            return new NotLoaded(className);
        } catch (LinkageError e) {
            return unlinkable(className, e);
        }
    }

    /**
     * Loads {@code className} and materialises its declared-method table, for a class graphitron
     * is about to pick a method off. Materialising resolves the parameter and return types of
     * every declared method, not only the picked one, so this is where a sibling helper naming a
     * type the codegen classloader cannot host fails; once it has succeeded the types are
     * defined and later reads of the method table cannot fail on them.
     */
    static ConsumerClassLoad reflect(String className, ClassLoader loader) {
        var loaded = load(className, loader);
        if (loaded instanceof Loaded l) {
            try {
                l.cls().getDeclaredMethods();
            } catch (LinkageError e) {
                return unlinkable(className, e);
            }
        }
        return loaded;
    }

    /**
     * Loads a type named the way a signature spells it: a generic argument is stripped
     * ({@code List<Foo>} is {@code List}) and a nested class is retried with the JVM's {@code $},
     * one trailing dot at a time, so {@code Outer.Mid.Inner} resolves. {@link NotLoaded} carries
     * the name as given.
     */
    static ConsumerClassLoad loadSignatureType(String typeName, ClassLoader loader) {
        int lt = typeName.indexOf('<');
        String candidate = lt < 0 ? typeName : typeName.substring(0, lt);
        while (true) {
            var loaded = load(candidate, loader);
            if (!(loaded instanceof NotLoaded)) {
                return loaded;
            }
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot < 0) {
                return new NotLoaded(typeName);
            }
            candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1);
        }
    }

    private static Unlinkable unlinkable(String className, LinkageError e) {
        return new Unlinkable(new ReflectionError.ClassUnlinkable(className,
            ReflectionError.ClassUnlinkable.Cause.of(e)));
    }
}
