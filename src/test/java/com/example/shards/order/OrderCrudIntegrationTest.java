package com.example.shards.order;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트가 띄운 실제 HTTP 서버를 통해 PostgreSQL까지 확인합니다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCrudIntegrationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ShardConnectionRegistry shardConnectionRegistry;

    private UUID testOrderId;
    private long testUserId;

    @ParameterizedTest(name = "user {0}, bucket {1}, shard {2}")
    @CsvSource({
            "1, 3, shardA, shardB",
            "101, 10, shardB, shardA"
    })
    void completesOrderLifecycle(
            long userId,
            int expectedBucketId,
            String expectedShard,
            String otherShard
    ) throws IOException, InterruptedException {
        testUserId = userId;

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            String createBody = "{\"userId\":" + userId + ",\"amount\":15000.00}";
            JsonNode created = jsonMapper.readTree(
                    send(client, "POST", "/orders", createBody, 201)
            );
            testOrderId = UUID.fromString(created.path("orderId").stringValue());
            assertThat(created.path("userId").longValue()).isEqualTo(userId);
            assertThat(created.path("bucketId").intValue()).isEqualTo(expectedBucketId);
            assertThat(created.path("amount").decimalValue()).isEqualByComparingTo("15000.00");
            assertThat(created.path("status").stringValue()).isEqualTo("CREATED");

            // 응답의 버킷 번호와 실제 저장된 DB를 각각 확인합니다.
            assertThat(countTestOrder(expectedShard)).as("rows on %s", expectedShard).isEqualTo(1);
            assertThat(countTestOrder(otherShard)).as("rows on %s", otherShard).isZero();

            String orderPath = "/orders/" + testOrderId;
            String userQuery = "?userId=" + userId;
            String detailPath = orderPath + userQuery;

            JsonNode detail = jsonMapper.readTree(send(client, "GET", detailPath, null, 200));
            assertThat(detail).isEqualTo(created);
            assertThat(listOrderIds(client, userId)).contains(testOrderId.toString());

            JsonNode amountUpdated = jsonMapper.readTree(send(
                    client, "PATCH", orderPath + "/amount" + userQuery, "{\"amount\":18000.50}", 200
            ));
            assertThat(amountUpdated.path("amount").decimalValue()).isEqualByComparingTo("18000.50");

            JsonNode statusUpdated = jsonMapper.readTree(send(
                    client, "PATCH", orderPath + "/status" + userQuery, "{\"status\":\"PAID\"}", 200
            ));
            assertThat(statusUpdated.path("status").stringValue()).isEqualTo("PAID");

            // 별도 요청으로 수정된 값이 다시 조회되는지 확인합니다.
            JsonNode afterUpdates = jsonMapper.readTree(send(client, "GET", detailPath, null, 200));
            assertThat(afterUpdates.path("amount").decimalValue()).isEqualByComparingTo("18000.50");
            assertThat(afterUpdates.path("status").stringValue()).isEqualTo("PAID");

            assertThat(send(client, "DELETE", detailPath, null, 204)).isEmpty();
            JsonNode notFound = jsonMapper.readTree(send(client, "GET", detailPath, null, 404));
            assertThat(notFound.path("title").stringValue()).isEqualTo("Order not found");
            assertThat(listOrderIds(client, userId)).doesNotContain(testOrderId.toString());
            assertThat(countTestOrder(expectedShard)).as("rows after deletion").isZero();
        }
    }

    @AfterEach
    void removeTestOrderIfPresent() {
        if (testOrderId == null) {
            return;
        }

        // 검사가 중간에 실패해도 이번 실행에서 받은 주문 UUID만 정리합니다.
        for (String shard : List.of("shardA", "shardB")) {
            shardConnectionRegistry.getJdbcTemplate(shard).update(
                    "DELETE FROM orders WHERE order_id = ? AND user_id = ?", testOrderId, testUserId
            );
        }
    }

    private long countTestOrder(String shard) {
        return shardConnectionRegistry.getJdbcTemplate(shard).queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id = ?", Long.class, testOrderId
        );
    }

    private List<String> listOrderIds(HttpClient client, long userId)
            throws IOException, InterruptedException {
        JsonNode orders = jsonMapper.readTree(send(client, "GET", "/orders?userId=" + userId, null, 200));
        assertThat(orders.isArray()).isTrue();
        return orders.valueStream().map(order -> order.path("orderId").stringValue()).toList();
    }

    private String send(HttpClient client, String method, String path, String body, int expectedStatus)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s %s, response: %s", method, path, response.body())
                .isEqualTo(expectedStatus);
        return response.body();
    }
}
