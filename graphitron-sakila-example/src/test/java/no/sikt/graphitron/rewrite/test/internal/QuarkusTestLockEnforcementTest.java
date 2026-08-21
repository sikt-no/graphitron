package no.sikt.graphitron.rewrite.test.internal;

import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.sakila.example.app.QuarkusTestLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.ResourceLocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @QuarkusTest} class in this module must lock {@link QuarkusTestLock#KEY}. Without
 * it the next Quarkus class somebody adds re-breaks the suite, and the failure it produces names a
 * method on the wrong class: a cause three files from the edit that caused it. That class explains
 * why the key exists; this test is what keeps it carried.
 *
 * <p>Reflective rather than a source scan, so an inherited or meta-annotated {@code @QuarkusTest}
 * is seen the same way JUnit sees it. Shares its walk shape with
 * {@link TierAnnotationEnforcementTest}.
 */
@UnitTier
class QuarkusTestLockEnforcementTest {

    @Test
    void everyQuarkusTestClassLocksTheDeploymentKey() throws IOException {
        Path testClasses = Path.of("target/test-classes");
        List<String> quarkusTests = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        try (var walk = Files.walk(testClasses)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().contains("$"))
                .forEach(classFile -> {
                    String className = toClassName(testClasses, classFile);
                    try {
                        Class<?> clazz = Class.forName(className);
                        if (!clazz.isAnnotationPresent(QuarkusTest.class)) {
                            return;
                        }
                        quarkusTests.add(clazz.getName());
                        if (!locksDeploymentKey(clazz)) {
                            violations.add(clazz.getName());
                        }
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    }
                });
        }

        assertThat(quarkusTests)
            .as("the walk found no @QuarkusTest class at all, so this test would pass vacuously")
            .isNotEmpty();
        assertThat(violations)
            .as("@QuarkusTest classes missing @ResourceLock(QuarkusTestLock.KEY), which makes them "
                + "mutually exclusive: without it two of them run at once and QuarkusTestExtension's "
                + "static bookkeeping slots cross. See QuarkusTestLock for why.\n"
                + String.join("\n", violations))
            .isEmpty();
    }

    private static boolean locksDeploymentKey(Class<?> clazz) {
        return Stream.concat(
                Arrays.stream(clazz.getAnnotationsByType(ResourceLock.class)),
                Arrays.stream(clazz.getAnnotationsByType(ResourceLocks.class))
                    .flatMap(locks -> Arrays.stream(locks.value())))
            .anyMatch(lock -> QuarkusTestLock.KEY.equals(lock.value()));
    }

    private static String toClassName(Path base, Path classFile) {
        return base.relativize(classFile).toString()
            .replace('/', '.').replace('\\', '.')
            .replaceAll("\\.class$", "");
    }
}
