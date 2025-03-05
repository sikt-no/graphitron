package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Response1;
import java.lang.String;

public class Response1TypeMapper {
    public static Response1 recordToGraphType(String response1Record, String path,
                                              RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var response1 = new Response1();

        if (select.contains(pathHere + "id1")) {
            response1.setId1(response1Record);
        }


        return response1;
    }
}
