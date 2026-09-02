package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.RunContext;

/**
 * The four rails that refuse a translated FK-target {@code @nodeId} carrier, and the one cause they
 * all report.
 *
 * <p>The read side supports the shape: the decoded key binds the target's key column inside a
 * correlated {@code EXISTS} (pinned by {@code NodeIdPipelineTest}'s translated-FK cases). Writing
 * such a carrier is a different problem. The value the statement would need is an FK-column value on
 * the row's own table, and the client supplied a key of the target row instead, so an INSERT, an
 * UPDATE SET, a DELETE key or a {@code @lookupKey} VALUES join would each need a subquery converting
 * one into the other. That is unimplemented, and each rail says so at its own gate:
 * {@code MutationInputResolver.admitMutationInputFields} (INSERT),
 * {@code UpdateRowsWalker.classifyInto} (UPDATE), {@code DeleteRowsWalker.classifyInto} (DELETE) and
 * {@code FieldBuilder.classifyPlainLookupKeyArg} (the query-side lookup).
 *
 * <p>The rails do not share an error channel: two mint a {@code Rejection.Deferred}, two report
 * through their walker's own {@code UnsupportedInputFieldShape} arm (whose type is a sibling
 * {@code Rejection.AuthorError} that a {@code Deferred} cannot inhabit). What they share is the
 * message text, and that sharing is the property worth pinning: an author who hits this on one verb
 * must not have to relearn it on the next. Every case below asserts the same substring.
 *
 * <p>Fixture: {@code nodeidfixture.child_ref.parent_alt_key} references
 * {@code parent_node(alt_key)}, a non-PK unique column, while {@code ParentNode}'s node key is
 * {@code pk_id}. A decoded {@code ParentNode} id therefore yields a {@code pk_id} value that no
 * {@code child_ref} column holds.
 *
 * <p>Each rail is pinned twice, once on that single-hop carrier and once on a junction chain
 * through sakila's {@code film_category}. The two arrive at the remote binding for different
 * reasons, an FK pointing at the wrong unique key versus a pairing that exists only in a junction
 * row, and a rail that reported the shared cause for one shape but not the other would be a rail
 * gating on the path rather than on the binding.
 */
@PipelineTier
class TranslatedFkTargetRailGatesPipelineTest {

    private static final RunContext NODEID_CTX = new RunContext(
        List.of(),
        Path.of(""), "TranslatedFkTargetRailGatesPipelineTest",
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture"
    );

    /** The shared cause every rail reports, minted by {@code FilterBinding.remoteBindingUnsupported}. */
    private static final String SHARED_CAUSE =
        "needs a key-to-FK-column subquery, which is not implemented";

    private static final String PARENT_AND_CHILD = """
        type ParentNode implements Node @table(name: "parent_node") @node { id: ID! }
        type ChildRef implements Node @table(name: "child_ref") @node(keyColumns: ["child_id"]) {
            id: ID! @nodeId
            childId: String! @field(name: "child_id")
        }
        """;

    @Test
    void insertRail_rejectsWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(PARENT_AND_CHILD + """
            input CreateChildRefInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ParentNode")
            }
            type Query { x: String }
            type Mutation {
                createChildRef(in: CreateChildRefInput!): ID @mutation(typeName: INSERT, table: "child_ref")
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createChildRef");
        assertThat(f.reason()).contains("parentRef").contains(SHARED_CAUSE);
    }

    @Test
    void updateRail_rejectsWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(PARENT_AND_CHILD + """
            input UpdateChildRefInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ParentNode")
            }
            type Query { x: String }
            type Mutation {
                updateChildRef(in: UpdateChildRefInput!): ID @mutation(typeName: UPDATE, table: "child_ref")
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "updateChildRef");
        assertThat(f.reason()).contains("parentRef").contains(SHARED_CAUSE);
    }

    @Test
    void deleteRail_rejectsWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(PARENT_AND_CHILD + """
            input DeleteChildRefInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ParentNode")
            }
            type Query { x: String }
            type Mutation {
                deleteChildRef(in: DeleteChildRefInput!): ID @mutation(typeName: DELETE, table: "child_ref")
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteChildRef");
        assertThat(f.reason()).contains("parentRef").contains(SHARED_CAUSE);
    }

    @Test
    void lookupKeyRail_rejectsWithTheSharedCause() {
        // The query-side rail: an arg-level @lookupKey over a plain input whose field is the
        // translated carrier. The lookup shape is a VALUES join over columns of the return table,
        // and this carrier names none of them.
        var schema = TestSchemaHelper.buildSchema(PARENT_AND_CHILD + """
            input ChildRefLookupInput {
                parentRef: ID! @nodeId(typeName: "ParentNode")
            }
            type Query {
                childRefsByParentKey(key: [ChildRefLookupInput!]! @lookupKey): [ChildRef!]!
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Query", "childRefsByParentKey");
        assertThat(f.reason()).contains("parentRef").contains(SHARED_CAUSE);
    }

    // ===== The junction chain on the same four rails =====
    //
    // A junction chain reaches the write rails now that an unlanded key position binds remotely
    // instead of being refused earlier. What the author sees at each rail is the rail's own
    // remote-binding message, which speaks about the binding and not about their chain, so each
    // rail states it here rather than leaving the diagnostic to be discovered.
    //
    // Sakila rather than nodeidfixture, because film_category is the junction: reaching category
    // from film traverses one FK against its direction and the next along it, so no film column
    // holds a category_id and the carrier binds Remote with a two-hop reach.

    private static final String FILM_AND_CATEGORY = """
        type Category implements Node @table(name: "category") @node { id: ID! }
        type Film implements Node @table(name: "film") @node {
            id: ID! @nodeId
            title: String!
        }
        """;

    /** The junction carrier, spelled once: the same leaf on every rail below. */
    private static final String JUNCTION_LEAF = """
                categoryRef: ID! @nodeId(typeName: "Category") @reference(path: [
                    {key: "film_category_film_id_fkey"},
                    {key: "film_category_category_id_fkey"}
                ])
        """;

    @Test
    void insertRail_rejectsAJunctionCarrierWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(FILM_AND_CATEGORY + """
            input CreateFilmInput {
                title: String!
            """ + JUNCTION_LEAF + """
            }
            type Query { x: String }
            type Mutation {
                createFilm(in: CreateFilmInput!): ID @mutation(typeName: INSERT, table: "film")
            }
            """);

        var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
        assertThat(f.reason()).contains("categoryRef").contains(SHARED_CAUSE);
    }

    @Test
    void updateRail_rejectsAJunctionCarrierWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(FILM_AND_CATEGORY + """
            input UpdateFilmInput {
                title: String!
            """ + JUNCTION_LEAF + """
            }
            type Query { x: String }
            type Mutation {
                updateFilm(in: UpdateFilmInput!): ID @mutation(typeName: UPDATE, table: "film")
            }
            """);

        var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
        assertThat(f.reason()).contains("categoryRef").contains(SHARED_CAUSE);
    }

    @Test
    void deleteRail_rejectsAJunctionCarrierWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(FILM_AND_CATEGORY + """
            input DeleteFilmInput {
                title: String!
            """ + JUNCTION_LEAF + """
            }
            type Query { x: String }
            type Mutation {
                deleteFilm(in: DeleteFilmInput!): ID @mutation(typeName: DELETE, table: "film")
            }
            """);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
        assertThat(f.reason()).contains("categoryRef").contains(SHARED_CAUSE);
    }

    @Test
    void lookupKeyRail_rejectsAJunctionCarrierWithTheSharedCause() {
        var schema = TestSchemaHelper.buildSchema(FILM_AND_CATEGORY + """
            input FilmLookupInput {
            """ + JUNCTION_LEAF + """
            }
            type Query {
                filmsByCategoryKey(key: [FilmLookupInput!]! @lookupKey): [Film!]!
            }
            """);

        var f = (UnclassifiedField) schema.field("Query", "filmsByCategoryKey");
        assertThat(f.reason()).contains("categoryRef").contains(SHARED_CAUSE);
    }

    @Test
    void directFkCarrierOnTheSameRails_isStillAdmitted() {
        // The gates are per-instance, not per-carrier-type: the same reference carrier over a
        // *direct* FK (reordered_fk_child's FK targets reordered_pk_parent's PK, same multiset as
        // the NodeType key) keeps writing on INSERT. Without this, a gate that rejected the carrier
        // type outright would look equally green.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild @table(name: "reordered_fk_child") { childId: String! @field(name: "child_id") }
            input CreateReorderedChildInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                createReorderedChild(in: CreateReorderedChildInput!): ReorderedChild @mutation(typeName: INSERT)
            }
            """, NODEID_CTX);

        assertThat(schema.field("Mutation", "createReorderedChild"))
            .as("a Local-bound FK-target reference carrier is still writable")
            .isNotInstanceOf(UnclassifiedField.class);
    }
}
