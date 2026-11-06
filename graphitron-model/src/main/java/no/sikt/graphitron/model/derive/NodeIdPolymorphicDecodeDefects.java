package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The polymorphic {@code @nodeId} rules, projected from the store: a {@code typeName:} naming a
 * multitable interface or union either reaches a {@code @service} slot whose declared Java type every
 * member's generated record is, in which case the decode is carried out and the store carries a
 * {@code POLYMORPHIC_RECORD} destination, or it is one of the six refusals below. The refusals are
 * entry defects at the {@code @nodeId}, which the graphitron rule picks between on the coordinate,
 * the member set and the slot's type and shape, a per-member verdict naming its members in
 * {@code graphitron_entry_defect_member}; what remains here is the decode of those codes into
 * {@link Rejection} arms and the prose they carry, one per member where a verdict names members. At
 * a list argument the slot is a {@code java.util.List} and its element is what every member's record
 * has to be.
 *
 * <p>A sibling of {@link NodeIdDecodeDefects} rather than more arms on it: that family's verdicts are
 * a function of the slot's shape and the node key's arity, and these six are decided on the
 * container's members. The shape verdict's code is shared, each family composing its own remedy for
 * its own population, and the two populations are disjoint by construction, a container's id being
 * judged as a container and never against a single node's key.
 *
 * <p>Every arm is {@link Rejection.AuthorError.Structural} and none is a deferral. Each of the six
 * is fixed in the author's own schema or signature: name a type instead of the container, annotate a
 * member with {@code @node}, declare the slot one rung wider, or move the directive to a coordinate
 * the rule reaches. No arm here fails while promising an emitter later.
 *
 * <p>Every such defect fails the build: the rule judges occurrences, so a defect is one the generator
 * reaches.
 */
public final class NodeIdPolymorphicDecodeDefects {

    private NodeIdPolymorphicDecodeDefects() {}

    /**
     * Every relation this component's statements name.
     */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT_SITE,
        GRAPHITRON_ENTRY_DEFECT_MEMBER, GRAPHITRON_BINDING_CLAIM, GRAPHITRON_TYPE, GRAPHITRON_NODE,
        GRAPHITRON_NODE_ID_SLOT, GRAPHITRON_OCCURRENCE, GRAPHITRON_ARGUMENT, GRAPHQL_ARGUMENT,
        GRAPHQL_POLY_MEMBER, GRAPHITRON_TABLETYPE, SQL_TABLE);

    /** Which precondition stopped the polymorphic decode, in the view's own closed vocabulary. */
    private enum Verdict {
        /**
         * The use site draws no slot row, so the instruction's value binds a table predicate or is
         * encoded rather than descending into Java. The coordinate half of the rule: an output
         * field, a read-side argument or filter input on a generated fetch field, and a
         * {@code @service} argument no parameter is fed from all read as that absence.
         */
        CONTAINER_NOT_AT_A_SLOT,
        /**
         * The container carries {@code @table} itself, so every member shares one record class and
         * one key. Both remedies are the author's and the message offers both, a second spelling of
         * the single-type decode being a trap rather than a convenience.
         */
        SINGLE_TABLE_CONTAINER,
        /** No member binds a table, so there is nothing to decode an id into. */
        NO_TABLE_MEMBERS,
        /**
         * One table-bound member is not a node type. Refused rather than dropped from the candidate
         * set: a set with a hole in it would reject that member's ids at runtime with nothing in the
         * build having said so.
         */
        MEMBER_NOT_NODE_TYPE,
        /**
         * One member's generated record is not the slot's declared type. Fires only where the store
         * types the slot the author annotated, which is a producer parameter; at an input-field site
         * the slot relation types the bean and the walk refuses the member on its own.
         */
        SLOT_NOT_SUPERTYPE_OF_MEMBER,
        /**
         * The argument's list-ness and the slot's disagree on terms the emitter cannot fill: a list
         * argument at a parameter that is not a {@code java.util.List}, a single id at a
         * multi-valued one, or a list nested deeper than one level. About the slot and not the
         * container, so a container verdict on the same coordinate still fires beside it.
         */
        SLOT_SHAPE_MISMATCH;

        /** The entry defect code the verdict is written under. */
        String code() {
            return "NODE_ID_" + name();
        }

        static Verdict of(String code) {
            return Arrays.stream(values())
                .filter(v -> v.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the polymorphic node id decode defects produced code '" + code
                    + "', which no " + Verdict.class.getSimpleName()
                    + " value names; the rule and the enum must move together"));
        }
    }

    /**
     * The detection pass's typed product: one entry per refused instruction, use site and, on the two
     * member verdicts, member. {@link #violations()} is the error stream every caller reads; the
     * entries are kept beside it so a consumer wanting the coordinates has them without re-parsing a
     * message.
     */
    public record Detection(List<Defect> defects) {

        public Detection {
            defects = List.copyOf(defects);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation the detection minted, in coordinate order. */
        public List<ValidationError> violations() {
            return defects.stream()
                .map(d -> ValidationError.forField(d.coordinate(), d.rejection(), d.location()))
                .toList();
        }
    }

    /**
     * One refused instruction: the coordinate the error attaches to, the container the author named,
     * the member the verdict is about where it is about one, and the typed rejection. The container
     * rides along because a consumer grouping refusals by the identity that could not be decoded
     * would otherwise recover it from prose.
     */
    public record Defect(String coordinate, String containerName, String memberTypeName,
                         Rejection rejection, SourceLocation location) {}

    /** The slot a container's id lands in, at one declared argument or input field. */
    private record Slot(String paramName, String slotJavaType, Integer listDepth) {}

    /** The graph's refusals of an id naming a container, in type, field, coordinate and code order. */
    public static Detection detect(DSLContext dsl, String graphName) {
        var e = GRAPHITRON_ENTRY_DEFECT_SITE;
        var cl = GRAPHITRON_BINDING_CLAIM;
        var t = GRAPHITRON_TYPE;
        var codes = Arrays.stream(Verdict.values()).map(Verdict::code).toList();
        var slots = slots(dsl, graphName);
        var members = members(dsl, graphName);
        var out = new ArrayList<Defect>();
        dsl.select(e.TYPE_NAME, e.FIELD_NAME, e.COORDINATE, e.CODE, cl.NAMED, t.KIND,
                e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN)
            .from(e)
            .join(cl).on(cl.GRAPH_NAME.eq(e.GRAPH_NAME), cl.COORDINATE.eq(e.COORDINATE),
                cl.KIND.eq("NODE_ID"))
            .join(t).on(t.GRAPH_NAME.eq(cl.GRAPH_NAME), t.TYPE_NAME.eq(cl.NAMED))
            .where(e.GRAPH_NAME.eq(graphName), e.CODE.in(codes), t.KIND.in("INTERFACE", "UNION"),
                notExists(selectOne().from(GRAPHITRON_NODE).where(
                    GRAPHITRON_NODE.GRAPH_NAME.eq(t.GRAPH_NAME),
                    GRAPHITRON_NODE.TYPE_NAME.eq(t.TYPE_NAME))))
            .orderBy(e.TYPE_NAME, e.FIELD_NAME, e.COORDINATE, e.CODE)
            .forEach(row -> {
                var verdict = Verdict.of(row.value4());
                String coordinate = row.value1() + "." + row.value2();
                String container = row.value5();
                var slot = slots.getOrDefault(row.value3(), new Slot(null, null, null));
                var location = new SourceLocation(row.value8(), row.value9(), row.value7());
                List<String> named = containerMembers(dsl, graphName, container);
                var namedMembers = defectMembers(dsl, graphName, row.value7(), row.value8(),
                    row.value9(), row.value4());
                if (namedMembers.isEmpty()) {
                    out.add(new Defect(coordinate, container, null,
                        rejectionOf(verdict, container, row.value6(), null, null,
                            slot.slotJavaType(), slot.listDepth(), slot.paramName(), row.value3(),
                            named),
                        location));
                }
                for (String member : namedMembers) {
                    out.add(new Defect(coordinate, container, member,
                        rejectionOf(verdict, container, row.value6(), member, members.get(member),
                            slot.slotJavaType(), slot.listDepth(), slot.paramName(), row.value3(),
                            named),
                        location));
                }
            });
        return new Detection(out);
    }

    /** Each declared argument or input field's slot, the least parameter where uses differ. */
    private static Map<String, Slot> slots(DSLContext dsl, String graphName) {
        var s = GRAPHITRON_NODE_ID_SLOT;
        var o = GRAPHITRON_OCCURRENCE;
        var a = GRAPHITRON_ARGUMENT;
        var g = GRAPHQL_ARGUMENT;
        var out = new HashMap<String, Slot>();
        dsl.select(s.ELEMENT_COORDINATE, s.PARAMETER_NAME, s.LANDING_TYPE, s.SLOT_SHAPE, g.LIST_DEPTH)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.ID_COORDINATE))
            .join(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME),
                a.COORDINATE.eq(coalesce(o.ARGUMENT_COORDINATE, o.COORDINATE)))
            .join(g).on(g.GRAPH_NAME.eq(a.GRAPH_NAME), g.TYPE_NAME.eq(a.TYPE_NAME),
                g.FIELD_NAME.eq(a.FIELD_NAME), g.ARGUMENT_NAME.eq(a.ARGUMENT_NAME))
            .where(s.GRAPH_NAME.eq(graphName))
            .orderBy(s.ELEMENT_COORDINATE, s.PARAMETER_NAME)
            .forEach(r -> out.putIfAbsent(r.value1(), new Slot(r.value2(),
                "LIST".equals(r.value4()) ? "java.util.List" : r.value3(), r.value5())));
        return out;
    }

    /** Each table-bound type's generated record, by type name. */
    private static Map<String, String> members(DSLContext dsl, String graphName) {
        var tt = GRAPHITRON_TABLETYPE;
        var r = SQL_TABLE;
        var out = new HashMap<String, String>();
        dsl.select(tt.TYPE_NAME, r.RECORD_CLASS_FQN)
            .from(tt)
            .join(r).on(r.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), r.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                r.TABLE_NAME.eq(tt.TABLE_NAME))
            .where(tt.GRAPH_NAME.eq(graphName))
            .forEach(row -> out.put(row.value1(), row.value2()));
        return out;
    }

    /** The members one defect names, in name order. */
    private static List<String> defectMembers(DSLContext dsl, String graphName, String sourceName,
                                              int line, int column, String code) {
        var m = GRAPHITRON_ENTRY_DEFECT_MEMBER;
        return dsl.select(m.MEMBER_TYPE_NAME)
            .from(m)
            .where(m.GRAPH_NAME.eq(graphName), m.SOURCE_NAME.eq(sourceName), m.SOURCE_LINE.eq(line),
                m.SOURCE_COLUMN.eq(column), m.CODE.eq(code))
            .orderBy(m.MEMBER_TYPE_NAME)
            .fetch(r -> r.value1());
    }

    /** A container's table-bound members, in the order the schema declares them. */
    private static List<String> containerMembers(DSLContext dsl, String graphName, String containerName) {
        var p = GRAPHQL_POLY_MEMBER;
        var tt = GRAPHITRON_TABLETYPE;
        return dsl.select(p.MEMBER_TYPE_NAME)
            .from(p)
            .join(tt).on(tt.GRAPH_NAME.eq(p.GRAPH_NAME), tt.TYPE_NAME.eq(p.MEMBER_TYPE_NAME))
            .where(p.GRAPH_NAME.eq(graphName), p.CONTAINER_NAME.eq(containerName))
            .orderBy(p.POSITION)
            .fetch(r -> r.value1());
    }

    private static Rejection rejectionOf(Verdict verdict, String containerName, String containerKind,
                                         String memberTypeName, String memberRecordClass,
                                         String slotJavaType, Integer argumentListDepth,
                                         String paramName, String useSite, List<String> members) {
        String lead = "@nodeId(typeName: \"" + containerName + "\") names "
            + ("UNION".equals(containerKind) ? "a union" : "an interface");
        String supertypes = "org.jooq.Record, TableRecord<?>, or UpdatableRecord<?> where every"
            + " implementation's table has a primary key";
        return switch (verdict) {
            case CONTAINER_NOT_AT_A_SLOT -> Rejection.structural(lead
                + ", and a polymorphic node id is decoded into a @service slot typed as a record"
                + " supertype. At '" + useSite + "' the value binds a table predicate or is encoded"
                + " rather than reaching Java, so there is no slot to decode into; name one node"
                + " type here, or move the polymorphic spelling to the @service input that receives"
                + " the id");
            case SINGLE_TABLE_CONTAINER -> Rejection.structural(lead
                + " that carries @table itself, so every implementation shares one record class and"
                + " one key and there is nothing to dispatch on. Name one of its object types"
                + " instead, or make '" + containerName + "' a @node type; the single-type decode is"
                + " what you get either way");
            case NO_TABLE_MEMBERS -> Rejection.structural(lead
                + " with no @table implementations, so there is nothing to decode an id into. Give"
                + " the implementations a @table binding, or name a node type here");
            case MEMBER_NOT_NODE_TYPE -> Rejection.structural(lead
                + " whose implementation '" + memberTypeName + "' is not a @node type. Every @table"
                + " implementation of a polymorphic node id must be a node type, or ids of that"
                + " implementation would be rejected at runtime with nothing in the build saying so;"
                + " annotate '" + memberTypeName + "' with @node. Implementations: "
                + String.join(", ", members));
            case SLOT_NOT_SUPERTYPE_OF_MEMBER -> Rejection.structural(lead
                + " and decodes an id of any of: " + String.join(", ", members) + ", but parameter '"
                + paramName + "' takes " + simpleName(slotJavaType) + ", which "
                + simpleName(memberRecordClass) + " (implementation '" + memberTypeName + "') is"
                + " not. Declare the parameter as a type every implementation's record is"
                + " (" + supertypes + "), or point typeName: at one implementation instead of at '"
                + containerName + "'");
            case SLOT_SHAPE_MISMATCH -> Rejection.structural(lead
                + shapeMismatch(paramName, slotJavaType, argumentListDepth, supertypes));
        };
    }

    /**
     * The shape arm's clause after the lead, in the direction the argument and the slot disagree. A
     * list argument is handed over as one {@code java.util.List}, so the slot has to be one whose
     * element every implementation's record is; a single id decodes to one record, so a
     * multi-valued slot is the wrong shape for it.
     */
    private static String shapeMismatch(String paramName, String slotJavaType,
                                        Integer argumentListDepth, String supertypes) {
        int depth = argumentListDepth == null ? 0 : argumentListDepth;
        String takes = "parameter '" + paramName + "' takes " + simpleName(slotJavaType);
        String listOf = "Declare the parameter as a java.util.List of a type every implementation's"
            + " record is (" + supertypes + ")";
        if (depth > 1) {
            return ", but the argument is a list of lists, and a list argument is decoded only one"
                + " level deep. Make the argument a list of IDs. " + listOf;
        }
        if (depth == 1) {
            return ", and the argument is a list, so the decoded ids are handed over as a"
                + " java.util.List, but " + takes + ". " + listOf;
        }
        return ", but " + takes + ("java.util.List".equals(slotJavaType)
                ? ", which a list argument fills with one decoded record per id"
                : ", which holds many values where one id decodes to one record")
            + ". Declare the parameter as a type every implementation's record is (" + supertypes
            + "), or make the argument a list";
    }
}

