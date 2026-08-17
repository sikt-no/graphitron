package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Census-aware validation for known directives. Cleans-vs-typo test
 * matrix per directive plus the "no false positives on neutral schema"
 * sanity check.
 *
 * <p>Every value arm reads the fact store, so the fixtures are captures rather than hand-built
 * projections: the {@code sql_} arms read the fixture module's real generated jOOQ model, the
 * {@code jvm_} arms a real class list, the {@code @node} arm a real capture of SDL that declares one.
 * Four stores cover the whole matrix and are captured once, since what separates the cases is which
 * census is populated rather than what is in it. A case whose subject is the resolution of a site in
 * the document under validation captures that document instead; see {@link #computeCaptured}.
 */
class DiagnosticsTest {

    /** The schema is beside the point in the shared fixtures; each case's own buffer is the subject. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    /**
     * The catalog fixture's schema, which is not beside the point: what a type's members resolve
     * against is the store's answer now, so a case validating a column name needs a graph where the
     * type its buffer declares is bound to a table. {@code Foo} is the name those buffers use.
     */
    private static final String TABLE_SDL = """
        type Query { film: Foo }
        type Foo @table(name: "film") { title: String }
        """;

    /**
     * The one shared fixture whose schema is not beside the point: which class backs a type is the
     * store's answer now, so a case validating a member name against a backing class needs a graph
     * where a producer grounds the type its buffer declares.
     */
    private static final String BACKED_SDL = """
        type Query {
            card: FilmCard @service(service: {className: "no.sikt.graphitron.lsp.fixtures.R157Service", method: "makeFilmRecord"})
        }
        type FilmCard { title: String }
        """;

    @TempDir
    Path tmp;

    @TempDir
    static Path catalogRoot;
    @TempDir
    static Path multiSchemaRoot;
    @TempDir
    static Path classesRoot;
    @TempDir
    static Path backingRoot;
    @TempDir
    static Path nodesRoot;

    /** The generated catalog and nothing else, over a graph that binds {@code Foo} to {@code film}. */
    private static StoreFixture catalogOnly;
    /** The two-schema generated model, where a constraint name stops identifying one key. */
    private static StoreFixture multiSchema;
    /** The catalog plus the class census the class-name, method and scalar arms resolve against. */
    private static StoreFixture withClasses;
    /** The catalog plus the backing-class fixtures, whose members the class-backed arms read. */
    private static StoreFixture withBackingClasses;
    /** A graph whose SDL declares a {@code @node} type, and one that declares none. */
    private static StoreFixture withNodes;

    @BeforeAll
    static void capture() {
        catalogOnly = StoreFixture.ofCatalog(catalogRoot, TABLE_SDL);
        multiSchema = StoreFixture.ofMultiSchemaCatalog(multiSchemaRoot, PLACEHOLDER_SDL);
        withClasses = StoreFixture.ofCatalog(classesRoot, PLACEHOLDER_SDL, classCensus());
        withBackingClasses = StoreFixture.ofCatalog(backingRoot, BACKED_SDL,
            StoreFixture.backingClasses());
        withNodes = StoreFixture.of(nodesRoot, """
            type Query { x: Int }
            type Film @node(typeId: "Film") { id: ID }
            """);
    }

    @AfterAll
    static void closeStores() {
        catalogOnly.close();
        multiSchema.close();
        withClasses.close();
        withBackingClasses.close();
        withNodes.close();
    }

    /**
     * The classes the class-name, method and scalar arms name. Each carries {@code foo}, the method
     * the happy paths reference, so a case about a class name does not trip the sibling method arm;
     * {@code FilmService} carries the two the method cases name and, deliberately, no {@code ghost}.
     */
    private static List<CompletionData.ExternalReference> classCensus() {
        var foo = List.of(StoreFixture.method("foo", "String"));
        return List.of(
            StoreFixture.jarClass("com.example.RealService", foo),
            StoreFixture.jarClass("com.example.RealCondition", foo),
            StoreFixture.jarClass("com.example.RealRecord", foo),
            StoreFixture.jarClass("com.example.RealEnum", foo),
            StoreFixture.jarClass("com.example.RealLifter", foo),
            StoreFixture.scalarHolder("com.example.Scalars", "MONEY"),
            StoreFixture.jarClass("com.example.FilmService", List.of(
                StoreFixture.method("list", "List"),
                StoreFixture.method("get", "String"))));
    }

    @Test
    void unknownTableNameProducesError() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownColumnNameProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TYPO")
            }
            """);

        var diags = compute(file, catalogOnly, noBackings());

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

        var diags = compute(file, catalogOnly, noBackings());

        assertThat(diags).isEmpty();
    }

    @Test
    void sqlColumnNameProducesNoDiagnostic() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);

        var diags = compute(file, catalogOnly, noBackings());

        assertThat(diags).isEmpty();
    }

    /**
     * The {@code @table} is the typo, not the {@code @field}, and only the real mistake is reported.
     * Nothing suppresses the second one: a binding that resolves to no table scopes the type to
     * nothing, so there is no column list for the member name to be absent from. Its own capture,
     * because the subject is what the store makes of this document's own binding.
     */
    @Test
    void unknownColumnButUnknownTableSuppressesDuplicateField() {
        String sdl = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "anything")
            }
            type Query { foo: Foo }
            """;

        var diags = computeCaptured(sdl);

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("MISSING");
    }

    @Test
    void unknownRecordComponentProducesError() {
        // Two answers meet here and neither is an SDL directive on the buffer, which is why the arm
        // fires without an applied @record. That the parent resolves to a class, and which class, is
        // the store's: a producer in the captured graph grounds FilmCard on the fixture record. What
        // the class offers is the census's, so the accept line is the compiler's record header rather
        // than a list this fixture wrote, and the word the message uses for the member comes from the
        // arm the relation chose for the class.
        var file = file("""
            type FilmCard {
                bar: Int @field(name: "TYPO")
            }
            """);

        var diags = compute(file, withBackingClasses, noBackings());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("TYPO").contains("component").contains(RECORD_FIXTURE);
    }

    @Test
    void knownRecordComponentProducesNoError() {
        var file = file("""
            type FilmCard {
                bar: Int @field(name: "title")
            }
            """);

        var diags = compute(file, withBackingClasses, noBackings());

        assertThat(diags).isEmpty();
    }

    /**
     * A built projection holding no backing at all, which is what every member-check case now passes:
     * the arm reads the store for what a type resolves against, so what the projection would have
     * said about it is not a variable of these cases. Built rather than unavailable so the
     * freshness-gated warn arms behave as they do in a settled session.
     */
    private static LspSchemaSnapshot noBackings() {
        return new LspSchemaSnapshot.Built.Current(java.util.List.of(), Map.of(), Map.of());
    }

    /**
     * The projection is not merely unread here, it is absent: a session before its first build gets
     * the same member check as a settled one, because the capture the check reads is on the save
     * cadence rather than the pipeline's. Under the projection-era dispatch this was silence, an
     * absent snapshot meaning an absent backing meaning nothing to check against.
     */
    @Test
    void theCheckRunsWithNoProjectionAtAll() {
        var file = file("""
            type FilmCard {
                bar: Int @field(name: "TYPO")
            }
            """);

        var diags = compute(file, withBackingClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains(RECORD_FIXTURE);
    }

    // ===== @field(name:) member validation inside extend type X { ... } =====

    @Test
    void unknownColumnInsideTypeExtensionProducesError() {
        // The AST node DeclarationKind.enclosing returns is the extension; member validation resolves
        // what the parent type's members answer to by type name, so even though @table lives on the
        // definition in another file, the diagnostic fires.
        var file = file("""
            extend type Foo {
                bar: Int @field(name: "GHOST")
            }
            """);

        var diags = compute(file, catalogOnly, noBackings());

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

        var diags = compute(file, catalogOnly, noBackings());

        assertThat(diags).isEmpty();
    }

    // ===== $source sigil diagnostics =====

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
        var diags = compute(file, catalogOnly, snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void sourceSigil_atNonCarrierSite_producesCanonicalNotDefinedHereDiagnostic() {
        // The parent has an entry in the projection but none in the carrier projection, so the LSP
        // emits the canonical "$source is not defined here" message. The entry is load-bearing as
        // membership and not as a backing: the sigil arm speaks only about a type the projection has
        // seen, so that a site whose classification is merely stale is left alone. Which variant the
        // entry carries is beside the point, nothing reading a backing off the projection any more.
        var file = file("""
            type Foo {
                bar: Int @field(name: "$source")
            }
            """);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            java.util.List.of(),
            java.util.Map.of("Foo", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.RecordBacking("com.example.FooDto")),
            java.util.Map.of()
        );
        var diags = compute(file, catalogOnly, snapshot);

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
        var diags = compute(file, catalogOnly, snapshot);

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownReferenceKeyProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "NOPE"}])
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void knownReferenceKeyMatchesCaseInsensitively() {
        // Mirrors JooqCatalog.findForeignKey(name, source), which the runtime
        // resolver uses with equalsIgnoreCase. The LSP must not flag a
        // lowercased FK name the generator would accept.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film__film_language_id_fkey"}])
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void referenceKeyResolvesUnderTheSqlConstraintName() {
        // The census carries both namespaces a key can be named in, and the generator resolves either,
        // so an author who wrote the SQL constraint name is not red-squiggled for it. The projection
        // carried only the generated constant, so this was a false positive on a name the build accepts
        // and the completion arm on this very coordinate offers.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void schemaQualifiedReferenceKeyProducesNoError() {
        // A valid key: may carry a leading schema qualifier ("multischema_a.note_event_fk").
        // The qualifier scopes to the declaring schema rather than being stripped and forgotten, which
        // is how the generator's own resolver reads it.
        var file = file("""
            type Foo @table(name: "note") {
                bar: Int @reference(path: [{key: "multischema_a.note_event_fk"}])
            }
            """);

        var diags = compute(file, multiSchema, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void schemaQualifiedReferenceKeyUnderTheWrongSchemaProducesError() {
        // dup_gizmo_fk is declared in multischema_a only. Under the projection the qualifier was
        // stripped and the bare name matched, so a key named under a schema that does not declare it
        // was waved through; the census carries the declaring schema, so the qualifier binds.
        var file = file("""
            type Foo @table(name: "gizmo") {
                bar: Int @reference(path: [{key: "multischema_b.dup_gizmo_fk"}])
            }
            """);

        var diags = compute(file, multiSchema, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("multischema_b.dup_gizmo_fk");
    }

    @Test
    void aCensusWithNoCatalogDefersOnEveryCatalogName() {
        // The consumer's generated model is not there yet: no table resolves, no key resolves, and
        // nothing they wrote is wrong about a catalog nobody has generated. The same argument the
        // class arm defers on, on the other census.
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);

        assertThat(compute(file, withNodes, LspSchemaSnapshot.unavailable())).isEmpty();
    }

    @Test
    void schemaQualifiedReferenceKeyWithUnknownBareNameStillProducesError() {
        // A qualified spelling must not swallow a genuinely unknown key: no constraint of that name is
        // declared under that schema, and the message echoes the full author value.
        var file = file("""
            type Foo @table(name: "note") {
                bar: Int @reference(path: [{key: "multischema_a.NOPE"}])
            }
            """);

        var diags = compute(file, multiSchema, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("multischema_a.NOPE").contains("foreign key");
    }

    @Test
    void unknownReferenceTableProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "GHOST"}])
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("GHOST");
    }

    // ===== @field(name:) on @reference path field validates against terminal table =====

    @Test
    void outputTableWithReferencePathValidatesAgainstTerminalTable() {
        // The named column lives on the path's terminal table, so that is the table the check runs
        // against and a clean schema raises nothing.
        String sdl = """
            type Film @table(name: "film") {
                languageName: String @field(name: "NAME") @reference(path: [{table: "language"}])
            }
            type Query { films: [Film] }
            """;

        assertThat(computeCaptured(sdl)).isEmpty();
    }

    @Test
    void unresolvedReferencePathColumnSilentOnLspSide() {
        // The path names a table the catalog does not have, so it reaches nothing and there is no
        // table to check the column against. The report that names the real problem is the one to
        // leave standing; a second one blaming the enclosing type's own table would name the wrong
        // end of the join.
        String sdl = """
            type FilmType @table(name: "film") {
                languageName: String @field(name: "TYPO") @reference(path: [{table: "no_such_table"}])
            }
            type Query { films: [FilmType] }
            """;

        assertThat(computeCaptured(sdl)).noneMatch(d -> d.getMessage().contains("Unknown column"));
    }

    // ===== @field(name:) on a @table-interface participant cross-table reference =====
    //              validates against the @reference terminal table, not the participant's @table

    @Test
    void participantCrossTableReferenceValidatesAgainstTerminalTable() {
        // Single-table-interface participant: the enclosing @table is "film" but the field
        // reaches a column on "language" through a single-hop @reference. The field classifies
        // as ParticipantCrossTable, whose targetTableName is the terminal table. "NAME"
        // exists on "language" but not on "film"; validation targets the terminal table, so a
        // schema that builds clean raises no false-positive
        // "Unknown column 'NAME' on table 'film'."
        String sdl = """
            type DokumentMelding implements Melding @table(name: "film") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "NAME") @reference(path: [{table: "language"}])
            }
            interface Melding @table(name: "film") { languageName: String }
            type Query { meldinger: [Melding] }
            """;

        assertThat(computeCaptured(sdl)).isEmpty();
    }

    @Test
    void participantCrossTableReferenceBogusColumnCitesTerminalTable() {
        // The wrong-table message is the user-visible bug: a bogus column must be reported
        // against the terminal table ("language"), never the participant's own @table ("film").
        String sdl = """
            type DokumentMelding implements Melding @table(name: "film") @discriminator(value: "DOKUMENT") {
                languageName: String @field(name: "NOPE") @reference(path: [{table: "language"}])
            }
            interface Melding @table(name: "film") { languageName: String }
            type Query { meldinger: [Melding] }
            """;
        var diags = computeCaptured(sdl);

        assertThat(diags).anyMatch(d -> d.getMessage().contains("Unknown column 'NOPE' on table 'language'"));
        assertThat(diags).noneMatch(d -> d.getMessage().contains("on table 'film'"));
    }

    // ===== @defaultOrder(fields: [{name:}]) validates against the element table =====

    @Test
    void defaultOrderFieldNameValidatesAgainstElementTable() {
        // The enclosing @table is "film"; the list field navigates to "language". "NAME"
        // exists on the element table, so a valid @defaultOrder column raises no diagnostic.
        String sdl = """
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(fields: [{name: "NAME"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;

        assertThat(computeCaptured(sdl)).isEmpty();
    }

    @Test
    void defaultOrderFieldNameBogusColumnCitesElementTable() {
        // A bogus @defaultOrder column must be reported against the element table ("language"),
        // never the enclosing type's @table ("film"): the ordering column lives where the field
        // navigates to, and the site's resolved scope says so.
        String sdl = """
            type Film @table(name: "film") {
                languages: [Language!]! @defaultOrder(fields: [{name: "NOPE"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        var diags = computeCaptured(sdl);

        assertThat(diags).anyMatch(d -> d.getMessage().contains("Unknown column 'NOPE' on table 'language'"));
        assertThat(diags).noneMatch(d -> d.getMessage().contains("on table 'film'"));
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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void diagnosticRangeCoversTheArgumentValueWithQuotes() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void recordClassName_carveOut_producesNoUnknownClassError() {
        // @record is deprecated and ignored — its className binds no class, so an unresolvable
        // className raises no "Unknown class" diagnostic (the carve-out gates on the enclosing
        // directive name). The same coordinate under @enum/@service still validates.
        var file = file("""
            input FooInput @record(record: {className: "com.example.Missing"}) {
                bar: Int
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void knownServiceClassProducesNoError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.RealService", method: "foo"})
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownMethodOnKnownClassProducesError() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "ghost"})
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void methodWithNullParameterNamesProducesParametersWarning() {
        // The census's only overload of `list` takes one parameter and carries no name for it, which
        // is what a class compiled without -parameters records. Its own store, because the shared
        // census carries a nameless-free `list` and the point here is that no overload of the name has
        // names to offer.
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        try (var nameless = StoreFixture.ofClasspath(tmp, List.of(
            StoreFixture.jarClass("com.example.FilmService", List.of(
                StoreFixture.method("list", "List", StoreFixture.parameter(null, "int"))))))) {
            var diags = compute(file, nameless, LspSchemaSnapshot.unavailable());

            assertThat(diags).hasSize(1);
            assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
            assertThat(diags.get(0).getMessage()).contains("-parameters");
        }
    }

    @Test
    void methodWithNoParametersDoesNotProduceParametersWarning() {
        var file = file("""
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "get"})
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownExternalFieldClassProducesError() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {className: "com.example.Missing"})
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing").contains("class");
    }

    @Test
    void unknownEnumClassProducesError() {
        var file = file("""
            enum Foo @enum(enumReference: {className: "com.example.Missing"}) { A B }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Missing");
    }

    @Test
    void unknownSourceRowClassProducesError() {
        // @sourceRow has flat className/method directive args; the canonical
        // overlay binds @sourceRow(className:) → ClassNameBinding so the
        // same validator that fires inside ExternalCodeReference fires here too.
        var file = file("""
            type Foo {
                bar: Int @sourceRow(className: "com.example.Missing", method: "foo")
            }
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());  // empty externalReferences

        assertThat(diags).isEmpty();
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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly,
            new LspSchemaSnapshot.Built.Previous(List.of(), Map.of(), Map.of()));

        assertThat(diags).isEmpty();
    }

    @Test
    void userDeclaredDirectiveSilencedBySnapshot() {
        // Canonical motivating case: federation directives,
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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownTopLevelArgProducesWarning() {
        var file = file("""
            type Foo @table(neme: "film") {
                bar: Int
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    // @scalarType(scalar:) diagnostics.

    @Test
    void scalarType_malformedReference_producesError() {
        var file = file("""
            scalar Money @scalarType(scalar: "NoDotsHere")
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("fully.qualified.Class.FIELD");
    }

    @Test
    void scalarType_unknownClass_producesError() {
        var file = file("""
            scalar Money @scalarType(scalar: "com.example.Missing.MONEY")
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void scalarType_emptyValueProducesNoDiagnostic() {
        // Mid-edit state: empty quoted value. Completion fires, diagnostics
        // stay quiet so the user is not yelled at while still typing.
        var file = file("""
            scalar Money @scalarType(scalar: "")
            """);

        var diags = compute(file, withClasses, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());  // empty externalReferences

        assertThat(diags).isEmpty();
    }

    // ---- Arg validation on user-declared directives. ----

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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void userDirectiveUnknownArgUnderPreviousSnapshot_silent() {
        // Stale-snapshot silence — same trade applied to user-declared directives.
        var file = file("""
            type Query {
                customers: [String!]! @auth(rle: "admin")
            }
            """);

        var diags = compute(file, catalogOnly,
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

        var diags = compute(file, catalogOnly,
            new LspSchemaSnapshot.Built.Current(List.of(shadowTable), Map.of(), Map.of()));

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage())
            .contains("'neme'").contains("Unknown argument").contains("@table");
    }

    // @node(keyColumns:) and @nodeId(typeName:) diagnostics.

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

        var diags = compute(file, catalogOnly, noBackings());

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

        var diags = compute(file, catalogOnly, noBackings());

        assertThat(diags).isEmpty();
    }

    @Test
    void nodeIdTypeName_unknownType_producesError() {
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Ghost")): Int
            }
            """);

        var diags = compute(file, withNodes, LspSchemaSnapshot.unavailable());

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

        var diags = compute(file, withNodes, LspSchemaSnapshot.unavailable());

        assertThat(diags).isEmpty();
    }

    @Test
    void nodeIdTypeName_graphDeclaringNoNodeType_stillFlagsTheReference() {
        // A graph whose SDL declares no @node type is not a graph nobody has built: capture writes
        // every @node it sees, so no rows means the schema declares none and the build will reject
        // this reference. The projection could not tell those apart and deferred on both.
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Ghost")): Int
            }
            """);

        var diags = compute(file, catalogOnly, LspSchemaSnapshot.unavailable());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("Ghost").contains("@node");
    }

    @Test
    void nodeIdTypeName_beforeTheFirstBuild_isSilent() {
        // The pre-build state the deferral existed for, now the absence of a store rather than the
        // emptiness of a projection: one decision, taken once, for every value arm.
        var file = file("""
            type Query {
                x(id: ID @nodeId(typeName: "Ghost")): Int
            }
            """);

        assertThat(computeWithoutStore(file, LspSchemaSnapshot.unavailable())).isEmpty();
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

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    /**
     * Test-only forwarder that omits the URI and validator report. Every test in this class
     * exercises the SDL-only directive walks; the validator slice is covered by
     * {@link ValidatorDiagnosticsTest}. Threading an empty URI and {@link ValidationReport#empty()}
     * here keeps the call sites focused on the directive arm under test without committing
     * production callers to a backward-compat overload.
     */
    private static List<org.eclipse.lsp4j.Diagnostic> compute(
        FileSnapshot file, StoreFixture store, LspSchemaSnapshot snapshot
    ) {
        return Diagnostics.compute(LspVocabulary.load(), "", file, snapshot,
            ValidationReport.empty(), Optional.of(store.handle()));
    }

    /** The same walk with no store at all, which is what a session before its first build sees. */
    private static List<org.eclipse.lsp4j.Diagnostic> computeWithoutStore(
        FileSnapshot file, LspSchemaSnapshot snapshot
    ) {
        return Diagnostics.compute(LspVocabulary.load(), "", file, snapshot, ValidationReport.empty());
    }

    /**
     * Runs the walk against a store that captured this very document. The column arm resolves a
     * site's scope from the facts of the schema the directive sits in, so a case about that
     * resolution captures the schema it is validating rather than describing it twice.
     */
    private List<org.eclipse.lsp4j.Diagnostic> computeCaptured(String sdl) {
        try (var fixture = StoreFixture.ofCatalog(tmp, sdl)) {
            return Diagnostics.compute(LspVocabulary.load(), "", file(sdl),
                LspSchemaSnapshot.unavailable(),
                ValidationReport.empty(), Optional.of(fixture.handle()));
        }
    }

    /** The census's record, whose components the class-backed member cases resolve against. */
    private static final String RECORD_FIXTURE = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";



}
