package no.sikt.graphql.helpers.query;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.TableImpl;

import javax.json.Json;
import javax.json.JsonString;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.*;

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

    public static <R extends Record> SelectField<String> getOrderByTokenForMultitableInterface(TableImpl<R> table, OrderField<?>[] orderByFields, String typeName) {
        return jsonObject(
                key("typeName").value(field(inline(typeName))),
                key("fields").value(field(getOrderByToken(table, orderByFields)))
        ).convertFrom(QueryHelper::encodeToken);
    }

    public static AfterTokenWithTypeName getOrderByValuesForMultitableInterface(DSLContext ctx, Map<String, OrderField<?>[]> orderByFields, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            var jsonText = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            var jsonReader = Json.createReader(new StringReader(jsonText));

            var val = jsonReader.readObject();

            Object[] array = val.getJsonArray("fields")
                    .stream()
                    .map(it -> it instanceof JsonString
                            ? ((JsonString) it).getString()
                            : it.toString())
                    .toArray(String[]::new);
            jsonReader.close();

            String typeName = val.getString("typeName");

            var fields = Stream.of(orderByFields.get(typeName))
                    .map(it -> it instanceof Field ? (Field<?>) it : ((SortField<?>) it).$field())
                    .toArray(Field[]::new);

            var record = ctx.newRecord(fields);
            record.fromArray(array);
            List<Object> list = record.intoList();

            return new AfterTokenWithTypeName(typeName, list.toArray());
        } catch (Exception e) {
            throw new IllegalArgumentException("Ugyldig verdi/format på token brukt til paginering (after): '" + token + "'", e);
        }
    }

    public static Map<String, Object> makeMap(Object[] row, String[] labels) {
        if (Arrays.stream(row).allMatch(Objects::isNull)) {
            return null;
        }

        var resultMap = new HashMap<String, Object>();
        for (int i = 0; i < row.length; i++) {
            var rowValue = row[i];
            if (rowValue == null) {
                continue;
            }
            resultMap.put(labels[i], rowValue);
        }
        return resultMap;
    }
}
