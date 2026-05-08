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
    void rejects_whenNodeTypeKeyArityExceeds22() {
        // jOOQ's typed Record/Row caps at Row22. A NodeType with > 22 key columns cannot be
        // expressed as a typed Record<N>, so NodeIdLeafResolver.resolve must reject at
        // classification time rather than letting the emitter crash. The nodeidfixture
        // catalog carries a deliberately oversized NodeType (`too_wide`, 23-column PK) for
        // this guard; see NodeIdFixtureGenerator.METADATA and init.sql.
        String sdl = """
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            type TooWide implements Node @table(name: "too_wide") @node { id: ID! }
            type Query {
                barsByTooWide(tooWideIds: [ID!]! @nodeId(typeName: "TooWide")): [Bar!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var barField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("barsByTooWide");
        GraphQLArgument arg = barField.getArgument("tooWideIds");
        var barTable = bctx.resolveTable("bar").orElseThrow();

        var resolved = resolver.resolve(arg, "tooWideIds", barTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.Rejected.class);
        var rejected = (NodeIdLeafResolver.Resolved.Rejected) resolved;
        assertThat(rejected.rejection().message())
            .contains("TooWide")
            .contains("tooWideIds")
            .contains("23 key columns")
            .contains("Row22 cap");
    }

    @Test
    void multiHopIdentityCarryingLift_succeeds() {
        // R114: level_c → level_b → level_a chain. Both adjacent hops satisfy the lift predicate
        // (each step's source-side columns are a positional subset of the previous hop's
        // target-side columns by SQL name), so the terminal hop's source-side tuple lifts back
        // through the chain to a sub-tuple of the first hop's source-side columns on level_c.
        // The DirectFk arm carries the lifted tuple level_c.(k1, k2), positionally aligned with
        // LevelA's NodeType keys (k1, k2).
        String sdl = """
            type LevelA implements Node @table(name: "level_a") @node { id: ID! }
            type LevelC @table(name: "level_c") {
                cId: String! @field(name: "c")
            }
            type Query {
                levelCsByLevelA(
                    levelAId: ID! @nodeId(typeName: "LevelA") @reference(path: [
                        {key: "level_c_level_b_fk"},
                        {key: "level_b_level_a_fk"}
                    ])
                ): [LevelC!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("levelCsByLevelA");
        GraphQLArgument arg = queryField.getArgument("levelAId");
        var levelCTable = bctx.resolveTable("level_c").orElseThrow();

        var resolved = resolver.resolve(arg, "levelAId", levelCTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.FkTarget.DirectFk.class);
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk) resolved;
        assertThat(direct.refTypeName()).isEqualTo("LevelA");
        assertThat(direct.targetTable().tableName()).isEqualToIgnoringCase("level_a");
        assertThat(direct.keyColumns()).extracting(c -> c.sqlName()).containsExactly("k1", "k2");
        assertThat(direct.joinPath()).hasSize(2);
        // Lifted tuple is on level_c (the parent's own table) — k1 and k2 positions of hop[0]'s
        // source-side columns. SQL names line up positionally with LevelA's NodeType keys, so
        // the predicate compiles to DSL.row(level_c.K1, level_c.K2).in(decodedKeys) — a single-
        // table SELECT, identical in shape to single-hop direct-FK.
        assertThat(direct.liftedSourceColumns())
            .extracting(c -> c.sqlName())
            .containsExactly("k1", "k2");
    }

    @Test
    void multiHopLiftTranslationRejected() {
        // R114: lift_fail_c -> lift_fail_b -> lift_fail_a chain. hop[0] uses fk_b -> b_id (so
        // lift_fail_b's source-side columns from c are (b_id) only); hop[1] uses (a_k1, a_k2)
        // -> (k1, k2). Lift predicate at i=1 requires hop[1].sourceSide ⊂ hop[0].targetSide
        // by SQL name; (a_k1, a_k2) is NOT a subset of (b_id), so the lift fails. The resolver
        // rejects with the LIFT_FAILURE_MARKER text.
        String sdl = """
            type LiftFailA implements Node @table(name: "lift_fail_a") @node { id: ID! }
            type LiftFailC @table(name: "lift_fail_c") {
                cId: String! @field(name: "c_id")
            }
            type Query {
                liftFailCsByA(
                    aId: ID! @nodeId(typeName: "LiftFailA") @reference(path: [
                        {key: "lift_fail_c_b_fk"},
                        {key: "lift_fail_b_a_fk"}
                    ])
                ): [LiftFailC!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("liftFailCsByA");
        GraphQLArgument arg = queryField.getArgument("aId");
        var liftFailCTable = bctx.resolveTable("lift_fail_c").orElseThrow();

        var resolved = resolver.resolve(arg, "aId", liftFailCTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.Rejected.class);
        var rejected = (NodeIdLeafResolver.Resolved.Rejected) resolved;
        // Anchor on the constant-named marker rather than copying prose verbatim — the
        // R114 diagnostic-anchoring policy.
        assertThat(rejected.rejection().message())
            .contains(NodeIdLeafResolver.LIFT_FAILURE_MARKER)
            .contains("aId")
            .contains("hop 2");
    }

    @Test
    void multiHopConditionStepRejected() {
        // R114: a condition step inside a multi-hop @reference path is rejected. Every step
        // must be a FkJoin; the resolver routes ConditionJoin steps to a Rejected with the
        // CONDITION_STEP_MARKER text. The first hop is a real FK; the second is a condition
        // method on TestConditionStub.
        String sdl = """
            type LevelA implements Node @table(name: "level_a") @node { id: ID! }
            type LevelB @table(name: "level_b") {
                s: String!
            }
            type Query {
                levelBsByLevelA(
                    levelAId: ID! @nodeId(typeName: "LevelA") @reference(path: [
                        {key: "level_b_level_a_fk"},
                        {condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}
                    ])
                ): [LevelB!]!
            }
            """;
        var bctx = buildBuildContext(sdl);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("levelBsByLevelA");
        GraphQLArgument arg = queryField.getArgument("levelAId");
        var levelBTable = bctx.resolveTable("level_b").orElseThrow();

        var resolved = resolver.resolve(arg, "levelAId", levelBTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.Rejected.class);
        var rejected = (NodeIdLeafResolver.Resolved.Rejected) resolved;
        assertThat(rejected.rejection().message())
            .contains(NodeIdLeafResolver.CONDITION_STEP_MARKER)
            .contains("levelAId")
            .contains("step 2");
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
