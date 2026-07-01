package no.sikt.graphitron.rewrite.generators.schema;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R229 integration test: pin graphql-java's bidirectional behavior at the
 * {@code GraphQLEnumValueDefinition.name(...).value(...)} boundary that the schema emitter (see
 * {@link EnumTypeGenerator#buildValueDefinitionMethod}) writes into the generated {@code <Name>Type.type()}
 * method.
 *
 * <p>These tests construct the {@link GraphQLEnumType} the same way the generated code does — by
 * calling {@code GraphQLEnumType.newEnum().value(GraphQLEnumValueDefinition.newEnumValueDefinition()
 * .name(sdl).value(runtime).build())} — and exercise the full graphql-java parse / serialize path
 * around it. They document the boundary contract R229 relies on: with {@code @field(name:)} lifted
 * into the {@code .value(...)} slot, graphql-java's wire ↔ runtime translation happens at the
 * boundary, in both directions, and the Java-side {@code TextMapLookup} translation step that
 * pre-R229 graphitron emitted is no longer needed.
 *
 * <p>This is execution-tier: the assertion is on observed graphql-java behavior, not on emitted
 * source. The unit-tier counterpart in {@link EnumTypeGeneratorTest} pins that the generator
 * actually writes the {@code .name(sdl).value(runtime)} pair into the {@code .type()} body.
 */
@ExecutionTier
class EnumSerializationExecutionTest {

    /**
     * Output round-trip: a resolver returns the {@code @field(name:)} runtime form ({@code "FØDSELSNUMMER"}),
     * graphql-java's Coercing layer matches it against the registered {@code .value(...)}, and the
     * response carries the SDL identifier ({@code FODSELSNUMMER}). Pre-R229 (with {@code .value(SDL)})
     * this would fail with {@code Can't serialize value ... Unknown value 'FØDSELSNUMMER'}.
     */
    @Test
    void directiveValueRoundTripsThroughCoercing() {
        var schema = schemaWithDirectiveEnum(env -> "FØDSELSNUMMER");
        var result = GraphQL.newGraphQL(schema).build()
            .execute(ExecutionInput.newExecutionInput().query("{ type }").build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.<Map<String, Object>>getData()).containsEntry("type", "FODSELSNUMMER");
    }

    /**
     * Input round-trip: the SDL identifier is what the user types ({@code FODSELSNUMMER}); graphql-java
     * delivers the registered {@code .value(...)} ({@code "FØDSELSNUMMER"}) to the resolver. Pins the
     * wire ↔ runtime translation on the input side — the same mechanism that lets graphitron retire
     * the Java-side {@code TextMapLookup} for filter / service args.
     */
    @Test
    void enumInputStillByName() {
        var observed = new String[1];
        var schema = schemaWithDirectiveEnum(env -> {
            // graphql-java has parsed the SDL identifier and handed us the runtime backing.
            observed[0] = (String) env.getArgument("input");
            // Pick something that round-trips to a known SDL identifier so the response carries a
            // signal independent of the captured argument.
            return "FØDSELSNUMMER";
        });
        var result = GraphQL.newGraphQL(schema).build()
            .execute(ExecutionInput.newExecutionInput()
                .query("{ echo(input: FODSELSNUMMER) }")
                .build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(observed[0]).isEqualTo("FØDSELSNUMMER");
        assertThat(result.<Map<String, Object>>getData()).containsEntry("echo", "FODSELSNUMMER");
    }

    /**
     * Values without {@code @field(name:)} continue to use the SDL identifier on both slots — the
     * directive arm doesn't change the identity case. graphql-java sees {@code .name("ANNET").value("ANNET")}
     * and round-trips cleanly without the lift mattering.
     */
    @Test
    void simpleValueRoundTripsWithoutDirective() {
        var schema = schemaWithDirectiveEnum(env -> "ANNET");
        var result = GraphQL.newGraphQL(schema).build()
            .execute(ExecutionInput.newExecutionInput().query("{ type }").build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.<Map<String, Object>>getData()).containsEntry("type", "ANNET");
    }

    /**
     * Builds the same enum shape {@link EnumTypeGenerator#buildValueDefinitionMethod} writes for
     * {@code FODSELSNUMMER @field(name: "FØDSELSNUMMER")} on a {@code PersonIdentifikasjon} enum,
     * plus a {@code Query} with one enum-returning field and one enum-arg-bearing field both
     * routed to {@code typeFetcher}.
     */
    private static GraphQLSchema schemaWithDirectiveEnum(graphql.schema.DataFetcher<String> typeFetcher) {
        var enumType = GraphQLEnumType.newEnum()
            .name("PersonIdentifikasjon")
            .value(GraphQLEnumValueDefinition.newEnumValueDefinition()
                .name("FODSELSNUMMER").value("FØDSELSNUMMER").build())
            .value(GraphQLEnumValueDefinition.newEnumValueDefinition()
                .name("ANNET").value("ANNET").build())
            .build();

        var query = GraphQLObjectType.newObject()
            .name("Query")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("type").type(enumType))
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("echo")
                .argument(graphql.schema.GraphQLArgument.newArgument()
                    .name("input").type(GraphQLNonNull.nonNull(enumType)))
                .type(enumType))
            .build();

        var codeRegistry = graphql.schema.GraphQLCodeRegistry.newCodeRegistry()
            .dataFetcher(graphql.schema.FieldCoordinates.coordinates("Query", "type"), typeFetcher)
            .dataFetcher(graphql.schema.FieldCoordinates.coordinates("Query", "echo"), typeFetcher)
            .build();

        return GraphQLSchema.newSchema()
            .query(query)
            .codeRegistry(codeRegistry)
            .build();
    }
}
