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

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.derive.NodeIdMessages.keyColumnsOf;
import static no.sikt.graphitron.model.derive.NodeIdMessages.nodeIdSpelling;
import static no.sikt.graphitron.model.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The {@code @nodeId} decode rules at a producer parameter, projected from the store: an argument
 * carrying a decoding {@code @nodeId} whose value a producer method's parameter of that name
 * receives either has its decode carried out, with a row in {@code intent_node_id_decode}, or is one
 * of the three refusals below. The reduction lives in {@code intent_node_id_decode_defect}, which
 * picks between them on the slot's shape and then the node key's arity in one pass; what remains
 * here is the decode of that view's closed three-verdict vocabulary into {@link Rejection} arms and
 * the prose they carry.
 *
 * <p>A {@code java.util.List} parameter at a list argument is judged on its element, which is where
 * one decoded id lands: the emitter's list decode hands over a {@code java.util.List} with one
 * decoded value per id. Every remedy is therefore spelled in the shape the slot needs, a
 * {@code List} of the record where the argument is a list and the record itself where it is not.
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

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = NodeIdMessages.readsWith(
        Set.of(INTENT_NODE_ID_DECODE_DEFECT, INTENT_TYPE_DOMAIN));

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
         * One key column, and the Java type jOOQ binds it as is not the parameter's, or at a list
         * slot not its element's. The verdict
         * whose two operands are both types, which is why it fires only where both are known: a
         * column no catalog could type and a parameter no census could type leave the decode to be
         * carried out on arity alone with javac as the backstop, refusing on an operand nobody could
         * read being a new silence rather than the closing of one.
         */
        KEY_COLUMN_TYPE_DISAGREEMENT,
        /**
         * The argument's list-ness and the parameter's disagree on terms the emitter cannot fill: a
         * list argument at a parameter that is not a {@code java.util.List}, which is the one
         * container the list decode builds, a single id at a multi-valued parameter, or a list
         * nested deeper than one level. Decided before arity or type, a mismatched slot having no
         * place one decoded value goes.
         */
        SLOT_SHAPE_MISMATCH;

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
                    row.getKeyColumnName(), row.getColumnJavaType(), row.getRecordClass(),
                    row.getSlotJavaType(), row.getLandingJavaType(),
                    "LIST".equals(row.getSlotShape()), row.getArgumentListDepth(),
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
     * Decodes one verdict into the rejection the report carries. All three are structural: there is
     * no closed name set to have missed here, the author having named no column for an editor to
     * offer alternatives to, which is what distinguishes these from the sibling family's typed
     * unknown-column arm.
     *
     * <p>Each message states the operands the view compared and nothing it did not: the arity arm
     * quotes the count the join read, the type arm quotes both types off the row rather than
     * resolving them again here, and the shape arm quotes the container the parameter declares, so
     * none can describe a comparison other than the one that refused the instruction.
     */
    private static Rejection rejectionOf(Verdict verdict, String argumentName, String paramName,
                                         String nodeTypeName, int arity, String keyColumnName,
                                         String columnJavaType, String recordClass,
                                         String slotJavaType, String landingJavaType,
                                         boolean listSlot, Integer argumentListDepth,
                                         List<String> keyColumns) {
        String lead = lead(argumentName, paramName, nodeTypeName);
        return switch (verdict) {
            case KEY_ARITY_EXCEEDS_SLOT -> Rejection.structural(lead + ", but that key is " + arity
                + " columns and one " + (listSlot ? "list element" : "parameter")
                + " takes one value; declare '" + paramName + "' as "
                + shaped(recordOf(recordClass), listSlot) + " to receive the whole tuple, or bind"
                + " one of its key columns to a parameter with argMapping: "
                + String.join(", ", keyColumns));
            case KEY_COLUMN_TYPE_DISAGREEMENT -> Rejection.structural(lead + ", and its key column '"
                + keyColumnName + "' jOOQ binds as " + simpleName(columnJavaType)
                + (listSlot
                    ? ", but the elements of '" + paramName + "' are " + simpleName(landingJavaType)
                        + "; declare '" + paramName + "' as "
                        + shaped(simpleName(columnJavaType), true)
                    : ", but '" + paramName + "' takes " + simpleName(landingJavaType)
                        + "; declare the parameter with the column's own type"));
            case SLOT_SHAPE_MISMATCH -> Rejection.structural(lead
                + shapeMismatch(paramName, slotJavaType, argumentListDepth, arity, recordClass,
                    columnJavaType));
        };
    }

    /**
     * The shape arm's clause after the lead, in the direction the argument and the parameter
     * disagree. A list argument is handed over as one {@code java.util.List}, so a parameter declaring
     * any other container cannot take it; a single id decodes to one value, so a multi-valued
     * parameter is the wrong shape for it; and a list of lists has no decode at all.
     */
    private static String shapeMismatch(String paramName, String slotJavaType,
                                        Integer argumentListDepth, int arity, String recordClass,
                                        String columnJavaType) {
        int depth = argumentListDepth == null ? 0 : argumentListDepth;
        String takes = "'" + paramName + "' takes " + simpleName(slotJavaType);
        if (depth > 1) {
            return ", but the argument is a list of lists, and a list argument is decoded only one"
                + " level deep; make the argument a list of IDs and declare "
                + declaration(paramName, arity, recordClass, columnJavaType, true);
        }
        if (depth == 1) {
            return ", and the argument is a list, so the decoded ids are handed over as a"
                + " java.util.List, but " + takes + "; declare "
                + declaration(paramName, arity, recordClass, columnJavaType, true);
        }
        return ", but " + takes + (LIST.equals(slotJavaType)
                ? ", which a list argument fills with one decoded value per id"
                : ", which holds many values where one id decodes to one")
            + "; declare " + declaration(paramName, arity, recordClass, columnJavaType, false)
            + ", or make the argument a list";
    }

    /** The one container the emitter's list decode builds. */
    private static final String LIST = "java.util.List";

    /**
     * The declaration a remedy offers for the parameter: the node type's record above one key
     * column, the sole column's own type at one with the record named as the alternative, each in
     * the shape the slot needs. Where an operand is unknown the record is described rather than
     * named.
     */
    private static String declaration(String paramName, int arity, String recordClass,
                                      String columnJavaType, boolean list) {
        String as = "'" + paramName + "' as ";
        if (arity > 1 || columnJavaType == null) {
            return as + shaped(recordOf(recordClass), list);
        }
        String column = as + shaped(simpleName(columnJavaType), list);
        return recordClass == null ? column
            : column + ", or as " + shaped(simpleName(recordClass), list);
    }

    /** The node type's generated record by its simple name, or described where none resolved. */
    private static String recordOf(String recordClass) {
        return recordClass == null ? "the generated record of that node type's own table"
            : simpleName(recordClass);
    }

    /**
     * {@code type} as the slot needs it: wrapped in {@code List<...>} at a list slot, or described as
     * a list of it where {@code type} is itself a description rather than a name.
     */
    private static String shaped(String type, boolean list) {
        if (!list) {
            return type;
        }
        return type.indexOf(' ') < 0 ? "List<" + type + ">" : "a java.util.List of " + type;
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
