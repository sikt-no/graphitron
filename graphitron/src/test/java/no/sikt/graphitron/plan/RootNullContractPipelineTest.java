package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.CarrierDsl;
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
    void lookupKey_cardinalityFollowsTheReturn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languageBySingleKey(language_id: Int! @lookupKey @field(name: "language_id")): Language
                languagesByKey(language_id: [Int!]! @lookupKey @field(name: "language_id")): [Language]!
            }
            """);

        var relation = LauncherCommands.produce(schema,
            ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE), DEFAULT_OUTPUT_PACKAGE);

        var single = relation.rowFor("Query", "languageBySingleKey").orElseThrow();
        assertThat(single.source()).isInstanceOf(LaunchSource.KeyedLookup.class);
        assertThat(single.result()).isInstanceOf(ResultShape.SingleRecord.class);

        // The list lookup keeps its scatter, and with it the absent ordering slot.
        var list = relation.rowFor("Query", "languagesByKey").orElseThrow();
        assertThat(list.result()).isInstanceOf(ResultShape.RecordList.class);
        assertThat(((ResultShape.RecordList) list.result()).ordering()).isNull();
    }

    @Test
    void lookupKey_schemaFreeWalkMintsTheSameShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languageBySingleKey(language_id: Int! @lookupKey @field(name: "language_id")): Language
            }
            """);

        var relation = LauncherCommands.produceWithoutSchema(schema.fieldsOf("Query"),
            DEFAULT_OUTPUT_PACKAGE, CarrierDsl.ENV_ACQUIRED);

        var single = relation.rowFor("Query", "languageBySingleKey").orElseThrow();
        assertThat(single.result()).isInstanceOf(ResultShape.SingleRecord.class);
    }
}
