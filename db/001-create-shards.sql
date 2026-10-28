CREATE TABLE shards (

    shard_id SMALLINT PRIMARY KEY CHECK (shard_id > 0),


    shard_name VARCHAR(20) NOT NULL UNIQUE,


    connection_key VARCHAR(50) NOT NULL UNIQUE,


    status VARCHAR(10) NOT NULL DEFAULT 'INACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);