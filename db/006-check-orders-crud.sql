-- CRUD test at Shard_A.
-- user_id=101, bucket_id=3 is test example.
-- It's not meaning real hash calculation result.

-- Batch of tasks start.
BEGIN;

INSERT INTO orders (
    order_id,
    user_id,
    bucket_id,
    amount
)
VALUES (
    '00000000-0000-4000-8000-000000000012',
    101,
    3,
    15000.00
);

SELECT
    order_id,
    user_id,
    bucket_id,
    amount,
    status,
    created_at,
    updated_at
FROM orders
WHERE order_id = '00000000-0000-4000-8000-000000000012';


UPDATE orders
SET amount = 18000.00,
    updated_at = statement_timestamp()
WHERE order_id = '00000000-0000-4000-8000-000000000012'
RETURNING order_id, amount, status, updated_at;


DELETE FROM orders
WHERE order_id = '00000000-0000-4000-8000-000000000012'
RETURNING order_id;


SELECT COUNT(*) AS remaining_test_orders
FROM orders
WHERE order_id = '00000000-0000-4000-8000-000000000012';

-- NOT COMMIT, JUST ROLLBACK.
ROLLBACK;