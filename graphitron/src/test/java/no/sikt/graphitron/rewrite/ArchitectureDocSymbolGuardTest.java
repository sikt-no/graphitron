package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.ArchitectureDocSymbolScanner.Citation;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: a Java type an architecture page names in a backtick span still exists.
 *
 * <p>There is a direct precedent for the shape and for why it is worth having.
 * {@code SchemaIdentifierDriftCheck} exists because store-identifier citations in these same pages
 * drifted silently, and its javadoc names the consequence: "which is exactly how the old pipeline
 * overview came to describe a retired architecture as current." No sibling resolved the Java
 * symbols the same pages cite, which is how a generator class survived its own deletion in the
 * Source Map for long enough for a survey to find it.
 *
 * <p><b>Why this tier.</b> Resolution needs the generator classes on a classpath, which rules out
 * {@code roadmap-tool}: it depends on {@code graphitron-model} and not on {@code graphitron}, and
 * adding that dependency to make a docs check work is the wrong direction. So the guard sits in the
 * {@code graphitron} test tier beside {@link RoadmapReferenceGuardTest}, and pays for it in the
 * {@code SIBLING_MODULE} exemptions below: a class in a module this one does not depend on cannot
 * be resolved from here, and is exempted by name rather than resolved by a second mechanism.
 *
 * <p>{@link #EXEMPT} is the one list, and it is permanent: each entry claims a span is legitimately
 * not a resolvable type. It carried a second, temporary list beside it while the pages this guard
 * landed against were still being rebuilt; that list burned down to empty and went away with the
 * rebuild, which is what it was built to do.
 *
 * <p>When this guard fires, repoint the citation at the symbol that replaced it, or state the fact
 * without naming a type; do not add an exemption to silence it. {@link #exemptionsAreAllStillCited}
 * keeps the list honest in the other direction: an exemption for text no page carries any more is an
 * unguarded census of its own.
 */
@UnitTier
class ArchitectureDocSymbolGuardTest {

    /** A floor on scanned pages: the tree holds nineteen, and a walk that reached nothing must fail. */
    private static final int MIN_PAGES = 15;

    /** A floor on extracted citations: an extractor that matched nothing would pass vacuously. */
    private static final int MIN_CITATIONS = 400;

    /** A floor on the classpath index: an empty universe would fail every citation, not none. */
    private static final int MIN_INDEXED_TYPES = 5_000;

    /**
     * Spans that are legitimately not a type on this module's classpath. Each entry states which
     * of five reasons applies, so an exemption is a reviewable claim rather than a silent miss.
     *
     * <ul>
     *   <li><b>emitted</b>: a name the generator writes into a consumer's sources. The reactor
     *       never declares it, and the pages name it because that is what a consumer sees.</li>
     *   <li><b>module</b>: a real reactor type in a module this test tier does not depend on. It
     *       exists; this classpath cannot see it.</li>
     *   <li><b>schema</b>: a GraphQL type from a worked example, not a Java type at all.</li>
     *   <li><b>value</b>: an enum constant or axis value. The index holds types, not fields.</li>
     *   <li><b>library</b>: a third-party type not on this module's classpath.</li>
     *   <li><b>rejected</b>: a name the page gives to something deliberately not built. The prose
     *       is accurate and there is no live symbol for it to point at.</li>
     * </ul>
     */
    static final Map<String, String> EXEMPT = exemptions();

    @Test
    void everyCitedTypeResolvesOnTheClasspath() throws IOException {
        Path root = GuardScope.locateRepoRoot();
        Set<String> universe = ArchitectureDocSymbolScanner.classpathTypeNames();
        List<Path> pages = ArchitectureDocSymbolScanner.pages(root);

        assertThat(universe.size())
            .as("the type universe comes from java.class.path; an index this small means the "
                + "classpath was not readable and every citation would fail for the wrong reason")
            .isGreaterThan(MIN_INDEXED_TYPES);

        assertThat(pages.size())
            .as("this guard reaches the docs tree by walking up to the repository root; a page "
                + "count near zero means the root drifted and the guard would pass vacuously")
            .isGreaterThan(MIN_PAGES);

        List<Citation> citations = new ArrayList<>();
        for (Path page : pages) {
            citations.addAll(ArchitectureDocSymbolScanner.scanPage(root, page));
        }

        assertThat(citations.size())
            .as("the extractor found almost no backticked type spans across %d pages, which means "
                + "it stopped matching rather than that the pages stopped citing", pages.size())
            .isGreaterThan(MIN_CITATIONS);

        List<Citation> dangling = citations.stream()
            .filter(c -> !ArchitectureDocSymbolScanner.resolves(c.symbol(), universe))
            .filter(c -> !EXEMPT.containsKey(c.symbol()))
            .toList();

        assertThat(dangling)
            .as("an architecture page names a Java type that no longer exists. Repoint the "
                + "citation at the symbol that replaced it, or state the fact without naming a "
                + "type. Only add an exemption when the span is genuinely not a resolvable type "
                + "(emitted output, another module's internals, a GraphQL type, an enum constant, "
                + "an off-classpath library); never to silence the guard. Offending citations:\n"
                + dangling.stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse(""))
            .isEmpty();
    }

    @Test
    void exemptionsAreAllStillCited() throws IOException {
        Path root = GuardScope.locateRepoRoot();
        Set<String> cited = new LinkedHashSet<>();
        for (Path page : ArchitectureDocSymbolScanner.pages(root)) {
            for (Citation c : ArchitectureDocSymbolScanner.scanPage(root, page)) {
                cited.add(c.symbol());
            }
        }

        Set<String> unused = new TreeSet<>(EXEMPT.keySet());
        unused.removeAll(cited);

        assertThat(unused)
            .as("an exemption for a span no page cites any more is an unguarded census of its own: "
                + "it claims something about text that is gone. Delete these entries: %s", unused)
            .isEmpty();
    }

    private static Map<String, String> exemptions() {
        Map<String, String> exempt = new LinkedHashMap<>();

        // emitted: written into a consumer's sources by a generator, never declared here.
        exempt.put("ColumnFetcher", "emitted: per-app runtime class");
        exempt.put("ConnectionResult", "emitted: per-app runtime class");
        exempt.put("EntityFetcherDispatch", "emitted: per-app federation dispatch class");
        exempt.put("Graphitron", "emitted: per-app entry-point class carrying newExecutionInput");
        exempt.put("GraphitronContext", "emitted: per-app context interface");
        exempt.put("GraphitronContextImpl", "emitted: the consumer's own implementation of it");
        exempt.put("NodeIdEncoder", "emitted: per-app node-id encoder");
        exempt.put("NodeIdStrategy", "emitted: per-app node-id strategy interface");
        exempt.put("OrderByResult", "emitted: per-app order-by result class");
        exempt.put("Outcome", "emitted: error-channel result type in generated output");
        exempt.put("Outcome.Success", "emitted: the success arm of that generated result type");

        // module: a real reactor type in a module this test tier does not depend on.
        exempt.put("BundledLibraryLookup", "module: graphitron-lsp");
        exempt.put("LspTrace", "module: graphitron-lsp");
        exempt.put("NativeLibraryBundleTest", "module: graphitron-lsp");
        exempt.put("StoreAccess", "module: graphitron-lsp");
        exempt.put("TriggerDispatchMatrixTest", "module: graphitron-lsp");
        exempt.put("DiagnosticsAggregateTest", "module: graphitron-mcp");
        exempt.put("SchemaQueries", "module: graphitron-mcp");
        exempt.put("StoreBackedBuild", "module: graphitron-mcp");
        exempt.put("StoreFixture", "module: graphitron-lsp and graphitron-mcp");
        exempt.put("FieldClassification.Conflicted", "module: the LSP/MCP classification projection");
        exempt.put("CatalogRefreshTest", "module: graphitron-maven-plugin");
        exempt.put("DevMojo", "module: graphitron-maven-plugin");
        exempt.put("DevMojoTest", "module: graphitron-maven-plugin");
        exempt.put("ValidateMojo", "module: graphitron-maven-plugin");
        exempt.put("GraphiqlBundle", "module: graphitron-jakarta-rest");
        exempt.put("GraphitronApplication", "module: graphitron-jakarta-rest");
        exempt.put("GraphqlHttpHandler", "module: graphitron-jakarta-rest");
        exempt.put("OperationPolicy", "module: graphitron-jakarta-rest");
        exempt.put("DocSizeBudgetTest", "module: graphitron-sakila-example");
        exempt.put("FederationBuildSmokeTest", "module: graphitron-sakila-example");
        exempt.put("FederationEntitiesDispatchTest", "module: graphitron-sakila-example");
        exempt.put("GeneratedSourcesLintTest", "module: graphitron-sakila-example");
        exempt.put("GeneratedSourcesSmokeTest", "module: graphitron-sakila-example");
        exempt.put("GeneratorDeterminismTest", "module: graphitron-sakila-example");
        exempt.put("GraphQLQueryTest", "module: graphitron-sakila-example");
        exempt.put("IdempotentWriterTest", "module: graphitron-sakila-example");
        exempt.put("NoFederationRegressionTest", "module: graphitron-sakila-example");
        exempt.put("ReadmeLinkIntegrityTest", "module: graphitron-sakila-example");
        exempt.put("ScatterSingleByIdxTest", "module: graphitron-sakila-example");
        exempt.put("SealedHierarchyDocCoverageTest", "module: graphitron-sakila-example");
        exempt.put("SchemaIdentifierDriftCheck", "module: roadmap-tool");

        // schema: a GraphQL type from a worked example, not a Java type at all.
        exempt.put("FilmDetails", "schema: worked-example GraphQL type");
        exempt.put("FilmInput", "schema: worked-example GraphQL input type");
        exempt.put("FilmOrActor", "schema: worked-example GraphQL union");
        exempt.put("FilmStats", "schema: worked-example GraphQL type");
        exempt.put("MediaItem", "schema: worked-example GraphQL interface");
        exempt.put("Sprak", "schema: worked-example GraphQL enum naming a pivot vocabulary");
        exempt.put("TranslatedTexts", "schema: worked-example GraphQL type");
        exempt.put("Foo", "schema: placeholder type in a worked example");
        exempt.put("Int", "schema: GraphQL built-in scalar");
        exempt.put("ExternalCodeReference", "schema: the directives schema's shared input type");

        // rejected: a name the page gives to something deliberately not built. The citation is
        // accurate prose about a decision, so there is no live symbol for it to point at.
        exempt.put("ConditionDirectives", "rejected: a utility class the design considered and did not build");
        exempt.put("NodeIdField", "rejected: an input-field variant argument resolution leaves out of scope");

        // value: an enum constant or axis value. The index holds types, not fields.
        exempt.put("Fetch", "value: an Operation axis constant");
        exempt.put("Nest", "value: an Operation axis constant");

        // library: a third-party type not on this module's classpath.
        exempt.put("JsonWebToken", "library: MicroProfile JWT, reached only from graphitron-jakarta-rest");
        exempt.put("RecordN", "library: jOOQ declares Record1..Record22; RecordN is the family shorthand");

        return exempt;
    }
}
