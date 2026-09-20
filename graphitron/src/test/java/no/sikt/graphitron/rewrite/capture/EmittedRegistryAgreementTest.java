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
 * difference fails the build, and so does an old one fixed without being struck off. It is empty,
 * which is the state this gate was built to reach and the condition under which the second producer
 * can be retired and this test deleted with it.
 *
 * <p>Both causes it found are recorded here rather than only in history, because both shapes recur
 * and the second is why an empty set is worth more than it looks.
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
 *       {@code graphitron_facet_entry} held the applications and {@code intent_connection_facet}
 *       already resolved which facets a carrier surfaces and in what order, its own comment naming
 *       itself what a consumer emitting a faceted connection reads. What was missing was the mint,
 *       which {@code MacroCapture.expandFacets} now does from that relation. It came down to one
 *       description differing on one line after the types already matched, which is the kind of
 *       last mile a relation-count comparison would have called agreement.</li>
 * </ul>
 */
@PipelineTier
class EmittedRegistryAgreementTest {

    /**
     * The corpus documents whose two printings differ today. Struck off as each cause is fixed; a
     * document that starts or stops disagreeing fails this test either way. Empty is the state in
     * which the second producer can be retired, and this test with it.
     */
    private static final Set<String> KNOWN_DISAGREEMENTS = Set.of();

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

            if (!printer.print(incumbent).equals(printer.print(derived))) {
                differed.add(document.id());
            }
        }

        assertThat(unusable)
            .as("a document neither producer could answer for is a failure of this gate, not a "
                + "disagreement: the comparison never ran for it")
            .isEmpty();
        assertThat(documents).as("the corpus loaded").isNotEmpty();
        assertThat(differed)
            .as("the two producers' printed schemas, over %d corpus documents", documents.size())
            .isEqualTo(KNOWN_DISAGREEMENTS);
    }

    private static String brief(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
