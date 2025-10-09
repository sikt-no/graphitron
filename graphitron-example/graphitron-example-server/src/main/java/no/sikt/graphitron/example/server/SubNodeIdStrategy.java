package no.sikt.graphitron.example.server;

import jakarta.inject.Singleton;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.Field;
import org.jooq.impl.UpdatableRecordImpl;

@Singleton
public class SubNodeIdStrategy extends NodeIdStrategy {

    @Override
    public void setId(UpdatableRecordImpl<?> record, String id, String typeId, Field<?>... idFields) {
        super.setFields(record, id, typeId, idFields);
    }
}
