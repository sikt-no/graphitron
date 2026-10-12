package no.sikt.graphitron.rewrite;

import org.jooq.Condition;
import org.jooq.Table;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixtures for the carrier a {@code @condition} parameter bound to a GraphQL input-object slot
 * receives: the {@code Map} shapes graphql-java delivers, the beans built from it, and the types
 * that are neither. Every method is a binding-shape fixture only; none is called.
 */
public final class TestConditionCarrierStub {

    private TestConditionCarrierStub() {}

    public static Condition carrierMap(Table<?> table, Map<String, Object> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierWildcardMap(Table<?> table, Map<?, ?> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierObject(Table<?> table, Object input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierMapList(Table<?> table, List<Map<String, Object>> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierMapSet(Table<?> table, Set<Map<String, Object>> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierString(Table<?> table, String input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierRecord(Table<?> table, CarrierRecord input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierRecordList(Table<?> table, List<CarrierRecord> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierRecordSet(Table<?> table, Set<CarrierRecord> input) {
        throw new UnsupportedOperationException();
    }

    /** Input-field-level twin of {@link #carrierRecord}, named after the input field it sits on. */
    public static Condition carrierRecordInner(Table<?> table, CarrierRecord inner) {
        throw new UnsupportedOperationException();
    }

    /** Input-field-level twin of {@link #carrierMap}, named after the input field it sits on. */
    public static Condition carrierMapInner(Table<?> table, Map<String, Object> inner) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierSetterBean(Table<?> table, CarrierSetterBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierNoNoArgConstructor(Table<?> table, CarrierNoNoArgConstructor input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierRecordExtraComponent(Table<?> table, CarrierRecordExtraComponent input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierInterface(Table<?> table, CarrierInterface input) {
        throw new UnsupportedOperationException();
    }

    public static Condition carrierJooqRecord(Table<?> table,
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord input) {
        throw new UnsupportedOperationException();
    }

    /** A record bean mirroring {@code CarrierFilter { title: String }}. */
    public record CarrierRecord(String title) {}

    /** A record whose second component has no SDL field to bind it. */
    public record CarrierRecordExtraComponent(String title, String rating) {}

    /** A setter bean with a nested bean member, mirroring {@code CarrierOuter}. */
    public static class CarrierSetterBean {
        private String title;
        private CarrierNested inner;

        public void setTitle(String title) { this.title = title; }
        public void setInner(CarrierNested inner) { this.inner = inner; }
        public String getTitle() { return title; }
        public CarrierNested getInner() { return inner; }
    }

    /** The nested member of {@link CarrierSetterBean}. */
    public static class CarrierNested {
        private String title;

        public void setTitle(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    /** A class with setters but no public no-arg constructor. */
    public static class CarrierNoNoArgConstructor {
        private String title;

        public CarrierNoNoArgConstructor(String title) { this.title = title; }
        public void setTitle(String title) { this.title = title; }
    }

    /** An interface: nothing to instantiate. */
    public interface CarrierInterface {
        void setTitle(String title);
    }
}
