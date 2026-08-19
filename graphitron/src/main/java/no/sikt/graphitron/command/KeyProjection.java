package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.TableRef;

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
 * <p>The decode facts arrive as the model's own {@link HelperRef.Decode}, which is the reference the
 * node-id encoder generator and every decode call site already share, so the helper this projection
 * calls cannot be named one way here and another there. Its {@code outputColumnShape} is the node
 * type's resolved key list in key order, which is what a positional {@code fromArray} load needs;
 * {@link #nodeTable} is where those columns live, and the record class the load materialises.
 *
 * @param coordinate    the {@code argMapping}'s owning coordinate, {@code Type.field}
 * @param argumentPath  the path as the author wrote it, this projection's key within the coordinate
 * @param nodeTypeName  the node type the {@code @nodeId} named, carried for messages and locals
 * @param decode        the per-type decode helper, with its key list, encoder class and wire type id
 * @param nodeTable     the node type's own table: the record the decode materialises, and the
 *                      constants class the projected column is read through
 * @param column        the projected key column, one of {@code decode.outputColumnShape()}
 */
public record KeyProjection(FieldCoordinates coordinate, String argumentPath, String nodeTypeName,
                            HelperRef.Decode decode, TableRef nodeTable, ColumnRef column) {

    public KeyProjection {
        if (coordinate == null) {
            throw new IllegalArgumentException("a key projection carries the coordinate it was"
                + " spelled at");
        }
        if (argumentPath == null || argumentPath.isBlank()) {
            throw new IllegalArgumentException("a key projection is keyed by the path the author"
                + " wrote, which is never blank");
        }
        if (decode == null || nodeTable == null || column == null) {
            throw new IllegalArgumentException("a key projection carries a decode, its table and the"
                + " projected column; none of the three is optional");
        }
        if (!decode.outputColumnShape().contains(column)) {
            throw new IllegalArgumentException(
                "the projected column '" + column.sqlName() + "' is not one of '" + nodeTypeName
                + "'s key columns, so no decode of that node id could ever produce it");
        }
    }
}
