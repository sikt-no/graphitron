package no.sikt.graphql.helpers.query;

import org.jooq.*;
import org.jooq.impl.TableImpl;

import javax.json.Json;
import javax.json.JsonString;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.jsonArray;

public class QueryHelper {

    public static SortField<?>[] getSortFields(List<Index> indexes, String selectedIndexName, String sortOrder) {
        return indexes.stream()
                .filter(it -> it.getName().equals(selectedIndexName))
                .findFirst()
                .orElseThrow()
                .getFields().stream()
                .map(field -> field.$sortOrder(sortOrder.equalsIgnoreCase("ASC") ? SortOrder.ASC : SortOrder.DESC))
                .toArray(SortField<?>[]::new);
    }

    public static Object[] getOrderByValues(DSLContext ctx, OrderField<?>[] orderByFields, String token) {

        var fields = Stream.of(orderByFields)
                .map(it -> it instanceof Field ? (Field<?>) it : ((SortField<?>) it).$field())
                .toArray(Field[]::new);

        if (token == null || token.isBlank()) {
            return new Object[]{};
        }

        try {
            var jsonText = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            var jsonReader = Json.createReader(new StringReader(jsonText));
            Object[] array = jsonReader.readArray()
                    .stream()
                    .map(it -> it instanceof JsonString
                            ? ((JsonString) it).getString()
                            : it.toString())
                    .toArray(String[]::new);
            jsonReader.close();

            var record = ctx.newRecord(fields);
            record.fromArray(array);
            List<Object> list = record.intoList();

            return list.toArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Ugyldig verdi/format på token brukt til paginering (after): '" + token + "'", e);
        }
    }

    public static <R extends Record> SelectField<String> getOrderByToken(TableImpl<R> table, OrderField<?>[] orderByFields) {
        var fields = Stream.of(orderByFields)
                .map(it -> it instanceof Field ? (Field<?>) it : ((SortField<?>) it).$field())
                .toArray(Field[]::new);
        return jsonArray(table.fields(fields)).convertFrom(QueryHelper::encodeToken);
    }

    private static String encodeToken(JSON json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.data().getBytes(StandardCharsets.UTF_8));
    }
}
