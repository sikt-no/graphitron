package no.sikt.graphitron.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.config.RunContext;

/**
 * The build-side statement of the nameability rule: a schema may name a class from this module,
 * from another module of the same reactor once a dependency on it is declared, or from a
 * dependency this module declares itself; nothing else. Built once over the classified
 * {@link ClasspathEntry} list and asked for a {@link Verdict} per author-written class name.
 *
 * <p>The predicate is a resource probe against the classified entries, not a census read and not
 * a question to the codegen loader. The census filters out nested classes, jOOQ-package classes
 * and non-public classes, so its absence does not mean un-nameable; and the codegen loader is
 * parent-first over the plugin's own realm, so where a loaded class <em>came from</em> answers the
 * wrong question: a properly declared {@code graphql-java} class loads from the plugin's realm.
 * Whether an entry <em>carries</em> the name is exactly the rule, and the probe asks it directly:
 * the class's resource name present in a kept entry, with the same jar / directory dispatch the
 * scanner uses and the same trailing-dot-to-{@code $} retry the codegen reflection sites apply to
 * nested names.
 *
 * <p>The verdicts, per {@link ClasspathEntry.Origin} arm plus the two cases no entry accounts for:
 *
 * <ul>
 *   <li>{@code PROJECT} / {@code DECLARED} carries the name: nameable.</li>
 *   <li>{@code SIBLING} carries it: rejected, naming the module to declare.</li>
 *   <li>No kept entry carries it but the platform loader resolves it: nameable. JDK classes are
 *       on every consumer's classpath by construction and are not a census question.</li>
 *   <li>No kept entry carries it: rejected. Only then are the {@code TRANSITIVE} entries probed,
 *       to name the coordinate that carries the class; the build is failing anyway, and this is
 *       the one place a transitive jar gets opened.</li>
 *   <li>An empty classified list: inert, every name nameable. A unit-tier
 *       {@link RunContext} carries no classpath roots, and the rule cannot be enforced
 *       against a classification nobody supplied.</li>
 * </ul>
 *
 * <p>Each probed jar's class-resource index is listed once, on first probe, and reused for every
 * later name; directories are probed by file existence and need no index.
 */
public final class ClasspathNameability {

    /** The answer for one author-written class name. */
    public sealed interface Verdict {
        /** The name may appear in a schema; resolution proceeds as before. */
        record Nameable() implements Verdict {}

        /**
         * The name may not appear in a schema. {@code reason} is the canonical author-facing
         * sentence, stated once here so every rejection channel says the same thing.
         */
        record Rejected(String reason) implements Verdict {}
    }

    private static final Verdict.Nameable NAMEABLE = new Verdict.Nameable();

    private static final ClasspathNameability INERT = new ClasspathNameability(List.of());

    /**
     * The inert check, for callers with no classification to enforce against: every name is
     * nameable, which is the empty-list arm stated as a value.
     */
    public static ClasspathNameability inert() {
        return INERT;
    }

    private final List<ClasspathEntry> entries;
    private final Map<Path, Set<String>> jarIndexByPath = new ConcurrentHashMap<>();

    public ClasspathNameability(List<ClasspathEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * The verdict for {@code className} as an author wrote it: a dotted fully-qualified name,
     * nested classes in either the source ({@code Outer.Inner}) or binary ({@code Outer$Inner})
     * spelling.
     */
    public Verdict verdictFor(String className) {
        if (entries.isEmpty() || className == null || className.isBlank()) {
            return NAMEABLE;
        }
        List<String> resources = resourceCandidates(className);
        for (ClasspathEntry entry : entries) {
            if (entry.origin() == ClasspathEntry.Origin.PROJECT
                || entry.origin() == ClasspathEntry.Origin.DECLARED) {
                if (carries(entry, resources)) {
                    return NAMEABLE;
                }
            }
        }
        for (ClasspathEntry entry : entries) {
            if (entry.origin() == ClasspathEntry.Origin.SIBLING && carries(entry, resources)) {
                String module = entry.coordinate() != null ? entry.coordinate() : entry.path().toString();
                return new Verdict.Rejected("class '" + className + "' is in reactor module "
                    + module + ", which this module does not declare a dependency on. Declare a "
                    + "dependency on " + module + " to name it.");
            }
        }
        if (platformResolves(className)) {
            return NAMEABLE;
        }
        for (ClasspathEntry entry : entries) {
            if (entry.origin() == ClasspathEntry.Origin.TRANSITIVE && carries(entry, resources)) {
                String coordinate = entry.coordinate() != null ? entry.coordinate() : entry.path().toString();
                return new Verdict.Rejected("class '" + className + "' is in " + coordinate
                    + ", which this module does not declare: it reaches the classpath only through "
                    + "another dependency's dependencies. Declare " + coordinate
                    + " as a dependency of this module to name it.");
            }
        }
        return new Verdict.Rejected("class '" + className + "' is not carried by this module's "
            + "own classes, its reactor siblings, or any dependency this module declares, which "
            + "is the whole set a schema may name from. Check the spelling, or declare the "
            + "dependency that carries it; an artifact supplied only on the generator plugin's "
            + "own classpath does not make a class nameable.");
    }

    private boolean carries(ClasspathEntry entry, List<String> resources) {
        Path path = entry.path();
        if (Files.isDirectory(path)) {
            for (String resource : resources) {
                if (Files.isRegularFile(path.resolve(resource))) {
                    return true;
                }
            }
            return false;
        }
        if (!no.sikt.graphitron.model.classpath.ClasspathScanner.isJar(path)) {
            return false;
        }
        Set<String> index = jarIndexByPath.computeIfAbsent(path, ClasspathNameability::listClassResources);
        for (String resource : resources) {
            if (index.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every {@code .class} entry name in the jar. An unreadable jar indexes empty, mirroring the
     * scanner: the resolver reports an unreadable dependency at the coordinate that names a class
     * in it.
     */
    private static Set<String> listClassResources(Path jar) {
        var names = new HashSet<String>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                var zipEntry = zipEntries.nextElement();
                if (!zipEntry.isDirectory() && zipEntry.getName().endsWith(".class")) {
                    names.add(zipEntry.getName());
                }
            }
        } catch (IOException ignored) {
            // See the method comment.
        }
        return names;
    }

    /**
     * The resource names {@code className} may live under, walking the trailing dots to {@code $}
     * one at a time so a nested class written in source spelling ({@code Outer.Inner}) probes the
     * binary resource ({@code Outer$Inner.class}) too, the same retry the codegen reflection
     * sites apply before {@link Class#forName(String, boolean, ClassLoader)}.
     */
    private static List<String> resourceCandidates(String className) {
        var candidates = new ArrayList<String>();
        String candidate = className;
        while (true) {
            candidates.add(candidate.replace('.', '/') + ".class");
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot < 0) {
                return candidates;
            }
            candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1);
        }
    }

    /** Whether the platform loader resolves {@code className}, with the same nested-name retry. */
    private static boolean platformResolves(String className) {
        String candidate = className;
        while (true) {
            try {
                // nameability: exempt (the rule's own platform-loader probe)
                Class.forName(candidate, false, ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot < 0) {
                    return false;
                }
                candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1);
            }
        }
    }
}
