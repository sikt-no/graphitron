package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planted regressions for the doc-symbol guard, in both directions: a name that resolves must
 * pass, a name that does not must fail, and an exempted span must pass for its stated reason.
 * A guard nobody has watched fail is a guard nobody knows fires.
 *
 * <p>The extractor's ignore rule is the risk in this mechanism, and getting it wrong in either
 * direction costs something: too eager and a method call or a SQL fragment reads as a type, too
 * shy and a real dangling class walks through. The {@code notATypeCitation} cases pin the shy
 * side against the spans the survey found in the pages, and {@code isATypeCitation} the eager one.
 */
@UnitTier
class ArchitectureDocSymbolScannerTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "LEFT JOIN",              // SQL, and a space rules it out
        "util/",                  // a directory
        "graphitron/src/main",    // a path
        "PAGE_CANDIDATES",        // a constant, not a type
        "SQL",                    // a bare acronym
        "@table",                 // a directive
        "intent_bound_table",     // a store relation
        "--mode=migration",       // a command-line flag
    })
    void notATypeCitation(String span) {
        assertThat(ArchitectureDocSymbolScanner.symbolOf(span))
            .as("`%s` is not a claim about a Java type and must not reach resolution", span)
            .isNull();
    }

    @Test
    void isATypeCitation() {
        // A method call keeps its receiver and loses its tail.
        assertThat(ArchitectureDocSymbolScanner.symbolOf("SelectedField.getArguments()")).isEqualTo("SelectedField");
        // A nested type keeps the qualification the page wrote.
        assertThat(ArchitectureDocSymbolScanner.symbolOf("AuthorError.TypeConflict")).isEqualTo("AuthorError.TypeConflict");
        // Generics and arrays are stripped.
        assertThat(ArchitectureDocSymbolScanner.symbolOf("RowN<...>")).isEqualTo("RowN");
        assertThat(ArchitectureDocSymbolScanner.symbolOf("GraphitronField[]")).isEqualTo("GraphitronField");
        // A plain type is itself.
        assertThat(ArchitectureDocSymbolScanner.symbolOf("LauncherRelation")).isEqualTo("LauncherRelation");
    }

    @Test
    void aLiveTypeResolvesAndADeletedOneDoesNot() {
        Set<String> universe = ArchitectureDocSymbolScanner.classpathTypeNames();

        assertThat(ArchitectureDocSymbolScanner.resolves("LauncherRelation", universe))
            .as("a class the reactor declares must resolve, or the guard fails on everything")
            .isTrue();
        assertThat(ArchitectureDocSymbolScanner.resolves("String", universe))
            .as("a JDK type must resolve through the class loader; it is not on java.class.path")
            .isTrue();

        for (String deleted : List.of("ColumnFetcherClassGenerator", "InputDirectiveInputTypes", "FetchRelated")) {
            assertThat(ArchitectureDocSymbolScanner.resolves(deleted, universe))
                .as("`%s` is one of the names the architecture survey found dangling; if it "
                    + "resolves now, the class came back and the burn-down entry is stale", deleted)
                .isFalse();
        }
    }

    @Test
    void aPlantedDanglingCitationIsFound() throws IOException {
        Path root = GuardScope.locateRepoRoot();
        Path page = Files.createTempFile("planted", ".adoc");
        try {
            Files.writeString(page, """
                = Planted

                The `LauncherRelation` row is real, and `NoSuchTypeAnywhereInTheReactor` is not.
                """);
            var citations = ArchitectureDocSymbolScanner.scanPage(page.getParent(), page);
            Set<String> universe = ArchitectureDocSymbolScanner.classpathTypeNames();

            assertThat(citations).extracting(ArchitectureDocSymbolScanner.Citation::symbol)
                .containsExactly("LauncherRelation", "NoSuchTypeAnywhereInTheReactor");
            assertThat(citations.stream()
                .filter(c -> !ArchitectureDocSymbolScanner.resolves(c.symbol(), universe))
                .map(ArchitectureDocSymbolScanner.Citation::symbol))
                .as("only the planted name must fail to resolve")
                .containsExactly("NoSuchTypeAnywhereInTheReactor");
        } finally {
            Files.deleteIfExists(page);
        }
        assertThat(root).isNotNull();
    }

    @Test
    void everyExemptionStatesOneOfTheFiveReasons() {
        assertThat(ArchitectureDocSymbolGuardTest.EXEMPT).isNotEmpty();
        assertThat(ArchitectureDocSymbolGuardTest.EXEMPT.values())
            .as("an exemption's reason is what makes it reviewable; each must name its category")
            .allSatisfy(reason -> assertThat(reason)
                .matches("^(emitted|module|schema|value|library|rejected): .+"));
    }

    @Test
    void everyBaselineEntryStatesWhatIsWrong() {
        assertThat(ArchitectureDocSymbolGuardTest.KNOWN_DANGLING.values())
            .as("a burn-down entry that does not say what the citation should become is a "
                + "suppression wearing a different name")
            .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void theStagedDocsCopyIsNotScannedTwice() throws IOException {
        Path root = GuardScope.locateRepoRoot();
        List<Path> pages = ArchitectureDocSymbolScanner.pages(root);
        assertThat(pages)
            .as("the docs module stages a rendered copy of this tree under target/; scanning it "
                + "would double every finding and every count")
            .allSatisfy(p -> assertThat(p.toString()).doesNotContain("/target/"));
    }
}
