package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.LauncherCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared launcher-axis pin: every produced row's invocation arm equals the arm
 * {@link LauncherCommands#INVOCATION_BY_SOURCE} declares for its source arm. Applied at each
 * relation the test tree builds (the membership fixture, the closure test's generator run, the
 * corpus production sweep), so the declared determination is checked against every production
 * path rather than one.
 */
public final class LauncherAxisPins {

    private LauncherAxisPins() {}

    public static void assertInvocationMatchesDeclaredDetermination(LauncherRelation relation) {
        for (LauncherCommand row : relation.rows()) {
            var declared = LauncherCommands.INVOCATION_BY_SOURCE.get(row.source().getClass());
            assertThat(declared)
                .as("source arm %s must have a declared invocation determination"
                        + " (INVOCATION_BY_SOURCE is total over LaunchSource's concrete arms)",
                    row.source().getClass().getSimpleName())
                .isNotNull();
            assertThat(row.invocation().getClass())
                .as("coordinate %s.%s: the produced invocation arm must equal the declared"
                        + " determination for source arm %s",
                    row.coordinate().getTypeName(), row.coordinate().getFieldName(),
                    row.source().getClass().getSimpleName())
                .isEqualTo(declared);
        }
    }
}
