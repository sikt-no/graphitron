package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Public (Sakila) catalog context. The self-FK fixture (the {@code email} / {@code mailbox}
     * pair, with the self-FK {@code email_in_reply_to_fk} sharing the {@code mailbox_id} child column
     * with the cross-table {@code email.mailbox_id -> mailbox} FK) lives in the public schema so the
     * same shape is reachable from both the {@code graphitron}-module classifier tests here and the
     * {@code graphitron-sakila-example} execution tier. See {@code init.sql}.
     */
    private static final RewriteContext PUBLIC_CTX = TestConfiguration.testContext();

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
        // Level_c → level_b → level_a chain. Both adjacent hops satisfy the lift predicate
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
        // Lift_fail_c -> lift_fail_b -> lift_fail_a chain. hop[0] uses fk_b -> b_id (so
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
        // Anchor on the constant-named marker rather than copying prose verbatim, per the
        // diagnostic-anchoring policy.
        assertThat(rejected.rejection().message())
            .contains(NodeIdLeafResolver.LIFT_FAILURE_MARKER)
            .contains("aId")
            .contains("hop 2");
    }

    @Test
    void multiHopConditionStepRejected() {
        // A condition step inside a multi-hop @reference path is rejected. Every step
        // must be FK-derived; the resolver routes condition-join steps to a Rejected with the
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

    // ===== self-FK @nodeId @reference on a same-table leaf =====

    @Test
    void selfFkReference_resolvesToDirectFk_landingOnSelfFkChildColumns() {
        // A same-table @nodeId(typeName: "Email") carrying an explicit @reference naming
        // the self-FK email_in_reply_to_fk is NOT own-PK identity — it points at a *different* email
        // row of the same table. The line-269 same-table short-circuit is gated on @reference being
        // absent, so this falls through to resolveFkJoinPath, which orients the self-FK with
        // selfRefFkOnSource=true. Result: DirectFk whose liftedSourceColumns are the self-FK's child
        // columns (mailbox_id, in_reply_to_no) on email's own table — the decoded Email key
        // (mailbox_id, message_no) maps onto them. No new sealed variant; same data shape as a
        // cross-table FK.
        String sdl = """
            type Email implements Node @table(name: "email") @node { id: ID! }
            type Query {
                emailReplies(
                    parentId: ID! @nodeId(typeName: "Email")
                        @reference(path: [{key: "email_in_reply_to_fk"}])
                ): [Email!]!
            }
            """;
        var bctx = buildBuildContext(sdl, PUBLIC_CTX);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("emailReplies");
        GraphQLArgument arg = queryField.getArgument("parentId");
        var emailTable = bctx.resolveTable("email").orElseThrow();

        var resolved = resolver.resolve(arg, "parentId", emailTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.FkTarget.DirectFk.class);
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk) resolved;
        assertThat(direct.refTypeName()).isEqualTo("Email");
        assertThat(direct.targetTable().tableName()).isEqualToIgnoringCase("email");
        // NodeType key columns = email's composite PK (the decode order).
        assertThat(direct.keyColumns()).extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "message_no");
        // Lifted columns = the self-FK child columns on email's own table, positionally aligned with
        // the decoded keys. The shared mailbox_id is the overlap with the cross-table FK (deduped).
        assertThat(direct.liftedSourceColumns()).extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");
        assertThat(direct.joinPath()).hasSize(1);
        assertThat(direct.decodeMethod().methodName()).isEqualTo("decodeEmail");
    }

    @Test
    void sameTableNodeId_withoutReference_staysOwnPkIdentity() {
        // The same email-backed leaf WITHOUT @reference is unchanged: a same-table
        // @nodeId is own-PK identity (SameTable), not a self-FK. The @reference is the only thing that
        // flips the meaning; absent it, line 269 still short-circuits to SameTable.
        String sdl = """
            type Email implements Node @table(name: "email") @node { id: ID! }
            type Query {
                emailsByIds(ids: [ID!]! @nodeId(typeName: "Email")): [Email!]!
            }
            """;
        var bctx = buildBuildContext(sdl, PUBLIC_CTX);
        var resolver = bctx.nodeIdLeafResolver();

        var queryField = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("emailsByIds");
        GraphQLArgument arg = queryField.getArgument("ids");
        var emailTable = bctx.resolveTable("email").orElseThrow();

        var resolved = resolver.resolve(arg, "ids", emailTable);

        assertThat(resolved).isInstanceOf(NodeIdLeafResolver.Resolved.SameTable.class);
        var sameTable = (NodeIdLeafResolver.Resolved.SameTable) resolved;
        assertThat(sameTable.keyColumns()).extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "message_no");
    }

    @Test
    void selfFkReference_classifierAndRecordPaths_landIdenticalChildColumns() {
        // "same-table + @reference => self-FK, not identity" lives as a one-line
        // predicate at two sites: the classifier path (NodeIdLeafResolver.resolve) and the
        // @service jOOQ-record path (BuildContext.resolveRecordFkTargetColumns). Both must map the
        // decoded Email key onto the SAME self-FK child columns. Feed the resolver's own node keys into
        // the record path so the two are pinned against one shared input; drift would show as differing
        // target columns. (The without-@reference identity contrast is staysOwnPkIdentity above.)
        String sdl = """
            type Email implements Node @table(name: "email") @node { id: ID! }
            type Query {
                emailReplies(
                    parentId: ID! @nodeId(typeName: "Email")
                        @reference(path: [{key: "email_in_reply_to_fk"}])
                ): [Email!]!
            }
            """;
        var bctx = buildBuildContext(sdl, PUBLIC_CTX);
        var resolver = bctx.nodeIdLeafResolver();
        var emailTable = bctx.resolveTable("email").orElseThrow();

        var arg = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("emailReplies").getArgument("parentId");
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk)
            resolver.resolve(arg, "parentId", emailTable);

        // Classifier path: decoded keys land on the self-FK child columns.
        assertThat(direct.liftedSourceColumns()).extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");

        // Record-population path: the same self-FK, fed the resolver's own node keys, lands the
        // decode on the identical child columns (never the record's own PK mailbox_id, message_no).
        var recordTargets = bctx.resolveRecordFkTargetColumns(
            emailTable, "email", direct.keyColumns(), Optional.of("email_in_reply_to_fk"));
        assertThat(recordTargets).isInstanceOf(BuildContext.RecordFkTargets.Resolved.class);
        assertThat(((BuildContext.RecordFkTargets.Resolved) recordTargets).targetColumns())
            .extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no")
            .isEqualTo(direct.liftedSourceColumns().stream().map(c -> c.sqlName()).toList());
    }

    @Test
    void reorderedFk_classifierAndRecordPaths_reconcileIdentically_offIdentityPermutation() {
        // Reconciliation anti-drift, OFF the identity permutation. The email self-FK declares
        // its child columns in node-key order, so the email anti-drift test above exercises both paths
        // only on the identity permutation. Orientation is the self-FK-specific axis and is shared via
        // resolveFkSlots; but RECONCILIATION (aligning FK child columns to node-key decode order) is
        // orientation-agnostic and genuinely duplicated: the classifier path permutes via
        // permutationToKeyColumns, the record path matches via a targetSide-name loop.
        // The reordered_fk_child -> reordered_pk_parent FK
        // references the parent PK in (pk_b, pk_c, pk_a) order while __NODE_KEY_COLUMNS is
        // (pk_a, pk_b, pk_c), forcing a NON-identity permutation through both reconciliation
        // implementations; they must still land identical child columns (fk_a, fk_b, fk_c). This pins
        // the reconciliation surface the self-FK rides on, which the email fixture cannot reach.
        String sdl = """
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild @table(name: "reordered_fk_child") {
                childId: String! @field(name: "child_id")
            }
            type Query {
                childrenByParent(parentIds: [ID!]! @nodeId(typeName: "ReorderedPkParent")): [ReorderedChild!]!
            }
            """;
        var bctx = buildBuildContext(sdl); // FIXTURE_CTX (nodeidfixture), where the reordered fixture lives
        var resolver = bctx.nodeIdLeafResolver();
        var childTable = bctx.resolveTable("reordered_fk_child").orElseThrow();

        var arg = ((GraphQLObjectType) bctx.schema.getType("Query"))
            .getFieldDefinition("childrenByParent").getArgument("parentIds");
        var direct = (NodeIdLeafResolver.Resolved.FkTarget.DirectFk)
            resolver.resolve(arg, "parentIds", childTable);

        // Classifier-path reconciliation: permutationToKeyColumns puts the lifted columns in node-key (decode) order.
        assertThat(direct.liftedSourceColumns()).extracting(c -> c.sqlName())
            .containsExactly("fk_a", "fk_b", "fk_c");

        // Record-path reconciliation: the targetSide-name match loop, fed the resolver's own node keys, lands the
        // identical child columns — proving the two duplicated reconciliations agree off the identity
        // permutation, not just on it.
        var recordTargets = bctx.resolveRecordFkTargetColumns(
            childTable, "reordered_pk_parent", direct.keyColumns(), Optional.empty());
        assertThat(recordTargets).isInstanceOf(BuildContext.RecordFkTargets.Resolved.class);
        assertThat(((BuildContext.RecordFkTargets.Resolved) recordTargets).targetColumns())
            .extracting(c -> c.sqlName())
            .containsExactly("fk_a", "fk_b", "fk_c")
            .isEqualTo(direct.liftedSourceColumns().stream().map(c -> c.sqlName()).toList());
    }

    private static BuildContext buildBuildContext(String sdl) {
        return buildBuildContext(sdl, FIXTURE_CTX);
    }

    private static BuildContext buildBuildContext(String sdl, RewriteContext ctx) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude() + sdl);
        return GraphitronSchemaBuilder.buildContextForTests(AttributedRegistry.from(registry), ctx);
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
