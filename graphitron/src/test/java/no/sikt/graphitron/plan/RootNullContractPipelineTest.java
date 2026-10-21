package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier assertions on the result shape a root table read gets: which single reads mint a
 * {@link ResultShape.SingleRecord}, over each source of a {@code QueryTableField}. Asserts on the
 * launcher commands, never on generated method bodies.
 */
@PipelineTier
class RootNullContractPipelineTest {

    @Test
    void singleLookupKey_mintsTheListShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languageBySingleKey(language_id: Int! @lookupKey @field(name: "language_id")): Language
            }
            """);

        var relation = LauncherCommands.produce(schema,
            ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE), DEFAULT_OUTPUT_PACKAGE);

        var row = relation.rowFor("Query", "languageBySingleKey").orElseThrow();
        assertThat(row.source()).isInstanceOf(LaunchSource.KeyedLookup.class);
        assertThat(row.result()).isInstanceOf(ResultShape.RecordList.class);
    }
}
