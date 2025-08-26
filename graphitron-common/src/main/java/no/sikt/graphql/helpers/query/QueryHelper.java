package no.sikt.graphql.helpers.query;

import org.jooq.Record;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.constant.Constable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.*;

public class QueryHelper {

    public static SortField<?>[] getSortFields(TableImpl<?> table, String selectedIndexName, String sortOrder) {
        var indexes = table.getIndexes();
        return indexes.stream()
                .filter(it -> it.getName().equalsIgnoreCase(selectedIndexName))
                .findFirst()
                .orElseThrow()
                .getFields().stream()
                .map(field -> field.$sortOrder(sortOrder.equalsIgnoreCase("ASC") ? SortOrder.ASC : SortOrder.DESC))
                .map(it -> it.$field(table.field(it.$field())))
                .toArray(SortField<?>[]::new);
    }

    public static Field<?>[] getOrderByValues(DSLContext ctx, OrderField<?>[] orderByFields, String token) {

        var fields = Stream.of(orderByFields)
                .map(it -> it instanceof Field ? (Field<?>) it : ((SortField<?>) it).$field())
                .toArray(Field[]::new);

        if (token == null || token.isBlank()) {
            return new Field[]{noField()};
        }

        try {
            var jsonText = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            Object[] array = new JSONArray(jsonText).toList().toArray();

            var record = ctx.newRecord(fields);
            record.fromArray(array);
            List<Object> list = record.intoList();

            return IntStream.range(0, fields.length)
                    .mapToObj(i -> DSL.val(list.get(i), fields[i].getDataType()))
                    .toArray(Field[]::new);
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
            var jsonObject = new JSONObject(jsonText);

            Object[] array = jsonObject.getJSONArray("fields").toList().toArray();
            String typeName = jsonObject.getString("typeName");

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

    public static Map<String, Object> makeObjectMap(String[] labels, Object[] row) {
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

    public static SelectField<Map> objectRow(String label, Object row) {
        if (row == null) {
            return null;
        }
        return DSL.row(row).mapping(Map.class, r -> Map.of(label, row));
    }

    public static SelectField<Map> objectRow(List<String> labels, List<Object> row) {
        if (row.stream().allMatch(Objects::isNull)) {
            return null;
        }
        return DSL.row(row).mapping(Map.class, r -> makeObjectMap(labels.toArray(new String[0]), r));
    }

    private static <T, U> HashMap<T, U> buildEnumMap(List<T> from, List<U> to) {
        var i1 = from.iterator();
        var i2 = to.iterator();
        var map = new HashMap<T, U>();
        while (i1.hasNext()) {
            map.put(i1.next(), i2.next());
        }
        return map;
    }

    // Extend Constable to remove potential ambiguity with the generics being Object.
    public static <T extends Constable, U extends Constable> U makeEnumMap(T value, List<T> from, List<U> to) {
        if (value == null) {
            return null;
        }
        var map = buildEnumMap(from, to);
        return map.getOrDefault(value, null);
    }

    public static <T extends Constable, U extends Constable> List<U> makeEnumMap(List<T> values, List<T> from, List<U> to) {
        var map = buildEnumMap(from, to);
        return values
                .stream()
                .map(it -> it == null ? null : map.getOrDefault(it, null))
                .toList();
    }
}