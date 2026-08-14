package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_class_member_slot}: which member names a class
 * on the classpath offers an SDL author, and which of its methods offer none.
 *
 * <p>The census these cases read is a real classfile scan of this package's three fixture types
 * rather than hand-built rows. The rule is a reading of what a classfile declares, so a fixture
 * that declared its own class kind or its own descriptors could assert a census no compiler
 * produces: a record whose classfile carries no record attribute, or an accessor whose parameter
 * list disagrees with its descriptor. Compiling the fixtures and scanning them is what makes
 * "a record answers with its components" a claim about records.
 */
@PipelineTier
class ClassMemberSlotTest {

    @TempDir
    Path tmp;

    // ===== A record answers with its components =====

    /** The record arm: components in the author's vocabulary, each resolving to its own accessor. */
    @Test
    void aRecordAnswersWithItsComponents() {
        withCapturedStore(dsl -> {
            var slots = slots(dsl, TestSlotRecord.class);
            assertThat(slots).extracting(r -> r.get(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME))
                .containsExactly("filmId", "title");
            assertThat(slots).allSatisfy(r ->
                assertThat(r.get(INTENT_CLASS_MEMBER_SLOT.ORIGIN)).isEqualTo("RECORD_COMPONENT"));
            assertThat(slot(dsl, TestSlotRecord.class, "filmId")
                .get(INTENT_CLASS_MEMBER_SLOT.DISPLAY_TYPE)).isEqualTo("Integer");
            assertThat(slot(dsl, TestSlotRecord.class, "filmId")
                .get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)).isEqualTo("filmId");
        });
    }

    /**
     * A record's declared bean accessor contributes nothing. Without the arm gate the class would
     * offer {@code title} twice, once as a component and once as an accessor, and a reader taking
     * the first match would land on whichever the union happened to order first.
     */
    @Test
    void aRecordIgnoresItsOwnBeanAccessors() {
        withCapturedStore(dsl -> {
            var titles = rows(dsl, TestSlotRecord.class, "title");
            assertThat(titles).hasSize(1);
            assertThat(titles.getFirst().get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
                .isEqualTo("title");
        });
    }

    // ===== Anything else answers with its bean accessors =====

    /** The two prefixes the rule accepts, each yielding the remainder of its own name. */
    @Test
    void aClassAnswersWithItsBeanAccessors() {
        withCapturedStore(dsl -> {
            var title = byAccessor(dsl, TestSlotPojo.class, "getTitle");
            assertThat(title.get(INTENT_CLASS_MEMBER_SLOT.ORIGIN)).isEqualTo("BEAN_ACCESSOR");
            assertThat(title.get(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME)).isEqualTo("title");
            assertThat(title.get(INTENT_CLASS_MEMBER_SLOT.DISPLAY_TYPE)).isEqualTo("String");

            var restricted = slot(dsl, TestSlotPojo.class, "restricted");
            assertThat(restricted.get(INTENT_CLASS_MEMBER_SLOT.DISPLAY_TYPE)).isEqualTo("boolean");
            assertThat(restricted.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
                .isEqualTo("isRestricted");
        });
    }

    /** Only the first letter is lowered, so an acronym keeps the rest of its case. */
    @Test
    void anAccessorLowersItsFirstLetterAndLeavesTheRest() {
        withCapturedStore(dsl ->
            assertThat(slotNames(dsl, TestSlotPojo.class)).contains("uRL").doesNotContain("url"));
    }

    /**
     * The near-misses: the prefix with nothing after it, a lower-case letter where the rule requires
     * an upper-case one, and a method with no prefix at all. The last is asserted on the accessor
     * rather than the slot name, {@code title} being a name the class does offer under two prefixed
     * spellings; what must not have contributed is the unprefixed method.
     */
    @Test
    void aMethodThatIsNotBeanShapedOffersNothing() {
        withCapturedStore(dsl -> {
            assertThat(slotNames(dsl, TestSlotPojo.class))
                .doesNotContain("", "lower")
                .doesNotContain("Title");
            assertThat(slots(dsl, TestSlotPojo.class))
                .extracting(r -> r.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
                .doesNotContain("title", "get", "getlower");
        });
    }

    /**
     * A bean-shaped method taking an argument offers nothing: no member name can resolve to a call
     * the generator would have to supply a value for. Read as the absence of parameter rows, which
     * is why the same fixture pins the census's parameter capture too.
     */
    @Test
    void aBeanShapedMethodTakingAnArgumentOffersNothing() {
        withCapturedStore(dsl ->
            assertThat(slots(dsl, TestSlotPojo.class))
                .extracting(r -> r.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
                .doesNotContain("getRated"));
    }

    /**
     * The rule reads the name and not the return type, so {@code isTitle()} returning a String is a
     * slot. It is also the second spelling of a property the class already offers, and both rows
     * stand: the projection's list held both, and a reader that offers candidates should offer both.
     */
    @Test
    void twoSpellingsOfOnePropertyAreTwoSlots() {
        withCapturedStore(dsl ->
            assertThat(rows(dsl, TestSlotPojo.class, "title"))
                .extracting(r -> r.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
                .containsExactlyInAnyOrder("getTitle", "isTitle"));
    }

    /** The arm is chosen by "not a record", so an interface answers with accessors like any class. */
    @Test
    void anInterfaceAnswersLikeAnyNonRecord() {
        withCapturedStore(dsl -> {
            var name = slot(dsl, TestSlotInterface.class, "name");
            assertThat(name.get(INTENT_CLASS_MEMBER_SLOT.ORIGIN)).isEqualTo("BEAN_ACCESSOR");
            assertThat(name.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)).isEqualTo("getName");
        });
    }

    // ===== Partition =====

    /**
     * The relation carries no graph column, so the partition is the reader's semi-join through
     * {@code store_graph_source}. A graph that read none of these classes sees none of their slots,
     * which is the whole of what keeps one workspace's modules out of each other's answers.
     */
    @Test
    void aGraphThatReadNoneOfTheseClassesSeesNoSlots() {
        withCapturedStore(dsl -> {
            assertThat(dsl.fetchCount(INTENT_CLASS_MEMBER_SLOT,
                new StoreHandle(dsl, GRAPH).reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME)))
                .isPositive();
            assertThat(dsl.fetchCount(INTENT_CLASS_MEMBER_SLOT,
                new StoreHandle(dsl, "other").reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME)))
                .isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "ClassMemberSlotTest";

    /** The SDL is beside the point here; the subject is the classpath. */
    private static final String SDL = "type Query { placeholder: Int }\n";

    private static final Set<String> FIXTURE_CLASSES = Set.of(
        TestSlotRecord.class.getName(), TestSlotPojo.class.getName(), TestSlotInterface.class.getName());

    /** Scanned once: the census is the same for every case, and the scan reads every test class. */
    private static List<CompletionData.ExternalReference> fixtureCensus;

    private static List<Record> slots(DSLContext dsl, Class<?> owner) {
        return dsl.select(INTENT_CLASS_MEMBER_SLOT.fields())
            .from(INTENT_CLASS_MEMBER_SLOT)
            .where(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.eq(owner.getName()))
            .orderBy(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME, INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)
            .fetch();
    }

    private static List<String> slotNames(DSLContext dsl, Class<?> owner) {
        return slots(dsl, owner).stream()
            .map(r -> r.get(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME))
            .toList();
    }

    private static List<Record> rows(DSLContext dsl, Class<?> owner, String slotName) {
        return slots(dsl, owner).stream()
            .filter(r -> slotName.equals(r.get(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME)))
            .toList();
    }

    /** The row a named accessor produced, for a property the class spells more than one way. */
    private static Record byAccessor(DSLContext dsl, Class<?> owner, String accessorName) {
        var matching = slots(dsl, owner).stream()
            .filter(r -> accessorName.equals(r.get(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)))
            .toList();
        assertThat(matching).as("one row for accessor '%s' on %s", accessorName, owner.getName())
            .hasSize(1);
        return matching.getFirst();
    }

    /** The one row for a slot name, for the cases where the class offers a single spelling of it. */
    private static Record slot(DSLContext dsl, Class<?> owner, String slotName) {
        var matching = rows(dsl, owner, slotName);
        assertThat(matching).as("one row for slot '%s' on %s", slotName, owner.getName()).hasSize(1);
        return matching.getFirst();
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, SDL);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, census(), new NodeDeclaration(null));
            body.accept(store.dsl());
        }
    }

    /**
     * The three fixture classes as a real scan produced them, filtered out of the scan of this
     * module's test classes. Filtering keeps the captured census small; what each row says is the
     * scanner's, not this test's.
     */
    private static synchronized List<CompletionData.ExternalReference> census() {
        if (fixtureCensus == null) {
            fixtureCensus = ClasspathScanner.scan(testClassesRoot(), testContext().jooqPackage())
                .stream()
                .filter(reference -> FIXTURE_CLASSES.contains(reference.className()))
                .toList();
            assertThat(fixtureCensus)
                .as("every fixture class is public and top-level, so the scan reads all three")
                .hasSize(FIXTURE_CLASSES.size());
        }
        return fixtureCensus;
    }

    private static Path testClassesRoot() {
        try {
            return Path.of(ClassMemberSlotTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("test classes root is not a file path", e);
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
