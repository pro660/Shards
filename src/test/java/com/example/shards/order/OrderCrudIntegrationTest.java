package com.example.shards.order;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Autowired
    private BucketMappingRepository mappings;

    // 소유자가 C로 바뀐 뒤에도 이번 테스트가 만든 UUID만 실제 저장 위치에서 정리합니다.
    private final Map<String, Set<UUID>> fixtures = new LinkedHashMap<>();
    private UUID testOrderId;

    @ParameterizedTest(name = "user {0}, bucket {1}")
    @CsvSource({"1, 3", "101, 10"})
    void completesOrderLifecycle(long userId, int expectedBucketId) throws IOException, InterruptedException {
        String expectedShard = ownerOf(expectedBucketId);
        String otherShard = otherThan(expectedShard);
        try (HttpClient client = newClient()) {
            JsonNode created = createTestOrder(client, userId);
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

    @Test
    void returnsBadRequestForMalformedJson() throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            assertProblem(client, "POST", "/orders", "{\"userId\":1,\"amount\":}",
                    400, "Invalid request body", "요청 JSON의 형식");
        }
    }

    @Test
    void rejectsNegativeAmountWithoutChangingOrder() throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            JsonNode created = createTestOrder(client, 1L);
            String orderPath = "/orders/" + testOrderId;

            assertProblem(client, "PATCH", orderPath + "/amount?userId=1", "{\"amount\":-1}",
                    400, "Invalid order request", "주문 금액은 0 이상");

            JsonNode unchanged = jsonMapper.readTree(send(client, "GET", orderPath + "?userId=1", null, 200));
            assertThat(unchanged).isEqualTo(created);
        }
    }

    @Test
    void returnsNotFoundForUnknownOrder() throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            String path = "/orders/" + UUID.randomUUID() + "?userId=1";
            assertProblem(client, "GET", path, null,
                    404, "Order not found", "해당 사용자의 주문을 찾을 수 없습니다.");
        }
    }

    @Test
    void returnsNotFoundForAnotherUsersOrderInSameBucket() throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            JsonNode created = createTestOrder(client, 1L);
            String orderPath = "/orders/" + testOrderId;

            // 사용자 1과 25는 버킷 3을 공유하므로 같은 DB에서 사용자 조건을 검사합니다.
            assertProblem(client, "GET", orderPath + "?userId=25", null,
                    404, "Order not found", "해당 사용자의 주문을 찾을 수 없습니다.");
            assertProblem(client, "DELETE", orderPath + "?userId=25", null,
                    404, "Order not found", "해당 사용자의 주문을 찾을 수 없습니다.");

            JsonNode unchanged = jsonMapper.readTree(send(client, "GET", orderPath + "?userId=1", null, 200));
            assertThat(unchanged).isEqualTo(created);
        }
    }

    @Test
    void returnsConflictWithoutChangingPaidOrder() throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            createTestOrder(client, 1L);
            String orderPath = "/orders/" + testOrderId;
            String statusPath = orderPath + "/status?userId=1";
            JsonNode paid = jsonMapper.readTree(send(client, "PATCH", statusPath, "{\"status\":\"PAID\"}", 200));
            assertThat(paid.path("status").stringValue()).isEqualTo("PAID");

            assertProblem(client, "PATCH", statusPath, "{\"status\":\"CANCELLED\"}",
                    409, "Order status conflict", "PAID에서 CANCELLED로 변경할 수 없습니다.");

            JsonNode unchanged = jsonMapper.readTree(send(client, "GET", orderPath + "?userId=1", null, 200));
            assertThat(unchanged).isEqualTo(paid);
        }
    }

    @ParameterizedTest(name = "count includes only the owner of bucket {1} for user {0}")
    @CsvSource({"1, 3", "101, 10"})
    void countsOnlyOrdersOnTheirOwningShard(long userId, int bucketId)
            throws IOException, InterruptedException {
        String owner = ownerOf(bucketId);
        String otherShard = otherThan(owner);
        try (HttpClient client = newClient()) {
            // 각 버킷의 소유 DB를 직접 조회해 API 집계와 독립적인 기준값을 만듭니다.
            long baseline = 0;
            for (BucketMapping mapping : mappings.findAll()) {
                baseline += shardConnectionRegistry.getJdbcTemplate(mapping.connectionKey()).queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE bucket_id = ?", Long.class, mapping.bucketId());
            }
            assertThat(readTotalCount(client)).isEqualTo(baseline);

            JsonNode original = createTestOrder(client, userId);
            assertThat(readTotalCount(client)).isEqualTo(baseline + 1);

            // 같은 주문을 다른 DB에 넣어 버킷 이동 중 두 행이 공존하는 상황을 만듭니다.
            trackFixture(otherShard, testOrderId);
            shardConnectionRegistry.getJdbcTemplate(otherShard).update("""
                    INSERT INTO orders (order_id, user_id, bucket_id, amount, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    testOrderId, userId, bucketId, original.required("amount").decimalValue(),
                    original.required("status").stringValue(),
                    OffsetDateTime.parse(original.required("createdAt").stringValue()),
                    OffsetDateTime.parse(original.required("updatedAt").stringValue())
            );
            assertThat(countTestOrder(owner)).isEqualTo(1);
            assertThat(countTestOrder(otherShard)).isEqualTo(1);
            assertThat(readTotalCount(client)).isEqualTo(baseline + 1);

            // 원본을 삭제한 뒤에는 복사본이 남아 있어도 합계에서 빠져야 합니다.
            send(client, "DELETE", "/orders/" + testOrderId + "?userId=" + userId, null, 204);
            assertThat(countTestOrder(otherShard)).isEqualTo(1);
            assertThat(readTotalCount(client)).isEqualTo(baseline);
        }
    }

    @Test
    void listsRecentOrdersAcrossShardsInGlobalOrder() throws IOException, InterruptedException {
        UUID newestId = UUID.randomUUID();
        // 시각이 같을 때 PostgreSQL UUID 순서에서 8로 시작하는 값이 7보다 앞섭니다.
        UUID higherId = new UUID(Long.MIN_VALUE, UUID.randomUUID().getLeastSignificantBits());
        UUID lowerId = new UUID(Long.MAX_VALUE, UUID.randomUUID().getLeastSignificantBits());
        UUID copyOnlyId = UUID.randomUUID();
        OffsetDateTime tiedAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        List<String> fixtureIds = List.of(newestId.toString(), higherId.toString(), lowerId.toString());

        try (HttpClient client = newClient()) {
            String owner = ownerOf(3);
            String copy = otherThan(owner);
            insertOrderFixture(owner, lowerId, 1L, 3, tiedAt);
            insertOrderFixture(ownerOf(10), higherId, 101L, 10, tiedAt);
            insertOrderFixture(owner, newestId, 1L, 3, tiedAt.plusSeconds(1));
            insertOrderFixture(copy, lowerId, 1L, 3, tiedAt);
            insertOrderFixture(copy, copyOnlyId, 1L, 3, tiedAt.plusSeconds(2));

            JsonNode orders = jsonMapper.readTree(send(client, "GET", "/orders/all", null, 200));
            assertThat(orders.isArray()).isTrue();
            assertThat(orders.size()).isLessThanOrEqualTo(50);
            List<String> orderIds = orders.valueStream().map(order -> order.path("orderId").stringValue()).toList();

            assertThat(orderIds.stream().filter(fixtureIds::contains).toList()).containsExactlyElementsOf(fixtureIds);
            assertThat(orderIds).doesNotContain(copyOnlyId.toString());
        }
    }

    @Test
    void continuesFromLastOrderAcrossShards() throws IOException, InterruptedException {
        OffsetDateTime tiedAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        UUID firstId = UUID.randomUUID();
        UUID higherId = new UUID(Long.MIN_VALUE, UUID.randomUUID().getLeastSignificantBits());
        UUID lowerId = new UUID(Long.MAX_VALUE, UUID.randomUUID().getLeastSignificantBits());
        UUID insertedLater = UUID.randomUUID();

        try (HttpClient client = newClient()) {
            String owner = ownerOf(3);
            String controlOwner = ownerOf(10);
            insertOrderFixture(owner, firstId, 1L, 3, tiedAt.plusSeconds(1));
            insertOrderFixture(controlOwner, higherId, 101L, 10, tiedAt);
            insertOrderFixture(owner, lowerId, 1L, 3, tiedAt);
            insertOrderFixture(otherThan(owner), lowerId, 1L, 3, tiedAt);

            JsonNode firstPage = jsonMapper.readTree(send(client, "GET", "/orders/all?size=2", null, 200));
            assertThat(firstPage.size()).isEqualTo(2);
            assertThat(firstPage.get(0).required("orderId").stringValue()).isEqualTo(firstId.toString());
            JsonNode lastOrder = firstPage.get(1);
            assertThat(lastOrder.required("orderId").stringValue()).isEqualTo(higherId.toString());

            // 페이지 사이에 최신 주문이 들어와도 다음 결과는 직전에 본 주문 바로 뒤에서 시작합니다.
            insertOrderFixture(controlOwner, insertedLater, 101L, 10, tiedAt.plusSeconds(2));
            String cursorTime = URLEncoder.encode(lastOrder.required("createdAt").stringValue(), StandardCharsets.UTF_8);
            String path = "/orders/all?size=1&beforeCreatedAt=" + cursorTime
                    + "&beforeOrderId=" + lastOrder.required("orderId").stringValue();
            JsonNode nextPage = jsonMapper.readTree(send(client, "GET", path, null, 200));
            assertThat(nextPage.size()).isOne();
            assertThat(nextPage.get(0).required("orderId").stringValue()).isEqualTo(lowerId.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=51", "beforeCreatedAt=2026-01-01T00:00:00Z",
            "beforeOrderId=00000000-0000-0000-0000-000000000001"})
    void rejectsInvalidPageParameters(String query) throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            assertProblem(client, "GET", "/orders/all?" + query, null, 400, "Invalid order request",
                    query.startsWith("size") ? "size" : "beforeCreatedAt과 beforeOrderId");
        }
    }

    @AfterEach
    void removeFixtures() {
        for (var entry : fixtures.entrySet()) {
            var shard = shardConnectionRegistry.getJdbcTemplate(entry.getKey());
            for (UUID id : entry.getValue()) {
                shard.update("DELETE FROM orders WHERE order_id = ?", id);
            }
        }
    }

    private String ownerOf(int bucketId) {
        return mappings.findByBucketId(bucketId).connectionKey();
    }

    private String otherThan(String owner) {
        return "shardA".equals(owner) ? "shardB" : "shardA";
    }

    private void trackFixture(String shard, UUID id) {
        fixtures.computeIfAbsent(shard, key -> new LinkedHashSet<>()).add(id);
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private JsonNode createTestOrder(HttpClient client, long userId) throws IOException, InterruptedException {
        String body = "{\"userId\":" + userId + ",\"amount\":15000.00}";
        JsonNode created = jsonMapper.readTree(send(client, "POST", "/orders", body, 201));
        testOrderId = UUID.fromString(created.path("orderId").stringValue());
        trackFixture(ownerOf(created.required("bucketId").intValue()), testOrderId);
        return created;
    }

    private long countTestOrder(String shard) {
        return shardConnectionRegistry.getJdbcTemplate(shard).queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id = ?", Long.class, testOrderId
        );
    }

    private void insertOrderFixture(String shard, UUID orderId, long userId, int bucketId, OffsetDateTime createdAt) {
        trackFixture(shard, orderId);
        shardConnectionRegistry.getJdbcTemplate(shard).update("""
                INSERT INTO orders (order_id, user_id, bucket_id, amount, created_at, updated_at)
                VALUES (?, ?, ?, 15000.00, ?, ?)
                """, orderId, userId, bucketId, createdAt, createdAt);
    }

    private long readTotalCount(HttpClient client) throws IOException, InterruptedException {
        JsonNode response = jsonMapper.readTree(send(client, "GET", "/orders/count", null, 200));
        return response.required("totalCount").longValue();
    }

    private List<String> listOrderIds(HttpClient client, long userId)
            throws IOException, InterruptedException {
        JsonNode orders = jsonMapper.readTree(send(client, "GET", "/orders?userId=" + userId, null, 200));
        assertThat(orders.isArray()).isTrue();
        return orders.valueStream().map(order -> order.path("orderId").stringValue()).toList();
    }

    private String send(HttpClient client, String method, String path, String body, int expectedStatus)
            throws IOException, InterruptedException {
        return sendResponse(client, method, path, body, expectedStatus).body();
    }

    private void assertProblem(
            HttpClient client, String method, String path, String body,
            int expectedStatus, String expectedTitle, String expectedDetailPart
    ) throws IOException, InterruptedException {
        HttpResponse<String> response = sendResponse(client, method, path, body, expectedStatus);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("application/problem+json"));

        JsonNode problem = jsonMapper.readTree(response.body());
        assertThat(problem.path("status").intValue()).isEqualTo(expectedStatus);
        assertThat(problem.path("title").stringValue()).isEqualTo(expectedTitle);
        assertThat(problem.path("detail").stringValue()).contains(expectedDetailPart);
    }

    private HttpResponse<String> sendResponse(
            HttpClient client, String method, String path, String body, int expectedStatus
    ) throws IOException, InterruptedException {
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
        return response;
    }
}
