INSERT INTO bucket_mapping (bucket_id, shard_id)
VALUES
    -- 버킷 0~7은 샤드 A(번호 1)가 담당합니다.
    (0, 1),
    (1, 1),
    (2, 1),
    (3, 1),
    (4, 1),
    (5, 1),
    (6, 1),
    (7, 1),

    -- 버킷 8~15는 샤드 B(번호 2)가 담당합니다.
    (8, 2),
    (9, 2),
    (10, 2),
    (11, 2),
    (12, 2),
    (13, 2),
    (14, 2),
    (15, 2);



SELECT
    b.bucket_id,
    s.shard_name,
    b.state,
    b.version

-- bucket_mapping을 이 SQL 안에서는 b라고 줄여 부릅니다.
FROM bucket_mapping AS b

-- shards는 s라고 줄여 부릅니다.
JOIN shards AS s
    -- 담당 샤드 번호가 같은 행끼리 연결합니다.
    ON b.shard_id = s.shard_id

-- 버킷 번호가 작은 순서대로 보여줍니다.
ORDER BY b.bucket_id;