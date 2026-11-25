BEGIN;

DO $test$
DECLARE
    test_bucket SMALLINT;
    source_shard SMALLINT;
    target_shard SMALLINT;
    mapping_version BIGINT;
    other_bucket SMALLINT;
    other_source_shard SMALLINT;
    other_mapping_version BIGINT;
    first_job BIGINT;
    initial_phase TEXT;
    violated_constraint TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM public.migration_jobs WHERE finished_at IS NULL) THEN
        RAISE EXCEPTION 'Finish the existing migration job before running this check';
    END IF;

    SELECT shard_id INTO STRICT target_shard
    FROM public.shards
    WHERE connection_key = 'shardC';

    -- 현재 소유자가 C가 아닌 서로 다른 두 버킷으로 테스트합니다.
    SELECT b.bucket_id, b.shard_id, b.version
    INTO test_bucket, source_shard, mapping_version
    FROM public.bucket_mapping AS b
    WHERE b.shard_id <> target_shard
      AND b.state = 'NORMAL'
    ORDER BY b.bucket_id
    LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No test bucket is available. Check the initial bucket mappings.';
    END IF;

    SELECT b.bucket_id, b.shard_id, b.version
    INTO STRICT other_bucket, other_source_shard, other_mapping_version
    FROM public.bucket_mapping AS b
    WHERE b.bucket_id <> test_bucket AND b.shard_id <> target_shard AND b.state = 'NORMAL'
    ORDER BY b.bucket_id
    LIMIT 1;

    BEGIN
        INSERT INTO public.migration_jobs (bucket_id, source_shard_id, target_shard_id, source_mapping_version)
        VALUES (test_bucket, source_shard, source_shard, mapping_version);
        RAISE EXCEPTION 'A job with the same source and target was accepted';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS violated_constraint = CONSTRAINT_NAME;
        IF violated_constraint IS DISTINCT FROM 'migration_jobs_distinct_shards' THEN
            RAISE;
        END IF;
    END;
    RAISE NOTICE 'PASS: same source and target rejected';

    INSERT INTO public.migration_jobs (bucket_id, source_shard_id, target_shard_id, source_mapping_version)
    VALUES (test_bucket, source_shard, target_shard, mapping_version)
    RETURNING job_id, phase INTO first_job, initial_phase;
    IF initial_phase IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION 'Expected PENDING, got %', initial_phase;
    END IF;
    RAISE NOTICE 'PASS: new job starts in PENDING';

    BEGIN
        INSERT INTO public.migration_jobs (bucket_id, source_shard_id, target_shard_id, source_mapping_version)
        VALUES (other_bucket, other_source_shard, target_shard, other_mapping_version);
        RAISE EXCEPTION 'A second unfinished job for a different bucket was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS violated_constraint = CONSTRAINT_NAME;
        IF violated_constraint IS DISTINCT FROM 'migration_jobs_one_unfinished' THEN
            RAISE;
        END IF;
    END;
    RAISE NOTICE 'PASS: a second unfinished job for another bucket was rejected';

    BEGIN
        UPDATE public.migration_jobs SET phase = 'CANCELLED' WHERE job_id = first_job;
        RAISE EXCEPTION 'A terminal phase without finished_at was accepted';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS violated_constraint = CONSTRAINT_NAME;
        IF violated_constraint IS DISTINCT FROM 'migration_jobs_finished_phase' THEN
            RAISE;
        END IF;
    END;
    RAISE NOTICE 'PASS: cancelled job requires finished_at';

    UPDATE public.migration_jobs
    SET phase = 'CANCELLED', finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
    WHERE job_id = first_job;

    INSERT INTO public.migration_jobs (bucket_id, source_shard_id, target_shard_id, source_mapping_version)
    VALUES (test_bucket, source_shard, target_shard, mapping_version);
    RAISE NOTICE 'PASS: a new job can start after the previous job finishes';
END;
$test$;

ROLLBACK;
