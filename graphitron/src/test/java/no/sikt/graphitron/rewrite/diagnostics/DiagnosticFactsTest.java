package no.sikt.graphitron.rewrite.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.compile.CompileDiagnostic;
import no.sikt.graphitron.model.compile.CompileRound;
import no.sikt.graphitron.model.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.model.lint.LintFix;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.model.diagnostics.PivotError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import static no.sikt.graphitron.model.test.FactWriters.buildWarningFacts;
import static no.sikt.graphitron.model.test.FactWriters.compileFacts;
import static no.sikt.graphitron.model.test.FactWriters.rejectionFacts;
import static no.sikt.graphitron.model.Tables.BUILD_WARNING_NO_RULE;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.LINT_FINDING;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX_EDIT;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR_DIRECTIVE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnostics stratum's content anchors and derived-column pins, asserted where the DDL
 * says they live: the loaders' typed columns against hand-written expectations (the same
 * snapshot reduced two ways, at the loaders' cadence), and the {@code diagnostic} union view's
 * derived columns against the Java spellings they restate. The parity pins here are the ones
 * the design names: {@code actionable} against the deferred-versus-rest predicate the LSP
 * severity projection documents, the compile arm's {@code severity} against
 * {@link CompileDiagnostic#severity()}, the compile sentinels normalising to the uniform NULL
 * absent bucket, and the pilot arm's rendered {@code message} against the report's own. The file
 * axis has its own pin here too, and it is a property rather than a parity: no arm stores or
 * projects a URI, so no spelling is computed in this stratum at all.
 */
@PipelineTier
class DiagnosticFactsTest {

    private static final String GRAPH = "DiagnosticFactsTest";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the residue's typed columns transcribe each rejection arm's own data")
    void residueTypedColumnsTranscribeTheRejections() {
        String source = tmp.resolve("s.graphqls").toString();
        var loc = new SourceLocation(3, 5, source);
        var errors = List.of(
            ValidationError.forField("Film.id",
                Rejection.unknownColumn("column 'id' could not be resolved", "id", List.of("film_id")), loc),
            ValidationError.forType("Film",
                Rejection.directiveConflict(List.of("splitQuery", "routine"), "conflict"), loc),
            ValidationError.forField("Film.texts",
                Rejection.deferred("computed fields are not generated yet", ChildField.ComputedField.class), loc),
            ValidationError.forField("Film.title",
                new PivotError.NonNullSlot("nn", "TranslatedTexts"), loc),
            new ValidationError(null, Rejection.invalidSchema("schema-wide"), null));
        withStore(dsl -> {
            rejectionFacts(dsl, GRAPH, tmp).write(errors);

            var rows = dsl.selectFrom(REJECTION_VALIDATION_ERROR)
                .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(GRAPH))
                .orderBy(REJECTION_VALIDATION_ERROR.ORDINAL)
                .fetch();
            assertThat(rows).hasSize(5);

            var unknown = rows.get(0);
            assertThat(unknown.getKind()).isEqualTo("AUTHOR_ERROR");
            assertThat(unknown.getVariant()).isEqualTo("Rejection.AuthorError.UnknownName");
            assertThat(unknown.getAttemptKind()).isEqualTo("COLUMN");
            assertThat(unknown.getAttempt()).isEqualTo("id");
            assertThat(unknown.getTypeName()).isEqualTo("Film");
            assertThat(unknown.getFieldName()).isEqualTo("id");
            assertThat(unknown.getFile()).isEqualTo(source);
            assertThat(unknown.getSourceLine()).isEqualTo(3);
            assertThat(unknown.getSourceColumn()).isEqualTo(5);
            assertThat(unknown.getMessage()).startsWith("Field 'Film.id': column 'id'");

            var conflict = rows.get(1);
            assertThat(conflict.getVariant()).isEqualTo("Rejection.InvalidSchema.DirectiveConflict");
            assertThat(conflict.getKind()).isEqualTo("INVALID_SCHEMA");
            assertThat(conflict.getFieldName()).isNull();
            assertThat(dsl.selectFrom(REJECTION_VALIDATION_ERROR_DIRECTIVE)
                .where(REJECTION_VALIDATION_ERROR_DIRECTIVE.GRAPH_NAME.eq(GRAPH),
                    REJECTION_VALIDATION_ERROR_DIRECTIVE.ERROR_ORDINAL.eq(conflict.getOrdinal()))
                .orderBy(REJECTION_VALIDATION_ERROR_DIRECTIVE.POSITION)
                .fetch(r -> r.getDirective()))
                .as("the child keeps the rejection's own order; the sorted render is the view's")
                .containsExactly("splitQuery", "routine");

            var deferred = rows.get(2);
            assertThat(deferred.getKind()).isEqualTo("DEFERRED");
            assertThat(deferred.getStubKey()).isEqualTo("ChildField.ComputedField");

            var coded = rows.get(3);
            assertThat(coded.getVariant()).isEqualTo("PivotError.NonNullSlot");
            assertThat(coded.getLspCode()).isEqualTo(new PivotError.NonNullSlot("nn", "T").lspCode());

            var schemaWide = rows.get(4);
            assertThat(schemaWide.getTypeName()).isNull();
            assertThat(schemaWide.getFieldName()).isNull();
            assertThat(schemaWide.getFile()).isNull();
            assertThat(schemaWide.getSourceLine()).isNull();
        });
    }

    @Test
    @DisplayName("the view's rejection-arm derivations restate the Java spellings")
    void viewDerivationsMatchTheJavaSpellings() {
        String source = tmp.resolve("s.graphqls").toString();
        var loc = new SourceLocation(3, 5, source);
        var errors = List.of(
            ValidationError.forField("Film.id",
                Rejection.structural("author error"), loc),
            ValidationError.forType("Film",
                Rejection.directiveConflict(List.of("splitQuery", "routine"), "conflict"), loc),
            ValidationError.forField("Film.texts", Rejection.deferred("later"), loc));
        withStore(dsl -> {
            rejectionFacts(dsl, GRAPH, tmp).write(errors);
            var rows = dsl.selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH))
                .orderBy(DIAGNOSTIC.COORDINATE)
                .fetch();
            assertThat(rows).hasSize(3);

            // actionable is the deferred-versus-rest CASE over kind, the same predicate the LSP
            // severity projection documents ("Deferred is Error rather than Warning"): exactly
            // the DEFERRED row is not actionable, and every rejection row is severity error.
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getSeverity()).isEqualTo("error");
                assertThat(row.getSource()).isEqualTo("schema");
                assertThat(row.getActionable()).isEqualTo(!"DEFERRED".equals(row.getKind()));
            });
            assertThat(rows.stream().filter(r -> !r.getActionable()).count()).isEqualTo(1);

            var conflict = rows.stream()
                .filter(r -> "Rejection.InvalidSchema.DirectiveConflict".equals(r.getVariant())).findFirst().orElseThrow();
            assertThat(conflict.getCoordinate()).isEqualTo("Film");

            // The directives are rows under the error's key rather than a column on the surface,
            // which is what lets this read ask membership instead of comparing a joined set.
            assertThat(dsl.select(REJECTION_VALIDATION_ERROR_DIRECTIVE.DIRECTIVE)
                    .from(REJECTION_VALIDATION_ERROR_DIRECTIVE)
                    .join(REJECTION_VALIDATION_ERROR)
                    .on(REJECTION_VALIDATION_ERROR.GRAPH_NAME
                            .eq(REJECTION_VALIDATION_ERROR_DIRECTIVE.GRAPH_NAME),
                        REJECTION_VALIDATION_ERROR.ORDINAL
                            .eq(REJECTION_VALIDATION_ERROR_DIRECTIVE.ERROR_ORDINAL))
                    .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(GRAPH),
                        REJECTION_VALIDATION_ERROR.VARIANT.eq(conflict.getVariant()),
                        REJECTION_VALIDATION_ERROR.TYPE_NAME.eq("Film"))
                    .orderBy(REJECTION_VALIDATION_ERROR_DIRECTIVE.POSITION)
                    .fetch(REJECTION_VALIDATION_ERROR_DIRECTIVE.DIRECTIVE))
                .as("the claiming directives, in the rejection's own order")
                .containsExactly("splitQuery", "routine");

            assertThat(conflict.getFile()).isEqualTo(source);
        });
    }

    @Test
    @DisplayName("the warning arms split on the sealed arm, suppression-filtered list in, emit order kept")
    void warningArmsSplitOnTheSealedArm() {
        String source = tmp.resolve("s.graphqls").toString();
        var loc = new SourceLocation(2, 1, source);
        var rule = LintRule.TYPE_NAMES_PASCAL_CASE;
        var warnings = List.<BuildWarning>of(
            BuildWarning.LintFinding.of("located finding", loc, rule),
            new BuildWarning.NoRule("advisory", loc),
            BuildWarning.LintFinding.of("whole-build finding", null, rule));
        withStore(dsl -> {
            buildWarningFacts(dsl, GRAPH, tmp).write(warnings);
            var rows = dsl.selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH))
                .orderBy(DIAGNOSTIC.MESSAGE)
                .fetch();
            assertThat(rows).hasSize(3);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getSeverity()).isEqualTo("warning");
                assertThat(row.getSource()).isEqualTo("schema");
                assertThat(row.getActionable()).isTrue();
                assertThat(row.getKind()).isNull();
            });
            var advisory = rows.stream()
                .filter(r -> "advisory".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(advisory.getLintRule())
                .as("the advisory arm is precisely the warnings no rule tags")
                .isNull();
            var located = rows.stream()
                .filter(r -> "located finding".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(located.getLintRule()).isEqualTo(rule.id());
            assertThat(located.getFile()).isEqualTo(source);
            var wholeBuild = rows.stream()
                .filter(r -> "whole-build finding".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(wholeBuild.getFile())
                .as("a whole-build finding sits in the stated NULL absent bucket")
                .isNull();
        });
    }

    @Test
    @DisplayName("a fix-bearing finding stores its edits in the rule's order; a fix-less one stores none")
    void fixRowsCarryTheRulesOwnEditOrder() {
        String source = tmp.resolve("s.graphqls").toString();
        var rule = LintRule.NO_TYPENAME_PREFIX;
        // Written later-span-first, which is what makes the position column the rule's order rather
        // than a sort of the spans.
        var fix = new LintFix("drop the suffixes", List.of(
            new LintFix.Edit(new SourceLocation(5, 16, source), new SourceLocation(5, 22, source), "y"),
            new LintFix.Edit(new SourceLocation(5, 3, source), new SourceLocation(5, 9, source), "x")));
        var warnings = List.<BuildWarning>of(
            new BuildWarning.LintFinding("fixable", new SourceLocation(5, 3, source), rule,
                java.util.Optional.of(fix)),
            BuildWarning.LintFinding.of("not fixable", new SourceLocation(6, 1, source), rule));
        withStore(dsl -> {
            buildWarningFacts(dsl, GRAPH, tmp).write(warnings);
            var fixes = dsl.selectFrom(LINT_FINDING_FIX)
                .where(LINT_FINDING_FIX.GRAPH_NAME.eq(GRAPH))
                .fetch();
            assertThat(fixes).as("only the fix-bearing finding has a fix row").hasSize(1);
            assertThat(fixes.getFirst().getDescription()).isEqualTo("drop the suffixes");
            assertThat(fixes.getFirst().getFindingOrdinal())
                .as("the fix hangs off its own finding's emit ordinal")
                .isEqualTo(0);
            var edits = dsl.selectFrom(LINT_FINDING_FIX_EDIT)
                .where(LINT_FINDING_FIX_EDIT.GRAPH_NAME.eq(GRAPH))
                .orderBy(LINT_FINDING_FIX_EDIT.POSITION)
                .fetch();
            assertThat(edits).extracting(r -> r.getStartColumn()).containsExactly(16, 3);
            assertThat(edits).extracting(r -> r.getEndColumn()).containsExactly(22, 9);
            assertThat(edits).extracting(r -> r.getReplacement()).containsExactly("y", "x");
            assertThat(edits).allSatisfy(r -> {
                assertThat(r.getStartLine()).isEqualTo(5);
                assertThat(r.getEndLine()).isEqualTo(5);
            });
        });
    }

    @Test
    @DisplayName("the compile arm projects javac's verdict and normalises its sentinels")
    void compileArmProjectsSeverityAndNormalisesSentinels() {
        var located = new CompileDiagnostic("/gen/A.java", 12, 7, "ERROR",
            "compiler.err.cant.resolve", "cannot find symbol");
        var note = new CompileDiagnostic("/gen/A.java", 3, 1, "NOTE", null, "a note");
        var unlocated = new CompileDiagnostic("(no source)", -1, -1, "WARNING", null, "unchecked");
        withStore(dsl -> {
            compileFacts(dsl, GRAPH, tmp).write(new CompileRound(false, List.of(located, note, unlocated)));
            var rows = dsl.selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH), DIAGNOSTIC.SOURCE.eq("compile"))
                .orderBy(DIAGNOSTIC.MESSAGE)
                .fetch();
            assertThat(rows).hasSize(3);
            for (var diagnostic : List.of(located, note, unlocated)) {
                var row = rows.stream()
                    .filter(r -> diagnostic.message().equals(r.getMessage())).findFirst().orElseThrow();
                assertThat(row.getSeverity())
                    .as("the view's CASE restates CompileDiagnostic.severity()")
                    .isEqualTo(diagnostic.severity());
                assertThat(row.getLspCode()).isEqualTo(diagnostic.code());
                assertThat(row.getActionable()).isTrue();
                assertThat(row.getKind()).isNull();
            }
            var sentinel = rows.stream()
                .filter(r -> "unchecked".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(sentinel.getFile())
                .as("javac's (no source) sentinel normalises to the uniform NULL absent bucket")
                .isNull();
            assertThat(sentinel.getSourceLine()).isNull();
            assertThat(sentinel.getSourceColumn()).isNull();
            var locatedRow = rows.stream()
                .filter(r -> "cannot find symbol".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(locatedRow.getFile()).isEqualTo("/gen/A.java");
            assertThat(locatedRow.getSourceLine()).isEqualTo(12);
        });
    }

    @Test
    @DisplayName("the pilot arm carries the report's own message and the canonical file spelling")
    void pilotArmMessageMatchesTheReports() {
        var sdl = """
            type Film @table(name: "film") {
                id: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """;
        Path file = write(tmp, sdl);
        withStore(dsl -> {
            FactCapture.capture(dsl, graph(), SubjectConfig.none(),
                SchemaLoader.load(List.of(SchemaSource.file(file))),
                TestSchemaHelper.attribution(file));
            var expected = AuthoredClaimConflicts.detect(dsl, GRAPH).violations();
            assertThat(expected).hasSize(1);

            var rows = dsl.selectFrom(DIAGNOSTIC).where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH)).fetch();
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getMessage())
                .as("the view's rendered message is byte-identical to the report's for this family")
                .isEqualTo(expected.getFirst().message());
            assertThat(row.getVariant())
                .as("the variant is minted from the rejection's own class, not spelled in SQL")
                .isEqualTo("Rejection.InvalidSchema.DirectiveConflict");
            assertThat(row.getCoordinate()).isEqualTo("Film.id");
            assertThat(row.getFile())
                .as("the pilot arm projects capture's own source name, one spelling with the "
                    + "loaded arms and no conversion between them")
                .isEqualTo(file.toString());
        });
    }

    @Test
    @DisplayName("the SDL-toolchain arms project their verdicts, and the parser arm's variant is a pinned spelling")
    void sdlToolchainArmsProjectTheirVerdicts() throws java.io.IOException {
        // One refusal per stage, and no cascade: the root operation stays in the file that parses,
        // so assembly's only complaint is the dangling reference rather than a missing query root
        // caused by the refused file.
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, "type Extra { id: ID! }\nstrayTokenHere\n");
        Path dangling = tmp.resolve("dangling.graphqls");
        Files.writeString(dangling, "type Query { gone: Nope }\n");

        var sources = List.of(SchemaSource.file(broken), SchemaSource.file(dangling));
        var read = SchemaLoader.parsePerSource(sources);
        var assembly = SchemaAssembly.of(read.registry());
        var verdicts = SdlVerdicts.of(read);
        assertThat(verdicts.syntaxFailures()).hasSize(1);
        assertThat(assembly.errors()).isNotEmpty();

        withStore(dsl -> {
            FactCapture.capture(dsl, false, graph(), SubjectConfig.none(),
                read.registry(), assembly, verdicts,
                SchemaInputAttribution.build(sources.stream().map(f -> SchemaInput.file(f.path())).toList()),
                null, List.of());

            var rows = dsl.selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH), DIAGNOSTIC.SOURCE.eq("schema"))
                .orderBy(DIAGNOSTIC.VARIANT)
                .fetch();
            assertThat(rows).hasSize(2);

            var assemblyRow = rows.stream()
                .filter(row -> "MissingTypeError".equals(row.getVariant()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the schema-error arm should render graphql-java's error class onto variant"));
            assertThat(assemblyRow.getSeverity()).isEqualTo("error");
            assertThat(assemblyRow.getActionable()).isTrue();
            assertThat(assemblyRow.getKind()).as("the Rejection three-way fork does not apply").isNull();
            assertThat(assemblyRow.getLintRule()).isNull();

            // The parser stage has exactly one way to refuse, so the view states its variant as a
            // literal. Nothing in SQL can resolve a Java class name, so the spelling is pinned here
            // or it rots silently the first time the exception is renamed or replaced.
            String parserVariant = graphql.parser.InvalidSyntaxException.class.getSimpleName();
            var parserRow = rows.stream()
                .filter(row -> parserVariant.equals(row.getVariant()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the parser arm's variant should be the literal " + parserVariant));
            assertThat(parserRow.getSeverity()).isEqualTo("error");
            assertThat(parserRow.getActionable()).isTrue();
            assertThat(parserRow.getFile())
                .as("the parser arm projects its stored source name, one spelling with every "
                    + "other arm")
                .isEqualTo(broken.toString());
            assertThat(parserRow.getSourceLine()).isEqualTo(2);
            assertThat(parserRow.getMessage())
                .as("the row carries the parser's own words, explanatory clause included")
                .isEqualTo(verdicts.syntaxFailures().getFirst().verbatimMessage())
                .contains("not been consumed");
        });
    }

    /**
     * The file axis is a path wherever it is stored and wherever the view projects one, so nothing
     * in this stratum computes a spelling and the two wires that name a document by URI render one
     * at their own boundary. Stated over rows in all seven arms, which takes three fixtures rather
     * than one build: the three loaders share a store, while the pilot arm and the SDL toolchain's
     * arms each need their own capture (a capture clears the graph's partition on the way in), and
     * no one build both refuses a source at the parser and reaches javac with it.
     */
    @Test
    @DisplayName("no file column, and no projection of one, spells a file as a URI")
    void noFileColumnSpellsAUri() throws IOException {
        String source = tmp.resolve("s.graphqls").toString();
        var loc = new SourceLocation(3, 5, source);
        withStore(dsl -> {
            rejectionFacts(dsl, GRAPH, tmp).write(List.of(ValidationError.forField("Film.id",
                Rejection.unknownColumn("column 'id' could not be resolved", "id",
                    List.of("film_id")), loc)));
            buildWarningFacts(dsl, GRAPH, tmp).write(List.of(
                BuildWarning.LintFinding.of("finding", loc, LintRule.TYPE_NAMES_PASCAL_CASE),
                new BuildWarning.NoRule("advisory", loc)));
            compileFacts(dsl, GRAPH, tmp).write(new CompileRound(false, List.of(
                new CompileDiagnostic("/gen/A.java", 12, 7, "ERROR", null, "cannot find symbol"))));
            assertEveryFileIsAPath(dsl, 4);
        });

        Path conflict = write(tmp, """
            type Film @table(name: "film") {
                id: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """);
        withStore(dsl -> {
            FactCapture.capture(dsl, graph(), SubjectConfig.none(),
                SchemaLoader.load(List.of(SchemaSource.file(conflict))),
                TestSchemaHelper.attribution(conflict));
            assertEveryFileIsAPath(dsl, 1);
        });

        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, "type Extra { id: ID! }\nstrayTokenHere\n");
        Path dangling = tmp.resolve("dangling.graphqls");
        Files.writeString(dangling, "type Query { gone: Nope }\n");
        var sources = List.of(SchemaSource.file(broken), SchemaSource.file(dangling));
        var read = SchemaLoader.parsePerSource(sources);
        withStore(dsl -> {
            FactCapture.capture(dsl, false, graph(), SubjectConfig.none(),
                read.registry(), SchemaAssembly.of(read.registry()), SdlVerdicts.of(read),
                SchemaInputAttribution.build(sources.stream().map(f -> SchemaInput.file(f.path())).toList()),
                null, List.of());
            assertEveryFileIsAPath(dsl, 2);
        });
    }

    // ===== Helpers =====

    /**
     * Every file this graph holds, gathered from the four columns the arms write and from the view's
     * own {@code file}, and asserted to be a path. The row count is asserted alongside so the case
     * cannot pass by seeding nothing.
     */
    private static void assertEveryFileIsAPath(DSLContext dsl, int expectedViewRows) {
        var files = new java.util.ArrayList<String>();
        files.addAll(dsl.select(REJECTION_VALIDATION_ERROR.FILE).from(REJECTION_VALIDATION_ERROR)
            .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(GRAPH))
            .fetch(REJECTION_VALIDATION_ERROR.FILE));
        files.addAll(dsl.select(LINT_FINDING.FILE).from(LINT_FINDING)
            .where(LINT_FINDING.GRAPH_NAME.eq(GRAPH)).fetch(LINT_FINDING.FILE));
        files.addAll(dsl.select(BUILD_WARNING_NO_RULE.FILE).from(BUILD_WARNING_NO_RULE)
            .where(BUILD_WARNING_NO_RULE.GRAPH_NAME.eq(GRAPH)).fetch(BUILD_WARNING_NO_RULE.FILE));
        files.addAll(dsl.select(JAVAC_DIAGNOSTIC.FILE).from(JAVAC_DIAGNOSTIC)
            .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq(GRAPH)).fetch(JAVAC_DIAGNOSTIC.FILE));
        var view = dsl.selectFrom(DIAGNOSTIC).where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH)).fetch();
        assertThat(view).hasSize(expectedViewRows);
        files.addAll(view.map(row -> row.getFile()));
        assertThat(files.stream().filter(java.util.Objects::nonNull).toList())
            .as("the file axis is a path wherever it is stored or projected; a wire that names a "
                + "document by URI renders one at its own boundary")
            .isNotEmpty()
            .allSatisfy(file -> assertThat(file).doesNotStartWith("file:"));
    }

    private GraphIdentity graph() {
        return new GraphIdentity(GRAPH, tmp);
    }

    private void withStore(java.util.function.Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
