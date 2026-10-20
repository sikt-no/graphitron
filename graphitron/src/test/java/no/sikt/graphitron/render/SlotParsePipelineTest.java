package no.sikt.graphitron.render;

import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.derive.StoreNodeTables;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.plan.KeyProjectionCommands;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link SlotParse} arm each node-id key slot takes, read off every carrier a decode emitter
 * reads its key columns from: the node type's own decode, the {@code _entities} node-id alternative,
 * a jOOQ-record writer's key decode, and the record decode built from a store-read key projection.
 * The arm is a function of the column alone, so every carrier holding the column has to answer the
 * same, and the last carrier is the one whose columns come back out of the store, which is what
 * makes {@code sql_column.database_type} part of what this pins.
 *
 * <p>The node types are real sakila tables: {@code customer} has no converter, and
 * {@code converter_campus_term} and {@code converter_id_change} each carry one converter-backed key
 * column, to {@code String} over a {@code bigint} domain and to {@code LocalDateTime} over a
 * {@code timestamptz} domain. The catalog has no converter to a class jOOQ cannot parse text into, so
 * that arm is pinned over a column ref of the test's own.
 */
@PipelineTier
class SlotParsePipelineTest {

    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static final String SDL = """
        type Customer implements Node @table(name: "customer") @node { id: ID! }
        type ConverterCampusTerm implements Node @table(name: "converter_campus_term") @node { id: ID! }
        type ConverterIdChange implements Node @table(name: "converter_id_change") @node { id: ID! }
        input ModifyTermInput { id: ID! @nodeId(typeName: "ConverterCampusTerm") }
        input ModifyChangeInput { id: ID! @nodeId(typeName: "ConverterIdChange") }
        type Query {
            customer: Customer
            modifyTerm(in: ModifyTermInput!): String
                @service(service: {className: "%1$s", method: "modifyConverterCampusTermRecord"})
            modifyChange(in: ModifyChangeInput!): String
                @service(service: {className: "%1$s", method: "modifyConverterIdChangeRecord"})
        }
        """.formatted(SERVICE_STUB);

    /** The arm per key column, in key order, for each node type the fixture declares. */
    private static final Map<String, List<SlotParse>> EXPECTED = Map.of(
        "Customer", List.of(new SlotParse.ColumnType()),
        "ConverterCampusTerm", List.of(new SlotParse.UserType("java.lang.String"), new SlotParse.ColumnType()),
        "ConverterIdChange", List.of(new SlotParse.ColumnType(), new SlotParse.UserType("java.time.LocalDateTime")));

    @TempDir
    Path tmp;

    @Test
    void theNodeTypesOwnDecode() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        EXPECTED.forEach((type, arms) ->
            assertThat(arms(((NodeType) schema.type(type)).decodeMethod().outputColumnShape()))
                .as(type).isEqualTo(arms));
    }

    @Test
    void theEntitiesNodeIdAlternative() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        EXPECTED.forEach((type, arms) -> {
            var alternative = (KeyAlternative.NodeId) schema.entityResolution(type).alternatives().stream()
                .filter(KeyAlternative.NodeId.class::isInstance)
                .findFirst().orElseThrow();
            assertThat(arms(alternative.columns())).as(type).isEqualTo(arms);
        });
    }

    @Test
    void aJooqRecordWritersKeyDecode() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        assertThat(arms(keyDecode(schema, "modifyTerm").targetColumns()))
            .isEqualTo(EXPECTED.get("ConverterCampusTerm"));
        assertThat(arms(keyDecode(schema, "modifyChange").targetColumns()))
            .isEqualTo(EXPECTED.get("ConverterIdChange"));
    }

    /**
     * The record decode {@code TypeFetcherGenerator} builds from a key projection row, whose key
     * columns are the ones {@link StoreNodeTables} reads back out of a captured store. Built here the
     * way that generator builds it, over a projection carrying the store's columns, since what is
     * under test is the columns and not the {@code argMapping} that would name one.
     */
    @Test
    void aRecordDecodeBuiltFromAStoreReadKeyProjection() {
        var tables = captured();
        EXPECTED.forEach((type, arms) -> {
            var node = tables.get(type).orElseThrow();
            var row = KeyProjectionCommands.produce(new ResolvedKeyProjections.Projections(List.of(
                new ResolvedKeyProjections.Projection("Query", "pick", "in.id", null, type,
                    node.typeId(), node.table(), node.keyColumns(), node.keyColumns().getFirst()))))
                .rows().getFirst();
            var decode = new CallSiteExtraction.NodeIdDecodeRecord(NodeIdEncoderRef.of(DEFAULT_OUTPUT_PACKAGE),
                row.nodeTypeName(), row.typeId(), row.keyColumns(), row.nodeTable(), false);
            assertThat(arms(decode.keyColumns())).as(type).isEqualTo(arms);
        });
    }

    @Test
    void aConverterToAClassJooqCannotParseTakesTheDatabaseType() {
        assertThat(SlotParse.of(new ColumnRef("code", "CODE", "com.example.OrgCode", "java.lang.Long")))
            .isEqualTo(new SlotParse.DatabaseType());
        assertThat(SlotParse.of(new ColumnRef("data", "DATA", "java.lang.Object", "java.lang.String")))
            .as("OTHER's Object would parse any text")
            .isEqualTo(new SlotParse.DatabaseType());
    }

    private static List<SlotParse> arms(List<ColumnRef> columns) {
        return columns.stream().map(SlotParse::of).toList();
    }

    private static CallSiteExtraction.RecordKeyDecode keyDecode(GraphitronSchema schema, String queryField) {
        var field = (ServiceField) schema.field("Query", queryField);
        var shape = field.serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg)
            .map(e -> ((MappingEntry.FromArg) e).shape())
            .findFirst().orElseThrow();
        var keyDecodes = ((ValueShape.JooqRecordInput) shape).carrier().keyDecodes();
        assertThat(keyDecodes).as(queryField).hasSize(1);
        return keyDecodes.getFirst();
    }

    /** Captures {@link #SDL} against the real fixture catalog, as {@code StoreNodeTablesTest} does. */
    private StoreNodeTables.Tables captured() {
        var ctx = testContext();
        CapturedStore.writeSource(tmp, SDL);
        try (var store = FactStores.inMemory()) {
            CapturedStore.capture(store.dsl(), CapturedStore.graph(tmp), CapturedStore.corpusOf(tmp),
                CatalogInput.of(ctx));
            return StoreNodeTables.read(store.dsl(), CapturedStore.GRAPH);
        }
    }
}
