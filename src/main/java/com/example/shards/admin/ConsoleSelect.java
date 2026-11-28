package com.example.shards.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

record ConsoleSelect(String sql, List<String> parameters, int limit) {

    private static final int MAX_ROWS = 100;
    private static final String IDENTIFIER = "[a-z][a-z0-9_]*";
    private static final String VALUE = "'(?:[^']|'')*+'|[+-]?[0-9]+(?:\\.[0-9]+)?";
    private static final String OPERATOR = "<=|>=|<>|!=|=|<|>";
    private static final String CONDITION = IDENTIFIER + "\\s*(?:" + OPERATOR + ")\\s*(?:" + VALUE + ")";
    private static final Pattern FILTER = Pattern.compile(
            "(" + IDENTIFIER + ")\\s*(" + OPERATOR + ")\\s*(" + VALUE + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT = Pattern.compile("(" + IDENTIFIER + ")(?:\\s+(asc|desc))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("count\\s*\\(\\s*\\*\\s*\\)(?:\\s+as\\s+(" + IDENTIFIER + "))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT = Pattern.compile(
            "select\\s+(?<fields>.+?)\\s+from\\s+(?:public\\.)?(?<table>" + IDENTIFIER + ")"
            + "(?:\\s+where\\s+(?<filters>" + CONDITION + "(?:\\s+and\\s+" + CONDITION + ")*))?"
            + "(?:\\s+order\\s+by\\s+(?<sorts>[^;]+?))?"
            + "(?:\\s+limit\\s+(?<limit>[0-9]{1,3}))?\\s*;?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Map<String, List<String>> METADATA = Map.of(
            "shards", List.of("shard_id", "shard_name", "connection_key", "status"),
            "bucket_mapping", List.of("bucket_id", "shard_id", "state", "version"),
            "migration_jobs", List.of("job_id", "bucket_id", "source_shard_id", "target_shard_id",
                    "source_mapping_version", "phase", "last_copied_order_id", "copied_order_count",
                    "verified_at", "created_at", "updated_at", "finished_at")
    );
    private static final Map<String, List<String>> SHARD = Map.of(
            "orders", List.of("order_id", "user_id", "bucket_id", "amount", "status", "created_at", "updated_at")
    );

    static ConsoleSelect parse(String input, boolean metadata) {
        if (input == null || input.length() > 2_000) {
            throw invalid("Enter a SELECT statement of up to 2,000 characters.");
        }
        var select = SELECT.matcher(input.strip());
        if (!select.matches()) {
            throw invalid("Use one SELECT with optional WHERE, ORDER BY and LIMIT.");
        }
        String table = lower(select.group("table"));
        List<String> columns = (metadata ? METADATA : SHARD).get(table);
        if (columns == null) {
            throw invalid("That table is unavailable on the selected database.");
        }

        // 브라우저의 SQL 원문은 JDBC로 전달하지 않습니다. 서버가 허용한 구조로 다시 만듭니다.
        String projection = projection(select.group("fields").strip(), columns);
        StringBuilder sql = new StringBuilder("SELECT ").append(projection).append(" FROM public.").append(table);
        List<String> parameters = new ArrayList<>();
        if (select.group("filters") != null) {
            var filter = FILTER.matcher(select.group("filters"));
            String separator = " WHERE ";
            while (filter.find()) {
                sql.append(separator).append(column(filter.group(1), columns)).append(' ')
                        .append(filter.group(2)).append(" ?");
                String value = filter.group(3);
                parameters.add(value.startsWith("'") ? value.substring(1, value.length() - 1).replace("''", "'") : value);
                separator = " AND ";
            }
        }
        if (select.group("sorts") != null) {
            if (projection.startsWith("COUNT(")) {
                throw invalid("COUNT(*) returns a single row and does not use ORDER BY here.");
            }
            List<String> sorts = new ArrayList<>();
            for (String field : select.group("sorts").split(",", -1)) {
                var sort = SORT.matcher(field.strip());
                if (!sort.matches()) {
                    throw invalid("ORDER BY accepts column names, optionally followed by ASC or DESC.");
                }
                sorts.add(column(sort.group(1), columns) + ("desc".equalsIgnoreCase(sort.group(2)) ? " DESC" : " ASC"));
            }
            sql.append(" ORDER BY ").append(String.join(", ", sorts));
        }
        String requestedLimit = select.group("limit");
        int limit = requestedLimit == null ? MAX_ROWS : Integer.parseInt(requestedLimit);
        if (limit < 1 || limit > MAX_ROWS) {
            throw invalid("LIMIT must be between 1 and 100.");
        }
        return new ConsoleSelect(sql.append(" LIMIT ").append(limit).toString(), List.copyOf(parameters), limit);
    }

    private static String projection(String fields, List<String> columns) {
        if (fields.equals("*")) {
            return String.join(", ", columns);
        }
        var count = COUNT.matcher(fields);
        if (count.matches()) {
            return count.group(1) == null ? "COUNT(*)" : "COUNT(*) AS \"" + lower(count.group(1)) + "\"";
        }
        return String.join(", ", Arrays.stream(fields.split(",", -1))
                .map(String::strip).map(field -> column(field, columns)).toList());
    }

    private static String column(String input, List<String> allowed) {
        String column = lower(input);
        if (!allowed.contains(column)) {
            throw invalid("Use only columns from the selected table.");
        }
        return column;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
