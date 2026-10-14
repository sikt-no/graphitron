package no.sikt.graphitron.model.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One compile-classpath entry, carrying the decision the producer made about it. The plugin
 * classifies the classpath once ({@code AbstractRewriteMojo.resolveCompileClasspath()} in the
 * Maven plugin) and every consumer projects what it needs from the same list: the codegen loader,
 * javac and the execution loader project every entry's {@link #path()}, while the classpath
 * capture ({@link no.sikt.graphitron.model.capture.code.ClasspathSourceCapture}) skips
 * {@link Origin#TRANSITIVE} entries. Keeping the decision on the element, rather than producing two sibling path lists,
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
 * @param suppliedStamp the entry's content identity where the producer already knows it, spelled
 *                   by {@link no.sikt.graphitron.model.read.SourceStamp}; {@code null} where it
 *                   does not, which is every caller that resolved nothing and every entry whose
 *                   bytes could still move. Same doctrine as {@code coordinate}: the producer
 *                   resolved this classpath and knows things about it that no consumer can
 *                   recover from a path, so it says them once here instead of every consumer
 *                   re-deriving them. What a consumer saves is the read, a jar being verified by
 *                   content hash on every round of a session; what it must not do is treat an
 *                   absent stamp as a claim, since none is the answer for "ask the bytes"
 * @param kind       what the entry is as the producer resolved it: a directory the build writes,
 *                   or a jar that is a release or a snapshot
 * @param module     the workspace module this entry is a form of, its output or a copy of it;
 *                   {@code null} for an entry no module of the workspace builds
 */
public record ClasspathEntry(Path path, Origin origin, String coordinate, String suppliedStamp,
                             Kind kind, WorkspaceModule module) {

    /** What an entry is, which decides whether its bytes can change under its name. */
    public enum Kind {
        /** A directory the build writes, whose contents change whenever it compiles. */
        DIRECTORY,
        /** A jar released under its coordinate and version, which cannot change under them. */
        RELEASE,
        /** A jar that can be replaced under its coordinate and version. */
        SNAPSHOT;

        /**
         * The kind where the producer resolved nothing: a directory is one, and a jar that
         * nobody vouched for may still change.
         */
        public static Kind unresolved(Path path) {
            return Files.isDirectory(path) ? DIRECTORY : SNAPSHOT;
        }
    }

    /** How an entry reached the compile classpath. */
    public enum Origin {
        /** This module's own build output. */
        PROJECT,
        /**
         * Somebody else's artifact, with its coordinate declared in the module's own pom. A
         * library: nameable, because declaring it is what makes naming it legitimate, but not
         * the consumer's own code, which is the distinction {@code REACTOR} below carries.
         */
        DECLARED,
        /**
         * A reactor module's output that this module declares a dependency on, which is the
         * ordinary way one module of a build uses another. Nameable exactly as {@code DECLARED}
         * is, and the consumer's own code exactly as {@code PROJECT} is, that second half being
         * why it is not spelled {@code DECLARED}: a reader collecting what an author plausibly
         * wrote cannot otherwise tell this module apart from a third-party jar.
         */
        REACTOR,
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
        Objects.requireNonNull(kind, "kind");
    }

    /** An entry no module of the workspace builds. */
    public ClasspathEntry(Path path, Origin origin, String coordinate, String suppliedStamp,
                          Kind kind) {
        this(path, origin, coordinate, suppliedStamp, kind, null);
    }

    /** An entry whose producer did not resolve what kind it is; see {@link Kind#unresolved}. */
    public ClasspathEntry(Path path, Origin origin, String coordinate, String suppliedStamp) {
        this(path, origin, coordinate, suppliedStamp, Kind.unresolved(path));
    }

    /**
     * An entry whose producer has no content identity to supply, which is every caller that did
     * not resolve the classpath from a repository. Keeps the three-argument shape the record had
     * before the stamp existed, so a producer opts into supplying one rather than opting out.
     */
    public ClasspathEntry(Path path, Origin origin, String coordinate) {
        this(path, origin, coordinate, null);
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
