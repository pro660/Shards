import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { MigrationPanel } from './MigrationPanel';
import { BUCKET_COUNT, fetchTopology, TopologyError } from './topology';
import type { Topology } from './topology';

const bucketIds = Array.from({ length: BUCKET_COUNT }, (_, index) => index);
const shardNames = ['A', 'B', 'C'];
const clock = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit', minute: '2-digit', second: '2-digit',
});

type Snapshot = { topology: Topology; receivedAt: Date; readToken: string };

function BucketOwners({ topology }: { topology: Topology | undefined }) {
  // 버킷 번호로 매핑을 찾고, 매핑의 shardId로 표시할 샤드 이름을 찾아.
  const byBucketId = new Map(topology?.buckets.map(bucket => [bucket.bucketId, bucket]));
  const byShardId = new Map(topology?.shards.map(shard => [shard.shardId, shard]));

  return (
    <section className="panel matrix-panel" aria-labelledby="bucket-heading">
      <div className="panel-heading">
        <h2 id="bucket-heading">Bucket owners</h2>
        <span className="panel-meta">{topology ? `${topology.buckets.length} / ${BUCKET_COUNT} mapped` : 'Buckets 00 to 15'}</span>
      </div>
      <ol className="bucket-grid">
        {bucketIds.map(bucketId => {
          const bucket = byBucketId.get(bucketId);
          const shard = bucket ? byShardId.get(bucket.shardId) : undefined;
          const label = String(bucketId).padStart(2, '0');

          return (
            <li key={bucketId} className="bucket" data-owner={shard?.shardName}
                data-migrating={bucket?.state === 'MIGRATING' || undefined}>
              <span className="bucket-id"><span className="visually-hidden">Bucket </span>{label}</span>
              <div className="bucket-body">
                <span className="bucket-owner">
                  <span className="visually-hidden">Owner </span>{shard?.shardName ?? '—'}
                </span>
                {bucket && <span className="bucket-version">v{bucket.version}</span>}
              </div>
              {topology && (
                <span className="bucket-state">{bucket?.state.toLowerCase() ?? 'unmapped'}</span>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function ShardStatus({ topology }: { topology: Topology | undefined }) {
  return (
    <section className="panel shard-panel" aria-labelledby="shard-heading">
      <div className="panel-heading">
        <h2 id="shard-heading">Shard status</h2>
      </div>
      <table className="shard-register" aria-describedby="shard-status-note">
        <thead>
          <tr>
            <th scope="col">Shard</th>
            <th scope="col">State</th>
            <th scope="col">Buckets</th>
          </tr>
        </thead>
        <tbody>
          {shardNames.map(name => {
            const shard = topology?.shards.find(item => item.shardName === name);
            // 소유 버킷 수는 API의 매핑에서 계산해. 등록 전인 샤드는 수치를 비워 둬.
            const count = topology && shard
              ? topology.buckets.filter(bucket => bucket.shardId === shard.shardId).length
              : undefined;

            return (
              <tr key={name} className="shard" data-owner={name}>
                <th scope="row">
                  <span className="shard-name">{name}</span>
                  {shard && <span className="shard-key">{shard.connectionKey}</span>}
                </th>
                <td>
                  <span className="shard-state" data-active={shard?.status === 'ACTIVE' || undefined}>
                    {shard?.status ?? (topology ? 'Missing' : '—')}
                  </span>
                </td>
                <td className="shard-count">{count ?? '—'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p id="shard-status-note" className="shard-note">State is recorded in the metadata database.</p>
    </section>
  );
}

export function App() {
  const [input, setInput] = useState('');
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [tokenRejected, setTokenRejected] = useState(false);
  const requestRef = useRef<AbortController | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => () => requestRef.current?.abort(), []);
  useEffect(() => {
    // 잘못된 토큰으로 실패하면 키보드로 바로 재입력할 수 있게 해.
    if (tokenRejected) inputRef.current?.focus();
  }, [tokenRejected]);

  async function load(token: string) {
    requestRef.current?.abort();
    const request = new AbortController();
    requestRef.current = request;
    setError('');
    setTokenRejected(false);
    setPending(true);

    try {
      const signal = AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]);
      const topology = await fetchTopology(token, signal);
      if (request.signal.aborted) return;
      setInput('');
      // 조회에 성공한 토큰은 다음 조회를 위해 이 화면의 메모리에만 둬.
      setSnapshot({ topology, receivedAt: new Date(), readToken: token });
    } catch (failure) {
      if (request.signal.aborted) return;
      if (failure instanceof TopologyError) {
        if (failure.status === 401 || failure.status === 403) {
          setInput('');
          setSnapshot(undefined);
          setTokenRejected(true);
        }
        setError(failure.message);
      } else {
        setError('Could not read topology. Check that Spring Boot is running and try again.');
      }
    } finally {
      // 연결을 해제했거나 새 요청이 시작됐으면 이전 응답이 현재 화면을 바꾸지 않게 해.
      if (requestRef.current === request) {
        requestRef.current = null;
        setPending(false);
      }
    }
  }

  function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const token = input.trim();
    if (!token) {
      setTokenRejected(true);
      setError('Enter your read token to load the topology.');
      return;
    }
    void load(token);
  }

  function disconnect() {
    requestRef.current?.abort();
    requestRef.current = null;
    setInput('');
    setSnapshot(undefined);
    setPending(false);
    setError('');
    setTokenRejected(false);
  }

  const status = pending ? 'Loading' : error ? (snapshot ? 'Last snapshot' : 'Request failed')
    : snapshot ? 'Snapshot loaded' : 'Awaiting token';

  return (
    <>
      <header className="site-header">
        <div className="shell header-inner">
          <div className="wordmark">shards-pro</div>
          <span className="connection-state" data-ready={!!snapshot && !error || undefined} role="status">
            {status}
          </span>
        </div>
      </header>

      <main className="shell dashboard">
        <div className="page-heading">
          <div>
            <h1>Shard topology</h1>
            <p>PostgreSQL shard status and bucket ownership.</p>
          </div>
          {snapshot && (
            <p className="snapshot-time">
              <span>{error ? 'Last successful read' : 'Last read'}</span>
              <time dateTime={snapshot.receivedAt.toISOString()}>{clock.format(snapshot.receivedAt)}</time>
            </p>
          )}
        </div>

        <div className="access">
          {snapshot ? (
            <div className="session">
              <p>Read access</p>
              <div className="session-actions">
                <button className="secondary-button" type="button" onClick={disconnect}>Forget token</button>
                <button className="primary-button" type="button" disabled={pending}
                        onClick={() => void load(snapshot.readToken)}>
                  {pending ? 'Refreshing…' : 'Refresh topology'}
                </button>
              </div>
            </div>
          ) : (
            <form className="token-form" onSubmit={connect} autoComplete="off">
              <div className="token-field">
                <label htmlFor="read-token">Read token <span>(required)</span></label>
                <input id="read-token" name="readToken" type="password" value={input}
                       ref={inputRef}
                       onChange={event => {
                         setInput(event.target.value);
                         if (tokenRejected) {
                           setTokenRejected(false);
                           setError('');
                         }
                       }}
                       aria-invalid={tokenRejected || undefined}
                       aria-describedby={tokenRejected ? 'topology-error' : undefined}
                       spellCheck={false} autoComplete="off" required />
              </div>
              <button className="primary-button" type="submit" disabled={pending}>
                {pending ? 'Loading…' : 'Load topology'}
              </button>
            </form>
          )}
          {error && <p id="topology-error" className="error" role="alert">{error}</p>}
        </div>

        <div className="topology-layout" aria-busy={pending} data-loaded={snapshot ? 'true' : undefined}>
          <BucketOwners topology={snapshot?.topology} />
          <ShardStatus topology={snapshot?.topology} />
        </div>

        {snapshot && <MigrationPanel topology={snapshot.topology} readToken={snapshot.readToken}
                                    topologyFresh={!pending && !error}
                                    refreshTopology={() => void load(snapshot.readToken)} />}

        <footer className="source"><span>GET</span> /admin/migrations/topology</footer>
      </main>
    </>
  );
}
