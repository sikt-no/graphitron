package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R390 — a {@code @table} type reachable only through a directive-driven {@code @asConnection}
 * carrier must survive the connection rebuild. After
 * {@link ConnectionPromoter#rebuildAssembledForConnections} retypes a bare-list carrier to name its
 * synthesised Connection, the element type is referenced only through the Connection's {@code nodes}
 * / Edge's {@code node} type references. {@code SchemaTransformer} rebuilds its type map from the
 * concretely-traversed graph (type references are leaves), so without pinning the element type as an
 * additional root it — and its whole transitive {@code @reference} subgraph — would be pruned: the
 * rebuilt assembled schema would lack the type, its {@code <Type>Type} schema class would never emit
 * (the {@code cannot find symbol} build failure the consumer hit on RC20), and a nested carrier's
 * own {@code @asConnection} rewrite would be skipped because its parent is never visited.
 *
 * <p>The fixture is the consumer's reported shape mapped onto Sakila FKs: a plain {@code @table}
 * parent ({@code Store}) reaches a {@code Node} middle type ({@code Customer}) only through a
 * {@code @splitQuery @asConnection} carrier, and the middle type reaches a leaf {@code Node}
 * ({@code Payment}) two levels down through a nested {@code @reference} list.
 */
@PipelineTier
class NestedConnectionElementRetentionPipelineTest {

    // Store --@asConnection @splitQuery @reference--> Customer --@reference--> Payment
    private static final String NESTED_REFERENCE_SDL = """
        type Query { store: Store }
        type Store @table(name: "store") {
            customers: [Customer!]! @asConnection @splitQuery
                @reference(path: [{key: "customer_store_id_fkey"}])
                @defaultOrder(primaryKey: true)
        }
        type Customer implements Node @table(name: "customer") {
            id: ID! @nodeId
            payments: [Payment!]!
                @reference(path: [{key: "payment_customer_id_fkey"}])
                @defaultOrder(primaryKey: true)
        }
        type Payment implements Node @table(name: "payment") {
            id: ID! @nodeId
            amount: String @field(name: "AMOUNT")
        }
        """;

    @Test
    void nestedReferenceElementSurvivesTheConnectionRebuild() {
        var bundle = TestSchemaHelper.buildBundle(NESTED_REFERENCE_SDL);

        // Classified (record + fetcher emit off this) ...
        assertThat(bundle.model().types())
            .as("the connection element and its nested @reference child are classified")
            .containsKey("Customer")
            .containsKey("Payment");

        // ... and present in the rebuilt assembled schema, so ObjectTypeGenerator emits their
        // <Type>Type schema classes (the absence of which is the consumer's javac failure).
        assertThat(bundle.assembled().getType("Customer"))
            .as("connection element type retained")
            .isNotNull();
        assertThat(bundle.assembled().getType("Payment"))
            .as("nested @reference type two levels down retained")
            .isNotNull();
    }

    // As above, but the nested @reference list also carries @asConnection (the consumer's "variant 3").
    private static final String NESTED_CONNECTION_SDL = """
        type Query { store: Store }
        type Store @table(name: "store") {
            customers: [Customer!]! @asConnection @splitQuery
                @reference(path: [{key: "customer_store_id_fkey"}])
                @defaultOrder(primaryKey: true)
        }
        type Customer implements Node @table(name: "customer") {
            id: ID! @nodeId
            payments: [Payment!]! @asConnection @splitQuery
                @reference(path: [{key: "payment_customer_id_fkey"}])
                @defaultOrder(primaryKey: true)
        }
        type Payment implements Node @table(name: "payment") {
            id: ID! @nodeId
            amount: String @field(name: "AMOUNT")
        }
        """;

    @Test
    void nestedAsConnectionCarrierIsActuallyRetyped() {
        var bundle = TestSchemaHelper.buildBundle(NESTED_CONNECTION_SDL);

        var customer = (GraphQLObjectType) bundle.assembled().getType("Customer");
        assertThat(customer).as("nested connection's parent type retained").isNotNull();

        var payments = customer.getFieldDefinition("payments");
        assertThat(GraphQLTypeUtil.simplePrint(payments.getType()))
            .as("the nested @asConnection carrier is retyped to its synthesised Connection, "
                + "not left as a bare list while the fetcher is connection-shaped")
            .isEqualTo("CustomerPaymentsConnection!");

        assertThat(bundle.model().types().get("CustomerPaymentsConnection"))
            .isInstanceOf(GraphitronType.ConnectionType.class);
    }
}
