package no.sikt.graphitron.model.test;

import no.sikt.graphitron.rewrite.test.services.CityService;

import java.nio.file.Files;
import java.nio.file.Path;

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
     * Where the corpus sits on disk, asked of the corpus itself rather than spelled as a path.
     *
     * <p>A relative path from this module would be a second statement of the reactor's layout, and
     * wrong the moment a build resolves the corpus from a jar instead of a sibling's target. The
     * class knows where it was loaded from and that answer is right under both.
     */
    public static Path root() {
        var source = CityService.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException(
                "the classpath corpus has no code source, so nothing can read it as a corpus");
        }
        Path path;
        try {
            path = Path.of(source.getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the classpath corpus is at no readable location", e);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException("the classpath corpus is not where it says it is: " + path);
        }
        return path;
    }
}
