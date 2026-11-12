package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.derive.NodeIdMessages.keyColumnsOf;
import static no.sikt.graphitron.model.derive.NodeIdMessages.keyColumnTypesOf;
import static no.sikt.graphitron.model.derive.NodeIdMessages.nodeIdSpelling;
import static no.sikt.graphitron.model.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The {@code @nodeId} decode rules at a producer parameter, projected from the store: an argument
 * carrying a decoding {@code @nodeId} whose value a producer method's parameter of that name
 * receives either has its decode carried out or is one of the three refusals below. The refusals
 * are entry defects at the {@code @nodeId}, which the graphitron rule picks between on the slot's
 * shape and then the node key's arity; what remains here is the decode of those codes into
 * {@link Rejection} arms and the prose they carry, from the slot in {@code graphitron_occurrence_node_id_slot}
 * and the key the occurrence binds in {@code graphitron_occurrence_node_id}.
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
 * <p>Every such defect fails the build, at any coordinate the store holds an occurrence for.
 *
 * <p>The wording converges with {@link ArgmappingProjectionDefects} rather than being renegotiated:
 * that family refuses the same two facts one carrier over, where an {@code argMapping} entry binds
 * the node id instead of a parameter name matching it, and the shared vocabulary lives in
 * {@link NodeIdMessages}. What differs is the remedy, and it differs because the carrier does: an
 * author who wrote an {@code argMapping} entry is told about their entry, and an author who wrote
 * none is told about the name match that found the parameter, which is the provenance
 * {@code graphitron_occurrence_call_parameter_pair} makes sayable: a parameter a pair fills has a row
 * there, and this family judges only those that have none.
 */
public final class NodeIdDecodeDefects {

    private NodeIdDecodeDefects() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = NodeIdMessages.readsWith(
        Set.of(GRAPHITRON_ENTRY_DEFECT_SITE, GRAPHITRON_BINDING_CLAIM, GRAPHITRON_TYPE, GRAPHITRON_NODE,
            GRAPHITRON_OCCURRENCE_NODE_ID_SLOT, GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER,
            GRAPHITRON_OCCURRENCE, GRAPHITRON_OCCURRENCE_CALL_PARAMETER,
            GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR, GRAPHITRON_OCCURRENCE_NODE_ID,
            GRAPHITRON_ARGUMENT, GRAPHQL_ARGUMENT, SQL_COLUMN, SQL_TABLE));

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
         * A jOOQ {@code RecordN} or {@code RowN} of the key's degree whose type arguments are not
         * the key columns' types in key order. A tuple is the key only where both its degree and
         * its types are the key's; one of another degree is refused on the arity instead.
         */
        KEY_TUPLE_TYPE_DISAGREEMENT,
        /**
         * The argument's list-ness and the parameter's disagree on terms the emitter cannot fill: a
         * list argument at a parameter that is not a {@code java.util.List}, which is the one
         * container the list decode builds, a single id at a multi-valued parameter, or a list
         * nested deeper than one level. Decided before arity or type, a mismatched slot having no
         * place one decoded value goes.
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
                    "the node id decode defects produced code '" + code + "', which no "
                    + Verdict.class.getSimpleName()
                    + " value names; the rule and the enum must move together"));
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

    /** The slot and key facts a message names, at one argument and parameter. */
    private record Slot(String argumentName, String landingType, String slotShape, Integer listDepth,
                        String nodeTypeName, int arity, String keyColumnName, String columnJavaType,
                        String recordClass) {}

    /** The graph's refusals, in type, field and argument order. */
    public static Detection detect(DSLContext dsl, String graphName) {
        var slots = slots(dsl, graphName);
        var e = GRAPHITRON_ENTRY_DEFECT_SITE;
        // A container's shape mismatch shares the code and is the polymorphic family's.
        var cl = GRAPHITRON_BINDING_CLAIM;
        var t = GRAPHITRON_TYPE;
        var n = GRAPHITRON_NODE;
        var codes = Arrays.stream(Verdict.values()).map(Verdict::code).toList();
        return new Detection(dsl.select(e.TYPE_NAME, e.FIELD_NAME, e.COORDINATE, e.CODE, e.DETAIL,
                e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN)
            .from(e)
            .where(e.GRAPH_NAME.eq(graphName), e.CODE.in(codes), notExists(selectOne()
                .from(cl)
                .join(t).on(t.GRAPH_NAME.eq(cl.GRAPH_NAME), t.TYPE_NAME.eq(cl.NAMED))
                .where(cl.GRAPH_NAME.eq(e.GRAPH_NAME), cl.COORDINATE.eq(e.COORDINATE),
                    cl.KIND.eq("NODE_ID"), t.KIND.in("INTERFACE", "UNION"),
                    notExists(selectOne().from(n).where(n.GRAPH_NAME.eq(t.GRAPH_NAME),
                        n.TYPE_NAME.eq(t.TYPE_NAME))))))
            .orderBy(e.TYPE_NAME, e.FIELD_NAME, e.COORDINATE)
            .fetch(row -> {
                var slot = slots.get(row.value3() + "#" + row.value5());
                if (slot == null) {
                    throw new IllegalStateException("the node id decode defect at '" + row.value3()
                        + "' names parameter '" + row.value5() + "', which no slot holds; the rule"
                        + " and the slot relation must move together");
                }
                boolean listSlot = "LIST".equals(slot.slotShape());
                String slotJavaType = listSlot ? LIST : slot.landingType();
                return new Defect(row.value1() + "." + row.value2(), slot.argumentName(),
                    row.value5(), slot.nodeTypeName(),
                    rejectionOf(Verdict.of(row.value4()), slot.argumentName(), row.value5(),
                        slot.nodeTypeName(), slot.arity(), slot.keyColumnName(),
                        slot.columnJavaType(), slot.recordClass(), slotJavaType, slot.landingType(),
                        listSlot, slot.listDepth(), keyColumnsOf(dsl, graphName, slot.nodeTypeName()),
                        keyColumnTypesOf(dsl, graphName, slot.nodeTypeName())),
                    new SourceLocation(row.value7(), row.value8(), row.value6()));
            }));
    }

    /**
     * Each by-name argument slot's facts, keyed by the argument's coordinate and the parameter: the
     * slot's shape and landing, the argument's list depth, and the key its occurrence binds.
     */
    private static Map<String, Slot> slots(DSLContext dsl, String graphName) {
        var s = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
        var sp = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
        var o = GRAPHITRON_OCCURRENCE;
        var cp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var pp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var c = SQL_COLUMN;
        var t = SQL_TABLE;
        var a = GRAPHITRON_ARGUMENT;
        var g = GRAPHQL_ARGUMENT;
        var out = new HashMap<String, Slot>();
        dsl.select(o.ELEMENT_COORDINATE, cp.PARAMETER_NAME, a.ARGUMENT_NAME, s.LANDING_CLASS,
                s.SLOT_SHAPE, g.LIST_DEPTH, min(n.NODE_TYPE_NAME), count(), min(n.COLUMN_NAME),
                min(c.BINDING_TYPE), min(t.RECORD_CLASS_FQN))
            .from(sp)
            .join(s).on(s.GRAPH_NAME.eq(sp.GRAPH_NAME), s.COORDINATE.eq(sp.COORDINATE), s.SLOT.eq(sp.SLOT))
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            .join(cp).on(cp.GRAPH_NAME.eq(sp.GRAPH_NAME), cp.COORDINATE.eq(sp.CALL_COORDINATE),
                cp.CALL.eq(sp.CALL), cp.POSITION.eq(sp.POSITION))
            .join(n).on(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.COORDINATE.eq(s.COORDINATE))
            .leftJoin(c).on(c.SOURCE_NAME.eq(n.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(n.TABLE_SCHEMA),
                c.TABLE_NAME.eq(n.TABLE_NAME), c.COLUMN_NAME.eq(n.COLUMN_NAME))
            .leftJoin(t).on(t.SOURCE_NAME.eq(n.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(n.TABLE_SCHEMA),
                t.TABLE_NAME.eq(n.TABLE_NAME))
            .join(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(g).on(g.GRAPH_NAME.eq(a.GRAPH_NAME), g.TYPE_NAME.eq(a.TYPE_NAME),
                g.FIELD_NAME.eq(a.FIELD_NAME), g.ARGUMENT_NAME.eq(a.ARGUMENT_NAME))
            // The parameter a @service's call names for the argument, which no written pair fills.
            .where(sp.GRAPH_NAME.eq(graphName), o.KIND.eq("FIELD_ARGUMENT"),
                cp.CALL_KIND.eq("SERVICE"), cp.PARAMETER_NAME.eq(o.NAME),
                notExists(selectOne().from(pp).where(pp.GRAPH_NAME.eq(cp.GRAPH_NAME),
                    pp.COORDINATE.eq(cp.COORDINATE), pp.CALL.eq(cp.CALL), pp.POSITION.eq(cp.POSITION))))
            .groupBy(o.ELEMENT_COORDINATE, s.COORDINATE, cp.PARAMETER_NAME, a.ARGUMENT_NAME,
                s.LANDING_CLASS, s.SLOT_SHAPE, g.LIST_DEPTH)
            .forEach(r -> {
                int arity = r.value8();
                String record = "org.jooq.Record".equals(r.value11()) ? null : r.value11();
                out.putIfAbsent(r.value1() + "#" + r.value2(), new Slot(r.value3(), r.value4(),
                    r.value5(), r.value6(), r.value7(), arity, arity == 1 ? r.value9() : null,
                    arity == 1 ? r.value10() : null, record));
            });
        return out;
    }

    private static Rejection rejectionOf(Verdict verdict, String argumentName, String paramName,
                                         String nodeTypeName, int arity, String keyColumnName,
                                         String columnJavaType, String recordClass,
                                         String slotJavaType, String landingJavaType,
                                         boolean listSlot, Integer argumentListDepth,
                                         List<String> keyColumns, List<String> keyColumnTypes) {
        String lead = lead(argumentName, paramName, nodeTypeName);
        String types = String.join(", ", keyColumnTypes);
        return switch (verdict) {
            case KEY_ARITY_EXCEEDS_SLOT -> Rejection.structural(lead + ", but that key is " + arity
                + " columns and one " + (listSlot ? "list element" : "parameter")
                + " takes one value; declare '" + paramName + "' as "
                + shaped(recordOf(recordClass), listSlot) + ", or as "
                + shaped("Record" + arity + "<" + types + ">", listSlot) + " or "
                + shaped("Row" + arity + "<" + types + ">", listSlot)
                + ", to receive the whole tuple, or bind"
                + " one of its key columns to a parameter with argMapping: "
                + String.join(", ", keyColumns));
            case KEY_TUPLE_TYPE_DISAGREEMENT -> Rejection.structural(lead + ", and its key columns "
                + String.join(", ", keyColumns) + " jOOQ binds as " + types + " in key order, but "
                + (listSlot
                    ? "the " + simpleName(landingJavaType) + " elements of '" + paramName + "' are"
                    : "'" + paramName + "', a " + simpleName(landingJavaType) + ", is")
                + " typed otherwise; declare '" + paramName + "' as "
                + shaped(simpleName(landingJavaType) + "<" + types + ">", listSlot));
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

}

