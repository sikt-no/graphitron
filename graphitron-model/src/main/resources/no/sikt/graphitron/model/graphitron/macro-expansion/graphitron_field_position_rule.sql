-- A connection's fields, its edge's fields, and the fields of the type its pageInfo field names.
SELECT f.graph_name, f.type_name, f.field_name, p.position
  FROM graphitron_connection c
  JOIN graphitron_field f ON f.graph_name = c.graph_name AND f.type_name = c.type_name
  JOIN graphitron_connection_position p ON p.role = 'CONNECTION' AND p.field_name = f.field_name
UNION
SELECT f.graph_name, f.type_name, f.field_name, p.position
  FROM graphitron_edge e
  JOIN graphitron_field f ON f.graph_name = e.graph_name AND f.type_name = e.type_name
  JOIN graphitron_connection_position p ON p.role = 'EDGE' AND p.field_name = f.field_name
UNION
SELECT f.graph_name, f.type_name, f.field_name, p.position
  FROM graphitron_connection c
  JOIN graphitron_connection_position pp ON pp.position = 'PAGE_INFO'
  JOIN graphitron_field pf
    ON pf.graph_name = c.graph_name AND pf.type_name = c.type_name AND pf.field_name = pp.field_name
  JOIN graphitron_field f ON f.graph_name = pf.graph_name AND f.type_name = pf.named_type
  JOIN graphitron_connection_position p ON p.role = 'PAGE_INFO' AND p.field_name = f.field_name
