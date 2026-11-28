package com.example.shards.admin;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class SqlLab {

    private static final int MAX_ROWS = 100;
    private static final int MAX_RESULTS = 8;
    private static final int MAX_COLUMNS = 40;
    private static final int MAX_CELL_LENGTH = 500;
    private static final int MAX_DISPLAY_CHARACTERS = 160_000;

    private final Semaphore sessions = new Semaphore(2);
    private final String url;
    private final String password;

    SqlLab(@Value("${SQL_LAB_PORT:15436}") int port, @Value("${SQL_LAB_PASSWORD:}") String password) {
        // Always connect to the isolated PostgreSQL service with its non-admin login.
        this.url = "jdbc:postgresql://127.0.0.1:" + port + "/sql_lab"
                + "?connectTimeout=3&socketTimeout=6&maxResultBuffer=2M"
                + "&preferQueryMode=simple&logServerErrorDetail=false";
        this.password = password;
    }

    SqlConsoleController.Result execute(String script) {
        if (script == null || script.isBlank() || script.length() > 16_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter up to 16,000 characters of PostgreSQL SQL.");
        }
        if (password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Start SQL Lab with scripts/Start-SqlLab.ps1, then restart Spring Boot.");
        }
        if (!sessions.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "SQL Lab is busy.");
        }
        try (var connection = DriverManager.getConnection(url, "sql_terminal", password);
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(4);
            statement.setMaxRows(MAX_ROWS + 1);
            statement.setEscapeProcessing(false);
            return collect(statement, statement.execute(script));
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            if (state == null || state.startsWith("08") || state.startsWith("28")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not reach SQL Lab. Check that its container is running.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PostgreSQL " + state + " · " + abbreviate(failure.getMessage(), 500));
        } finally {
            sessions.release();
        }
    }

    private static SqlConsoleController.Result collect(Statement statement, boolean hasRows) throws SQLException {
        List<SqlConsoleController.Output> results = new ArrayList<>();
        int remaining = MAX_DISPLAY_CHARACTERS;
        while (hasRows || statement.getUpdateCount() != -1) {
            if (results.size() == MAX_RESULTS || remaining <= 0) {
                return new SqlConsoleController.Result(results, true);
            }
            if (hasRows) {
                try (ResultSet rows = statement.getResultSet()) {
                    var schema = rows.getMetaData();
                    int width = Math.min(schema.getColumnCount(), MAX_COLUMNS);
                    List<String> columns = new ArrayList<>(width);
                    for (int column = 1; column <= width; column++) {
                        columns.add(schema.getColumnLabel(column));
                    }
                    var values = new ArrayList<List<String>>();
                    while (values.size() < MAX_ROWS && remaining > 0 && rows.next()) {
                        var row = new ArrayList<String>(width);
                        for (int column = 1; column <= width; column++) {
                            String value = abbreviate(rows.getString(column), MAX_CELL_LENGTH);
                            row.add(value);
                            remaining -= value == null ? 8 : value.length() + 8;
                        }
                        values.add(row);
                    }
                    results.add(new SqlConsoleController.Output(columns, values, null,
                            remaining <= 0 || rows.next() || schema.getColumnCount() > width));
                }
            } else {
                results.add(new SqlConsoleController.Output(List.of(), List.of(), statement.getUpdateCount(), false));
            }
            hasRows = statement.getMoreResults();
        }
        return new SqlConsoleController.Result(results, false);
    }

    private static String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
