package no.sikt.graphitron.lsp.hover;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration-name hover overlay and its parity with goto-definition,
 * exercised <em>off</em> the tree-sitter tier. Both falsifiable pieces are pure:
 * the shared {@link DeclTarget} resolver (a function over the {@code Built}
 * backing projection) and the two projections of it,
 * {@link DeclarationDefinitions#locate} (goto) and {@link DeclarationHovers#overlay}
 * (hover). The only tree-sitter-bound step, the {@code @field(name:)} trigger, is
 * shared with goto and already covered by the live {@code DeclarationHoversTest}.
 *
 * <p>The headline assertion is the drift guard: for every {@link DeclTarget}
 * variant that goto jumps on, hover must overlay something, and where goto stays
 * put, hover must too. Because both arms project the <em>same</em> resolved target,
 * this is a structural property; the test pins it so a future divergence in either
 * projection fails here.
 */
class DeclarationHoverOverlayParityTest {

    private static final String FILM_CLASS = "no.sikt.example.tables.Film";
    private static final String STANDALONE_CLASS = "no.sikt.example.CustomRecord";
    private static final String RECORD_CLASS = "no.sikt.example.PersonRecord";
    private static final String POJO_CLASS = "no.sikt.example.PersonPojo";
    private static final String SERVICE_CLASS = "no.sikt.example.PriceService";

    // ===== resolver: SDL coordinate -> named declaration =====

    @Test
    void typeNameResolvesPerBacking() {
        var catalog = catalog();
        var built = built();
        assertThat(DeclTarget.ofType("Film", built, catalog))
            .isEqualTo(new DeclTarget.CatalogTable(filmTable()));
        assertThat(DeclTarget.ofType("Standalone", built, catalog))
            .isEqualTo(new DeclTarget.SourceClass(STANDALONE_CLASS));
        assertThat(DeclTarget.ofType("Person", built, catalog))
            .isEqualTo(new DeclTarget.SourceClass(RECORD_CLASS));
        assertThat(DeclTarget.ofType("PersonPojo", built, catalog))
            .isEqualTo(new DeclTarget.SourceClass(POJO_CLASS));
        assertThat(DeclTarget.ofType("Query", built, catalog)).isInstanceOf(DeclTarget.None.class);
        assertThat(DeclTarget.ofType("Unknown", built, catalog)).isInstanceOf(DeclTarget.None.class);
    }

    @Test
    void fieldNameResolvesPerBacking() {
        var catalog = catalog();
        var built = built();
        // Case-insensitive column match yields the canonical (uppercase) column name.
        assertThat(DeclTarget.ofField("Film", "title", built, catalog))
            .isEqualTo(new DeclTarget.CatalogColumn(filmTable(), titleColumn()));
        // F1: a standalone-jOOQ field degrades to its backing class, where goto jumps.
        assertThat(DeclTarget.ofField("Standalone", "anything", built, catalog))
            .isEqualTo(new DeclTarget.SourceClass(STANDALONE_CLASS));
        // F3: record component and POJO accessor field arms.
        assertThat(DeclTarget.ofField("Person", "firstName", built, catalog))
            .isEqualTo(new DeclTarget.SourceField(RECORD_CLASS, "firstName"));
        assertThat(DeclTarget.ofField("PersonPojo", "firstName", built, catalog))
            .isEqualTo(new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0));
        // A method-backed (@service) field name resolves to its bound method, with
        // the arity read off the catalog, taking precedence over the parent backing.
        assertThat(DeclTarget.ofField("Priced", "price", built, catalog))
            .isEqualTo(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1));
        // Unknown column / unknown member / no backing all yield no target.
        assertThat(DeclTarget.ofField("Film", "no_such_column", built, catalog))
            .isInstanceOf(DeclTarget.None.class);
        assertThat(DeclTarget.ofField("Person", "noSuchMember", built, catalog))
            .isInstanceOf(DeclTarget.None.class);
        assertThat(DeclTarget.ofField("Query", "whatever", built, catalog))
            .isInstanceOf(DeclTarget.None.class);
    }

    // ===== overlay: named declaration -> Javadoc text =====

    @Test
    void overlayReturnsTheBoundDeclarationsJavadoc() {
        var index = indexWithSources();
        assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogTable(filmTable()), index))
            .isEqualTo("Film table class javadoc");
        assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogColumn(filmTable(), titleColumn()), index))
            .isEqualTo("title column javadoc");
        assertThat(DeclarationHovers.overlay(new DeclTarget.SourceClass(STANDALONE_CLASS), index))
            .isEqualTo("standalone class javadoc");
        assertThat(DeclarationHovers.overlay(new DeclTarget.SourceField(RECORD_CLASS, "firstName"), index))
            .isEqualTo("firstName component javadoc");
        assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0), index))
            .isEqualTo("getFirstName accessor javadoc");
        // Non-zero arity: the overlay keys the index on the carried arity, no longer
        // hardcoded to 0, so a service method's Javadoc surfaces.
        assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1), index))
            .isEqualTo("price service method javadoc");
        assertThat(DeclarationHovers.overlay(new DeclTarget.None(), index)).isEmpty();
    }

    // ===== drift guard: overlay-presence <=> jump-presence, per variant =====

    @Test
    void overlayIsPresentExactlyWhenGotoJumps() {
        var catalog = catalog();
        var present = indexWithSources();
        var missing = SourceWalker.Index.EMPTY;

        for (DeclTarget target : List.of(
            new DeclTarget.CatalogTable(filmTable()),
            new DeclTarget.CatalogColumn(filmTable(), titleColumn()),
            new DeclTarget.SourceClass(STANDALONE_CLASS),
            new DeclTarget.SourceField(RECORD_CLASS, "firstName"),
            new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0),
            new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1)
        )) {
            // Source indexed: goto jumps AND hover overlays.
            assertThat(DeclarationDefinitions.locate(target, present))
                .as("goto jump for %s when indexed", target).isPresent();
            assertThat(DeclarationHovers.overlay(target, present))
                .as("hover overlay for %s when indexed", target).isNotEmpty();
            // Source absent: neither fires.
            assertThat(DeclarationDefinitions.locate(target, missing))
                .as("goto jump for %s when not indexed", target).isEmpty();
            assertThat(DeclarationHovers.overlay(target, missing))
                .as("hover overlay for %s when not indexed", target).isEmpty();
        }
    }

    @Test
    void noBackingTargetNeverJumpsAndNeverOverlays() {
        var none = new DeclTarget.None();
        assertThat(DeclarationDefinitions.locate(none, indexWithSources())).isEmpty();
        assertThat(DeclarationHovers.overlay(none, indexWithSources())).isEmpty();
    }

    // ===== fixtures =====

    private static CompletionData.Column titleColumn() {
        // The catalog carries the canonical uppercase column name and no description,
        // so the column's overlay comes solely from the source index (matching goto).
        return new CompletionData.Column("TITLE", "String", true, "");
    }

    private static CompletionData.Table filmTable() {
        // Empty SQL-comment description so ofTable falls through to the class javadoc:
        // a non-empty description would make the overlay present even off-index,
        // breaking the parity the table arm is meant to hold.
        return new CompletionData.Table("film", "", FILM_CLASS, List.of(titleColumn()), List.of());
    }

    private static CompletionData catalog() {
        // The POJO accessor (arity 0) and the service method (arity 1) are external
        // references; the service ref's parameter count is what the @service field's
        // resolution reads to key the right overload.
        var pojoRef = new CompletionData.ExternalReference(
            "PersonPojo", POJO_CLASS, "",
            List.of(new CompletionData.Method("getFirstName", "String", "", List.of())),
            List.of());
        var serviceRef = new CompletionData.ExternalReference(
            "PriceService", SERVICE_CLASS, "",
            List.of(new CompletionData.Method("price", "Field", "",
                List.of(new CompletionData.Parameter("ctx", "DSLContext", "", "")))),
            List.of());
        return new CompletionData(List.of(filmTable()), List.of(), List.of(pojoRef, serviceRef));
    }

    private static LspSchemaSnapshot.Built built() {
        Map<String, TypeBackingShape> backings = Map.of(
            "Film", new TypeBackingShape.TableBacking("film"),
            "Standalone", new TypeBackingShape.JooqRecordBacking.Standalone(STANDALONE_CLASS),
            "Person", new TypeBackingShape.RecordBacking(
                RECORD_CLASS, List.of(new TypeBackingShape.MemberSlot("firstName", "String", "firstName"))),
            "PersonPojo", new TypeBackingShape.PojoBacking(
                POJO_CLASS, List.of(new TypeBackingShape.MemberSlot("firstName", "String", "getFirstName"))),
            "Query", new TypeBackingShape.NoBacking.Root());
        // "Priced.price" is a field-level @service field; its classification names the
        // bound method, which the field-name resolution prefers over any backing.
        Map<String, FieldClassification> classifications = Map.of(
            "Priced.price", new FieldClassification.ServiceBacked(SERVICE_CLASS, "price", false, null, null));
        return new LspSchemaSnapshot.Built.Current(List.of(), backings, Map.of(), classifications, Map.of());
    }

    private static SourceWalker.Index indexWithSources() {
        var loc = new CompletionData.SourceLocation("file:///Sources.java", 1, 0);
        Map<String, SourceWalker.Decl> classes = Map.of(
            FILM_CLASS, new SourceWalker.Decl(loc, "Film table class javadoc"),
            STANDALONE_CLASS, new SourceWalker.Decl(loc, "standalone class javadoc"),
            RECORD_CLASS, new SourceWalker.Decl(loc, "PersonRecord class javadoc"),
            POJO_CLASS, new SourceWalker.Decl(loc, "PersonPojo class javadoc"),
            SERVICE_CLASS, new SourceWalker.Decl(loc, "PriceService class javadoc"));
        Map<SourceWalker.FieldKey, SourceWalker.Decl> fields = Map.of(
            new SourceWalker.FieldKey(FILM_CLASS, "TITLE"), new SourceWalker.Decl(loc, "title column javadoc"),
            new SourceWalker.FieldKey(RECORD_CLASS, "firstName"),
            new SourceWalker.Decl(loc, "firstName component javadoc"));
        Map<SourceWalker.MethodKey, SourceWalker.Decl> methods = Map.of(
            new SourceWalker.MethodKey(POJO_CLASS, "getFirstName", 0),
            new SourceWalker.Decl(loc, "getFirstName accessor javadoc"),
            new SourceWalker.MethodKey(SERVICE_CLASS, "price", 1),
            new SourceWalker.Decl(loc, "price service method javadoc"));
        return new SourceWalker.Index(classes, methods, fields, Set.of());
    }
}
