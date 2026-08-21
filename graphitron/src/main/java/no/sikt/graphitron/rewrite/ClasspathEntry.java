package no.sikt.graphitron.rewrite;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One compile-classpath entry, carrying the decision the producer made about it. The plugin
 * classifies the classpath once ({@code AbstractRewriteMojo.resolveCompileClasspath()} in the
 * Maven plugin) and every consumer projects what it needs from the same list: the codegen loader,
 * javac and the execution loader project every entry's {@link #path()}, while the class census
 * ({@link no.sikt.graphitron.rewrite.catalog.ClasspathScanner}) skips {@link Origin#TRANSITIVE}
 * entries. Keeping the decision on the element, rather than producing two sibling path lists,
 * makes census &sube; loader a derivation over one list instead of a promise about two.
 *
 * @param path       the entry on disk: a {@code target/classes} directory or a resolved jar
 * @param origin     how this entry reached the classpath; decides whether the census reads it and
 *                   what the nameability rule says about classes it carries
 * @param coordinate {@code groupId:artifactId} where one exists, the module name for a
 *                   convention-scanned reactor sibling, {@code null} for {@link Origin#PROJECT}.
 *                   Carried for the rejection messages: "declare a dependency on module X" and
 *                   "the class is in org.foo:bar, which this module does not declare" both need a
 *                   name no {@code target/classes} path can supply
 */
public record ClasspathEntry(Path path, Origin origin, String coordinate) {

    /** How an entry reached the compile classpath. */
    public enum Origin {
        /** This module's own build output. */
        PROJECT,
        /** On the compile classpath with its coordinate declared in the module's own pom. */
        DECLARED,
        /**
         * A reactor module's output that this module does not declare a dependency on. Kept in
         * the census (an author can name it and then declare the dependency, which is the dev
         * loop working) but rejected by the build, naming the module to declare.
         */
        SIBLING,
        /**
         * On the compile classpath only because a declared dependency dragged it in. Not read by
         * the census and not nameable in a schema; naming one is the undeclared-dependency
         * antipattern, and the fix is declaring it.
         */
        TRANSITIVE
    }

    public ClasspathEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(origin, "origin");
    }

    /** Wraps a bare path as this module's own output, for callers with no classification to give. */
    public static ClasspathEntry project(Path path) {
        return new ClasspathEntry(path, Origin.PROJECT, null);
    }

    /** {@link #project(Path)} over a list, so path-based call sites stay one argument long. */
    public static List<ClasspathEntry> projectRoots(List<Path> paths) {
        return paths.stream().map(ClasspathEntry::project).toList();
    }
}
