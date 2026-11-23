package no.sikt.graphitron.model;

import graphql.language.StringValue;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an issuing subgraph publishes for a type marked {@code @issue}, read off the two schemas capture
 * hands back for the {@code scenarios/voucher-issue} document: the emitted schema carries the voucher
 * field Graphitron mints alone, nullable as on the accepting side and kept from clients by
 * {@code @inaccessible}, and keys the type by it as a key this subgraph does not resolve, while the schema as written keeps every absorbed field and
 * has no voucher field, nobody having written one.
 */
class VoucherPublishedShapeTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static GraphQLSchema emitted;
    private static GraphQLSchema written;

    @BeforeAll
    static void read() {
        FACTS.read("scenarios/voucher-issue", graph -> {
            emitted = ((SchemaAssembly.Assembled) graph.schema().synthesised().orElseThrow()).schema();
            written = ((SchemaAssembly.Assembled) graph.schema().written().assembly()).schema();
            return null;
        });
    }

    @Test
    @DisplayName("the issuing type publishes the minted voucher alone, and the written schema keeps what it absorbs")
    void theVoucherAlone() {
        var emittedType = (GraphQLObjectType) emitted.getType("FilmRef");
        assertThat(emittedType.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
            .containsExactly("internalGraphitronVoucher");
        var writtenType = (GraphQLObjectType) written.getType("FilmRef");
        assertThat(writtenType.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
            .containsExactly("filmId", "title");
    }

    @Test
    @DisplayName("the voucher is a nullable String, as on the accepting side, so a row it cannot sign keeps its place")
    void theVoucherIsNullable() {
        var type = (GraphQLObjectType) emitted.getType("FilmRef");
        assertThat(GraphQLTypeUtil.simplePrint(type.getFieldDefinition("internalGraphitronVoucher").getType()))
            .isEqualTo("String");
    }

    @Test
    @DisplayName("the voucher is kept from clients, and keys the type as a key this subgraph does not resolve")
    void theVoucherKey() {
        var type = (GraphQLObjectType) emitted.getType("FilmRef");
        assertThat(type.getFieldDefinition("internalGraphitronVoucher").getAppliedDirectives("inaccessible")).hasSize(1);
        var keys = type.getAppliedDirectives("key");
        assertThat(keys).hasSize(1);
        // Federation's FieldSet is no scalar graphql-java coerces, so it arrives as its literal.
        StringValue fields = keys.getFirst().getArgument("fields").getValue();
        Boolean resolvable = keys.getFirst().getArgument("resolvable").getValue();
        assertThat(fields.getValue()).isEqualTo("internalGraphitronVoucher");
        assertThat(resolvable).isFalse();
    }
}
