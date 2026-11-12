package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.rewrite.test.services.CityService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

/**
 * The classpaths a fact document can be read over instead of the {@linkplain ClasspathCorpus corpus},
 * each by the name {@code @withConfiguration(classpath:)} gives it. A document naming none is read
 * over the corpus, which is what nearly every document wants.
 *
 * <p>A fixture is real compiled Java, as the corpus is. Maven compiles it, in
 * {@code graphitron-sakila-service}, and a shape a fixture lacks is a class to write there; nothing
 * here runs a compiler. What this class does is arrange: which roots a fixture is, in what order,
 * under what {@link ClasspathEntry.Origin}, and where a root is a copy of a class the corpus
 * already declares, the copy. The service module carries each compiled library as one jar resource
 * under {@code classpath-fixtures/}, beside its own classes rather than among them, so a reading of
 * the corpus never meets a fixture's classfiles; a fixture reads that resource off the classloader,
 * which works whether the service module is a directory or a jar, and unpacks it into a root of its
 * own.
 *
 * <p>Roots are made once per JVM, under the module's {@code target}, in a directory of the JVM's own
 * so two forks never write one root.
 */
public final class ClasspathFixtures {

    private ClasspathFixtures() {}

    /**
     * Every fixture by name, with what it is for. Each is made the first time it is asked for.
     */
    private static final Map<String, Supplier<List<ClasspathEntry>>> FIXTURES = fixtures();

    private static Map<String, Supplier<List<ClasspathEntry>>> fixtures() {
        var fixtures = new LinkedHashMap<String, Supplier<List<ClasspathEntry>>>();
        // FilmService alone, copied out of the corpus into a root of its own and named as a reactor
        // sibling: one class declared by two entries, which a graph of the corpus and a graph of
        // this one meet when they share a store, and which nothing but the store's membership
        // relation keeps apart.
        fixtures.put("service-copy", () -> List.of(new ClasspathEntry(
            copyOf("service-copy", "no.sikt.graphitron.rewrite.test.services.FilmService"),
            ClasspathEntry.Origin.REACTOR, "no.sikt:graphitron-sakila-service-copy", null)));
        // The library compiled with -parameters, as the consumer's own code: a FilmBlurbHolder under
        // the corpus's name holding a lib.Blurb, and lib.HolderService producing it and taking a
        // lib.Assignment by its parameter's name.
        fixtures.put("library-named", () -> List.of(library("library-named")));
        // The same library compiled without -parameters, which is its one difference from
        // library-named: the parameter compiled without its name.
        fixtures.put("library-nameless", () -> List.of(library("library-nameless")));
        // The corpus and library-named, in that order: one class name on two entries, each
        // backing the types it reaches.
        fixtures.put("corpus-and-library-named", () -> {
            var entries = new ArrayList<>(ClasspathCorpus.entries());
            entries.add(library("library-named"));
            return List.copyOf(entries);
        });
        return Map.copyOf(fixtures);
    }

    private static final Map<String, List<ClasspathEntry>> MADE = new ConcurrentHashMap<>();

    private static final Map<String, Path> ROOTS = new ConcurrentHashMap<>();

    private static volatile Path home;

    /** Every fixture's name. */
    public static Set<String> names() {
        return new java.util.TreeSet<>(FIXTURES.keySet());
    }

    /**
     * The entries the fixture {@code name} is, made on first use; an unknown name is refused,
     * naming the fixtures there are.
     */
    public static List<ClasspathEntry> entries(String name) {
        var fixture = FIXTURES.get(name);
        if (fixture == null) {
            throw new IllegalArgumentException("@withConfiguration(classpath:) names '" + name
                + "', which is no classpath fixture; the fixtures are " + names()
                + ", and a document naming none is read over the classpath corpus");
        }
        return MADE.computeIfAbsent(name, ignored -> fixture.get());
    }

    /** A library the service module carries as a jar resource, unpacked into a root of its own. */
    private static ClasspathEntry library(String name) {
        var root = ROOTS.computeIfAbsent(name, ignored -> {
            var resource = "/classpath-fixtures/" + name + ".jar";
            Path dir = directory(name);
            try (InputStream bytes = CityService.class.getResourceAsStream(resource)) {
                if (bytes == null) {
                    throw new IllegalStateException("the service corpus carries no " + resource
                        + "; graphitron-sakila-service's build packages it");
                }
                try (var zip = new ZipInputStream(bytes)) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                            continue;
                        }
                        Path target = dir.resolve(entry.getName()).normalize();
                        if (!target.startsWith(dir)) {
                            throw new IllegalStateException(resource + " names " + entry.getName()
                                + ", outside its root");
                        }
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return dir;
        });
        return new ClasspathEntry(root, ClasspathEntry.Origin.REACTOR, "no.sikt:graphitron-test-" + name,
            null);
    }

    /**
     * {@code className}, read off the classloader rather than from a path under the corpus root,
     * copied into a root of its own.
     */
    private static Path copyOf(String name, String className) {
        return ROOTS.computeIfAbsent(name, ignored -> {
            Path dir = directory(name);
            String resource = "/" + className.replace('.', '/') + ".class";
            Path target = dir.resolve(resource.substring(1));
            try (InputStream bytes = CityService.class.getResourceAsStream(resource)) {
                if (bytes == null) {
                    throw new IllegalStateException("the service corpus declares no " + resource);
                }
                Files.createDirectories(target.getParent());
                Files.copy(bytes, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return dir;
        });
    }

    private static Path directory(String name) {
        try {
            return Files.createDirectories(home().resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path home() {
        var at = home;
        if (at == null) {
            synchronized (ClasspathFixtures.class) {
                at = home;
                if (at == null) {
                    try {
                        var parent = Files.createDirectories(Path.of("target").toAbsolutePath());
                        at = Files.createTempDirectory(parent, "classpath-fixtures-");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    home = at;
                }
            }
        }
        return at;
    }
}
