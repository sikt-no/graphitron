package no.sikt.graphitron.codereferences.dummyreferences;

import graphql.schema.DataFetchingEnvironment;

/**
 * Test fixtures for the pipeline-tier accessor-resolution coverage. Each public type
 * pins one shape the {@link no.sikt.graphitron.rewrite.ClassAccessorResolver} either accepts or
 * rejects when classifying an SDL field on a JavaRecord-backed parent.
 *
 * <p>Co-located with the other dummy references so SDL fixtures inside
 * {@link no.sikt.graphitron.rewrite.validation.RecordFieldAccessorValidationTest} can name them
 * by FQN.
 */
public final class R88AccessorFixtures {

    private R88AccessorFixtures() {}

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
     * {@code getFooBar(String x): String}. Arity and per-parameter type match; resolution
     * succeeds via the get-prefixed candidate.
     */
    public static final class ArgumentBearingPojo {
        public String getFooBar(String x) { return x; }
    }

    /**
     * Argument-bearing full-environment match: the candidate method takes a single
     * {@link DataFetchingEnvironment} parameter rather than per-SDL-arg parameters. Resolver
     * accepts via {@code paramsMatch}'s full-env branch.
     */
    public static final class FullEnvPojo {
        public String getFooBar(DataFetchingEnvironment env) { return ""; }
    }

    /**
     * The same SDL fields over accessors of the wrong type: {@code Integer[]} is not assignable
     * to {@code String[]}, nor {@code String} to {@code List}. Both are rejected, which holds
     * only while the scalar's array or parameterized type is erased to its runtime class rather
     * than to {@code Object}.
     */
    public record CollectionScalarMismatchRecord(Integer[] tags, String tagList) {}
}
