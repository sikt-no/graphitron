package no.sikt.graphql;

import org.jooq.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.jooq.impl.DSL.row;

public class NodeIdStrategy {
    SelectField<String> createId(String typeId, Field<?>... keyColumnFields) {
        return row(keyColumnFields)
                .mapping(
                        String.class,
                        Functions.nullOnAnyNull(keyColumns ->
                                createId(typeId, Arrays.toString(keyColumns))
                        )
                );
    }

    protected static String createId(String typeId, String... keyColumns) {
        var csv = Arrays
                .stream(keyColumns)
                .map(x -> x.replace(",", "%2C"))
                .collect(Collectors.joining(","));
        var id = String.format("%s:%s", typeId, csv);
        return enc(id);
    }

    protected static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    protected static String getTypeId(String base64EncodedId) {
        String id = dec(base64EncodedId);
        return getTypeIdPartOf(base64EncodedId, id);
    }

    private static String getTypeIdPartOf(String base64Id, String id) {
        if (id.indexOf(':') < 0) {
            throw new IllegalArgumentException(String.format("%s (%s) is not a valid id", base64Id, id));
        }
        return id.substring(0, id.indexOf(':'));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("%s contains illegal characters", s), e);
        }
    }


    Condition hasIds(String typeId, Set<String> base64Ids, Field<?>... keyColumnFields) {
        var rows = getRows(typeId, keyColumnFields, base64Ids);
        return row(keyColumnFields).in(rows);
    }



    List<? extends RowN> getRows(String typeId, Field<?>[] fields, Set<String> base64Ids) {
        return base64Ids.stream().map(base64Id -> {
            String[] values = unpack(typeId, fields, base64Id);

            return row(IntStream.range(0, values.length)
                    .mapToObj(i -> {
                        var f = fields[i];
                        return fieldValue(f, values[i]);
                    }).toList());
        }).toList();
    }


    String[] unpack(String typeId, Field<?>[] fields, String base64Id) {
        String id = dec(base64Id);
        var foundTypeId = getTypeIdPartOf(id, id);
        var keyPart = id.substring(id.indexOf(':') + 1);

        if (!Objects.equals(typeId, foundTypeId)) {
            throw new IllegalArgumentException("TypeId" + typeId + " does not match foundTypeId" + foundTypeId);
        }

        var values = Arrays
                .stream(keyPart.split(","))
                .map(x -> x.replace("%2C", ","))
                .toArray(String[]::new);

        if (values.length != fields.length) {
            throw new IllegalArgumentException(String.format("The number of base64 encoded ids: %s needs to match the number of key columns %s",
                    values.length, fields.length));
        }
        return values;
    }


    private static <T> T fieldValue(Field<T> field, String value) {
        var fieldType = field.getDataType().getType();
        if (fieldType.isAssignableFrom(OffsetDateTime.class)) {
            return (T) OffsetDateTime.parse(value);
        } else if (fieldType.isAssignableFrom(LocalDate.class)) {
            return (T) LocalDate.parse(value);
        }

        return field.getDataType().convert(value);
    }

}
