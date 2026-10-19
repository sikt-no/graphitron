package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: graphql-java's per-subtree {@code localContext} slot means the tenant in
 * generated code, and nothing else. The tenant resolvers write it (the divined key a routed
 * fetcher hands down, the per-row key a fanned fetcher stamps) and the inherited acquisitions read
 * it back; errors travel on the payload's {@code Outcome}. A second writer would make every
 * tenant read below it see the wrong value, which is how an errors list once arrived where a
 * tenant key was expected.
 *
 * <p>The success envelope takes a {@link no.sikt.graphitron.render.TenantHandDown} rather than a
 * free builder tail, which keeps non-tenant code from reaching the slot through the envelope. This
 * guard backs the emitted-literal side: a {@code localContext(} or {@code getLocalContext} literal
 * in the generator's main sources must sit in one of the tenant owners named in
 * {@link #OWNERS}. When it fires, route the value through the owners instead; widen the list only
 * for a new tenant owner.
 */
@UnitTier
class LocalContextOwnershipGuardTest {

    /** The generator sources that own the slot, by file name. */
    private static final Set<String> OWNERS = Set.of(
        "TenantDslEmitter.java",
        "ConnectionRuntimeClassGenerator.java",
        "TenantHandDown.java");

    private static final Pattern SLOT = Pattern.compile("localContext\\(|getLocalContext");

    /** A floor on scanned files, so a drifted repository root cannot pass vacuously. */
    private static final int MIN_SCANNED_FILES = 200;

    @Test
    void onlyTheTenantOwnersNameTheLocalContextSlot() throws IOException {
        Path root = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                if (OWNERS.contains(p.getFileName().toString())) continue;
                var lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (SLOT.matcher(lines.get(i)).find()) {
                        offenders.add(root.relativize(p) + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }

        assertThat(scanned)
            .as("the guard walks the generator's main sources from the repository root; a count "
                + "near zero means the root drifted")
            .isGreaterThan(MIN_SCANNED_FILES);
        assertThat(offenders)
            .as("graphql-java's localContext slot carries the tenant only; route a hand-down through "
                + "TenantHandDown and a read through the tenant acquisitions. Offending sites:\n"
                + String.join("\n", offenders))
            .isEmpty();
    }
}
