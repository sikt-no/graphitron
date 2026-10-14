package no.sikt.graphitron.rewrite.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What {@code graphitron:dev} runs before its loop starts: the module's own build through
 * {@code process-classes}, with the module's {@code graphitron:generate} replaced by the dev loop's
 * first round, so the module generates once and its compile compiles what the round wrote. The way
 * {@code quarkus:dev} compiles its module first, with the plan computed by Maven rather than read
 * off the pom.
 *
 * <p>A pure function over the plan Maven computed, so the substitution and the skip are stated and
 * tested apart from the executions themselves.
 */
final class ModulePlan {

    private ModulePlan() {}

    /** One bound goal of the module's build: which plugin, which goal, in which phase. */
    record Bound<T>(String plugin, String goal, String phase, T execution) {}

    /** One step before the loop starts. */
    sealed interface Step<T> {
        /** A goal of the module's build, run as the build would run it. */
        record Run<T>(Bound<T> bound) implements Step<T> {}

        /** The dev loop's first round, in the place generation has in the module's build. */
        record DevRound<T>() implements Step<T> {}
    }

    static final String GRAPHITRON_PLUGIN = "no.sikt:graphitron-maven-plugin";

    /** The default lifecycle from {@code compile} on: a session that ran one has compiled. */
    private static final Set<String> COMPILED = Set.of("compile", "process-classes",
        "generate-test-sources", "process-test-sources", "generate-test-resources",
        "process-test-resources", "test-compile", "process-test-classes", "test",
        "prepare-package", "package", "pre-integration-test", "integration-test",
        "post-integration-test", "verify", "install", "deploy");

    /**
     * The steps for {@code plan}, Maven's plan for the module through {@code process-classes}.
     *
     * <p>The dev round takes the place of the module's {@code graphitron:generate}, or where none is
     * bound runs before the first goal of the {@code compile} phase, or last where nothing compiles.
     * Graphitron's other goals are left out, the loop being what runs them. Where the session already
     * ran a phase from {@code compile} on before the dev goal, the module is compiled and the round is
     * the only step, which is also what {@code compileModule} turned off asks for.
     *
     * @param sessionGoals the goals the session was invoked with, in order
     */
    static <T> List<Step<T>> of(List<Bound<T>> plan, List<String> sessionGoals,
                                boolean compileModule) {
        if (!compileModule || compiledBefore(sessionGoals)) {
            return List.of(new Step.DevRound<>());
        }
        var steps = new ArrayList<Step<T>>();
        boolean placed = false;
        for (Bound<T> bound : plan) {
            if (GRAPHITRON_PLUGIN.equals(bound.plugin())) {
                if (!placed && "generate".equals(bound.goal())) {
                    steps.add(new Step.DevRound<>());
                    placed = true;
                }
                continue;
            }
            if (!placed && "compile".equals(bound.phase())) {
                steps.add(new Step.DevRound<>());
                placed = true;
            }
            steps.add(new Step.Run<>(bound));
        }
        if (!placed) {
            steps.add(new Step.DevRound<>());
        }
        return List.copyOf(steps);
    }

    /** Whether a goal before the dev goal is a lifecycle phase from {@code compile} on. */
    private static boolean compiledBefore(List<String> sessionGoals) {
        for (String goal : sessionGoals) {
            if (goal.endsWith(":dev")) {
                return false;
            }
            if (COMPILED.contains(goal)) {
                return true;
            }
        }
        return false;
    }
}
