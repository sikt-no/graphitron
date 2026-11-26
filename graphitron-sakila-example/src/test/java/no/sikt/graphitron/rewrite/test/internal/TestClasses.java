package no.sikt.graphitron.rewrite.test.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The module's compiled test classes, for an enforcement test that checks a rule by reflection
 * ({@link TierAnnotationEnforcementTest}, {@link QuarkusTestLockEnforcementTest},
 * {@link FixtureCloseEnforcementTest}). Each caller filters to the classes its rule covers.
 */
final class TestClasses {

    private static final Path TEST_CLASSES = Path.of("target/test-classes");

    private TestClasses() {}

    /**
     * Every class under {@code target/test-classes}, nested, local and anonymous ones included,
     * loaded but not initialized: a caller that reads a static field's value initializes that one
     * class itself. A class that does not load is left out.
     *
     * @throws IllegalStateException if the walk loads no class at all, so a rule checked over the
     *     result cannot pass vacuously when the tests run from somewhere else
     */
    static List<Class<?>> loaded() {
        var loader = TestClasses.class.getClassLoader();
        var classes = new ArrayList<Class<?>>();
        try (var walk = Files.walk(TEST_CLASSES)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                .forEach(classFile -> {
                    try {
                        classes.add(Class.forName(toClassName(classFile), false, loader));
                    } catch (ClassNotFoundException | LinkageError ignored) {
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (classes.isEmpty()) {
            throw new IllegalStateException("no class loaded from " + TEST_CLASSES.toAbsolutePath()
                + "; the enforcement tests run from the module directory after test-compile");
        }
        return List.copyOf(classes);
    }

    private static String toClassName(Path classFile) {
        return TEST_CLASSES.relativize(classFile).toString()
            .replace('/', '.').replace('\\', '.')
            .replaceAll("\\.class$", "");
    }
}
