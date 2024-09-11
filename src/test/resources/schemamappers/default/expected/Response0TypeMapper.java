package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Response0;
import java.lang.String;

public class Response0TypeMapper {
    public static Response0 recordToGraphType(String response0Record, String path,
                                              RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var response0 = new Response0();

        if (select.contains(pathHere + "id")) {
            response0.setId(response0Record);
        }


        return response0;
    }
}
