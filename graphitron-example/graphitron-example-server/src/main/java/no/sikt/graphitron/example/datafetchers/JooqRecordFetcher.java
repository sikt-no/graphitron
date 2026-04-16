package no.sikt.graphitron.example.datafetchers;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.LightDataFetcher;
import org.jooq.Field;
import org.jooq.Record;

import java.util.function.Supplier;

public class JooqRecordFetcher {

    public static class JooqRecordAliasFetcher implements LightDataFetcher<Object> {
        private final String alias;

        public JooqRecordAliasFetcher(String alias) {
            this.alias = alias;
        }

        @Override
        public Object get(DataFetchingEnvironment env) {
            var source = (Record) env.getSource();
            return source.get(alias);
        }

        @Override
        public Object get(GraphQLFieldDefinition fieldDefinition, Object sourceObject, Supplier<DataFetchingEnvironment> environmentSupplier) throws Exception {
            return get(environmentSupplier.get());
        }

        public static Object get(DataFetchingEnvironment env, String alias) {
            return (new JooqRecordAliasFetcher(alias)).get(env);
        }
    }

    public static class JooqRecordFieldFetcher implements LightDataFetcher<Object> {
        private final Field<?> field;

        public JooqRecordFieldFetcher(Field<?> field) {
            this.field = field;
        }

        @Override
        public Object get(DataFetchingEnvironment env) {
            var source = (Record) env.getSource();
            return source.get(field);
        }

        @Override
        public Object get(GraphQLFieldDefinition fieldDefinition, Object sourceObject, Supplier<DataFetchingEnvironment> environmentSupplier) throws Exception {
            return get(environmentSupplier.get());
        }

        public static Object get(DataFetchingEnvironment env, Field<?> field) {
            return (new JooqRecordFieldFetcher(field)).get(env);
        }

        public static Object get(DataFetchingEnvironment env, Integer index) {
            var source = (Record) env.getSource();
            return source.get(index);
        }

    }
    public static class JooqRecordIndexFetcher implements LightDataFetcher<Object> {
        private final int index;

        public JooqRecordIndexFetcher(int index) {
            this.index = index;
        }

        @Override
        public Object get(DataFetchingEnvironment env) {
            var source = (Record) env.getSource();
            return source.get(index);
        }

        @Override
        public Object get(GraphQLFieldDefinition fieldDefinition, Object sourceObject, Supplier<DataFetchingEnvironment> environmentSupplier) throws Exception {
            return get(environmentSupplier.get());
        }

        public static Object get(DataFetchingEnvironment env, int index) {
            return (new JooqRecordIndexFetcher(index)).get(env);
        }
    }
}
