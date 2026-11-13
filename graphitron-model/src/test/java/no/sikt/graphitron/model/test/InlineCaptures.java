package no.sikt.graphitron.model.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The capture call sites in a module's tests whose schema is not read from a fact document, which
 * each module holds under a ceiling of its own that only falls.
 *
 * <p>Every schema a test captures is, or should be, a fact document: a test reads one from
 * {@link FactGraphs}, or captures one into a store of its own through {@link CapturedStore#captureFolder}
 * where the store is its subject. A capture through any other arm states its schema inline, so this
 * counts the calls of those arms: the capturing arms of {@link CapturedStore}, the generator's own
 * {@code GraphitronStore} capture, the fixtures above them in the modules that consume this one, and
 * {@link FactGraphs.Reader#readInline} and {@link FactGraphs#inline()}, which take inline SDL into
 * the population and are still inline until the SDL is a document.
 *
 * <p>A count rather than a roster, as {@code SeedingDissolutionGateTest} is, because conversions run
 * in several sessions at once and a roster collides on every one; the count may be stale on the low
 * side, never the high. It sees calls spelled through their class, and an arm reached through a
 * static import or an instance is counted by the names below; comments are set aside first, so a
 * javadoc naming an arm counts nothing.
 */
public final class InlineCaptures {

    /** The fixtures whose static arms capture, each the class its harness file is named after. */
    private static final String FIXTURES = "CapturedStore|GraphitronStore|TestSchemaHelper|BuiltStore"
        + "|StoreFixture|StoreBackedBuild|BuiltStoreFixture|ModelCapture|EntryFamilyFixture";

    /** The arms that capture, through the class or the {@code held()} owner. */
    private static final String ARMS = "of|ofWith|ofFiles|ofCatalog|ofCatalogWith"
        + "|ownStore|ownStoreWith|ownStoreOfCatalog|ownStoreOfCatalogWith|ownLongLivedStoreOfCatalog"
        + "|ownStoreOfFiles|tagged|configured|ofRefusedSchema|withCapturedStore|capture|captured"
        + "|capturedInMemory|buildBundle|storeBackedPlan|storeBackedFetchers|withStoreOver|run"
        + "|ofClasspath|ofOtherCatalog|withoutCatalog|ofCodeFixtures|ofSchema";

    private static final Pattern THROUGH_A_FIXTURE =
        Pattern.compile("\\b(?:" + FIXTURES + ")\\.(?:held\\(\\)\\.)?(?:" + ARMS + ")\\s*\\(");

    /** Arms reached without their class: a static import, or a further graph on an open fixture. */
    private static final Pattern WITHOUT_A_FIXTURE = Pattern.compile(
        "(?<![\\w.])(?:withCapturedStore|buildBundle)\\s*\\(|\\.(?:readInline|inline\\(\\)\\.read|andGraph|andGraphWith|andCatalogGraph"
            + "|andCatalogGraphWith|andGraphSharingTheFile|recapture"
            + "|recaptureCatalog)\\s*\\(");

    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private InlineCaptures() {}

    /**
     * The inline capture call sites under {@code tests}, one entry per site as {@code file:count},
     * leaving out the files named in {@code harnesses}, where the arms are defined rather than called.
     */
    public static List<String> in(Path tests, Set<String> harnesses) {
        var sites = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(tests)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (harnesses.contains(file.getFileName().toString())) {
                    continue;
                }
                var code = COMMENTS.matcher(Files.readString(file)).replaceAll("");
                long count = THROUGH_A_FIXTURE.matcher(code).results().count()
                    + WITHOUT_A_FIXTURE.matcher(code).results().count();
                for (long i = 0; i < count; i++) {
                    sites.add(tests.relativize(file).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sites;
    }
}
