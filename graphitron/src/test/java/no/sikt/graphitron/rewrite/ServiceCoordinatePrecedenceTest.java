package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@code @service} rejection surfaces when a field carries more than one defect.
 *
 * <p>The SDL is the contract and the Java signature is fitted to it, so a defect in the field's
 * shape is reported before a defect in the coordinate's ability to host the signature, which is
 * reported before a defect in the signature's fit, which is reported before a defect in name
 * binding. Every case below pairs two real defects and asserts which one the author is told
 * about; a test that only asserted "rejected" would pass under either order.
 *
 * <p>The pins live at this tier because the precedence is decided in
 * {@link ServiceDirectiveResolver}'s classify phase, between the catalog's decode and bind
 * phases. {@code ServiceCatalogTest} drives decode and bind directly and structurally cannot
 * observe the ordering.
 */
@PipelineTier
class ServiceCoordinatePrecedenceTest {

    private static final String SVC = "no.sikt.graphitron.rewrite.generators.TestFilmService";
    /** The DTO-shaped batch-parameter fixtures live on the other service stub. */
    private static final String STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    /** Gives the record-backed parent type a backing class, so it resolves to a {@code ResultType}. */
    private static final String DUMMY = "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    /** SDL preamble: a record-backed (non-{@code @table}) parent reachable from the root. */
    private static String recordParentSchema(String detailsFields) {
        return """
            type Film @table(name: "film") { details: FilmDetails }
            type Language @table(name: "language") { name: String @field(name: "name") }
            type FilmDetails {
            %s
            }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "%s", method: "makeDummyRecord"})
            }
            """.formatted(detailsFields, DUMMY);
    }

    private static String reasonOf(GraphitronSchema schema, String type, String field) {
        var f = schema.field(type, field);
        assertThat(f)
            .as("field '%s.%s' must be rejected", type, field)
            .isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) f).reason();
    }

    // ===== Record-backed parent: the coordinate outranks everything below it =====

    /**
     * The headline case. A batch-shaped signature whose declared key the parent cannot produce is
     * answered about the coordinate; the surviving diagnostic used to be an argument-name mismatch
     * telling the author their parameter matched no GraphQL argument, on a field that declares none.
     * {@code DummyRecord} exposes no accessor returning a {@code film} record, so this parent has no
     * route to the key the signature names.
     */
    @Test
    void recordParent_batchShapedSignature_isAnsweredAboutTheCoordinate() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                rating: String @service(service: {className: "%s", method: "getRatingByFilmRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "rating"))
            .as("the coordinate is the problem, not the author's parameter name")
            .contains("FilmRecord", "cannot produce one")
            .doesNotContain("available GraphQL arguments");
    }

    /** Coordinate outranks binding: an unbindable parameter does not change the verdict. */
    @Test
    void recordParent_batchShapedSignatureWithUnbindableParameter_stillAnsweredAboutTheCoordinate() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                rating: String @service(service: {className: "%s", method: "getRatingByFilmRecordWithExtra"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "rating"))
            .contains("cannot produce one")
            .doesNotContain("available GraphQL arguments");
    }

    /**
     * Coordinate outranks signature fit at a strict-regime coordinate: the method's return type
     * is also wrong for the field's single {@code @table} return, and the coordinate still wins.
     */
    @Test
    void recordParent_batchShapedSignatureWithWrongReturnType_isAnsweredAboutTheCoordinate() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                language: Language @service(service: {className: "%s", method: "getRatingByFilmRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "language"))
            .contains("cannot produce one")
            .doesNotContain("must return");
    }

    /**
     * A record-parent {@code @service} with no batch-shaped parameter is rejected for the parameter it
     * does not declare rather than for the coordinate, whatever the field returns. Every child
     * {@code @service} batches, so the verdict no longer forks on the return type here; the fixtures
     * for both return shapes live in
     * {@code ServiceRecordParentBatchKeyTest.sourcesLessChildOnClassBackedParent_isRejectedNamingTheMissingParameter}
     * and its table-bound sibling. This case keeps the precedence pin: the missing parameter outranks
     * the argument-name mismatch its unbound {@code filter} would otherwise produce.
     */
    @Test
    void recordParent_noBatchParameter_reportsTheMissingParameterNotTheArgumentName() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                title: String
                nested(filter: String): FilmDetails @service(service: {className: "%s", method: "getConstantRank"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "nested"))
            .contains("declares no Sources parameter")
            .doesNotContain("available GraphQL arguments");
    }

    // ===== Regime pins: what record-backed parents no longer inherit from the root regime =====

    /**
     * Record-backed parents used to share the root return-type regime, so a {@code @service} child
     * with no batch parameter got the strict return-type comparison. They are batched children now,
     * and the residue that flip would have stranded (a coordinate with no return-type validation at
     * all) is closed by the missing-parameter rejection, which is what this pins: the "after" side of
     * the flip is a rejection, not a laxer acceptance.
     */
    @Test
    void recordParent_noBatchParameter_noLongerReachesTheStrictReturnTypeComparison() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                language(filter: String): Language @service(service: {className: "%s", method: "getConstantRank"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "language"))
            .as("the coordinate is a batched child now, so the missing key is the verdict")
            .contains("declares no Sources parameter")
            .doesNotContain("must return");
    }

    // The Connection half of the root regime is exercised by the root arm below, which owns the
    // STRICT_ROOT switch case outright now. It has no record-parent counterpart, and none is owed:
    // a record-backed parent is a batched child, so its Connection returns are governed by the same
    // rules as a @table parent's rather than by the root's rejection. No fixture could have pinned
    // the flip either way, because a Connection-shaped type returned from a record-backed parent's
    // field classifies both as a ConnectionType and as a record carrier, so the type itself rejects
    // before any field on it reaches the resolver.

    // ===== Field shape outranks the coordinate; the coordinate outranks signature fit =====

    /**
     * A Connection return is a defect in the field's shape, so it is reported even when the
     * signature is also unbindable. Before the phase split the binding failure fired first and
     * the author never saw the Connection rejection.
     */
    @Test
    void root_connectionReturnWithUnbindableParameter_reportsTheConnection() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmConnection { edges: [FilmEdge], pageInfo: PageInfo! }
            type FilmEdge { node: Film, cursor: String! }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                films: FilmConnection @service(service: {className: "%s", method: "getConstantRank"})
            }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Query", "films"))
            .contains("does not support Connection return types")
            .doesNotContain("available GraphQL arguments");
    }

    /**
     * Connection now beats the batch-at-root rejection. A deliberate flip: both are true of the
     * field, and the return type is the one the author has to change first.
     */
    @Test
    void root_connectionReturnWithBatchParameter_reportsTheConnection() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmConnection { edges: [FilmEdge], pageInfo: PageInfo! }
            type FilmEdge { node: Film, cursor: String! }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                films: FilmConnection @service(service: {className: "%s", method: "getFilmRootBatchWrongReturn"})
            }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Query", "films"))
            .contains("does not support Connection return types")
            .doesNotContain("no parent context to batch against");
    }

    /**
     * Batch-at-root no longer depends on the batch parameter being declared before the parameter
     * that fails to bind. The unbindable parameter is declared first here.
     */
    @Test
    void root_batchParameterAfterUnbindableParameter_reportsBatchAtRoot() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @service(service: {className: "%s", method: "getFilmsRootBatch"})
            }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Query", "films"))
            .contains("no parent context to batch against")
            .doesNotContain("available GraphQL arguments");
    }

    /**
     * Batch-at-root now beats the strict return-type mismatch. The other deliberate flip: the
     * root cannot batch at all, so telling the author to change the return type would send them
     * down a road that does not end anywhere.
     */
    @Test
    void root_batchParameterWithWrongReturnType_reportsBatchAtRoot() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                film: Film @service(service: {className: "%s", method: "getFilmRootBatchWrongReturn"})
            }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Query", "film"))
            .contains("no parent context to batch against")
            .doesNotContain("must return");
    }

    /**
     * The List-cardinality return-pair check used to run after binding while its Single-cardinality
     * twin ran before it, so this pairing lost a race its sibling won. The two arms are one fact
     * and now sit together above binding.
     */
    @Test
    void root_listReturnPairMismatchWithUnbindableParameter_reportsTheReturnPair() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @service(service: {className: "%s", method: "getLanguagesWithUnmatched"})
            }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Query", "films"))
            .contains("must return", "FilmRecord")
            .doesNotContain("available GraphQL arguments");
    }

    // ===== Table-backed parents =====

    /**
     * The PK-less-parent rejection no longer depends on declaration order either: the batch
     * parameter is declared second here, behind a parameter that binds to nothing. The
     * swapped-order sibling is pinned by {@code PkLessParentServiceSourcesRejectionTest}.
     */
    @Test
    void pkLessTableParent_batchParameterAfterUnbindableParameter_reportsThePkLessParent() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmList @table(name: "film_list") {
                title: String @field(name: "title")
                rank: Int @service(service: {className: "%s", method: "getFilmListRankMisordered"})
            }
            type Query { filmList: FilmList }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "FilmList", "rank"))
            .contains("film_list", "no primary key")
            .doesNotContain("available GraphQL arguments");
    }

    /**
     * The Sources element-class check used to sit after the whole binding loop, where any other
     * parameter's failure masked it. It reads only the decoded wrap and the parent's record
     * class, so it belongs with the other coordinate answers.
     */
    @Test
    void tableParent_wrongSourcesElementWithUnbindableParameter_reportsTheElementMismatch() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rating: String @service(service: {className: "%s", method: "getRatingByFilmRecordWithExtra"})
            }
            type Query { language: Language }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Language", "rating"))
            .contains("Sources element type", "FilmRecord", "LanguageRecord")
            .doesNotContain("available GraphQL arguments");
    }

    /**
     * Name-claim precedence, unchanged in its mechanism and visible in its consequence: a batch-shaped
     * parameter whose name matches a GraphQL argument is claimed by the argument, so it never reaches
     * the SOURCES candidate role. The field must not silently mint a batch key over an argument the
     * author meant to pass, and it does not: the coordinate is left with no batch parameter at all and
     * is rejected for that, which is the honest reading of a signature whose only key-shaped slot the
     * author spent on an argument.
     */
    @Test
    void tableParent_batchShapedParameterNamedAfterAnArgument_isClaimedByTheArgument() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank(languageKeys: [ID!]): Int @service(service: {className: "%s", method: "getRankNamedSources"})
            }
            type Query { language: Language }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Language", "rank"))
            .as("the argument claim wins, so the coordinate is left with no batch key")
            .contains("declares no Sources parameter");
    }

    /**
     * A DTO-shaped batch parameter at a child coordinate: the coordinate batches, and a
     * {@code List<DTO>} cannot be its key. The verdict moved to the classify phase with the
     * missing-{@code Sources} rejection it sits beside, so it can no longer be masked by a parameter
     * declared ahead of it; the root arm keeps its own answer (an argument-name mismatch, since a root
     * {@code List<DTO>} is the canonical input-bean shape) and is pinned by {@code ServiceCatalogTest}.
     */
    @Test
    void tableParent_dtoShapedBatchParameter_reportsTheDtoVerdict() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Language @table(name: "language") {
                name: String @field(name: "name")
                films: [Film!]! @service(service: {className: "%s", method: "getFilmsWithDtoSources"})
            }
            type Query { language: Language }
            """.formatted(STUB));

        assertThat(reasonOf(schema, "Language", "films"))
            .contains("not backed by a jOOQ TableRecord")
            .doesNotContain("available GraphQL arguments");
    }

    /** The {@code Set<DTO>} container takes the same answer as {@code List<DTO>}. */
    @Test
    void tableParent_dtoShapedSetParameter_reportsTheSameDtoVerdict() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Language @table(name: "language") {
                name: String @field(name: "name")
                films: [Film!]! @service(service: {className: "%s", method: "getFilmsWithSetOfDtoSources"})
            }
            type Query { language: Language }
            """.formatted(STUB));

        assertThat(reasonOf(schema, "Language", "films"))
            .contains("not backed by a jOOQ TableRecord")
            .doesNotContain("unrecognized sources type");
    }

    // ===== Child polymorphic returns =====

    /**
     * The child-polymorphic deferral is hoisted above the join-path parse both child classify
     * sites run between {@code resolve} and their switch. The field is unsupported at this
     * coordinate whatever its path says, so the path error is not the author's next move.
     */
    @Test
    void tableParent_polymorphicReturnWithUnresolvablePath_reportsTheDeferral() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Searchable { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "title") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "first_name") }
            type Language @table(name: "language") {
                name: String @field(name: "name")
                found(filter: String): Searchable
                    @service(service: {className: "%s", method: "getConstantRank"})
                    @reference(path: [{key: "no_such_fkey"}])
            }
            type Query { language: Language }
            """.formatted(SVC));

        assertThat(reasonOf(schema, "Language", "found"))
            .contains("polymorphic type", "root @service fields only")
            .doesNotContain("no_such_fkey");
    }

    /** The record-parent twin of the case above. */
    @Test
    void recordParent_polymorphicReturnWithUnresolvablePath_reportsTheDeferral() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Searchable { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "title"), details: FilmDetails }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "first_name") }
            type FilmDetails {
                found(filter: String): Searchable
                    @service(service: {className: "%s", method: "getConstantRank"})
                    @reference(path: [{key: "no_such_fkey"}])
            }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "%s", method: "makeDummyRecord"})
            }
            """.formatted(SVC, DUMMY));

        assertThat(reasonOf(schema, "FilmDetails", "found"))
            .contains("polymorphic type", "root @service fields only")
            .doesNotContain("no_such_fkey");
    }
}
