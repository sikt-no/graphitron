package no.sikt.graphitron.model.capture.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a class on the classpath extends and implements, followed past the classpath's own edge.
 *
 * <p>A classfile names its superclass and its interfaces and stops there, so a census holds edges
 * and not chains. That is enough while a chain stays inside the census and useless the moment it
 * leaves, which for a throwable is immediately: every one of them reaches {@code java.lang.Throwable}
 * through {@code java.lang}, and the JDK is no classpath entry. So the edges come from the census
 * where the census has them and from a loaded class where it does not, and the two are read the
 * same way.
 *
 * <p>Loading is the fallback rather than the method. A census edge costs a map lookup and a load
 * costs a class, and the names that need loading are few and shared: a thousand library exceptions
 * reach {@code RuntimeException} through it, and it is loaded once.
 */
final class ClassAncestry {

    private static final String THROWABLE = "java.lang.Throwable";
    private static final String EXTENDS = "EXTENDS";

    private final Map<String, List<ClassfileCensus.SupertypeAt>> census;
    private final ClassLoader loader;

    /** Memo over every name asked about, loaded ones included; null means the name did not resolve. */
    private final Map<String, List<ClassfileCensus.SupertypeAt>> resolved = new HashMap<>();
    private final Map<String, Boolean> throwable = new HashMap<>();

    ClassAncestry(List<ClassfileCensus.ClassAt> classes, ClassLoader loader) {
        this.census = new HashMap<>();
        for (ClassfileCensus.ClassAt at : classes) {
            this.census.putIfAbsent(at.className(), at.supertypes());
        }
        this.loader = loader == null ? Thread.currentThread().getContextClassLoader() : loader;
    }

    /**
     * Whether {@code className} extends its way to {@code java.lang.Throwable}.
     *
     * <p>The extends chain alone, because that is the rule: throwing is single inheritance and no
     * interface makes a class throwable. A chain that runs out, at a class extending nothing or at a
     * name neither the census nor the loader answers for, is not a throwable. Both silences answer
     * the same way on purpose, admitting nothing being the only honest reading of a chain that
     * could not be followed.
     */
    boolean isThrowable(String className) {
        Boolean known = throwable.get(className);
        if (known != null) {
            return known;
        }
        boolean verdict = false;
        var walked = new LinkedHashSet<String>();
        String at = className;
        while (at != null && walked.add(at)) {
            if (THROWABLE.equals(at)) {
                verdict = true;
                break;
            }
            at = superclassOf(at);
        }
        // Every class the walk climbed through shares its verdict: they are all on one chain, so
        // either all of them reach Throwable above it or none of them do.
        for (String name : walked) {
            throwable.put(name, verdict);
        }
        return verdict;
    }

    /**
     * Whether {@code className} is {@code supertype}, itself counting.
     *
     * <p>The interface arm of {@link #isThrowable}, and interfaces are why it reads the whole
     * closure where that one reads a chain: a jOOQ table reaches {@code org.jooq.Table} through
     * implements clauses, often several levels up a superclass chain it shares with nothing else.
     * Best-effort on the same terms as the closure it reads, so a type the loader cannot resolve
     * answers no rather than guessing yes.
     */
    boolean isA(String className, String supertype) {
        return supertype.equals(className) || ancestorsOf(className).contains(supertype);
    }

    /**
     * Every type {@code className} is, itself excluded, in the order the walk met them.
     *
     * <p>Best-effort where {@link #isThrowable} is strict, and the asymmetry is deliberate: a name
     * that does not resolve costs this walk one branch and costs the admission its whole answer, so
     * a throwable whose interface happens to be missing from the classpath still gets the ancestry
     * that was readable rather than none.
     */
    List<String> ancestorsOf(String className) {
        var out = new LinkedHashSet<String>();
        collect(className, out, new LinkedHashSet<>());
        out.remove(className);
        return List.copyOf(out);
    }

    private void collect(String className, Set<String> out, Set<String> walking) {
        if (!walking.add(className)) {
            return;
        }
        List<ClassfileCensus.SupertypeAt> supertypes = supertypesOf(className);
        if (supertypes == null) {
            return;
        }
        for (ClassfileCensus.SupertypeAt supertype : supertypes) {
            if (out.add(supertype.name())) {
                collect(supertype.name(), out, walking);
            }
        }
    }

    private String superclassOf(String className) {
        List<ClassfileCensus.SupertypeAt> supertypes = supertypesOf(className);
        if (supertypes == null) {
            return null;
        }
        return supertypes.stream()
            .filter(supertype -> EXTENDS.equals(supertype.declaredVia()))
            .map(ClassfileCensus.SupertypeAt::name)
            .findFirst()
            .orElse(null);
    }

    /** The census's edges, or a loaded class's, or null where the name resolves to neither. */
    private List<ClassfileCensus.SupertypeAt> supertypesOf(String className) {
        List<ClassfileCensus.SupertypeAt> fromCensus = census.get(className);
        if (fromCensus != null) {
            return fromCensus;
        }
        if (resolved.containsKey(className)) {
            return resolved.get(className);
        }
        List<ClassfileCensus.SupertypeAt> read = reflect(className);
        resolved.put(className, read);
        return read;
    }

    /**
     * The edges a loaded class declares, spelled as the census spells them.
     *
     * <p>{@code java.lang.Object} is kept here where the census drops it, and the difference is the
     * two relations' subjects: the census states what a source declared, and a chain followed past
     * the classpath states what a class is.
     */
    private List<ClassfileCensus.SupertypeAt> reflect(String className) {
        Class<?> loaded;
        try {
            loaded = Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        var edges = new ArrayList<ClassfileCensus.SupertypeAt>();
        if (loaded.getSuperclass() != null) {
            edges.add(new ClassfileCensus.SupertypeAt(loaded.getSuperclass().getName(), EXTENDS));
        }
        for (Class<?> face : loaded.getInterfaces()) {
            edges.add(new ClassfileCensus.SupertypeAt(face.getName(), "IMPLEMENTS"));
        }
        return List.copyOf(edges);
    }
}
