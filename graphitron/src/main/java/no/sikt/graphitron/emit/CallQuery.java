package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;

/**
 * The call emitter's query: for every service call, the method and what it is called on, what it
 * answers, and under an object the parameter the batch's keys go into. A static method is called on
 * its class; an instance method on an object made with the class's constructor the store resolved,
 * handed the jOOQ context where that constructor takes it.
 */
public final class CallQuery {

    private CallQuery() {}

    /** The descriptor of a constructor taking the jOOQ context, the one argument a holder may take. */
    static final String DSL_CONSTRUCTOR = "(Lorg/jooq/DSLContext;)V";

    /** The parameter the batch's keys go into: its position, and its type as the store spells it. */
    public record Source(int position, String parameterType) {
        public Source {
            Objects.requireNonNull(parameterType, "parameterType");
        }
    }

    /**
     * One call: the producing occurrence; the method's class and name; whether it is static, and if
     * not, the descriptor of the constructor its object is made with; the type it answers, as the
     * store spells it; and under an object, the parameter taking the batch's keys.
     */
    public record Row(String coordinate, String className, String methodName, boolean isStatic,
                      Optional<String> constructor, String resultType, Optional<Source> source) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(resultType, "resultType");
            Objects.requireNonNull(constructor, "constructor");
            Objects.requireNonNull(source, "source");
        }

        /** Whether the object an instance method runs on is made with the jOOQ context. */
        public boolean holderTakesDsl() {
            return constructor.map(DSL_CONSTRUCTOR::equals).orElse(false);
        }
    }

    /** The graph's calls, by coordinate. */
    public static List<Row> read(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var m = CODE_METHOD;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var p = CODE_METHOD_PARAMETER;
        return store.dsl()
            .select(s.COORDINATE, s.METHOD_CLASS_NAME, s.METHOD_NAME, m.IS_STATIC, s.CONSTRUCTOR_DESCRIPTOR,
                m.RESULT_TYPE, c.POSITION, p.PARAMETER_TYPE)
            .from(s)
            .join(m).on(m.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), m.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                m.METHOD_NAME.eq(s.METHOD_NAME), m.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR))
            // Driven from the service producer, so another kind's fills never reach a service call.
            .leftJoin(c).on(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE), c.CALL.eq(s.CALL), c.FILL.eq("SOURCE"))
            .leftJoin(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR), p.POSITION.eq(c.POSITION))
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .orderBy(s.COORDINATE)
            .fetch(r -> new Row(r.value1(), r.value2(), r.value3(), r.value4(), Optional.ofNullable(r.value5()),
                r.value6(), r.value7() == null ? Optional.empty() : Optional.of(new Source(r.value7(), r.value8()))));
    }
}
