package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end witness for {@code intent_class_member_slot}: that a real compiler's output, read
 * by a real classpath scan, lands in the census in the shape the rule expects, and that the slots
 * it yields are the ones the fixtures' own declarations describe.
 *
 * <p>One case, deliberately. What the relation returns given rows is a question about the model's
 * own SQL and is pinned where that SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ClassMemberSlotTest}, against a census stated row by row:
 * the arrangements the rule's joins turn on are ones no compiled fixture offers, one class name
 * under two classpath entries and a record component under a class among them. Whether the scanner
 * produces the census faithfully is pinned beside the scanner, in
 * {@code no.sikt.graphitron.rewrite.catalog.ClasspathScannerTest}, and {@code FactCaptureAgreementTest}
 * already binds the census against the walk. What is left for this class is the join of the three:
 * three classes whose declared form the test did not write, and the slot list an author would meet.
 *
 * <p>The population is asserted whole rather than sampled. Sampling would let the rule answer with
 * something extra, and every near-miss in {@link TestSlotPojo} is a name that must produce nothing.
 */
@PipelineTier
class ClassMemberSlotScanTest {

    @TempDir
    Path tmp;

    /**
     * The three fixture classes span every branch of the rule between them: a record with a
     * hand-written bean accessor the record arm must beat, a class covering both prefixes, the
     * first-letter lowering, the near-misses and the two spellings of one property, and an
     * interface, the declared form that is neither.
     *
     * <p>{@code List<String>} appearing on both arms is the load-bearing part of the round trip: the
     * descriptor erases it to {@code List}, so only a scan that read the {@code Signature} attribute
     * and a view that read the declared column give the author the type they wrote.
     */
    @Test
    void aCompilersOwnClassesYieldTheSlotsTheirDeclarationsDescribe() {
        try (var captured = CapturedStore.of(tmp, GRAPH, SDL, census())) {
            assertThat(captured.dsl().selectFrom(INTENT_CLASS_MEMBER_SLOT)
                .fetch(r -> r.getClassName().substring(r.getClassName().lastIndexOf('.') + 1)
                    + "." + r.getSlotName() + " " + r.getOrigin()
                    + " " + r.getDisplayType() + " " + r.getAccessorMethodName()))
                .containsExactlyInAnyOrder(
                    "TestSlotRecord.filmId RECORD_COMPONENT Integer filmId",
                    "TestSlotRecord.title RECORD_COMPONENT String title",
                    "TestSlotRecord.tags RECORD_COMPONENT List<String> tags",
                    "TestSlotPojo.title BEAN_ACCESSOR String getTitle",
                    "TestSlotPojo.title BEAN_ACCESSOR String isTitle",
                    "TestSlotPojo.restricted BEAN_ACCESSOR boolean isRestricted",
                    "TestSlotPojo.uRL BEAN_ACCESSOR String getURL",
                    "TestSlotPojo.tags BEAN_ACCESSOR List<String> getTags",
                    "TestSlotInterface.name BEAN_ACCESSOR String getName");
        }
    }

    // ===== Helpers =====

    private static final String GRAPH = "ClassMemberSlotScanTest";

    /** The SDL is beside the point here; the subject is the classpath. */
    private static final String SDL = "type Query { placeholder: Int }\n";

    private static final Set<String> FIXTURE_CLASSES = Set.of(
        TestSlotRecord.class.getName(), TestSlotPojo.class.getName(), TestSlotInterface.class.getName());

    /** Scanned once: the scan reads every test class, and the answer does not change between runs. */
    private static List<CompletionData.ExternalReference> fixtureCensus;

    /**
     * The three fixture classes as a real scan produced them, filtered out of the scan of this
     * module's test classes. Filtering keeps the captured census small; what each row says is the
     * scanner's, not this test's.
     */
    private static synchronized List<CompletionData.ExternalReference> census() {
        if (fixtureCensus == null) {
            fixtureCensus = ClasspathScanner.scan(testClassesRoot(), testContext().jooqPackage())
                .stream()
                .filter(reference -> FIXTURE_CLASSES.contains(reference.className()))
                .toList();
            assertThat(fixtureCensus)
                .as("every fixture class is public and top-level, so the scan reads all three")
                .hasSize(FIXTURE_CLASSES.size());
        }
        return fixtureCensus;
    }

    private static Path testClassesRoot() {
        try {
            return Path.of(ClassMemberSlotScanTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("test classes root is not a file path", e);
        }
    }
}
