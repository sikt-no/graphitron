package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration-name hover overlay and its agreement with goto-definition. Both falsifiable pieces
 * are separable: the shared {@link DeclTarget} resolver names a declaration from the {@code Built}
 * backing projection and the store's censuses, and the two projections of it,
 * {@link DeclarationHovers#overlay} and {@link DeclarationDefinitions#locate}, are queries against one
 * store. The only tree-sitter-bound step, the {@code @field(name:)} trigger, is shared with goto and
 * covered by the live {@code DeclarationHoversTest}.
 *
 * <p>The overlay cases stand up a real store: real capture of the fixture module's generated jOOQ
 * catalog for the two catalog arms, and a real parse of {@code .java} files on disk for the arms that
 * answer about Java. No row is inserted by hand, so a fixture cannot claim a state capture never
 * writes, and every doc comment and position in an assertion came from parsing source.
 *
 * <p>The drift guard is the parity claim, and it is structural in both halves again. Both projections
 * switch exhaustively over the <em>same</em> resolved target, so neither can point at a different
 * declaration and a new backing permit breaks both at compile time; and both read the same row of the
 * java-source family, one for its doc comment and one for its position, so neither can be answering
 * about a state of the source the other has not seen. One asymmetry is left, and the guard is what
 * pins it: goto jumps for every declaration the parse positioned, hover overlays only those it read a
 * doc comment for. Where the source carries one, both fire; where nothing is parsed, neither does.
 */
class DeclarationHoverOverlayParityTest {

    private static final String STANDALONE_CLASS = "no.sikt.example.CustomRecord";
    private static final String RECORD_CLASS = "no.sikt.example.PersonRecord";
    private static final String POJO_CLASS = "no.sikt.example.PersonPojo";
    private static final String SERVICE_CLASS = "no.sikt.example.PriceService";

    /** The graph is beside the point in the overlay cases; the subject is the catalog and the source. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    // ===== resolver: SDL coordinate -> named declaration =====

    @Test
    void typeNameResolvesPerBacking(@TempDir Path root) {
        var built = built();
        try (var store = resolverStore(root)) {
            var handle = store.handle();
            assertThat(DeclTarget.ofType("Film", built, handle))
                .isEqualTo(new DeclTarget.CatalogTable("film", store.tableClassFqn("film")));
            assertThat(DeclTarget.ofType("Standalone", built, handle))
                .isEqualTo(new DeclTarget.SourceClass(STANDALONE_CLASS));
            assertThat(DeclTarget.ofType("Person", built, handle))
                .isEqualTo(new DeclTarget.SourceClass(RECORD_CLASS));
            assertThat(DeclTarget.ofType("PersonPojo", built, handle))
                .isEqualTo(new DeclTarget.SourceClass(POJO_CLASS));
            assertThat(DeclTarget.ofType("Query", built, handle)).isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofType("Unknown", built, handle)).isInstanceOf(DeclTarget.None.class);
        }
    }

    /**
     * The class-backed arms read the store: which of a record's components or a class's accessors a
     * member name resolves to, and therefore whether the declaration behind it is a field or a
     * method, is the member-slot relation's answer rather than the permit's.
     */
    @Test
    void fieldNameResolvesPerBacking(@TempDir Path root) {
        var built = built();
        try (var store = resolverStore(root)) {
            var handle = store.handle();
            // The author's spelling is the SQL name; the target carries the generated field's, which
            // is what the class declares and what either consumer then reads about it.
            assertThat(DeclTarget.ofField("Film", "title", built, handle))
                .isEqualTo(new DeclTarget.CatalogColumn("film", store.tableClassFqn("film"), "TITLE"));
            // A standalone-jOOQ field degrades to its backing class, where goto jumps.
            assertThat(DeclTarget.ofField("Standalone", "anything", built, handle))
                .isEqualTo(new DeclTarget.SourceClass(STANDALONE_CLASS));
            // Record component and POJO accessor field arms.
            assertThat(DeclTarget.ofField("Person", "firstName", built, handle))
                .isEqualTo(new DeclTarget.SourceField(RECORD_CLASS, "firstName"));
            assertThat(DeclTarget.ofField("PersonPojo", "firstName", built, handle))
                .isEqualTo(new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0));
            // A method-backed (@service) field name resolves to its bound method, with
            // the arity read off the census, taking precedence over the parent backing.
            assertThat(DeclTarget.ofField("Priced", "price", built, handle))
                .isEqualTo(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1));
            // Unknown column / unknown member / no backing all yield no target.
            assertThat(DeclTarget.ofField("Film", "no_such_column", built, handle))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofField("Person", "noSuchMember", built, handle))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofField("Query", "whatever", built, handle))
                .isInstanceOf(DeclTarget.None.class);
        }
    }

    // ===== overlay: named declaration -> the text the store holds for it =====

    @Test
    void everyArmReadsWhatTheStoreHoldsForItsDeclaration(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, root);
            var handle = store.handle();

            // The generated table class's doc comment: the fixture database carries no comment on
            // film, so the class Javadoc is what a table with nothing written about it falls back to.
            assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogTable("film", filmFqn), handle))
                .isEqualTo("The film table.");
            assertThat(DeclarationHovers.overlay(
                new DeclTarget.CatalogColumn("film", filmFqn, "TITLE"), handle))
                .isEqualTo("The film's title.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceClass(STANDALONE_CLASS), handle))
                .isEqualTo("A hand-written record.");
            // The same declaration the column arm just described, reached the other way: a member
            // keyed by class and field name, which is the lookup a record component resolves through.
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceField(filmFqn, "TITLE"), handle))
                .isEqualTo("The film's title.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0), handle))
                .isEqualTo("Reads the first name.");
            // A non-zero arity keys the overload the resolution named, rather than assuming zero.
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1), handle))
                .isEqualTo("Prices one film.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.None(), handle)).isEmpty();
        }
    }

    /**
     * The column arm matches under either spelling the census carries, so a target resolved from the
     * jOOQ constant and one resolved from the SQL column name describe the same column. Goto has only
     * the generated field's own name to key on, which is what the resolution hands it.
     */
    @Test
    void theColumnArmAnswersUnderEitherSpelling(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, root);
            var handle = store.handle();

            assertThat(DeclarationHovers.overlay(
                new DeclTarget.CatalogColumn("film", filmFqn, "title"), handle))
                .isEqualTo("The film's title.");
        }
    }

    /**
     * A record component is where the two surfaces genuinely part, and not because they read different
     * substrates: they read one row, and the parse positioned that declaration but did not retain for
     * it the doc comment written in the record's header, so goto jumps and there is nothing to overlay.
     * Pinned rather than fixed here, since it is a property of the parse; the incumbent overlay had the
     * same gap, masked by a hand-built index that asserted a component Javadoc no parse produces.
     */
    @Test
    void aRecordComponentJumpsButHasNoDocCommentToOverlay(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            writeSources(store, root);
            var target = new DeclTarget.SourceField(RECORD_CLASS, "firstName");

            assertThat(DeclarationDefinitions.locate(target, store.handle()))
                .as("the component is positioned, so goto jumps")
                .isPresent();
            assertThat(DeclarationHovers.overlay(target, store.handle()))
                .as("its header doc comment is not retained for the component's own declaration")
                .isEmpty();
        }
    }

    /** A doc comment nothing has parsed is absence, not an empty paragraph under the classification. */
    @Test
    void anUnparsedDeclarationOverlaysNothing(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            var handle = store.handle();
            String filmFqn = store.tableClassFqn("film");

            assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogTable("film", filmFqn), handle)).isEmpty();
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceClass(RECORD_CLASS), handle)).isEmpty();
            assertThat(DeclarationHovers.overlay(
                new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1), handle)).isEmpty();
        }
    }

    // ===== drift guard: overlay-presence <=> jump-presence, per variant =====

    @Test
    void overlayIsPresentExactlyWhenGotoJumps(@TempDir Path parsed, @TempDir Path unparsed) {
        try (var store = StoreFixture.ofCatalog(parsed, PLACEHOLDER_SDL);
             var bare = StoreFixture.ofCatalog(unparsed, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, parsed);

            for (DeclTarget target : List.of(
                new DeclTarget.CatalogTable("film", filmFqn),
                new DeclTarget.CatalogColumn("film", filmFqn, "TITLE"),
                new DeclTarget.SourceClass(STANDALONE_CLASS),
                new DeclTarget.SourceField(filmFqn, "TITLE"),
                new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0),
                new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1)
            )) {
                // One parsed declaration behind both: goto jumps AND hover overlays.
                assertThat(DeclarationDefinitions.locate(target, store.handle()))
                    .as("goto jump for %s when parsed", target).isPresent();
                assertThat(DeclarationHovers.overlay(target, store.handle()))
                    .as("hover overlay for %s when parsed", target).isNotEmpty();
                // A store whose catalog was captured but whose sources never were: neither fires.
                assertThat(DeclarationDefinitions.locate(target, bare.handle()))
                    .as("goto jump for %s when unparsed", target).isEmpty();
                assertThat(DeclarationHovers.overlay(target, bare.handle()))
                    .as("hover overlay for %s when unparsed", target).isEmpty();
            }
        }
    }

    @Test
    void noBackingTargetNeverJumpsAndNeverOverlays(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            writeSources(store, root);
            var none = new DeclTarget.None();
            assertThat(DeclarationDefinitions.locate(none, store.handle())).isEmpty();
            assertThat(DeclarationHovers.overlay(none, store.handle())).isEmpty();
        }
    }

    // ===== fixtures =====

    /**
     * The Java sources every overlay arm reads, parsed into the store's java-source family. The
     * generated table class is written under the FQN the catalog walk actually captured, so the join
     * between the two populations is a real one rather than a spelled-out naming strategy.
     *
     * @return that FQN, which the catalog arms are keyed on
     */
    private static String writeSources(StoreFixture store, Path root) {
        String filmFqn = store.tableClassFqn("film");
        store.withJavaSource(root, filmFqn, """
            /** The film table. */
            public class Film {
                /** The film's title. */
                public final Object TITLE = null;
            }
            """);
        store.withJavaSource(root, STANDALONE_CLASS, """
            /** A hand-written record. */
            public class CustomRecord {
            }
            """);
        store.withJavaSource(root, RECORD_CLASS, """
            /** A person. */
            public record PersonRecord(
                /** The person's first name. */
                String firstName
            ) {
            }
            """);
        store.withJavaSource(root, POJO_CLASS, """
            /** A person, as a bean. */
            public class PersonPojo {
                /** Reads the first name. */
                public String getFirstName() { return null; }
            }
            """);
        store.withJavaSource(root, SERVICE_CLASS, """
            /** Prices films. */
            public class PriceService {
                /** Prices one film. */
                public Object price(Object table) { return null; }
            }
            """);
        return filmFqn;
    }

    /**
     * The store the resolver cases read: the fixture module's generated catalog for the table and
     * column arms, plus a census carrying the record's component, the POJO's accessor and the service
     * method whose parameter count is the arity a method-backed field resolves at.
     */
    private static StoreFixture resolverStore(Path root) {
        return StoreFixture.ofCatalog(root, PLACEHOLDER_SDL, List.of(
            StoreFixture.jarRecord(RECORD_CLASS, StoreFixture.component("firstName", "String")),
            StoreFixture.jarClass(POJO_CLASS, List.of(
                StoreFixture.method("getFirstName", "String"))),
            StoreFixture.jarClass(SERVICE_CLASS, List.of(
                StoreFixture.method("price", "Field",
                    StoreFixture.parameter("ctx", "DSLContext"))))));
    }

    private static LspSchemaSnapshot.Built built() {
        Map<String, TypeBackingShape> backings = Map.of(
            "Film", new TypeBackingShape.TableBacking("film"),
            "Standalone", new TypeBackingShape.JooqRecordBacking.Standalone(STANDALONE_CLASS),
            "Person", new TypeBackingShape.RecordBacking(RECORD_CLASS),
            "PersonPojo", new TypeBackingShape.PojoBacking(POJO_CLASS),
            "Query", new TypeBackingShape.NoBacking.Root());
        // "Priced.price" is a field-level @service field; its classification names the
        // bound method, which the field-name resolution prefers over any backing.
        Map<String, FieldClassification> classifications = Map.of(
            "Priced.price", new FieldClassification.ServiceBacked(SERVICE_CLASS, "price", false, null, null));
        return new LspSchemaSnapshot.Built.Current(List.of(), backings, Map.of(), classifications, Map.of());
    }
}
