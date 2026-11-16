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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;

/**
 * A {@code @service} fetcher emitted from the store, composed of the parts' emitters: the frame
 * graphql-java calls, the body that fills the parameters and calls the method, and the lift onto
 * the target's rows where the call hands back records of its table. The frame takes the field's own
 * name, the method the wiring registers, and the rest sit beside it in the type's fetchers class.
 *
 * <p>A field is emitted here when the store states every part of it and none of what is not yet
 * emitted from the store applies; otherwise it stays with the classified model's emitters, and
 * {@link #census} says why. Today that is a service, at a root or under an object whose field has one
 * occurrence, whose answer is a record, a scalar or the records of its target's table, in a build with no tenant column, whose outcome claims no
 * validation, whose arguments build no jOOQ record and reach no input field
 * deeper than the argument itself, and whose target is not a connection; a discriminated interface's rows are told apart by
 * its column where {@link DiscriminatedQuery} states every participant. A field whose call uses no jOOQ context takes none, so it opens no connection.
 */
public final class ServiceEmission {

    /** Why a service is not emitted here yet; the census reads these. */
    public static final String EMITTED = "EMITTED";

    /**
     * The generated classes a store-emitted fetcher calls into, and where the type's fetchers class
     * and a target type's projection unit are.
     */
    public record Runtime(FrameEmitter.Runtime frame, FillEmitter.Runtime fill, ValidationEmitter.Runtime validation,
                          ClassName fetchersClass, ClassName selectionOccurrences,
                          Function<String, ClassName> projectionClass, Routing routing) {

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
     * The generated classes a routed fetcher calls into: the connection carrier, the key it is acquired
     * and handed down by, the client error a call naming no tenant is refused with, and the node id
     * encoder a tenant inside an id is decoded by. Named only where the store states a routed call.
     */
    public record Routing(ClassName ownedConnections, ClassName connectionKey, ClassName clientException,
                          ClassName nodeIdEncoder) {
        public Routing {
            Objects.requireNonNull(ownedConnections, "ownedConnections");
            Objects.requireNonNull(connectionKey, "connectionKey");
            Objects.requireNonNull(clientException, "clientException");
            Objects.requireNonNull(nodeIdEncoder, "nodeIdEncoder");
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

    /**
     * The names the census renders a body with: it asks the call emitter whether it emits a body,
     * so the census and {@link #emit} decide by the one emitter, and the body itself is dropped.
     */
    private static final ClassName CENSUS_HOST = ClassName.get("census", "Census");
    private static final FillEmitter.Runtime CENSUS_FILL = new FillEmitter.Runtime(
        key -> CodeBlock.of("null"), coordinate -> CodeBlock.of("null"), (member, coordinate) -> CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(CENSUS_HOST, "census"));
    private static final ValidationEmitter.Runtime CENSUS_VALIDATION = new ValidationEmitter.Runtime(
        CENSUS_HOST, CENSUS_HOST, env -> CodeBlock.of("null"));

    /** Every service producer occurrence the store states, by coordinate: what the census reads. */
    private final Set<String> services;
    private final Map<String, FrameQuery.Row> frames;
    private final Map<String, CallQuery.Row> calls;
    private final Map<String, FillQuery.Row> fills;
    private final Map<String, Map<Integer, BuildQuery.Result>> builds;
    private final Map<String, LiftQuery.Row> lifts;
    private final Map<String, PolymorphicLiftQuery.Row> polymorphic;
    /** The services whose outcome claims validation, by coordinate. */
    private final Set<String> validating;
    /** The lifting occurrences whose rows a column of one shared table tells apart, by coordinate. */
    private final Map<String, DiscriminatedQuery.Row> discriminated;
    /** The services whose rows a column of one shared table tells apart, lifting or not. */
    private final Set<String> toldApart;
    /** Each root call's connection in a database-per-tenant graph, by coordinate. */
    private final Map<String, ConnectionQuery.Row> connections;
    /** The tenant column's Java type, in a database-per-tenant graph whose tables agree on one. */
    private final Optional<String> tenantType;

    private ServiceEmission(Set<String> services, Map<String, FrameQuery.Row> frames, Map<String, CallQuery.Row> calls,
                            Map<String, FillQuery.Row> fills, Map<String, Map<Integer, BuildQuery.Result>> builds,
                            Map<String, LiftQuery.Row> lifts, Map<String, PolymorphicLiftQuery.Row> polymorphic,
                            Set<String> validating, Map<String, DiscriminatedQuery.Row> discriminated,
                            Set<String> toldApart,
                            Map<String, ConnectionQuery.Row> connections, Optional<String> tenantType) {
        this.services = services;
        this.frames = frames;
        this.calls = calls;
        this.fills = fills;
        this.builds = builds;
        this.lifts = lifts;
        this.polymorphic = polymorphic;
        this.validating = validating;
        this.discriminated = discriminated;
        this.toldApart = toldApart;
        this.connections = connections;
        this.tenantType = tenantType;
    }

    /** Emits nothing: every service keeps its classified fetcher, for a caller with no store. */
    public static ServiceEmission none() {
        return new ServiceEmission(Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(),
            Set.of(), Map.of(), Optional.empty());
    }

    /** Reads every part's rows for the graph once. */
    public static ServiceEmission read(StoreHandle store) {
        var builds = new HashMap<String, Map<Integer, BuildQuery.Result>>();
        BuildQuery.read(store).forEach((root, result) ->
            builds.computeIfAbsent(root.callCoordinate(), k -> new HashMap<>()).put(root.position(), result));
        var lifts = byCoordinate(LiftQuery.read(store), LiftQuery.Row::coordinate);
        var targets = new HashMap<String, String>();
        lifts.forEach((coordinate, row) -> targets.put(coordinate, row.targetType()));
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        return new ServiceEmission(
            Set.copyOf(store.dsl().select(p.COORDINATE).from(p)
                .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE")).fetch(p.COORDINATE)),
            byCoordinate(FrameQuery.read(store), FrameQuery.Row::coordinate),
            byCoordinate(CallQuery.read(store), CallQuery.Row::coordinate),
            byCoordinate(FillQuery.read(store), FillQuery.Row::coordinate),
            builds,
            lifts,
            byCoordinate(PolymorphicLiftQuery.read(store), PolymorphicLiftQuery.Row::coordinate),
            validating(store),
            DiscriminatedQuery.read(store, targets),
            toldApart(store),
            ConnectionQuery.read(store),
            tenantType(store));
    }

    /** The tenant column's Java type, where the graph's tenant columns agree on one. */
    private static Optional<String> tenantType(StoreHandle store) {
        var tc = GRAPHITRON_TENANT_COLUMN;
        var c = SQL_COLUMN;
        var types = store.dsl().selectDistinct(c.BINDING_TYPE).from(tc)
            .join(c).on(c.SOURCE_NAME.eq(tc.SOURCE_NAME), c.TABLE_SCHEMA.eq(tc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(tc.TABLE_NAME), c.COLUMN_NAME.eq(tc.COLUMN_NAME))
            .where(tc.GRAPH_NAME.eq(store.graphName()))
            .fetch(c.BINDING_TYPE);
        return types.size() == 1 ? Optional.of(types.getFirst()) : Optional.empty();
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
     * The services whose outcome claims validation: a member of the errors field's union is an
     * {@code @error} with a {@code VALIDATION} handler. Such a service validates the arguments its
     * call builds before the call, a class whole and a jOOQ record one written column at a time; one
     * that builds none has no pass to run, and its outcome is the frame's like any other.
     */
    private static Set<String> validating(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var po = GRAPHITRON_PRODUCER_OUTCOME;
        var ef = GRAPHITRON_ERRORS_FIELD;
        var m = GRAPHQL_POLY_MEMBER;
        var da = GRAPHQL_DIRECTIVE_APPLICATION;
        var h = GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
        return store.dsl().selectDistinct(p.COORDINATE)
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
            .stream().collect(Collectors.toSet());
    }

    /**
     * Every service producer the store states, with {@link #EMITTED} or why it is not emitted here
     * yet, by coordinate: one with no frame is {@code NO_FRAME}, which on a graph the store accepts is
     * a regression rather than a deferral.
     */
    public Map<String, String> census() {
        var out = new java.util.TreeMap<String, String>();
        services.forEach(c -> out.put(c, verdict(c)));
        return out;
    }

    /** {@link #EMITTED} where {@code coordinate}'s fetcher is emitted here, otherwise why not. */
    public String verdict(String coordinate) {
        var frame = frames.get(coordinate);
        if (frame == null) return "NO_FRAME";
        if (occurrencesOf(elementOf(frame)).size() > 1) return "SEVERAL_OCCURRENCES";
        if (toldApart.contains(coordinate) && (!lifts.containsKey(coordinate) || !lifts.get(coordinate).root())) {
            return "DISCRIMINATED";
        }
        var call = calls.get(coordinate);
        if (call == null) return "NO_CALL";
        var filled = fills.get(coordinate);
        if (filled == null || !filled.complete()) return "FILLS_INCOMPLETE";
        var built = builds.getOrDefault(coordinate, Map.of()).values();
        if (built.stream().anyMatch(r -> r instanceof BuildQuery.Result.Refused)) return "BUILD_REFUSED";
        if (filled.fills().stream().anyMatch(f -> f.landing().map(l -> FillEmitter.unreadThroughList(l,
                SessionIdentityRows.typeOf(f.parameterType()))).orElse(false))) {
            return "ARGUMENT_PATH";
        }
        var lift = Optional.ofNullable(lifts.get(coordinate))
            .map(row -> new CallEmitter.Lift(CENSUS_HOST, "lift", row.list()));
        if (CallEmitter.body(call, filled, builds.getOrDefault(coordinate, Map.of()), CENSUS_FILL, "census",
                frame.parent().map(ServiceEmission::frameKey), lift,
                Optional.ofNullable(polymorphic.get(coordinate)).map(row -> new CallEmitter.Polymorphic(row.list(),
                    row.participants().stream().map(p -> new CallEmitter.Polymorphic.Member(p.typeName(),
                        CENSUS_HOST, CENSUS_HOST, "lift")).toList())),
                validating.contains(coordinate) ? Optional.of(CENSUS_VALIDATION) : Optional.empty())
                instanceof CallEmitter.Result.NeedsBuilding) {
            return "CALL_REFUSED";
        }
        return EMITTED;
    }

    /**
     * What an emitted mutation's {@code @outbox} messages need: the store's row for the field, what
     * the companion takes from the generated runtime, the package its node-id encoder is under, and
     * the provider an owned transaction is opened through.
     */
    public record Outbox(OutboxQuery.Row row, OutboxEmitter.Runtime runtime, String outputPackage,
                         ClassName provider) {
        public Outbox {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(outputPackage, "outputPackage");
            Objects.requireNonNull(provider, "provider");
        }
    }

    /**
     * The fetcher of the field at {@code coordinate} named {@code fieldName}, or empty where it is not
     * emitted here, {@link #verdict} saying why.
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
        if (!emits(fieldCoordinate)) {
            return Optional.empty();
        }
        var occurrences = occurrencesOf(fieldCoordinate);
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
            var source = runtime.fetchersClass().nestedClass(sourceName(row.table()));
            nested.add(LiftEmitter.sourceTable(source, row.table(), row.key()).toBuilder()
                .addModifiers(javax.lang.model.element.Modifier.STATIC).build());
            String liftName = fieldName + "Lift";
            var told = discriminated.get(coordinate);
            methods.add(told != null
                ? LiftEmitter.discriminatedLift(row, told, liftName, source, runtime.projectionClass(),
                    runtime.selectionOccurrences())
                : LiftEmitter.lift(row, liftName, source, runtime.projectionClass().apply(row.targetType()),
                runtime.selectionOccurrences()));
            liftRef = Optional.of(new CallEmitter.Lift(runtime.fetchersClass(), liftName, row.list()));
        }
        // A record that may be a row of several members' tables is lifted by its own member's lift,
        // each row carrying its type's name and projecting only its type's occurrences.
        Optional<CallEmitter.Polymorphic> members = Optional.empty();
        var poly = polymorphic.get(coordinate);
        if (poly != null) {
            var each = new ArrayList<CallEmitter.Polymorphic.Member>();
            for (var participant : poly.participants()) {
                var source = runtime.fetchersClass().nestedClass(sourceName(participant.table()));
                nested.add(LiftEmitter.sourceTable(source, participant.table(), participant.key()).toBuilder()
                    .addModifiers(javax.lang.model.element.Modifier.STATIC).build());
                String liftName = fieldName + participant.typeName() + "Lift";
                var liftRow = new LiftQuery.Row(coordinate, participant.typeName(), true, true,
                    participant.table(), participant.key());
                methods.add(LiftEmitter.lift(liftRow, liftName, source,
                    runtime.projectionClass().apply(participant.typeName()), runtime.selectionOccurrences(),
                    Optional.of(participant.typeName())));
                each.add(new CallEmitter.Polymorphic.Member(participant.typeName(),
                    ClassName.bestGuess(participant.table().recordClassName()), runtime.fetchersClass(), liftName));
            }
            members = Optional.of(new CallEmitter.Polymorphic(poly.list(), each));
        }
        boolean validates = validating.contains(coordinate);
        var body = CallEmitter.body(call, fills.get(coordinate), builds.getOrDefault(coordinate, Map.of()),
            runtime.fill(), bodyName, frameKey, liftRef, members,
            validates ? Optional.of(runtime.validation()) : Optional.empty());
        if (!(body instanceof CallEmitter.Result.Emitted emitted)) {
            throw new IllegalStateException(coordinate + " is censused as emitted but its body is not, "
                + "though the census asks the same emitter: "
                + ((CallEmitter.Result.NeedsBuilding) body).reason());
        }
        boolean listed = liftRef.map(CallEmitter.Lift::list).orElse(members.map(CallEmitter.Polymorphic::list).orElse(false));
        TypeName valueType = liftRef.isPresent() || members.isPresent()
            ? (listed
                ? ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get("org.jooq", "Record"))
                : ClassName.get("org.jooq", "Record"))
            : answered(SessionIdentityRows.typeOf(call.resultType()).box(), frameKey.isPresent());
        String framed = bodyName;
        var written = new ArrayList<MethodSpec>();
        if (outbox.isPresent()) {
            if (frameKey.isPresent()) {
                throw new IllegalStateException(coordinate + " carries an @outbox under an object; the store"
                    + " admits one on a mutation field only");
            }
            framed = fieldName + "Written";
            written.addAll(written(outbox.get(), emitted.body(), framed, runtime.fetchersClass(), validates));
        }
        var frame = FrameEmitter.render(frameRow, fieldName, valueType, runtime.fetchersClass(),
            framed, runtime.frame(),
            outbox.isPresent() || needsContext(call, fills.get(coordinate), lift.isPresent() || members.isPresent()),
            validates, connection(coordinate, fieldCoordinate, runtime));
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
     */
    private static List<MethodSpec> written(Outbox outbox, MethodSpec body, String name, ClassName host,
                                            boolean validates) {
        TypeName answered = body.returnType();
        TypeName value = validates ? ((ParameterizedTypeName) answered).typeArguments().getFirst() : answered;
        var companion = OutboxEmitter.renderService(outbox.row(), value, outbox.outputPackage(), outbox.runtime());
        var dsl = ClassName.get("org.jooq.impl", "DSL");
        var lambda = CodeBlock.builder()
            .add("return $L{\n", OwnedScope.transactionResult(outbox.provider(), "dsl")).indent()
            .addStatement("$T answered = $T.$L(env, $T.using(tx))", answered, host, body.name(), dsl);
        if (validates) {
            var success = ((ParameterizedTypeName) answered).rawType().nestedClass("Success");
            lambda.beginControlFlow("if (answered instanceof $T<$T> success)", success, value.box())
                .add(OutboxEmitter.call(outbox.row(), "tx", "success.value()"))
                .endControlFlow();
        } else {
            lambda.add(OutboxEmitter.call(outbox.row(), "tx", "answered"));
        }
        lambda.addStatement("return answered").unindent().add("});\n");
        var wrapper = MethodSpec.methodBuilder(name)
            .addModifiers(javax.lang.model.element.Modifier.STATIC)
            .returns(answered)
            .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
            .addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl")
            .addException(Exception.class)
            .addJavadoc("Runs {@code $L} and writes its {@code @outbox} messages in one owned transaction.\n",
                body.name())
            .addCode(lambda.build())
            .build();
        return List.of(wrapper, companion);
    }

    /**
     * Whether the fetcher of the field at {@code fieldCoordinate} is emitted here: the field has
     * one occurrence with a frame, and its verdict is {@link #EMITTED}.
     */
    public boolean emits(String fieldCoordinate) {
        var occurrences = occurrencesOf(fieldCoordinate);
        return occurrences.size() == 1 && EMITTED.equals(verdict(occurrences.getFirst()));
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
     * The connection {@code coordinate}'s frame runs on, as the store states it: the request's own where
     * the store states none, as in a graph with no tenant column, else the routed, inherited or unscoped
     * key. An outcome the store gains later fails to compile here rather than falling into a known arm.
     */
    private FrameEmitter.Connection connection(String coordinate, String fieldCoordinate, Runtime runtime) {
        var row = connections.get(coordinate);
        if (row == null) {
            return new FrameEmitter.Connection.Request();
        }
        var routing = runtime.routing();
        return switch (row.outcome()) {
            case INHERIT -> new FrameEmitter.Connection.Inherited(routing.ownedConnections());
            case UNSCOPED -> new FrameEmitter.Connection.Unscoped(routing.ownedConnections(), routing.connectionKey());
            case ROUTED -> new FrameEmitter.Connection.Routed(
                ClassName.bestGuess(tenantType.orElseThrow(() -> new IllegalStateException(coordinate
                    + " is routed, but the graph's tenant columns agree on no one type, which the store refuses"))),
                routing.ownedConnections(), routing.connectionKey(),
                row.slots().stream().map(slot -> slotRead(slot, routing)).toList(),
                noTenant(row, fieldCoordinate, routing));
        };
    }

    /** What a routed call whose values name no tenant gets, as its tenant part states it. */
    private static FrameEmitter.NoTenant noTenant(ConnectionQuery.Row row, String fieldCoordinate, Routing routing) {
        return switch (row.whenEmpty().orElseThrow(() -> new IllegalStateException(row.coordinate()
            + " is routed with no tenant part"))) {
            case UNREACHABLE -> new FrameEmitter.NoTenant.Unreachable();
            case EMPTY -> new FrameEmitter.NoTenant.Empty(emptyValue(row));
            case REFUSE -> new FrameEmitter.NoTenant.Refuse(routing.clientException(),
                noTenantMessage(fieldCoordinate, row.slots().stream()
                    .map(slot -> slot.names().getFirst()).distinct().toList()));
        };
    }

    /**
     * The client-facing refusal for a call whose lists name no tenant:
     * {@code Mutation.rateFilms names no tenant: its tenant is read from the elements of 'in', which
     * is empty.}
     */
    private static String noTenantMessage(String coordinate, List<String> arguments) {
        var quoted = arguments.stream().map(a -> "'" + a + "'").toList();
        String names = quoted.size() == 1
            ? quoted.getFirst()
            : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.getLast();
        return coordinate + " names no tenant: its tenant is read from the elements of " + names
            + (quoted.size() == 1 ? ", which is empty." : ", which are empty.");
    }

    /** The producer's empty answer, as the store states it: an empty list, set or result. */
    private static CodeBlock emptyValue(ConnectionQuery.Row row) {
        var empty = row.empty().orElseThrow(() -> new IllegalStateException(row.coordinate()
            + " answers an empty call empty, but its producer states no empty answer"));
        return switch (empty.kind()) {
            case LIST -> CodeBlock.of("$T.of()", List.class);
            case SET -> CodeBlock.of("$T.of()", Set.class);
            case RESULT -> CodeBlock.of("$T.using($T.DEFAULT).newResult($T.$L)",
                ClassName.get("org.jooq.impl", "DSL"), ClassName.get("org.jooq", "SQLDialect"),
                ClassName.bestGuess(empty.tablesClass().orElseThrow()), empty.tableField().orElseThrow());
        };
    }

    /**
     * How one value the tenant is read from is read: the argument, and where it lies under an input
     * object, the path below it, read over every element of a list it crosses; and where it is a node
     * id, the tenant column's value out of each id.
     */
    private static CodeBlock slotRead(ConnectionQuery.Slot slot, Routing routing) {
        var names = slot.names();
        var read = names.size() == 1
            ? CodeBlock.of("env.getArgument($S)", names.getFirst())
            : CodeBlock.of("$T.tenantSlot(env.getArgument($S), $L)", routing.ownedConnections(), names.getFirst(),
                CodeBlock.join(names.subList(1, names.size()).stream().map(n -> CodeBlock.of("$S", n)).toList(),
                    ", "));
        if (slot.node().isEmpty()) {
            return read;
        }
        var node = slot.node().get();
        var tables = ClassName.bestGuess(node.tablesClass());
        var columns = CodeBlock.join(node.keyColumns().stream()
            .map(c -> CodeBlock.of("$T.$L.$L", tables, node.tableField(), c)).toList(), ", ");
        return CodeBlock.of("$T.mapTenantLeaves($L, id -> id instanceof $T s ? $T.decodeColumns($S, s, $L)[$L] : null)",
            routing.ownedConnections(), read, String.class, routing.nodeIdEncoder(), node.typeId(), columns,
            slot.keyPosition().orElseThrow());
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
