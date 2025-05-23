import java.io.Serializable;
import java.lang.Object;
import java.lang.Override;
import java.time.OffsetTime;
import java.util.Objects;

public class Customer implements Serializable {
    private OffsetTime time;

    public Customer() {
    }

    public Customer(OffsetTime time) {
        this.time = time;
    }

    public OffsetTime getTime() {
        return time;
    }

    public void setTime(OffsetTime time) {
        this.time = time;
    }

    @Override
    public int hashCode() {
        return Objects.hash(time);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final Customer that = (Customer) obj;
        return Objects.equals(time, that.time);
    }
}