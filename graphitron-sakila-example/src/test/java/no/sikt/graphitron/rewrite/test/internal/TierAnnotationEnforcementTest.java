package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.CompilationTier;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UnitTier
class TierAnnotationEnforcementTest {

    private static final String CROSS_CUTTING_TAG = "cross-cutting";

    @Test
    void allTestClassesCarryExactlyOneTierIdentity() {
        List<String> violations = new ArrayList<>();

        // Top-level classes only: a nested class is not a test class of its own here (Surefire's
        // default excludes skip it), and a @Nested one takes its tier tag from its enclosing class.
        TestClasses.loaded().stream()
            .filter(clazz -> clazz.getEnclosingClass() == null)
            .filter(TierAnnotationEnforcementTest::isTestClass)
            .forEach(clazz -> checkTierIdentity(clazz, violations));

        assertThat(violations)
            .as("Test classes with missing or duplicate tier identities:\n" + String.join("\n", violations))
            .isEmpty();
    }

    private static boolean isTestClass(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .anyMatch(m -> m.isAnnotationPresent(Test.class) || m.isAnnotationPresent(ParameterizedTest.class));
    }

    private static void checkTierIdentity(Class<?> clazz, List<String> violations) {
        int tierCount = 0;
        if (clazz.isAnnotationPresent(UnitTier.class)) tierCount++;
        if (clazz.isAnnotationPresent(PipelineTier.class)) tierCount++;
        if (clazz.isAnnotationPresent(CompilationTier.class)) tierCount++;
        if (clazz.isAnnotationPresent(ExecutionTier.class)) tierCount++;
        boolean crossCutting = Arrays.stream(clazz.getAnnotationsByType(Tag.class))
            .anyMatch(t -> CROSS_CUTTING_TAG.equals(t.value()));

        if (tierCount == 0 && !crossCutting) {
            violations.add(clazz.getName()
                + ": no tier annotation — add @UnitTier, @PipelineTier, @CompilationTier, @ExecutionTier, or @Tag(\"cross-cutting\")");
        } else if (tierCount > 1 || (tierCount >= 1 && crossCutting)) {
            violations.add(clazz.getName() + ": multiple tier identities");
        }
    }
}
