package no.fellesstudentsystem.graphitron.definitions.sql;

import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;

public class SQLImplicitFKJoin {
    private final JOOQTableMapping table;
    private final String key;

    public SQLImplicitFKJoin(String table, String key) {
        this.table = table.isEmpty() ? null : new JOOQTableMapping(table);
        this.key = key;
    }

    public JOOQTableMapping getTable() {
        return table;
    }

    public boolean hasTable() {
        return table != null;
    }

    public String getKey() {
        return key;
    }
}
