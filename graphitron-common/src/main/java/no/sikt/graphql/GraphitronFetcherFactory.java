package no.sikt.graphql;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.LightDataFetcher;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;

import java.util.function.Supplier;

/**
 * Factory for lightweight data fetchers used by the record-based output pipeline.
 * These fetchers resolve fields directly from a jOOQ {@link Record} in the source position,
 * eliminating the need for DTO/TypeMapper classes.
 */
public class GraphitronFetcherFactory {

    /** Resolves a scalar column directly from the jOOQ Record in source position. */
    public static <T> LightDataFetcher<T> field(Field<T> jooqField) {
        return new LightDataFetcher<>() {
            @Override
            public T get(GraphQLFieldDefinition fieldDef, Object source, Supplier<DataFetchingEnvironment> envSupplier) {
                return ((Record) source).get(jooqField);
            }

            @Override
            public T get(DataFetchingEnvironment env) {
                return ((Record) env.getSource()).get(jooqField);
            }
        };
    }

    /** Resolves an inline nested single object (many-to-one) from the source Record. */
    public static LightDataFetcher<Record> nestedRecord(String alias) {
        return new LightDataFetcher<>() {
            @Override
            public Record get(GraphQLFieldDefinition fieldDef, Object source, Supplier<DataFetchingEnvironment> envSupplier) {
                return ((Record) source).get(alias, Record.class);
            }

            @Override
            public Record get(DataFetchingEnvironment env) {
                return ((Record) env.getSource()).get(alias, Record.class);
            }
        };
    }

    /** Resolves an inline nested list (one-to-many) from the source Record. */
    @SuppressWarnings("unchecked")
    public static LightDataFetcher<Result<Record>> nestedResult(String alias) {
        return new LightDataFetcher<>() {
            @Override
            public Result<Record> get(GraphQLFieldDefinition fieldDef, Object source, Supplier<DataFetchingEnvironment> envSupplier) {
                return ((Record) source).get(alias, Result.class);
            }

            @Override
            public Result<Record> get(DataFetchingEnvironment env) {
                return ((Record) env.getSource()).get(alias, Result.class);
            }
        };
    }
}
