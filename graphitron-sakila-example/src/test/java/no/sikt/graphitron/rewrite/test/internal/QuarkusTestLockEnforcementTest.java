package no.sikt.graphitron.rewrite.test.internal;

import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.sakila.example.app.QuarkusTestLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.ResourceLocks;

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
 * is seen the same way JUnit sees it. The classes come from {@link TestClasses}.
 */
@UnitTier
class QuarkusTestLockEnforcementTest {

    @Test
    void everyQuarkusTestClassLocksTheDeploymentKey() {
        List<String> quarkusTests = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        // Top-level classes only: a nested class is not a test class of its own here (Surefire's
        // default excludes skip it), and a @Nested one runs under its enclosing class's lock.
        TestClasses.loaded().stream()
            .filter(clazz -> clazz.getEnclosingClass() == null)
            .filter(clazz -> clazz.isAnnotationPresent(QuarkusTest.class))
            .forEach(clazz -> {
                quarkusTests.add(clazz.getName());
                if (!locksDeploymentKey(clazz)) {
                    violations.add(clazz.getName());
                }
            });

        assertThat(quarkusTests)
            .as("the walk found no @QuarkusTest class at all, so this test would pass vacuously")
            .isNotEmpty();
        assertThat(violations)
            .as("@QuarkusTest classes missing @ResourceLock(QuarkusTestLock.KEY), which makes them "
                + "mutually exclusive: without it two of them run at once and QuarkusTestExtension's "
                + "static bookkeeping slots cross. See QuarkusTestLock for why.\n\n"
                + "Before adding the key, check the class needs the container at all. Running GraphQL "
                + "operations against the generated schema does not: 46 of the 47 classes in "
                + "no.sikt.graphitron.rewrite.test.querydb build a DSLContext and a GraphQL directly, "
                + "and the lock they do not take is why they overlap each other freely. A class whose "
                + "subject is HTTP status, headers, routing, or CDI wiring belongs here and takes the "
                + "key; one whose assertions are all on response shape belongs in querydb.\n"
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
}
