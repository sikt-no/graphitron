package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.model.test.CorpusDocuments;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.RoutineRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_MATCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for which input element a {@code @routine} binding spends. Two
 * independent derivations answer that question and this pins them equal: the store's
 * {@code graphitron_argmapping_match.bound_path}, derived relationally from the captured candidate
 * tree, and the classification walk's own answer, resolved in
 * {@code RoutineDirectiveResolver.bindArgs} against the field's argument types and carried on the
 * {@code RoutineRef.ArgBinding} as a coordinate.
 *
 * <p>Both implement the same two readings in the same order, which is the whole reason the
 * agreement is worth pinning rather than assuming: the whole written path where all of it resolves
 * as input elements, and that path less its last name where one name is left over, the leftover
 * being the key column a {@code @nodeId} element projects. A walk that implemented only the first
 * reading would spend the wrong coordinate on every projected binding and would not fail any
 * verdict test, because the coordinate it spends and the coordinate the read surface withholds
 * would be the same wrong one.
 *
 * <p>The comparison is keyed on the written path, so only entries an author actually wrote take
 * part: a parameter identity-bound to a same-named argument has a spent coordinate on the walk side
 * and no {@code argMapping} entry at all on the store side, and reading that absence as a
 * disagreement would be reading it as a fact about spending rather than about what was written.
 *
 * <p>The corpus sweep carries the population and the targeted fixtures carry the readings: every
 * corpus {@code @routine} binds a flat argument, so the sweep alone would pin only the first
 * reading and pass on a walk that had never heard of the second.
 */
@PipelineTier
class RoutineSpentInputShadowTest {

    @TempDir
    Path tmp;

    // ===== The corpus sweep =====

    /**
     * Per corpus example, captured as its own graph in one store: every written {@code @routine}
     * entry whose path resolves has the same landing on both sides. The floor keeps the sweep from
     * passing on an accidentally empty population.
     */
    @Test
    void spentCoordinatesAgreeWithBoundPathOverTheCorpus() {
        int compared = 0;
        try (var store = captureCorpus()) {
            for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
                var derived = storeLandings(store.dsl(), example.id());
                if (derived.isEmpty()) {
                    continue;
                }
                var walked = walkLandings(ClassifiedHarness.classify(example.sdl()).schema());
                assertThat(walked)
                    .as("walk-side spent coordinates vs bound_path (%s)", example.id())
                    .containsAllEntriesOf(derived);
                compared += derived.size();
            }
        }
        assertThat(compared)
            .as("the corpus exercises a non-trivial routine argMapping population")
            .isGreaterThan(2);
    }

    // ===== Targeted fixtures: the two readings =====

    /**
     * The second reading, which the corpus does not carry. {@code filter.actorId.actor_id} resolves
     * as input elements only as far as {@code filter.actorId}, the trailing name being the key
     * column the {@code @nodeId} element projects, and both sides land there. Beside it, a binding
     * whose whole path resolves lands the first reading, so one fixture shows the two apart.
     */
    @Test
    void aProjectedBindingLandsOnTheElementItProjectsFrom() {
        String sdl = """
            type Actor implements Node @table(name: "actor") @node(keyColumns: ["actor_id"]) { id: ID! }
            type ActorFilm @table(name: "films_for_actor") {
              filmId: Int @field(name: "film_id")
              title:  String
            }
            input ActorFilmFilter {
              actorId:   ID! @nodeId(typeName: "Actor")
              minLength: Int
            }
            type Query {
              actorFilms(filter: ActorFilmFilter!): [ActorFilm!]
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: filter.actorId.actor_id, pMinLength: filter.minLength")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            """;
        var walked = walkLandings(TestSchemaHelper.buildSchema(sdl));
        assertThat(walked).containsOnly(
            Map.entry("Query.actorFilms|filter.actorId.actor_id", "filter.actorId"),
            Map.entry("Query.actorFilms|filter.minLength", "filter.minLength"));

        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(storeLandings(store.dsl(), CapturedStore.GRAPH))
                .as("the store reaches the same two landings by its own route")
                .isEqualTo(walked);
        }
    }

    /**
     * A binding into a flat argument: the path is one segment, it resolves whole, and the landing
     * is the argument itself. The zero-depth case of the same rule, and the one the corpus covers,
     * pinned here too so the fixture states both arms of the coordinate the walk carries.
     */
    @Test
    void aFlatBindingLandsOnTheArgument() {
        String sdl = """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """;
        var walked = walkLandings(TestSchemaHelper.buildSchema(sdl));
        assertThat(walked).containsOnly(
            Map.entry("Mutation.rentFilm|inventoryId", "inventoryId"),
            Map.entry("Mutation.rentFilm|customerId", "customerId"));

        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(storeLandings(store.dsl(), CapturedStore.GRAPH)).isEqualTo(walked);
        }
    }

    // ===== The two derivations, each rendered to one comparable shape =====

    /**
     * The store's answer: {@code bound_path} per written {@code @routine} entry, keyed by the
     * spelling site and the path the author wrote.
     */
    private static Map<String, String> storeLandings(DSLContext dsl, String graphName) {
        var e = GRAPHITRON_ARGMAPPING_ENTRY;
        var m = GRAPHITRON_ARGMAPPING_MATCH;
        var out = new LinkedHashMap<String, String>();
        dsl.select(e.COORDINATE, e.WRITTEN_PATH, m.BOUND_PATH)
            .from(e)
            .join(m).on(m.GRAPH_NAME.eq(e.GRAPH_NAME)).and(m.SITE.eq(e.SITE))
                .and(m.USE_SITE.eq(e.USE_SITE)).and(m.POSITION.eq(e.POSITION))
            .where(e.GRAPH_NAME.eq(graphName)).and(e.SITE.eq("ROUTINE"))
            .forEach(r -> out.put(r.value1() + "|" + r.value2(), r.value3()));
        return out;
    }

    /**
     * The walk's answer: every {@code RoutineRef.ArgBinding}'s resolved coordinate, rendered back
     * into the dotted spelling {@code bound_path} holds, keyed the same way. Rendering rather than
     * comparing structurally is what a shadow anchor is for: the two derivations are independent
     * and their agreement is the claim, so one of them has to be brought onto the other's terms.
     */
    private static Map<String, String> walkLandings(GraphitronSchema model) {
        var out = new LinkedHashMap<String, String>();
        for (var entry : model.fields().entrySet()) {
            for (RoutineRef routine : routinesUnder(entry.getValue())) {
                for (var binding : routine.argBindings()) {
                    if (!(binding.source() instanceof ParamSource.Arg arg) || binding.spentAt() == null) {
                        continue;
                    }
                    out.put(entry.getKey() + "|" + arg.path().asString(), dotted(binding.spentAt()));
                }
            }
        }
        return out;
    }

    /** A coordinate as {@code bound_path} spells it: the head slot, then one name per step. */
    private static String dotted(NodeIdDecodeCoordinate at) {
        var sb = new StringBuilder(at.rootArgumentName());
        if (at instanceof NodeIdDecodeCoordinate.InputField f) {
            f.descent().forEach(step -> sb.append('.').append(step.fieldName()));
        }
        return sb.toString();
    }

    /**
     * Every {@link RoutineRef} reachable from a classified field, found by walking the record graph
     * rather than by enumerating the arms that carry one. Which leaf holds a routine is not this
     * test's subject, and an enumeration here would quietly stop covering a new arm.
     */
    private static List<RoutineRef> routinesUnder(Object root) {
        var found = new ArrayList<RoutineRef>();
        collect(root, found, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        return found;
    }

    private static void collect(Object value, List<RoutineRef> found, Set<Object> seen) {
        if (value == null || !seen.add(value)) {
            return;
        }
        if (value instanceof RoutineRef routine) {
            found.add(routine);
            return;
        }
        if (value instanceof Iterable<?> items) {
            items.forEach(item -> collect(item, found, seen));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collect(item, found, seen));
            return;
        }
        Class<?> type = value.getClass();
        if (!type.isRecord() || !type.getName().startsWith("no.sikt.graphitron.")) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                collect(component.getAccessor().invoke(value), found, seen);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "record component unreadable: " + type.getName() + "." + component.getName(), e);
            }
        }
    }

    // ===== Store plumbing =====

    /** Every corpus example captured as its own graph into one store. */
    private CapturedStore captureCorpus() {
        var jooq = jooq();
        CapturedStore store = null;
        for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
            store = store == null
                ? CapturedStore.ofCatalog(tmp, example.id(), preluded(example.sdl()), jooq)
                : store.andCatalogGraph(example.id(), preluded(example.sdl()), jooq);
        }
        return store;
    }

    /** SDL as the walk sees it: the corpus prelude plus the Node interface the helper injects. */
    private static String preluded(String sdl) {
        String full = CorpusDocuments.prelude() + "\n" + sdl;
        return full.contains("interface Node") ? full : full + "\ninterface Node { id: ID! }\n";
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
