package no.sikt.graphitron.model;

import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.FederationLinkComposer;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tagged capture's stamps: the {@code scenarios/configured-tag} document reads its file under a
 * configured {@code <schemaInput tag>}, so the capture's assembly composes the corpus with the
 * synthesised {@code @link} importing {@code @tag}. The document claims the configuration and the
 * clean assembly; what is left here is what only the shipped bytes can say, or a store-wide row a
 * document cannot claim: the tagged file is stamped, the bundled directive vocabulary and
 * specification are stamped by the bytes this module ships, and the synthesised link's source, a
 * name no file holds, is recorded unstamped rather than stamped or refused.
 */
class TaggedCaptureStampTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String TAGGED = "scenarios/configured-tag";

    @Test
    @DisplayName("a tagged capture stamps the tagged file, and the bundled documents by the bytes this module ships")
    void aTaggedCaptureStampsItsSources() {
        FACTS.read(TAGGED, graph -> {
            var dsl = graph.handle().dsl();
            var file = dsl.select(STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE).from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq(TAGGED))
                .and(STORE_GRAPH_SCHEMA_INPUT.TAG.isNotNull())
                .fetchSingle(STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE);
            var stamps = dsl.select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP).from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.eq("SCHEMA_FILE"))
                .and(STORE_SOURCE.SOURCE_NAME.in(List.of(file, SchemaLoader.DIRECTIVES_SOURCE_NAME,
                    SchemaLoader.SPECIFICATION_SOURCE_NAME)))
                .fetchMap(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP);
            assertThat(stamps).as("the tagged file is stamped").containsKey(file);
            assertThat(stamps.get(file)).isNotNull();
            assertThat(stamps).containsEntry(SchemaLoader.DIRECTIVES_SOURCE_NAME,
                    SourceStamp.of(SchemaLoader.directivesSdl().getBytes(StandardCharsets.UTF_8)))
                .containsEntry(SchemaLoader.SPECIFICATION_SOURCE_NAME,
                    SourceStamp.of(SchemaLoader.specificationBytes()));
            // The source is store-wide, its row standing while some graph of the store reads it,
            // so whether it is here depends on the store's other graphs; where it is, it is
            // unstamped rather than stamped or refused.
            assertThat(dsl.select(STORE_SOURCE.STAMP).from(STORE_SOURCE)
                    .where(STORE_SOURCE.SOURCE_NAME.eq(FederationLinkComposer.CONFIGURED_SOURCE_NAME))
                    .fetch(STORE_SOURCE.STAMP))
                .as("the synthesised link is a source no file holds, recorded unstamped")
                .allMatch(java.util.Objects::isNull);
            return null;
        });
    }
}
