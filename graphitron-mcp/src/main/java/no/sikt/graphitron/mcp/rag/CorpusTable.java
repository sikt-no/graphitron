package no.sikt.graphitron.mcp.rag;

import java.util.List;

/**
 * One table as the {@code catalog.search} corpus reads it: the two names its id is composed from,
 * the database comment, and the columns in the order the table defines them.
 *
 * <p>Declared here rather than where the rows are read, so the composer and the index state what they
 * need and the census query fills it. That keeps the dependency one-directional: this package names
 * no query surface and no store type, and the module's catalog reads all stay in one place.
 *
 * <p>Both comments are nullable rather than {@link java.util.Optional}, which is what the census
 * carries: jOOQ codegen captures a comment or it does not, and the capture normalises a blank one to
 * SQL {@code NULL} so a reader can tell an empty comment from an absent one. The descriptor composer
 * omits the line either way.
 */
public record CorpusTable(String schema, String name, String comment, List<Column> columns) {

    /** One column's contribution to the descriptor: its SQL name and its comment, {@code null} where it has none. */
    public record Column(String name, String comment) {}

    /** The schema-qualified SQL name: this table's index entry id, and the id {@code catalog.describe} accepts back. */
    public String id() {
        return schema + "." + name;
    }
}
