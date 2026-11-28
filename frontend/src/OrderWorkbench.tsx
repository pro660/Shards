import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { isRecord } from './topology';

type CreatedOrder = { orderId: string; userId: string; amount: string; bucketId: number };

export async function readResponse(response: Response): Promise<unknown> {
  const body: unknown = await response.json().catch(() => undefined);
  if (!response.ok) {
    const detail = isRecord(body) && typeof body.detail === 'string' ? body.detail : 'Request failed.';
    throw new Error('HTTP ' + response.status + ' · ' + detail);
  }
  return body;
}

function requestSignal(request: AbortController) {
  return AbortSignal.any([request.signal, AbortSignal.timeout(12_000)]);
}

export function CreateOrderPanel() {
  const [userId, setUserId] = useState('3');
  const [amount, setAmount] = useState('15000');
  const [created, setCreated] = useState<CreatedOrder>();
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const requestRef = useRef<AbortController | null>(null);
  useEffect(() => () => requestRef.current?.abort(), []);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (requestRef.current) return;
    setError('');
    setCreated(undefined);
    if (!/^[1-9][0-9]{0,18}$/.test(userId) || BigInt(userId) > 9223372036854775807n
        || !/^(?:0|[1-9][0-9]{0,9})(?:\.[0-9]{1,2})?$/.test(amount)) {
      setError('Enter a positive user ID and an amount from 0 to 9999999999.99.');
      return;
    }
    const request = new AbortController();
    requestRef.current = request;
    setPending(true);
    try {
      // Keep the user ID as text until serialization to preserve Java long values exactly.
      const body = '{"userId":' + userId + ',"amount":' + amount + '}';
      const response = await fetch('/api/orders', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body, signal: requestSignal(request),
      });
      const data = await readResponse(response);
      if (!isRecord(data) || typeof data.orderId !== 'string' || typeof data.bucketId !== 'number') {
        throw new Error('Unexpected response. Check the SQL terminal for the order before submitting again.');
      }
      if (!request.signal.aborted) setCreated({ orderId: data.orderId, bucketId: data.bucketId, userId, amount });
    } catch (failure) {
      if (!request.signal.aborted) {
        const detail = failure instanceof Error && failure.name === 'Error' ? failure.message : 'No response from the order API.';
        setError(detail.startsWith('HTTP 4') ? detail : detail + ' Check the SQL terminal before submitting again.');
      }
    } finally {
      if (requestRef.current === request) requestRef.current = null;
      if (!request.signal.aborted) setPending(false);
    }
  }

  return (
    <section className="panel workbench-panel" aria-labelledby="order-entry-heading">
      <div className="panel-heading"><h2 id="order-entry-heading">Create order</h2></div>
      <form className="order-entry" onSubmit={create}>
        <label className="migration-field">User ID
          <input value={userId} onChange={event => setUserId(event.target.value)}
                 inputMode="numeric" maxLength={19} disabled={pending} required />
        </label>
        <label className="migration-field">Amount
          <input value={amount} onChange={event => setAmount(event.target.value)}
                 inputMode="decimal" maxLength={13} disabled={pending} required />
        </label>
        <button type="submit" className="primary-button" disabled={pending}>
          {pending ? 'Creating…' : 'Create order'}
        </button>
      </form>
      {error && <p className="migration-error" role="alert">{error}</p>}
      {created && (
        <dl className="order-receipt" aria-label="Created order" aria-live="polite">
          <div><dt>Order ID</dt><dd>{created.orderId}</dd></div>
          <div><dt>User ID</dt><dd>{created.userId}</dd></div>
          <div><dt>Bucket</dt><dd>{String(created.bucketId).padStart(2, '0')}</dd></div>
          <div><dt>Amount</dt><dd>{created.amount}</dd></div>
        </dl>
      )}
    </section>
  );
}
