package com.example.shards.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.meta.MigrationJob;
import com.example.shards.meta.MigrationJobRepository;
import com.example.shards.meta.OperationLocks;
import com.example.shards.meta.ShardRepository;
import com.example.shards.order.BucketOrderRepository;
import com.example.shards.order.Order;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationRunnerTest {

    private final MigrationJobRepository jobs = mock(MigrationJobRepository.class);
    private final BucketMappingRepository mappings = mock(BucketMappingRepository.class);
    private final ShardRepository shards = mock(ShardRepository.class);
    private final ShardConnectionRegistry connections = mock(ShardConnectionRegistry.class);
    private final BucketOrderRepository orders = mock(BucketOrderRepository.class);
    private final OperationLocks locks = mock(OperationLocks.class);
    private final JdbcTemplate source = mock(JdbcTemplate.class);
    private final JdbcTemplate target = mock(JdbcTemplate.class);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private final Order row = new Order(UUID.randomUUID(), 1, 3, new BigDecimal("1.00"), "CREATED", now, now);
    private final MigrationRunner runner = new MigrationRunner(jobs, mappings, shards, connections, orders, locks);

    @BeforeEach
    void stubMetadata() {
        when(mappings.findByBucketIdForShare(3))
                .thenReturn(Optional.of(new BucketMapping(3, 1, "A", "shardA", "MIGRATING", 1, "ACTIVE")));
        when(shards.findActiveConnectionKeyForShare(1)).thenReturn(Optional.of("shardA"));
        when(shards.findActiveConnectionKeyForShare(3)).thenReturn(Optional.of("shardC"));
        when(connections.getJdbcTemplate("shardA")).thenReturn(source);
        when(connections.getJdbcTemplate("shardC")).thenReturn(target);
    }

    @Test
    void checkpointsOnlyAfterTargetCommit() throws SQLException {
        MigrationJob job = stubJob("COPYING");
        Connection targetConnection = stubTargetTransaction();

        runner.advance(job.jobId());

        var calls = inOrder(orders, targetConnection, jobs);
        calls.verify(orders).copyBatch(target, List.of(row));
        calls.verify(targetConnection).commit();
        calls.verify(jobs).saveCheckpoint(job, row.orderId(), 1);
    }

    @Test
    void leavesCheckpointWhenTargetCommitFails() throws SQLException {
        MigrationJob job = stubJob("COPYING");
        Connection targetConnection = stubTargetTransaction();
        doThrow(new SQLException("test commit failure")).when(targetConnection).commit();

        assertThatThrownBy(() -> runner.advance(job.jobId())).isInstanceOf(RuntimeException.class);
        verify(jobs, never()).saveCheckpoint(job, row.orderId(), 1);
    }

    @Test
    void stopsAtVerificationWhenRowsDiffer() {
        MigrationJob job = stubJob("VERIFYING");
        when(orders.readBatch(source, 3, null, 100)).thenReturn(List.of(row));
        when(orders.readBatch(target, 3, null, 100)).thenReturn(List.of());

        assertThatThrownBy(() -> runner.advance(job.jobId())).isInstanceOf(IllegalStateException.class);
        verify(jobs, never()).markVerified(job);
        verify(mappings, never()).switchOwner(job);
    }

    private MigrationJob stubJob(String phase) {
        MigrationJob job = new MigrationJob(1, 3, 1, 3, 1, phase, null, 0, null, null, now, now, null);
        when(jobs.findByIdForUpdate(job.jobId())).thenReturn(Optional.of(job));
        when(jobs.findById(job.jobId())).thenReturn(Optional.of(job));
        return job;
    }

    private Connection stubTargetTransaction() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(target.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(orders.readBatch(source, 3, null, 100)).thenReturn(List.of(row));
        return connection;
    }
}
