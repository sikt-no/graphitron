package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_VOUCHED_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SERVICE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FETCHER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;

/**
 * A {@code @service} fetcher emitted from the store, composed of the parts' emitters: the frame
 * graphql-java calls, the body that fills the parameters and calls the method, and the lift onto
 * the target's rows where the call hands back records of its table. The frame takes the field's own
 * name, the method the wiring registers, and the rest sit beside it in the type's fetchers class.
 *
 * <p>Every field the store states a service producer occurrence of is {@link #owns owned} here and
 * emitted here, whatever the classified model made of it: the store states every part of it, at a
 * root or under an object whose field has one occurrence, and refuses the graph where it cannot. An
 * owned field whose parts do not hold is a generator bug, and {@link #emit} throws naming what
 * failed rather than leave the field to the classified model's emitters. A discriminated
 * interface's rows are told apart by its column where {@link DiscriminatedQuery} states every
 * participant. A field whose call uses no jOOQ context takes none, so it opens no connection.
 */
public final class ServiceEmission {

    /** The verdict of a service whose parts all hold, which {@link #emit} emits. */
    public static final String EMITTED = "EMITTED";

    /**
     * The generated classes a store-emitted fetcher calls into, and where the type's fetchers class
     * and a target type's projection unit are.
     */
    public record Runtime(FrameEmitter.Runtime frame, FillEmitter.Runtime fill, ValidationEmitter.Runtime validation,
                          ClassName fetchersClass, ClassName selectionOccurrences,
                          Function<String, ClassName> projectionClass, FrameConnections.Routing routing) {

        public Runtime {
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(validation, "validation");
            Objects.requireNonNull(fill, "fill");
            Objects.requireNonNull(fetchersClass, "fetchersClass");
            Objects.requireNonNull(selectionOccurrences, "selectionOccurrences");
            Objects.requireNonNull(projectionClass, "projectionClass");
        }
    }

    /**
     * What a field's fetcher is made of: the methods for its fetchers class, the frame first, the
     * classes to nest in it, the lifted tables' derived source tables, one per table, and the
     * constants its validation pass names.
     */
    public record Emitted(List<MethodSpec> methods, List<TypeSpec> nestedTypes, List<FieldSpec> constants) {
        public Emitted {
            methods = List.copyOf(methods);
            nestedTypes = List.copyOf(nestedTypes);
            constants = List.copyOf(constants);
        }
    }


    /** Whether a store was read: {@link #none()} has none, and leaves every field to the classified model. */
    private final boolean stored;
    /** The fields the store states a service producer occurrence of, by their declared coordinate. */
    private final Set<String> owned;
    /** The fields the store states a fetcher for, by their declared coordinate. */
    private final Set<String> fetched;
    /** The coordinates whose walk errors are the store's to judge; see {@link #walkSilenced()}. */
    private final Set<String> silenced;
    private final Map<String, FrameQuery.Row> frames;
    private final Map<String, CallQuery.Row> calls;
    private final Map<String, FillQuery.Row> fills;
    private final Map<String, Map<Integer, BuildQuery.Result>> builds;
    private final Map<String, LiftQuery.Row> lifts;
    private final Map<String, PolymorphicLiftQuery.Row> polymorphic;
    /** The services whose outcome claims validation, by coordinate. */
    private final Set<String> validating;
    /** The services that own their transactions ({@code transactionControl: SERVICE}), by coordinate. */
    private final Set<String> serviceControlled;
    /** The lifting occurrences whose rows a column of one shared table tells apart, by coordinate. */
    private final Map<String, DiscriminatedQuery.Row> discriminated;
    /** The services whose rows a column of one shared table tells apart, lifting or not. */
    private final Set<String> toldApart;
    /** Each producer's connection in a graph whose connection key has a part, by coordinate. */
    private final Map<String, ConnectionQuery.Row> connections;
    /** The tenant column's Java type, in a database-per-tenant graph whose tables agree on one. */
    private final Optional<String> tenantType;
    /** The services the store states no connection for by design; see {@link #unkeyed(StoreHandle)}. */
    private final Set<String> unkeyed;
    /** The types whose projection reads the session, which a lift hands the statement's connection. */
    private final Set<String> sessionProjections;

    private ServiceEmission(boolean stored, Set<String> owned, Set<String> fetched,
                            Set<String> silenced,
                            Map<String, FrameQuery.Row> frames, Map<String, CallQuery.Row> calls,
                            Map<String, FillQuery.Row> fills, Map<String, Map<Integer, BuildQuery.Result>> builds,
                            Map<String, LiftQuery.Row> lifts, Map<String, PolymorphicLiftQuery.Row> polymorphic,
                            Set<String> validating, Set<String> serviceControlled,
                            Map<String, DiscriminatedQuery.Row> discriminated,
                            Set<String> toldApart,
                            Map<String, ConnectionQuery.Row> connections, Optional<String> tenantType,
                            Set<String> unkeyed, Set<String> sessionProjections) {
        this.stored = stored;
        this.owned = owned;
        this.fetched = fetched;
        this.silenced = silenced;
        this.frames = frames;
        this.calls = calls;
        this.fills = fills;
        this.builds = builds;
        this.lifts = lifts;
        this.polymorphic = polymorphic;
        this.validating = validating;
        this.serviceControlled = serviceControlled;
        this.discriminated = discriminated;
        this.toldApart = toldApart;
        this.connections = connections;
        this.tenantType = tenantType;
        this.unkeyed = unkeyed;
        this.sessionProjections = sessionProjections;
    }

    /**
     * Owns nothing: every service keeps its classified fetcher, for a caller with no store, which only
     * the classified model's own tests are.
     */
    public static ServiceEmission none() {
        return new ServiceEmission(false, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(),
            Map.of(),
            Set.of(), Map.of(), Optional.empty(), Set.of(), Set.of());
    }

    /** Reads every part's rows for the graph once. */
    public static ServiceEmission read(StoreHandle store) {
        var builds = new HashMap<String, Map<Integer, BuildQuery.Result>>();
        BuildQuery.read(store).forEach((root, result) ->
            builds.computeIfAbsent(root.callCoordinate(), k -> new HashMap<>()).put(root.position(), result));
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var services = Set.copyOf(store.dsl().select(p.COORDINATE).from(p)
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE")).fetch(p.COORDINATE));
        // The services' lifts: a routine write's is rendered beside its own body.
        var lifts = byCoordinate(LiftQuery.read(store).stream().filter(l -> services.contains(l.coordinate())).toList(),
            LiftQuery.Row::coordinate);
        var targets = new HashMap<String, String>();
        lifts.forEach((coordinate, row) -> targets.put(coordinate, row.targetType()));
        var owned = owned(store);
        return new ServiceEmission(
            true,
            owned,
            fetched(store),
            walkSilenced(store, services, owned),
            // The services' frames: a routine write's is framed by its own emitter.
            byCoordinate(FrameQuery.read(store).stream().filter(f -> services.contains(f.coordinate())).toList(),
                FrameQuery.Row::coordinate),
            byCoordinate(CallQuery.read(store), CallQuery.Row::coordinate),
            byCoordinate(FillQuery.read(store), FillQuery.Row::coordinate),
            builds,
            lifts,
            byCoordinate(PolymorphicLiftQuery.read(store), PolymorphicLiftQuery.Row::coordinate),
            validating(store),
            serviceControlled(store),
            DiscriminatedQuery.read(store, targets),
            toldApart(store),
            ConnectionQuery.read(store),
            ConnectionQuery.tenantType(store),
            unkeyed(store),
            ProjectionSessionQuery.read(store));
    }

    /**
     * The service occurrences the store states no connection for by design, which run on the request's
     * own: every one in a graph whose connection key has no part, and the roots of a graph that
     * accepts vouchers, a vouched key only ever being handed down. None in a graph with a tenant
     * column, where a service the store states no connection for is one it refuses.
     */
    private static Set<String> unkeyed(StoreHandle store) {
        var tc = STORE_GRAPH_TENANT_COLUMN;
        if (store.dsl().fetchExists(tc, tc.GRAPH_NAME.eq(store.graphName()))) {
            return Set.of();
        }
        var av = GRAPHITRON_ACCEPT_VOUCHED_FOR;
        boolean accepting = store.dsl().fetchExists(av, av.GRAPH_NAME.eq(store.graphName()));
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        return Set.copyOf(store.dsl().select(p.COORDINATE).from(p)
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"),
                accepting ? p.START.eq("ROOT") : org.jooq.impl.DSL.noCondition())
            .fetch(p.COORDINATE));
    }

    /** The fields the store states a service producer occurrence of, by their declared coordinate. */
    private static Set<String> owned(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        return Set.copyOf(store.dsl().selectDistinct(o.ELEMENT_COORDINATE)
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .fetch(o.ELEMENT_COORDINATE));
    }

    /** The fields the store states a fetcher for, by their declared coordinate. */
    private static Set<String> fetched(StoreHandle store) {
        var fe = GRAPHITRON_FETCHER;
        var coordinate = fe.TYPE_NAME.concat(".").concat(fe.FIELD_NAME);
        return Set.copyOf(store.dsl().select(coordinate).from(fe)
            .where(fe.GRAPH_NAME.eq(store.graphName()))
            .fetch(coordinate));
    }

    /**
     * The coordinates the classified model's errors are not asked at, the store judging them: each
     * owned field, its arguments, and the input fields and input types reached through a service's
     * arguments and through nothing else. An input field or input type also reached through another
     * field's arguments keeps the classified model's errors, which that field still answers to.
     */
    private static Set<String> walkSilenced(StoreHandle store, Set<String> services, Set<String> owned) {
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var underService = new HashSet<String>();
        var underOther = new HashSet<String>();
        store.dsl().select(o.FIELD_COORDINATE, o.KIND, o.ELEMENT_COORDINATE, f.TYPE_NAME)
            .from(o)
            .leftJoin(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE),
                o.KIND.eq("INPUT_FIELD"))
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"))
            .forEach(r -> {
                var into = services.contains(r.value1()) ? underService : underOther;
                into.add(r.value3());
                if (r.value4() != null) {
                    into.add(r.value4());
                }
            });
        underService.removeAll(underOther);
        underService.addAll(owned);
        return Collections.unmodifiableSet(underService);
    }

    private static <R> Map<String, R> byCoordinate(List<R> rows, Function<R, String> coordinate) {
        var out = new LinkedHashMap<String, R>();
        rows.forEach(r -> out.put(coordinate.apply(r), r));
        return out;
    }

    /** The services a discriminator tells some participant of apart. */
    private static Set<String> toldApart(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var pt = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        return Set.copyOf(store.dsl().selectDistinct(p.COORDINATE)
            .from(p)
            .join(pt).on(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE),
                pt.RECOGNIZED_BY.eq("DISCRIMINATOR"))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .fetch(p.COORDINATE));
    }

    /**
     * The services that own their transactions: the {@code @service} a call came from says
     * {@code transactionControl: SERVICE}. The store refuses one outside a mutation field, so each is
     * a top-level mutation field's.
     */
    private static Set<String> serviceControlled(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CALL;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var se = GRAPHITRON_AST_SERVICE_ENTRY;
        return Set.copyOf(store.dsl().select(c.COORDINATE)
            .from(c)
            .join(da).on(da.GRAPH_NAME.eq(c.GRAPH_NAME), da.COORDINATE.eq(c.APPLICATION_COORDINATE),
                da.DIRECTIVE_NAME.eq(c.APPLICATION_DIRECTIVE), da.ORDINAL.eq(c.APPLICATION_ORDINAL))
            .join(se).on(se.GRAPH_NAME.eq(da.GRAPH_NAME), se.SOURCE_NAME.eq(da.SOURCE_NAME),
                se.SOURCE_LINE.eq(da.SOURCE_LINE), se.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .where(c.GRAPH_NAME.eq(store.graphName()), c.CALL_KIND.eq("SERVICE"),
                se.TRANSACTION_CONTROL.eq("SERVICE"))
            .fetch(c.COORDINATE));
    }

    /**
     * The services whose outcome claims validation, as {@code graphitron_occurrence_producer_service.validates}
     * states them: a member of the errors field's union is an {@code @error} with a {@code VALIDATION}
     * handler. Such a service validates the arguments its
     * call builds before the call, a class whole and a jOOQ record one written column at a time; one
     * that builds none has no pass to run, and its outcome is the frame's like any other.
     */
    private static Set<String> validating(StoreHandle store) {
        var v = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        return Set.copyOf(store.dsl().select(v.COORDINATE)
            .from(v)
            .where(v.GRAPH_NAME.eq(store.graphName()), v.VALIDATES.isTrue())
            .fetch(v.COORDINATE));
    }


    /**
     * {@link #EMITTED} where every part the store states of the occurrence at {@code coordinate} holds,
     * otherwise the first that does not; a shape the store refuses, met here, throws as a generator bug.
     */
    private String statedVerdict(String coordinate) {
        var frame = frames.get(coordinate);
        if (frame == null) return "NO_FRAME";
        // A field with several service occurrences is not judged here: the store refuses it as
        // SERVICE_SEVERAL_OCCURRENCES_UNSUPPORTED, and emit throws on it.
        // A discriminated service's rows are lifted participant by participant off the shared table's
        // records, at a root only; the store refuses every other shape, so meeting one is a bug.
        if (toldApart.contains(coordinate) && !lifts.containsKey(coordinate)) {
            throw new IllegalStateException("Graphitron generator bug: @service " + coordinate
                + " answers a discriminated interface with no lift, which SERVICE_RETURN_MISMATCH refuses as an"
                + " answer that is not the shared table's records");
        }
        if (toldApart.contains(coordinate) && !lifts.get(coordinate).root()) {
            throw new IllegalStateException("Graphitron generator bug: @service " + coordinate
                + " answers a discriminated interface under an object, which SERVICE_CHILD_POLYMORPHIC_UNSUPPORTED"
                + " refuses");
        }
        var call = calls.get(coordinate);
        if (call == null) return "NO_CALL";
        var filled = fills.get(coordinate);
        if (filled == null || !filled.complete()) return "FILLS_INCOMPLETE";
        var built = builds.getOrDefault(coordinate, Map.of()).values();
        if (built.stream().anyMatch(r -> r instanceof BuildQuery.Result.Refused)) return "BUILD_REFUSED";
        return EMITTED;
    }

    /**
     * Whether the store states a service producer occurrence of the field at {@code fieldCoordinate}:
     * such a field is emitted here, whatever the classified model made of it, or the generation fails.
     */
    public boolean owns(String fieldCoordinate) {
        return owned.contains(fieldCoordinate);
    }

    /**
     * The coordinates the classified model's validation errors are dropped at, the store judging what
     * is there: every owned field, its arguments, and the input fields and input types reached through
     * a service's arguments and through no other field's. Empty for {@link #none()}.
     */
    public Set<String> walkSilenced() {
        return silenced;
    }

    /**
     * Refuses a {@code @service} field the store does not own, though it states a fetcher for it:
     * with a store read, every service a fetcher is stated for is owned, so nothing would emit the
     * fetcher the store asks for. Passes for {@link #none()}, which states no fetcher.
     */
    public void requireOwnedWhereFetched(String fieldCoordinate) {
        if (stored && fetched.contains(fieldCoordinate)) {
            throw new IllegalStateException("Graphitron generator bug: @service " + fieldCoordinate
                + " has a fetcher, but the store states no service producer occurrence of it, so it is not"
                + " emitted from the store");
        }
    }

    /** The throw for an owned field whose parts do not hold, naming the {@code verdict}. */
    private static IllegalStateException notEmitted(String fieldCoordinate, String verdict, Throwable cause) {
        return new IllegalStateException("Graphitron generator bug: @service " + fieldCoordinate
            + " is not emitted from the store (" + verdict + "): the store accepted the graph, so it should"
            + " have refused this", cause);
    }

    /**
     * What an emitted mutation's {@code @outbox} messages need: the store's row for the field, what
     * the companion takes from the generated runtime, the package its node-id encoder is under, the
     * provider an owned transaction is opened through, and the connection carrier a failure
     * companion is registered with.
     */
    public record Outbox(OutboxQuery.Row row, OutboxEmitter.Runtime runtime, String outputPackage,
                         ClassName provider, ClassName ownedConnections) {
        public Outbox {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(outputPackage, "outputPackage");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(ownedConnections, "ownedConnections");
        }

        /** Whether the field writes messages when it commits. */
        boolean writesSuccess() {
            return !row.successMessages().isEmpty();
        }

        /** Whether the field writes messages after it rolled back. */
        boolean writesFailure() {
            return !row.failureMessages().isEmpty();
        }
    }

    /**
     * The fetcher of the field at {@code fieldCoordinate} named {@code fieldName}, or empty where the
     * field is not {@link #owns owned} here. An owned field whose parts do not hold throws, naming
     * which.
     */
    public Optional<Emitted> emit(String fieldCoordinate, String fieldName, Runtime runtime) {
        return emit(fieldCoordinate, fieldName, runtime, Optional.empty());
    }

    /**
     * {@link #emit}, writing {@code outbox}'s messages where it is present: the body runs inside one
     * owned transaction whose context the service is handed, and the companion writes the messages on
     * it from what the service returned, so they commit exactly when its writes do.
     */
    public Optional<Emitted> emit(String fieldCoordinate, String fieldName, Runtime runtime,
                                  Optional<Outbox> outbox) {
        if (!owns(fieldCoordinate)) {
            return Optional.empty();
        }
        var occurrences = occurrencesOf(fieldCoordinate);
        if (occurrences.size() > 1) {
            throw new IllegalStateException("Graphitron generator bug: @service " + fieldCoordinate
                + " has " + occurrences.size() + " service occurrences; the store refuses this shape as"
                + " SERVICE_SEVERAL_OCCURRENCES_UNSUPPORTED, so reaching it is a generator bug");
        }
        String verdict = occurrences.isEmpty() ? "NO_FRAME" : statedVerdict(occurrences.getFirst());
        if (!EMITTED.equals(verdict)) {
            throw notEmitted(fieldCoordinate, verdict, null);
        }
        String coordinate = occurrences.getFirst();
        var frameRow = frames.get(coordinate);
        Optional<CallEmitter.FrameKey> frameKey = frameRow.parent().map(ServiceEmission::frameKey);
        var call = calls.get(coordinate);
        var lift = Optional.ofNullable(lifts.get(coordinate));
        var methods = new ArrayList<MethodSpec>();
        var nested = new ArrayList<TypeSpec>();
        String bodyName = fieldName + "Call";
        Optional<CallEmitter.Lift> liftRef = Optional.empty();
        if (lift.isPresent()) {
            var row = lift.get();
            var source = runtime.fetchersClass().nestedClass(LiftEmitter.sourceTableName(row.table()));
            nested.add(LiftEmitter.sourceTable(source, row.table(), row.key()).toBuilder()
                .addModifiers(javax.lang.model.element.Modifier.STATIC).build());
            String liftName = fieldName + "Lift";
            var told = discriminated.get(coordinate);
            methods.add(told != null
                ? LiftEmitter.discriminatedLift(row, told, liftName, source, runtime.projectionClass(),
                    runtime.selectionOccurrences(), sessionProjections::contains)
                : LiftEmitter.lift(row, liftName, source, runtime.projectionClass().apply(row.targetType()),
                runtime.selectionOccurrences(), Optional.empty(), sessionProjections.contains(row.targetType())));
            liftRef = Optional.of(new CallEmitter.Lift(runtime.fetchersClass(), liftName, row.list()));
        }
        // A record that may be a row of several members' tables is lifted by its own member's lift,
        // each row carrying its type's name and projecting only its type's occurrences.
        Optional<CallEmitter.Polymorphic> members = Optional.empty();
        var poly = polymorphic.get(coordinate);
        if (poly != null) {
            var each = new ArrayList<CallEmitter.Polymorphic.Member>();
            for (var participant : poly.participants()) {
                var source = runtime.fetchersClass().nestedClass(LiftEmitter.sourceTableName(participant.table()));
                nested.add(LiftEmitter.sourceTable(source, participant.table(), participant.key()).toBuilder()
                    .addModifiers(javax.lang.model.element.Modifier.STATIC).build());
                String liftName = fieldName + participant.typeName() + "Lift";
                var liftRow = new LiftQuery.Row(coordinate, participant.typeName(), true, true,
                    participant.table(), participant.key());
                methods.add(LiftEmitter.lift(liftRow, liftName, source,
                    runtime.projectionClass().apply(participant.typeName()), runtime.selectionOccurrences(),
                    Optional.of(participant.typeName()), sessionProjections.contains(participant.typeName())));
                each.add(new CallEmitter.Polymorphic.Member(participant.typeName(),
                    ClassName.bestGuess(participant.table().recordClassName()), runtime.fetchersClass(), liftName));
            }
            members = Optional.of(new CallEmitter.Polymorphic(poly.list(), each));
        }
        boolean validates = validating.contains(coordinate);
        CallEmitter.Result.Emitted emitted;
        try {
            emitted = (CallEmitter.Result.Emitted) CallEmitter.body(call, fills.get(coordinate),
                builds.getOrDefault(coordinate, Map.of()), runtime.fill(), bodyName, frameKey, liftRef, members,
                validates ? Optional.of(runtime.validation()) : Optional.empty());
        } catch (IllegalStateException refused) {
            throw notEmitted(fieldCoordinate, "CALL_REFUSED, " + refused.getMessage(), refused);
        }
        boolean listed = liftRef.map(CallEmitter.Lift::list).orElse(members.map(CallEmitter.Polymorphic::list).orElse(false));
        TypeName valueType = liftRef.isPresent() || members.isPresent()
            ? (listed
                ? ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get("org.jooq", "Record"))
                : ClassName.get("org.jooq", "Record"))
            : answered(SessionIdentityRows.typeOf(call.resultType()).box(), frameKey.isPresent());
        String framed = bodyName;
        var written = new ArrayList<MethodSpec>();
        boolean writesSuccess = outbox.map(Outbox::writesSuccess).orElse(false);
        if (outbox.isPresent() && frameKey.isPresent()) {
            throw new IllegalStateException(coordinate + " carries an @outbox under an object; the store"
                + " admits one on a mutation field only");
        }
        if (writesSuccess) {
            framed = fieldName + "Written";
            written.addAll(written(outbox.get(), emitted.body(), framed, runtime.fetchersClass(), validates,
                serviceControlled.contains(coordinate)));
        }
        // A child call's generated scalar arguments are decoded in its fetcher too, before the key is
        // loaded, so a wrong value fails the field it was given on; the batch decodes them again where
        // it hands the components over.
        var beforeLoad = frameKey.isPresent()
            ? FillEmitter.decodeChecks(fills.get(coordinate), runtime.fill())
            : CodeBlock.of("");
        var frame = FrameEmitter.render(frameRow, fieldName, valueType,
            new FrameEmitter.Body.Call(runtime.fetchersClass(), framed), runtime.frame(),
            writesSuccess || needsContext(call, fills.get(coordinate), lift.isPresent() || members.isPresent()),
            validates, FrameConnections.of(frameRow, Optional.ofNullable(connections.get(coordinate)),
                unkeyed.contains(coordinate), tenantType, runtime.routing(), fieldCoordinate), beforeLoad);
        if (outbox.isPresent() && outbox.get().writesFailure()) {
            // Registered first thing, before the frame acquires a connection, so a failure to
            // acquire or mount is covered too.
            var o = outbox.get();
            frame = prepended(frame, OutboxEmitter.registration(o.row(), o.ownedConnections()));
            written.addAll(OutboxEmitter.renderFailed(o.row(), o.outputPackage(), o.runtime()));
        }
        if (serviceControlled.contains(coordinate)) {
            // Marked ahead of everything else, the failure registration included, so the field opens
            // no transaction on any connection it acquires.
            frame = prepended(frame, CodeBlock.builder()
                .addStatement("$T.markFieldServiceControlled(env)", runtime.routing().ownedConnections())
                .build());
        }
        var all = new ArrayList<MethodSpec>();
        all.add(frame);
        all.addAll(written);
        all.add(emitted.body());
        all.addAll(emitted.helpers());
        all.addAll(methods);
        return Optional.of(new Emitted(all, nested, emitted.constants()));
    }

    /**
     * The owned transaction an {@code @outbox} field's body runs in, and the companion writing its
     * messages on it from the value the body answers: the success's value where the body answers an
     * outcome, and nothing where it answers a failure, since then the service was never called.
     *
     * <p>A service that owns its transactions shares none with its messages: it runs on the
     * connection as it is, and its messages follow the field's outcome, handed to the connection
     * carrier to write once the field settled succeeded, in a transaction of their own. Where no
     * carrier takes them (the escape hatch) they are written at once, in an owned transaction.
     */
    private static List<MethodSpec> written(Outbox outbox, MethodSpec body, String name, ClassName host,
                                            boolean validates, boolean serviceControlled) {
        TypeName answered = body.returnType();
        TypeName value = validates ? ((ParameterizedTypeName) answered).typeArguments().getFirst() : answered;
        var companion = OutboxEmitter.renderService(outbox.row(), value, outbox.outputPackage(), outbox.runtime());
        var dsl = ClassName.get("org.jooq.impl", "DSL");
        var lambda = CodeBlock.builder();
        if (serviceControlled) {
            lambda.addStatement("$T answered = $T.$L(env, dsl)", answered, host, body.name());
        } else {
            lambda.add("return $L{\n", OwnedScope.transactionResult(outbox.provider(), "dsl")).indent()
                .addStatement("$T answered = $T.$L(env, $T.using(tx))", answered, host, body.name(), dsl);
        }
        String written = validates ? "success.value()" : "answered";
        if (validates) {
            var success = ((ParameterizedTypeName) answered).rawType().nestedClass("Success");
            lambda.beginControlFlow("if (answered instanceof $T<$T> success)", success, value.box());
        }
        lambda.add(serviceControlled ? afterSettle(outbox, written) : OutboxEmitter.call(outbox.row(), "tx", written));
        if (validates) {
            lambda.endControlFlow();
        }
        lambda.addStatement("return answered");
        if (!serviceControlled) {
            lambda.unindent().add("});\n");
        }
        var wrapper = MethodSpec.methodBuilder(name)
            .addModifiers(javax.lang.model.element.Modifier.STATIC)
            .returns(answered)
            .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
            .addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl")
            .addException(Exception.class)
            .addJavadoc(serviceControlled
                ? "Runs {@code $L} on the connection as it is, its service owning its transactions, and has\n"
                    + "its {@code @outbox} messages written once the field settled succeeded.\n"
                : "Runs {@code $L} and writes its {@code @outbox} messages in one owned transaction.\n",
                body.name())
            .addCode(lambda.build())
            .build();
        return List.of(wrapper, companion);
    }

    /**
     * A service-controlled field's success messages: handed to the connection carrier, which writes
     * them when the field settles succeeded, or written now, in an owned transaction, where no
     * carrier takes them.
     */
    private static CodeBlock afterSettle(Outbox outbox, String written) {
        var events = CodeBlock.join(outbox.row().successMessages().stream()
            .map(m -> CodeBlock.of("$S", m.event())).toList(), ", ");
        var write = OutboxEmitter.call(outbox.row(), "tx", written);
        return CodeBlock.builder()
            .add("if (!$T.onFieldSuccess(env, $T.of($L), (settled, claimed) -> $L{\n",
                outbox.ownedConnections(), List.class, events,
                OwnedScope.transactionResult(outbox.provider(), "settled")).indent()
            .add(write)
            .addStatement("return null")
            .unindent().add("}))) {\n").indent()
            .add("$L{\n", OwnedScope.transactionResult(outbox.provider(), "dsl")).indent()
            .add(write)
            .addStatement("return null")
            .unindent().add("});\n")
            .unindent().add("}\n")
            .build();
    }

    /** {@code method} with {@code first} ahead of its body. */
    private static MethodSpec prepended(MethodSpec method, CodeBlock first) {
        var copy = MethodSpec.methodBuilder(method.name())
            .addModifiers(method.modifiers())
            .addAnnotations(method.annotations())
            .addTypeVariables(method.typeVariables())
            .returns(method.returnType())
            .addParameters(method.parameters())
            .addExceptions(method.exceptions())
            .varargs(method.varargs())
            .addCode(first)
            .addCode(method.code());
        if (!method.javadoc().isEmpty()) {
            copy.addJavadoc(method.javadoc());
        }
        return copy.build();
    }

    /** The occurrences of the field at {@code fieldCoordinate} the store states a frame for. */
    private List<String> occurrencesOf(String fieldCoordinate) {
        return frames.values().stream().filter(f -> elementOf(f).equals(fieldCoordinate))
            .map(FrameQuery.Row::coordinate).sorted().toList();
    }

    /** The field an occurrence is of: its declaring type and the coordinate's last name. */
    private static String elementOf(FrameQuery.Row frame) {
        var coordinate = frame.coordinate();
        return frame.declaringType() + coordinate.substring(coordinate.lastIndexOf('.'));
    }

    /** The batch's keys as the frame hands them over: records of the parent's key, off its table. */
    private static CallEmitter.FrameKey frameKey(FrameQuery.ParentTable parent) {
        TypeName[] keyTypes = parent.key().stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.bindingType())).toArray(TypeName[]::new);
        return new CallEmitter.FrameKey(ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + keyTypes.length),
            keyTypes), ClassName.bestGuess(parent.constantsClassName()), parent.javaFieldName());
    }

    /**
     * What the body answers for one occurrence: the call's answer at a root, and under an object what
     * it answers per parent, a map's value or a list's element.
     */
    private static TypeName answered(TypeName answer, boolean batched) {
        if (!batched || !(answer instanceof ParameterizedTypeName p)) {
            return answer;
        }
        return p.rawType().simpleName().equals("Map") ? p.typeArguments().get(1) : p.typeArguments().getFirst();
    }

    /**
     * Whether the call uses the jOOQ context: to lift what it hands back, to make its holder, or to
     * fill a parameter with the context or the session; a call that does not takes no connection.
     */
    private static boolean needsContext(CallQuery.Row call, FillQuery.Row filled, boolean lifted) {
        return lifted || call.holderTakesDsl() || filled.fills().stream()
            .anyMatch(f -> Set.of("DSL_CONTEXT", "SESSION", "SESSION_MEMBER").contains(f.fill()));
    }

}
