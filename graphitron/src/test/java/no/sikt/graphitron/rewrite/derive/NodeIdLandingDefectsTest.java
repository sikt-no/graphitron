package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.derive.NodeIdLandingDefects;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store-backed home of the {@code @nodeId} landing rules: real SDL captured against the sakila
 * catalog, and the violations {@link NodeIdLandingDefects} projects from
 * {@code intent_node_id_decode_landing_defect}. This is the tier that says an author's schema
 * reaches that relation in the shape the rules read, and that the report a consumer meets is minted
 * from what it finds.
 *
 * <p>What the view returns given rows is not asked here. That is the relation's own algebra, its two
 * verdicts, its completeness gate and its participant exclusion, and it lives in the module whose
 * DDL declares it, in {@code no.sikt.graphitron.model.intent.NodeIdDecodeLandingDefectTest}, against
 * a store seeded row by row. What stands here is the decode: which {@link Rejection} arm each verdict
 * becomes, the prose it carries, the location it points at, and the population this consumer asks
 * about.
 *
 * <p>Every refusing fixture below spells a schema that {@code graphitron:generate} accepted with zero
 * errors, and what an author met instead differed by what the terminal table happened to hold. The
 * truncated {@code Category} pair is the whole of that spread in two schemas: reached from
 * {@code film} the mislanding emits SQL that is right by coincidence, {@code film_category} carrying
 * a {@code category_id} of the same type as the child column of its own key to {@code category};
 * reached from {@code inventory} the identical classification emits {@code FILM.CATEGORY_ID} and the
 * consumer's own {@code javac} rejects it. Both refuse now, and keeping the pair is what says the
 * refusal is about the landing rather than about whether the output happened to compile.
 *
 * <p>The catalog is load-bearing rather than incidental here, for both verdicts. A key column's Java
 * type is what jOOQ reports off the generated field, so the type verdict cannot be constructed
 * without it, and the converter-diverged fixture is already in the sakila DDL:
 * {@code converter_org.org_code} is a {@code bigint} domain whose column carries a converter and
 * reports {@code java.lang.String}, while {@code diverged_ref_child.org_code} is a plain
 * {@code bigint} referencing it and reports {@code java.lang.Long}. Declaring the node type over
 * that table is the whole setup.
 */
@PipelineTier
class NodeIdLandingDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    /** The sakila catalog, scanned once for the class; both verdicts read a column's Java type. */
    private static JooqCatalog jooq;

    @BeforeAll
    static void scanTheCatalog() {
        var ctx = TestConfiguration.testContext();
        jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }

    @TempDir
    Path tmp;

    /** The decode target and the two tables a truncated path departs from. */
    private static final String CATEGORY_STOCK = """
        interface Node { id: ID! }
        type Category implements Node @table(name: "category") @node { id: ID! @nodeId }
        type Film @table(name: "film") { title: String }
        type Inventory @table(name: "inventory") { inventoryId: ID! @field(name: "inventory_id") }
        """;

    /** The converter-diverged foreign key, and the same key with both ends converted. */
    private static final String CONVERTER_ORG = """
        interface Node { id: ID! }
        type ConverterOrg implements Node @table(name: "converter_org") @node { id: ID! @nodeId }
        type DivergedRefChild @table(name: "diverged_ref_child") {
            childName: String @field(name: "child_name")
        }
        type ConverterCampus @table(name: "converter_campus") {
            campusName: String @field(name: "campus_name")
        }
        """;

    // ===== PATH_STOPS_SHORT =====

    /**
     * The coincidence twin: the path stops on the junction table, nothing lands, and the remote
     * predicate today happens to name a {@code film_category.category_id} that exists and carries
     * the value the author meant. There is nothing in the emitted output to point at, which is why
     * this fault needed a verification rule rather than a codegen fix.
     */
    @Test
    void aPathStoppingOnTheJunctionTableIsRejectedNamingBothTables() {
        assertThat(messages(detect(CATEGORY_STOCK + """
            input FilmFilter {
                categoryRef: ID! @nodeId(typeName: "Category")
                    @reference(path: [{key: "film_category_film_id_fkey"}])
            }
            type Query { films(filter: FilmFilter): [Film!]! }
            """))).containsExactly(
            "Field 'Query.films': input field 'FilmFilter.categoryRef':"
            + " @nodeId(typeName: \"Category\") reaches its target through an @reference path whose"
            + " last step lands on table 'film_category', not on Category's @table 'category'."
            + " A decoded Category id binds against columns of 'category', so the path has to end"
            + " there: add the remaining step, or name the type the path already reaches");
    }

    /**
     * The uncompilable twin: one hop from {@code inventory} arrives on {@code film}, which carries
     * no {@code category_id} at all, so the same classification emits a column the table does not
     * have. Identical verdict, identical prose, and only the tables differ, which is the point of
     * running the pair.
     */
    @Test
    void aPathStoppingOnAnUnrelatedTableIsRejectedTheSameWay() {
        assertThat(messages(detect(CATEGORY_STOCK + """
            input InventoryFilter {
                categoryRef: ID! @nodeId(typeName: "Category")
                    @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { stock(filter: InventoryFilter): [Inventory!]! }
            """))).containsExactly(
            "Field 'Query.stock': input field 'InventoryFilter.categoryRef':"
            + " @nodeId(typeName: \"Category\") reaches its target through an @reference path whose"
            + " last step lands on table 'film', not on Category's @table 'category'."
            + " A decoded Category id binds against columns of 'category', so the path has to end"
            + " there: add the remaining step, or name the type the path already reaches");
    }

    /**
     * The remaining step written: the terminal hop arrives on the node type's own table and there
     * is nothing to report. Asserted as an empty report rather than as an absent verdict, because
     * this family strictly adds refusals and a chain that read and filtered has to go on doing so.
     */
    @Test
    void theWholeJunctionPathIsNoDefect() {
        assertThat(detect(CATEGORY_STOCK + """
            input FilmFilter {
                categoryRef: ID! @nodeId(typeName: "Category")
                    @reference(path: [
                        {key: "film_category_film_id_fkey"},
                        {key: "film_category_category_id_fkey"}
                    ])
            }
            type Query { films(filter: FilmFilter): [Film!]! }
            """))
            .as("the path ends on the table the node type is bound to")
            .isEmpty();
    }

    // ===== LANDING_TYPE_DISAGREEMENT =====

    /**
     * The diverged key: nothing is written, the one foreign key from
     * {@code diverged_ref_child} to {@code converter_org} is discovered, and its two ends report
     * different Java types because the converter is selected by catalog type and only one end has
     * it. The generated row comparison does not compile, and the SQL it would have rendered was
     * always valid, a converter being a client-side mapping only.
     */
    @Test
    void aDiscoveredKeyWhoseEndsDisagreeIsRejectedNamingBothColumnsAndTypes() {
        var violations = detect(CONVERTER_ORG + """
            input DivergedRefChildFilter {
                orgRef: ID! @nodeId(typeName: "ConverterOrg")
            }
            type Query { divergedChildren(filter: DivergedRefChildFilter): [DivergedRefChild!]! }
            """);

        assertThat(messages(violations)).containsExactly(
            "Field 'Query.divergedChildren': input field 'DivergedRefChildFilter.orgRef':"
            + " @nodeId(typeName: \"ConverterOrg\") decodes key column 'org_code' of table"
            + " 'converter_org', which jOOQ binds as String, and the reference path lands it on"
            + " column 'org_code' of table 'diverged_ref_child', which jOOQ binds as Long."
            + " The two columns disagree on Java type, so no predicate can bind one against the"
            + " other. Either align the two columns' catalog types, or route the reference through"
            + " a path that lands the key on a column of its own type");
        assertThat(violations.getFirst().rejection())
            .isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(violations.getFirst().location()).isNotNull();
        assertThat(violations.getFirst().location().getSourceName()).endsWith("fixture.graphqls");
    }

    /**
     * The same foreign key one table over, where both ends carry the converter and both report
     * {@code java.lang.String}. The control that proves the comparison is on the type the catalog
     * reports rather than on whether a converter is present at all: this landing is
     * converter-backed on both sides and is not a fault.
     */
    @Test
    void aLandingWhoseEndsBothCarryTheConverterIsNoDefect() {
        assertThat(detect(CONVERTER_ORG + """
            input ConverterCampusFilter {
                orgRef: ID! @nodeId(typeName: "ConverterOrg")
            }
            type Query { campuses(filter: ConverterCampusFilter): [ConverterCampus!]! }
            """))
            .as("both ends of the key report java.lang.String")
            .isEmpty();
    }

    /**
     * The same diverged key at an argument coordinate, which is where the lead spells the slot as
     * an argument rather than as an input field. The two sites are one row shape in the view and
     * two spellings in the report, so the argument one is asserted here rather than left to the
     * site's other fixtures, all of which refuse nothing by design.
     */
    @Test
    void anArgumentSiteRefusalNamesTheArgument() {
        assertThat(messages(detect(CONVERTER_ORG + """
            type Query {
                divergedChildren(orgRef: ID @nodeId(typeName: "ConverterOrg")): [DivergedRefChild!]!
            }
            """))).singleElement(InstanceOfAssertFactories.STRING)
            .startsWith("Field 'Query.divergedChildren': argument 'orgRef':"
                + " @nodeId(typeName: \"ConverterOrg\") decodes key column 'org_code'");
    }

    // ===== The route this family declines =====

    /**
     * A per-participant route refuses nothing, at either coordinate. The store states such a
     * branch's navigation as auto-discovery's, no view reading the {@code @referenceFor} step
     * tables, while the classifier walks the chain the author wrote there, so a verdict computed
     * under the store's answer would judge a route the generator does not take and could refuse a
     * sound schema.
     *
     * <p>This is the narrowing's enforcer rather than a control: it is what fails if a later change
     * lets a verdict reach that route, and it is the assertion the store's own
     * {@code @referenceFor} repair inverts when it lands. Both fixtures use the diverged key, which
     * is the shape that refuses when nothing declines it.
     */
    @Test
    void aParticipantRouteAtAnInputFieldRefusesNothing() {
        assertThat(detect(CONVERTER_ORG + """
            union OrgHolder = DivergedRefChild | ConverterCampus
            input OrgHolderFilter {
                orgRef: ID @nodeId(typeName: "ConverterOrg")
                    @referenceFor(type: "DivergedRefChild", path: [{key: "diverged_ref_child_org_code_fkey"}])
            }
            type Query { holders(filter: OrgHolderFilter): [OrgHolder!]! }
            """))
            .as("the branch a participant route applies at is declined rather than judged")
            .isEmpty();
    }

    /**
     * The same polymorphic consumer with no application written: the diverged branch refuses and
     * its agreeing sibling does not. The other direction of the two cases above, and what says the
     * exclusion is what silenced that branch rather than anything about a union return type. It is
     * also the one fixture whose coordinate holds two branches, so it is where the lead names one.
     */
    @Test
    void aPolymorphicConsumerWithNoParticipantRouteStillRefusesTheDivergedBranch() {
        assertThat(messages(detect(CONVERTER_ORG + """
            union OrgHolder = DivergedRefChild | ConverterCampus
            input OrgHolderFilter {
                orgRef: ID @nodeId(typeName: "ConverterOrg")
            }
            type Query { holders(filter: OrgHolderFilter): [OrgHolder!]! }
            """))).singleElement(InstanceOfAssertFactories.STRING)
            .contains("input field 'OrgHolderFilter.orgRef' on the 'diverged_ref_child' branch:")
            .contains("which jOOQ binds as String")
            .contains("which jOOQ binds as Long");
    }

    /** The same route at the argument coordinate, whose consumer is the field it sits on. */
    @Test
    void aParticipantRouteAtAnArgumentRefusesNothing() {
        assertThat(detect(CONVERTER_ORG + """
            union OrgHolder = DivergedRefChild | ConverterCampus
            type Query {
                holders(
                    orgRef: ID @nodeId(typeName: "ConverterOrg")
                        @referenceFor(type: "DivergedRefChild", path: [{key: "diverged_ref_child_org_code_fkey"}])
                ): [OrgHolder!]!
            }
            """))
            .as("the argument coordinate declines the same branch on the same terms")
            .isEmpty();
    }

    // ===== The population this consumer asks about =====

    /**
     * The build-error population is the classification domain, so the same mislanding at a
     * coordinate no root operation reaches mints nothing: only an emitted coordinate can fail a
     * build. The view itself states no such filter, so an editor's diagnostic arm can read the row
     * ungated, a coordinate nothing reaches being where an author most needs to be told.
     */
    @Test
    void aRefusalOutsideTheClassificationDomainFailsNoBuild() {
        assertThat(detect(CATEGORY_STOCK + """
            input FilmFilter {
                categoryRef: ID! @nodeId(typeName: "Category")
                    @reference(path: [{key: "film_category_film_id_fkey"}])
            }
            type Query { category: Category }
            type Orphan { films(filter: FilmFilter): [Film!]! }
            """))
            .as("no root operation reaches Orphan, so no emitted source carries this decode")
            .isEmpty();
    }

    // ===== Helpers =====

    /** Captures {@code sdl} against the catalog and runs the detection over what capture wrote. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq)) {
            return NodeIdLandingDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** The violations' messages, the surface an author actually meets. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }
}
