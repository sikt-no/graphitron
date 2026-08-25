package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_FILTER_ROLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldDirective;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedInputFieldLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_input_field_filter_role} states: which rule resolves what one input field
 * contributes, at the grain the classifier decides it, which is the field and the table it was
 * classified against rather than the field alone.
 *
 * <p>The classifier's input-field switch is an ordered fork, so the cases come in two kinds and
 * both are needed. One kind pins what an arm answers when nothing else is in play. The other pins
 * which arm wins when two could answer, and those cases are the ones a union would pass and a rank
 * would not: they seed the losing arm's premise deliberately, so a case asserting a role also
 * asserts that the arm above it was consulted first.
 *
 * <p>The rejection population gets cases stated as absence, because a site the classifier refuses
 * and a site that resolves to nothing are different answers here. {@code UNBOUND} is the second of
 * those and it is a role: the classifier has a carrier for a name that reaches no column, and only
 * the refusals are silent.
 */
class InputFieldFilterRoleTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== One arm at a time =====

    /** The ordinary case: a name that reaches a column on the handed table is a name match. */
    @Test
    void aNameThatReachesAColumnIsANameMatch() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film NAME_MATCHED");
        });
    }

    /**
     * A name that reaches no column is the classifier's unbound carrier, which is a resolved result
     * and not a refusal. Stated as a role rather than as absence, because absence here means the
     * classifier rejected the site and this one it accepted.
     */
    @Test
    void aNameThatReachesNoColumnIsTheUnboundCarrier() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "nothingNamedThis", "String");

            assertThat(roles(dsl)).containsExactly("FilmFilter.nothingNamedThis@film UNBOUND");
        });
    }

    /** A field whose named type is an input object is a nesting the walk descends into. */
    @Test
    void anInputObjectTypedFieldIsANesting() {
        withCatalog(dsl -> {
            filmSite(dsl);
            nestedInputField(dsl, "nested", "title");

            assertThat(roles(dsl)).containsExactly(
                "FilmFilter.nested@film NESTING",
                "NestedFilter.title@film NAME_MATCHED");
        });
    }

    /**
     * The nested type's fields resolve against the same table the outer field was handed, which is
     * the classifier's nesting arm descending with the table rather than re-rooting. The case above
     * shows the pair; this one states the property that makes it a property, a binding on the
     * nested type that would have re-rooted it if anything read one.
     */
    @Test
    void nestingCarriesTheTableDownUnchanged() {
        withCatalog(dsl -> {
            filmSite(dsl);
            nestedInputField(dsl, "nested", "title");
            seedTableBinding(dsl, GRAPH, "NestedFilter", "language");

            assertThat(roles(dsl)).containsExactly(
                "FilmFilter.nested@film NESTING",
                "NestedFilter.title@film NAME_MATCHED");
        });
    }

    /** An {@code id: ID} field is the node id of the table it was handed, implicitly. */
    @Test
    void anIdFieldNamedIdIsTheImplicitNodeId() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            inputField(dsl, "id", "ID");

            assertThat(roles(dsl)).containsExactly("FilmFilter.id@film NODE_ID");
        });
    }

    /** An authored {@code @nodeId} is the node id whatever the field is called. */
    @Test
    void anAuthoredNodeIdIsTheNodeId() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            inputField(dsl, "film", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "film", "Film");

            assertThat(roles(dsl)).containsExactly("FilmFilter.film@film NODE_ID");
        });
    }

    /**
     * A bare {@code @nodeId} is the node id too, its target inferred from the table the field was
     * handed. The store's instruction relation owns that inference and every decline it makes, so
     * the case is here to state that this relation reads both bases and not just the written one.
     */
    @Test
    void aBareAuthoredNodeIdInfersItsTargetFromTheTable() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            inputField(dsl, "film", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "film", null);

            assertThat(roles(dsl)).containsExactly("FilmFilter.film@film NODE_ID");
        });
    }

    /** {@code @condition(override: true)} on a plain field is the condition-owned carrier. */
    @Test
    void anOverridingConditionOwnsThePlainSitesContribution() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "title", true);

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film CONDITION_OWNED");
        });
    }

    // ===== Which arm wins =====

    /**
     * The authored {@code @nodeId} is consulted before the column lookup, so a field whose name
     * also reaches a column is still the node id. The column is seeded on purpose: without it this
     * case would pass with the two arms in either order.
     */
    @Test
    void anAuthoredNodeIdOutranksTheNameItWouldHaveMatched() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film", 2, "FILM");
            inputField(dsl, "film", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "film", "Film");

            assertThat(roles(dsl)).containsExactly("FilmFilter.film@film NODE_ID");
        });
    }

    /**
     * A nested input object is consulted before the condition fork, so an overriding condition on a
     * nesting does not turn it into the condition-owned carrier: the classifier returns the nesting
     * with the condition attached, and only a plain scalar field reaches the fork the override
     * decides.
     */
    @Test
    void aNestingOutranksTheConditionOwnedCarrier() {
        withCatalog(dsl -> {
            filmSite(dsl);
            nestedInputField(dsl, "nested", "title");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "nested", true);

            assertThat(roles(dsl)).containsExactly(
                "FilmFilter.nested@film NESTING",
                "NestedFilter.title@film NAME_MATCHED");
        });
    }

    /**
     * A {@code @reference} path is consulted before the condition fork too, for the same reason:
     * the reference arm builds its carrier with the condition attached rather than yielding to it.
     */
    @Test
    void aReferencePathOutranksTheConditionOwnedCarrier() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "name", "String");
            pathTo(dsl, "name", "language");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "name", true);

            assertThat(roles(dsl)).containsExactly("FilmFilter.name@film NAME_MATCHED");
        });
    }

    /**
     * A {@code @field(name:)} binding suppresses the implicit reading, and here that is a
     * fall-through to the name match rather than the rejection the argument site gives it. The
     * difference is the point of the case: at an argument a binding beside an implicit node id
     * names two binding axes at once and the site resolves to nothing, and at an input field the
     * binding simply renames what the column lookup looks for.
     */
    @Test
    void aBindingFallsTheImplicitReadingThroughToTheNameMatch() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 2, "FILM_ID");
            inputField(dsl, "id", "ID");
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "id", "film_id");

            assertThat(roles(dsl)).containsExactly("FilmFilter.id@film NAME_MATCHED");
        });
    }

    /**
     * Beside an authored {@code @nodeId} a binding is neither a rejection nor a fall-through: the
     * resolver never consults it, so the site is still the node id. The paired case above is what
     * makes this one worth stating separately.
     */
    @Test
    void aBindingBesideAnAuthoredNodeIdChangesNothing() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 2, "FILM_ID");
            inputField(dsl, "id", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "id", "Film");
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "id", "film_id");

            assertThat(roles(dsl)).containsExactly("FilmFilter.id@film NODE_ID");
        });
    }

    // ===== The rejection population, stated as absence =====

    /**
     * A {@code @notGenerated} application rejects the site outright whatever else would have
     * resolved, so the column its name reaches is seeded and the site is still silent.
     */
    @Test
    void aNotGeneratedApplicationRejectsTheSite() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedFieldDirective(dsl, GRAPH, "FilmFilter", "title", "notGenerated");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /** {@code @lookupKey} is retired at this site and rejected rather than ignored. */
    @Test
    void aLookupKeyApplicationRejectsTheSite() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedInputFieldLookupKey(dsl, GRAPH, "FilmFilter", "title");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * A {@code @reference} whose path resolves no column is a rejection and not the unbound
     * carrier: the reference arm returns before the column lookup that mints that carrier ever
     * runs, so a reader cannot read this site as a field contributing nothing.
     */
    @Test
    void aReferenceThatResolvesNoColumnIsARejectionAndNotUnbound() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "nothingNamedThis", "String");
            pathTo(dsl, "nothingNamedThis", "language");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * Repetition is a conflict here and not a chain, ordered composition having no meaning on an
     * input field. Both applications name a table the walk could reach, so what rejects the site is
     * the repetition itself.
     */
    @Test
    void aRepeatedReferenceIsAConflictAndNotAChain() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "name", "String");
            pathTo(dsl, "name", "language");
            seedFieldReference(dsl, GRAPH, "FilmFilter", "name", 1);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "name", 1, 0, "language", null);

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * An authored {@code @nodeId} naming a type that is no node resolves nothing, and it does not
     * fall through: the classifier enters the node-id arm on the directive and the {@code ID} type
     * alone, and what the resolver then declines is a rejection rather than a return to the column
     * lookup below. The column the field's name reaches is seeded, so a fall-through would show.
     */
    @Test
    void anAuthoredNodeIdThatResolvesNoNodeTypeIsARejection() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "ID", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "ID"));
            seedFieldNodeId(dsl, GRAPH, "FilmFilter", "title", "NotANodeType");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * A real column of the field's own name shadows the implicit reading into a rejection, and the
     * test is made ahead of the column lookup so that the column is a rejection rather than a
     * contest the name match would win.
     */
    @Test
    void aColumnOfThatNameShadowsTheImplicitReadingIntoARejection() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedColumn(dsl, PKG, PUBLIC, "film", "id", 2, "ID");
            inputField(dsl, "id", "ID");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * A table backing two node types cannot answer which one an unqualified {@code id} means, and
     * the classifier says so rather than picking. The second node type is the only difference from
     * the implicit-reading case above, which resolves. The store's instruction relation declines
     * this site by requiring a single candidate, so the refusal has to be stated here; without it
     * an arm one rung down would claim a site the build rejects.
     */
    @Test
    void aTableBackingTwoNodeTypesRejectsTheImplicitReading() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedTableBinding(dsl, GRAPH, "Movie", "film");
            seedNode(dsl, GRAPH, "Movie");
            inputField(dsl, "id", "ID");

            assertThat(roles(dsl)).isEmpty();
        });
    }

    /**
     * A table backing no node type at all is not a refusal: an {@code id} field there is an
     * ordinary column, per the rule that {@code ID} without {@code @nodeId} is a plain scalar. The
     * case beside the one above, so the two outcomes of the same lookup are both pinned.
     */
    @Test
    void aTableBackingNoNodeTypeFallsThroughToTheNameMatch() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "id", 2, "ID");
            inputField(dsl, "id", "ID");

            assertThat(roles(dsl)).containsExactly("FilmFilter.id@film NAME_MATCHED");
        });
    }

    // ===== The condition modifier =====

    /**
     * A composing {@code @condition} is a modifier and not a role: the field still classifies as
     * whatever its own shape says, and the condition rides alongside.
     */
    @Test
    void aComposingConditionIsAModifierAndNotARole() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "title", false);

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film NAME_MATCHED +condition");
        });
    }

    /**
     * An omitted {@code override:} is the composing spelling, which is the default this relation
     * applies to a null rather than a value it invents: the capture stores the argument as written.
     */
    @Test
    void anOmittedOverrideIsTheComposingSpelling() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "title", (Boolean) null);

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film NAME_MATCHED +condition");
        });
    }

    /**
     * The owning condition is not also a composing one, the two being the same directive read two
     * ways. Without this the modifier and the role would double-count a single application.
     */
    @Test
    void theOwningConditionIsNotAlsoAComposingOne() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "title", true);

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film CONDITION_OWNED");
        });
    }

    /**
     * An unbound field carrying a composing condition is exactly the located rejection the
     * validator mints, a condition asked to compose with an implicit predicate that has no column
     * to build one from. The pair is a rejection's population stated as two columns rather than as
     * an absence, which is why the carrier has a name at all.
     */
    @Test
    void anUnboundFieldWithAComposingConditionIsTheLocatedRejectionsPopulation() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "nothingNamedThis", "String");
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "nothingNamedThis", false);

            assertThat(roles(dsl))
                .containsExactly("FilmFilter.nothingNamedThis@film UNBOUND +condition");
        });
    }

    // ===== The grain =====

    /**
     * One input type reached from two arguments on two tables is two rows, and the roles can
     * differ: the same declaration is a name match where a column of that name exists and the
     * unbound carrier where none does. A coordinate-keyed relation could state only one of these
     * and would be wrong at whichever site it did not pick.
     */
    @Test
    void oneInputTypeUnderTwoTablesCanCarryTwoRoles() {
        withCatalog(dsl -> {
            filmSite(dsl);
            actorSite(dsl);
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "String"));
            seedOccurrencePath(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "String"));

            assertThat(roles(dsl)).containsExactly(
                "FilmFilter.title@actor UNBOUND",
                "FilmFilter.title@film NAME_MATCHED");
        });
    }

    /**
     * Two use sites over one table collapse to the one classification the build performs, the
     * several occurrences of a field under one table being one answer and not several.
     */
    @Test
    void twoUseSitesOverOneTableCollapseToOneRow() {
        withCatalog(dsl -> {
            filmSite(dsl);
            seedField(dsl, GRAPH, "Query", "otherFilms", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "String"));
            seedOccurrencePath(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "title", "String"));

            assertThat(roles(dsl)).containsExactly("FilmFilter.title@film NAME_MATCHED");
        });
    }

    /** The graph partition holds, a sibling graph's identical coordinates resolving none of these. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseFields() {
        withCatalog(dsl -> {
            filmSite(dsl);
            inputField(dsl, "title", "String");

            assertThat(rolesIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * {@code film} carries {@code title}, {@code actor} carries {@code first_name}, and
     * {@code language} carries {@code name}, so a field can reach a column on the table it was
     * handed, on a table a path away, or on neither. No table has an {@code id} column; the cases
     * about shadowing seed one, which is what makes the shadow the case's subject rather than the
     * fixture's.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "film");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 0, "TITLE");
            seedTable(dsl, PKG, PUBLIC, "actor");
            seedConstraint(dsl, PKG, PUBLIC, "actor", "actor_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "actor", "first_name", 0, "FIRST_NAME");
            seedTable(dsl, PKG, PUBLIC, "language");
            seedConstraint(dsl, PKG, PUBLIC, "language", "language_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "language", "name", 0, "NAME");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_language_id_fkey", "FOREIGN KEY", null);
            seedReferentialConstraint(dsl, PKG, PUBLIC, "film", "film_language_id_fkey",
                PKG, PUBLIC, "language", "language_pkey");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** {@code Query.films(filter: FilmFilter): [Film]} with {@code Film} bound to {@code film}. */
    private static void filmSite(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", "films", "Film", true);
        seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");
    }

    /** The same shape over {@code actor}, so one input type can be reached from two tables. */
    private static void actorSite(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Actor", "actor");
        seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
        seedArgument(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter");
    }

    /** One input field on {@code FilmFilter}, reached from the film use site. */
    private static void inputField(DSLContext dsl, String fieldName, String namedType) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, namedType, 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
            new OccurrenceStep("FilmFilter", fieldName, namedType));
    }

    /** An input-object-typed field on {@code FilmFilter} carrying one scalar field of its own. */
    private static void nestedInputField(DSLContext dsl, String fieldName, String nestedFieldName) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, "NestedFilter", 0, false, false, null);
        seedInputField(dsl, GRAPH, "NestedFilter", nestedFieldName, "String", 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
            new OccurrenceStep("FilmFilter", fieldName, "NestedFilter"),
            new OccurrenceStep("NestedFilter", nestedFieldName, "String"));
    }

    /** One {@code @reference} application on an input field, naming one table to hop to. */
    private static void pathTo(DSLContext dsl, String fieldName, String tableRef) {
        seedFieldReference(dsl, GRAPH, "FilmFilter", fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", fieldName, 0, 0, tableRef, null);
    }

    private static List<String> roles(DSLContext dsl) {
        return rolesIn(dsl, GRAPH);
    }

    private static List<String> rolesIn(DSLContext dsl, String graphName) {
        derive(dsl);
        Result<Record> rows = dsl.select(INTENT_INPUT_FIELD_FILTER_ROLE.fields())
            .from(INTENT_INPUT_FIELD_FILTER_ROLE)
            .where(INTENT_INPUT_FIELD_FILTER_ROLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_INPUT_FIELD_FILTER_ROLE.TYPE_NAME,
                INTENT_INPUT_FIELD_FILTER_ROLE.FIELD_NAME,
                INTENT_INPUT_FIELD_FILTER_ROLE.RESOLVING_TABLE)
            .fetch();
        return rows.map(InputFieldFilterRoleTest::render);
    }

    /** The site, the table it was classified against, its role, and the modifier when it is set. */
    private static String render(Record row) {
        return row.get(INTENT_INPUT_FIELD_FILTER_ROLE.TYPE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_FILTER_ROLE.FIELD_NAME) + "@"
            + row.get(INTENT_INPUT_FIELD_FILTER_ROLE.RESOLVING_TABLE) + " "
            + row.get(INTENT_INPUT_FIELD_FILTER_ROLE.ROLE)
            + (Boolean.TRUE.equals(row.get(INTENT_INPUT_FIELD_FILTER_ROLE.AUTHORED_CONDITION))
               ? " +condition" : "");
    }
}
