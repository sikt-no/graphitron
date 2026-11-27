package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys {@link RootQuery} states for a root lookup whose input object carries a node id, read over
 * the {@code scenarios/inline-list-lookup-key} fact document: {@code Query.customersByKey} takes a
 * {@code CustomerKey} of a column, {@code email}, and a store's node id, {@code store}, whose key the
 * customer's foreign key carries back to {@code customer.store_id}.
 */
@PipelineTier
class RootLookupKeysTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static Map<String, List<RootQuery.LookupArg>> stated;

    @BeforeAll
    static void read() {
        stated = FACTS.read("scenarios/inline-list-lookup-key", graph -> RootQuery.statedLookupKeys(graph.handle()));
    }

    @Test
    void anInputObjectsNodeIdIsDecodedOntoTheColumnItsForeignKeyCarriesItTo() {
        assertThat(stated.get("Query.customersByKey")).singleElement()
            .isInstanceOfSatisfying(RootQuery.LookupArg.Fields.class, fields -> {
                assertThat(fields.argument()).isEqualTo("key");
                assertThat(fields.list()).isTrue();
                assertThat(fields.fields()).hasSize(2);
                assertThat(fields.fields().get(0)).isInstanceOfSatisfying(RootQuery.FieldKey.Column.class, email -> {
                    assertThat(email.field()).isEqualTo("email");
                    assertThat(email.column().sqlName()).isEqualTo("email");
                });
                assertThat(fields.fields().get(1)).isInstanceOfSatisfying(RootQuery.FieldKey.NodeId.class, store -> {
                    assertThat(store.field()).isEqualTo("store");
                    assertThat(store.decode().nodeType()).isEqualTo("Store");
                    assertThat(store.decode().typeId()).isEqualTo("Store");
                    assertThat(store.decode().keyColumns()).extracting(ColumnRef::sqlName).containsExactly("store_id");
                    assertThat(store.targetColumns()).extracting(ColumnRef::sqlName).containsExactly("store_id");
                    assertThat(store.decode().table().tableName()).isEqualToIgnoringCase("store");
                });
            });
    }

    @Test
    void aLookupOfColumnsAloneIsStatedAsBefore() {
        assertThat(stated.get("Query.filmsByKey")).singleElement()
            .isInstanceOfSatisfying(RootQuery.LookupArg.Scalar.class, filmId ->
                assertThat(filmId.column().sqlName()).isEqualTo("film_id"));
    }
}
