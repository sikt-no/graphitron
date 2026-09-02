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
 * The generator reads facts and neither writes them nor decides which store they come from. Two
 * properties over the generator's own sources, and both are about direction rather than placement.
 *
 * <p><b>What the module boundary took over.</b> The fact tier used to live in this module, and this
 * file used to carry three more properties over the file set that was going to move: no javapoet, no
 * upward import, and no sealed type straddling the line. The move happened, so all three are now
 * the pom's: {@code graphitron-model} does not depend on {@code graphitron}, which makes an upward
 * import a compile error rather than an assertion, and javapoet and the seal closures follow from
 * the same edge. Nothing was relaxed; the checks moved to a place that cannot be argued with.
 *
 * <p><b>What it could not take over.</b> This module depends on {@code graphitron-model}, so
 * everything the fact tier publishes is on this module's classpath, the write surface and the
 * store's lifetime included. The compiler is content for a planner to open a store and insert a
 * row; only the two properties below say it must not. They are the direction the move exists to
 * establish rather than a consequence of it.
 *
 * <ul>
 *   <li><b>Nothing that stays writes.</b> It holds today for a reason worth stating because it
 *       makes the rule cheap to keep: no value the generator computes reaches the store at all.
 *       Capture is handed the registry as it stood before the synthesis rewrites, and it re-runs
 *       the {@code @asConnection} expansion from its own decoded rows rather than inheriting the
 *       pipeline's, so even work the generator has already done is refused. The plugin's own
 *       writers are a separate matter and stay outside this module: they record a completed pass,
 *       a compile round and the consumer's source tree, which are the plugin's observations rather
 *       than the generator's account of itself.</li>
 *   <li><b>Nothing that stays owns a store.</b> The generator asks a
 *       {@link no.sikt.graphitron.model.run.CapturePort} for a capture and reads what comes back;
 *       which store that is, where it lives and how long it is held are the port's, decided by
 *       whichever caller built it. So nothing here names the capture entry point, the type that
 *       owns a store's lifetime, or the store's home. The last of those is why
 *       {@link no.sikt.graphitron.model.config.RunContext} carries its store directory below the
 *       line rather than here: the Maven goals need it as a setting, and what it stopped being is
 *       something a pass reads on its way to a store.</li>
 * </ul>
 */
@UnitTier
class FactTierBoundaryTest {

    private static final Path REWRITE = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /**
     * The store's write surface, in the three spellings a writer cannot avoid: the generated table
     * constants a statement names, the sink every capture write goes through, and the boot package
     * that opens a store to write to. A read needs none of the three, going through
     * {@link no.sikt.graphitron.model.read.StoreHandle} and the fact tier's query classes, so
     * naming any of them here is a write or the beginning of one.
     *
     * <p>Asked of this tree alone, not of the module: the query classes under
     * {@code no.sikt.graphitron.plan} name table constants for their reads, which is what a query
     * class does, so the surface reads as a write only where reads have a different route.
     */
    private static final Set<String> WRITE_SURFACE = Set.of(
        "no.sikt.graphitron.model.Tables",
        "FactSink",
        "no.sikt.graphitron.model.boot");

    /**
     * The store-ownership surface: the capture entry point, the type that owns an open store's
     * lifetime, and the name of the setting that says where one lives. A pass needs none of the
     * three, naming {@code CapturePort} and its request instead, so any of them here is a pass
     * deciding something about the store rather than asking for facts.
     */
    private static final Set<String> OWNERSHIP_SURFACE = Set.of(
        "FactCapture",
        "RunStore",
        "storeDirectory");

    @Test
    void nothingThatStaysWritesFacts() throws IOException {
        var violations = new ArrayList<String>();
        for (Path file : staysAboveTheLine()) {
            String body = Files.readString(file);
            for (String surface : WRITE_SURFACE) {
                if (names(body, surface)) {
                    violations.add(rel(file) + "  names  " + surface);
                }
            }
        }
        assertThat(violations)
            .as("the generator writing facts; add the query to the fact tier and read what it"
                + " returns, and if a new fact is wanted, capture is where it is written")
            .isEmpty();
    }

    @Test
    void nothingThatStaysOwnsAStore() throws IOException {
        var violations = new ArrayList<String>();
        for (Path file : staysAboveTheLine()) {
            String body = Files.readString(file);
            for (String surface : OWNERSHIP_SURFACE) {
                if (names(body, surface)) {
                    violations.add(rel(file) + "  names  " + surface);
                }
            }
        }
        assertThat(violations)
            .as("the generator deciding something about the fact store; ask a CapturePort for the"
                + " capture and let whoever built the port decide which store answers it")
            .isEmpty();
    }

    /**
     * The generator's own tree, which is the population both rules are asked of. Before the move it
     * was the complement of a hundred-file manifest; now it is simply what is there, which is the
     * one simplification the move buys this file.
     */
    private static List<Path> staysAboveTheLine() throws IOException {
        List<Path> staying;
        try (var paths = Files.walk(REWRITE)) {
            staying = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
        assertThat(staying)
            .as("the generator's sources must be found on disk; an empty walk would pass both"
                + " checks vacuously")
            .hasSizeGreaterThanOrEqualTo(200);
        return staying;
    }

    /**
     * Whether {@code body} names {@code token} as a whole identifier. Word boundaries rather than a
     * substring, because a surface name is routinely a suffix of an unrelated one: capture's own
     * {@code CatalogFactCapture} ends in {@code FactCapture}, and a substring test reads a javadoc
     * link to it as the generator naming the entry point.
     */
    private static boolean names(String body, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(body).find();
    }

    private static String rel(Path file) {
        return REWRITE.relativize(file).toString().replace('\\', '/');
    }
}
