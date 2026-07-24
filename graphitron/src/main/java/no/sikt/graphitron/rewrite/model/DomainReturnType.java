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
 * <p>The builder groups the classified field registry by SDL return type and compares
 * {@code domainReturnType()} values by sealed-arm record equality. Producers of the same SDL
 * return type with disagreeing arms are recorded as a
 * {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} on the
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}, which the schema validator surfaces as a
 * build error.
 */
public sealed interface DomainReturnType
    permits DomainReturnType.Record, DomainReturnType.TableRecord, DomainReturnType.Plain {

    /**
     * Sparse-Record projection over a named table's columns. The producer's emit shape is
     * {@code Result<RecordN<...>>} (bulk) or {@code RecordN<...>} (single); the typed identity
     * is the resolved {@link TableRef}. DML mutation producers ({@code MutationDmlRecordField} /
     * {@code MutationBulkDmlRecordField}) answer this arm.
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
     * A typed jOOQ {@code TableRecord} subclass (e.g. {@code FilmRecord}). The producer's method
     * returns the typed record verbatim; {@code @service}-carrier producers answer this arm.
     * {@link ClassName} (not {@link Class}) so the validator does not classload at
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
     * An explicit Java type with no jOOQ surface (column scalars, computed fields, properties,
     * generated POJOs, polymorphic returns). {@link TypeName} (not {@link Class}) so the
     * validator does not classload to compute equality; arm equality is {@code TypeName} string
     * equality (a bare FQN for a {@link ClassName} scalar, {@code Foo[]} for an
     * {@link no.sikt.graphitron.javapoet.ArrayTypeName} column type, so array-typed column
     * scalars carry their real type).
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
