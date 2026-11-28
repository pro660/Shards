package com.example.shards.admin;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.example.shards.sharding.ShardConnectionRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SqlConsoleController {

    private final AdminAccess access;
    private final JdbcTemplate metadata;
    private final ShardConnectionRegistry registry;
    private final SqlLab lab;

    public SqlConsoleController(AdminAccess access, JdbcTemplate metadata, ShardConnectionRegistry registry, SqlLab lab) {
        this.access = access;
        this.metadata = metadata;
        this.registry = registry;
        this.lab = lab;
    }

    @PostMapping("/admin/sql-console")
    public Result execute(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestBody Query query
    ) {
        if ("lab".equals(query.database())) {
            access.requireWrite(token);
            return lab.execute(query.sql());
        }
        access.requireRead(token);
        if (query.database() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a database.");
        }
        try {
            JdbcTemplate jdbc = switch (query.database()) {
                case "metadata" -> metadata;
                case "shardA", "shardB", "shardC" -> registry.getJdbcTemplate(query.database());
                default -> throw new IllegalArgumentException("Choose Metadata, A, B or C.");
            };
            ConsoleSelect select = ConsoleSelect.parse(query.sql(), "metadata".equals(query.database()));
            return jdbc.query(connection -> {
                var statement = connection.prepareStatement(select.sql());
                statement.setQueryTimeout(3);
                for (int index = 0; index < select.parameters().size(); index++) {
                    // PostgreSQL이 비교할 열의 타입(UUID, 숫자, 시각 등)에 맞게 파라미터를 해석합니다.
                    statement.setObject(index + 1, select.parameters().get(index), Types.OTHER);
                }
                return statement;
            }, rows -> {
                var schema = rows.getMetaData();
                int width = schema.getColumnCount();
                List<String> columns = new ArrayList<>(width);
                for (int column = 1; column <= width; column++) {
                    columns.add(schema.getColumnLabel(column));
                }
                List<List<String>> values = new ArrayList<>();
                while (rows.next()) {
                    List<String> row = new ArrayList<>(width);
                    for (int column = 1; column <= width; column++) {
                        row.add(rows.getString(column));
                    }
                    values.add(row);
                }
                return new Result(List.of(new Output(columns, values, null, values.size() == select.limit())), false);
            });
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (DataAccessException failure) {
            var cause = failure.getMostSpecificCause();
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("22")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A filter value does not match its column type.");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Query failed. Check the database or narrow the query.");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> respond(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).body(error.getBody());
    }

    public record Query(String database, String sql) {}

    public record Result(List<Output> outputs, boolean truncated) {}

    public record Output(List<String> columns, List<List<String>> rows, Integer affectedRows, boolean truncated) {}
}
