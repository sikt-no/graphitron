package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorFieldRead;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ValueLocator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@code @nodeId} on an extra field of an {@code @error} type: the coordinate this whole item was
 * reported from.
 *
 * <p>An {@code @error} type stands for no table, so its fields are read off the matched exception
 * rather than projected, and the directive was inert there. The difficulty was not the encode but
 * that the coordinate stated its read twice: as a classified leaf nothing on the emit path
 * consulted, and as the type's own {@code @field(name:)} override list the registration folded
 * over. The override list held only the fields carrying the directive, so an extra field without
 * one got no registration at all and resolved through graphql-java's default on its SDL name, which
 * is why nothing on the type could carry a wire direction.
 *
 * <p>So the assertions come in pairs, and the pairing is the claim: the classified leaf and the
 * per-field read the registration folds over agree, on the accessor base and on the direction, for
 * a field with {@code @field(name:)} and for one without. A test asserting only the leaf would pass
 * with the registration still folding over the override subset, and one asserting only the fold
 * would pass with the leaf saying something else.
 */
@PipelineTier
class NodeIdErrorFieldEncodePipelineTest {

    private static final String SERVICE_DECL =
        "@service(service: {className: \"no.sikt.graphitron.rewrite.TestServiceStub\", method: \"runSak\"})";

    private static final String FILM_NODE = """
        type Film implements Node @table(name: "film") @node { id: ID! }
        """;

    private static final String CAST_NODE = """
        type FilmCast implements Node @table(name: "film_actor") @node { id: ID! }
        """;

    /** The handler source class holds {@code film_id}'s own type, and a String beside it. */
    private static final String HANDLER =
        "{handler: GENERIC, className: \"no.sikt.graphitron.codereferences.dummyreferences.FilmNotFoundException\"}";

    private static String schemaFor(String errorFields) {
        return FILM_NODE + """
            type FilmNotFound @error(handlers: [%s]) {
                path: [String!]!
                message: String!
            %s
            }
            union SakError = FilmNotFound
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(HANDLER, errorFields, SERVICE_DECL);
    }

    @Test
    void anIdFieldNamingANode_encodesWhatTheExceptionHolds() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
                filmRef: ID @nodeId(typeName: "Film") @field(name: "filmId")
            """));

        var read = (ChildField.RecordReadField) schema.field("FilmNotFound", "filmRef");
        assertThat(read.locator())
            .as("graphitron locates nothing on an @error parent; the located name is the accessor base")
            .isEqualTo(new ValueLocator.DefaultRead("filmId"));
        assertThat(read.compaction()).isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);

        assertThat(schema.errorFieldReads("FilmNotFound"))
            .as("the read the registration folds over agrees with the leaf, and covers every field")
            .extracting(Object::getClass, ErrorFieldRead::sdlFieldName)
            .containsExactly(
                tuple(ErrorFieldRead.Builtin.class, "path"),
                tuple(ErrorFieldRead.Builtin.class, "message"),
                tuple(ErrorFieldRead.SourceAccessor.class, "filmRef"));
        var accessor = (ErrorFieldRead.SourceAccessor) schema.errorFieldReads("FilmNotFound").get(2);
        assertThat(accessor.accessorBase()).isEqualTo("filmId");
        assertThat(accessor.wire()).isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
    }

    /**
     * An extra field without {@code @field(name:)}, which the old fold could not see at all. Its
     * read is its own SDL name and its direction is plain, which is what the graphql-java default
     * already did, so this is the case that says the fold widened without changing anything.
     */
    @Test
    void anOrdinaryExtraField_readsItsOwnNameAndStaysDirect() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
                label: String
            """));

        var read = (ChildField.RecordReadField) schema.field("FilmNotFound", "label");
        assertThat(read.locator()).isEqualTo(new ValueLocator.DefaultRead("label"));
        assertThat(read.compaction()).isInstanceOf(CallSiteCompaction.Direct.class);

        assertThat(schema.errorFieldReads("FilmNotFound"))
            .filteredOn(r -> r instanceof ErrorFieldRead.SourceAccessor)
            .singleElement()
            .satisfies(r -> {
                var a = (ErrorFieldRead.SourceAccessor) r;
                assertThat(a.accessorBase()).isEqualTo("label");
                assertThat(a.wire()).isInstanceOf(CallSiteCompaction.Direct.class);
            });
    }

    /**
     * The accessor-coverage check now expects the key column's type at a {@code @nodeId} field, and
     * this is the case that proves it changed: {@code getLabel()} returns a {@code String}, which is
     * exactly what a consumer encoding by hand would have exposed, and the carrier is refused so the
     * hand-written encoder has to go rather than silently disagreeing with the generated one.
     */
    @Test
    void anAccessorYieldingTheEncodedForm_refusesTheCarrier() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
                filmRef: ID @nodeId(typeName: "Film") @field(name: "label")
            """));

        var f = (UnclassifiedField) schema.field("Query", "sak");
        assertThat(f.reason())
            .contains("filmRef")
            .contains("FilmNotFoundException")
            .contains("Integer");
    }

    /** The arity precondition reaches this coordinate too, and names the count. */
    @Test
    void aCompositeKeyAtAnErrorField_isRefusedNamingTheCount() {
        var schema = TestSchemaHelper.buildSchema(CAST_NODE + schemaFor("""
                castRef: ID @nodeId(typeName: "FilmCast") @field(name: "filmId")
            """));

        var f = (UnclassifiedField) schema.field("FilmNotFound", "castRef");
        assertThat(f.reason()).contains("FilmCast").contains("key of 2 columns");
    }

    /** {@code path} and {@code message} are graphitron's own reads and take no direction. */
    @Test
    void theRequiredFields_stayBuiltInReads() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
                label: String
            """));

        assertThat(schema.errorFieldReads("FilmNotFound"))
            .filteredOn(r -> r instanceof ErrorFieldRead.Builtin)
            .extracting(ErrorFieldRead::sdlFieldName)
            .containsExactly("path", "message");
    }
}
