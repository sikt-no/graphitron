package no.sikt.graphitron.rewrite.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;
import no.sikt.graphitron.rewrite.compile.CompileRound;
import no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.PivotError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.schema.SchemaAssembly;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import static no.sikt.graphitron.rewrite.FactWriters.buildWarningFacts;
import static no.sikt.graphitron.rewrite.FactWriters.compileFacts;
import static no.sikt.graphitron.rewrite.FactWriters.rejectionFacts;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
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
 * {@link CompileDiagnostic#severity()}, the {@code canonical_uri} alias against
 * {@link ValidationReport#canonicalUri}, the compile sentinels normalising to the uniform NULL
 * absent bucket, and the pilot arm's rendered {@code message} against the report's own.
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
            String uri = ValidationReport.canonicalUri(source);

            var unknown = rows.get(0);
            assertThat(unknown.getKind()).isEqualTo("AUTHOR_ERROR");
            assertThat(unknown.getVariant()).isEqualTo("Rejection.AuthorError.UnknownName");
            assertThat(unknown.getAttemptKind()).isEqualTo("COLUMN");
            assertThat(unknown.getAttempt()).isEqualTo("id");
            assertThat(unknown.getTypeName()).isEqualTo("Film");
            assertThat(unknown.getFieldName()).isEqualTo("id");
            assertThat(unknown.getFile()).isEqualTo(uri);
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
            assertThat(conflict.getDirectives())
                .as("the canonical render sorts, so claim order cannot split a group")
                .isEqualTo("routine,splitQuery");
            assertThat(conflict.getCoordinate()).isEqualTo("Film");

            String uri = ValidationReport.canonicalUri(source);
            assertThat(conflict.getFile()).isEqualTo(uri);
            assertThat(conflict.getDirectory()).isEqualTo(uri.substring(0, uri.lastIndexOf('/')));
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
            assertThat(located.getFile()).isEqualTo(ValidationReport.canonicalUri(source));
            var wholeBuild = rows.stream()
                .filter(r -> "whole-build finding".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(wholeBuild.getFile())
                .as("a whole-build finding sits in the stated NULL absent bucket")
                .isNull();
            assertThat(wholeBuild.getDirectory()).isNull();
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
        var located = new CompileDiagnostic("file:///gen/A.java", 12, 7, "ERROR",
            "compiler.err.cant.resolve", "cannot find symbol");
        var note = new CompileDiagnostic("file:///gen/A.java", 3, 1, "NOTE", null, "a note");
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
            assertThat(sentinel.getDirectory()).isNull();
            assertThat(sentinel.getSourceLine()).isNull();
            assertThat(sentinel.getSourceColumn()).isNull();
            var locatedRow = rows.stream()
                .filter(r -> "cannot find symbol".equals(r.getMessage())).findFirst().orElseThrow();
            assertThat(locatedRow.getFile()).isEqualTo("file:///gen/A.java");
            assertThat(locatedRow.getDirectory()).isEqualTo("file:///gen");
            assertThat(locatedRow.getSourceLine()).isEqualTo(12);
        });
    }

    @Test
    @DisplayName("the canonical_uri alias restates ValidationReport.canonicalUri, spelling included")
    void canonicalUriAliasMatchesTheJavaSite() {
        withStore(dsl -> {
            for (String path : List.of("/tmp/plain/schema.graphqls", "/tmp/with space/x.graphqls")) {
                String viaAlias = dsl.select(
                        DSL.function("canonical_uri", String.class, DSL.val(path)))
                    .fetchSingle().value1();
                assertThat(viaAlias).isEqualTo(ValidationReport.canonicalUri(path));
            }
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
            FactCapture.capture(dsl, graph(), FactCapture.SubjectConfig.none(),
                RewriteSchemaLoader.load(List.of(SchemaSource.file(file))),
                TestSchemaHelper.attribution(file));
            var expected = AuthoredClaimConflicts.detect(dsl, GRAPH).violations();
            assertThat(expected).hasSize(1);

            var rows = dsl.selectFrom(DIAGNOSTIC).where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH)).fetch();
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getMessage())
                .as("the view's rendered message is byte-identical to the report's for this family")
                .isEqualTo(expected.getFirst().message());
            assertThat(row.getVariant()).isEqualTo("Rejection.InvalidSchema.DirectiveConflict");
            assertThat(row.getDirectives()).isEqualTo("nodeId,service");
            assertThat(row.getCoordinate()).isEqualTo("Film.id");
            assertThat(row.getFile())
                .as("the pilot arm's file goes through the alias, one spelling with the loaded arms")
                .isEqualTo(ValidationReport.canonicalUri(file.toString()));
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
        var read = RewriteSchemaLoader.parsePerSource(sources);
        var assembly = SchemaAssembly.of(read.registry());
        var verdicts = SdlVerdicts.of(read);
        assertThat(verdicts.syntaxFailures()).hasSize(1);
        assertThat(assembly.errors()).isNotEmpty();

        withStore(dsl -> {
            FactCapture.capture(dsl, false, graph(), FactCapture.SubjectConfig.none(),
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
                .as("the parser arm's file goes through the alias, one spelling with every other arm")
                .isEqualTo(ValidationReport.canonicalUri(broken.toString()));
            assertThat(parserRow.getSourceLine()).isEqualTo(2);
            assertThat(parserRow.getMessage())
                .as("the row carries the parser's own words, explanatory clause included")
                .isEqualTo(verdicts.syntaxFailures().getFirst().verbatimMessage())
                .contains("not been consumed");
        });
    }

    // ===== Helpers =====

    private FactCapture.GraphIdentity graph() {
        return new FactCapture.GraphIdentity(GRAPH, tmp);
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
