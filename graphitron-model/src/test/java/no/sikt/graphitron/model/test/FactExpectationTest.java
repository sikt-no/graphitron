package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact examples: what each document in {@code src/test/resources/facts} says the store holds
 * for it.
 *
 * <p>A neighbour of {@link CorpusExpectationTest} sharing its whole mechanism, and separate from it
 * for what the two folders are about rather than for anything mechanical. A corpus document carries
 * a classification verdict and renders into an author-facing page; a document here is about the
 * shape of the store, which is a contributor's concern. Folding them together would put store
 * plumbing on that page and make a fact example read as a claim about generated code.
 *
 * <p>What this class does not carry is the corpus's other obligations, and the absence is the point:
 * no projection operation, no documentation fragment, no verdict, no launcher-command apparatus. A
 * fact document is an SDL example and the rows it says the store holds for it.
 *
 * <p>A graph can be read in steps, one document per version of one of its files:
 * {@code name.<file>.<epoch>.graphqls}, the file a short name that only tells the files apart and
 * the epoch the second that version is read at. A claim about a second reading is a claim about the
 * store a first one left, so it is written as the sequence that produces it; and each reading runs
 * at its own instant, so a mark column says which reading last wrote a row, which is what tells a
 * row the sweep kept from one a later reading wrote again.
 */
class FactExpectationTest {

    /** The fact folder, with the floor that stops a folder which has stopped resolving. */
    private static final CorpusDocuments.Folder FACTS = new CorpusDocuments.Folder("facts", 1);

    @TempDir
    static Path tmp;

    /**
     * A stepped document's file name, whole: the graph, the file within it, and the instant this
     * version of the file is read at as ten-digit epoch seconds. Anchored on the extension and on
     * a graph with no dot of its own, so a name either is a step or is not one; a plain document's
     * name carries no dot, and a name with one that does not match is refused rather than read as
     * whichever kind it happens to resemble.
     */
    private static final Pattern STEP =
        Pattern.compile("([^.]+)\\.([a-z]+)\\.(\\d{10})" + Pattern.quote(CorpusDocuments.SUFFIX));

    /** The name a stepped graph's prelude is written under, which no file of the pattern can take. */
    private static final String PRELUDE_FILE = "_prelude";

    private static CapturedStore captured;
    private static List<Block> blocks;
    private static List<String> divergences;

    /**
     * The documents read once, by graph, and the stepped ones: by graph, then by file, then by the
     * instant each version of the file is read at. A plain document beside a stepped graph of the
     * same name is a folder that does not say which it means.
     */
    private record Graphs(Map<String, CorpusDocuments.Document> plain,
                          Map<String, Map<String, TreeMap<Long, CorpusDocuments.Document>>> stepped) {

        static Graphs of(List<CorpusDocuments.Document> documents) {
            var plain = new LinkedHashMap<String, CorpusDocuments.Document>();
            var stepped = new LinkedHashMap<String, Map<String, TreeMap<Long, CorpusDocuments.Document>>>();
            for (var document : documents) {
                var step = STEP.matcher(document.id() + CorpusDocuments.SUFFIX);
                if (step.matches()) {
                    stepped.computeIfAbsent(step.group(1), ignored -> new TreeMap<>())
                        .computeIfAbsent(step.group(2), ignored -> new TreeMap<>())
                        .put(Long.parseLong(step.group(3)), document);
                } else if (document.id().contains(".")) {
                    throw new AssertionError(document.id() + CorpusDocuments.SUFFIX + " is neither "
                        + "a plain document, whose name has no dot, nor a step named "
                        + "<graph>.<file>.<epoch>" + CorpusDocuments.SUFFIX + " with a lower-case "
                        + "file and a ten-digit epoch");
                } else {
                    plain.put(document.id(), document);
                }
            }
            for (String graph : stepped.keySet()) {
                if (plain.containsKey(graph)) {
                    throw new AssertionError(graph + " is both a document and a graph read in "
                        + "steps; name it one way");
                }
            }
            if (plain.isEmpty()) {
                throw new AssertionError("the fact folder holds no plain document to open the store");
            }
            return new Graphs(plain, stepped);
        }

        /** Every instant one stepped graph is read at, in order. */
        static List<Long> instants(Map<String, TreeMap<Long, CorpusDocuments.Document>> files) {
            return files.values().stream().flatMap(versions -> versions.keySet().stream())
                .distinct().sorted().toList();
        }
    }

    @BeforeAll
    static void captureEveryDocument() {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var graphs = Graphs.of(CorpusDocuments.documents(FACTS));
        // The classpath corpus beside the catalog, so a document can state what a reading finds in
        // Java as well as what it finds in SDL. The model and the capture are one subject: a
        // document asserting a derived relation without the facts under it tests half of what it
        // names, and the runner resolves a relation by name whether capture wrote it or a view
        // derived it.
        //
        // Affordable because the classpath is read once for the store. It carries no graph, so the
        // second document's reading of it is the first document's reading again.
        var corpus = ClasspathCorpus.entries();
        var allBlocks = new ArrayList<Block>();
        var allDivergences = new ArrayList<String>();

        for (var document : graphs.plain().entrySet()) {
            if (captured == null) {
                captured = CapturedStore.ofCatalogWith(tmp, document.getKey(),
                    full(document.getValue()), jooq, List.of(), corpus);
            } else {
                captured.andCatalogGraphWith(document.getKey(), full(document.getValue()), jooq,
                    List.of(), corpus);
            }
        }
        var plainBlocks = CorpusExpectations.blocks(captured.dsl()).stream()
            .filter(block -> graphs.plain().containsKey(block.graph()))
            .toList();
        check(plainBlocks, "", allBlocks, allDivergences);

        // A stepped graph is read once per instant any of its files names. At each, the files
        // with a version at that instant are written and dated at it, the graph is captured over
        // every file it has met so far, and only the claims written at that instant are checked:
        // a file the reading did not write keeps its bytes, its rows keep the mark an earlier
        // reading gave them, and its blocks state that earlier reading's claims. The prelude is
        // the graph's own file, written at its first instant, so no file declares it twice.
        for (var graph : graphs.stepped().entrySet()) {
            var files = new LinkedHashSet<String>();
            files.add(PRELUDE_FILE);
            boolean first = true;
            for (long instant : Graphs.instants(graph.getValue())) {
                var written = new LinkedHashMap<String, String>();
                if (first) {
                    written.put(PRELUDE_FILE, CorpusDocuments.prelude(FACTS));
                    first = false;
                }
                graph.getValue().forEach((file, versions) -> {
                    var version = versions.get(instant);
                    if (version != null) {
                        written.put(file, version.sdl());
                        files.add(file);
                    }
                });
                var sourceNames = captured.andCatalogGraphReadAt(graph.getKey(), written, files,
                    Instant.ofEpochSecond(instant), jooq, List.of(), corpus);
                var writtenNow = written.keySet().stream()
                    .filter(file -> !PRELUDE_FILE.equals(file))
                    .map(sourceNames::get)
                    .collect(Collectors.toSet());
                check(CorpusExpectations.blocksWrittenIn(captured.dsl(), writtenNow),
                    "@" + instant + " ", allBlocks, allDivergences);
            }
        }
        blocks = List.copyOf(allBlocks);
        divergences = List.copyOf(allDivergences);
    }

    /** How {@code current} disagrees with the store as it now stands, labelled with the reading. */
    private static void check(List<Block> current, String label, List<Block> allBlocks,
                              List<String> allDivergences) {
        allBlocks.addAll(current);
        CorpusExpectations.divergences(captured.dsl(), current).stream()
            .map(divergence -> label + divergence)
            .forEach(allDivergences::add);
    }

    @AfterAll
    static void closeTheStore() {
        if (captured != null) {
            captured.close();
        }
    }

    /** The prelude and the document, which is what the loader captures as one graph. */
    private static String full(CorpusDocuments.Document document) {
        return CorpusDocuments.prelude(FACTS) + "\n" + document.sdl();
    }

    @Test
    @DisplayName("every declared row is a row the store holds")
    void everyDeclaredRowIsProduced() {
        assertThat(divergences)
            .as("a row a document declares and the relation does not hold, or the reverse where the "
                + "document claimed the whole population")
            .isEmpty();
    }

    /**
     * The defect model's totality gate over the fact examples' store: every output field has a
     * source, or an error defect says why it has none, written on the field or on the type it is
     * a field of. A field missing a total fact with nothing explaining it is a capture bug, and the
     * examples are where every arm of the facts is written down.
     */
    @Test
    @DisplayName("every output field has a source, or a defect explains why not")
    void everyOutputFieldHasASourceOrADefect() {
        var f = no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
        var t = no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
        var src = no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE;
        var site = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
        var type = no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
        var dsl = captured.dsl();
        var unexplained = dsl.select(f.GRAPH_NAME, f.COORDINATE)
            .from(f)
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.TYPE_NAME),
                t.KIND.in("OBJECT", "INTERFACE"))
            .whereNotExists(dsl.selectOne().from(src)
                .where(src.GRAPH_NAME.eq(f.GRAPH_NAME), src.TYPE_NAME.eq(f.TYPE_NAME),
                    src.FIELD_NAME.eq(f.FIELD_NAME)))
            .andNotExists(dsl.selectOne().from(site)
                .join(type).on(type.CODE.eq(site.CODE), type.SEVERITY.eq("error"))
                .where(site.GRAPH_NAME.eq(f.GRAPH_NAME),
                    site.COORDINATE.in(f.COORDINATE, f.TYPE_NAME)))
            .fetch(r -> r.value1() + " " + r.value2());
        assertThat(unexplained)
            .as("output fields with no source and no error defect on them or their type")
            .isEmpty();
        assertThat(dsl.fetchCount(src))
            .as("a store with no sources passes the gate by having nothing to check")
            .isPositive();
    }

    /**
     * The floor that makes an empty read loud. Every other assertion here passes over a folder that
     * has stopped resolving, a glob that has stopped matching, or a document whose blocks were all
     * deleted, so the one that cannot is worth stating separately.
     */
    @Test
    @DisplayName("the documents declare blocks at all")
    void theDocumentsDeclareBlocks() {
        assertThat(blocks)
            .as("a fact folder that declares nothing passes every sweep over it")
            .isNotEmpty();
        assertThat(blocks).allSatisfy(block -> assertThat(block.rows())
            .as("a block declaring no rows asserts nothing, in either mode")
            .isNotEmpty());
    }

    /**
     * Ragged lines are a cell count that disagrees with the header, which the decoder keeps rather
     * than guessing at. A document that lines its columns up by eye is easy to get wrong by one
     * comma, and the failure is otherwise a missing row rather than a malformed one.
     */
    @Test
    @DisplayName("no block has a line whose cells disagree with its header")
    void everyBlockIsWellFormed() {
        assertThat(blocks.stream().flatMap(block -> block.raggedLines().stream()).toList())
            .as("lines whose cell count differs from the header's")
            .isEmpty();
    }
}
