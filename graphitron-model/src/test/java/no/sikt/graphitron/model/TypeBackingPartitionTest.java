package no.sikt.graphitron.model;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which class backs a type, read by graphs whose classpaths differ.
 *
 * <p>Java rather than a fact document because a document cannot vary its classpath, and every case
 * here is about the classpath. The second classpath is a small library compiled by this test, twice:
 * once with {@code -parameters} and once without, which is the one difference between the
 * {@code named} and {@code nameless} graphs. It declares a {@code FilmBlurbHolder} under the service
 * corpus's own name and with other contents, so a graph reading both entries meets one class name
 * twice. Compiled here rather than kept as a module because both differences are a compiler's to
 * make, and capture reads the bytes it makes exactly as it reads the corpus's.
 *
 * <p>Every graph reads one schema. Holder is produced by a service only the corpus declares and
 * LibHolder by one only the library declares, so in each graph the half its classpath lacks grounds
 * nothing. The rest of what the relation answers is in {@code facts/type-backing*.graphqls}.
 */
class TypeBackingPartitionTest {

    @TempDir
    static Path tmp;

    private static CapturedStore captured;

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services";
    private static final String HOLDER = SERVICES + ".FilmBlurbHolder";
    private static final String BLURB = SERVICES + ".FilmBlurb";

    private static final String SDL = """
        extend type Query {
            holder(filmId: Int): Holder
                @service(service: {className: "%1$s.FilmBlurbHolderService", method: "byId"})
            libHolder(filmId: Int): LibHolder
                @service(service: {className: "lib.HolderService", method: "byId"})
            assign(in: AssignmentInput): String
                @service(service: {className: "lib.HolderService", method: "assign"})
        }

        type Holder { blurb: Blurb }
        type Blurb { description: String }
        type LibHolder { blurb: LibBlurb }
        type LibBlurb { description: String }
        input AssignmentInput { note: String }
        """.formatted(SERVICES);

    /** The library: the holder under the corpus's name, what it holds, and a service for both. */
    private static final Map<String, String> LIBRARY = Map.of(
        SERVICES.replace('.', '/') + "/FilmBlurbHolder.java", """
            package %s;
            public record FilmBlurbHolder(lib.Blurb blurb) {}
            """.formatted(SERVICES),
        "lib/Blurb.java", """
            package lib;
            public record Blurb(String description) {}
            """,
        "lib/Assignment.java", """
            package lib;
            public record Assignment(String note) {}
            """,
        "lib/HolderService.java", """
            package lib;
            public final class HolderService {
                private HolderService() {}
                public static %s.FilmBlurbHolder byId(Integer filmId) {
                    return new %s.FilmBlurbHolder(new Blurb(""));
                }
                public static String assign(Assignment in) {
                    return in.note();
                }
            }
            """.formatted(SERVICES, SERVICES));

    @BeforeAll
    static void captureFourGraphs() throws IOException {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var corpus = ClasspathCorpus.entries();
        var named = library("named", true);
        var nameless = library("nameless", false);
        var both = new ArrayList<>(corpus);
        both.addAll(named);

        captured = CapturedStore.ofCatalogWith(tmp.resolve("store"), "own", SDL, jooq,
            corpus);
        captured.andCatalogGraphWith("named", SDL, jooq, named);
        captured.andCatalogGraphWith("nameless", SDL, jooq, nameless);
        captured.andCatalogGraphWith("both", SDL, jooq, both);
    }

    @AfterAll
    static void closeTheStore() {
        if (captured != null) {
            captured.close();
        }
    }

    @Test
    @DisplayName("a graph is grounded and hops on its own classpath only")
    void aGraphIsGroundedAndHopsOnItsOwnMembershipOnly() {
        assertThat(backings("own")).containsExactlyInAnyOrder(
            "Holder=" + HOLDER + " PRODUCER",
            "Blurb=" + BLURB + " ACCESSOR");
        assertThat(backings("named")).containsExactlyInAnyOrder(
            "LibHolder=" + HOLDER + " PRODUCER",
            "LibBlurb=lib.Blurb ACCESSOR",
            "AssignmentInput=lib.Assignment PRODUCER");
    }

    @Test
    @DisplayName("a parameter compiled without its name feeds nothing")
    void aParameterWithNoNameFeedsNothing() {
        assertThat(backings("nameless")).containsExactlyInAnyOrder(
            "LibHolder=" + HOLDER + " PRODUCER",
            "LibBlurb=lib.Blurb ACCESSOR");
    }

    @Test
    @DisplayName("one class name on two entries is two classes, and a contest")
    void oneClassNameOnTwoEntriesIsTwoClasses() {
        assertThat(backings("both")).containsExactlyInAnyOrder(
            "Holder=" + HOLDER + " PRODUCER",
            "Blurb=" + BLURB + " ACCESSOR",
            "Blurb=lib.Blurb ACCESSOR",
            "LibHolder=" + HOLDER + " PRODUCER",
            "LibBlurb=" + BLURB + " ACCESSOR",
            "LibBlurb=lib.Blurb ACCESSOR",
            "AssignmentInput=lib.Assignment PRODUCER");
        assertThat(conflicts()).containsExactlyInAnyOrder("both Blurb 2", "both LibBlurb 2");
    }

    private static List<String> backings(String graph) {
        DSLContext dsl = captured.dsl();
        var b = GRAPHITRON_TYPE_BACKING;
        return dsl.select(b.TYPE_NAME, b.CLASS_NAME, b.DECLARED_VIA)
            .from(b)
            .where(b.GRAPH_NAME.eq(graph))
            .fetch(r -> r.value1() + "=" + r.value2() + " " + r.value3());
    }

    /** Every contest in the store, graph first, so a contest leaking across graphs shows. */
    private static List<String> conflicts() {
        var c = INTENT_TYPE_BACKING_CONFLICT;
        return captured.dsl().selectFrom(c)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + " " + r.getCandidates());
    }

    /** The library compiled under a root of its own, as the consumer's own code. */
    private static List<ClasspathEntry> library(String name, boolean parameters) throws IOException {
        Path sources = tmp.resolve(name + "-src");
        Path classes = Files.createDirectories(tmp.resolve(name + "-classes"));
        var files = new ArrayList<String>();
        for (var source : LIBRARY.entrySet()) {
            Path file = sources.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        var args = new ArrayList<>(List.of("-d", classes.toString()));
        if (parameters) {
            args.add("-parameters");
        }
        args.addAll(files);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            args.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("the " + name + " library does not compile");
        }
        try (Stream<Path> written = Files.walk(classes)) {
            assertThat(written.anyMatch(p -> p.toString().endsWith("HolderService.class")))
                .as("the %s library compiled to %s", name, classes).isTrue();
        }
        return List.of(new ClasspathEntry(classes, ClasspathEntry.Origin.REACTOR,
            "no.sikt:graphitron-test-" + name, null));
    }
}
