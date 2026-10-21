package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLDirective;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class DirectiveDefinitionEmitterTest {

    @Test
    void definitions_includesUserDeclaredDirectives() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @auth(roles: [String!]) on FIELD_DEFINITION
            type Query { x: String }
            """).assembled();
        var names = DirectiveDefinitionEmitter.definitions(schema).stream()
            .map(GraphQLDirective::getName).toList();
        assertThat(names).contains("auth");
    }

    @Test
    void isSeededByGraphqlJava_holdsForIncludeAndSkipOnly() {
        var schema = TestSchemaHelper.buildBundle("type Query { x: String }").assembled();
        var seeded = DirectiveDefinitionEmitter.definitions(schema).stream()
            .filter(DirectiveDefinitionEmitter::isSeededByGraphqlJava)
            .map(GraphQLDirective::getName).toList();
        assertThat(seeded).containsExactlyInAnyOrder("include", "skip");
    }

    @Test
    void definitions_sortedByName() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @zebra on FIELD_DEFINITION
            directive @alpha on FIELD_DEFINITION
            type Query { x: String }
            """).assembled();
        var names = DirectiveDefinitionEmitter.definitions(schema).stream()
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
        var rendered = DirectiveDefinitionEmitter
            .buildDefinitionMethod("directiveDefinition_auth", authDef).toString();
        assertThat(rendered)
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
        var rendered = DirectiveDefinitionEmitter
            .buildDefinitionMethod("directiveDefinition_tag", schema.getDirective("tag"))
            .toString();
        assertThat(rendered).contains(".repeatable(true)");
    }
}
