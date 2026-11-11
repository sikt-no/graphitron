package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ServiceKeySource;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static org.jooq.impl.DSL.selectOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agreement anchor for how a producer under a held object takes its keys: the store's
 * {@code graphitron_occurrence_producer_lift}, derived relationally per occurrence from the parent's
 * class, the path's elements and the classpath, and the classifier's {@link KeyLift} on each batched
 * leaf, decided by reflection while it builds the field. Over the whole sakila example schema, every
 * field whose parent is a result type carries the same read, arity and method on both sides, under
 * every occurrence of it.
 *
 * <p>{@link KeyLift.FkColumns} and {@link KeyLift.ProducedRecords} both read the keys as columns
 * off the object, the store's {@code COLUMNS}; which columns is the path's, so the comparison does
 * not ask. A polymorphic field under a table-row parent carries a lift too, but its parent holds a
 * row, not an object; a service under one is filled off that row, which the classifier states as
 * {@link ServiceKeySource.FromTableRow}, so the comparison leaves both out.
 */
@PipelineTier
class ProducerFillPinTest {

    @TempDir
    Path tmp;

    @Test
    void theStoreTakesKeysAsTheClassifierLiftsThem() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        var walked = walked(sdl);
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, sdl,
                CatalogInput.of(testContext()), TestSchemaHelper.sakilaExampleClasspath())) {
            var dsl = store.dsl();
            var k = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
            var a = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR;
            var o = GRAPHITRON_OCCURRENCE;
            var f = GRAPHITRON_FIELD;
            var tt = GRAPHITRON_TABLETYPE;
            var stored = new TreeMap<String, String>();
            // An accessor key names the member it reads, its code_read_slot row. A field reached
            // under several parents is filled once per occurrence, the same way each time.
            dsl.select(f.TYPE_NAME, f.FIELD_NAME, k.READ, k.ARITY, a.SLOT_METHOD_NAME)
                .from(k)
                .join(o).on(o.GRAPH_NAME.eq(k.GRAPH_NAME), o.COORDINATE.eq(k.COORDINATE))
                .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .leftJoin(a).on(a.GRAPH_NAME.eq(k.GRAPH_NAME), a.COORDINATE.eq(k.COORDINATE))
                .whereNotExists(selectOne().from(tt)
                    .where(tt.GRAPH_NAME.eq(f.GRAPH_NAME), tt.TYPE_NAME.eq(f.TYPE_NAME)))
                .fetch()
                .forEach(r -> stored.merge(r.value1() + "." + r.value2(),
                    r.value3() + " " + r.value4() + (r.value5() == null ? "" : " " + r.value5()),
                    (x, y) -> x.equals(y) ? x : x + " | " + y));
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
            assertThat(onlyWalked.keySet()).as("no field the classifier lifts keys at goes unfilled in"
                + " the store").isEmpty();
        }
    }


    private static Map<String, String> walked(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var out = new TreeMap<String, String>();
        for (var f : schema.fields().values()) {
            if (!(f instanceof ChildField cf)
                || !(schema.types().get(cf.parentTypeName()) instanceof GraphitronType.ResultType)) {
                continue;
            }
            // A service under a held object keys its batch by records of the table its source
            // parameter collects, off the parent itself or through the one member returning one.
            var serviceKey = switch (cf) {
                case ChildField.ServiceTableField s -> s.keySource();
                case ChildField.ServiceRecordField s -> s.keySource();
                default -> null;
            };
            if (serviceKey != null) {
                switch (serviceKey) {
                    case ServiceKeySource.FromHeldRecord x -> out.put(cf.parentTypeName() + "." + cf.name(), "COLUMNS ONE");
                    case ServiceKeySource.FromAccessor x -> out.put(cf.parentTypeName() + "." + cf.name(),
                        "ACCESSOR ONE " + x.accessor().methodName());
                    case ServiceKeySource.FromTableRow x -> { }
                }
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
                case KeyLift.Accessor x -> "ACCESSOR " + x.arity() + " " + x.accessor().methodName();
            });
        }
        return out;
    }
}
