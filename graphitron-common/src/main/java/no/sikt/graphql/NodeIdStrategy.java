package no.sikt.graphql;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.UpdatableRecordImpl;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.jooq.impl.DSL.row;

public class NodeIdStrategy {
    public SelectField<String> createId(String typeId, Field<?>... keyColumnFields) {
        return row(keyColumnFields)
                .mapping(String.class, Functions.nullOnAnyNull((Object[] values) -> {
                    String[] stringValues = Arrays.stream(values)
                            .map(Object::toString)
                            .toArray(String[]::new);
                    return createId(typeId, stringValues);
                }));
    }

    public String createId(UpdatableRecordImpl<?> record, String typeId, Field<?>... keyColumnFields) {
        var values = new String[keyColumnFields.length];
        record.into(keyColumnFields).into(values);
        return createId(typeId, values);
    }

    protected String createId(String typeId, String... keyColumns) {
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

    public String getTypeId(String base64EncodedId) {
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

    public Condition hasId(String typeId, String base64Id, Field<?>... keyColumnFields) {
        return hasIds(typeId, Set.of(base64Id), keyColumnFields);
    }

    public Condition hasId(String typeId, UpdatableRecordImpl<?> record, Field<?>... keyColumnFields) {
        return hasIds(typeId, Set.of(createId(record, typeId, keyColumnFields)), keyColumnFields);
    }

    public Condition hasIds(String typeId, List<UpdatableRecordImpl<?>> records, Field<?>... keyColumnFields) {
        return hasIds(typeId, records.stream().map(it -> createId(it, typeId, keyColumnFields)).collect(Collectors.toSet()), keyColumnFields);
    }

    public Condition hasIds(String typeId, Set<String> base64Ids, Field<?>... keyColumnFields) {
        var rows = getRows(typeId, keyColumnFields, base64Ids);
        return row(keyColumnFields).in(rows);
    }

    public <T extends UpdatableRecord<T>> void setId(UpdatableRecordImpl<T> record, String id, String typeId, List<TableField<T, ?>> keyColumnFields) {
        setId(record, id, typeId, keyColumnFields.toArray(Field[]::new));
    }

    private <T extends UpdatableRecord<T>> void setId(UpdatableRecordImpl<T> record, String id, String typeId, Field<?>... keyColumnFields) {
        var values = new String[keyColumnFields.length];
        if (id != null) {
            values = unpack(typeId, keyColumnFields, id);
        }
        record.from(values, keyColumnFields);

        Arrays.stream(keyColumnFields)
                .filter(field -> Arrays.asList(record.key().fields()).contains(field))
                .forEach(field -> record.changed(field, false));
    }

    public <T extends Record & UpdatableRecord<T>, U extends Record> void setId(UpdatableRecordImpl<T> record, ForeignKey<T, U> key, String id, String typeId, List<TableField<U, ?>> idFields) {
        setId(record, id, typeId, getSourceColumns(key, idFields));
    }

    private static <T extends Record, U extends Record> Field<?>[] getSourceColumns(ForeignKey<U, T> fk, List<TableField<T, ?>> targetNodeIdFields) {
        var mapping = new HashMap<String, TableField<U, ?>>();
        var sourceColumns = fk.getFields();
        var targetColumns = fk.getInverseKey().getFields();

        for (int i = 0; i < sourceColumns.size(); i++) {
            mapping.put(targetColumns.get(i).getName(), sourceColumns.get(i));
        }

        var missingFields = mapping.keySet().stream()
                .filter(name -> targetNodeIdFields.stream().noneMatch(it -> it.getName().equals(name)))
                .toList();

        if (!missingFields.isEmpty()) {
            throw new IllegalArgumentException("ID is missing the following fields to set the required fields for foreign key " + fk.getName() + ": " + String.join(", ", missingFields));
        }

        return targetNodeIdFields.stream()
                .map(it -> mapping.get(it.getName()))
                .toArray(Field[]::new);
    }

    private static List<? extends RowN> getRows(String typeId, Field<?>[] fields, Set<String> base64Ids) {
        return base64Ids.stream().map(base64Id -> {
            String[] values = unpack(typeId, fields, base64Id);

            return row(IntStream.range(0, values.length)
                    .mapToObj(i -> {
                        var f = fields[i];
                        return fieldValue(f, values[i]);
                    }).toList());
        }).toList();
    }

    private static String[] unpack(String typeId, Field<?>[] fields, String base64Id) {
        String id = dec(base64Id);
        var foundTypeId = getTypeIdPartOf(id, id);
        var keyPart = id.substring(id.indexOf(':') + 1);

        if (!Objects.equals(typeId, foundTypeId)) {
            throw new IllegalArgumentException("TypeId " + typeId + " does not match foundTypeId " + foundTypeId);
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
