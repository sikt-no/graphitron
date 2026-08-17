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
 * <p>The root splits on whether the producer can state anything at all. A {@link Claim} is a
 * statement about the Java value that reaches {@code env.getSource()}; {@link NoClaim} is the
 * structural home for "this producer cannot say", and it is the honest answer wherever no fact
 * in the model names the arriving class. The split is what keeps a placeholder value (a
 * {@code Plain(java.lang.Object)} standing in for "don't know") out of the comparison: only
 * claims are compared, and the grouping helpers are declared over {@link Claim} so a no-claim
 * cannot reach a comparison by omission.
 *
 * <p>The builder groups the classified field registry by SDL return type and compares the
 * {@code domainReturnType()} claims by sealed-arm record equality. Producers of the same SDL
 * return type with disagreeing claims are recorded as a
 * {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} on the
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}, which the schema validator surfaces as a
 * build error.
 */
public sealed interface DomainReturnType
    permits DomainReturnType.Claim, DomainReturnType.NoClaim {

    /**
     * A producer's statement about the Java value it puts at {@code env.getSource()}. The three
     * arms keep a sparse jOOQ {@code Record} projection, a typed jOOQ {@code TableRecord} and a
     * plain domain object apart on purpose: "same class" would paper over a projection
     * difference. The conflict reduction compares only these.
     */
    sealed interface Claim extends DomainReturnType
        permits DomainReturnType.Record, DomainReturnType.TableRecord, DomainReturnType.Plain {}

    /**
     * Sparse-Record projection over a named table's columns. The producer's emit shape is
     * {@code Result<RecordN<...>>} (bulk) or {@code RecordN<...>} (single); the typed identity
     * is the resolved {@link TableRef}. DML mutation producers ({@code MutationDmlRecordField} /
     * {@code MutationBulkDmlRecordField}) answer this arm.
     */
    record Record(TableRef table) implements Claim {
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
    record TableRecord(ClassName recordClass) implements Claim {
        public TableRecord {
            Objects.requireNonNull(recordClass, "recordClass");
        }
        @Override public String toString() {
            return "TableRecord(" + recordClass.simpleName() + ")";
        }
    }

    /**
     * An explicit Java type with no jOOQ surface (column scalars, computed fields, properties,
     * generated POJOs). {@link TypeName} (not {@link Class}) so the
     * validator does not classload to compute equality; arm equality is {@code TypeName} string
     * equality (a bare FQN for a {@link ClassName} scalar, {@code Foo[]} for an
     * {@link no.sikt.graphitron.javapoet.ArrayTypeName} column type, so array-typed column
     * scalars carry their real type).
     */
    record Plain(TypeName javaClass) implements Claim {
        public Plain {
            Objects.requireNonNull(javaClass, "javaClass");
        }
        @Override public String toString() {
            return "Plain(" + javaClass + ")";
        }
    }

    /**
     * The producer cannot state what it puts at {@code env.getSource()}: no fact in the model
     * names the arriving class. Answered by the polymorphic producers (whose value is one of
     * several developer classes chosen at run time), by the errors-list producer, and by a
     * class-backed read whose return type never grounded a backing class.
     *
     * <p>A no-claim is excluded from the conflict reduction's comparison rather than treated as
     * a distinct value: "unverifiable" is not "disagreeing", and the check is measuring
     * disagreement. It stays in the rejection's participant list when a real conflict fires,
     * where {@link #toString()} renders it as the statement it is.
     */
    record NoClaim() implements DomainReturnType {
        @Override public String toString() {
            return "makes no source-type claim";
        }
    }

    /**
     * The claim for a result return type's grounded backing class: the single mint for "the
     * backing class, as a {@link TypeName}". Routes through {@link RowsMethodShape#fromBinaryName}
     * so a nested class resolves to the JLS-legal {@code Outer.Nested} rather than to
     * {@code ClassName.bestGuess}'s one simple name containing a {@code $}; two producers of one
     * nested class spelling it two ways is the same false-conflict defect this vocabulary exists
     * to prevent.
     *
     * <p>Answers {@link NoClaim} when the result axis grounded no class, rather than handing back
     * a null for the caller to paper over with a placeholder. Bounded to
     * {@link ReturnTypeRef.ResultReturnType} at the signature: a
     * {@link ReturnTypeRef.TableBoundReturnType} does have a record class on offer, but the
     * consumer-level claim there is {@link Record} over the table, and a factory that answered
     * {@link Plain} for it because no caller passes that arm today would be the same defect one
     * arm over.
     */
    static DomainReturnType claimForBacking(ReturnTypeRef.ResultReturnType returnType) {
        String fq = returnType.fqClassName();
        if (fq == null) return new NoClaim();
        return new Plain(RowsMethodShape.fromBinaryName(fq));
    }

    /**
     * The claim every producer over a {@link ReturnTypeRef.ResultReturnType} answers, stated once
     * so the leaves asking it cannot drift apart. Three sub-populations, answered by fact rather
     * than by leaf:
     *
     * <ul>
     *   <li><b>A resolved table is present.</b> The producer's element is that table's typed
     *       record, and the typed record is what reaches {@code env.getSource()}: the claim is
     *       {@link TableRecord} over the table's record class. Covers both the unbacked
     *       table-backed carrier payload and the reflected jOOQ table record.</li>
     *   <li><b>No table, but a grounded backing class.</b> An ordinary class-backed result type;
     *       the claim is {@link #claimForBacking}'s {@link Plain} over that class.</li>
     *   <li><b>Neither.</b> Nothing in the model names what arrives: {@link NoClaim}.</li>
     * </ul>
     *
     * <p>The table question is asked first, and the ordering is load-bearing: a result type
     * carrying both a table and a reflected class must never mint {@code Plain(XRecord)}, which
     * would falsify {@link Plain}'s "no jOOQ surface" contract and demote a producer that really
     * does hand down a typed record. Keying the fork on {@code fqClassName}'s nullity instead
     * would answer the two {@code JooqTableRecordType} populations (one carrying a reflected
     * class name, one the stand-in null) with two different arms although both put a typed jOOQ
     * table record at {@code env.getSource()}.
     */
    static DomainReturnType claimForResultReturn(ReturnTypeRef.ResultReturnType returnType) {
        TableRef table = returnType.table();
        if (table != null) return new TableRecord(table.recordClass());
        return claimForBacking(returnType);
    }
}
