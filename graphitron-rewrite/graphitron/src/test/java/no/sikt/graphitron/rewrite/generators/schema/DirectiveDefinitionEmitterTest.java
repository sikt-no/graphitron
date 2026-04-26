package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLDirective;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectiveDefinitionEmitterTest {

    @Test
    void survivors_excludesAllGeneratorOnlyDirectives() {
        var schema = TestSchemaHelper.buildBundle("type Query { x: String }").assembled();
        var names = DirectiveDefinitionEmitter.survivors(schema).stream()
            .map(GraphQLDirective::getName).toList();
        assertThat(names).isNotEmpty();
        names.forEach(n -> assertThat(n)
            .isNotIn("table", "record", "field", "reference", "condition",
                     "externalField", "notGenerated", "splitQuery", "service",
                     "tableMethod", "asConnection", "discriminate", "discriminator",
                     "node", "nodeId", "mutation", "orderBy", "lookupKey", "error",
                     "defaultOrder", "multitableReference", "enum", "index", "order",
                     "experimental_constructType"));
    }

    @Test
    void survivors_includesUserDeclaredDirectives() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @auth(roles: [String!]) on FIELD_DEFINITION
            type Query { x: String }
            """).assembled();
        var names = DirectiveDefinitionEmitter.survivors(schema).stream()
            .map(GraphQLDirective::getName).toList();
        assertThat(names).contains("auth");
    }

    @Test
    void survivors_sortedByName() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @zebra on FIELD_DEFINITION
            directive @alpha on FIELD_DEFINITION
            type Query { x: String }
            """).assembled();
        var names = DirectiveDefinitionEmitter.survivors(schema).stream()
            .map(GraphQLDirective::getName).toList();
        assertThat(names).isSorted();
    }

    @Test
    void buildDefinition_emitsNameLocationsAndArguments() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @auth(roles: [String!], mode: String = "strict") on FIELD_DEFINITION | OBJECT
            type Query { x: String }
            """).assembled();
        var authDef = schema.getDirective("auth");
        var block = DirectiveDefinitionEmitter.buildDefinition(authDef).toString();
        assertThat(block)
            .contains("GraphQLDirective.newDirective()")
            .contains(".name(\"auth\")")
            .contains("DirectiveLocation.FIELD_DEFINITION")
            .contains("DirectiveLocation.OBJECT")
            .contains("GraphQLArgument.newArgument()")
            .contains(".name(\"roles\")")
            .contains(".name(\"mode\")");
    }

    @Test
    void buildDefinition_marksRepeatableDirectives() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @tag(name: String!) repeatable on FIELD_DEFINITION
            type Query { x: String }
            """).assembled();
        var block = DirectiveDefinitionEmitter.buildDefinition(schema.getDirective("tag")).toString();
        assertThat(block).contains(".repeatable(true)");
    }
}
