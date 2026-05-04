package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolver-tier coverage for {@link NodeIdLeafResolver}: asserts that the resolver picks
 * {@link NodeIdLeafResolver.Resolved.FkTarget.DirectFk DirectFk} when the FK's target columns
 * positionally match the NodeType's key columns, and
 * {@link NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk TranslatedFk} when they differ
 * (the parent_node + child_ref reproducer where the FK targets {@code parent_node.alt_key}
 * but the NodeType keyColumn is {@code parent_node.pk_id}).
 *
 * <p>Resolver-tier unit test; the sibling resolvers ({@link OrderByResolver},
 * {@link LookupMappingResolver}, etc.) are exercised end-to-end through pipeline tests. The
 * coverage matters here because the variant choice
 * influences both call-site projections (the carrier shape on the argument and input-field
 * sides differs only in error vs. success arms, which is observable downstream); a
 * resolver-tier assertion locks the variant choice itself, independent of carrier
 * construction.
 *
 * <p>Wiring: {@link GraphitronSchemaBuilder#buildContextForTests} runs the schema generator and
 * {@code TypeBuilder} but stops before field classification, returning the same fully-wired
 * {@link BuildContext} the orchestrator hands to {@link FieldBuilder}. This avoids consuming
 * the resolver via {@link FieldBuilder#classifyArgument} or
 * {@link BuildContext#classifyInputField}, so the resolver's variant choice is asserted
 * directly.
 */
@UnitTier
class NodeIdLeafResolverTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    @Test
    void directFk_whenFkTargetColumnsPositionallyMatchNodeTypeKeys() {
        // bar.id_1 → baz.id is a unique FK; baz's NodeType keyColumn is `id`. FK target {id}
        // positionally matches NodeType key {id} → DirectFk.
        String sdl = """
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            type Query {
                barsByBaz(bazIds: [ID!]! @nodeId(typeName: "Baz")): [Bar!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var barField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("barsByBaz");
        GraphQLArgument arg = barField.getArgument("bazIds");
        var barTable = bctx.resolveTable("bar").orElseThrow();

        var resolved = resolver.resolve(arg, "bazIds", barTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.FkTarget.DirectFk.class);
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk) resolved;
        assertThat(direct.refTypeName()).isEqualTo("Baz");
        assertThat(direct.targetTable().tableName()).isEqualToIgnoringCase("baz");
        assertThat(direct.keyColumns()).extracting(c -> c.sqlName()).containsExactly("id");
        assertThat(direct.fkSourceColumns()).extracting(c -> c.sqlName()).containsExactly("id_1");
        assertThat(direct.joinPath()).hasSize(1);
        assertThat(direct.decodeMethod().methodName()).isEqualTo("decodeBaz");
    }

    @Test
    void translatedFk_whenFkTargetColumnsDifferFromNodeTypeKeys() {
        // child_ref.parent_alt_key → parent_node.alt_key, but ParentNode's NodeType keyColumn
        // is parent_node.pk_id. FK target {alt_key} ≠ NodeType key {pk_id} → TranslatedFk.
        String sdl = """
            type ParentNode implements Node @table(name: "parent_node") @node { id: ID! }
            type ChildRef @table(name: "child_ref") {
                childId: String! @field(name: "child_id")
            }
            type Query {
                childRefsByParent(parentIds: [ID!]! @nodeId(typeName: "ParentNode")): [ChildRef!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("childRefsByParent");
        GraphQLArgument arg = queryField.getArgument("parentIds");
        var childRefTable = bctx.resolveTable("child_ref").orElseThrow();

        var resolved = resolver.resolve(arg, "parentIds", childRefTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk.class);
        var translated = (NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk) resolved;
        assertThat(translated.refTypeName()).isEqualTo("ParentNode");
        assertThat(translated.targetTable().tableName()).isEqualToIgnoringCase("parent_node");
        assertThat(translated.keyColumns()).extracting(c -> c.sqlName()).containsExactly("pk_id");
        assertThat(translated.joinPath()).hasSize(1);
    }

    @Test
    void directFk_alsoDrivesInputFieldSideClassification() {
        // The same DirectFk variant arises whether the @nodeId leaf is a top-level argument
        // or an input field on a @table-bound input type. Build the resolver once against an
        // input-field leaf and assert it lands in the DirectFk arm.
        String sdl = """
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            input BarFilterInput @table(name: "bar") {
                bazIds: [ID!] @nodeId(typeName: "Baz")
            }
            type Query { x: String }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var inputType = (GraphQLInputObjectType) bctx.schema.getType("BarFilterInput");
        GraphQLInputObjectField leaf = inputType.getFieldDefinition("bazIds");
        var barTable = bctx.resolveTable("bar").orElseThrow();

        var resolved = resolver.resolve(leaf, "bazIds", barTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.FkTarget.DirectFk.class);
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk) resolved;
        assertThat(direct.fkSourceColumns()).extracting(c -> c.sqlName()).containsExactly("id_1");
    }

    private static BuildContext buildBuildContext(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude() + sdl);
        return GraphitronSchemaBuilder.buildContextForTests(AttributedRegistry.from(registry), FIXTURE_CTX);
    }

    private static String prelude() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\ninterface Node { id: ID! }\n";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
