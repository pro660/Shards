package com.example.shards.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConsoleSelectTest {

    @Test
    void compilesTheQueryAndBindsValuesSeparately() {
        var query = ConsoleSelect.parse("""
                SELECT order_id, amount FROM public.orders
                WHERE user_id = 3 AND amount >= 100.25
                ORDER BY created_at DESC, order_id LIMIT 5;
                """, false);

        assertThat(query.sql()).isEqualTo("SELECT order_id, amount FROM public.orders WHERE user_id = ? "
                + "AND amount >= ? ORDER BY created_at DESC, order_id ASC LIMIT 5");
        assertThat(query.parameters()).containsExactly("3", "100.25");
    }

    @Test
    void treatsQuotedSqlCharactersAsDataAndCapsResults() {
        var query = ConsoleSelect.parse(
                "SELECT COUNT(*) AS matches FROM shards WHERE shard_name = 'a''; AND x = 9' AND status = 'ACTIVE';",
                true);

        assertThat(query.sql()).isEqualTo("SELECT COUNT(*) AS \"matches\" FROM public.shards "
                + "WHERE shard_name = ? AND status = ? LIMIT 100");
        assertThat(query.parameters()).containsExactly("a'; AND x = 9", "ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE orders SET amount = 0",
            "SELECT * FROM orders; DELETE FROM orders;",
            "SELECT * FROM pg_catalog.pg_roles",
            "SELECT current_setting('server_version') FROM orders",
            "SELECT * FROM orders WHERE user_id = 3 OR 1 = 1",
            "SELECT * FROM orders ORDER BY pg_catalog.pg_sleep(5)",
            "SELECT * FROM orders LIMIT 101",
            "SELECT * FROM orders FOR UPDATE",
            "SELECT * FROM migration_jobs"
    })
    void rejectsInputOutsideTheAllowedQuerySyntax(String sql) {
        assertThatIllegalArgumentException().isThrownBy(() -> ConsoleSelect.parse(sql, false));
    }
}
