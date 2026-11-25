BEGIN;

CREATE TABLE public.migration_jobs (
    job_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bucket_id SMALLINT NOT NULL REFERENCES public.bucket_mapping(bucket_id),
    source_shard_id SMALLINT NOT NULL REFERENCES public.shards(shard_id),
    target_shard_id SMALLINT NOT NULL REFERENCES public.shards(shard_id),

    -- 작업을 요청했을 때의 소유권 버전입니다. 나중에 매핑이 바뀌었는지 확인할 때 씁니다.
    source_mapping_version BIGINT NOT NULL CHECK (source_mapping_version > 0),
    phase VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (phase IN ('PENDING', 'COPYING', 'VERIFYING', 'SWITCHING', 'CLEANING', 'COMPLETED', 'CANCELLED')),

    -- 목적지에 복사가 커밋된 마지막 주문부터 이어서 처리할 수 있도록 기록합니다.
    last_copied_order_id UUID,
    copied_order_count BIGINT NOT NULL DEFAULT 0 CHECK (copied_order_count >= 0),
    verified_at TIMESTAMPTZ,

    -- 중단 원인을 기록해도 현재 단계와 체크포인트를 보존할 수 있습니다.
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,

    CONSTRAINT migration_jobs_distinct_shards
        CHECK (source_shard_id <> target_shard_id),
    CONSTRAINT migration_jobs_finished_phase
        CHECK ((phase IN ('COMPLETED', 'CANCELLED')) = (finished_at IS NOT NULL))
);

-- 미완료 작업에는 모두 같은 인덱스 키(1)를 주어 전체에서 하나만 허용합니다.
CREATE UNIQUE INDEX migration_jobs_one_unfinished
    ON public.migration_jobs ((1))
    WHERE finished_at IS NULL;

COMMIT;
