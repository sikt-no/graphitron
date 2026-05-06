package no.sikt.graphitron.codereferences.dummyreferences;

import graphql.schema.DataFetchingEnvironment;

/**
 * Test fixtures for {@code R88}'s pipeline-tier accessor-resolution coverage. Each public type
 * pins one shape the {@link no.sikt.graphitron.rewrite.ClassAccessorResolver} either accepts or
 * rejects when classifying an SDL field on a {@code @record}-Java-backed parent.
 *
 * <p>Co-located with the other dummy references so SDL fixtures inside
 * {@link no.sikt.graphitron.rewrite.validation.RecordFieldAccessorValidationTest} can name them
 * by FQN through the same {@code @record(record: {className: "..."})} form the rest of the
 * fixtures use.
 */
public final class R88AccessorFixtures {

    private R88AccessorFixtures() {}

    /**
     * The user's bug case: SDL declares {@code sakId: ID} but the POJO has only {@code getSak()}
     * (no {@code getSakId} or bare {@code sakId} accessor). Resolver rejects with "no method
     * named getSakId; no method named sakId".
     */
    public static final class MissingGetterPojo {
        public String getSak() { return ""; }
    }

    /**
     * Return-type-mismatch case: a method by the right name exists but returns the wrong type.
     * SDL {@code sakId: ID} expects {@code String}; this POJO's {@code getSakId} returns
     * itself, which is incompatible. Resolver rejects with the type-mismatch arm of the
     * diagnostic.
     */
    public static final class ReturnTypeMismatchPojo {
        public ReturnTypeMismatchPojo getSakId() { return null; }
    }

    /**
     * Argument-bearing per-arg match: SDL {@code fooBar(x: String): String} matches
     * {@code fooBar(String x): String}. Arity and per-parameter type match; resolution
     * succeeds via the bare-name candidate.
     */
    public static final class ArgumentBearingPojo {
        public String fooBar(String x) { return x; }
    }

    /**
     * {@code @field(name:)} override path: SDL field {@code sakId: ID @field(name: "sak")}
     * redirects accessor resolution to {@code sak}. {@code getSak} matches the get-prefixed
     * candidate, returning {@code String} (assignable to the SDL field's resolved Java type).
     */
    public static final class OverridePojo {
        public String getSak() { return ""; }
    }

    /**
     * Public-field fallback: no method named {@code title} or {@code getTitle}, but a public
     * field of the right name and type. Resolver returns {@code FieldRead}.
     */
    public static final class PublicFieldPojo {
        public String title;
    }

    /**
     * Argument-bearing full-environment match: the candidate method takes a single
     * {@link DataFetchingEnvironment} parameter rather than per-SDL-arg parameters. Resolver
     * accepts via {@code paramsMatch}'s full-env branch.
     */
    public static final class FullEnvPojo {
        public String fooBar(DataFetchingEnvironment env) { return ""; }
    }

    /**
     * Boolean {@code is<X>} candidate: the {@code is}-prefixed candidate is added to the list
     * only when the SDL field's resolved Java type is {@code boolean} / {@code Boolean}.
     * {@code isActive} matches under that branch despite the absence of {@code getActive}.
     */
    public static final class BooleanPojo {
        public boolean isActive() { return true; }
    }

    /**
     * Java record canonical component accessor: {@code sakId()} is the auto-generated bare-name
     * accessor. Under {@code RECORD_FIRST} candidate ordering this matches first.
     */
    public record BareNameRecord(String sakId) {}

    /**
     * Java record with no component matching the SDL field name. The resolver tries bare-name,
     * get-prefixed, and public-field candidates (no public field exists either) and produces a
     * {@code Rejected}.
     */
    public record MissingComponentRecord(String otherField) {}
}
