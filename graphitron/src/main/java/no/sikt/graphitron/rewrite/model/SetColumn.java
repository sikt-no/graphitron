package no.sikt.graphitron.rewrite.model;

/**
 * One column contribution on the SET side of an UPDATE. Carries the GraphQL input field
 * name it came from, the jOOQ column it writes, and how to read the input value at the call-site
 * root.
 *
 * <p>Deliberately decoupled from {@link InputField}: the principle is that input fields have no
 * semantics independent of the consuming field, so the carrier names exactly what UPDATE's SET
 * partition needs and nothing else. A composite-NodeId input field that lifts to several columns
 * produces several {@code SetColumn} rows sharing one {@link #sdlFieldName()} (the emitter groups
 * by it to emit a single decode local the columns reference); {@link #extraction()} reuses the
 * existing {@link CallSiteExtraction} family so the emit-side decode helpers stay unchanged.
 */
public record SetColumn(
    String sdlFieldName,
    ColumnRef targetColumn,
    CallSiteExtraction extraction
) {}
