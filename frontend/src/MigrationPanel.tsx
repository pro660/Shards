import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  changeJob, fetchJob, fetchUnfinishedJob, isTerminal, MigrationError, migrationSteps, registerJob,
} from './migrations';
import type { MigrationAction, MigrationJob } from './migrations';
import type { Topology } from './topology';

type Props = {
  topology: Topology;
  topologyFresh: boolean;
  readToken: string;
  refreshTopology: () => void;
};

const phaseNotes = {
  PENDING: 'Registered. Copying has yet to start.',
  COPYING: 'Each advance copies one batch or closes the copy phase.',
  VERIFYING: 'The next advance compares the source and destination orders.',
  SWITCHING: 'The next advance checks the copies again and changes the bucket owner.',
  CLEANING: 'The new owner serves the bucket. Each advance clears a source batch or finishes the job.',
  COMPLETED: 'The new owner serves the bucket and source cleanup is complete.',
  CANCELLED: 'The job ended before copying. The bucket owner stays the same.',
};

function readError(error: unknown) {
  if (error instanceof MigrationError && (error.status === 401 || error.status === 403)) {
    return 'Job read denied. Forget the current token and reconnect with a valid read token.';
  }
  return 'Could not check the current job. Verify the backend connection, then refresh the job.';
}

function writeError(error: unknown) {
  if (error instanceof MigrationError) {
    switch (error.status) {
      case 400: return 'The backend rejected these values (HTTP 400). Check the bucket and destination.';
      case 401: return 'The write token was rejected (HTTP 401). Enter a valid write token.';
      case 403: return 'Write permission is required (HTTP 403). Enter the admin write token.';
      case 404: return 'This job is missing (HTTP 404). Refresh the job list.';
      case 409: return 'The operation conflicts with current data (HTTP 409). Refresh the job and topology.';
      case 503: return 'A required database or lock is unavailable (HTTP 503). Check the backend and refresh the job.';
    }
  }
  return 'The write result is unknown. Refresh the job to check what was saved.';
}

function JobProgress({ job, topology }: { job: MigrationJob; topology: Topology }) {
  const phaseIndex = migrationSteps.findIndex(step => step === job.phase);
  const name = (id: number) => topology.shards.find(shard => shard.shardId === id)?.shardName ?? `#${id}`;

  return (
    <div className="migration-progress" aria-live="polite">
      <dl className="migration-summary">
        <div><dt>Job</dt><dd>#{job.jobId}</dd></div>
        <div><dt>Bucket</dt><dd>{String(job.bucketId).padStart(2, '0')}</dd></div>
        <div><dt>Route</dt><dd>{name(job.sourceShardId)} → {name(job.targetShardId)}</dd></div>
        <div><dt>Copied orders</dt><dd>{job.copiedOrderCount}</dd></div>
        <div><dt>Phase</dt><dd>{job.phase}</dd></div>
      </dl>
      {phaseIndex >= 0 && (
        <ol className="migration-steps" aria-label="Migration phases">
          {migrationSteps.map((step, index) => (
            <li key={step} aria-current={index === phaseIndex ? 'step' : undefined}
                data-done={index < phaseIndex || undefined}>{step}</li>
          ))}
        </ol>
      )}
      <p className="migration-note">{phaseNotes[job.phase]}</p>
    </div>
  );
}

export function MigrationPanel({ topology, topologyFresh, readToken, refreshTopology }: Props) {
  const [job, setJob] = useState<MigrationJob | null>(null);
  const [bucket, setBucket] = useState('');
  const [target, setTarget] = useState('');
  const [writeToken, setWriteToken] = useState('');
  const [pending, setPending] = useState<MigrationAction | 'read' | null>('read');
  const [needsCheck, setNeedsCheck] = useState(true);
  const [error, setError] = useState('');
  const requestRef = useRef<AbortController | null>(null);

  useEffect(() => {
    // 새로 열어도 Spring이 기억한 미완료 작업부터 확인합니다.
    const request = new AbortController();
    const signal = AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]);
    void fetchUnfinishedJob(readToken, signal)
      .then(current => {
        if (request.signal.aborted) return;
        setJob(current);
        setNeedsCheck(false);
      })
      .catch(failure => {
        if (!request.signal.aborted) setError(readError(failure));
      })
      .finally(() => {
        if (!request.signal.aborted) setPending(null);
      });
    return () => {
      request.abort();
      requestRef.current?.abort();
    };
  }, [readToken]);

  const availableBuckets = topology.buckets.filter(item => item.state === 'NORMAL'
    && topology.shards.some(shard => shard.shardId === item.shardId && shard.status === 'ACTIVE'));
  const selectedBucket = availableBuckets.find(item => String(item.bucketId) === bucket);
  const destinations = selectedBucket ? topology.shards.filter(shard =>
    shard.status === 'ACTIVE' && shard.shardId !== selectedBucket.shardId) : [];
  const selectedTarget = destinations.find(shard => String(shard.shardId) === target);
  const activeJob = job !== null && !isTerminal(job);
  const token = writeToken.trim();
  const canWrite = !!token && !pending && !needsCheck;
  const canRegister = canWrite && topologyFresh && !!selectedBucket && !!selectedTarget;

  async function reloadJob() {
    if (pending || requestRef.current) return;
    const request = new AbortController();
    requestRef.current = request;
    setPending('read');
    setError('');
    try {
      const signal = AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]);
      // 다른 관리자가 이전 작업을 끝내고 새 작업을 등록했을 수도 있습니다.
      let current = await fetchUnfinishedJob(readToken, signal);
      if (!current && job && !isTerminal(job)) {
        try {
          current = await fetchJob(job.jobId, readToken, signal);
        } catch (failure) {
          if (!(failure instanceof MigrationError && failure.status === 404)) throw failure;
        }
      } else if (!current) {
        current = job;
      }
      if (request.signal.aborted) return;
      setJob(current);
      setNeedsCheck(false);
      refreshTopology();
    } catch (failure) {
      if (request.signal.aborted) return;
      setNeedsCheck(true);
      setError(readError(failure));
    } finally {
      if (!request.signal.aborted) {
        requestRef.current = null;
        setPending(null);
      }
    }
  }

  async function write(action: MigrationAction, run: (signal: AbortSignal) => Promise<MigrationJob>) {
    // 상태 갱신 전 연속 클릭이 들어와도 변경 요청을 한 번만 보냅니다.
    if (requestRef.current || !canWrite) return;
    const request = new AbortController();
    requestRef.current = request;
    setPending(action);
    setError('');
    try {
      const signal = AbortSignal.any([request.signal, AbortSignal.timeout(30_000)]);
      const updated = await run(signal);
      if (request.signal.aborted) return;
      setJob(updated);
      if (action === 'register') {
        setBucket('');
        setTarget('');
      } else {
        refreshTopology();
      }
    } catch (failure) {
      if (request.signal.aborted) return;
      const status = failure instanceof MigrationError ? failure.status : undefined;
      if (status === 401 || status === 403) setWriteToken('');
      // 서버에서 변경했을 수 있으므로 오류 직후 같은 POST를 다시 보내지 않습니다.
      if (status !== 400 && status !== 401 && status !== 403) setNeedsCheck(true);
      setError(writeError(failure));
    } finally {
      if (!request.signal.aborted) {
        requestRef.current = null;
        setPending(null);
      }
    }
  }

  function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canRegister || !selectedBucket || !selectedTarget) return;
    const selection = { bucketId: selectedBucket.bucketId, targetShardId: selectedTarget.shardId };
    void write('register', signal => registerJob(selection, token, signal));
  }

  function run(action: 'advance' | 'cancel') {
    if (!job || !activeJob || (action === 'cancel' && job.phase !== 'PENDING')) return;
    void write(action, signal => changeJob(job.jobId, action, token, signal));
  }

  return (
    <section className="panel migration-panel" aria-labelledby="migration-heading">
      <div className="panel-heading">
        <h2 id="migration-heading">Bucket migration</h2>
        <button type="button" className="secondary-button" disabled={!!pending} onClick={() => void reloadJob()}>
          Refresh job
        </button>
      </div>
      {job && <JobProgress job={job} topology={topology} />}
      {pending && <p className="migration-note" role="status">
        {pending === 'read' ? 'Checking job…' : 'Waiting for the backend…'}
      </p>}
      {!job && !pending && !needsCheck && <p className="migration-note">No unfinished migration.</p>}
      {error && <p className="migration-error" role="alert">{error}</p>}

      <div className="migration-controls">
        <div className="migration-field">
          <label htmlFor="migration-token">Write token</label>
          <input id="migration-token" name="migrationToken" type="password" value={writeToken}
                 onChange={event => setWriteToken(event.target.value)} autoComplete="off"
                 spellCheck={false} disabled={!!pending} aria-describedby="write-token-note" />
          <p id="write-token-note" className="migration-note">Required for changes. Kept in this page session.</p>
        </div>

        {activeJob ? (
          <div className="migration-actions">
            {job.phase === 'PENDING' && (
              <button type="button" className="secondary-button" disabled={!canWrite} onClick={() => run('cancel')}>
                Cancel pending job
              </button>
            )}
            <button type="button" className="primary-button" disabled={!canWrite} onClick={() => run('advance')}>
              Advance one step
            </button>
          </div>
        ) : (
          <form className="migration-form" onSubmit={register}>
            <div className="migration-field">
              <label htmlFor="migration-bucket">Bucket</label>
              <select id="migration-bucket" value={selectedBucket ? bucket : ''}
                      disabled={!!pending || needsCheck || !topologyFresh} required
                      onChange={event => { setBucket(event.target.value); setTarget(''); }}>
                <option value="">Select bucket</option>
                {availableBuckets.map(item => (
                  <option value={item.bucketId} key={item.bucketId}>
                    {String(item.bucketId).padStart(2, '0')} · {topology.shards.find(shard => shard.shardId === item.shardId)?.shardName}
                  </option>
                ))}
              </select>
            </div>
            <div className="migration-field">
              <label htmlFor="migration-target">Target shard</label>
              <select id="migration-target" value={selectedTarget ? target : ''} required
                      disabled={!selectedBucket || !!pending || needsCheck || !topologyFresh}
                      onChange={event => setTarget(event.target.value)}>
                <option value="">Select target</option>
                {destinations.map(shard => <option key={shard.shardId} value={shard.shardId}>{shard.shardName}</option>)}
              </select>
            </div>
            <button type="submit" className="primary-button" disabled={!canRegister}>Register migration</button>
            {selectedBucket && destinations.length === 0 && (
              <p className="migration-note">No active destination for this bucket.</p>
            )}
          </form>
        )}
      </div>
    </section>
  );
}
