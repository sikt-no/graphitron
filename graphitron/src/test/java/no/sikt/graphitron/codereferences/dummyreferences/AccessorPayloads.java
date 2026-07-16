package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;
import java.util.Set;

/**
 * Test fixtures for the {@code R60} accessor-derived classifier path. Each public
 * type exposes a typed zero-arg accessor whose return type is a concrete jOOQ
 * {@link org.jooq.TableRecord} subtype (single, list, or set), or matches one of the rejection
 * shapes the classifier tests pin down.
 *
 * <p>Co-located with the other dummy references so the SDL fixtures inside
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilderTest} can name them by FQN through
 * the same {@code @record(record: {className: "..."})} form the lifter cases use.
 */
public final class AccessorPayloads {

    private AccessorPayloads() {}

    /** Single-accessor case: bare {@link FilmRecord} return; matches a single child field. */
    public record SinglePayload(FilmRecord film) {}

    /**
     * R370 classification-tier witness: a <em>nested</em> class-backed payload carrying an
     * {@code errors} slot. Because it is nested in {@link AccessorPayloads}, its binary name is
     * {@code AccessorPayloads$NestedErrorsPayload}; the JLS-legal source form is
     * {@code AccessorPayloads.NestedErrorsPayload}. A child {@code @service} field returning this
     * payload with an {@code @error}-union {@code errors} field classifies to a
     * {@code ChildField.ServiceRecordField} whose {@code ErrorChannel} resolves via
     * {@code FieldBuilder.resolveErrorChannel} to an
     * {@link no.sikt.graphitron.rewrite.model.ErrorChannel.PayloadClass}. Before R370 that channel's
     * {@code payloadClass()} was built with {@code ClassName.bestGuess} over the binary name and
     * carried {@code AccessorPayloads$NestedErrorsPayload} as a single simple name; after, it is
     * {@code ClassName.get(payloadCls)} = the structural {@code AccessorPayloads.NestedErrorsPayload}.
     * The canonical ctor exposes a {@code List<Object>} errors slot plus a defaulted {@code data}
     * slot, mirroring {@link SakPayload}.
     */
    public record NestedErrorsPayload(String data, List<Object> errors) {}

    /** List-accessor case: {@code List<FilmRecord>}; matches a list child field. */
    public record ListPayload(List<FilmRecord> films) {}

    /** Set-accessor case: {@code Set<FilmRecord>}; matches a list child field via the SET container. */
    public record SetPayload(Set<FilmRecord> films) {}

    /**
     * Ambiguous case: two zero-arg accessors with the same name (one direct, one prefixed
     * {@code get...}) returning {@code List<FilmRecord>}; the field name {@code films} matches
     * both, exercising the {@code AccessorDerivation.Ambiguous} arm.
     */
    public static final class AmbiguousListPayload {
        private final List<FilmRecord> a;
        private final List<FilmRecord> b;

        public AmbiguousListPayload(List<FilmRecord> a, List<FilmRecord> b) {
            this.a = a;
            this.b = b;
        }

        public List<FilmRecord> films() { return a; }
        public List<FilmRecord> getFilms() { return b; }
    }

    /**
     * Heterogeneous-element case: the accessor returns a {@code TableRecord} whose mapped
     * jOOQ table is <em>not</em> the field's {@code @table} return. The classifier should fall
     * through to the rewritten three-option AUTHOR_ERROR rather than auto-deriving (since the
     * author may have intended a non-trivial transform that {@code @sourceRow} can express).
     */
    public record HeterogeneousElementPayload(LanguageRecord films) {}

    /**
     * Cardinality-mismatch case: the field is a list ({@code [Film]}) but the accessor returns
     * a single {@link FilmRecord}. Exercises the
     * {@code AccessorDerivation.CardinalityMismatch} arm.
     */
    public record SingleAccessorOnListField(FilmRecord films) {}

    /**
     * R191 remap case: the accessor name diverges from the GraphQL field name, and the SDL field
     * carries {@code @field(name: "filmRecord")} to bridge the divergence. Exercises the
     * directive-driven accessor-name remap on a free-form record-backed parent.
     */
    public record RemappedPayload(FilmRecord filmRecord) {}

    /**
     * R201 bean-arm remap witness: a mutable-bean payload whose Java member names
     * ({@code info}, {@code failures}) diverge from the SDL field names ({@code data},
     * {@code errors}). Only {@code @field(name: "info")} / {@code @field(name: "failures")} on the
     * SDL fields can bind the setters. Getters are present so the payload's own data field also
     * resolves cleanly on the read side.
     */
    public static final class DivergentBeanErrorsPayload {
        private String info;
        private List<Object> failures;
        public DivergentBeanErrorsPayload() {}
        public void setInfo(String info) { this.info = info; }
        public void setFailures(List<Object> failures) { this.failures = failures; }
        public String getInfo() { return info; }
        public List<Object> getFailures() { return failures; }
    }

    /**
     * R201 bean-arm existence witness: a mutable-bean payload whose setters match the SDL field
     * names ({@code setData} / {@code setErrors}). A {@code @field(name:)} on the {@code data}
     * field naming a member the class does not expose (e.g. {@code "info"}) must reject, since the
     * bean predicate requires a setter to exist under the resolved base. Also serves the
     * blank-{@code @field} and ctor-reorder-free cases.
     */
    public static final class SetterErrorsPayload {
        private String data;
        private List<Object> errors;
        public SetterErrorsPayload() {}
        public void setData(String data) { this.data = data; }
        public void setErrors(List<Object> errors) { this.errors = errors; }
        public String getData() { return data; }
        public List<Object> getErrors() { return errors; }
    }

    /**
     * R201 ctor-arm remap witness: a record whose components are declared in a different order
     * than the SDL fields. SDL declares {@code data} then {@code errors}; this record declares the
     * errors slot ({@code problems}) first. Only {@code @field(name: "problems")} on the
     * errors-shaped SDL field can name the errors constructor parameter; the positional rule alone
     * would place the errors list at SDL index 1 ({@code data}, a {@code String}) and reject.
     */
    public record ReorderedErrorsPayload(List<Object> problems, String data) {}
}
