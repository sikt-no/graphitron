package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agreement anchor for how a field under a held object takes its keys: the store's
 * {@code graphitron_field_key_row}, derived relationally from the parent's class, the path's
 * elements and the classpath, and the classifier's {@link KeyLift} on each batched leaf, decided by
 * reflection while it builds the field. Over the whole sakila example schema, every field whose
 * parent is a result type carries the same kind, arity and method on both sides.
 *
 * <p>{@link KeyLift.FkColumns} and {@link KeyLift.ProducedRecords} both read the keys as columns
 * off the object, the store's {@code COLUMNS}; which columns is the path's, so the comparison does
 * not ask. A polymorphic field under a table-row parent carries a lift too, but its parent holds a
 * row, not an object, and has no key row.
 */
@PipelineTier
class FieldKeyRowPinTest {

    @TempDir
    Path tmp;

    /**
     * Fields the classifier lifts keys for and the store has no key row at, each for a fact a
     * relation this rule reads does not yet state. Shrink-only: a gap closed upstream fails the
     * last assertion until its line goes.
     */
    private static final Map<String, String> UPSTREAM_GAPS = Map.of(
        "FilmCardWrapper.film", "graphitron_result holds a ROW of inventory for a type an @externalField returns, where the method hands back a FilmCardData object",
        "FilmWithActorsResult.film", "graphitron_result_class reaches no class for an error-carrying payload's data element, so its fields are readers",
        "FilmWithActorsResult.actors", "the same",
        "NestedFilmsPayload.films", "the code family reads no nested class, here NestedFilmsPayloadHolder$Payload, so the accessor is unknown",
        "NestedOccupantCarrier.firstOccupant", "the same, NestedOccupantCarrierHolder$Carrier");

    @Test
    void theStoreTakesKeysAsTheClassifierLiftsThem() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        var walked = walked(sdl);
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, sdl,
                CatalogInput.of(testContext()), sakilaClasspath())) {
            var dsl = store.dsl();
            var k = GRAPHITRON_FIELD_KEY_ROW;
            var m = GRAPHITRON_FIELD_KEY_ROW_METHOD;
            var stored = new TreeMap<String, String>();
            dsl.select(k.TYPE_NAME, k.FIELD_NAME, k.KIND, k.ARITY, m.METHOD_NAME)
                .from(k)
                .leftJoin(m).on(m.GRAPH_NAME.eq(k.GRAPH_NAME), m.TYPE_NAME.eq(k.TYPE_NAME),
                    m.FIELD_NAME.eq(k.FIELD_NAME))
                .fetch()
                .forEach(r -> stored.put(r.value1() + "." + r.value2(),
                    r.value3() + " " + r.value4() + (r.value5() == null ? "" : " " + r.value5())));
            assertThat(walked).as("the classifier lifts keys off a held object at this many fields")
                .hasSizeGreaterThanOrEqualTo(30);
            var onlyWalked = new TreeMap<>(walked);
            onlyWalked.keySet().removeAll(stored.keySet());
            var agreed = new TreeMap<>(walked);
            agreed.keySet().retainAll(stored.keySet());
            assertThat(stored).as("the store takes keys nowhere the classifier does not")
                .containsOnlyKeys(agreed.keySet());
            assertThat(agreed.entrySet()).as("and the same way, field by field, where both do")
                .allMatch(e -> e.getValue().equals(stored.get(e.getKey())));
            assertThat(onlyWalked.keySet()).as("the fields only the classifier lifts are exactly the"
                + " ones upstream relations do not yet state; one going away is a line to delete")
                .containsExactlyInAnyOrderElementsOf(UPSTREAM_GAPS.keySet());
        }
    }

    /**
     * The test classpath with the sakila service and catalog modules read as the sakila example
     * reads them, reactor modules it declares, so the parents' classes and their members are known.
     */
    private static List<ClasspathEntry> sakilaClasspath() {
        return TestSchemaHelper.testClasspath().stream()
            .map(e -> e.path().toString().contains("graphitron-sakila-")
                ? new ClasspathEntry(e.path(), ClasspathEntry.Origin.REACTOR, null, null)
                : e)
            .toList();
    }

    private static Map<String, String> walked(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var out = new TreeMap<String, String>();
        for (var f : schema.fields().values()) {
            if (!(f instanceof ChildField cf)
                || !(schema.types().get(cf.parentTypeName()) instanceof GraphitronType.ResultType)) {
                continue;
            }
            KeyLift lift = switch (cf) {
                case ChildField.BatchedTableField b -> b.sourceShape() == SourceShape.Record ? b.lift() : null;
                case ChildField.InterfaceField i -> i.parentKeyLift();
                case ChildField.UnionField u -> u.parentKeyLift();
                case ChildField.BatchedInterfaceField i -> i.parentKeyLift();
                case ChildField.BatchedUnionField u -> u.parentKeyLift();
                case ChildField.BatchedPivotField p -> p.lift();
                default -> null;
            };
            if (lift == null) {
                continue;
            }
            out.put(cf.parentTypeName() + "." + cf.name(), switch (lift) {
                case KeyLift.FkColumns x -> "COLUMNS ONE";
                case KeyLift.ProducedRecords x -> "COLUMNS " + x.arity();
                case KeyLift.Lifter x -> "SOURCE_ROW ONE " + x.lifter().methodName();
                case KeyLift.Accessor x -> "ACCESSOR " + x.arity() + " " + x.accessor().methodName();
            });
        }
        return out;
    }
}
