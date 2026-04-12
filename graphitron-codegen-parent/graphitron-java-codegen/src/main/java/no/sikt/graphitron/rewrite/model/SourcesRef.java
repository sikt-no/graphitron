package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Classifies the element type of a {@link ParamSource.Sources} parameter
 * (i.e., the {@code T} in {@code List<T>}) for DataLoader-batched methods.
 *
 * <p>The four variants differ in what the DataLoader key is and how it is constructed from the
 * parent record at runtime. When the declared element type cannot be classified the containing
 * field is classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} at build time.
 *
 * <ul>
 *   <li>{@link RowKeyed} — the method takes {@code List<RowN<T1,...>>}. The DataLoader key
 *       is a jOOQ {@code Row} built from the parent record's PK column(s) via {@code DSL.row(...)}.</li>
 *   <li>{@link RecordKeyed} — the method takes {@code List<RecordN<T1,...>>}. The DataLoader
 *       key is a jOOQ {@code RecordN} built from the parent record's PK column(s) via
 *       {@code record.into(field1)}.</li>
 *   <li>{@link TableRecordKeyed} — the method takes {@code List<SomeTableRecord>}. The DataLoader
 *       key is the whole parent record cast to the declared jOOQ table-record type.</li>
 *   <li>{@link ResultKeyed} — the method takes {@code List<SomeResultDto>}. The DataLoader key
 *       is the whole parent result object cast to the declared type. Used when lifting from a
 *       result-mapped parent back into table context via a user-provided condition method.</li>
 * </ul>
 */
public sealed interface SourcesRef
    permits SourcesRef.RowKeyed, SourcesRef.RecordKeyed,
            SourcesRef.TableRecordKeyed, SourcesRef.ResultKeyed {

    /**
     * The method takes {@code List<RowN<T1,...>>} (e.g. {@code List<Row1<Long>>}).
     *
     * <p>{@code pkJavaTypes} lists the binary class names of the type arguments in declaration
     * order, e.g. {@code ["java.lang.Long"]} for {@code Row1<Long>}.
     */
    record RowKeyed(List<String> pkJavaTypes) implements SourcesRef {}

    /**
     * The method takes {@code List<RecordN<T1,...>>} (e.g. {@code List<Record1<Long>>}).
     *
     * <p>{@code pkJavaTypes} lists the binary class names of the type arguments, parallel to the
     * parent table's PK columns.
     */
    record RecordKeyed(List<String> pkJavaTypes) implements SourcesRef {}

    /**
     * The method takes {@code List<SomeTableRecord>} (e.g. {@code List<LanguageRecord>}).
     *
     * <p>{@code fqClassName} is the binary class name of the jOOQ table-record type. The DataLoader
     * key is the whole parent record cast to this type — no per-column PK extraction is needed.
     */
    record TableRecordKeyed(String fqClassName) implements SourcesRef {}

    /**
     * The method takes {@code List<SomeResultDto>} where the parent is a result-mapped type
     * (not a jOOQ {@code TableRecord}).
     *
     * <p>{@code fqClassName} is the binary class name of the result DTO type. The DataLoader key
     * is the whole parent result object cast to this type. Used for user-provided lift conditions
     * that reconnect a result-mapped parent back into table-query context.
     */
    record ResultKeyed(String fqClassName) implements SourcesRef {}
}
