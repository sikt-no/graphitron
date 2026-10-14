package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.maven.ModulePlan.Bound;
import no.sikt.graphitron.rewrite.maven.ModulePlan.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@code graphitron:dev} runs before its loop, from the plan Maven computes for the module. */
class ModulePlanTest {

    private static final Bound<String> JOOQ = bound("org.jooq:jooq-codegen-maven", "generate",
        "generate-sources");
    private static final Bound<String> GENERATE = bound(ModulePlan.GRAPHITRON_PLUGIN, "generate",
        "generate-sources");
    private static final Bound<String> RESOURCES = bound("org.apache.maven.plugins:maven-resources-plugin",
        "resources", "process-resources");
    private static final Bound<String> COMPILE = bound("org.apache.maven.plugins:maven-compiler-plugin",
        "compile", "compile");

    @Test
    @DisplayName("the first round takes the place of the module's graphitron:generate")
    void theRoundTakesGeneratesPlace() {
        assertThat(render(ModulePlan.of(List.of(JOOQ, GENERATE, RESOURCES, COMPILE),
                List.of("graphitron:dev"), true)))
            .containsExactly("jooq-codegen-maven:generate", "round", "maven-resources-plugin:resources",
                "maven-compiler-plugin:compile");
    }

    @Test
    @DisplayName("with no graphitron:generate bound, the round runs before the module compiles")
    void theRoundRunsBeforeCompileWhereNothingGenerates() {
        assertThat(render(ModulePlan.of(List.of(JOOQ, RESOURCES, COMPILE),
                List.of("graphitron:dev"), true)))
            .containsExactly("jooq-codegen-maven:generate", "maven-resources-plugin:resources", "round",
                "maven-compiler-plugin:compile");
    }

    @Test
    @DisplayName("graphitron's other goals are the loop's, and a module that compiles nothing still gets the round")
    void graphitronsOtherGoalsAreLeftOut() {
        var validate = bound(ModulePlan.GRAPHITRON_PLUGIN, "validate", "process-sources");
        assertThat(render(ModulePlan.of(List.of(validate, RESOURCES), List.of("graphitron:dev"), true)))
            .containsExactly("maven-resources-plugin:resources", "round");
    }

    @Test
    @DisplayName("a session that already compiled runs the round alone")
    void aCompiledSessionRunsTheRoundAlone() {
        assertThat(render(ModulePlan.of(List.of(JOOQ, GENERATE, COMPILE),
                List.of("compile", "graphitron:dev"), true)))
            .containsExactly("round");
        assertThat(render(ModulePlan.of(List.of(JOOQ, GENERATE, COMPILE),
                List.of("generate-sources", "graphitron:dev"), true)))
            .as("a phase before compile has not compiled")
            .containsExactly("jooq-codegen-maven:generate", "round", "maven-compiler-plugin:compile");
    }

    @Test
    @DisplayName("compileModule off runs the round alone")
    void theOptOutRunsTheRoundAlone() {
        assertThat(render(ModulePlan.of(List.of(JOOQ, GENERATE, COMPILE), List.of("graphitron:dev"), false)))
            .containsExactly("round");
    }

    private static Bound<String> bound(String plugin, String goal, String phase) {
        return new Bound<>(plugin, goal, phase, plugin + ":" + goal);
    }

    private static List<String> render(List<Step<String>> steps) {
        return steps.stream()
            .map(step -> step instanceof Step.Run<String> run
                ? run.bound().plugin().substring(run.bound().plugin().indexOf(':') + 1) + ":" + run.bound().goal()
                : "round")
            .toList();
    }
}
