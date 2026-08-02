package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMember.Kind;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keystone's membership-agreement pin: over every classified output coordinate in the flat
 * index (the corpus plus the per-kind fixtures below), the minted trigger-fact production
 * ({@link OperationMemberRelation}, read through the schema view) must equal the leaf-derived
 * projection ({@link OperationMembers#membersOf}), compared through a canonical member
 * rendering (a {@link RecordComponent} walk with a justified exclusion set for minted alias
 * addresses, never an inclusion list and never bare deep equality).
 *
 * <p>What this pin independently tests is <em>membership production</em>: the minted side
 * decides membership from trigger slots and shape facts, the projected side from the 51-leaf
 * class switch. Payloads are shared leaf-carried resolutions at this slice (the additive
 * window's sanctioned edge), so their equality is by construction; the kinds whose membership
 * predicates also share inputs on both sides (SELECT off the target fact, JOIN off the
 * reference fact, REENTRY off the shape-and-record facts) are pinned for regression, not
 * independence, until the delivery fact and the walked reference home separate them.
 *
 * <p>The per-kind floors keep the agreement non-vacuous where the corpus is thin (the corpus
 * observes CONDITION once and PAGINATE twice); the fixtures below raise exactly those columns.
 * The slot theorems and the reentry-launcher agreement document the structural implications
 * between the walked slots, the member view and the back-half launcher relation.
 */
@PipelineTier
class OperationMemberMintPinTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    /**
     * Component names excluded from the canonical rendering: minted alias addresses, whose
     * generated names are mint-order-dependent and never value-equal across independent
     * producers. Everything else a member record carries participates.
     */
    private static final Set<String> MINTED_ADDRESS_COMPONENTS = Set.of("alias", "aliasName");

    /** The per-kind coverage fixtures raising the corpus's thin columns. */
    private static List<String> coverageFixtures() {
        return List.of(
            // Condition coverage: field-level, authored-on-lookup, participant-expanded union,
            // inline child, split child, nested-inside-nesting-type child.
            """
            type Language @table(name: "language") { name: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type FilmMeta {
                languages(name: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)):
                    [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                meta: FilmMeta
                language(name: String @field(name: "name")): Language
                    @reference(path: [{key: "film_language_id_fkey"}])
                splitLanguages(name: String @field(name: "name")): [Language!]! @splitQuery
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
                films(title: String @field(name: "TITLE")): [Film!]!
                unfiltered: [Language!]!
                languagesByKey(
                    language_id: [Int] @lookupKey @field(name: "language_id"),
                    name: String @field(name: "name")
                        @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)
                ): [Language!]!
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """.formatted(STUB, STUB),
            // Ordering and pagination coverage: @orderBy argument, @defaultOrder, authored
            // pagination args, the connection directive, and a split child connection.
            """
            enum ActorOrderField { NAME @order(primaryKey: true) }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { lastName: String @field(name: "last_name") }
            type Film @table(name: "film") {
                title: String
                actors(first: Int, after: String): [Actor!]! @splitQuery @defaultOrder(primaryKey: true)
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query {
                actors(order: ActorOrder @orderBy, first: Int, after: String): [Actor!]!
                paged: [Actor!]! @asConnection @defaultOrder(primaryKey: true)
                films: [Film!]!
            }
            """);
    }

    @Test
    void mintedMembersEqualTheLeafProjectionOverTheCorpusAndFixtures() {
        var observedKinds = new EnumMap<Kind, Integer>(Kind.class);
        int coordinates = 0;

        var schemas = new LinkedHashMap<String, GraphitronSchema>();
        for (var example : ClassifiedCorpus.examples()) {
            schemas.put(example.id(), ClassifiedHarness.classify(example.sdl()).schema());
        }
        int fixture = 0;
        for (var sdl : coverageFixtures()) {
            schemas.put("coverage-fixture-" + (++fixture), TestSchemaHelper.buildSchema(sdl));
        }

        for (var entry : schemas.entrySet()) {
            var schema = entry.getValue();
            for (var leaf : flatOutputLeaves(schema)) {
                coordinates++;
                var coord = FieldCoordinates.coordinates(leaf.parentTypeName(), leaf.name());
                List<OperationMember> minted = schema.operationMembersOf(coord);
                List<OperationMember> projected = OperationMembers.membersOf(leaf);
                assertThat(minted.stream().map(OperationMemberMintPinTest::canonical).toList())
                    .as("minted vs projected members at %s.%s (%s in %s)",
                        leaf.parentTypeName(), leaf.name(), leaf.getClass().getSimpleName(),
                        entry.getKey())
                    .isEqualTo(projected.stream().map(OperationMemberMintPinTest::canonical).toList());
                minted.forEach(m -> observedKinds.merge(m.kind(), 1, Integer::sum));
            }
        }

        assertThat(coordinates).as("the agreement scan must not be vacuous").isGreaterThan(100);
        assertThat(observedKinds)
            .as("per-kind floors: the agreement must be tested where the corpus is thin;"
                + " observed histogram %s", observedKinds)
            .hasEntrySatisfying(Kind.CONDITION, c -> assertThat(c).isGreaterThanOrEqualTo(7))
            .hasEntrySatisfying(Kind.ORDER_BY, c -> assertThat(c).isGreaterThanOrEqualTo(3))
            .hasEntrySatisfying(Kind.PAGINATE, c -> assertThat(c).isGreaterThanOrEqualTo(4))
            .hasEntrySatisfying(Kind.LOOKUP, c -> assertThat(c).isGreaterThanOrEqualTo(2))
            .hasEntrySatisfying(Kind.SERVICE_CALL, c -> assertThat(c).isGreaterThanOrEqualTo(2))
            .hasEntrySatisfying(Kind.WRITE, c -> assertThat(c).isGreaterThanOrEqualTo(3))
            .hasEntrySatisfying(Kind.REENTRY, c -> assertThat(c).isGreaterThanOrEqualTo(3))
            .hasEntrySatisfying(Kind.SELECT, c -> assertThat(c).isGreaterThanOrEqualTo(50))
            .hasEntrySatisfying(Kind.JOIN, c -> assertThat(c).isGreaterThanOrEqualTo(5));
    }

    /**
     * Slot theorems: the structural implications between the walked trigger slots and the
     * minted member view, in both directions over a fixture carrying both populations plus
     * slot-free coordinates. Gate-grade agreement lives in the pin above; these document the
     * slot-to-member direction so an edit that breaks structure nobody intended to break fails
     * loudly.
     */
    @Test
    void slotPresenceAgreesWithMintedMembership() {
        var bundle = TestSchemaHelper.buildBundle("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              rating: String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
              language: Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            input FilmInput { title: String }
            type Query {
              film: Film
              externalFilm: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            type Mutation {
              createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
            }
            """);
        var schema = bundle.model();
        var facts = no.sikt.graphitron.facts.GatheredFacts.gather(
            bundle.assembled(), SchemaReachability::walk);
        Set<String> serviceSlots = facts.service().rows().stream()
            .map(r -> r.parentTypeName() + "." + r.fieldName()).collect(Collectors.toSet());
        Set<String> writeSlots = facts.write().rows().stream()
            .map(r -> r.parentTypeName() + "." + r.fieldName()).collect(Collectors.toSet());

        var serviceMembers = new TreeSet<String>();
        var writeMembers = new TreeSet<String>();
        for (var leaf : flatOutputLeaves(schema)) {
            var label = leaf.parentTypeName() + "." + leaf.name();
            var minted = schema.operationMembersOf(
                FieldCoordinates.coordinates(leaf.parentTypeName(), leaf.name()));
            if (minted.stream().anyMatch(m -> m instanceof OperationMember.ServiceCall)) {
                serviceMembers.add(label);
            }
            if (minted.stream().anyMatch(m -> m instanceof OperationMember.Write w
                    && !(w instanceof OperationMember.Write.RoutineWrite))) {
                writeMembers.add(label);
            }
        }
        assertThat(serviceMembers)
            .as("the serviceCall member population and the @service slot population are one"
                + " fact over classified coordinates")
            .isNotEmpty()
            .isEqualTo(new TreeSet<>(serviceSlots));
        assertThat(writeMembers)
            .as("the DML-verb write member population and the @mutation slot population are one"
                + " fact over classified coordinates")
            .isNotEmpty()
            .isEqualTo(new TreeSet<>(writeSlots));
    }

    /**
     * The reentry-launcher agreement: over the corpus's DML table coordinates, the minted
     * reentry member's coordinate set equals the launcher relation's reentry-sourced row set.
     * The two are produced by independent predicates (the central reentry mint vs the
     * {@code DmlReturnExpression} switch in the launcher producer); this is the enforcer that
     * keeps them one fact.
     */
    @Test
    void reentryMembersMatchTheDmlReentryLauncherRows() {
        var reentryMembers = new TreeSet<String>();
        var reentryLaunchers = new TreeSet<String>();
        int dmlCoordinates = 0;
        var productions = ClassifiedHarness.launcherProductions();
        for (var example : ClassifiedCorpus.examples()) {
            // The corpus deliberately carries shapes the launcher producer rejects (recorded
            // mirror gaps); the harness's guarded production names them, and the agreement's
            // domain is the producible examples.
            if (!(productions.get(example.id())
                    instanceof ClassifiedHarness.LauncherProduction.Produced produced)) {
                continue;
            }
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            var launchers = produced.relation();
            for (var row : launchers.rows()) {
                if (row.source() instanceof LaunchSource.ProjectedReentry
                    || row.source() instanceof LaunchSource.DiscriminatedReentry) {
                    reentryLaunchers.add(example.id() + ":" + row.coordinate().getTypeName()
                        + "." + row.coordinate().getFieldName());
                }
            }
            for (var leaf : flatOutputLeaves(schema)) {
                if (!(leaf instanceof MutationField.DmlTableField)) {
                    continue;
                }
                dmlCoordinates++;
                var minted = schema.operationMembersOf(
                    FieldCoordinates.coordinates(leaf.parentTypeName(), leaf.name()));
                if (minted.stream().anyMatch(m -> m instanceof OperationMember.Reentry)) {
                    reentryMembers.add(example.id() + ":" + leaf.parentTypeName() + "." + leaf.name());
                }
            }
        }
        assertThat(dmlCoordinates).as("the corpus must exercise DML coordinates").isPositive();
        assertThat(reentryMembers)
            .as("the reentry member and the reentry launcher row are one fact at DML grain")
            .isEqualTo(reentryLaunchers);
        assertThat(reentryMembers).as("the agreement must not be vacuous").isNotEmpty();
    }

    /**
     * Every classified output leaf in the flat index: the minted relation's stated domain. A
     * nesting type's fields deliberately mint no coordinate-keyed rows (the record-handoff
     * example reaches one nested coordinate through two source shapes whose reentry truths
     * differ), so they are outside this agreement; their leaf instances keep the leaf-local
     * derivation.
     */
    private static List<OutputField> flatOutputLeaves(GraphitronSchema schema) {
        var leaves = new ArrayList<OutputField>();
        for (var field : schema.fields().values()) {
            if (field instanceof OutputField out) {
                leaves.add(out);
            }
        }
        return leaves;
    }

    /**
     * The canonical member rendering: a recursive record-component walk, refs rendered by
     * value, minted address components excluded (see {@link #MINTED_ADDRESS_COMPONENTS}).
     * Mechanical over whatever components a member arm carries, so a new payload component
     * participates automatically instead of silently escaping the comparison.
     */
    private static String canonical(Object value) {
        return switch (value) {
            case null -> "null";
            case Optional<?> o -> o.map(OperationMemberMintPinTest::canonical).orElse("empty");
            case Collection<?> c -> c.stream()
                .map(OperationMemberMintPinTest::canonical)
                .collect(Collectors.joining(",", "[", "]"));
            case Map<?, ?> m -> m.entrySet().stream()
                .map(e -> canonical(e.getKey()) + "=" + canonical(e.getValue()))
                .sorted()
                .collect(Collectors.joining(",", "{", "}"));
            case Record r -> {
                var sb = new StringBuilder(r.getClass().getSimpleName()).append("{");
                boolean first = true;
                for (RecordComponent component : r.getClass().getRecordComponents()) {
                    if (MINTED_ADDRESS_COMPONENTS.contains(component.getName())) {
                        continue;
                    }
                    Object componentValue;
                    try {
                        componentValue = component.getAccessor().invoke(r);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    if (!first) {
                        sb.append(",");
                    }
                    first = false;
                    sb.append(component.getName()).append("=").append(canonical(componentValue));
                }
                yield sb.append("}").toString();
            }
            default -> value.toString();
        };
    }
}
