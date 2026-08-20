package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Closes {@code meta_materialize}'s authored rows against the observed schema, the shape
 * {@code meta_relation_family} already uses for the family roster.
 *
 * <p>The registry is a claim about relations that have to exist, in kinds that have to match, with
 * a shape that makes {@code INSERT INTO target SELECT * FROM source} a statement whose result is
 * the view's own rows. Every clause of that claim is checked here rather than argued in prose,
 * because the design's whole safety argument is that a registration changes no answer, and a
 * mismatched column list would break it silently in the one direction nobody reads: the target
 * would hold rows, they would simply be the wrong ones.
 *
 * <p>Structural only, over a booted schema with no rows in it. Whether a target's rows actually
 * equal its view's rows is the other half of the claim, and answering it needs a capture rather
 * than a schema, so it sits with the schema gates that already have one.
 */
class MaterializeRegistryGateTest {

    /**
     * The {@code intent_} base tables written by a hand-written derivation rather than by the
     * materializer. Each argues impossibility in its own table comment: no view could state its
     * rule. Enumerated so the gate below can tell a deliberate hand-written derivation from a
     * bespoke materializer someone wrote beside the mechanism instead of inside it.
     */
    private static final Set<String> HAND_WRITTEN = Set.of(
        "intent_type_domain",
        "intent_type_backing_class",
        "intent_input_occurrence_path",
        "intent_input_occurrence_path_step");

    @Test
    @DisplayName("every registered source is a view and every registered target is a table")
    void registeredRelationsExistInTheKindsTheRegistryClaims() {
        withStore(dsl -> {
            var offenders = new ArrayList<String>();
            for (var registration : Materializations.registrations(dsl)) {
                String source = registration.sourceViewName();
                String target = registration.targetTableName();
                if (!"VIEW".equals(kindOf(dsl, source))) {
                    offenders.add(source + " is registered as a source but is "
                        + describe(kindOf(dsl, source)));
                }
                if (!"BASE TABLE".equals(kindOf(dsl, target))) {
                    offenders.add(target + " is registered as a target but is "
                        + describe(kindOf(dsl, target)));
                }
            }
            assertThat(offenders).as("registrations naming a relation of the wrong kind").isEmpty();
        });
    }

    @Test
    @DisplayName("every target's column list matches its source view's, name for name in order")
    void targetsAreShapedLikeTheViewsThatFillThem() {
        withStore(dsl -> {
            var offenders = new ArrayList<String>();
            for (var registration : Materializations.registrations(dsl)) {
                var source = columnsOf(dsl, registration.sourceViewName());
                var target = columnsOf(dsl, registration.targetTableName());
                if (!source.equals(target)) {
                    offenders.add(registration.targetTableName() + " has columns " + target
                        + " but is filled from " + registration.sourceViewName()
                        + ", whose columns are " + source);
                }
            }
            assertThat(offenders)
                .as("targets whose shape would make INSERT .. SELECT * write the wrong columns")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("no registered view reads another registration's target")
    void theRegistryNeedsNoOrderingYet() {
        withStore(dsl -> {
            var registrations = Materializations.registrations(dsl);
            var targets = registrations.stream()
                .map(Materializations.Registration::targetTableName)
                .collect(java.util.stream.Collectors.toSet());
            var offenders = new ArrayList<String>();
            for (var registration : registrations) {
                for (String read : closureOf(dsl, registration.sourceViewName())) {
                    if (targets.contains(read)) {
                        offenders.add(registration.sourceViewName() + " reads " + read
                            + ", which is itself a registered target, so the two refreshes are"
                            + " ordered and the registry records no order");
                    }
                }
            }
            assertThat(offenders).as("registrations that need an ordering the registry cannot state")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("every materialized intent_ relation is a registration or a stated hand-written one")
    void nothingMaterializesOutsideTheMechanism() {
        withStore(dsl -> {
            var targets = Materializations.registrations(dsl).stream()
                .map(Materializations.Registration::targetTableName)
                .collect(java.util.stream.Collectors.toSet());
            var unaccounted = baseTables(dsl).stream()
                .filter(relation -> relation.startsWith("intent_"))
                .filter(relation -> !targets.contains(relation))
                .filter(relation -> !HAND_WRITTEN.contains(relation))
                .toList();
            assertThat(unaccounted)
                .as("stored intent_ relations that are neither registered nor a stated hand-written"
                    + " derivation; register it rather than writing a second bespoke writer")
                .isEmpty();
            assertThat(HAND_WRITTEN).allSatisfy(relation ->
                assertThat(baseTables(dsl)).as("hand-written derivation the DDL no longer declares")
                    .contains(relation));
        });
    }

    // ===== Reading the observed schema =====

    private static void withStore(java.util.function.Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    private static String kindOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .fetchOne(0, String.class);
    }

    private static String describe(String kind) {
        return kind == null ? "not declared at all" : "a " + kind.toLowerCase(Locale.ROOT);
    }

    private static List<String> columnsOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class);
    }

    private static List<String> baseTables(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_TYPE"), String.class).eq("BASE TABLE"))
            .fetch(0, String.class).stream()
            .map(relation -> relation.toLowerCase(Locale.ROOT))
            .toList();
    }

    /**
     * Every relation the view reads, transitively, read out of the engine's own view definitions.
     * A textual scan of the stored SQL rather than a dependency catalog, because H2 publishes no
     * such catalog; the names it looks for are the observed relation names, so a false positive
     * would need a relation whose name appears in a view body meaning something else.
     */
    private static Set<String> closureOf(DSLContext dsl, String viewName) {
        var relations = dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch(0, String.class).stream()
            .map(relation -> relation.toLowerCase(Locale.ROOT))
            .toList();
        var reached = new LinkedHashSet<String>();
        var frontier = new ArrayList<String>();
        frontier.add(viewName.toLowerCase(Locale.ROOT));
        while (!frontier.isEmpty()) {
            String current = frontier.removeLast();
            String body = viewBody(dsl, current);
            if (body == null) {
                continue;
            }
            String stripped = body.toLowerCase(Locale.ROOT);
            for (String candidate : relations) {
                if (candidate.equals(current) || reached.contains(candidate)) {
                    continue;
                }
                if (Pattern.compile("(?<![\\w.])" + Pattern.quote(candidate) + "(?![\\w])")
                        .matcher(stripped).find()) {
                    reached.add(candidate);
                    frontier.add(candidate);
                }
            }
        }
        return reached;
    }

    private static String viewBody(DSLContext dsl, String relationName) {
        return dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .fetchOne(0, String.class);
    }

    private static String fold(String relationName) {
        return relationName.toUpperCase(Locale.ROOT);
    }
}
