package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog-aware validation for known directives. Cleans-vs-typo test
 * matrix per directive plus the "no false positives on neutral schema"
 * sanity check.
 */
class DiagnosticsTest {

    @Test
    void unknownTableNameProducesError() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("MISSING").contains("table");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    }

    @Test
    void knownTableNameProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownColumnNameProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TYPO")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains("column");
    }

    @Test
    void javaFieldNameProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TITLE")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).isEmpty();
    }

    @Test
    void sqlColumnNameProducesNoDiagnostic() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownColumnButUnknownTableSuppressesDuplicateField() {
        // The @table is the typo, not the @field; reporting both would
        // double-count one mistake. The @field validator yields here.
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "anything")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("MISSING"));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("MISSING");
    }

    @Test
    void unknownRecordComponentProducesError() {
        // The parent's record-backing comes from the snapshot's name-keyed projection (below), not
        // from any SDL directive, so the @field member validation fires without an applied @record.
        var file = file("""
            input FilmInput {
                bar: Int @field(name: "TYPO")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("FilmInput", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.RecordBacking(
                "com.example.FilmDto",
                java.util.List.of(
                    new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.MemberSlot("filmId", "Integer"),
                    new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.MemberSlot("title", "String")
                )
            )),
        Map.of());
        var diags = compute(file, filmCatalog(), snapshot);

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains("component").contains("com.example.FilmDto");
    }

    @Test
    void knownRecordComponentProducesNoError() {
        var file = file("""
            input FilmInput {
                bar: Int @field(name: "title")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("FilmInput", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.RecordBacking(
                "com.example.FilmDto",
                java.util.List.of(new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.MemberSlot("title", "String"))
            )),
        Map.of());
        var diags = compute(file, filmCatalog(), snapshot);

        assertThat(diags).isEmpty();
    }

    private static LspSchemaSnapshot fooTableBacking(String tableName) {
        return new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("Foo", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.TableBacking(tableName)),
        Map.of());
    }

    // ===== R216 — @field(name:) member validation inside extend type X { ... } =====

    @Test
    void unknownColumnInsideTypeExtensionProducesError() {
        // The AST node DeclarationKind.enclosing returns is the extension; member validation
        // resolves the parent type's backing through the snapshot's name-keyed projection, so
        // even though @table lives on the definition in another file, the diagnostic fires.
        var file = file("""
            extend type Foo {
                bar: Int @field(name: "GHOST")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("GHOST").contains("column");
    }

    @Test
    void knownColumnInsideTypeExtensionProducesNoError() {
        var file = file("""
            extend type Foo {
                bar: Int @field(name: "title")
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).isEmpty();
    }

    // ===== R159 — $source sigil diagnostics =====

    @Test
    void sourceSigil_atCarrierDataField_producesNoDiagnostic() {
        // Admitted carrier-data-field site — $source is valid; no diagnostic.
        var file = file("""
            type FilmListPayload {
                films: [Film!] @field(name: "$source")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("FilmListPayload", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.NoBacking.UnbackedResult()),
            java.util.Map.of("FilmListPayload", "films")
        );
        var diags = compute(file, filmCatalog(), snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void sourceSigil_atNonCarrierSite_producesCanonicalNotDefinedHereDiagnostic() {
        // Parent has a known TypeBackingShape (RecordBacking) but no entry in the carrier
        // projection — the LSP emits the canonical "$source is not defined here" message.
        var file = file("""
            type Foo {
                bar: Int @field(name: "$source")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("Foo", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.RecordBacking(
                "com.example.FooDto", java.util.List.of())),
            java.util.Map.of()
        );
        var diags = compute(file, filmCatalog(), snapshot);

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .isEqualTo(no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilNotDefinedHereMessage());
    }

    @Test
    void sourceSigil_snapshotUncertainty_silent() {
        // No entry for the parent in typesByName AND no entry in carrierDataFieldByType —
        // shape unknown. LSP is silent: no diagnostic emitted even though the user typed
        // $source (we cannot resolve whether the site admits it; defer to the build).
        var file = file("""
            type RenamedMidEdit {
                films: [Film!] @field(name: "$source")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(), java.util.Map.of(), java.util.Map.of());
        var diags = compute(file, filmCatalog(), snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownReferenceKeyProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "NOPE"}])
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("NOPE").contains("foreign key");
    }

    @Test
    void knownReferenceKeyProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void knownReferenceKeyMatchesCaseInsensitively() {
        // Mirrors JooqCatalog.findForeignKeyByName, which the runtime
        // resolver uses with equalsIgnoreCase. The LSP must not flag a
        // lowercased FK name the generator would accept.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film__film_language_id_fkey"}])
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownReferenceTableProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "GHOST"}])
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("GHOST");
    }

    // ===== R224 — @field(name:) on @reference path field validates against terminal table =====

    @Test
    void inputTableWithReferencePathValidatesAgainstTerminalTable() {
        // The enclosing @table is "film"; the @reference path navigates to "language";
        // the column "NAME" exists on "language" but not on "film". Pre-R224 the LSP
        // checked the enclosing type's @table and emitted a false-positive
        // "Unknown column 'NAME' on table 'film'."
        var file = file("""
            input FilmInput @table(name: "film") {
                languageName: String @field(name: "NAME") @reference(path: [{table: "language"}])
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("FilmInput", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("FilmInput.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ColumnReference(
                    "language", "NAME", java.util.List.of())),
            java.util.Map.of()
        );
        var diags = compute(file, filmAndLanguageCatalogWithLanguageName(), snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void outputTableWithReferencePathValidatesAgainstTerminalTable() {
        // Mirror on an output type declaration — covers the
        // ChildField.ColumnReferenceField projection arm of projectFieldClassification.
        var file = file("""
            type Film @table(name: "film") {
                languageName: String @field(name: "NAME") @reference(path: [{table: "language"}])
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("Film", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("Film.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.ColumnReference(
                    "language", "NAME", java.util.List.of())),
            java.util.Map.of()
        );
        var diags = compute(file, filmAndLanguageCatalogWithLanguageName(), snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void unresolvedReferencePathColumnSilentOnLspSide() {
        // Classifier could not assign a variant (Unclassified). The validator's
        // ValidationReport emits a precise "no column reachable via @reference path"
        // message; the LSP must not emit a duplicate "Unknown column ... on table '<enclosing>'"
        // diagnostic naming the wrong table.
        var file = file("""
            input FilmInput @table(name: "film") {
                languageName: String @field(name: "TYPO") @reference(path: [{table: "language"}])
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("FilmInput", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.TableBacking("film")),
            java.util.Map.of(),
            java.util.Map.of("FilmInput.languageName",
                new no.sikt.graphitron.rewrite.catalog.FieldClassification.Unclassified("synthetic test reason")),
            java.util.Map.of()
        );
        var diags = compute(file, filmAndLanguageCatalogWithLanguageName(), snapshot);

        assertThat(diags).noneMatch(d -> d.getMessage().contains("Unknown column"));
    }

    @Test
    void emptyArgumentValueProducesNoError() {
        // Mid-edit state: cursor sits in an empty quoted value. We
        // suggest completions but do not yelp at the empty string.
        var file = file("""
            type Foo @table(name: "") {
                bar: Int @field(name: "")
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void diagnosticRangeCoversTheArgumentValueWithQuotes() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        var d = diags.get(0);
        // The reported range should sit on line 0 of the source.
        assertThat(d.getRange().getStart().getLine()).isZero();
        // Range covers the quoted token (start before opening quote, end after).
        assertThat(d.getRange().getStart().getCharacter())
            .isLessThan(d.getRange().getEnd().getCharacter());
    }

    @Test
    void unknownServiceClassProducesError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.Missing", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing").contains("class");
    }

    @Test
    void unknownConditionClassProducesError() {
        var file = file("""
            type Query {
                x: Int @condition(condition: {className: "com.example.Missing", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealCondition"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void recordClassName_carveOut_producesNoUnknownClassError() {
        // R307: @record is deprecated and ignored — its className binds no class, so an unresolvable
        // className raises no "Unknown class" diagnostic (the carve-out gates on the enclosing
        // directive name). The same coordinate under @enum/@service still validates.
        var file = file("""
            input FooInput @record(record: {className: "com.example.Missing"}) {
                bar: Int
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealRecord"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void knownServiceClassProducesNoError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.RealService", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownMethodOnKnownClassProducesError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "ghost"})
            }
            """);

        var diags = compute(file, classWithListMethod(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("ghost").contains("FilmService");
    }

    @Test
    void knownMethodOnKnownClassProducesNoError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var diags = compute(file, classWithListMethod(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    private static CompletionData classWithListMethod() {
        var listMethod = new CompletionData.Method("list", "List", "", List.of());
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.FilmService", "com.example.FilmService", "",
                List.of(listMethod)
            , List.of()))
        );
    }

    @Test
    void methodWithNullParameterNamesProducesParametersWarning() {
        // Method takes one parameter, but parameter name is null
        // (consumer compiled the class without -parameters).
        var method = new CompletionData.Method(
            "list", "List", "",
            List.of(new CompletionData.Parameter(null, "int", null, ""))
        );
        var catalog = new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.FilmService", "com.example.FilmService", "",
                List.of(method)
            , List.of()))
        );
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var diags = compute(file, catalog, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
        assertThat(diags.get(0).getMessage()).contains("-parameters");
    }

    @Test
    void methodWithNoParametersDoesNotProduceParametersWarning() {
        var method = new CompletionData.Method("get", "String", "", List.of());
        var catalog = new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.FilmService", "com.example.FilmService", "",
                List.of(method)
            , List.of()))
        );
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "get"})
            }
            """);

        var diags = compute(file, catalog, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownExternalFieldClassProducesError() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {className: "com.example.Missing"})
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing").contains("class");
    }

    @Test
    void unknownEnumClassProducesError() {
        var file = file("""
            enum Foo @enum(enumReference: {className: "com.example.Missing"}) { A B }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealEnum"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void unknownTableMethodClassProducesError() {
        var file = file("""
            type Foo {
                bar: Int @tableMethod(className: "com.example.Missing", method: "foo")
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealTableMethod"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void unknownSourceRowClassProducesError() {
        // @sourceRow has flat className/method directive args; the canonical
        // overlay binds @sourceRow(className:) → ClassNameBinding so the
        // same validator that fires inside ExternalCodeReference fires here
        // too. Closes the R110 gap that the old hand-coded
        // DirectiveDefinitions.ENTRIES list left silent.
        var file = file("""
            type Foo {
                bar: Int @sourceRow(className: "com.example.Missing", method: "foo")
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealLifter"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void unknownReferencePathConditionClassProducesError() {
        var file = file("""
            type Foo {
                bar: Int @reference(path: [{condition: {className: "com.example.Missing", method: "foo"}}])
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealCondition"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void knownExternalFieldClassProducesNoError() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {className: "com.example.RealService", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.RealService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownTableMethodMethodOnKnownClassProducesError() {
        var file = file("""
            type Foo {
                bar: Int @tableMethod(className: "com.example.FilmService", method: "ghost")
            }
            """);

        var diags = compute(file, classWithListMethod(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("ghost");
    }

    @Test
    void emptyExternalReferencesSuppressesUnknownClassDiagnostic() {
        // Pre-`mvn compile` state: the scanner has nothing yet. Reporting
        // every reference as unknown in that state would be noise.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.RealService", method: "foo"})
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());  // empty externalReferences

        assertThat(diags).isEmpty();
    }

    // Legacy `name:` arms on ExternalCodeReference. The runtime resolves
    // `name:` via RewriteContext.namedReferences to a fully-qualified class.
    // The LSP mirrors that lookup: when `name:` resolves, no diagnostic
    // (the build-tier WARN is the migration-tracking signal); when it
    // doesn't, an error mirroring FieldBuilder.parseExternalRef's
    // lookupError arm. Coverage extends to all eight ExternalCodeReference
    // bindings (one fixture per site).

    @Test
    void legacyName_resolves_emitsNoDiagnostic() {
        var file = file("""
            type Query {
                x: Int @service(service: {name: "FilmService", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of("FilmService", "com.example.FilmService"), "com.example.FilmService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void legacyName_unresolved_service_messageNamesNameAndPointsAtFixes() {
        var file = file("""
            type Query {
                x: Int @service(service: {name: "Ghost", method: "foo"})
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of("FilmService", "com.example.FilmService"), "com.example.FilmService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        var d = diags.get(0);
        assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
        assertThat(d.getMessage())
            .contains("'Ghost'")
            .contains("namedReferences")
            .contains("className");
    }

    @Test
    void legacyName_unresolved_externalField() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {name: "Ghost"})
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of(), "com.example.RealService"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("'Ghost'");
    }

    @Test
    void legacyName_unresolved_enum() {
        var file = file("""
            enum Foo @enum(enumReference: {name: "Ghost"}) { A B }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of(), "com.example.RealEnum"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("'Ghost'");
    }

    // legacyName_unresolved_tableMethod removed: after R43 the @tableMethod directive
    // is flat (className: + method: directly on the directive) and does not carry the
    // deprecated `name:` alias.

    @Test
    void legacyName_record_carveOut_producesNoError() {
        // R307: @record is deprecated and ignored, so the legacy ExternalCodeReference.name →
        // className alias nudge is dead tooling for it too (it would only push the author toward a
        // className the directive no longer reads). The carve-out suppresses it, mirroring the
        // className-tooling carve-out; the same legacy alias under @service/@condition still warns.
        var file = file("""
            type Foo @record(record: {name: "Ghost"}) {
                bar: Int
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of(), "com.example.RealRecord"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void legacyName_unresolved_condition() {
        var file = file("""
            type Foo {
                bar: Int @condition(condition: {name: "Ghost"})
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of(), "com.example.RealCondition"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("'Ghost'");
    }

    @Test
    void legacyName_unresolved_referencePathCondition() {
        var file = file("""
            type Foo {
                bar: Int @reference(path: [{condition: {name: "Ghost"}}])
            }
            """);

        var diags = compute(file, catalogWithNamedReferences(
            Map.of(), "com.example.RealCondition"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("'Ghost'");
    }

    @Test
    void unknownDirectiveProducesWarning() {
        // Built.Current with no user-declared directives mimics the
        // post-build state on a schema that does not define @tabel: the
        // typo lands in the warn arm because the snapshot rules out the
        // "user declared it" branch.
        var file = file("""
            type Foo @tabel(name: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of()));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("@tabel").contains("Unknown directive");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void unknownDirectiveSilencedByUnavailableSnapshot() {
        // Pre-build state: the dev pipeline has not produced a snapshot yet,
        // so any unknown directive could resolve to a user declaration on
        // the next build. Silence avoids punishing the user for a typo that
        // might actually be their own `@auth` / `@key` / similar.
        var file = file("""
            type Foo @tabel(name: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownDirectiveSilencedByStaleSnapshot() {
        // Stale snapshot (parse failed after a prior success). Even when the
        // snapshot does not contain the unknown directive, the warn arm
        // silences: a typo introduced in the same edit that broke the parse
        // is dominated by the parse error itself, and the user will fix
        // that first. Pins the silence-on-Previous trade so any future
        // policy flip surfaces here.
        var file = file("""
            type Foo @tabel(name: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Previous(List.of(), Map.of(), Map.of()));

        assertThat(diags).isEmpty();
    }

    @Test
    void userDeclaredDirectiveSilencedBySnapshot() {
        // Canonical case in R139's motivation: federation directives,
        // @auth-style guards, etc. land in the snapshot and the
        // unknown-directive arm silences instead of pelting one warning per
        // use.
        var keyShape = new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "key",
            List.of(new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                "fields",
                new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", true),
                java.util.Optional.empty())),
            java.util.Optional.empty());
        var file = file("""
            type Film @key(fields: "id") {
                id: ID
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(keyShape), Map.of(), Map.of()));

        assertThat(diags).isEmpty();
    }

    @Test
    void userDeclaredDirectiveShadowedByBundledStillValidates() {
        // Collision case: the user accidentally redeclares @table. The
        // bundled SDL wins (overlay binds @table(name:) to the catalog), so
        // the existing arg-validation arm still flags missing_table even
        // though the snapshot also carries the same name.
        var shadowTable = new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "table",
            List.of(new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                "name",
                new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", false),
                java.util.Optional.empty())),
            java.util.Optional.empty());
        var file = file("""
            type Foo @table(name: "missing_table") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(shadowTable), Map.of(), Map.of()));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("missing_table").contains("table");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    }

    @Test
    void specBuiltinDirectivesAreNotFlagged() {
        // @deprecated is a GraphQL spec built-in; it appears in user
        // schemas but not in graphitron's bundled directives.graphqls.
        // The unknown-directive validator skips spec built-ins.
        var file = file("""
            type Foo @table(name: "film") {
                old: Int @deprecated(reason: "use new")
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownTopLevelArgProducesWarning() {
        var file = file("""
            type Foo @table(neme: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        // No required-arg miss because @table(name:) is optional.
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("'neme'").contains("Unknown argument").contains("@table");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void unknownNestedInputFieldProducesWarning() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{tabel: "x"}])
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("'tabel'").contains("ReferenceElement");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void missingRequiredArgProducesWarning() {
        // @field(name: String!) — the name arg is required.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("Missing required argument").contains("'name'").contains("@field");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void presentRequiredArgProducesNoWarning() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TITLE")
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    // R101 Phase 4 — @scalarType(scalar:) diagnostics.

    @Test
    void scalarType_malformedReference_producesError() {
        var file = file("""
            scalar Money @scalarType(scalar: "NoDotsHere")
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.Scalars"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
        assertThat(diags.get(0).getMessage())
            .contains("NoDotsHere")
            .contains("fully.qualified.Class.FIELD");
    }

    @Test
    void scalarType_trailingDot_producesError() {
        var file = file("""
            scalar Money @scalarType(scalar: "com.example.Scalars.")
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.Scalars"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("fully.qualified.Class.FIELD");
    }

    @Test
    void scalarType_unknownClass_producesError() {
        var file = file("""
            scalar Money @scalarType(scalar: "com.example.Missing.MONEY")
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.Scalars"), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
        assertThat(diags.get(0).getMessage())
            .contains("com.example.Missing")
            .contains("@scalarType");
    }

    @Test
    void scalarType_knownClass_producesNoDiagnostic() {
        var file = file("""
            scalar Money @scalarType(scalar: "com.example.Scalars.MONEY")
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.Scalars"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void scalarType_emptyValueProducesNoDiagnostic() {
        // Mid-edit state: empty quoted value. Completion fires, diagnostics
        // stay quiet so the user is not yelled at while still typing.
        var file = file("""
            scalar Money @scalarType(scalar: "")
            """);

        var diags = compute(file, catalogWithKnownClass("com.example.Scalars"), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void scalarType_emptyExternalReferencesSuppressesUnknownClass() {
        // Pre-`mvn compile` state: scanner saw nothing. Reporting every
        // reference as unknown would be noise; defer to the build-tier
        // resolver. Mirrors the @service / @condition class-name policy.
        var file = file("""
            scalar Money @scalarType(scalar: "com.example.Missing.MONEY")
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());  // empty externalReferences

        assertThat(diags).isEmpty();
    }

    // ---- R142 phase 2: arg validation on user-declared directives. ----

    @Test
    void userDirectiveUnknownTopLevelArg_warns() {
        // @auth(rle: "admin") against a snapshot that declares @auth(role: String!).
        // Warns on `rle`. The typo also leaves `role` absent, so the
        // required-arg arm fires a second warning — parallel to the bundled
        // path's behaviour on the same shape.
        var file = file("""
            type Query {
                customers: [String!]! @auth(rle: "admin")
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(authShape()), Map.of(), Map.of()));

        assertThat(diags).hasSize(2);
        assertThat(diags).extracting(d -> d.getMessage())
            .anyMatch(m -> m.contains("'rle'") && m.contains("Unknown argument") && m.contains("@auth"))
            .anyMatch(m -> m.contains("Missing required argument") && m.contains("'role'"));
        assertThat(diags).allMatch(d -> d.getSeverity() == DiagnosticSeverity.Warning);
    }

    @Test
    void userDirectiveMissingRequiredArg_warns() {
        var file = file("""
            type Query {
                customers: [String!]! @auth
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(authShape()), Map.of(), Map.of()));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("Missing required argument").contains("'role'").contains("@auth");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void userDirectivePresentRequiredArg_silent() {
        var file = file("""
            type Query {
                customers: [String!]! @auth(role: "admin")
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(authShape()), Map.of(), Map.of()));

        assertThat(diags).isEmpty();
    }

    @Test
    void userDirectiveUnknownArgUnderUnavailableSnapshot_silent() {
        // Pre-build state: no snapshot to consult. The typo is silenced
        // even though it would warn under Built.Current.
        var file = file("""
            type Query {
                customers: [String!]! @auth(rle: "admin")
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void userDirectiveUnknownArgUnderPreviousSnapshot_silent() {
        // Stale-snapshot silence — same R139 trade applied to phase 2.
        var file = file("""
            type Query {
                customers: [String!]! @auth(rle: "admin")
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Previous(List.of(authShape()), Map.of(), Map.of()));

        assertThat(diags).isEmpty();
    }

    @Test
    void bundledArgValidationStillFires_evenWhenSnapshotShadows() {
        // Snapshot carries a different-shape @table; bundled-precedence
        // means bundled arg validation still runs. The shadow's args do
        // not leak into the bundled path.
        var shadowTable = new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "table",
            List.of(new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                "differentArg",
                new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", true),
                java.util.Optional.empty())),
            java.util.Optional.empty());
        var file = file("""
            type Foo @table(neme: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, filmCatalog(),
            new LspSchemaSnapshot.Built.Current(List.of(shadowTable), Map.of(), Map.of()));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("'neme'").contains("Unknown argument").contains("@table");
    }

    // R100 — @node(keyColumns:) and @nodeId(typeName:) diagnostics.

    @Test
    void nodeKeyColumns_unknownElement_producesError() {
        // One valid element, one typo'd element. Exactly one diagnostic,
        // on the typo'd element node — the leaf walk fans the list out
        // into per-element leaves and CatalogColumnBinding dispatches on
        // each independently.
        var file = file("""
            type Foo implements Node @table(name: "film") @node(keyColumns: ["TITLE", "GHOST"]) {
                id: ID
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("GHOST").contains("column");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    }

    @Test
    void nodeKeyColumns_allValid_producesNoError() {
        var file = file("""
            type Foo implements Node @table(name: "film") @node(keyColumns: ["FILM_ID", "TITLE"]) {
                id: ID
            }
            """);

        var diags = compute(file, filmCatalog(), fooTableBacking("film"));

        assertThat(diags).isEmpty();
    }

    @Test
    void nodeIdTypeName_unknownType_producesError() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Ghost")): Int
            }
            """);

        var diags = compute(file, nodeCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Ghost").contains("@node");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    }

    @Test
    void nodeIdTypeName_knownNodeType_producesNoError() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Film")): Int
            }
            """);

        var diags = compute(file, nodeCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void nodeIdTypeName_emptyNodeMetadata_suppressesUnknownTypeDiagnostic() {
        // Pre-build state: no @node types known. Defer to the build-tier
        // rejection rather than yelp at every @nodeId site.
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Ghost")): Int
            }
            """);

        var diags = compute(file, filmCatalog(), LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    private static CompletionData nodeCatalog() {
        var film = new CompletionData.Table(
            "film", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                CompletionData.Column.of("TITLE", "String", false, "")
            ),
            List.of()
        );
        return new CompletionData(
            List.of(film),
            List.of(),
            List.of(),
            Map.of(),
            Map.of("Film", new CompletionData.NodeMetadata("Film", List.of("FILM_ID")))
        );
    }

    private static no.sikt.graphitron.rewrite.catalog.DirectiveShape authShape() {
        return new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "auth",
            List.of(new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                "role",
                new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", true),
                java.util.Optional.empty())),
            java.util.Optional.empty());
    }

    private static CompletionData catalogWithKnownClass(String fqn) {
        // The class diagnostic now also validates the sibling `method:` slot
        // when the class resolves; include the method names referenced by
        // the per-test happy paths so unrelated diagnostics don't fire.
        var foo = new CompletionData.Method("foo", "String", "", List.of());
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(fqn, fqn, "", List.of(foo), List.of()))
        );
    }

    private static CompletionData catalogWithNamedReferences(
        Map<String, String> namedReferences, String knownClass
    ) {
        // Non-empty externalReferences satisfies the gate in
        // validateExternalCodeReference; namedReferences is what the
        // legacy `name:` arm queries.
        var foo = new CompletionData.Method("foo", "String", "", List.of());
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(knownClass, knownClass, "", List.of(foo), List.of())),
            namedReferences
        );
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    /**
     * Test-only forwarder that omits the URI and validator report. Every test in this class
     * exercises the SDL-only directive walks; the validator slice is covered by
     * {@link ValidatorDiagnosticsTest}. Threading an empty URI and {@link ValidationReport#empty()}
     * here keeps the call sites focused on the directive arm under test without committing
     * production callers to a backward-compat overload.
     */
    private static List<org.eclipse.lsp4j.Diagnostic> compute(
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot
    ) {
        return Diagnostics.compute("", file, catalog, snapshot, ValidationReport.empty());
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                CompletionData.Column.of("TITLE", "String", false, "")
            ),
            List.of(
                CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false)
            )
        );
        var language = new CompletionData.Table(
            "language", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }

    /**
     * R224 — variant of {@link #filmCatalog()} where {@code language} carries the
     * {@code NAME} column. Lets the regression tests demonstrate the @reference path
     * retarget: {@code NAME} exists on {@code language} (the path's terminal) but not
     * on {@code film} (the enclosing type's @table).
     */
    private static CompletionData filmAndLanguageCatalogWithLanguageName() {
        var film = new CompletionData.Table(
            "film", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                CompletionData.Column.of("TITLE", "String", false, "")
            ),
            List.of(
                CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false)
            )
        );
        var language = new CompletionData.Table(
            "language", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                CompletionData.Column.of("LANGUAGE_ID", "Integer", false, ""),
                CompletionData.Column.of("NAME", "String", false, "")
            ),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
