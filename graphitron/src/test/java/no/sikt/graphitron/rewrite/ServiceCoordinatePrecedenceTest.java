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
     * The headline case. A batch-shaped signature on a record-backed parent has no batch key by
     * construction, and the surviving diagnostic used to be an argument-name mismatch telling the
     * author their parameter matched no GraphQL argument, on a field that declares none.
     */
    @Test
    void recordParent_batchShapedSignature_isAnsweredAboutTheCoordinate() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                rating: String @service(service: {className: "%s", method: "getRatingByFilmRecord"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "rating"))
            .as("the coordinate is the problem, not the author's parameter name")
            .contains("record-backed parent", "lifted through the parent chain")
            .doesNotContain("available GraphQL arguments");
    }

    /** Coordinate outranks binding: an unbindable parameter does not change the verdict. */
    @Test
    void recordParent_batchShapedSignatureWithUnbindableParameter_stillAnsweredAboutTheCoordinate() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                rating: String @service(service: {className: "%s", method: "getRatingByFilmRecordWithExtra"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "rating"))
            .contains("record-backed parent")
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
            .contains("record-backed parent")
            .doesNotContain("must return");
    }

    /**
     * Over-fire guard for the arm's second trigger. A record-parent {@code @service} with no
     * batch-shaped parameter at all still cannot be honoured when the field's return type is a
     * record or a scalar; gating the rejection on the batch shape alone would silently make this
     * legal, which is the feature the follow-up item owns, not this one.
     */
    @Test
    void recordParent_noBatchParameterWithRecordReturn_isStillRejected() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                title: String
                nested(filter: String): FilmDetails @service(service: {className: "%s", method: "getConstantRank"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "nested"))
            .contains("record-backed parent");
    }

    /**
     * The sibling that must keep classifying: a record-parent {@code @service} with no batch
     * parameter and a {@code @table}-bound return needs no batch key, so the coordinate has no
     * verdict to give. Mirrors {@code PkLessParentServiceSourcesRejectionTest}'s over-fire guard
     * on the other coordinate.
     */
    @Test
    void recordParent_noBatchParameterWithTableBoundReturn_stillClassifies() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                language(filter: String): Language @service(service: {className: "%s", method: "getLanguageByFilter"})
            """.formatted(SVC)));

        assertThat(schema.field("FilmDetails", "language"))
            .as("no batch key is needed, so the coordinate has nothing to reject")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    // ===== Regime pins: what record-backed parents still inherit from the root regime =====

    /**
     * Record-backed parents currently share the root return-type regime. A {@code @service}
     * child with no batch parameter therefore still gets the strict return-type comparison. This
     * is the "before" side of the flip the record-parent key feature will take.
     */
    @Test
    void recordParent_noBatchParameter_keepsTheStrictReturnTypeComparison() {
        var schema = TestSchemaHelper.buildSchema(recordParentSchema("""
                language(filter: String): Language @service(service: {className: "%s", method: "getConstantRank"})
            """.formatted(SVC)));

        assertThat(reasonOf(schema, "FilmDetails", "language"))
            .as("the strict comparison still applies at a record-backed parent")
            .contains("must return", "LanguageRecord");
    }

    // The Connection half of the same regime is exercised by the root arm below, which shares the
    // STRICT_ROOT switch case. It has no record-parent fixture: a Connection-shaped type returned
    // from a record-backed parent's field classifies both as a ConnectionType and as a record
    // carrier, so the type itself rejects before any field on it reaches the resolver.

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
     * Name-claim precedence, unchanged: a batch-shaped parameter whose name matches a GraphQL
     * argument is claimed by the argument, so it never reaches the SOURCES candidate role and the
     * coordinate has no verdict to give. The field must not silently mint a batch key over an
     * argument the author meant to pass.
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

        var field = schema.field("Language", "rank");
        assertThat(field)
            .as("the argument claim wins, so the coordinate never sees a batch candidate")
            .isInstanceOf(ChildField.ServiceRecordField.class);
        assertThat(((ChildField.ServiceRecordField) field).method().params())
            .as("no batch key is minted over an argument the author meant to pass")
            .noneMatch(MethodRef.Param.Sourced.class::isInstance);
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
