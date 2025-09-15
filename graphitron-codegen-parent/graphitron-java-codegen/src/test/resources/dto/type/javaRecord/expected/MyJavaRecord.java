package no.sikt.graphitron.example.generated.graphitron.model;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;

public class MyJavaRecord {
    private String myString;

    public MyJavaRecord() {
    }

    public String getMyString() {
        return myString;
    }

    public void setMyString(String myString) {
        this.myString = myString;
    }

    @Override
    public int hashCode() {
        return Objects.hash(myString);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final MyJavaRecord that = (MyJavaRecord) obj;
        return Objects.equals(myString, that.myString);
    }
}