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
 * <p>The ceilings alone would not have caught the defect that prompted them, though: it cost a
 * declaration read 121 extra scans at this fixture's size, which is 15% of one of the numbers below
 * and no ceiling anybody would defend. What makes it visible is that the excess *grows with the
 * schema*, and that is {@link #theCensusLookupDoesNotTrackTheSchemasSize}, which states the arm's
 * invariant directly and at two sizes. That test is the sharp one; the ceilings are the net.
 */
class SurfaceScanCountTest {

    /** H2 annotates each plan node with the rows it visited. This is the whole instrument. */
    private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

    /**
     * The fixture every ceiling below is measured against: a type bound to a catalog table, a field
     * whose name a column answers, a field carrying an omitted directive argument for the inlay
     * surface to fill, and a type no table binds. Small on purpose. The costs a surface pays here
     * are the fixed shape of its statement rather than a function of how much schema it was pointed
     * at, which is what makes a constant ceiling meaningful; the one property that does need two
     * sizes says so and builds its own fixtures.
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
            .as("inlay hints over the whole file, every axis enabled")
            .isLessThan(1800);

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

    // ===== Helpers =====

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
        var tables = List.of("film", "actor", "address", "category", "city", "country", "customer",
            "inventory", "language", "payment", "rental", "staff", "store");
        return "type Query { first: T0 }\n" + IntStream.range(0, types)
            .mapToObj(i -> "type T" + i + " @table(name: \"" + tables.get(i % tables.size())
                + "\") { id: ID }\n")
            .reduce("", String::concat);
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
        var executed = new ArrayList<Query>();
        surface.accept(recording(executed));
        assertThat(executed).as("a surface that read nothing would pass every ceiling").isNotEmpty();
        long total = 0;
        for (Query query : executed) {
            total += scans(store.handle(), query);
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
    private static StoreHandle recording(List<Query> into) {
        var configuration = store.handle().dsl().configuration()
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
