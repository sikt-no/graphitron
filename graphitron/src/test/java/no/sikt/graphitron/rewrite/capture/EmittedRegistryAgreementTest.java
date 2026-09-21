package no.sikt.graphitron.rewrite.capture;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two producers of the emitted schema, compared document by document over the whole corpus.
 *
 * <p>The schema graphitron emits is produced twice today. {@code ConnectionPromoter} expands
 * {@code @asConnection} inside the classification walk and rebuilds the assembled schema from the
 * walk's output; {@code EmittedRegistry} patches the transcribed registry from the rows capture
 * wrote and assembles that. Two producers of one population is the thing being removed, and this is
 * the gate that says what removing it would cost: it prints both through one {@link SchemaPrinter}
 * and compares the text, which is the artifact a consumer actually receives rather than a proxy for
 * it.
 *
 * <p>Every document, both directions, no hand-picked list. A comparison scoped to what someone
 * expected to differ cannot see the case nobody thought of, and the whole point here is the case
 * nobody thought of. The corpus is swept as a whole and the disagreements are an output.
 *
 * <h3>What it costs</h3>
 *
 * <p>One capture per document, so about a minute. That is the price of the comparison and it is
 * deliberately paid once rather than sampled: this gate exists to be run while there are two
 * producers and to be deleted with the second one, a standing comparison between two producers
 * being a standing acceptance that there are two.
 *
 * <h3>The two producers agree, over every document</h3>
 *
 * <p>{@link #KNOWN_DISAGREEMENTS} is a ratchet, not an exemption list: the sweep stays total, a new
 * difference fails the build, and so does an old one fixed without being struck off.
 *
 * <p>It held one entry, and what that entry is matters more than the count. Three causes have been
 * found here. Two were capture stating something the run does not emit, and both were fixed in
 * capture. The third is the reverse: the store is right and the synthesis is wrong, so it is
 * recorded rather than repaired, and the difference ships the day the generator reads the store.
 * A ratchet that only ever went to zero could not have said that; it would have pushed a defect
 * into capture to make two producers agree on it.
 *
 * <ul>
 *   <li><b>The carrier lost the author's outer non-null.</b> {@code ConnectionPromoter} carries the
 *       authored expression's outer nullability across the rewrite, so {@code films: [Film!]!}
 *       emits as {@code QueryFilmsConnection!}; {@code MacroCapture.rewriteCarrier} wrote a bare
 *       nullable name unconditionally. Four documents disagreed on exactly that character. The
 *       expansion replaces what a field returns and says nothing about whether the field may be
 *       null, so the author's claim survives it, and a row saying otherwise described a schema the
 *       run does not emit.</li>
 *   <li><b>The facet machinery had no capture-side producer.</b> The decode was never the gap:
 *       {@code graphitron_facet_entry} held the applications and {@code graphitron_connection_facet}
 *       already resolved which facets a carrier surfaces and in what order, its own comment naming
 *       itself what a consumer emitting a faceted connection reads. What was missing was the mint,
 *       which {@code MacroCapture.expandFacets} now does from that relation. It came down to one
 *       description differing on one line after the types already matched, which is the kind of
 *       last mile a relation-count comparison would have called agreement.</li>
 * </ul>
 *
 * <h3>The one difference that is not a defect in capture</h3>
 *
 * <p><b>An authored pagination argument is replaced rather than yielded to.</b> Where an author
 * declares {@code first} themselves, {@code ConnectionPromoter.rewriteCarrierField} appends its own
 * over the top, so a bare {@code first: Int} comes back as {@code first: Int = 100} carrying the
 * connection's page size the author never wrote. The expansion yields per name, so the store keeps
 * the declaration as written. An expansion that silently rewrites what an author declared is the
 * defect, and it is the synthesis that has it.
 *
 * <p>This one is worth stating plainly because it is a schema change rather than an internal
 * correction. A consumer who declared {@code first} has been receiving an argument with a default
 * they did not ask for, and a client omitting the argument was paging at that default; after the
 * switch the argument is emitted as declared. The corpus carries the shape as
 * {@code authored-pagination-argument} so the difference is exercised rather than remembered.
 *
 * <p>It also changes the retirement condition. This gate was built to reach an empty set, and the
 * condition is now an empty set <em>or</em> a set holding only differences where the store is the
 * better answer. That is a weaker statement and it has to be read, not counted, which is why the
 * entry carries its reasoning here rather than only its id.
 */
@PipelineTier
class EmittedRegistryAgreementTest {

    /**
     * The corpus documents whose two printings differ today. Struck off as each cause is settled; a
     * document that starts or stops disagreeing fails this test either way.
     *
     * <p>The entry here is not owed a fix in capture. The synthesis replaces an authored
     * {@code first} with one carrying the connection's page size, and the store yields to what the
     * author wrote; the class comment argues why that makes the store right. It stops being a
     * disagreement when the synthesis goes, not when capture changes.
     */
    private static final Set<String> KNOWN_DISAGREEMENTS = Set.of("authored-pagination-argument");

    @Test
    @DisplayName("the store-derived schema and the incumbent agree on every corpus document but the recorded ones")
    void theProducersAgreeExceptWhereRecorded(@TempDir Path tmp) {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var census = TestSchemaHelper.classpathCensus(ctx);
        var printer = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeSchemaDefinition(true));

        var documents = CorpusDocuments.documents();
        var differed = new LinkedHashSet<String>();
        var detail = new ArrayList<String>();
        var unusable = new ArrayList<String>();

        for (var document : documents) {
            String sdl = CorpusDocuments.prelude() + document.sdl();

            GraphQLSchema incumbent;
            try {
                incumbent = TestSchemaHelper.buildBundle(sdl, ctx).assembled();
            } catch (RuntimeException e) {
                unusable.add(document.id() + ": the incumbent refused the document: " + brief(e));
                continue;
            }

            GraphQLSchema derived;
            try (var store = CapturedStore.ofCatalog(tmp.resolve(document.id()),
                    CapturedStore.GRAPH, sdl, jooq, census)) {
                var assembly = SchemaAssembly.of(EmittedRegistry.of(store.registry(),
                    new StoreHandle(store.dsl(), CapturedStore.GRAPH)));
                if (!(assembly instanceof SchemaAssembly.Assembled assembled)) {
                    var rejected = (SchemaAssembly.Rejected) assembly;
                    unusable.add(document.id() + ": the derived registry did not assemble: "
                        + rejected.errors().stream().limit(3)
                            .map(e -> e.errorClass() + " " + e.message()).toList());
                    continue;
                }
                derived = assembled.schema();
            }

            String left = printer.print(incumbent);
            String right = printer.print(derived);
            if (!left.equals(right)) {
                differed.add(document.id());
                // An id says that a document disagrees and not about what, which is the first
                // thing a reader needs and the one thing a set of ids cannot carry. The first
                // differing line is usually the whole answer: both sides are printed by one
                // printer in a stable order, so they diverge at the element that differs.
                detail.add(document.id() + ": " + firstDifference(left, right));
            }
        }

        assertThat(unusable)
            .as("a document neither producer could answer for is a failure of this gate, not a "
                + "disagreement: the comparison never ran for it")
            .isEmpty();
        assertThat(documents).as("the corpus loaded").isNotEmpty();
        assertThat(differed)
            .as("the two producers' printed schemas, over %d corpus documents. %s",
                documents.size(), detail)
            .isEqualTo(KNOWN_DISAGREEMENTS);
    }

    /** The first line the two printed schemas differ at, with a little of each side. */
    private static String firstDifference(String left, String right) {
        var a = left.split("\n");
        var b = right.split("\n");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                return "line " + (i + 1) + ", incumbent [" + a[i].strip()
                    + "] derived [" + b[i].strip() + "]";
            }
        }
        return "one is a prefix of the other, incumbent " + a.length
            + " lines and derived " + b.length;
    }

    private static String brief(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
