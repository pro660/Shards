export type Shard = {
  shardId: number;
  shardName: string;
  connectionKey: string;
  status: 'ACTIVE' | 'INACTIVE';
};

export type Bucket = {
  bucketId: number;
  shardId: number;
  state: 'NORMAL' | 'MIGRATING';
  version: number;
};

export type Topology = {
  shards: Shard[];
  buckets: Bucket[];
};

// 버킷 번호는 메타 DB와 같은 0~15 범위를 사용해.
export const BUCKET_COUNT = 16;

export class TopologyError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isShard(value: unknown): value is Shard {
  return isRecord(value)
    && typeof value.shardId === 'number' && Number.isInteger(value.shardId)
    && typeof value.shardName === 'string' && typeof value.connectionKey === 'string'
    && (value.status === 'ACTIVE' || value.status === 'INACTIVE');
}

function isBucket(value: unknown): value is Bucket {
  return isRecord(value)
    && typeof value.bucketId === 'number' && Number.isInteger(value.bucketId)
    && value.bucketId >= 0 && value.bucketId < BUCKET_COUNT
    && typeof value.shardId === 'number' && Number.isInteger(value.shardId)
    && (value.state === 'NORMAL' || value.state === 'MIGRATING')
    && typeof value.version === 'number' && Number.isSafeInteger(value.version);
}

export async function fetchTopology(token: string, signal: AbortSignal): Promise<Topology> {
  // 토큰은 이번 요청의 헤더에만 넣고, 앱 설정이나 브라우저 저장소에 기록하지 않아.
  const response = await fetch('/api/topology', {
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
    signal,
    cache: 'no-store',
  });

  if (response.status === 401 || response.status === 403) {
    throw new TopologyError(response.status, 'Access denied. Check your read token and try again.');
  }
  if (!response.ok) {
    throw new TopologyError(response.status, `The topology API is unavailable (HTTP ${response.status}).`);
  }

  // 실제 JSON 형식을 확인한 뒤에만 소유자와 상태를 화면에 표시해.
  const data: unknown = await response.json();
  if (!isRecord(data)
      || !Array.isArray(data.shards) || !data.shards.every(isShard)
      || !Array.isArray(data.buckets) || !data.buckets.every(isBucket)
      || new Set(data.shards.map(shard => shard.shardId)).size !== data.shards.length
      || new Set(data.buckets.map(bucket => bucket.bucketId)).size !== data.buckets.length) {
    throw new TopologyError(200, 'The API returned an invalid topology response.');
  }

  return { shards: data.shards, buckets: data.buckets };
}
