package no.sikt.graphitron.lsp.facts;

/**
 * One labelled value a surface can show about a declaration: a column's name, a service method, the
 * table a claim resolved against. Deliberately a pair of strings rather than a typed per-classifier
 * payload, because a typed payload is a taxonomy, and the taxonomy of classification variants is
 * exactly what the store does not carry and the language server no longer rebuilds.
 *
 * <p>The label names what the value is, in the words a schema author uses rather than the column's;
 * the relation each fact came from is the reader's business and the reader's alone.
 *
 * @param label what the value is
 * @param value the fact, never blank; a fact with nothing to say is not produced
 */
public record DeclarationFact(String label, String value) {}
