package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: a test does not stand a fact store up for itself. It takes one from the
 * harness that owns its subject, and the harnesses are layered so that every subject has one.
 *
 * <p><b>Why a guard and not javadoc.</b> Within one module, a public type beside
 * {@link TestSchemaHelper} in a package the author already imports would be enough. Across the
 * reactor it is not: an author writing a test in another module imports nothing from
 * {@code no.sikt.graphitron.rewrite}, and no amount of javadoc on a type they never open reaches
 * them. What actually happened is the evidence, every module that needed a store having built its
 * own way in, independently, and having converged on the same answers while disagreeing on the
 * rest by accident.
 *
 * <p><b>The message routes by subject.</b> It deliberately does not say "use the shared home",
 * because there is more than one and picking the wrong one is the mistake being corrected. It asks
 * the one question that decides the layer, what this test is about, and gives the answers. An
 * author who trips this guard should come away having classified their own test.
 *
 * <p><b>Two lists, and no arithmetic over either.</b> {@link #HOMES} names the harnesses
 * themselves, which stand a store up because that is their job. {@link #EXEMPT} names the classes
 * that stand one up and stay: {@link Why#LIFETIME} and {@link Why#ORACLE} permanently, the
 * {@code PENDING_} reasons until the population they name has adopted a layer. Nothing counts the
 * entries; what keeps them honest is
 * {@link #everyDeclaredEntryStillDescribesSomething}, which fails on an entry whose file is gone
 * or has quietly stopped standing a store up. An entry that outlives its reason is the failure
 * mode a hand-maintained list has, and it is the one a reader cannot see.
 *
 * @see StoreFixtureScanner for the recogniser and why it is the store type rather than a factory
 */
@UnitTier
class StoreFixtureGuardTest {

    /** A floor against a walk that reached nothing, the same anti-vacuity the sibling prose guards carry. */
    private static final int MIN_SCANNED_TEST_FILES = 400;

    /** One harness: where it lives, the population it serves, and what it hands out. */
    private record Home(String path, String serves) {}

    /**
     * The harnesses. Each opens a store because handing one out is what it is for, and each is
     * usable without the ones above it: a writer test never captures, a view test never runs a
     * crawler, and a crawler test never runs a build.
     */
    private static final List<Home> HOMES = List.of(
        new Home("graphitron-model/src/test/java/no/sikt/graphitron/model/test/FactStores.java",
            "the store's lifetime, in-memory and file-backed, under names rather than a flag"),
        new Home("graphitron/src/test/java/no/sikt/graphitron/rewrite/CapturedStore.java",
            "a real capture walk over a fixture document, for tests about the crawlers"),
        new Home("graphitron/src/test/java/no/sikt/graphitron/rewrite/BuiltStore.java",
            "a real generator run into a store on disk, for tests about the dev loop's wiring"));

    /** Why a class stands a store up and keeps doing so. */
    private enum Why {
        LIFETIME("the store's own lifetime is the subject: it reopens a home, compares cold against "
            + "warm, or holds the store across a failure, so it cannot take a handle that owns it"),
        ORACLE("a capture oracle, driving capture per view and per arm with the store's own "
            + "population as the subject"),
        PENDING_SEEDING("a relation's algebra, still reached through a crawler; its inputs are "
            + "stateable as rows, so its home is the seeding harness"),
        PENDING_WRITER("drives a facts writer over an open store, which is a level of its own and "
            + "not yet a shared one"),
        PENDING_MODULE_FLOOR("a module that has not yet taken the shared floor for the store's "
            + "lifetime and its fixture file");

        private final String reason;

        Why(String reason) {
            this.reason = reason;
        }
    }

    /** One exemption: the class, why it stands a store up, and what is specific to this one. */
    private record Exempt(String path, Why why, String note) {}

    /**
     * The exemptions. A {@code PENDING_} entry is a promise rather than a permission: it says a
     * population has a layer waiting and has not moved onto it yet, and it fails the build the day
     * the class does move, which is what stops the list from becoming the architecture.
     */
    private static final List<Exempt> EXEMPT = List.of(
        // Permanent: the store's lifetime is what these assert on.
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/PersistentStoreTest.java",
            Why.LIFETIME, "opens and reopens one directory, holding two handles at once"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/WarmStartRefreshTest.java",
            Why.LIFETIME, "compares a cold round against a warm one across a reopen"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/"
            + "BrokenSourceStillCapturesPipelineTest.java",
            Why.LIFETIME, "holds the store across a run that fails, the failure being the subject"),

        // Permanent: capture oracles, which drive capture themselves per arm.
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/FactCaptureAgreementTest.java",
            Why.ORACLE, "one arm per view against the walk, opening directly where no factory fits"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/FactSchemaGateTest.java",
            Why.ORACLE, "the same family, plus bare-store gates over the model's own DDL"),

        // Pending: view algebra still reached through a capture.
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/SeparateFetchTest.java",
            Why.PENDING_SEEDING, "the separate-fetch view; the delivery differential stays a capture"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/TypeBackingClassTest.java",
            Why.PENDING_SEEDING, "the backing views go down; the writer's own rows stay"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/InputOccurrenceShadowTest.java",
            Why.PENDING_SEEDING, "the override view goes down; the path tables stay"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/TypeBackingShadowTest.java",
            Why.PENDING_SEEDING, "walk agreement end to end, so it stays whole and takes a capture "
                + "handle rather than a seed"),

        // Pending: the facts writers, which are one population the tree still spells four ways.
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/JavaSourceFactsTest.java",
            Why.PENDING_WRITER, "the java_ declaration family"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/compile/CompileFactsTest.java",
            Why.PENDING_WRITER, "the javac round"),
        new Exempt("graphitron/src/test/java/no/sikt/graphitron/rewrite/diagnostics/DiagnosticFactsTest.java",
            Why.PENDING_WRITER, "hand-rolls three writers, and drives capture itself besides"),
        new Exempt("graphitron-maven-plugin/src/test/java/no/sikt/graphitron/rewrite/maven/DevMojoTest.java",
            Why.PENDING_WRITER, "line for line what the compile-facts test does, in another module"),
        new Exempt("graphitron-maven-plugin/src/test/java/no/sikt/graphitron/rewrite/maven/dev/"
            + "CatalogRefreshTest.java",
            Why.PENDING_WRITER, "the same, over the java-source writer"),
        new Exempt("graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/RejectionSeverityCoverageTest.java",
            Why.PENDING_WRITER, "spells its writer's fully qualified name inline, which is what a "
                + "test does when the thing it needs has no home worth importing"),
        new Exempt("graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/DiagnosticsToolCompileSourceTest.java",
            Why.PENDING_WRITER, "the javac round again, downstream"),
        new Exempt("graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/DiagnosticsAggregateTest.java",
            Why.PENDING_WRITER, "three own-store cases beside its fixture-backed ones"),

        // Pending: the two downstream modules' own floors.
        new Exempt("graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/StoreFixture.java",
            Why.PENDING_MODULE_FLOOR, "a capture-level copy, keeping its factory set and delegating "
                + "the floor when the module takes the shared one"),
        new Exempt("graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/StoreBackedBuild.java",
            Why.PENDING_MODULE_FLOOR, "a real build, so it belongs at the build level rather than "
                + "on the capture factories"),
        new Exempt("graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/GraphitronMcpServerTest.java",
            Why.PENDING_MODULE_FLOOR, "one empty store, to read a graph nothing has captured; it "
                + "wants the lifetime layer alone and the module cannot reach it yet"),
        new Exempt("graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/StoreFixture.java",
            Why.PENDING_MODULE_FLOOR, "the strongest of the independent copies, and the one whose "
                + "shapes the shared levels were sized against"));

    @Test
    void noTestStandsAStoreUpOutsideAHarness() throws IOException {
        Path repoRoot = GuardScope.locateRepoRoot();
        List<String> declared = declaredPaths();
        List<StoreFixtureScanner.Finding> findings = new ArrayList<>();
        int scanned = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            Path root = repoRoot.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(root)) continue;
            for (var finding : StoreFixtureScanner.scan(root)) {
                if (!declared.contains(relative(repoRoot, finding.file()))) findings.add(finding);
            }
            try (var paths = Files.walk(root)) {
                scanned += (int) paths.filter(p -> p.toString().endsWith(".java")).count();
            }
        }

        assertThat(scanned)
            .as("the guard reaches sibling modules by walking to the repository root; a scanned-file "
                + "count near zero means the root drifted and the guard would pass vacuously")
            .isGreaterThan(MIN_SCANNED_TEST_FILES);

        assertThat(findings)
            .as(routingMessage() + "\n\nSites:\n"
                + findings.stream().map(Object::toString).collect(Collectors.joining("\n")))
            .isEmpty();
    }

    /**
     * Both lists stay honest. An entry pointing at a missing file, or at one that has adopted a
     * harness since the entry was written, fails here rather than lingering as a permission nobody
     * rereads.
     */
    @Test
    void everyDeclaredEntryStillDescribesSomething() {
        Path repoRoot = GuardScope.locateRepoRoot();
        var stale = StoreFixtureScanner.stale(repoRoot, declaredPaths());

        assertThat(stale)
            .as("a harness or exemption entry no longer describes anything. Drop it in the same "
                + "commit as the change that made it stale; an entry outliving its reason is the "
                + "one failure of a hand-maintained list a reader cannot see. Entries, each with "
                + "the claim it was written to make:\n"
                + stale.stream()
                    .map(s -> "  " + s.path() + "\n      " + s.problem()
                        + "\n      claimed: " + claimFor(s.path()))
                    .collect(Collectors.joining("\n")))
            .isEmpty();
    }

    /** Repository-root-relative paths of everything allowed to name the store type in a test. */
    private static List<String> declaredPaths() {
        return Stream.concat(HOMES.stream().map(Home::path), EXEMPT.stream().map(Exempt::path)).toList();
    }

    /** What a declared entry says about itself, rendered when the entry turns out to be stale. */
    private static String claimFor(String path) {
        return Stream.concat(
                HOMES.stream().filter(h -> h.path().equals(path))
                    .map(h -> "a harness, handing out " + h.serves()),
                EXEMPT.stream().filter(e -> e.path().equals(path))
                    .map(e -> e.why().reason + "; " + e.note()))
            .findFirst().orElse("nothing this guard declares");
    }

    private static String relative(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString().replace('\\', '/');
    }

    /**
     * The failure text. It is the architecture stated to the author least likely to have read any
     * of it, so it routes on the subject rather than on the module the author happens to be in.
     */
    private static String routingMessage() {
        return """
            A test opened a fact store of its own. Do not reach for the nearest existing fixture: \
            ask what this test is about, and take the store from the harness that owns that subject.

              A relation's algebra, what a view or a constraint returns given rows?
                Seed it. SeededStore in graphitron-model's test-jar states the inputs as rows, \
            reaches states no crawler can produce, and cannot accidentally assert crawler behaviour.

              A facts writer putting rows in a table at its own cadence?
                Drive the writer over a store from FactStores. Capture cadence and writer cadence \
            are different facts about the store, so a captured fixture is the wrong shape for this.

              A crawler, or agreement between a store-native relation and the walk?
                Capture. CapturedStore hands you the fixture file, the graph identity and the walk, \
            and its named arms say what each capture's inputs are instead of leaving it to a flag.

              The dev loop's own wiring, over rows only a pipeline run can produce?
                Run a build. BuiltStore leaves its facts in a store on disk that outlives the run.

              Your own module's reads over a populated store?
                Put a fixture in your module over one of the above, the way graphitron-lsp's \
            StoreFixture does, and keep the reader-side surface local to you.

            If none of those expresses the shape you need, the finding is that a level is missing \
            one, so add it there. A private copy in your own test class is how the reactor arrived \
            at four independent fixtures that agree by accident and disagree by accident.""";
    }
}
