package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.code_action.LintQuickFixes;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * How much each language-server surface's statement makes the database read, counted rather than
 * reasoned about. The counterpart to the statement-count enforcers beside this one: they pin how
 * many questions a request asks, and a request that asks one question can still expand it into a
 * scan of every row in the store, which is the defect they cannot see. One goto-definition was
 * costing over a second inside its single pinned statement.
 *
 * <p>An enforcer, not a benchmark. What it reads is the {@code scanCount} H2 reports per plan node
 * under {@code EXPLAIN ANALYZE}, which is a count of rows visited and not a duration: it is the same
 * number on a fast machine and a loaded one, which is what lets a tier that must not fail for being
 * slow hold a cost ceiling at all. {@code ReadBudget} states the other half of that rule, and the
 * budgets stay what they are, a guard against a query that would never return.
 *
 * <h2>The two shapes of assertion here</h2>
 *
 * <p>{@link #everySurfaceStaysUnderItsCeiling} is the broad net: a ceiling per surface, each the
 * cost measured when this landed plus room, and each meant to fail on an order-of-magnitude
 * regression rather than to police a handful of scans. A ceiling is a number somebody has to
 * defend, which is the objection {@code InlineMultiplicityCheck} records against its own and the
 * reason that check reports rather than gates; the answer here is that one surface's own statement
 * is a far narrower claim than every relation in the schema, and that the fixture below is fixed. A
 * change to the fixture's catalog moves these numbers, and moving them is the right response.
 *
 * <p>Every ceiling here was set from both shapes measured on <em>this</em> fixture, and each was
 * confirmed to fail with the defect reinstated. That is the whole difference between a ceiling and a
 * decoration, and it is easy to lose: a number carried over from a bigger fixture's arithmetic, or
 * chosen as a round generous-looking figure, can sit above the very regression it names and pass on
 * a tree with the fix removed. So a ceiling added here for a new surface is not finished when it
 * passes; it is finished when it has been seen to fail.
 *
 * <p>The ceilings alone would not have caught the defect that prompted them, though: it cost a
 * declaration read 121 extra scans at this fixture's size, which is 15% of one of the numbers below
 * and no ceiling anybody would defend. What makes it visible is that the excess *grows with the
 * schema*, and that is {@link #theCensusLookupDoesNotTrackTheSchemasSize}, which states the arm's
 * invariant directly and at two sizes. Those tests are the sharp ones; the ceilings are the net.
 *
 * <p>{@link #theInlayReadCostsABoundedAmountPerDeclaration} is the second of them, and the reason it
 * exists is worth stating because the same reason will apply to the next surface. A fixed small
 * fixture is structurally blind to a cost that grows with the schema, the fixture being exactly
 * where such a term is smallest: the shape that made the inlay read answer nothing at all on a real
 * schema cost 1544 scans over the three types below and 561851 over sakila's 239. A retuned ceiling
 * catches that one defect and says nothing about the next, which will be a term invisible at three
 * types and dominant at three hundred. So the two live together by design. The five other surfaces
 * here still have only the net, and that gap is real rather than overlooked: each of their ceilings
 * passes on the unregistered tree too. What the helpers below are shaped for is that a second
 * surface is one more assertion rather than a second mechanism.
 */
class SurfaceScanCountTest {

    /** H2 annotates each plan node with the rows it visited. This is the whole instrument. */
    private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

    /**
     * The inlay ceiling, placed between two measured shapes rather than above one. The read costs
     * 482 scans on this fixture as the store stands, and 1544 with the two {@code meta_materialize}
     * registrations it depends on removed; that unregistered shape is not a hypothetical, it is what
     * the surface cost on a real schema until those rows landed for another surface's sake, and at
     * sakila's size it answered nothing at all. 800 leaves a factor of 1.66 below and 1.93 above.
     *
     * <p>So this is a number whose whole value is that it discriminates, and it cannot be raised on
     * the strength of the current cost alone. Raising it because a new arm pushed today's figure up
     * is exactly how it returns to being inert: re-measure the unregistered shape first, and if no
     * window is left between the two, say so rather than picking a number above both.
     */
    private static final long INLAY_CEILING = 800;

    /**
     * What one inlay request may cost per declaration of the schema it is pointed at, which is the
     * property the ceiling above is structurally blind to, for the reason this class's own
     * documentation gives.
     *
     * <p>Asserted as a level rather than as a growth ratio, for a measured reason. Both shapes are
     * flat in the schema, 42 then 41 per declaration as the store stands and 150 then 141
     * unregistered across a fourfold schema, so a ratio reads about 1 either way and separates
     * nothing. What separates them is the size of the constant, and 80 sits between the two with a
     * factor of 1.9 below and 1.76 above the worse of the guarded pair. Two sizes rather than one
     * because a single size cannot tell a bounded constant from the low end of a superlinear curve,
     * and flatness across the pair is what says the constant is the whole story.
     */
    private static final long INLAY_PER_DECLARATION_CEILING = 80;

    /** Types in the smaller and larger schema the per-declaration cost is measured over. */
    private static final int SMALLER_SCHEMA = 60;
    private static final int LARGER_SCHEMA = 240;

    /**
     * Omitted-name {@code @field} sites per type in those two schemas. More than one on purpose. A
     * leaf field carrying a {@code @field} is what the omitted-name arm reads, so sites are the
     * dimension that arm's cost actually tracks, and a type declaring a single field is not a shape
     * an author writes. Four is enough for the per-declaration figure to be flat across a fourfold
     * schema, which is what the assertion needs of the fixture.
     */
    private static final int SITES_PER_TYPE = 4;

    /**
     * The fixture every ceiling below is measured against: a type bound to a catalog table, a field
     * whose name a column answers, a field carrying an omitted directive argument for the inlay
     * surface to fill, and a type no table binds. Small on purpose. The costs a surface pays here
     * are the fixed shape of its statement rather than a function of how much schema it was pointed
     * at, which is what makes a constant ceiling meaningful; the two properties that do need two
     * sizes say so and build their own fixtures.
     */
    private static final String SDL = """
        type Query {
            films: [Film]
        }

        type Film @table(name: "film") {
            title: String @field
            rating: String
        }

        type FilmCard {
            title: String
        }
        """;

    /** The arg-name completion's own source: a directive with a trailing comma to complete after. */
    private static final String COMPLETION_SDL =
        "type Query { x: Int @service(service: {className: \"x\"}, ) }\n";

    @TempDir
    static Path tmp;

    private static StoreFixture store;
    private static FileSnapshot file;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL, StoreFixture.backingClasses());
        file = WorkspaceFileTestSupport.snapshot(SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void everySurfaceStaysUnderItsCeiling() {
        assertThat(scansFor(handle ->
            DeclarationDefinitions.compute(file, handle, at(4, "type Fi"))))
            .as("goto-definition on a type declaration name")
            .isLessThan(260);

        assertThat(scansFor(handle ->
            DeclarationDefinitions.compute(file, handle, at(5, "    titl"))))
            .as("goto-definition on a member declaration name, which adds the column arms")
            .isLessThan(900);

        assertThat(scansFor(handle ->
            DeclarationHovers.compute(file, Optional.of(handle), at(4, "type Fi"))))
            .as("hover on a type declaration name, which reads the declaration arms and its own")
            .isLessThan(360);

        assertThat(scansFor(handle ->
            InlayHints.compute(everyHintEnabled(), file, Optional.of(handle), wholeFile())))
            .as("inlay hints over the whole file, every axis enabled; see INLAY_CEILING before "
                + "raising this, the number sits between two measured shapes")
            .isLessThan(INLAY_CEILING);

        assertThat(scansFor(handle ->
            Diagnostics.compute(store.vocabulary(), "file:///x.graphqls", file, Optional.of(handle))))
            .as("the diagnostics of one file")
            .isLessThan(1050);

        assertThat(scansFor(handle -> LintQuickFixes.compute(
            codeActionParams(), Optional.of(handle), SDL.getBytes(StandardCharsets.UTF_8))))
            .as("the row-backed quick fixes behind a code action")
            .isLessThan(20);

        assertThat(scansFor(SurfaceScanCountTest::completeArgumentNames))
            .as("argument-name completion")
            .isLessThan(20);
    }

    /**
     * The redirect arm reaches the catalog census once per backing class the coordinate has, and not
     * once per row of a relation that grows with the schema. Stated as the arm's cost over the cost
     * of the relation it drives from, because that difference is the lookup and nothing else: the
     * driving relation is a view whose own cost grows with the schema and would otherwise be most of
     * the number, and it is a cost this surface does not own.
     *
     * <p>Two sizes, because one size cannot tell a bounded lookup from a per-row one. Written as one
     * join of the two relations the excess was 184 scans over a one-type schema and 3724 over a
     * sixty-type one, H2 having chosen to drive from the census and evaluate the view per catalog
     * table. As a correlated lookup it is the census's own row count at both sizes, which is the
     * number below.
     */
    @Test
    void theCensusLookupDoesNotTrackTheSchemasSize() {
        assertThat(censusLookupCost(1))
            .as("the lookup over a one-type schema")
            .isLessThan(150);
        assertThat(censusLookupCost(60))
            .as("the lookup over a sixty-type schema, which must be the same lookup")
            .isLessThan(150);
    }

    /**
     * One inlay request reads a relation over the whole captured graph and then filters, so what a
     * window asks decides which rows come back and not how many are visited: a ten-line window and a
     * four-thousand-line file scan the same amount. That is the shape this states, at a region held
     * fixed while the schema around it grows, so the number is a cost per declaration of schema the
     * author wrote and not a cost per hint the editor asked for.
     *
     * <p>Bounded rather than absent is the claim. The cost is allowed to track the schema, since
     * every arm reads a relation of it; what it may not do is track it steeply enough for a large
     * consumer's schema to stop answering, which is what the guarded shape did.
     */
    @Test
    void theInlayReadCostsABoundedAmountPerDeclaration() {
        assertThat(inlayScansPerDeclaration(SMALLER_SCHEMA))
            .as("per declaration over a " + SMALLER_SCHEMA + "-type schema")
            .isLessThan(INLAY_PER_DECLARATION_CEILING);
        assertThat(inlayScansPerDeclaration(LARGER_SCHEMA))
            .as("per declaration over a " + LARGER_SCHEMA + "-type schema, four times the schema "
                + "and so the size that would show a term the smaller one hides")
            .isLessThan(INLAY_PER_DECLARATION_CEILING);
    }

    // ===== Helpers =====

    /**
     * What an inlay request costs per declaration at a schema of {@code types} scaled types. The
     * region is the same few lines at both sizes, so the growth the number would show is the
     * schema's and not the request's.
     */
    private static long inlayScansPerDeclaration(int types) {
        Path directory = tmp.resolve("inlay-scale-" + types);
        String sdl = scaledSdl(types, SITES_PER_TYPE);
        try (var scaled = StoreFixture.ofCatalog(directory, sdl, StoreFixture.backingClasses())) {
            var snapshot = WorkspaceFileTestSupport.snapshot(sdl);
            long total = scansFor(scaled, handle -> InlayHints.compute(
                everyHintEnabled(), snapshot, Optional.of(handle), firstDeclarations()));
            return total / declarations(types, SITES_PER_TYPE);
        }
    }

    /** A region small enough to be the same request at every schema size, and not empty at any. */
    private static Range firstDeclarations() {
        return new Range(new Position(0, 0), new Position(4, 0));
    }

    /**
     * What reaching the census costs the redirect arm over reading its driving relation alone, at a
     * schema of {@code types} table-bound types. The arm is taken by the name it carries in the
     * statement rather than by position, and the control is the same relation under the same two
     * predicates, which is what the arm would cost if it asked the census nothing at all.
     */
    private static long censusLookupCost(int types) {
        Path directory = tmp.resolve("scale-" + types);
        try (var scaled = StoreFixture.ofCatalog(directory, scaledSdl(types),
                StoreFixture.backingClasses())) {
            var handle = scaled.handle();
            var arms = DeclarationFacts.arms(handle, new DeclarationFacts.Coord.Type("T0"));
            Field<?> redirects = arms.fields().stream()
                .filter(arm -> "redirects".equals(arm.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no arm named redirects in the statement"));

            var drivingRelation = multiset(select(INTENT_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING)
                .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(handle.graphName()))
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq("T0")));

            return scans(handle, handle.dsl().select(redirects))
                - scans(handle, handle.dsl().select(drivingRelation));
        }
    }

    /** {@code types} types each bound to a real catalog table, so every census row it reads exists. */
    private static String scaledSdl(int types) {
        return scaledSdl(types, 0);
    }

    /**
     * The same schema with {@code sites} leaf fields per type, each carrying a {@code @field} whose
     * name argument was omitted. A dimension of its own rather than a fixed count, because the cost
     * tracks sites and types separately and a schema of one field per type is not the shape an
     * author writes. The thirteen tables are the same thirteen at every size, which is what holds
     * the catalog census fixed while the schema grows; the catalog is the other scaling direction
     * and not this one's subject.
     */
    private static String scaledSdl(int types, int sites) {
        var tables = List.of("film", "actor", "address", "category", "city", "country", "customer",
            "inventory", "language", "payment", "rental", "staff", "store");
        return "type Query { first: T0 }\n" + IntStream.range(0, types)
            .mapToObj(i -> "type T" + i + " @table(name: \"" + tables.get(i % tables.size())
                + "\") { id: ID" + fieldSites(sites) + " }\n")
            .reduce("", String::concat);
    }

    /** The omitted-name {@code @field} sites one scaled type carries. */
    private static String fieldSites(int sites) {
        return IntStream.range(0, sites)
            .mapToObj(s -> " f" + s + ": String @field")
            .reduce("", String::concat);
    }

    /**
     * The type and field declarations {@link #scaledSdl(int, int)} writes, which is what a
     * per-declaration cost is stated over. The root type and its one field are left out as a
     * constant two that neither size's arithmetic turns on.
     */
    private static int declarations(int types, int sites) {
        return types * (1 + sites);
    }

    /** Every axis on, which is what a client that opted in sends and what the default is not. */
    private static InlayHintConfig everyHintEnabled() {
        return new InlayHintConfig(true, true, true, true);
    }

    private static Range wholeFile() {
        return new Range(new Position(0, 0), new Position((int) SDL.lines().count() + 1, 0));
    }

    private static Point at(int line, String upTo) {
        return new Point(line, upTo.length());
    }

    private static CodeActionParams codeActionParams() {
        var params = new CodeActionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///x.graphqls"));
        params.setRange(wholeFile());
        return params;
    }

    private static void completeArgumentNames(StoreHandle handle) {
        var bytes = COMPLETION_SDL.getBytes(StandardCharsets.UTF_8);
        var snapshot = WorkspaceFileTestSupport.snapshot(COMPLETION_SDL);
        int column = COMPLETION_SDL.indexOf("}, )") + 3;
        var cursor = new Point(0, column);
        var directive = Directives.findContaining(snapshot.tree().getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected a directive at the cursor"));
        ArgNameCompletions.generate(store.vocabulary(), handle, directive, cursor,
            new Position(0, column), bytes);
    }

    /**
     * The scans every statement one surface issues adds up to. The surface is driven through the
     * same provider seam the statement-count enforcers drive, over a handle that records what it
     * executes; each recorded statement is then explained on the fixture's own connection, rendered
     * with its bind values inlined so the plan is the one the surface would have got.
     */
    private static long scansFor(Consumer<StoreHandle> surface) {
        return scansFor(store, surface);
    }

    /** The same instrument over a store a test built for itself, which the scaled sizes need. */
    private static long scansFor(StoreFixture fixture, Consumer<StoreHandle> surface) {
        var executed = new ArrayList<Query>();
        surface.accept(recording(fixture, executed));
        assertThat(executed).as("a surface that read nothing would pass every ceiling").isNotEmpty();
        long total = 0;
        for (Query query : executed) {
            total += scans(fixture.handle(), query);
        }
        return total;
    }

    private static long scans(StoreHandle handle, Query query) {
        String plan = handle.dsl()
            .fetch("EXPLAIN ANALYZE " + handle.dsl().renderInlined(query))
            .get(0).get(0, String.class);
        long total = 0;
        Matcher counts = SCAN_COUNT.matcher(plan);
        while (counts.find()) {
            total += Long.parseLong(counts.group(1));
        }
        return total;
    }

    /** The fixture's store, seen through a handle that keeps every statement it executes. */
    private static StoreHandle recording(StoreFixture fixture, List<Query> into) {
        var configuration = fixture.handle().dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    if (ctx.query() != null) {
                        into.add(ctx.query());
                    }
                }
            }));
        return new StoreHandle(DSL.using(configuration), StoreFixture.GRAPH);
    }
}
