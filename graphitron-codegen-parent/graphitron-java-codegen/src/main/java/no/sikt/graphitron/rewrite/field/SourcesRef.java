package no.sikt.graphitron.rewrite.field;

import java.util.List;

/**
 * Classifies the element type of a SOURCES parameter (i.e., the {@code T} in {@code List<T>})
 * of a {@code @service} method.
 *
 * <p>The three recognised variants differ in what the DataLoader key is and how it is constructed
 * from the parent record at runtime. When the declared element type cannot be classified the
 * containing field is classified as
 * {@link no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField} at build time (via
 * the enclosing {@link ServiceMethodRef} failing to resolve).
 *
 * <ul>
 *   <li>{@link RowKeyed} — the service method takes {@code List<RowN<T1,...>>}. The DataLoader key
 *       is a jOOQ {@code Row} built from the parent record's PK column(s) via {@code DSL.row(...)}.</li>
 *   <li>{@link RecordKeyed} — the service method takes {@code List<RecordN<T1,...>>}. The DataLoader
 *       key is a jOOQ {@code RecordN} built from the parent record's PK column(s) via
 *       {@code record.into(field1)}.</li>
 *   <li>{@link TableRecordKeyed} — the service method takes {@code List<SomeTableRecord>}. The
 *       DataLoader key is the whole parent record cast to the declared table-record type.</li>
 * </ul>
 */
public sealed interface SourcesRef
    permits SourcesRef.RowKeyed, SourcesRef.RecordKeyed, SourcesRef.TableRecordKeyed {

    /**
     * The service method takes {@code List<RowN<T1,...>>} (e.g. {@code List<Row1<Long>>}).
     *
     * <p>{@code pkJavaTypes} lists the binary class names of the type arguments in declaration
     * order, e.g. {@code ["java.lang.Long"]} for {@code Row1<Long>}.
     */
    record RowKeyed(List<String> pkJavaTypes) implements SourcesRef {}

    /**
     * The service method takes {@code List<RecordN<T1,...>>} (e.g. {@code List<Record1<Long>>}).
     *
     * <p>{@code pkJavaTypes} lists the binary class names of the type arguments, parallel to the
     * parent table's PK columns.
     */
    record RecordKeyed(List<String> pkJavaTypes) implements SourcesRef {}

    /**
     * The service method takes {@code List<SomeTableRecord>} (e.g. {@code List<LanguageRecord>}).
     *
     * <p>{@code fqClassName} is the binary class name of the table-record type
     * (e.g. {@code "no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.LanguageRecord"}).
     * The DataLoader key is the whole parent record cast to this type — no per-column PK
     * extraction is needed.
     */
    record TableRecordKeyed(String fqClassName) implements SourcesRef {}
}
