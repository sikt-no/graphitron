package no.sikt.graphitron.example.generated.graphitron.model;

import java.io.Serializable;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;

public class CustomerTable implements Serializable {
    private String id;

    public CustomerTable() {
    }

    public CustomerTable(String id) {
        this.id = id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final CustomerTable that = (CustomerTable) obj;
        return Objects.equals(id, that.id);
    }
}
