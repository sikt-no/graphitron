package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.model.Arrival;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.NodeProvenance;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * The inference gate: a {@code @table} object type that declares {@code implements Node} over a
 * table whose jOOQ class publishes {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} classifies as
 * a {@link GraphitronType.NodeType} without the author restating those values in {@code @node}. Plus
 * the two rules that make the gate coherent: every consumer of the "is a node" predicate treats an
 * inferred node like a declared one ({@link NodeDeclaration}), and a node's own {@code id} field
 * publishes the global ID even when the backing table has a column of that name.
 *
 * <p>Uses the {@code nodeidfixture} catalog. The tables this class leans on:
 * <ul>
 *   <li>{@code bar}: composite-key metadata, and <em>no</em> column named {@code id}, so an
 *       {@code id: ID!} field has nothing to shadow.</li>
 *   <li>{@code baz}: single-key metadata whose key column is itself named {@code id} — the
 *       shadowing shape. Degenerate on one axis: its {@code __NODE_TYPE_ID} is the literal
 *       {@code "Baz"}, so the encoded and raw forms are not distinguishable by typeId alone.</li>
 *   <li>{@code shared_node}: the same shadowing shape with the numeric typeId {@code "10154"}, so
 *       the raw column value and the encoded global ID are visibly different things rather than
 *       coinciding the way {@code baz}'s do.</li>
 *   <li>{@code collide_a} / {@code collide_b}: two tables publishing the same
 *       {@code __NODE_TYPE_ID}, the only way an <em>inferred</em> typeId collision can be written.</li>
 *   <li>{@code qux}: no metadata; the negative case.</li>
 * </ul>
 */
@PipelineTier
class NodeInferencePipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RunContext FIXTURE_CTX = new RunContext(
        List.of(), Path.of(""), "NodeInferencePipelineTest", Path.of(""), DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE);

    private static GraphitronSchema schema(String sdl) {
        return TestSchemaHelper.buildSchema(sdl, FIXTURE_CTX);
    }

    private static GraphitronSchemaBuilder.Bundle bundle(String sdl) {
        return TestSchemaHelper.buildBundle(sdl, FIXTURE_CTX);
    }

    private static NodeDeclaration fixtureNodes() {
        return TestSchemaHelper.nodeDeclaration(FIXTURE_CTX);
    }

    // ===== The gate =====

    @Test
    void implementsNodePlusMetadataClassifiesAsNodeWithTheCatalogsValues() {
        var schema = schema("""
            type Foo implements Node @table(name: "bar") { id: ID! name: String }
            type Query { foo: Foo }
            """);

        var foo = (GraphitronType.NodeType) schema.type("Foo");
        assertThat(foo.typeId())
            .as("typeId comes from __NODE_TYPE_ID, not the type name")
            .isEqualTo("Bar");
        assertThat(foo.nodeKeyColumns()).extracting(ColumnRef::sqlName).containsExactly("id_1", "id_2");
        assertThat(foo.provenance())
            .as("both axes are recorded as catalog-sourced")
            .isEqualTo(NodeProvenance.fromMetadata());

        // The headline consequence: `id` encodes with no directive written anywhere.
        var id = (ChildField.ColumnBackedField) schema.field("Foo", "id");
        assertThat(((CallSiteCompaction.NodeIdEncodeKeys) id.compaction()).encodeMethod().methodName())
            .isEqualTo("encodeFoo");
        assertThat(id.columns()).extracting(ColumnRef::sqlName).containsExactly("id_1", "id_2");
    }

    @Test
    void inferredAndDeclaredNodesOverTheSameTableAreIndistinguishable() {
        var inferred = (GraphitronType.NodeType) schema("""
            type Foo implements Node @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """).type("Foo");
        var declared = (GraphitronType.NodeType) schema("""
            type Foo implements Node @table(name: "bar") @node { id: ID! }
            type Query { foo: Foo }
            """).type("Foo");

        // Everything but provenance: the gate is a reachability change, not a second resolution path.
        assertThat(inferred.typeId()).isEqualTo(declared.typeId());
        assertThat(inferred.nodeKeyColumns()).isEqualTo(declared.nodeKeyColumns());
        assertThat(inferred.encodeMethod()).isEqualTo(declared.encodeMethod());
        assertThat(inferred.decodeMethod()).isEqualTo(declared.decodeMethod());
    }

    @Test
    void explicitNodeStillOverridesTheCatalogOnTheAxisItNames() {
        var foo = (GraphitronType.NodeType) schema("""
            type Foo implements Node @table(name: "bar") @node(typeId: "Pinned") { id: ID! }
            type Query { foo: Foo }
            """).type("Foo");

        assertThat(foo.typeId()).isEqualTo("Pinned");
        assertThat(foo.provenance()).isEqualTo(
            new NodeProvenance(NodeProvenance.Origin.DECLARED, NodeProvenance.Origin.METADATA));
    }

    @Test
    void tableWithMetadataButNoNodeInterfaceStaysATableType() {
        // The gate's other half, and what keeps a nesting projection over a node-bearing table from
        // becoming a second node. Publishing the Relay contract is the author's opt-in.
        var schema = schema("""
            type SharedNode implements Node @table(name: "shared_node") { id: ID! @nodeId }
            type SharedNodeProjection @table(name: "shared_node") { label: String }
            type Query { shared: SharedNode projection: SharedNodeProjection }
            """);

        assertThat(schema.type("SharedNode")).isInstanceOf(GraphitronType.NodeType.class);
        assertThat(schema.type("SharedNodeProjection")).isInstanceOf(GraphitronType.TableType.class);
    }

    // ===== The typeId-collision hazard =====

    @Test
    void inferredTypeIdCollisionIsDiagnosedAndAttributedToTheCatalog() {
        // Two tables sharing __NODE_TYPE_ID. Neither type names a typeId, so the uniqueness
        // reduction is the only thing standing between this and a nondeterministic Query.node.
        var schema = schema("""
            type CollideA implements Node @table(name: "collide_a") { id: ID! name: String }
            type CollideB implements Node @table(name: "collide_b") { id: ID! name: String }
            type Query { a: CollideA b: CollideB }
            """);

        assertThat(schema.type("CollideA")).isInstanceOf(GraphitronType.NodeType.class);
        assertThat(schema.type("CollideB")).isInstanceOf(GraphitronType.NodeType.class);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("typeId '195'")
                // The message must not claim the value was "declared": nothing declared it. It
                // names the constant and the table an author would have to change.
                && m.contains("CollideA via __NODE_TYPE_ID on table 'collide_a'")
                && m.contains("CollideB via __NODE_TYPE_ID on table 'collide_b'")
                && m.contains("nondeterministic"));
    }

    @Test
    void anInferredCollisionIsCaughtEvenWhenNoFieldReturnsEitherType() {
        // The reachability hole this item had to close. validateNodeTypeIdUniqueness iterates the
        // *pruned* registry, so a node that failed to self-seed and is returned by no field would
        // escape the check entirely. Nothing here returns CollideA or CollideB.
        var schema = schema("""
            type CollideA implements Node @table(name: "collide_a") { id: ID! }
            type CollideB implements Node @table(name: "collide_b") { id: ID! }
            type Qux @table(name: "qux") { name: String }
            type Query { qux: Qux }
            """);

        assertThat(schema.type("CollideA"))
            .as("an inferred node self-seeds reachability, so it survives the registry prune")
            .isInstanceOf(GraphitronType.NodeType.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("typeId '195'") && m.contains("nondeterministic"));
    }

    @Test
    void pinningOneSideOfAnInferredCollisionResolvesIt() {
        // The escape hatch the collision message names, exercised against an inferred sibling.
        var schema = schema("""
            type CollideA implements Node @table(name: "collide_a") @node(typeId: "A") { id: ID! }
            type CollideB implements Node @table(name: "collide_b") { id: ID! }
            type Query { a: CollideA b: CollideB }
            """);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("nondeterministic"));
    }

    @Test
    void inferringASecondNodeOverOneTableTurnsAResolvedDecodeSiteIntoARejection() {
        // The second collision axis, which typeId uniqueness says nothing about: the implicit
        // input reading forks on how many nodes back the table, and widening the node population
        // moves tables across that boundary. Here the only edit between the two schemas is
        // `implements Node` on the sibling, and it turns a resolved input site into a build failure
        // whose message is about the input field.
        var oneNode = schema("""
            type FooA implements Node @table(name: "bar") @node(typeId: "FooA") { id: ID! }
            type FooB @table(name: "bar") { name: String }
            input Selector { id: ID! }
            type Query { a: FooA b: FooB bars(in: Selector): [FooA!] }
            """);
        assertThat(oneNode.field("Query", "bars"))
            .as("one node backs the table: the implicit decode helper resolves")
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);

        var twoNodes = schema("""
            type FooA implements Node @table(name: "bar") @node(typeId: "FooA") { id: ID! }
            type FooB implements Node @table(name: "bar") { name: String id: ID! }
            input Selector { id: ID! }
            type Query { a: FooA b: FooB bars(in: Selector): [FooA!] }
            """);
        assertThat(twoNodes.field("Query", "bars")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(TestSchemaHelper.diagnosticMessages(twoNodes))
            .contains("backs multiple node types")
            .contains("@nodeId(typeName: T)");
    }

    @Test
    void inferringTheFirstNodeOverATableGivesAnInputLeafItsDecodeHelper() {
        // The other direction across the same boundary. With no node over the table there is no
        // implicit node reading to have: a directive-less `id` is an ordinary column, and `bar` has
        // none by that name, so the consuming field rejects. Adding `implements Node` to the output
        // type supplies the node the input field was naming all along.
        var noNode = schema("""
            type Foo @table(name: "bar") { name: String }
            input Selector { id: ID! }
            type Query { bars(in: Selector): [Foo!] }
            """);
        assertThat(noNode.field("Query", "bars"))
            .as("no node backs the table, so `id` is a plain column and `bar` has none")
            .isInstanceOf(GraphitronField.UnclassifiedField.class);

        var inferred = schema("""
            type Foo implements Node @table(name: "bar") { name: String id: ID! }
            input Selector { id: ID! }
            type Query { bars(in: Selector): [Foo!] }
            """);
        assertThat(decodeMethodName(inferred)).isEqualTo("decodeFoo");
    }

    /** The decode helper the {@code bars} input leaf resolved to, across the decoding leaf arms. */
    private static String decodeMethodName(GraphitronSchema schema) {
        var field = (QueryField.QueryTableField) schema.field("Query", "bars");
        var filter = (GeneratedConditionFilter) field.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow();
        var leaf = ((CallSiteExtraction.NestedInputField) filter.callParams().get(0).extraction()).leaf();
        return switch (leaf) {
            case CallSiteExtraction.NodeIdDecodeKeys d -> d.decodeMethod().methodName();
            default -> throw new AssertionError("not a decoding leaf: " + leaf);
        };
    }

    // ===== Every consumer of the predicate agrees with the classifier =====

    @Test
    void anInferredNodeSeedsReachabilityLikeADeclaredOne() {
        var sdl = """
            type Foo implements Node @table(name: "bar") { id: ID! name: String }
            type Qux @table(name: "qux") { name: String }
            type Query { qux: Qux }
            """;

        assertThat(SchemaReachability.reachableTypeNames(bundle(sdl).assembled(), fixtureNodes()))
            .as("returned by no field, reached only as a seed")
            .contains("Foo");
        assertThat(bundle(sdl).model().types()).containsKey("Foo");
    }

    @Test
    void anInferredNodeFoldsToManyInTheArrivalIndex() {
        // Node lookups arrive batched, so a node is Many regardless of how many field edges reach
        // it. Foo has exactly one non-list reaching edge, which would fold to One without the seed.
        var assembled = bundle("""
            type Foo implements Node @table(name: "bar") { id: ID! name: String }
            type Query { foo: Foo }
            """).assembled();

        assertThat(ArrivalIndex.compute(assembled, fixtureNodes()).of("Foo")).isEqualTo(Arrival.MANY);
    }

    @Test
    void anInferredNodeBecomesAFederationEntity() {
        // KeyNodeSynthesiser runs on the raw registry before anything is classified, so it cannot
        // read the classifier; it asks the same predicate instead. Without that, an inferred node
        // would silently drop out of _Entity while an otherwise identical declared one stayed in.
        var registry = TestSchemaHelper.parseRegistryWithPrelude("""
            type Foo implements Node @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """);

        KeyNodeSynthesiser.apply(registry, fixtureNodes());

        var foo = (graphql.language.ObjectTypeDefinition) registry.types().get("Foo");
        assertThat(foo.getDirectives())
            .extracting(graphql.language.Directive::getName)
            .contains("key");
    }

    @Test
    void theLspSeesAnInferredNodeAsANodeWithNoAuthorSuppliedValues() {
        // Presence in the node view is the predicate the @nodeId(typeName:) arms read, so it has to
        // follow nodehood. The values stay author-supplied by design, so both axes read null here.
        var bundle = bundle("""
            type Foo implements Node @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """);
        var data = CatalogBuilder.build(
            new JooqCatalog(FIXTURE_JOOQ_PACKAGE), bundle.assembled(), FIXTURE_CTX);

        assertThat(data.nodeMetadata()).containsKey("Foo");
        assertThat(data.nodeMetadata().get("Foo").typeId()).isNull();
        assertThat(data.nodeMetadata().get("Foo").keyColumns()).isNull();
    }

    @Test
    void theLspStillOmitsATableTypeOverANodeBearingTable() {
        var bundle = bundle("""
            type SharedNode implements Node @table(name: "shared_node") { id: ID! @nodeId }
            type SharedNodeProjection @table(name: "shared_node") { label: String }
            type Query { shared: SharedNode projection: SharedNodeProjection }
            """);
        var data = CatalogBuilder.build(
            new JooqCatalog(FIXTURE_JOOQ_PACKAGE), bundle.assembled(), FIXTURE_CTX);

        assertThat(data.nodeMetadata()).containsKey("SharedNode");
        assertThat(data.nodeMetadata()).doesNotContainKey("SharedNodeProjection");
    }

    // ===== The shadowed `id` column =====
    //
    // Five rows, all over metadata-carrying tables. Rows 1 and 4 are the rejection: a `Node.id`
    // over a table that also has an `id` column names two different wire values, and the SDL does
    // not choose between them, so the build refuses instead of picking. Rows 2 and 3 are the two
    // ways to choose, and both already worked before this rule existed.

    @Test
    void row1_bazBareIdIsAmbiguousAndRejects() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            type Query { baz: Baz }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Baz", "id")).reason())
            .contains("field 'Baz.id'")
            .contains("table 'baz' has a column named 'id' and also publishes node metadata")
            .contains("'id' is ambiguous")
            // Both remedies named, so the rejection is actionable without the manual. An author
            // who meets this cannot proceed until they resolve it, which is the whole point.
            .contains("`@nodeId`")
            .contains("`@field(name: \"id\")`");
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .as("a rejected field must fail the build, not ride through it")
            .isNotEmpty();
    }

    @Test
    void row2_nodeIdPublishesTheGlobalId() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Query { baz: Baz }
            """);

        assertThat(((ChildField.ColumnBackedField) schema.field("Baz", "id")).compaction())
            .isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .as("the author stated the choice; nothing left to reject")
            .isEmpty();
    }

    @Test
    void row3_fieldDirectiveExposesTheRawColumn() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @field(name: "id") }
            type Query { baz: Baz }
            """);

        var id = (ChildField.ColumnBackedField) schema.field("Baz", "id");
        assertThat(id.compaction()).isInstanceOf(CallSiteCompaction.Direct.class);
        assertThat(id.columns()).extracting(ColumnRef::sqlName).containsExactly("id");
        assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
    }

    @Test
    void row4_sharedNodeRejectsWithoutDisturbingTheDecodeHelperName() {
        // The non-degenerate shadowing row: typeId "10154" differs from the type name, so this is
        // the case where the raw column value and the encoded global ID are visibly different
        // things, and the one where the typeId-suffixed decode fallback could have crept in.
        var bare = schema("""
            type SharedNode implements Node @table(name: "shared_node") @node(typeId: "10154") { id: ID! }
            input SharedSelector { id: ID! @nodeId(typeName: "SharedNode") }
            type Query { shared(in: SharedSelector): SharedNode }
            """);
        assertThat(((GraphitronField.UnclassifiedField) bare.field("SharedNode", "id")).reason())
            .contains("'id' is ambiguous");

        // Resolving the ambiguity leaves helper naming exactly where it was: still keyed on the
        // type name rather than the typeId, which is the axis this fixture exists to hold open.
        var pinned = schema("""
            type SharedNode implements Node @table(name: "shared_node") @node(typeId: "10154") { id: ID! @nodeId }
            input SharedSelector { id: ID! @nodeId(typeName: "SharedNode") }
            type Query { shared(in: SharedSelector): SharedNode }
            """);
        var id = (ChildField.ColumnBackedField) pinned.field("SharedNode", "id");
        assertThat(((CallSiteCompaction.NodeIdEncodeKeys) id.compaction()).encodeMethod().methodName())
            .isEqualTo("encodeSharedNode");
        assertThat(((GraphitronType.NodeType) pinned.type("SharedNode")).decodeMethod().methodName())
            .as("the decode helper is still keyed on the type name, not the typeId")
            .isEqualTo("decodeSharedNode");
    }

    @Test
    void row5_aNodeOverATableWithNoIdColumnNeedsNoDisambiguation() {
        // Nothing is shadowed, so `Node.id` has only one reading and the author is not asked to
        // restate it. This is the row that keeps the rejection scoped to genuine ambiguity.
        var schema = schema("""
            type Foo implements Node @table(name: "bar") @node { id: ID! }
            type Query { foo: Foo }
            """);

        assertThat(((ChildField.ColumnBackedField) schema.field("Foo", "id")).compaction())
            .isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
        assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
    }

    // ===== The shadowed `id` column, at the other two coordinates =====
    //
    // The same triple as the output rows above, at the input-field and argument coordinates. Sited
    // together deliberately: asserting all three coordinates in one place is what pins the
    // uniformity, and a test covering only one would not catch a later coordinate-specific
    // divergence, which is the failure mode this rule exists to prevent.

    @Test
    void inputField_bazBareIdIsAmbiguousAndRejects() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            input BazSelector { id: ID! }
            type Query { baz(in: BazSelector): Baz }
            """);

        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .contains("input field 'BazSelector.id'")
            .contains("'id' is ambiguous")
            .contains("`@nodeId`")
            .contains("`@field(name: \"id\")`");
    }

    @Test
    void inputField_fieldDirectiveSelectsTheColumnAndNodeIdSelectsTheNode() {
        var column = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            input BazSelector { id: ID! @field(name: "id") }
            type Query { baz(in: BazSelector): Baz }
            """);
        assertThat(new GraphitronSchemaValidator().validate(column))
            .as("the author stated the column reading; nothing left to reject")
            .isEmpty();

        var node = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            input BazSelector { id: ID! @nodeId }
            type Query { baz(in: BazSelector): Baz }
            """);
        assertThat(new GraphitronSchemaValidator().validate(node))
            .as("the author stated the node reading; nothing left to reject")
            .isEmpty();
    }

    @Test
    void argument_bazBareIdIsAmbiguousAndRejects() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Query { baz(id: ID): Baz }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Query", "baz")).reason())
            // Named once, by the projection's own prefix rather than by the rejection body.
            .contains("argument 'id':")
            .contains("'id' is ambiguous")
            .contains("`@nodeId`")
            .contains("`@field(name: \"id\")`");
    }

    @Test
    void argument_fieldDirectiveSelectsTheColumnAndNodeIdSelectsTheNode() {
        var column = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Query { baz(id: ID @field(name: "id")): Baz }
            """);
        assertThat(new GraphitronSchemaValidator().validate(column)).isEmpty();

        var node = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Query { baz(id: ID @nodeId): Baz }
            """);
        assertThat(new GraphitronSchemaValidator().validate(node)).isEmpty();
    }

    @Test
    void argument_theShadowingAnswerDoesNotDependOnTheReturnTypeWrapper() {
        // The wrapper is not a coordinate. An `@asConnection` field's arguments bind against the
        // same element table as the plain-list spelling, so wrapping the return type must not
        // change whether `id` is the node id or the column. Pinned because the target used to be
        // read off `fieldDef.getType()`, which sees `BazConnection` where the rest of the
        // classifier has already unwrapped to `Baz`, and the arm silently missed.
        var connection = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type BazConnection { edges: [BazEdge!]! pageInfo: PageInfo! }
            type BazEdge { node: Baz! cursor: String! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { bazes(id: ID, first: Int, after: String): BazConnection @asConnection }
            """);

        assertThat(((GraphitronField.UnclassifiedField) connection.field("Query", "bazes")).reason())
            .as("the connection spelling must reject exactly as the plain-list one does")
            .contains("'id' is ambiguous")
            .contains("`@nodeId`")
            .contains("`@field(name: \"id\")`");
    }

    // ===== The Relay root fields =====

    @Test
    void theRelayRootFieldsAreUntouchedByTheImplicitArgumentReading() {
        // The most `id`-named arguments in any schema, and a regression here would be both wide and
        // quiet. They return the Node *interface* rather than a node type, so the implicit reading
        // never applies: they stay NodeResolve coordinates with no backing table.
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Query { node(id: ID!): Node nodes(ids: [ID!]!): [Node]! baz: Baz }
            """);

        assertThat(schema.field("Query", "node"))
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(schema.field("Query", "nodes"))
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
    }

    // ===== `typeName:` on `Node.id` =====
    //
    // The enclosing type already answers "which node" at this coordinate, so the argument is
    // either a restatement or a contradiction. Both are rejected rather than the second alone:
    // checking for agreement would legitimise the spelling by rejecting only half of it.

    @Test
    void typeNameOnNodeIdIsRejectedEvenWhenItAgrees() {
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId(typeName: "Baz") }
            type Query { baz: Baz }
            """);

        var id = (GraphitronField.UnclassifiedField) schema.field("Baz", "id");
        assertThat(id.reason())
            .contains("field 'Baz.id'")
            .contains("`typeName:` is not allowed")
            .contains("node type 'Baz'")
            // The remedy is a deletion, so the message has to say so; an author who reads only
            // "not allowed" would otherwise go looking for a replacement spelling.
            .contains("Remove the argument");
        assertThat(new GraphitronSchemaValidator().validate(schema)).isNotEmpty();
    }

    @Test
    void typeNameOnNodeIdIsRejectedWhenItContradicts() {
        // The half that is unambiguously a bug: the field is Baz's identity by construction and
        // the author has named a different node. Same verdict, same message, no special case.
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId(typeName: "Foo") }
            type Foo implements Node @table(name: "bar") @node { id: ID! }
            type Query { baz: Baz foo: Foo }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Baz", "id")).reason())
            .contains("`typeName:` is not allowed");
    }

    @Test
    void theRejectionCarriesASourceLocationTheEditorCanPointAt() {
        // The rejection is minted classifier-side and drained through UnclassifiedField, which is
        // what carries the location into the validator's report. Pinned because the location is
        // the whole reason the diagnostic is actionable in an editor rather than just in a log.
        var schema = schema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId(typeName: "Baz") }
            type Query { baz: Baz }
            """);

        assertThat(schema.field("Baz", "id").location()).isNotNull();
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .allSatisfy(e -> assertThat(e.location()).isNotNull());
    }

    @Test
    void typeNameStaysLegalOnEveryCoordinateThatIsNotNodeId() {
        // The rejection is scoped to the one coordinate whose target is already settled. An input
        // field and a non-`id` output field both still need the argument, and a reference field
        // named `id` on a *non*-node type is not `Node.id` at all.
        // Over `bar`, which has no `id` column, so the output `id` is a clean rule-1 carrier and
        // the only rule under test here is where `typeName:` stays legal.
        var schema = schema("""
            type Foo implements Node @table(name: "bar") @node { id: ID! }
            input FooSelector { id: ID! @nodeId(typeName: "Foo") }
            type Query { foo(in: FooSelector): Foo }
            """);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .as("an input field's target is not its own enclosing type, so it still needs naming")
            .isEmpty();
    }

    @Test
    void bareNodeIdOnANonNodeTypeNamesTheArgumentThatWouldFixIt() {
        // Rule 2's rejection already existed; what it lacked was the remedy. Bare `@nodeId`
        // inherits its node from the enclosing type, so the fix is either to name one or to make
        // the enclosing type a node.
        var schema = schema("""
            type Zed @table(name: "baz") { id: ID! @nodeId }
            type Query { zed: Zed }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Zed", "id")).reason())
            .contains("@nodeId requires the containing type to be a node type")
            .contains("Add `typeName:`");
    }

    @Test
    void theShadowingRuleDoesNotReachOtherIdFieldsOnANodeType() {
        // The implementation trap this rule had to avoid. The retired synthesis arm fired for
        // *any* bare `ID` field on a node type, so hoisting that predicate above column resolution
        // would have rerouted every such field from its column to a nodeId encode. Only `Node.id`
        // is hoisted, so an `ID` field that resolves to a column still gets the column.
        var schema = schema("""
            type Doc implements Node @table(name: "plain_id") @node { id: ID! @nodeId name: ID! }
            type Query { doc: Doc }
            """);

        var other = (ChildField.ColumnBackedField) schema.field("Doc", "name");
        assertThat(other.compaction())
            .as("a non-`id` ID field still maps to its own column")
            .isInstanceOf(CallSiteCompaction.Direct.class);
        assertThat(other.columns()).extracting(ColumnRef::sqlName).containsExactly("name");
    }

    @Test
    void aNonIdBareIdFieldWithNoColumnIsAnUnknownColumnRatherThanASynthesisedNodeId() {
        // The other half of the narrowing. A non-`id` scalar `ID` on a node type used to be
        // synthesised into a nodeId carrier here; it is an ordinary scalar now, so a missing column
        // is simply a missing column. The deletion could not flip a working field, because the arm
        // it removed fired only where the column was already absent.
        var schema = schema("""
            type Foo implements Node @table(name: "bar") { id: ID! externalId: ID! }
            type Query { foo: Foo }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Foo", "externalId")).reason())
            .contains("column 'externalId' could not be resolved");
    }

    @Test
    void theRuleIsAboutNodehoodNotMetadataSoItCoversTheDeclaredOnlyPathToo() {
        // `plain_id` publishes no metadata, so @node resolves its key columns from the primary key,
        // which here is the shadowed `id` column itself. The verdict is the same and the reason
        // clause differs, because saying "the table publishes node metadata" would be false.
        var schema = schema("""
            type Doc implements Node @table(name: "plain_id") @node { id: ID! }
            type Query { doc: Doc }
            """);

        assertThat(((GraphitronField.UnclassifiedField) schema.field("Doc", "id")).reason())
            .contains("'Doc' is a node type over table 'plain_id', which also has a column named 'id'")
            .contains("'id' is ambiguous")
            .doesNotContain("publishes node metadata");
    }

    @Test
    void theShadowedColumnNeedNotBeAKeyColumn() {
        // `keyed_elsewhere` keys its node id on `key_x` and separately has a column named `id`, so
        // the two readings are different columns rather than two encodings of one. Same rejection,
        // and both remedies resolve it to visibly different values.
        var ambiguous = schema("""
            type Ke implements Node @table(name: "keyed_elsewhere") { id: ID! name: String }
            type Query { ke: Ke }
            """);
        assertThat(((GraphitronField.UnclassifiedField) ambiguous.field("Ke", "id")).reason())
            .contains("'id' is ambiguous");

        var encoded = schema("""
            type Ke implements Node @table(name: "keyed_elsewhere") { id: ID! @nodeId name: String }
            type Query { ke: Ke }
            """);
        var enc = (ChildField.ColumnBackedField) encoded.field("Ke", "id");
        assertThat(enc.compaction()).isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
        assertThat(enc.columns()).extracting(ColumnRef::sqlName).containsExactly("key_x");

        var pinned = schema("""
            type Ke implements Node @table(name: "keyed_elsewhere") { id: ID! @field(name: "id") name: String }
            type Query { ke: Ke }
            """);
        var raw = (ChildField.ColumnBackedField) pinned.field("Ke", "id");
        assertThat(raw.compaction()).isInstanceOf(CallSiteCompaction.Direct.class);
        assertThat(raw.columns()).extracting(ColumnRef::sqlName).containsExactly("id");
    }

    // ===== The non-goal: no metadata, no inference =====

    @Test
    void noMetadataMeansNoInferenceAndBothOtherSpellingsStillReject() {
        // Frozen deliberately. Metadata is a positive assertion by the jOOQ generator that the table
        // has a published node identity; a primary key is not. Asserted through a helper that runs
        // the validator, because the pipeline bundle helper does not, and a rejected field rides
        // through a green build silently otherwise.

        // Spelling (1): bare `id: ID!`, no column of that name.
        var bare = schema("""
            type Qux implements Node @table(name: "qux") { id: ID! }
            type Query { qux: Qux }
            """);
        assertThat(bare.type("Qux")).isInstanceOf(GraphitronType.TableType.class);
        assertThat(((GraphitronField.UnclassifiedField) bare.field("Qux", "id")).reason())
            .contains("column 'id' could not be resolved");

        // Spelling (3): `@nodeId` written on a non-node type. A different message, and the two are
        // kept apart on purpose — this one is about the containing type, that one about a column.
        var pinned = schema("""
            type Qux implements Node @table(name: "qux") { id: ID! @nodeId }
            type Query { qux: Qux }
            """);
        assertThat(pinned.type("Qux")).isInstanceOf(GraphitronType.TableType.class);
        assertThat(((GraphitronField.UnclassifiedField) pinned.field("Qux", "id")).reason())
            .contains("@nodeId requires the containing type to be a node type");
    }

    @Test
    void theInRepoNoNodeDeclarationsAreUnaffectedBecauseTheirTablesPublishNothing() {
        // Every `implements Node @table` declaration without `@node` in this repository is over a
        // sakila table that publishes no metadata, so inference fires for none of them. They are
        // green because of that, not because the directive is always present; adding metadata to
        // one of those tables would flip them. Asserted on the field, and then on the validator,
        // because the pipeline bundle helper the host tests use runs neither.
        var pinned = TestSchemaHelper.buildSchema("""
            type Customer implements Node @table(name: "customer") { id: ID! @nodeId }
            type Payment implements Node @table(name: "payment") { id: ID! @nodeId }
            type Query { customer: Customer payment: Payment }
            """);
        assertThat(pinned.type("Customer")).isInstanceOf(GraphitronType.TableType.class);
        assertThat(((GraphitronField.UnclassifiedField) pinned.field("Customer", "id")).reason())
            .contains("@nodeId requires the containing type to be a node type");
        assertThat(new GraphitronSchemaValidator().validate(pinned))
            .as("the shape has always been rejected; this item does not change that")
            .isNotEmpty();

        // The bare-`id` sibling shape, whose rejection names the column instead. The two messages
        // are kept apart deliberately: one is about the containing type, the other about a column.
        var bare = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") { id: ID! }
            type Query { film: Film }
            """);
        assertThat(bare.type("Film")).isInstanceOf(GraphitronType.TableType.class);
        assertThat(((GraphitronField.UnclassifiedField) bare.field("Film", "id")).reason())
            .contains("column 'id' could not be resolved");
    }

    @Test
    void theNodeIdRejectionMessageNowDescribesAPathThatExists() {
        // The message already read "via @node or KjerneJooqGenerator metadata" before inference
        // existed. Pin that the metadata half is now true as written: the identical field is
        // accepted over a metadata-carrying table with no @node anywhere.
        var accepted = schema("""
            type Foo implements Node @table(name: "bar") { id: ID! @nodeId }
            type Query { foo: Foo }
            """);

        assertThat(accepted.type("Foo")).isInstanceOf(GraphitronType.NodeType.class);
        assertThat(accepted.field("Foo", "id")).isInstanceOf(ChildField.ColumnBackedField.class);
    }

    @Test
    void anObjectTypeWithNoTableIsNeverANode() {
        var nodes = fixtureNodes();
        var assembled = bundle("""
            type Foo implements Node @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """).assembled();

        assertThat(nodes.isNodeType((GraphQLObjectType) assembled.getType("Foo"))).isTrue();
        assertThat(nodes.isNodeType((GraphQLObjectType) assembled.getType("Query"))).isFalse();
    }
}
