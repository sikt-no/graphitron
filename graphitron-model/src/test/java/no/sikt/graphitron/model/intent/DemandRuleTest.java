package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record1;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_DEMAND_RULE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_EXEMPTION_RULE;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_EXEMPTION;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedConnection;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedError;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedTypeDomain;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the demand relations return: which coordinates and which types require a classification
 * verdict, why, and which are intentionally not asked for one. Four rule relations, a demand and an
 * exemption at each of the two grains, plus a reduction over each pair. All of them are keyed by the
 * type rather than by the coordinate, because every rule shipped so far is a property of the parent,
 * so the field grain is a join the reduction performs and not something a rule states.
 *
 * <p>The two rule relations are deliberately unmasked, against each other and against demand. A
 * structural connection type is also a directiveless object and both readings are true, so both
 * arrive as rows and no arm has to know what the others matched. Picking one reading per coordinate
 * is the reduction's job alone, and it does it by a declared arm order rather than by anything a row
 * carries, which is why a case about precedence has to build a type that two arms both answer.
 *
 * <p>Three shapes are the ones worth stating deliberately, each being what tells an arm apart from
 * its neighbour. A root binding is not a type named {@code Query}: the rules key off the binding, so
 * a renamed root and an unbound type with the conventional name are the pair that separates the two
 * readings. A producer is a reference that decoded as far as its own arm requires, and the arms
 * disagree about how far that is, so a half-decoded reference is a row both the demand arm and the
 * exemption arm have to answer consistently. And connection machinery is recognized structurally
 * rather than from a marker, so its three conditions get a fixture each.
 *
 * <p>The reductions add one gate of their own that the rules do not have: only members of the
 * classification domain resolve. The domain is a materialized table rather than a view, its closure
 * being over a type graph with cycles, so a case about that gate seeds membership directly and can
 * state one a crawler would never have reached, which is the arrangement that tells a gate on the
 * domain apart from a gate on what its members happen to carry.
 *
 * <p>Whether real SDL reaches these relations in the shape the rules read, and whether what they
 * return agrees with the classification walk that still owns the verdict, is a different question
 * with a different home: {@code no.sikt.graphitron.rewrite.derive.DemandShadowTest} sweeps the
 * classified corpus against the walked registries, names the populations where the two disagree and
 * pins each one non-empty.
 */
class DemandRuleTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";
    private static final String SERVICE_CLASS = "app.Service";
    private static final String EXTERNAL_CLASS = "app.External";

    // ===== The field-grain rules =====

    /**
     * One arm at a time, each answering with its own rule and nothing else. The producer arm is
     * three arms wearing one literal, so all three of its sources appear here.
     */
    @Test
    void everyFieldDemandArmAnswersWithItsOwnRule() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedError(dsl, GRAPH, "Failure");
            seedProducedPayload(dsl, "served", "ServicePayload");
            seedService(dsl, GRAPH, "Query", "served", SERVICE_CLASS, "all");
            seedProducedPayload(dsl, "external", "ExternalPayload");
            seedExternalField(dsl, GRAPH, "Query", "external", EXTERNAL_CLASS, null);
            seedProducedPayload(dsl, "saved", "SavePayload");
            seedMutation(dsl, GRAPH, "Query", "saved", "INSERT");

            assertThat(fieldDemand(dsl, "Query")).containsExactly("ROOT_OPERATION");
            assertThat(fieldDemand(dsl, "Film")).containsExactly("TABLE_TYPE");
            assertThat(fieldDemand(dsl, "Failure")).containsExactly("ERROR_TYPE");
            assertThat(fieldDemand(dsl, "ServicePayload")).containsExactly("PRODUCER_PAYLOAD");
            assertThat(fieldDemand(dsl, "ExternalPayload")).containsExactly("PRODUCER_PAYLOAD");
            assertThat(fieldDemand(dsl, "SavePayload")).containsExactly("PRODUCER_PAYLOAD");
        });
    }

    /**
     * The root arm keys off the schema block's binding, not the conventional name. Both halves have
     * to be stated together: a renamed root that demands, beside a type named {@code Query} that
     * nothing binds and that therefore does not, since a rule reading the name answers the same as
     * this one on every schema where the two agree.
     */
    @Test
    void theRootArmReadsTheOperationBindingRatherThanTheTypesName() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "SUBSCRIPTION", "Feed");
            seedDeclaredType(dsl, GRAPH, "Query", "OBJECT");

            assertThat(fieldDemand(dsl, "Feed")).containsExactly("ROOT_OPERATION");
            assertThat(fieldDemand(dsl, "Query")).isEmpty();
            assertThat(typeDemand(dsl, "Feed")).containsExactly("ROOT_OPERATION");
            assertThat(typeDemand(dsl, "Query")).isEmpty();
        });
    }

    /**
     * How much of a reference each producer source requires. A {@code @service} needs both parts to
     * have decoded, an {@code @externalField} needs only the class (its method falls back to the
     * field's own name), and a {@code @mutation} reads neither, being a producer by its presence.
     *
     * <p>The exemption side is asserted in the same fixture, because the two relations state the
     * producer question independently and a case that read only one of them would not notice them
     * drifting apart: a payload whose reference did not decode far enough keeps the catch-all
     * reading exactly where the demand arm declines it.
     */
    @Test
    void theProducerArmReadsAsMuchOfEachReferenceAsItsOwnSourceRequires() {
        withSeededStore(GRAPH, dsl -> {
            seedProducedPayload(dsl, "whole", "Whole");
            seedService(dsl, GRAPH, "Query", "whole", SERVICE_CLASS, "get");
            seedProducedPayload(dsl, "methodless", "Methodless");
            seedService(dsl, GRAPH, "Query", "methodless", SERVICE_CLASS, null);
            seedProducedPayload(dsl, "classless", "Classless");
            seedService(dsl, GRAPH, "Query", "classless", null, "get");
            seedProducedPayload(dsl, "external", "External");
            seedExternalField(dsl, GRAPH, "Query", "external", EXTERNAL_CLASS, null);
            seedProducedPayload(dsl, "externalClassless", "ExternalClassless");
            seedExternalField(dsl, GRAPH, "Query", "externalClassless", null, "get");
            seedProducedPayload(dsl, "mutated", "Mutated");
            seedMutation(dsl, GRAPH, "Query", "mutated", "DELETE");

            assertThat(payloadsWithRule(dsl, "PRODUCER_PAYLOAD"))
                .containsExactlyInAnyOrder("Whole", "External", "Mutated");
            assertThat(typesWithReason(dsl, "NESTING_TARGET"))
                .as("the exemption arm's own producer anti-joins answer the same question")
                .containsExactlyInAnyOrder("Methodless", "Classless", "ExternalClassless", "Query");
        });
    }

    /**
     * The underscore masks, which transcribe the walk's name short-circuit: an underscore-prefixed
     * type never classifies, whatever it carries. The root arm is the exception and is checked
     * before the short-circuit, so a renamed root spelled with an underscore still demands.
     */
    @Test
    void theDemandArmsMaskUnderscoreNamesEverywhereButAtARootBinding() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "_Root");
            seedTableBinding(dsl, GRAPH, "_Table", "film");
            seedError(dsl, GRAPH, "_Error");
            seedDeclaredType(dsl, GRAPH, "_Payload", "OBJECT");
            seedField(dsl, GRAPH, "_Root", "produced", "_Payload", false);
            seedService(dsl, GRAPH, "_Root", "produced", SERVICE_CLASS, "get");

            assertThat(fieldDemand(dsl, "_Root")).containsExactly("ROOT_OPERATION");
            assertThat(fieldDemand(dsl, "_Table")).isEmpty();
            assertThat(fieldDemand(dsl, "_Error")).isEmpty();
            assertThat(fieldDemand(dsl, "_Payload")).isEmpty();
            assertThat(typeDemand(dsl, "_Root")).containsExactly("ROOT_OPERATION");
            assertThat(typeDemand(dsl, "_Table")).isEmpty();
            assertThat(typeDemand(dsl, "_Error")).isEmpty();
        });
    }

    /**
     * Every demand arm joins the type's kind and takes objects alone, which is a separate gate from
     * the masks above and needs its own fixture: an application on a type of another kind is an
     * ordinary row that the arm declines to read.
     */
    @Test
    void theDemandArmsLandOnObjectsAlone() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Named", "INTERFACE");
            seedRootOperation(dsl, GRAPH, "QUERY", "Named");
            seedDeclaredType(dsl, GRAPH, "FilmRef", "INPUT_OBJECT");
            seedTableBinding(dsl, GRAPH, "FilmRef", "film");
            seedDeclaredType(dsl, GRAPH, "Fault", "INTERFACE");
            seedError(dsl, GRAPH, "Fault");
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedField(dsl, GRAPH, "Query", "media", "Media", false);
            seedService(dsl, GRAPH, "Query", "media", SERVICE_CLASS, "get");

            assertThat(fieldDemand(dsl, "Named")).isEmpty();
            assertThat(fieldDemand(dsl, "FilmRef")).isEmpty();
            assertThat(fieldDemand(dsl, "Fault")).isEmpty();
            assertThat(fieldDemand(dsl, "Media")).isEmpty();
        });
    }

    // ===== The field-grain exemptions =====

    /**
     * One arm at a time again, and the overlap that makes the point of leaving them unmasked: the
     * connection type carries both its machinery reading and the catch-all, while the underscore
     * object carries the underscore reading alone, the catch-all having a mask of its own.
     */
    @Test
    void everyFieldExemptionArmAnswersWithItsOwnReason() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "_Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "_FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "_Hidden", "OBJECT");
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");
            seedConnectionShape(dsl, "Query", "films", "FilmConnection", "FilmEdge", "Film");

            assertThat(fieldExemption(dsl, "Media")).containsExactly("INTERFACE_TYPE");
            assertThat(fieldExemption(dsl, "_Media"))
                .as("the interface arm is kind-wide, the underscore arm being the object kind's")
                .containsExactly("INTERFACE_TYPE");
            assertThat(fieldExemption(dsl, "FilmRef")).containsExactly("INPUT_TYPE");
            assertThat(fieldExemption(dsl, "_FilmRef")).containsExactly("INPUT_TYPE");
            assertThat(fieldExemption(dsl, "_Hidden")).containsExactly("UNDERSCORE_TYPE");
            assertThat(fieldExemption(dsl, "Plain")).containsExactly("NESTING_TARGET");
            assertThat(fieldExemption(dsl, "FilmConnection"))
                .containsExactlyInAnyOrder("CONNECTION_MACHINERY", "NESTING_TARGET");
        });
    }

    /**
     * The machinery arm recognizes a shape rather than a marker, and answers with both ends of the
     * shape it found: the type carrying the edges field and the edge type that field names.
     */
    @Test
    void theMachineryArmNamesBothEndsOfTheShapeItRecognizes() {
        withSeededStore(GRAPH, dsl -> {
            seedConnectionShape(dsl, "Query", "films", "FilmConnection", "FilmEdge", "Film");

            assertThat(typesWithReason(dsl, "CONNECTION_MACHINERY"))
                .containsExactlyInAnyOrder("FilmConnection", "FilmEdge");
        });
    }

    /**
     * Each of the recognition's three conditions, dropped one at a time. Without a carrier field
     * naming it the shape is nothing any query reaches; without a {@code node} field on the element
     * it is an ordinary list; and the element has to be an object, which needs an element of some
     * other field-bearing kind to state, an interface carrying a {@code node} field satisfying
     * everything the arm asks except the kind it asks for.
     */
    @Test
    void theMachineryArmNeedsEachOfItsThreeConditions() {
        withSeededStore(GRAPH, dsl -> {
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedDeclaredType(dsl, GRAPH, "Film", "OBJECT");

            seedField(dsl, GRAPH, "Unreached", "edges", "UnreachedEdge", true);
            seedField(dsl, GRAPH, "UnreachedEdge", "node", "Film", false);

            seedField(dsl, GRAPH, "Query", "nodeless", "Nodeless", false);
            seedField(dsl, GRAPH, "Nodeless", "edges", "NodelessEdge", true);
            seedField(dsl, GRAPH, "NodelessEdge", "cursor", "String", false);

            seedDeclaredType(dsl, GRAPH, "InterfaceEdge", "INTERFACE");
            seedField(dsl, GRAPH, "Query", "interfaceEdged", "InterfaceEdged", false);
            seedField(dsl, GRAPH, "InterfaceEdged", "edges", "InterfaceEdge", true);
            seedField(dsl, GRAPH, "InterfaceEdge", "node", "Film", false);

            assertThat(typesWithReason(dsl, "CONNECTION_MACHINERY")).isEmpty();
            assertThat(typesWithReason(dsl, "NESTING_TARGET"))
                .contains("Unreached", "Nodeless", "InterfaceEdged");
        });
    }

    /**
     * The declared {@code PageInfo} is machinery only where some promotion would fire, and the arm
     * asks that question two ways. A marked field is one; an edges-and-node shape anywhere in the
     * graph is the other, and that second reading deliberately does not require the carrier the
     * connection type's own arm requires, so an unreached shape still speaks for {@code PageInfo}.
     */
    @Test
    void pageInfoIsMachineryOnlyWhereAPromotionWouldFire() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "PageInfo", "OBJECT");
            assertThat(typesWithReason(dsl, "CONNECTION_MACHINERY")).isEmpty();
        });
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "PageInfo", "OBJECT");
            seedField(dsl, GRAPH, "Query", "films");
            seedConnection(dsl, GRAPH, "Query", "films");
            assertThat(typesWithReason(dsl, "CONNECTION_MACHINERY")).containsExactly("PageInfo");
        });
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "PageInfo", "OBJECT");
            seedDeclaredType(dsl, GRAPH, "Film", "OBJECT");
            seedField(dsl, GRAPH, "Unreached", "edges", "UnreachedEdge", true);
            seedField(dsl, GRAPH, "UnreachedEdge", "node", "Film", false);
            assertThat(typesWithReason(dsl, "CONNECTION_MACHINERY"))
                .as("the shape speaks for PageInfo even where no carrier reaches it")
                .containsExactly("PageInfo");
        });
    }

    /**
     * The catch-all's six anti-joins in one arrangement, since what it states is an absence and only
     * an exhaustive fixture says which absences. Every shape that carries a reason of its own stands
     * the arm down, and the plain object is what is left.
     */
    @Test
    void theCatchAllStandsDownForEveryShapeThatCarriesItsOwnReason() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");
            seedRootOperation(dsl, GRAPH, "QUERY", "Rooted");
            seedTableBinding(dsl, GRAPH, "Tabled", "film");
            seedError(dsl, GRAPH, "Faulted");
            seedDeclaredType(dsl, GRAPH, "Serviced", "OBJECT");
            seedField(dsl, GRAPH, "Rooted", "s", "Serviced", false);
            seedService(dsl, GRAPH, "Rooted", "s", SERVICE_CLASS, "get");
            seedDeclaredType(dsl, GRAPH, "Externalled", "OBJECT");
            seedField(dsl, GRAPH, "Rooted", "e", "Externalled", false);
            seedExternalField(dsl, GRAPH, "Rooted", "e", EXTERNAL_CLASS, null);
            seedDeclaredType(dsl, GRAPH, "Mutated", "OBJECT");
            seedField(dsl, GRAPH, "Rooted", "m", "Mutated", false);
            seedMutation(dsl, GRAPH, "Rooted", "m", "INSERT");

            assertThat(typesWithReason(dsl, "NESTING_TARGET")).containsExactly("Plain");
        });
    }

    // ===== The type-grain rules =====

    /**
     * One arm at a time at the type grain. Two of the seven are reuses rather than restatements,
     * the machinery reading coming from the field-grain exemption and the payload reading from the
     * field-grain demand, so a fixture reaching them reaches those arms through this one.
     */
    @Test
    void everyTypeDemandArmAnswersWithItsOwnRule() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedError(dsl, GRAPH, "Failure");
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "Searchable", "UNION");
            seedConnectionShape(dsl, "Query", "films", "FilmConnection", "FilmEdge", "Film");
            seedProducedPayload(dsl, "saved", "SavePayload");
            seedMutation(dsl, GRAPH, "Query", "saved", "INSERT");

            assertThat(typeDemand(dsl, "Query")).containsExactly("ROOT_OPERATION");
            assertThat(typeDemand(dsl, "Film")).containsExactly("TABLE_TYPE");
            assertThat(typeDemand(dsl, "Failure")).containsExactly("ERROR_TYPE");
            assertThat(typeDemand(dsl, "Media")).containsExactly("INTERFACE_TYPE");
            assertThat(typeDemand(dsl, "Searchable")).containsExactly("UNION_TYPE");
            assertThat(typeDemand(dsl, "FilmConnection")).containsExactly("CONNECTION_MACHINERY");
            assertThat(typeDemand(dsl, "SavePayload")).containsExactly("PRODUCER_PAYLOAD");
        });
    }

    /**
     * The interface and union arms are kind-wide less the underscore mask, and the mask is where the
     * two grains deliberately differ: the field grain exempts every interface whatever its name,
     * because none of its fields ever classify, while the type grain demands a verdict for the type
     * itself and so has the short-circuit to apply.
     */
    @Test
    void theInterfaceAndUnionArmsAreKindWideLessTheUnderscoreMask() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "_Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "Searchable", "UNION");
            seedDeclaredType(dsl, GRAPH, "_Searchable", "UNION");

            assertThat(typeDemand(dsl, "Media")).containsExactly("INTERFACE_TYPE");
            assertThat(typeDemand(dsl, "_Media")).isEmpty();
            assertThat(typeDemand(dsl, "Searchable")).containsExactly("UNION_TYPE");
            assertThat(typeDemand(dsl, "_Searchable")).isEmpty();
            assertThat(fieldExemption(dsl, "_Media"))
                .as("the field grain has no mask to apply, its interface arm being kind-wide")
                .containsExactly("INTERFACE_TYPE");
        });
    }

    /**
     * The type grain's two exemptions. The underscore arm has no kind gate here, unlike its
     * field-grain namesake, and the leaf-kind arm carries a bound the migration will retire arm by
     * arm rather than a rule about those kinds. They are unmasked against each other like every
     * other pair, which is what an underscore-named input object shows.
     */
    @Test
    void everyTypeExemptionArmAnswersWithItsOwnReason() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "_Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "_Hidden", "OBJECT");
            seedType(dsl, GRAPH, "DateTime", "SCALAR");
            seedDeclaredType(dsl, GRAPH, "Rating", "ENUM");
            seedDeclaredType(dsl, GRAPH, "FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "_FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");

            assertThat(typeExemption(dsl, "_Media")).containsExactly("UNDERSCORE_TYPE");
            assertThat(typeExemption(dsl, "_Hidden")).containsExactly("UNDERSCORE_TYPE");
            assertThat(typeExemption(dsl, "DateTime")).containsExactly("LEAF_KIND_DEFERRED");
            assertThat(typeExemption(dsl, "Rating")).containsExactly("LEAF_KIND_DEFERRED");
            assertThat(typeExemption(dsl, "FilmRef")).containsExactly("LEAF_KIND_DEFERRED");
            assertThat(typeExemption(dsl, "_FilmRef"))
                .containsExactlyInAnyOrder("UNDERSCORE_TYPE", "LEAF_KIND_DEFERRED");
            assertThat(typeExemption(dsl, "Plain")).isEmpty();
        });
    }

    // ===== The reductions =====

    /**
     * The field reduction's own gates, which the rule relations do not have. Only a coordinate whose
     * parent is a member of the classification domain resolves, and only where that parent is of a
     * kind that bears fields; input coordinates resolve exempt here rather than falling outside.
     *
     * <p>The domain is stated as rows, so the type left out of it is an arrangement a captured
     * schema would not have produced, which is what separates that gate from the parent's own
     * reading. The kind gate needs a parent of some other kind that a rule relation nonetheless
     * answers for, and the machinery arm supplies one: its recognition puts no kind gate on the
     * type carrying the {@code edges} field, so a union shaped that way carries an exemption
     * reading and is kept out of the reduction by the kind gate alone.
     */
    @Test
    void onlyDomainMembersOfAFieldBearingKindResolve() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedField(dsl, GRAPH, "Query", "title");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "title");
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedField(dsl, GRAPH, "Media", "title");
            seedDeclaredType(dsl, GRAPH, "FilmRef", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "FilmRef", "id");
            seedDeclaredType(dsl, GRAPH, "Rating", "ENUM");
            seedField(dsl, GRAPH, "Rating", "label");
            seedDeclaredType(dsl, GRAPH, "Searchable", "UNION");
            seedConnectionShape(dsl, "Query", "search", "Searchable", "SearchEdge", "Film");

            domainOf(dsl, "Query", "Media", "FilmRef", "Rating", "Searchable");

            assertThat(resolvedField(dsl, "Query", "title")).isEqualTo("DEMANDED:ROOT_OPERATION");
            assertThat(resolvedField(dsl, "Media", "title")).isEqualTo("EXEMPT:INTERFACE_TYPE");
            assertThat(resolvedField(dsl, "FilmRef", "id")).isEqualTo("EXEMPT:INPUT_TYPE");
            assertThat(resolvedField(dsl, "Film", "title"))
                .as("a parent outside the domain resolves nowhere, whatever its rules say")
                .isNull();
            assertThat(resolvedField(dsl, "Rating", "label"))
                .as("a field hung off a leaf kind is not a coordinate the reduction covers")
                .isNull();
            assertThat(fieldExemption(dsl, "Searchable"))
                .as("the machinery arm does answer for it, so only the kind gate keeps it out")
                .containsExactly("CONNECTION_MACHINERY");
            assertThat(resolvedField(dsl, "Searchable", "edges")).isNull();
        });
    }

    /**
     * Precedence, which is the whole of what the reduction adds over the rules: demand beats
     * exemption where both carry the parent, and within a side the first arm in the declared order
     * names the row. Every assertion is a type two arms both answer, since a type only one arm
     * answers says nothing about the order.
     */
    @Test
    void demandBeatsExemptionAndTheFirstDeclaredArmWinsWithinASide() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedTableBinding(dsl, GRAPH, "Query", "film");
            seedConnectionShape(dsl, "Query", "films", "FilmConnection", "FilmEdge", "Film");
            seedTableBinding(dsl, GRAPH, "FilmConnection", "film");
            seedField(dsl, GRAPH, "FilmEdge", "cursor");
            seedField(dsl, GRAPH, "Film", "title");
            seedTableBinding(dsl, GRAPH, "Tabled", "film");
            seedError(dsl, GRAPH, "Tabled");
            seedField(dsl, GRAPH, "Tabled", "code");
            seedError(dsl, GRAPH, "Failure");
            seedField(dsl, GRAPH, "Query", "failing", "Failure", false);
            seedMutation(dsl, GRAPH, "Query", "failing", "INSERT");
            seedField(dsl, GRAPH, "Failure", "message");
            seedField(dsl, GRAPH, "Query", "hidden", "_Conn", false);
            seedField(dsl, GRAPH, "_Conn", "edges", "_Edge", true);
            seedField(dsl, GRAPH, "_Edge", "node", "Film", false);
            seedField(dsl, GRAPH, "_Conn", "count");
            domainOf(dsl, "Query", "FilmConnection", "FilmEdge", "Film", "Tabled", "Failure", "_Conn");

            assertThat(resolvedField(dsl, "Query", "films"))
                .as("the root binding beats the table reading")
                .isEqualTo("DEMANDED:ROOT_OPERATION");
            assertThat(resolvedField(dsl, "Tabled", "code"))
                .as("the table reading beats the error reading")
                .isEqualTo("DEMANDED:TABLE_TYPE");
            assertThat(resolvedField(dsl, "Failure", "message"))
                .as("the error reading beats the payload reading")
                .isEqualTo("DEMANDED:ERROR_TYPE");
            assertThat(resolvedField(dsl, "FilmConnection", "edges"))
                .as("demand beats exemption, so a table shaped like machinery classifies")
                .isEqualTo("DEMANDED:TABLE_TYPE");
            assertThat(resolvedField(dsl, "_Conn", "count"))
                .as("the underscore reading beats the machinery reading")
                .isEqualTo("EXEMPT:UNDERSCORE_TYPE");
            assertThat(resolvedField(dsl, "FilmEdge", "cursor"))
                .as("the machinery reading beats the catch-all")
                .isEqualTo("EXEMPT:CONNECTION_MACHINERY");
            assertThat(resolvedField(dsl, "Film", "title"))
                .as("and the catch-all is what a plain object resolves through")
                .isEqualTo("EXEMPT:NESTING_TARGET");
        });
    }

    /**
     * The type reduction on the same terms, plus the population that separates it from the field
     * reduction: a domain member neither side answers has no row at all. Over field-bearing kinds
     * the two field-grain relations happen to be total, the catch-all complementing the demand arms
     * over plain objects, so this grain is where the absence is stateable, and it is the residue the
     * embedding walk still decides.
     */
    @Test
    void theTypeReductionMirrorsTheFieldReductionAndLeavesItsResidueAbsent() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedTableBinding(dsl, GRAPH, "Query", "film");
            seedRootOperation(dsl, GRAPH, "MUTATION", "_Root");
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedDeclaredType(dsl, GRAPH, "_FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "FilmRef", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");
            seedTableBinding(dsl, GRAPH, "Outside", "film");
            domainOf(dsl, "Query", "_Root", "Media", "_FilmRef", "FilmRef", "Plain");

            assertThat(allResolvedTypes(dsl)).containsExactlyInAnyOrder(
                "Query DEMANDED ROOT_OPERATION",
                "_Root DEMANDED ROOT_OPERATION",
                "Media DEMANDED INTERFACE_TYPE",
                "_FilmRef EXEMPT UNDERSCORE_TYPE",
                "FilmRef EXEMPT LEAF_KIND_DEFERRED");
        });
    }

    // ===== The partition =====

    /**
     * Every arm's own graph key, in the one arrangement that can state it: two graphs carrying the
     * same type names and disagreeing about what those types are. A join blind to the partition
     * would read one graph's table binding as the other's, and the two structural recognitions
     * (the machinery arm's marker probe and the catch-all's anti-joins) would leak the same way.
     */
    @Test
    void aGraphDemandsNothingOnItsSiblingsBehalf() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "title");
            seedTypeDomain(dsl, GRAPH, "Film");
            seedDeclaredType(dsl, OTHER_GRAPH, "Film", "OBJECT");
            seedField(dsl, OTHER_GRAPH, "Film", "title");
            seedTypeDomain(dsl, OTHER_GRAPH, "Film");
            seedDeclaredType(dsl, GRAPH, "PageInfo", "OBJECT");
            seedDeclaredType(dsl, OTHER_GRAPH, "PageInfo", "OBJECT");
            seedConnection(dsl, GRAPH, "Film", "title");

            assertThat(allResolvedFields(dsl)).containsExactlyInAnyOrder(
                GRAPH + " Film.title DEMANDED TABLE_TYPE",
                OTHER_GRAPH + " Film.title EXEMPT NESTING_TARGET");
            assertThat(allMachinery(dsl)).containsExactly(GRAPH + " PageInfo");
        });
    }

    // ===== Fixtures =====

    /**
     * A payload type produced from a field of {@code Query}, for the arm that reads what a field's
     * producer delivers. The producing directive is the case's own to state, this being what the
     * three sources have in common and not what tells them apart.
     */
    private static void seedProducedPayload(DSLContext dsl, String fieldName, String payloadName) {
        seedDeclaredType(dsl, GRAPH, payloadName, "OBJECT");
        seedField(dsl, GRAPH, "Query", fieldName, payloadName, false);
    }

    /**
     * The shape the machinery arm recognizes, whole: a carrier field naming the connection type, an
     * {@code edges} field naming an object, and a {@code node} field on that object.
     */
    private static void seedConnectionShape(DSLContext dsl, String carrierType, String carrierField,
                                            String connectionType, String edgeType, String nodeType) {
        seedDeclaredType(dsl, GRAPH, nodeType, "OBJECT");
        seedField(dsl, GRAPH, carrierType, carrierField, connectionType, false);
        seedField(dsl, GRAPH, connectionType, "edges", edgeType, true);
        seedField(dsl, GRAPH, edgeType, "node", nodeType, false);
    }

    /** Domain membership for each named type, which is what the reductions gate on. */
    private static void domainOf(DSLContext dsl, String... typeNames) {
        for (String typeName : typeNames) {
            seedTypeDomain(dsl, GRAPH, typeName);
        }
    }

    // ===== Readings =====
    // Each derives first. These rules read the emitted element anchors, which are tables a
    // derivation fills rather than the union views they used to read, so a case that has only
    // seeded has nothing to read yet.

    private static List<String> fieldDemand(DSLContext dsl, String typeName) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_DEMAND_RULE.RULE)
            .from(INTENT_FIELD_DEMAND_RULE)
            .where(INTENT_FIELD_DEMAND_RULE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_DEMAND_RULE.TYPE_NAME.eq(typeName))
            .fetch(Record1::value1);
    }

    private static List<String> fieldExemption(DSLContext dsl, String typeName) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_EXEMPTION_RULE.REASON)
            .from(INTENT_FIELD_EXEMPTION_RULE)
            .where(INTENT_FIELD_EXEMPTION_RULE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_EXEMPTION_RULE.TYPE_NAME.eq(typeName))
            .fetch(Record1::value1);
    }

    private static List<String> typeDemand(DSLContext dsl, String typeName) {
        derive(dsl);
        return dsl.select(INTENT_TYPE_DEMAND.RULE)
            .from(INTENT_TYPE_DEMAND)
            .where(INTENT_TYPE_DEMAND.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_TYPE_DEMAND.TYPE_NAME.eq(typeName))
            .fetch(Record1::value1);
    }

    private static List<String> typeExemption(DSLContext dsl, String typeName) {
        derive(dsl);
        return dsl.select(INTENT_TYPE_EXEMPTION.REASON)
            .from(INTENT_TYPE_EXEMPTION)
            .where(INTENT_TYPE_EXEMPTION.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_TYPE_EXEMPTION.TYPE_NAME.eq(typeName))
            .fetch(Record1::value1);
    }

    /** Which types an exemption arm named, for the arms whose subject is the population itself. */
    private static List<String> typesWithReason(DSLContext dsl, String reason) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_EXEMPTION_RULE.TYPE_NAME)
            .from(INTENT_FIELD_EXEMPTION_RULE)
            .where(INTENT_FIELD_EXEMPTION_RULE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_EXEMPTION_RULE.REASON.eq(reason))
            .fetch(Record1::value1);
    }

    /** The same reading on the demand side. */
    private static List<String> payloadsWithRule(DSLContext dsl, String rule) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_DEMAND_RULE.TYPE_NAME)
            .from(INTENT_FIELD_DEMAND_RULE)
            .where(INTENT_FIELD_DEMAND_RULE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_DEMAND_RULE.RULE.eq(rule))
            .fetch(Record1::value1);
    }

    private static String resolvedField(DSLContext dsl, String typeName, String fieldName) {
        derive(dsl);
        return dsl.select(INTENT_RESOLVED_FIELD_DEMAND.VERDICT, INTENT_RESOLVED_FIELD_DEMAND.RULE)
            .from(INTENT_RESOLVED_FIELD_DEMAND)
            .where(INTENT_RESOLVED_FIELD_DEMAND.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_RESOLVED_FIELD_DEMAND.TYPE_NAME.eq(typeName))
            .and(INTENT_RESOLVED_FIELD_DEMAND.FIELD_NAME.eq(fieldName))
            .fetchOne(r -> r.value1() + ":" + r.value2());
    }

    private static List<String> allResolvedTypes(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_RESOLVED_TYPE_DEMAND.TYPE_NAME, INTENT_RESOLVED_TYPE_DEMAND.VERDICT,
                INTENT_RESOLVED_TYPE_DEMAND.RULE)
            .from(INTENT_RESOLVED_TYPE_DEMAND)
            .where(INTENT_RESOLVED_TYPE_DEMAND.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3());
    }

    /** Every graph's rows, for the partition cases, the graph name being the assertion itself. */
    private static List<String> allResolvedFields(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_RESOLVED_FIELD_DEMAND.GRAPH_NAME,
                INTENT_RESOLVED_FIELD_DEMAND.TYPE_NAME, INTENT_RESOLVED_FIELD_DEMAND.FIELD_NAME,
                INTENT_RESOLVED_FIELD_DEMAND.VERDICT, INTENT_RESOLVED_FIELD_DEMAND.RULE)
            .from(INTENT_RESOLVED_FIELD_DEMAND)
            .fetch(r -> r.value1() + " " + r.value2() + "." + r.value3()
                + " " + r.value4() + " " + r.value5());
    }

    private static List<String> allMachinery(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_EXEMPTION_RULE.GRAPH_NAME, INTENT_FIELD_EXEMPTION_RULE.TYPE_NAME)
            .from(INTENT_FIELD_EXEMPTION_RULE)
            .where(INTENT_FIELD_EXEMPTION_RULE.REASON.eq("CONNECTION_MACHINERY"))
            .fetch(r -> r.value1() + " " + r.value2());
    }
}
