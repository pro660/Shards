import { BUCKET_COUNT, isRecord } from './topology';

// 취소는 복사 시작 전의 별도 종료 상태라서 기본 진행 순서에서 분리합니다.
export const migrationSteps = ['PENDING', 'COPYING', 'VERIFYING', 'SWITCHING', 'CLEANING', 'COMPLETED'] as const;
type Phase = typeof migrationSteps[number] | 'CANCELLED';

export type MigrationJob = {
  jobId: number;
  bucketId: number;
  sourceShardId: number;
  targetShardId: number;
  phase: Phase;
  copiedOrderCount: number;
};

type Registration = Pick<MigrationJob, 'bucketId' | 'targetShardId'>;
export type MigrationAction = 'register' | 'advance' | 'cancel';

export class MigrationError extends Error {
  constructor(public readonly status: number) {
    super(`Migration API returned HTTP ${status}.`);
  }
}

export function isTerminal(job: MigrationJob) {
  return job.phase === 'COMPLETED' || job.phase === 'CANCELLED';
}

function isInteger(value: unknown, min: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min;
}

function isJob(value: unknown): value is MigrationJob {
  return isRecord(value)
    && isInteger(value.jobId, 1)
    && isInteger(value.bucketId, 0) && value.bucketId < BUCKET_COUNT
    && isInteger(value.sourceShardId, 1) && isInteger(value.targetShardId, 1)
    && value.sourceShardId !== value.targetShardId
    && isInteger(value.copiedOrderCount, 0)
    && (value.phase === 'CANCELLED' || migrationSteps.some(step => step === value.phase));
}

async function requestJob(path: string, token: string, signal: AbortSignal, method: 'GET' | 'POST', body?: Registration) {
  const response = await fetch(`/api/migrations${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
    signal,
  });
  if (!response.ok) throw new MigrationError(response.status);
  return response;
}

async function readJob(response: Response, expectedJobId?: number): Promise<MigrationJob> {
  const data: unknown = await response.json();
  if (!isJob(data) || (expectedJobId !== undefined && data.jobId !== expectedJobId)) {
    throw new Error('The API returned an invalid migration job.');
  }
  return data;
}

export async function fetchUnfinishedJob(token: string, signal: AbortSignal) {
  const response = await requestJob('/unfinished', token, signal, 'GET');
  // 204는 미완료 작업이 없다는 정상 응답이며 읽을 JSON이 없습니다.
  return response.status === 204 ? null : readJob(response);
}

export async function fetchJob(jobId: number, token: string, signal: AbortSignal) {
  return readJob(await requestJob(`/${jobId}`, token, signal, 'GET'), jobId);
}

export async function registerJob(selection: Registration, token: string, signal: AbortSignal) {
  const job = await readJob(await requestJob('', token, signal, 'POST', selection));
  if (job.phase !== 'PENDING' || job.bucketId !== selection.bucketId || job.targetShardId !== selection.targetShardId) {
    throw new Error('The API returned an unexpected registration.');
  }
  return job;
}

export async function changeJob(jobId: number, action: 'advance' | 'cancel', token: string, signal: AbortSignal) {
  return readJob(await requestJob(`/${jobId}/${action}`, token, signal, 'POST'), jobId);
}
