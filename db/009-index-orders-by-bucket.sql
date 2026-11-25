-- A, B, C의 주문 DB에 적용합니다.
CREATE INDEX IF NOT EXISTS orders_bucket_order_id_idx ON public.orders (bucket_id, order_id);
