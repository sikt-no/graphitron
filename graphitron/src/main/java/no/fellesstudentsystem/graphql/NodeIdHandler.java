package no.fellesstudentsystem.graphql;

import org.jooq.Table;

public interface NodeIdHandler {

    Table<?> getTable(String id);
}
