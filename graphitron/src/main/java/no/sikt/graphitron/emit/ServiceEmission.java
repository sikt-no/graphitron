package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static org.jooq.impl.DSL.selectOne;

/**
 * A {@code @service} fetcher emitted from the store, composed of the parts' emitters: the frame
 * graphql-java calls, the body that fills the parameters and calls the method, and the lift onto
 * the target's rows where the call hands back records of its table. The frame takes the field's own
 * name, the method the wiring registers, and the rest sit beside it in the type's fetchers class.
 *
 * <p>A field is emitted here when the store states every part of it and none of what is not yet
 * emitted from the store applies; otherwise it stays with the classified model's emitters, and
 * {@link #census} says why. Today that is a root service whose answer is a record, a scalar or the
 * records of its target's table, in a build with no tenant column, whose outcome claims no
 * validation, whose arguments decode no node id, build no jOOQ record and reach no input field
 * deeper than the argument itself, and whose target is neither a discriminated interface nor a
 * connection. A field whose call uses no jOOQ context takes none, so it opens no connection.
 */
public final class ServiceEmission {

    /** Why a service is not emitted here yet; the census reads these. */
    public static final String EMITTED = "EMITTED";

    /**
     * The generated classes a store-emitted fetcher calls into, and where the type's fetchers class
     * and a target type's projection unit are.
     */
    public record Runtime(FrameEmitter.Runtime frame, FillEmitter.Runtime fill, ClassName fetchersClass,
                          ClassName selectionOccurrences, Function<String, ClassName> projectionClass) {
        public Runtime {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(fill, "fill");
            Objects.requireNonNull(fetchersClass, "fetchersClass");
            Objects.requireNonNull(selectionOccurrences, "selectionOccurrences");
            Objects.requireNonNull(projectionClass, "projectionClass");
        }
    }

    /**
     * What a field's fetcher is made of: the methods for its fetchers class, the frame first, and
     * the classes to nest in it, the lifted tables' derived source tables, one per table.
     */
    public record Emitted(List<MethodSpec> methods, List<TypeSpec> nestedTypes) {
        public Emitted {
            methods = List.copyOf(methods);
            nestedTypes = List.copyOf(nestedTypes);
        }
    }

    private final Map<String, FrameQuery.Row> frames;
    private final Map<String, CallQuery.Row> calls;
    private final Map<String, FillQuery.Row> fills;
    private final Map<String, Map<Integer, BuildQuery.Result>> builds;
    private final Map<String, LiftQuery.Row> lifts;
    private final Set<String> polymorphic;
    private final Map<String, String> excluded;

    private ServiceEmission(Map<String, FrameQuery.Row> frames, Map<String, CallQuery.Row> calls,
                            Map<String, FillQuery.Row> fills, Map<String, Map<Integer, BuildQuery.Result>> builds,
                            Map<String, LiftQuery.Row> lifts, Set<String> polymorphic, Map<String, String> excluded) {
        this.frames = frames;
        this.calls = calls;
        this.fills = fills;
        this.builds = builds;
        this.lifts = lifts;
        this.polymorphic = polymorphic;
        this.excluded = excluded;
    }

    /** Emits nothing: every service keeps its classified fetcher, for a caller with no store. */
    public static ServiceEmission none() {
        return new ServiceEmission(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
    }

    /** Reads every part's rows for the graph once. */
    public static ServiceEmission read(StoreHandle store) {
        var builds = new HashMap<String, Map<Integer, BuildQuery.Result>>();
        BuildQuery.read(store).forEach((root, result) ->
            builds.computeIfAbsent(root.callCoordinate(), k -> new HashMap<>()).put(root.position(), result));
        return new ServiceEmission(
            byCoordinate(FrameQuery.read(store), FrameQuery.Row::coordinate),
            byCoordinate(CallQuery.read(store), CallQuery.Row::coordinate),
            byCoordinate(FillQuery.read(store), FillQuery.Row::coordinate),
            builds,
            byCoordinate(LiftQuery.read(store), LiftQuery.Row::coordinate),
            PolymorphicLiftQuery.read(store).stream().map(PolymorphicLiftQuery.Row::coordinate)
                .collect(Collectors.toSet()),
            excluded(store));
    }

    private static <R> Map<String, R> byCoordinate(List<R> rows, Function<R, String> coordinate) {
        var out = new LinkedHashMap<String, R>();
        rows.forEach(r -> out.put(coordinate.apply(r), r));
        return out;
    }

    /**
     * The root services the store states a part of the fetcher for that is not emitted here yet,
     * by why: a build with a tenant column, which routes calls by tenant; an outcome whose errors
     * claim validation, which runs a validation pass before the call; a target whose participants a
     * discriminating column tells apart, read off one shared table; and a connection's page.
     */
    private static Map<String, String> excluded(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var po = GRAPHITRON_PRODUCER_OUTCOME;
        var ef = GRAPHITRON_ERRORS_FIELD;
        var m = GRAPHQL_POLY_MEMBER;
        var da = GRAPHQL_DIRECTIVE_APPLICATION;
        var h = GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
        var tc = STORE_GRAPH_TENANT_COLUMN;
        var out = new LinkedHashMap<String, String>();
        boolean tenanted = store.dsl().fetchExists(selectOne().from(tc).where(tc.GRAPH_NAME.eq(store.graphName())));
        var coordinates = store.dsl().select(p.COORDINATE).from(p)
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .fetch(p.COORDINATE);
        if (tenanted) {
            coordinates.forEach(c -> out.put(c, "TENANT_ROUTING"));
            return out;
        }
        store.dsl().selectDistinct(p.COORDINATE)
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(po).on(po.GRAPH_NAME.eq(o.GRAPH_NAME),
                po.TYPE_NAME.concat(".").concat(po.FIELD_NAME).eq(o.ELEMENT_COORDINATE))
            .join(ef).on(ef.GRAPH_NAME.eq(po.GRAPH_NAME), ef.TYPE_NAME.eq(po.OUTCOME_TYPE_NAME),
                ef.FIELD_NAME.eq(po.ERRORS_FIELD_NAME))
            .join(m).on(m.GRAPH_NAME.eq(ef.GRAPH_NAME), m.CONTAINER_NAME.eq(ef.CONTAINER_NAME))
            .join(da).on(da.GRAPH_NAME.eq(m.GRAPH_NAME), da.COORDINATE.eq(m.MEMBER_TYPE_NAME),
                da.DIRECTIVE_NAME.eq("error"))
            .join(h).on(h.GRAPH_NAME.eq(da.GRAPH_NAME), h.SOURCE_NAME.eq(da.SOURCE_NAME),
                h.APPLICATION_LINE.eq(da.SOURCE_LINE), h.APPLICATION_COLUMN.eq(da.SOURCE_COLUMN),
                h.HANDLER.eq("VALIDATION"))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .fetch(p.COORDINATE)
            .forEach(c -> out.put(c, "VALIDATION"));
        var pt = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        store.dsl().selectDistinct(pt.COORDINATE).from(pt)
            .where(pt.GRAPH_NAME.eq(store.graphName()), pt.RECOGNIZED_BY.eq("DISCRIMINATOR"))
            .fetch(pt.COORDINATE)
            .forEach(c -> out.putIfAbsent(c, "DISCRIMINATED"));
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        store.dsl().select(r.COORDINATE).from(r)
            .where(r.GRAPH_NAME.eq(store.graphName()), r.SHAPE.eq("CONNECTION"))
            .fetch(r.COORDINATE)
            .forEach(c -> out.putIfAbsent(c, "CONNECTION"));
        return out;
    }

    /**
     * Every service the store states a frame for, with {@link #EMITTED} or why it is not emitted here
     * yet, by coordinate.
     */
    public Map<String, String> census() {
        var out = new java.util.TreeMap<String, String>();
        frames.keySet().forEach(c -> out.put(c, verdict(c)));
        return out;
    }

    /** {@link #EMITTED} where {@code coordinate}'s fetcher is emitted here, otherwise why not. */
    public String verdict(String coordinate) {
        var frame = frames.get(coordinate);
        if (frame == null) return "NO_FRAME";
        if (excluded.containsKey(coordinate)) return excluded.get(coordinate);
        if (!frame.root()) return "UNDER_AN_OBJECT";
        if (polymorphic.contains(coordinate)) return "POLYMORPHIC";
        var call = calls.get(coordinate);
        if (call == null) return "NO_CALL";
        var filled = fills.get(coordinate);
        if (filled == null || !filled.complete()) return "FILLS_INCOMPLETE";
        if (decodesNodeIds(coordinate, filled)) return "NODE_ID_DECODE";
        if (builds.getOrDefault(coordinate, Map.of()).values().stream()
                .anyMatch(r -> r instanceof BuildQuery.Result.Columns)) return "RECORD_BUILD";
        if (filled.fills().stream().anyMatch(f -> f.landing().map(l -> l.path().size() > 1).orElse(false))) {
            return "ARGUMENT_PATH";
        }
        return EMITTED;
    }

    /**
     * Whether an argument of the call decodes a node id: the decode's failure message is not yet the
     * one the classified emitters raise, so such a call stays with them.
     */
    private boolean decodesNodeIds(String coordinate, FillQuery.Row filled) {
        if (filled.fills().stream().anyMatch(f -> f.landing().map(FillQuery.Landing::nodeId).orElse(false))) {
            return true;
        }
        return builds.getOrDefault(coordinate, Map.of()).values().stream().anyMatch(ServiceEmission::decodes);
    }

    private static boolean decodes(BuildQuery.Result result) {
        return switch (result) {
            case BuildQuery.Result.Decoded d -> true;
            case BuildQuery.Result.Built b -> decodes(b.build());
            default -> false;
        };
    }

    private static boolean decodes(BuildQuery.Build build) {
        return build.members().stream()
            .anyMatch(m -> m.decode().isPresent() || m.nested().map(ServiceEmission::decodes).orElse(false));
    }

    /**
     * The fetcher of the field at {@code coordinate} named {@code fieldName}, or empty where it is not
     * emitted here, {@link #verdict} saying why.
     */
    public Optional<Emitted> emit(String coordinate, String fieldName, Runtime runtime) {
        if (!EMITTED.equals(verdict(coordinate))) {
            return Optional.empty();
        }
        var call = calls.get(coordinate);
        var lift = Optional.ofNullable(lifts.get(coordinate));
        var methods = new ArrayList<MethodSpec>();
        var nested = new ArrayList<TypeSpec>();
        String bodyName = fieldName + "Call";
        Optional<CallEmitter.Lift> liftRef = Optional.empty();
        if (lift.isPresent()) {
            var row = lift.get();
            var source = runtime.fetchersClass().nestedClass(sourceName(row.table()));
            nested.add(LiftEmitter.sourceTable(source, row.table(), row.key()).toBuilder()
                .addModifiers(javax.lang.model.element.Modifier.STATIC).build());
            String liftName = fieldName + "Lift";
            methods.add(LiftEmitter.lift(row, liftName, source, runtime.projectionClass().apply(row.targetType()),
                runtime.selectionOccurrences()));
            liftRef = Optional.of(new CallEmitter.Lift(runtime.fetchersClass(), liftName, row.list()));
        }
        var body = CallEmitter.body(call, fills.get(coordinate), builds.getOrDefault(coordinate, Map.of()),
            runtime.fill(), bodyName, Optional.empty(), liftRef);
        if (!(body instanceof CallEmitter.Result.Emitted emitted)) {
            return Optional.empty();
        }
        TypeName valueType = liftRef.isPresent()
            ? (liftRef.get().list()
                ? ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get("org.jooq", "Record"))
                : ClassName.get("org.jooq", "Record"))
            : SessionIdentityRows.typeOf(call.resultType()).box();
        var frame = FrameEmitter.render(frames.get(coordinate), fieldName, valueType, runtime.fetchersClass(),
            bodyName, runtime.frame(), needsContext(call, fills.get(coordinate), lift.isPresent()));
        var all = new ArrayList<MethodSpec>();
        all.add(frame);
        all.add(emitted.body());
        all.addAll(emitted.helpers());
        all.addAll(methods);
        return Optional.of(new Emitted(all, nested));
    }

    /**
     * Whether the call uses the jOOQ context: to lift what it hands back, to make its holder, or to
     * fill a parameter with the context or the session; a call that does not takes no connection.
     */
    private static boolean needsContext(CallQuery.Row call, FillQuery.Row filled, boolean lifted) {
        return lifted || call.holderTakesDsl() || filled.fills().stream()
            .anyMatch(f -> Set.of("DSL_CONTEXT", "SESSION", "SESSION_MEMBER").contains(f.fill()));
    }

    /** The nested derived source table's name: the lifted table's field name, as the tables class spells it. */
    private static String sourceName(LiftQuery.Table table) {
        var name = table.javaFieldName();
        var camel = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = false;
            }
        }
        return camel + "Source";
    }
}
