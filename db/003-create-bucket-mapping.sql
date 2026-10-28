CREATE TABLE bucket_mapping (
    bucket_id SMALLINT PRIMARY KEY
        CHECK (bucket_id BETWEEN 0 AND 15),

    shard_id SMALLINT NOT NULL
        REFERENCES shards(shard_id),

    state VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        CHECK (state IN ('NORMAL', 'MIGRATING')),

    version BIGINT NOT NULL DEFAULT 1
        CHECK (version > 0)
);