package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.maven.ModulePlan.Bound;
import no.sikt.graphitron.rewrite.maven.ModulePlan.Step;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module's build run as {@code graphitron:dev} runs it before its loop, against a plugin manager
 * standing in for Maven's: the goals run in order with the round in its place, and a goal that fails
 * ends the goals and not the round.
 */
class ModulePlanRunTest {

    @Test
    @DisplayName("the goals run in order with the first round in generation's place")
    void theStepsRunInOrder() throws Exception {
        var ran = new ArrayList<String>();
        var mojo = mojo(ran, null);

        mojo.runSteps(List.of(run("jooq"), new Step.DevRound<>(), run("resources"), run("compile")),
            () -> ran.add("round"));

        assertThat(ran).containsExactly("jooq", "round", "resources", "compile");
    }

    @Test
    @DisplayName("a goal that fails ends the goals, and the round still runs")
    void aFailingGoalEndsTheGoalsAndNotTheRound() throws Exception {
        var ran = new ArrayList<String>();
        var mojo = mojo(ran, "jooq");

        mojo.runSteps(List.of(run("jooq"), run("resources"), new Step.DevRound<>(), run("compile")),
            () -> ran.add("round"));

        assertThat(ran).containsExactly("jooq", "round");
    }

    /** A goal named {@code goal}, its execution carrying the name the stand-in records. */
    private static Step<MojoExecution> run(String goal) {
        var descriptor = new MojoDescriptor();
        descriptor.setGoal(goal);
        return new Step.Run<>(new Bound<>("ex:plugin", goal, "compile", new MojoExecution(descriptor)));
    }

    /** The goal with a plugin manager that records each goal it runs and fails {@code failing}. */
    private static DevMojo mojo(List<String> ran, String failing) {
        var manager = (BuildPluginManager) Proxy.newProxyInstance(
            BuildPluginManager.class.getClassLoader(), new Class<?>[] {BuildPluginManager.class},
            (proxy, method, args) -> {
                if (!method.getName().equals("executeMojo")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                String goal = ((MojoExecution) args[1]).getMojoDescriptor().getGoal();
                ran.add(goal);
                if (goal.equals(failing)) {
                    throw new MojoFailureException(goal + " failed");
                }
                return null;
            });
        var mojo = new DevMojo(null, manager);
        var project = new MavenProject();
        project.setArtifactId("module");
        mojo.project = project;
        return mojo;
    }
}
