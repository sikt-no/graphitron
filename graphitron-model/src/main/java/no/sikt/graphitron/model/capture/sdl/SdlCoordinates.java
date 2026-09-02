package no.sikt.graphitron.model.capture.sdl;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_COORDINATE;
import no.sikt.graphitron.model.sink.FactSink;

/**
 * Writes the SDL coordinate anchors, and owns the first-wins claim on each of them.
 *
 * <p>One place, because the anchor row and the won claim are the same fact: a coordinate exists
 * exactly when some declaration site first names it, and the anchor is what every other SDL
 * relation, every directive decode and every coordinate-keyed derivation hangs off. A caller that
 * took the claim without writing the anchor would leave the attribute row it goes on to write with
 * no parent, so the two are not separable steps a call site could get half right. Both producers
 * that name coordinates take their claims through here: the SDL walk, and the macro expansion that
 * mints shapes the author never wrote.
 *
 * <p>Each method returns {@code false} when the coordinate was already claimed, which is the signal
 * the caller quarantines the losing declaration on.
 */
final class SdlCoordinates {

    private final FactSink sink;

    SdlCoordinates(FactSink sink) {
        this.sink = sink;
    }

    boolean claimType(String typeName) {
        if (!sink.claim(GRAPHQL_TYPE_COORDINATE, typeName)) {
            return false;
        }
        var record = sink.dsl().newRecord(GRAPHQL_TYPE_COORDINATE);
        record.setTypeName(typeName);
        sink.add(record);
        return true;
    }

    boolean claimField(String typeName, String fieldName) {
        if (!sink.claim(GRAPHQL_FIELD_COORDINATE, typeName, fieldName)) {
            return false;
        }
        var record = sink.dsl().newRecord(GRAPHQL_FIELD_COORDINATE);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        sink.add(record);
        return true;
    }

    boolean claimArgument(String typeName, String fieldName, String argumentName) {
        if (!sink.claim(GRAPHQL_ARGUMENT_COORDINATE, typeName, fieldName, argumentName)) {
            return false;
        }
        var record = sink.dsl().newRecord(GRAPHQL_ARGUMENT_COORDINATE);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        record.setArgumentName(argumentName);
        sink.add(record);
        return true;
    }

    boolean claimEnumValue(String typeName, String valueName) {
        if (!sink.claim(GRAPHQL_ENUM_VALUE_COORDINATE, typeName, valueName)) {
            return false;
        }
        var record = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE_COORDINATE);
        record.setTypeName(typeName);
        record.setValueName(valueName);
        sink.add(record);
        return true;
    }
}
