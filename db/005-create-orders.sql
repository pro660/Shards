-- 해당 sql 쿼리는 메타DB가 아닌 샤드 A,B에서 직접 실행됨.
CREATE TABLE orders (
    -- 주문 번호는 나중에 Java에서 UUID로 만들어 전달.
    order_id UUID PRIMARY KEY,

    -- 사용자 번호는 양의 정수로 정합니다.
    -- 유저테이블이 없으므로 FK 필요 없음.
    user_id BIGINT NOT NULL
        CHECK (user_id > 0),

    -- Java에서 계산한 버킷 번호를 저장 후 범위 검사.
    bucket_id SMALLINT NOT NULL
        CHECK (bucket_id BETWEEN 0 AND 15),

    -- 소수점 결정 + 금액의 범위 검사.
    amount NUMERIC(12, 2) NOT NULL
        CHECK (amount BETWEEN 0 AND 9999999999.99),

    -- 주문을 만들 때 상태를 생략하면 CREATED로 전환.
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'PAID', 'CANCELLED')),

    -- 생성 시 시각을 생략하면 현재 트랜잭션의 시작 시각을 기록.
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 처음에는 생성 시각과 같은 값으로 시작.
    -- 이후 주문을 수정할 때에는 UPDATE 문에서 직접 갱신해야 함.
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);