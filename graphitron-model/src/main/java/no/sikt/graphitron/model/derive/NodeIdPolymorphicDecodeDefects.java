package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_CONTAINER_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The polymorphic {@code @nodeId} rules, projected from the store: a {@code typeName:} naming a
 * multitable interface or union either reaches a {@code @service} slot whose declared Java type every
 * member's generated record is, in which case the decode is carried out and the store carries a
 * {@code POLYMORPHIC_RECORD} destination, or it is one of the five refusals below. The reduction
 * lives in {@code intent_node_id_polymorphic_decode_defect}, which picks between them on the
 * coordinate, the member set and the slot's type; what remains here is the decode of that view's
 * closed five-verdict vocabulary into {@link Rejection} arms and the prose they carry.
 *
 * <p>A sibling of {@link NodeIdDecodeDefects} rather than more arms on it, for the reason the two
 * views are siblings: that family's two verdicts are a function of the node key's arity, and these
 * five are decided on facts that relation does not read. The two populations are disjoint by
 * construction, the incumbent joining the key shape on the slot's resolved type and a container
 * resolving no key of its own.
 *
 * <p>Every arm is {@link Rejection.AuthorError.Structural} and none is a deferral. Each of the five
 * is fixed in the author's own schema or signature: name a type instead of the container, annotate a
 * member with {@code @node}, declare the slot one rung wider, or move the directive to a coordinate
 * the rule reaches. No arm here fails while promising an emitter later.
 *
 * <p>The population is the view's and this class narrows it once more, on the terms
 * {@link AuthoredClaimConflicts} settled and {@link NodeIdDecodeDefects} restates: the view states
 * its whole predicate and no consumer's filter, and this is the <em>build-error</em> consumer, so it
 * joins {@code intent_type_domain} on the refused coordinate's owning type. The editor's diagnostic
 * arm asks the same rows a different question and joins nothing, a refused instruction at an
 * unreached coordinate being exactly where an author most needs to be told, which is also what makes
 * {@code CONTAINER_NOT_AT_A_SLOT} worth stating at all.
 */
public final class NodeIdPolymorphicDecodeDefects {

    private NodeIdPolymorphicDecodeDefects() {}

    /**
     * Every relation this component's statements name. {@code memberNames} reads the two membership
     * views once per container, which is a read of each body per row.
     */
    public static final Set<Table<?>> READS = Set.of(INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT,
        INTENT_TYPE_DOMAIN, INTENT_NODE_CONTAINER_MEMBER, INTENT_POLY_MEMBER);

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
        SLOT_NOT_SUPERTYPE_OF_MEMBER;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the polymorphic node id decode defect view produced verdict '" + verdict
                    + "', which no " + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
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

    /**
     * Projects every refused polymorphic decode over {@code graphName}'s emitted partition. Empty for
     * a graph whose container-naming {@code @nodeId}s all reach slots that can take them, and for one
     * whose refusals all sit outside the classification domain.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var v = INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
        return new Detection(dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), inDomain(graphName))
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.USE_SITE, v.VERDICT, v.MEMBER_TYPE_NAME)
            .fetch(row -> new Defect(
                row.getTypeName() + "." + row.getFieldName(),
                row.getContainerName(), row.getMemberTypeName(),
                rejectionOf(Verdict.of(row.getVerdict()), row.getContainerName(),
                    row.getContainerKind(), row.getMemberTypeName(), row.getMemberRecordClass(),
                    row.getSlotJavaType(), row.getParamName(), row.getUseSite(),
                    memberNames(dsl, graphName, row.getContainerName())),
                location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()))));
    }

    /**
     * The build-error consumer's population: the refused coordinate's owning type is a member of the
     * classification domain. The same predicate both grains of the sibling family use, and located on
     * the owning type because the refused slot sits on that type's field.
     */
    private static Condition inDomain(String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var v = INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(v.TYPE_NAME)));
    }

    /**
     * The container's table-bound members, in source order: the candidate list a message quotes. Read
     * as rows off the member relation rather than assembled from prose, so a message names the very
     * set the resolution axis draws its candidates from.
     */
    private static List<String> memberNames(DSLContext dsl, String graphName, String containerName) {
        var m = INTENT_NODE_CONTAINER_MEMBER;
        var p = INTENT_POLY_MEMBER;
        return dsl.select(m.MEMBER_TYPE_NAME)
            .from(m)
            .join(p).on(p.GRAPH_NAME.eq(m.GRAPH_NAME), p.CONTAINER_NAME.eq(m.CONTAINER_NAME),
                p.MEMBER_TYPE_NAME.eq(m.MEMBER_TYPE_NAME))
            .where(m.GRAPH_NAME.eq(graphName), m.CONTAINER_NAME.eq(containerName),
                m.IS_TABLE_BOUND.isTrue())
            .orderBy(p.POSITION)
            .fetch(r -> r.value1());
    }

    /**
     * Decodes one verdict into the rejection the report carries. Each message states the operands the
     * view compared and nothing it did not, and each names the remedy the verdict's own precedence
     * makes available: outward in, the coordinate first, then the container, then the member.
     */
    private static Rejection rejectionOf(Verdict verdict, String containerName, String containerKind,
                                         String memberTypeName, String memberRecordClass,
                                         String slotJavaType, String paramName, String useSite,
                                         List<String> members) {
        String lead = "@nodeId(typeName: \"" + containerName + "\") names "
            + ("UNION".equals(containerKind) ? "a union" : "an interface");
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
                + " (org.jooq.Record, TableRecord<?>, or UpdatableRecord<?> where every"
                + " implementation's table has a primary key), or point typeName: at one"
                + " implementation instead of at '" + containerName + "'");
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
