package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.model.config.DependencyVersions;
import no.sikt.graphitron.model.config.WatchedDependency;

import java.util.Optional;

/**
 * The generation-time refusal of a dependency below the oldest line the generated code compiles
 * against, {@link WatchedDependency#minimum()}. Where {@link DependencyVersionWarnings} nudges toward
 * the current line and never fails a build, this is a precondition: the generated sources would not
 * compile, so generation stops before it writes any, with the consumer's own coordinate and what to
 * change, rather than leaving javac to report a missing method in a file the consumer did not write.
 *
 * <p>It decides on the same observations the nudge does, the versions the consumer's build resolves
 * on the scopes the generated code compiles against, and settles several coordinates the same way,
 * on the lowest line. Where it cannot decide it is silent: no observation, or a version that does not
 * decompose into a release line. The compiler is still there behind it.
 */
public final class DependencyVersionFloor {

    private DependencyVersionFloor() {}

    /** Why generation is refused, for the first watched dependency below its minimum, or empty. */
    public static Optional<String> refusal(DependencyVersions versions) {
        for (WatchedDependency dep : WatchedDependency.values()) {
            var minimum = dep.minimum();
            if (minimum.isEmpty()) {
                continue;
            }
            var lowest = DependencyVersionWarnings.lowestLine(versions.observedFor(dep));
            if (lowest == null
                || !DependencyVersionWarnings.minorLine(lowest.version())
                    .isBehind(DependencyVersionWarnings.minorLine(minimum.get()))) {
                continue;
            }
            return Optional.of("Graphitron's generated code needs " + lowest.coordinate() + " "
                + minimum.get() + " or later, and this build resolves " + lowest.version() + ". Raise the "
                + "version your build resolves for " + lowest.coordinate() + " to " + minimum.get()
                + " or later (usually the jooq.version property in your pom), regenerate your jOOQ "
                + "catalog with that version's codegen, and run graphitron again.");
        }
        return Optional.empty();
    }
}
