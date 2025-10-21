package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AddKeyDirectivesTest {
    @Test
    public void testTransform() {
        var registry = new SchemaParser().parse("""
                directive @key(fields: federation__FieldSet!, resolvable: Boolean = true) repeatable on INTERFACE | OBJECT
                scalar federation__FieldSet

                interface Node { id: ID! }

                type Query {
                    someType(
                        param: String!
                    ): [SomeType]
                }

                type SomeType implements Node {
                    id: ID!
                    field: String!
                }
                """);
        AddKeyDirectives.transform(registry);

        var actual = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
                .includeDirectiveDefinitions(false)
                .setComparators(GraphqlTypeComparatorRegistry.AS_IS_REGISTRY))
                .print(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry));

        assertEquals("""
                interface Node {
                  id: ID!
                }

                type Query {
                  someType(param: String!): [SomeType]
                }

                type SomeType implements Node @key(fields : "id", resolvable : true) {
                  id: ID!
                  field: String!
                }

                scalar federation__FieldSet
                """, actual);
    }
}
