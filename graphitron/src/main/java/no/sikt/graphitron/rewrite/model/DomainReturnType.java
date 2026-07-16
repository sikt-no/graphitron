package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.Objects;

/**
 * The Java domain type an {@link OutputField} producer puts at {@code env.getSource()} for its
 * return type's child datafetchers. Sealed so two producers reaching the same SDL return type
 * can be checked for structural agreement on the source-Java-type axis without collapsing
 * sparse-Record projection ({@link Record}) and typed jOOQ {@code TableRecord} subclass
 * ({@link TableRecord}) into "same class."
 *
 * <p>The builder's group-by step over the classified field registry compares
 * {@code domainReturnType()} values by sealed-arm structural equality (record equality on the
 * arm's components). Two producers of the same SDL return type with disagreeing
 * {@code DomainReturnType} are recorded as a
 * {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} on the
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}, which
 * {@code GraphitronSchemaValidator.validateUniformDomainReturnType} surfaces as a build error.
 *
 * <ul>
 *   <li>{@link Record} — sparse-Record projection over the named table's columns. The producer
 *       emits {@code Result<RecordN<...>>} (bulk) or {@code RecordN<...>} (single) projected on
 *       the table's PK columns; the consumer reads {@code valueN()} accessors off the row.
 *       DML mutation producers ({@code MutationDmlRecordField} / {@code MutationBulkDmlRecordField})
 *       answer this arm.</li>
 *   <li>{@link TableRecord} — typed jOOQ {@code TableRecord} subclass (e.g. {@code FilmRecord}).
 *       The producer's method returns the typed record verbatim (or a {@code List<XRecord>});
 *       the consumer can call typed accessors and {@code get(Tables.X.COL)}.
 *       {@code @service}-carrier producers answer this arm.</li>
 *   <li>{@link Plain} — explicit Java type with no jOOQ surface (column scalars, computed fields,
 *       properties, generated POJOs, polymorphic returns, etc.). The producer emits whatever the
 *       developer's method / column projection returns.</li>
 * </ul>
 */
public sealed interface DomainReturnType
    permits DomainReturnType.Record, DomainReturnType.TableRecord, DomainReturnType.Plain {

    /**
     * Sparse-Record projection over a named table's columns. The producer's emit shape is
     * {@code Result<RecordN<...>>} (bulk) or {@code RecordN<...>} (single); the typed identity
     * is the resolved {@link TableRef}.
     */
    record Record(TableRef table) implements DomainReturnType {
        public Record {
            Objects.requireNonNull(table, "table");
        }
        @Override public String toString() {
            return "Record(" + table.tableName() + ")";
        }
    }

    /**
     * A typed jOOQ {@code TableRecord} subclass. The producer's method returns the typed record
     * verbatim. {@link ClassName} (not {@link Class}) so the validator does not classload at
     * group-by time and so the identity matches {@link SourceKey.Wrap.TableRecord}'s slot type.
     */
    record TableRecord(ClassName recordClass) implements DomainReturnType {
        public TableRecord {
            Objects.requireNonNull(recordClass, "recordClass");
        }
        @Override public String toString() {
            return "TableRecord(" + recordClass.simpleName() + ")";
        }
    }

    /**
     * An explicit Java type with no jOOQ surface. {@link TypeName} (not {@link Class}) so the
     * validator does not classload to compute equality; arm equality is {@code TypeName} string
     * equality (a bare FQN for a {@link ClassName} scalar, {@code Foo[]} for an
     * {@link no.sikt.graphitron.javapoet.ArrayTypeName} column type). Widened from
     * {@code ClassName} so array-typed column scalars carry their real type rather than crashing
     * {@code ClassName.bestGuess}.
     */
    record Plain(TypeName javaClass) implements DomainReturnType {
        public Plain {
            Objects.requireNonNull(javaClass, "javaClass");
        }
        @Override public String toString() {
            return "Plain(" + javaClass + ")";
        }
    }
}
