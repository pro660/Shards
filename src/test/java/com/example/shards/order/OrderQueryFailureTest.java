package com.example.shards.order;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderQueryFailureTest {

    private static final List<Integer> BUCKETS_A = List.of(0, 1, 2, 3, 4, 5, 6, 7);
    private static final String DRIVER_ERROR = "driver connection details";

    private final JdbcTemplate shardA = mock(JdbcTemplate.class);
    private final DataSource shardBDataSource = mock(DataSource.class);
    private final JdbcTemplate shardB = new JdbcTemplate(shardBDataSource);
    private final OrderRepository orderRepository = spy(new OrderRepository());
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BucketMappingRepository mappings = mock(BucketMappingRepository.class);
        ShardConnectionRegistry registry = mock(ShardConnectionRegistry.class);
        List<BucketMapping> initialMappings = IntStream.range(0, BucketHasher.BUCKET_COUNT)
                .mapToObj(bucket -> bucket < 8
                        ? new BucketMapping(bucket, 1, "A", "shardA", "NORMAL", 1L, "ACTIVE")
                        : new BucketMapping(bucket, 2, "B", "shardB", "NORMAL", 1L, "ACTIVE"))
                .toList();
        when(mappings.findAll()).thenReturn(initialMappings);
        when(registry.getJdbcTemplate("shardA")).thenReturn(shardA);
        when(registry.getJdbcTemplate("shardB")).thenReturn(shardB);

        OrderQueryService service = new OrderQueryService(mappings, registry, orderRepository);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(mock(OrderService.class), service))
                .setControllerAdvice(new OrderExceptionHandler())
                .build();
    }

    @Test
    void rejectsPartialCountWhenSecondShardCannotConnect() throws Exception {
        doReturn(4L).when(orderRepository).countByBucketIds(shardA, BUCKETS_A);
        when(shardBDataSource.getConnection()).thenThrow(new SQLException(DRIVER_ERROR, "08001"));

        JsonNode problem = expectUnavailable("/orders/count");

        assertThat(problem.has("totalCount")).isFalse();
        var queries = inOrder(orderRepository, shardBDataSource);
        queries.verify(orderRepository).countByBucketIds(shardA, BUCKETS_A);
        queries.verify(shardBDataSource).getConnection();
    }

    @Test
    void rejectsPartialListWhenSecondShardLosesConnectionDuringQuery() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        UUID orderId = UUID.randomUUID();
        Order order = new Order(orderId, 1L, 3, new BigDecimal("15000.00"), "CREATED", createdAt, createdAt);
        doReturn(List.of(order)).when(orderRepository).findRecentByBucketIds(shardA, BUCKETS_A, 1, null, null);
        Connection connection = mock(Connection.class);
        when(shardBDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException(DRIVER_ERROR, "08006"));

        JsonNode problem = expectUnavailable("/orders/all?size=1");

        assertThat(problem.toString()).doesNotContain(orderId.toString());
        var queries = inOrder(orderRepository, connection);
        queries.verify(orderRepository).findRecentByBucketIds(shardA, BUCKETS_A, 1, null, null);
        queries.verify(connection).prepareStatement(anyString());
    }

    private JsonNode expectUnavailable(String path) throws Exception {
        byte[] body = mvc.perform(get(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsByteArray();

        JsonNode problem = jsonMapper.readTree(body);
        assertThat(problem.isObject()).isTrue();
        assertThat(problem.path("status").intValue()).isEqualTo(503);
        assertThat(problem.path("title").stringValue()).isEqualTo("Order query unavailable");
        assertThat(problem.toString()).doesNotContain("shardB", DRIVER_ERROR);
        return problem;
    }
}
