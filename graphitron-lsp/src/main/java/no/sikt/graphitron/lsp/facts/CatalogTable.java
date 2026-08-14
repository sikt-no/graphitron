package no.sikt.graphitron.lsp.facts;

/**
 * One table of the catalog census, by its whole key. The census is partitioned by the generated
 * package a schema was written into, so a table is a triple and not a name: two schemas may declare
 * the same table name, and two generated packages may carry the same schema.
 *
 * <p>What earns this its own type is the difference between a name an author wrote and a table
 * something resolved. A reader handed a spelling matches it case-insensitively across the census and
 * answers with every table that spells it, because which one was meant is a resolution question.
 * A reader handed one of these needs no such latitude: the resolution already happened, so the
 * filter is equality on all three columns and the answer is about that table alone.
 */
public record CatalogTable(String sourceName, String schema, String tableName) {}
