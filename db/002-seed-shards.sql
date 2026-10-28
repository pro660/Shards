INSERT INTO shards (shard_id, shard_name, connection_key)
VALUES

    (1, 'A', 'shardA'),

    (2, 'B', 'shardB'),

    (3, 'C', 'shardC');

-- 등록한 내용을 샤드 번호가 작은 순서대로 조회합니다.
SELECT shard_id, shard_name, connection_key, status
FROM shards
ORDER BY shard_id;