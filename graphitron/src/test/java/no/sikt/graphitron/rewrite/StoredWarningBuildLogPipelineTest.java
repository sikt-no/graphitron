package no.sikt.graphitron.rewrite;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A warning-severity defect reaches the build log as an error-severity one reaches the build's
 * errors: logged once, in the {@code file:line:column: warning:} form, with the statement and code
 * the editor shows, a round the store refuses included. It is not a build warning, so the editor and
 * the MCP server, which read it off the diagnostic view's entry arm, show it once.
 *
 * <p>{@code defects/node-metadata-unreadable} holds the one warning-severity code today,
 * {@code NODE_METADATA_UNREADABLE}, beside an error, so its round is refused and still logs the
 * warning.
 */
@PipelineTier
class StoredWarningBuildLogPipelineTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "defects/node-metadata-unreadable";

    @Test
    void aWarningDefectIsLoggedByTheBuild(@TempDir Path tmp) {
        var ctx = context(tmp);
        var logger = (Logger) LoggerFactory.getLogger(GraphQLRewriteGenerator.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            FACTS.read(DOCUMENT, graph -> catchThrowable(() ->
                new GraphQLRewriteGenerator(ctx, graph.handle(), graph.schema()).validate()));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .filteredOn(m -> m.contains("NODE_METADATA_UNREADABLE"))
            .singleElement()
            .satisfies(m -> assertThat(m).contains(": warning: "));
    }

    @Test
    void aWarningDefectIsNoBuildWarningAndShowsOnceInTheEditor(@TempDir Path tmp) {
        var ctx = context(tmp);
        FACTS.read(DOCUMENT, graph -> {
            var output = new GraphQLRewriteGenerator(ctx, graph.handle(), graph.schema()).buildOutput();
            assertThat(output.warnings())
                .as("the defect rides beside the build warnings, not among them")
                .noneMatch(w -> w.message().contains("NODE_METADATA_UNREADABLE"));
            var rows = graph.handle().dsl().selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(graph.handle().graphName()),
                    DIAGNOSTIC.LSP_CODE.eq("GRAPHITRON_NODE_METADATA_UNREADABLE"))
                .fetch();
            assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.getSeverity()).isEqualTo("warning"));
            return null;
        });
    }

    private static RunContext context(Path tmp) {
        return new RunContext(List.of(), tmp, "StoredWarningBuildLogPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
    }
}
