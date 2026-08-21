package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.keyColumnsOf;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.nodeIdSpelling;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The {@code @nodeId} decode rules at a producer parameter, projected from the store: an argument
 * carrying a decoding {@code @nodeId} whose value a producer method's parameter of that name
 * receives either has its decode carried out, with a row in {@code intent_node_id_decode}, or is one
 * of the two refusals below. The reduction lives in {@code intent_node_id_decode_defect}, which
 * picks between them on the node key's arity in one pass; what remains here is the decode of that
 * view's closed two-verdict vocabulary into {@link Rejection} arms and the prose they carry.
 *
 * <p>These two arms close the last silence the directive had. An author annotated an argument
 * {@code @nodeId}, the schema walk's type gate stood aside because a decoded value and the
 * {@code ID}'s coercion output never meet, and the opaque wire string then reached the consumer's own
 * parameter with nothing in the build saying a word. The gate was opened deliberately and nothing was
 * standing behind it.
 *
 * <p>Both arms are {@link Rejection.AuthorError.Structural} and neither is a deferral. A composite
 * key at a parameter holding one value and a type disagreement at the sole key column are each fixed
 * in one line of the author's own signature, or by binding the argument onto a key column with
 * {@code argMapping}, and no arm here fails while promising an emitter later.
 *
 * <p>The population is the view's and this class narrows it once more, on the terms
 * {@link AuthoredClaimConflicts} settled: the view states its whole predicate and no consumer's
 * filter, and this is the <em>build-error</em> consumer, so it joins {@code intent_type_domain} on
 * the refused coordinate's owning type. Only a coordinate the generator intends to classify can fail
 * a build. The editor's diagnostic arm asks a different question of the same rows and joins nothing,
 * a refused instruction at an unreached coordinate being exactly where an author most needs to be
 * told, which is why the filter lives here rather than in the view.
 *
 * <p>The wording converges with {@link ArgmappingProjectionDefects} rather than being renegotiated:
 * that family refuses the same two facts one carrier over, where an {@code argMapping} entry binds
 * the node id instead of a parameter name matching it, and the shared vocabulary lives in
 * {@link NodeIdMessages}. What differs is the remedy, and it differs because the carrier does: an
 * author who wrote an {@code argMapping} entry is told about their entry, and an author who wrote
 * none is told about the name match that found the parameter, which is the provenance the slot
 * relation's {@code carrier} column exists to make sayable.
 */
public final class NodeIdDecodeDefects {

    private NodeIdDecodeDefects() {}

    /** Which precondition stopped the decode, in the view's own closed vocabulary. */
    private enum Verdict {
        /**
         * The node type's key is more than one column and the parameter holds one value. Refused
         * whether or not the census could type the parameter: what the verdict needs is whether the
         * parameter is the tuple's own row type, and a position naming no class is a primitive or a
         * type variable, neither of which is a generated record.
         */
        KEY_ARITY_EXCEEDS_SLOT,
        /**
         * One key column, and the Java type jOOQ binds it as is not the parameter's. The verdict
         * whose two operands are both types, which is why it fires only where both are known: a
         * column no catalog could type and a parameter no census could type leave the decode to be
         * carried out on arity alone with javac as the backstop, refusing on an operand nobody could
         * read being a new silence rather than the closing of one.
         */
        KEY_COLUMN_TYPE_DISAGREEMENT;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the node id decode defect view produced verdict '" + verdict + "', which no "
                    + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /**
     * The detection pass's typed product: one entry per refused instruction and use site.
     * {@link #violations()} is the error stream every caller reads; the entries are kept beside it so
     * a consumer wanting the coordinates has them without re-parsing a message.
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
     * One refused instruction: the coordinate the error attaches to, the argument the author
     * annotated, the parameter the value would have reached, and the typed rejection. The node type
     * rides along because a consumer grouping refusals by the identity that could not be decoded
     * would otherwise recover it from prose.
     */
    public record Defect(String coordinate, String argumentName, String paramName,
                         String nodeTypeName, Rejection rejection, SourceLocation location) {}

    /**
     * Projects every refused decode over {@code graphName}'s emitted partition. Empty for a graph
     * whose argument-carried node ids all reach parameters that can take them, and for one whose
     * refusals all sit outside the classification domain.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var v = INTENT_NODE_ID_DECODE_DEFECT;
        return new Detection(dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), inDomain(graphName))
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.USE_SITE, v.ARGUMENT_NAME)
            .fetch(row -> new Defect(
                row.getTypeName() + "." + row.getFieldName(),
                row.getArgumentName(), row.getParamName(), row.getNodeTypeName(),
                rejectionOf(Verdict.of(row.getVerdict()), row.getArgumentName(),
                    row.getParamName(), row.getNodeTypeName(), row.getArity(),
                    row.getKeyColumnName(), row.getColumnJavaType(), row.getSlotJavaType(),
                    keyColumnsOf(dsl, graphName, row.getNodeTypeName())),
                location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()))));
    }

    /**
     * The build-error consumer's population: the refused coordinate's owning type is a member of the
     * classification domain. The refused argument sits on that type's field, so the field's
     * population is its type's, which is the one predicate both grains of the sibling family use.
     */
    private static Condition inDomain(String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var v = INTENT_NODE_ID_DECODE_DEFECT;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(v.TYPE_NAME)));
    }

    /**
     * Decodes one verdict into the rejection the report carries. Both are structural: there is no
     * closed name set to have missed here, the author having named no column for an editor to offer
     * alternatives to, which is what distinguishes these two from the sibling family's typed
     * unknown-column arm.
     *
     * <p>Each message states the operands the view compared and nothing it did not: the arity arm
     * quotes the count the join read, and the type arm quotes both types off the row rather than
     * resolving them again here, so neither can describe a comparison other than the one that
     * refused the instruction.
     */
    private static Rejection rejectionOf(Verdict verdict, String argumentName, String paramName,
                                         String nodeTypeName, int arity, String keyColumnName,
                                         String columnJavaType, String slotJavaType,
                                         List<String> keyColumns) {
        return switch (verdict) {
            case KEY_ARITY_EXCEEDS_SLOT -> Rejection.structural(lead(argumentName, paramName,
                nodeTypeName) + ", but that key is " + arity
                + " columns and one parameter takes one value; declare '" + paramName
                + "' as the generated record of that node type's own table to receive the whole"
                + " tuple, or bind one of its key columns to a parameter with argMapping: "
                + String.join(", ", keyColumns));
            case KEY_COLUMN_TYPE_DISAGREEMENT -> Rejection.structural(lead(argumentName, paramName,
                nodeTypeName) + ", and its key column '" + keyColumnName + "' jOOQ binds as "
                + simpleName(columnJavaType) + ", but '" + paramName + "' takes "
                + simpleName(slotJavaType) + "; declare the parameter with the column's own type");
        };
    }

    /**
     * The clause both arms open with: the argument the author annotated, the node type it decodes
     * against, and the parameter a name match found. The name match is stated rather than left
     * implied, because it is the whole reason a parameter the author never mentioned in an
     * {@code argMapping} is receiving this value, and an author who does not know that has no way to
     * read either remedy.
     */
    private static String lead(String argumentName, String paramName, String nodeTypeName) {
        return "argument '" + argumentName + "' carries the " + nodeIdSpelling(nodeTypeName)
            + " and the producer method declares a parameter '" + paramName
            + "' of that name, so the decoded key lands there";
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
