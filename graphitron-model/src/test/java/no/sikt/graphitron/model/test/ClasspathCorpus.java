package no.sikt.graphitron.model.test;

import graphql.scalars.ExtendedScalars;
import no.sikt.graphitron.rewrite.test.services.CityService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The classpath corpus: real compiled Java for the {@code code_} gatherer to read.
 *
 * <p>A neighbour of the SDL corpus and the same idea. A document there states a schema and the test
 * reads back what capture made of it; a class here states a signature and the test reads back what
 * the reading made of that. What both replace is a fixture writing the rows a reading would have
 * written, which can state a shape no reading produces and cannot fail when it does.
 *
 * <p>The corpus is {@code graphitron-sakila-service}, which carries conditions, services and
 * extensions already and is where a shape a test needs and cannot find should be added. It is a
 * test dependency of this module, so a class it does not carry is a class to write there rather
 * than a row to seed here.
 */
public final class ClasspathCorpus {

    private ClasspathCorpus() {}

    /**
     * Everywhere the corpus sits on disk.
     *
     * <p>Two halves, and the second is not an afterthought. The service module is the code a
     * consumer writes; the extended-scalars artifact is the code a consumer <em>names</em>, a
     * {@code @scalarType(scalar:)} reaching a library constant by its fully qualified name far more
     * often than one of their own. A corpus holding only the first could not state that case at all.
     */
    public static List<Path> roots() {
        return List.of(rootOf(CityService.class, "the service corpus"),
                       rootOf(ExtendedScalars.class, "the scalar-constant corpus"));
    }

    /** The service half alone, for a reading that has no business with scalar constants. */
    public static Path root() {
        return rootOf(CityService.class, "the service corpus");
    }

    /**
     * Where a corpus sits, asked of the corpus itself rather than spelled as a path.
     *
     * <p>A relative path from this module would be a second statement of the reactor's layout, and
     * wrong the moment a build resolves the corpus from a jar instead of a sibling's target. The
     * class knows where it was loaded from and that answer is right under both.
     */
    private static Path rootOf(Class<?> member, String what) {
        var source = member.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException(
                what + " has no code source, so nothing can read it as a corpus");
        }
        Path path;
        try {
            path = Path.of(source.getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(what + " is at no readable location", e);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException(what + " is not where it says it is: " + path);
        }
        return path;
    }
}
