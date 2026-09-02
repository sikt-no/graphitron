package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * One {@code argMapping} binding that decodes a node id and hands its consumer one column of the
 * decoded key: everything an emitter needs to render {@code <decode>(wire).get(Tables.X.COL)} and
 * nothing else.
 *
 * <p>The column is named rather than indexed, which is the whole reason this carrier holds a
 * {@link ColumnRef} and not a key position. A composite key's decode returns values in the key's own
 * order, and a projection that read position <i>i</i> would be transposed the moment an author pinned
 * a {@code @node(keyColumns:)} order the metadata does not share. Naming it makes that
 * unconstructable: the store resolved <em>which</em> column, and what rides here is the column
 * itself.
 *
 * <p>{@link #trailingSegmentName} is the one component whose absence carries meaning, and it is a
 * captured fact rather than half an emission decision: it is what the author spelled, and the path
 * the wire id is read along is what an emitter derives from it. Null is the inferred resolution, an
 * author who stopped on the node id against a node type whose key is one column, where the wire id
 * sits at the whole of {@link #argumentPath}; non-null is the authored one, where it sits at that path
 * minus this segment. Nothing about the path's own shape tells the two apart, both being dotted paths
 * of the same arity at a nested leaf, which is why the fact rides here rather than being recomputed.
 *
 * <p>Every component is a captured fact. The row carries no reference to a generated method and no
 * encoder class, which is what keeps it a fact carrier rather than half an emission decision: the
 * decode this projection's read performs is {@code NodeIdEncoder.decodeValues(typeId, wire)}, whose
 * only inputs are the wire id and the type id beside it, and the encoder class those live on is a
 * function of generator configuration that render already holds. A per-type
 * {@code decode<TypeName>} name is not involved at all.
 *
 * <p>{@link #keyColumns} is the node type's resolved key list in key order, which is what the
 * positional {@code fromArray} load needs, and {@link #column} is the one of them this binding reads.
 * The list rides here rather than being looked up, because a row that named only the projected column
 * would leave its emitter unable to write the load.
 *
 * @param coordinate    the {@code argMapping}'s owning coordinate, {@code Type.field}
 * @param argumentPath  the path as the author wrote it, this projection's key within the coordinate
 * @param trailingSegmentName
 *                      the segment the author spelled past the node id to name the column, as
 *                      written, or {@code null} where they spelled none and the key's arity inferred
 *                      it: where in {@link #argumentPath} the wire id sits
 * @param nodeTypeName  the node type the {@code @nodeId} named, carried for messages and locals
 * @param typeId        the wire type id the encoded node id carries, which the decode matches
 * @param nodeTable     the node type's own table: the record the decode materialises, and the
 *                      constants class the projected column is read through
 * @param keyColumns    that node type's key columns in key order, the shape the decode loads
 * @param column        the projected key column, one of {@link #keyColumns}
 */
public record KeyProjection(FieldCoordinates coordinate, String argumentPath,
                            String trailingSegmentName, String nodeTypeName, String typeId,
                            TableRef nodeTable, java.util.List<ColumnRef> keyColumns,
                            ColumnRef column) {

    public KeyProjection {
        if (coordinate == null) {
            throw new IllegalArgumentException("a key projection carries the coordinate it was"
                + " spelled at");
        }
        if (argumentPath == null || argumentPath.isBlank()) {
            throw new IllegalArgumentException("a key projection is keyed by the path the author"
                + " wrote, which is never blank");
        }
        if (trailingSegmentName != null && trailingSegmentName.isBlank()) {
            throw new IllegalArgumentException("a key projection's trailing segment is the author's"
                + " own spelling of a key column or absent entirely; blank is neither, and a blank"
                + " one would make the authored resolution indistinguishable from the inferred");
        }
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("a key projection carries the wire type id its decode"
                + " matches, which is never blank: every node type resolves one");
        }
        if (nodeTable == null || keyColumns == null || column == null) {
            throw new IllegalArgumentException("a key projection carries its table, that table's key"
                + " columns and the projected one; none of the three is optional");
        }
        keyColumns = java.util.List.copyOf(keyColumns);
        if (!keyColumns.contains(column)) {
            throw new IllegalArgumentException(
                "the projected column '" + column.sqlName() + "' is not one of '" + nodeTypeName
                + "'s key columns, so no decode of that node id could ever produce it");
        }
    }
}
