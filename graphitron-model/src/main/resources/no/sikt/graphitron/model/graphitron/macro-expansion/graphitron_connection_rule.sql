SELECT c.graph_name, c.type_name, c.kind, e.named_type AS edge_type_name
  FROM graphitron_type c
  JOIN graphitron_field e
    ON e.graph_name = c.graph_name AND e.type_name = c.type_name AND e.field_name = 'edges'
  JOIN graphitron_type et
    ON et.graph_name = e.graph_name AND et.type_name = e.named_type AND et.kind = 'OBJECT'
  JOIN graphitron_field n
    ON n.graph_name = et.graph_name AND n.type_name = et.type_name AND n.field_name = 'node'
 WHERE c.kind = 'OBJECT'
