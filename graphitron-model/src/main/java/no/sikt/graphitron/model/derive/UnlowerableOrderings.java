package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_NAVIGATED_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_UNLOWERABLE_ORDERING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

/**
 * The never-unsorted invariant's honesty half, projected from the store: a coordinate where an
 * ordering is <em>available</em> and the coordinate's own read shape cannot <em>deliver</em> it
 * is a row of {@code intent_field_unlowerable_ordering}, and this class derives the located
 * {@link ValidationError} values from those rows. The comparison lives in the view's SQL; what
 * remains here is the population this consumer asks about, the join that names the multitable
 * container's participants, and the mint of the {@link Rejection} the report carries.
 *
 * <p>The rule is keyed on availability rather than on declaration, and that is what makes the two
 * arms one comparison instead of two rules. A declared ordering on a field returning a multitable
 * container is accepted today and lowered onto nothing, the read being one statement per
 * participant combined on a synthetic key. A list-returning {@code @routine} write has the target
 * table's primary key available and delivers no order at all, its visible rows being the captured
 * keys re-read by a keyed {@code SELECT} that sorts by nothing. Both are the same comparison, with
 * the fallback standing where the declaration usually does.
 *
 * <p>What it is not is an unsortedness rule, and the boundary decides the surprising absence. A
 * multitable read carrying <em>no</em> declaration mints nothing: the polymorphic emitter orders
 * the combined result on a synthetic key built from each participant's primary key, so what is
 * available is exactly what is delivered and the invariant holds. Whether an ordering is available
 * at all is a different question, and not this class's.
 *
 * <p>{@link #rejectionOf} is the one mint of this family's {@link Rejection}, shared with
 * {@link UnlowerableOrderingRejectionRows}, the capture-cadence writer that stores the same value
 * for the diagnostics surface to read as plain columns, so the build's error stream and an editor
 * cannot word one violation two ways. It is a total switch over the view's verdict vocabulary with
 * no {@code default}, and one of its two arms deliberately mints nothing: see the switch.
 *
 * <p>Locations are the view's, which are the declaring directive's own rather than the field's, so
 * an editor underlines what the author wrote. Only the fallback route has no directive to point at,
 * and it carries the field's position instead.
 *
 * <p>The view itself is total over the schema's coordinates. This class is the <em>build-error</em>
 * consumer of it, so the population it asks about is the classification domain: only a coordinate
 * the generator intends to classify can fail a build, and a declaration outside that domain costs
 * no emitted source. The editor's diagnostic arm asks a different question of the same rows and
 * joins nothing, which is why the filter lives here rather than in the view.
 */
public final class UnlowerableOrderings {

    private UnlowerableOrderings() {}

    /** Which read shape cannot honour the ordering: the view's own closed pair. */
    public enum Verdict {
        /**
         * The coordinate returns a multitable container, so its read is one statement per
         * participant and the branches are combined on a synthetic key. Nothing carries a
         * declaration down onto a branch.
         */
        PARTICIPANT_FAN_OUT,
        /**
         * A mutation-root {@code @routine} write returning a list: the routine's returned keys are
         * captured and the rows re-read by key, and neither step sorts.
         */
        KEY_CAPTURE_SCATTER;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the unlowerable-ordering view produced verdict '" + verdict + "', which no "
                    + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /** Which route made an ordering available at the coordinate: the view's own closed triple. */
    public enum AvailableVia {
        /** A field-level {@code @defaultOrder}. */
        DEFAULT_ORDER,
        /** An {@code @orderBy} on one of the field's arguments. */
        ORDER_BY_ARGUMENT,
        /**
         * The target table's primary key, which the ordering resolution supplies where nothing is
         * written. Inert on {@link Verdict#PARTICIPANT_FAN_OUT} by construction, that shape's own
         * precondition being that the navigated container binds no table at all.
         */
        PRIMARY_KEY_FALLBACK;

        /** The route a store row carries; an unknown value is vocabulary drift, a build bug. */
        static AvailableVia of(String route) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(route))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the unlowerable-ordering view produced availability route '" + route
                    + "', which no " + AvailableVia.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /**
     * One coordinate-and-route row of the view with what its message needs joined onto it: the
     * container the coordinate navigates as, its SDL kind, and the participants the read fans out
     * over. The container facts are absent on the write arm, which names neither.
     */
    public record Unlowered(String typeName, String fieldName, Verdict verdict,
                            AvailableVia availableVia, String argumentName,
                            String containerName, String containerKind, List<String> participants,
                            SourceLocation location) {

        public Unlowered {
            participants = List.copyOf(participants);
        }

        /** The coordinate the minted error attaches to. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    /**
     * The detection pass's typed product: every unlowerable-ordering row of the population the
     * build-error consumer asks about, minting arms and held arms alike. {@link #violations()} is
     * the error stream every caller reads; the rows are kept beside it so a consumer wanting the
     * coordinates and their routes has them without re-parsing a message.
     */
    public record Detection(List<Unlowered> unlowered) {

        public Detection {
            unlowered = List.copyOf(unlowered);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /**
         * Every violation the detection minted, in coordinate-and-route order. Fewer than the rows:
         * a verdict whose rejection is held mints none, so a coordinate can be counted here and
         * worded nowhere.
         */
        public List<ValidationError> violations() {
            return unlowered.stream()
                .flatMap(u -> rejectionOf(u).stream()
                    .map(r -> ValidationError.forField(u.coordinate(), r, u.location())))
                .toList();
        }
    }

    /**
     * Projects the build-error population over {@code graphName}'s partition: the view's rows whose
     * owning type is a member of the classification domain. Empty for every graph whose orderings
     * all reach the SQL they were written for, and for any graph whose domain rows were never
     * derived.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(read(dsl, graphName, inDomain(graphName)));
    }

    /**
     * The whole view over {@code graphName}'s partition, ungated. What the rejection-rows writer
     * reads, on the reasoning the view's own comment gives: a declaration that reaches no SQL is
     * that whatever the emitted surface does with the coordinate, and a type no field reaches is
     * where an author most needs the signal.
     */
    public static List<Unlowered> rows(DSLContext dsl, String graphName) {
        return read(dsl, graphName, noCondition());
    }

    /**
     * The build-error consumer's population: the coordinate's owning type is a member of the
     * classification domain.
     */
    private static Condition inDomain(String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var v = INTENT_FIELD_UNLOWERABLE_ORDERING;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(v.TYPE_NAME)));
    }

    /**
     * The view under {@code population}, with the container facts and the participant list joined
     * on. Two statements and not one per row: the participant names are a second grain, so they are
     * read once for the whole partition's participant-fan-out coordinates and indexed by
     * coordinate.
     */
    private static List<Unlowered> read(DSLContext dsl, String graphName, Condition population) {
        var v = INTENT_FIELD_UNLOWERABLE_ORDERING;
        var nv = INTENT_FIELD_NAVIGATED_TYPE;
        var t = GRAPHQL_TYPE;
        var participants = participantsOf(dsl, graphName);
        return dsl.select(v.TYPE_NAME, v.FIELD_NAME, v.VERDICT, v.AVAILABLE_VIA, v.ARGUMENT_NAME,
                v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN, nv.NAVIGATED_TYPE_NAME, t.KIND)
            .from(v)
            .join(nv).on(nv.GRAPH_NAME.eq(v.GRAPH_NAME), nv.TYPE_NAME.eq(v.TYPE_NAME),
                nv.FIELD_NAME.eq(v.FIELD_NAME))
            .leftJoin(t).on(t.GRAPH_NAME.eq(nv.GRAPH_NAME), t.TYPE_NAME.eq(nv.NAVIGATED_TYPE_NAME))
            .where(v.GRAPH_NAME.eq(graphName), population)
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.AVAILABLE_VIA, v.ARGUMENT_NAME)
            .fetch(row -> new Unlowered(row.value1(), row.value2(), Verdict.of(row.value3()),
                AvailableVia.of(row.value4()), row.value5(),
                row.value9(), row.value10(),
                participants.getOrDefault(row.value1() + "." + row.value2(), List.of()),
                location(row.value6(), row.value7(), row.value8())));
    }

    /**
     * The participants each fan-out coordinate's read is one statement per, in name order. Joined
     * to the view rather than read whole, so the participant relation is asked only about the
     * coordinates the rule already named.
     */
    private static Map<String, List<String>> participantsOf(DSLContext dsl, String graphName) {
        var p = INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
        var v = INTENT_FIELD_UNLOWERABLE_ORDERING;
        var out = new LinkedHashMap<String, List<String>>();
        dsl.selectDistinct(p.TYPE_NAME, p.FIELD_NAME, p.MEMBER_TYPE_NAME)
            .from(p)
            .join(v).on(v.GRAPH_NAME.eq(p.GRAPH_NAME), v.TYPE_NAME.eq(p.TYPE_NAME),
                v.FIELD_NAME.eq(p.FIELD_NAME))
            .where(p.GRAPH_NAME.eq(graphName),
                v.VERDICT.eq(Verdict.PARTICIPANT_FAN_OUT.name()))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.MEMBER_TYPE_NAME)
            .forEach(row -> out
                .computeIfAbsent(row.value1() + "." + row.value2(), key -> new ArrayList<>())
                .add(row.value3()));
        return out;
    }

    /**
     * Mints the {@link Rejection} this family carries at one rejected route, or nothing where the
     * arm's rejection is held. The one home for the message, shared with the writer that mints it
     * into the store, so the report and the diagnostics surface cannot word one violation two ways.
     *
     * <p>A total switch over the view's verdict vocabulary, and the empty arm is a measurement
     * rather than a preference. The only live instance of a list-returning {@code @routine} write
     * is in graphitron's own example schema, so wording the rejection reddens this reactor's
     * verification build until that write's second step carries an order to deliver. What the held
     * arm still delivers is its view row: the coordinate sits in a cell that can reject it, and the
     * unit test that asserts the row exists turns a lowering landing upstream into a failing test
     * rather than a quietly empty population.
     *
     * <p>{@link Rejection#deferred} and not an invalid-schema arm: the schema is well formed, the
     * directive is real, and the remedy is to drop the declaration or to wait for the lowering. A
     * deferred rejection still fails the build, so the outcome is a stopped build either way; what
     * the arm buys is that an editor triages it correctly and that the row leaves the population
     * the moment a lowering lands.
     */
    public static Optional<Rejection> rejectionOf(Unlowered row) {
        return switch (row.verdict()) {
            case PARTICIPANT_FAN_OUT -> Optional.of(Rejection.deferred(fanOutMessage(row)));
            case KEY_CAPTURE_SCATTER -> Optional.empty();
        };
    }

    /**
     * The fan-out arm's prose: what the author declared, why the read cannot carry it, and both
     * remedies. The participants are named because the sentence has to say what "one statement per
     * participant" means at this coordinate, and they come off the relation's own rows rather than
     * from a string assembled in SQL.
     */
    private static String fanOutMessage(Unlowered row) {
        String container = "'" + row.containerName() + "'";
        String kindWord = "UNION".equals(row.containerKind()) ? "union" : "interface";
        String read = "A field returning the multitable " + kindWord + " " + container
            + " is read as one statement per participant ("
            + String.join(", ", row.participants())
            + ") and the results are combined on a synthetic key, so ";
        return switch (row.availableVia()) {
            case DEFAULT_ORDER -> "@defaultOrder declares an ordering this field cannot honour. "
                + read + "the declared columns are not applied and rows arrive in participant"
                + " primary-key order. Remove the declaration, or return a single @table type.";
            case ORDER_BY_ARGUMENT -> "argument '" + row.argumentName() + "' carries @orderBy,"
                + " which asks for an ordering this field cannot honour. " + read
                + "a client-supplied order is not applied and rows arrive in participant"
                + " primary-key order. Remove the argument's @orderBy, or return a single @table"
                + " type.";
            case PRIMARY_KEY_FALLBACK -> throw new IllegalStateException(
                "the unlowerable-ordering view minted a primary-key fallback route at '"
                + row.coordinate() + "', whose read fans out over a container binding no table;"
                + " the view's inert pairings and this decode must move together");
        };
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
