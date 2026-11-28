import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { readResponse } from './OrderWorkbench';
import { isRecord } from './topology';

const databases = {
  metadata: 'Metadata',
  shardA: 'Shard A',
  shardB: 'Shard B',
  shardC: 'Shard C',
  lab: 'SQL Lab',
} as const;

type Database = keyof typeof databases;
type Output = { columns: string[]; rows: (string | null)[][]; affectedRows: number | null; truncated: boolean };
type Result = { outputs: Output[]; truncated: boolean };
type Command = { id: number; database: Database; sql: string; result?: Result; error?: string; milliseconds?: number };

const maxHistory = 20;
const examples = {
  metadata: 'SELECT bucket_id, shard_id, state, version FROM bucket_mapping ORDER BY bucket_id;',
  shard: 'SELECT order_id, user_id, bucket_id, amount FROM orders ORDER BY created_at DESC LIMIT 20;',
  lab: 'CREATE TABLE IF NOT EXISTS demo_orders (id integer PRIMARY KEY, amount numeric);\n'
    + 'INSERT INTO demo_orders VALUES (1, 15000) ON CONFLICT (id) DO NOTHING;\n'
    + 'SELECT count(*) AS orders, sum(amount) AS total FROM demo_orders;',
};

export function SqlTerminal({ readToken }: { readToken?: string }) {
  const [database, setDatabase] = useState<Database>('metadata');
  const [writeToken, setWriteToken] = useState('');
  const [sql, setSql] = useState('');
  const [commands, setCommands] = useState<Command[]>([]);
  const [historyIndex, setHistoryIndex] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const draft = useRef('');
  const nextId = useRef(1);
  const requestRef = useRef<AbortController | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);
  const viewport = useRef<HTMLDivElement>(null);

  useEffect(() => () => requestRef.current?.abort(), []);
  useEffect(() => {
    const screen = viewport.current;
    if (screen) screen.scrollTop = screen.scrollHeight;
  }, [commands]);

  const lab = database === 'lab';

  async function run() {
    if (!readToken || !sql.trim() || requestRef.current) return;
    const command = { id: nextId.current++, database, sql: sql.trim() };
    const request = new AbortController();
    const start = performance.now();
    requestRef.current = request;
    setCommands(previous => [...previous.slice(1 - maxHistory), command]);
    setSql('');
    setHistoryIndex(null);
    setPending(true);

    function finish(result: { result: Result } | { error: string }) {
      if (request.signal.aborted) return;
      setCommands(previous => previous.map(item => item.id === command.id
        ? { ...item, ...result, milliseconds: Math.round(performance.now() - start) } : item));
    }

    try {
      const response = await fetch('/api/sql-console', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + (lab ? writeToken.trim() || readToken : readToken),
        },
        body: JSON.stringify({ database, sql: command.sql }),
        signal: AbortSignal.any([request.signal, AbortSignal.timeout(12_000)]),
      });
      const responseBody = await readResponse(response);
      if (!isRecord(responseBody) || !Array.isArray(responseBody.outputs)) {
        throw new Error('Unexpected response from the SQL API.');
      }
      finish({ result: { outputs: responseBody.outputs, truncated: responseBody.truncated === true } });
    } catch (failure) {
      const error = failure instanceof Error && failure.name === 'Error'
        ? failure.message : 'The SQL API did not respond. Check the database before retrying writes.';
      finish({ error: lab && error.startsWith('HTTP 403') ? error + ' Enter the write token for SQL Lab.' : error });
    } finally {
      requestRef.current = null;
      if (!request.signal.aborted) {
        setPending(false);
      }
    }
  }

  function edit(value: string) {
    setSql(value);
    setHistoryIndex(null);
  }

  function keyboard(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing || pending) return;
    if (event.key === 'Enter' && (!event.shiftKey || event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      void run();
      return;
    }
    if ((event.key !== 'ArrowUp' && event.key !== 'ArrowDown') || event.altKey || event.shiftKey) return;
    const input = event.currentTarget;
    const singleLine = !sql.includes('\n');
    const up = event.key === 'ArrowUp';
    const atHistoryBoundary = up ? input.selectionStart === 0 : input.selectionEnd === sql.length;
    if ((!singleLine && !atHistoryBoundary) || input.selectionStart !== input.selectionEnd) return;

    const history = commands.filter(command => command.database === database);
    const index = historyIndex ?? history.length;
    const next = index + (up ? -1 : 1);
    if (next < 0 || next > history.length) return;
    event.preventDefault();
    if (historyIndex === null) draft.current = sql;
    setHistoryIndex(next < history.length ? next : null);
    setSql(next < history.length ? history[next].sql : draft.current);
  }

  return (
    <section className="panel workbench-panel" aria-labelledby="sql-heading">
      <div className="panel-heading">
        <h2 id="sql-heading">SQL terminal</h2>
        <span className="panel-meta">{lab ? 'Read / write' : 'Read only'}</span>
      </div>
      {readToken ? (
        <form className="sql-terminal" onSubmit={event => { event.preventDefault(); void run(); }}>
          <div className="sql-toolbar">
            <label className="migration-field">Database
              <select value={database} disabled={pending} onChange={event => {
                setDatabase(event.target.value as Database);
                setHistoryIndex(null);
              }}>
                {Object.entries(databases).map(([key, label]) => <option value={key} key={key}>{label}</option>)}
              </select>
            </label>
            <button type="button" className="secondary-button" disabled={pending} onClick={() => {
              edit(lab ? examples.lab : database === 'metadata' ? examples.metadata : examples.shard);
              editor.current?.focus();
            }}>Load example</button>
          </div>
          {lab && (
            <div className="sql-access">
              <label className="migration-field">Write token
                <input type="password" value={writeToken} autoComplete="off" readOnly={pending}
                       onChange={event => setWriteToken(event.target.value)} />
              </label>
              <p className="migration-note">Use the write token here or sign in with it.</p>
            </div>
          )}
          <label className="sql-editor" htmlFor="terminal-sql">SQL
            <textarea id="terminal-sql" aria-label="SQL command" aria-describedby="terminal-help"
                      ref={editor} value={sql} onChange={event => edit(event.target.value)}
                      readOnly={pending} onKeyDown={keyboard} spellCheck={false} autoCapitalize="off"
                      rows={5} maxLength={lab ? 16000 : 2000} placeholder="SELECT ..." />
          </label>
          <p id="terminal-help" className="migration-note">
            {lab ? 'PostgreSQL scripts · temporary database'
              : database === 'metadata' ? 'SELECT · bucket_mapping, shards, migration_jobs' : 'SELECT · orders'}
            {' '}· Enter run · Shift+Enter new line · ↑↓ history
          </p>
          <div className="sql-toolbar">
            <button type="submit" className="primary-button" disabled={pending || !sql.trim()}>
              {pending ? 'Running…' : 'Run query'}
            </button>
            {commands.length > 0 && <button type="button" className="secondary-button" disabled={pending} onClick={() => {
              setCommands([]);
              setHistoryIndex(null);
              editor.current?.focus();
            }}>Clear history</button>}
          </div>
          {commands.length > 0 && (
            <div className="sql-history" ref={viewport} role="log" aria-label="Executed SQL" tabIndex={0}>
              {commands.map(command => (
                <div className="sql-history-entry" key={command.id}>
                  <p className="migration-note">{databases[command.database]}</p>
                  <pre className="sql-history-command">{command.sql}</pre>
                  {command.error && <p className="migration-error" role="alert">{command.error}</p>}
                  {command.result?.outputs.map((output, index) => <QueryOutput output={output} key={index} />)}
                  {command.result?.truncated && <p className="migration-note">Additional output hidden.</p>}
                  <p className="migration-note">
                    {command.milliseconds === undefined ? 'Running…'
                      : (command.error ? 'Failed' : 'Completed') + ' · ' + command.milliseconds + ' ms'}
                  </p>
                </div>
              ))}
            </div>
          )}
        </form>
      ) : <p className="migration-note">Load topology with a read token to open the terminal.</p>}
    </section>
  );
}

function QueryOutput({ output }: { output: Output }) {
  if (output.affectedRows !== null) {
    return <p className="migration-note">{output.affectedRows} affected rows</p>;
  }
  return (
    <div>
      <div className="sql-output" tabIndex={0} role="region" aria-label="Query result">
        <table>
          <thead><tr>{output.columns.map((column, index) => <th key={index} scope="col">{column}</th>)}</tr></thead>
          <tbody>{output.rows.map((row, index) => (
            <tr key={index}>{row.map((value, position) => <td key={position}>{value ?? <i>NULL</i>}</td>)}</tr>
          ))}</tbody>
        </table>
      </div>
      <p className="migration-note">({output.rows.length} rows{output.truncated ? ', output capped' : ''})</p>
    </div>
  );
}
