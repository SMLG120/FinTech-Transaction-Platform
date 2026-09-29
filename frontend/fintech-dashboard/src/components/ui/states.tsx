import { AlertCircle, Inbox, SearchX } from 'lucide-react';
import { userMessage } from '../../utils/format';
import { Button } from './Button';

export function Skeleton({
  label = 'Loading',
  rows = 3,
}: {
  label?: string;
  rows?: number;
}) {
  return (
    <div role="status" aria-label={label} className="skeleton-row">
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          className="skeleton"
          style={{
            height: 18,
            width: i === 0 ? '100%' : i === 1 ? '72%' : '48%',
          }}
        />
      ))}
      <span
        style={{
          position: 'absolute',
          width: 1,
          height: 1,
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
        }}
      >
        {label}…
      </span>
    </div>
  );
}

export function TableSkeleton({ label = 'Loading rows', rows = 5 }: { label?: string; rows?: number }) {
  return (
    <div className="table-wrap" role="status" aria-label={label} style={{ padding: 16 }}>
      <div className="skeleton-row">
        {Array.from({ length: rows }).map((_, i) => (
          <div key={i} className="skeleton" style={{ height: 44 }} />
        ))}
      </div>
      <span
        style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}
      >
        {label}…
      </span>
    </div>
  );
}

export function EmptyState({
  title,
  body,
  action,
  icon = 'inbox',
}: {
  title: string;
  body?: string;
  action?: React.ReactNode;
  icon?: 'inbox' | 'search';
}) {
  return (
    <div className="state-block" role="status">
      <div className="state-icon" aria-hidden="true">
        {icon === 'search' ? <SearchX size={20} /> : <Inbox size={20} />}
      </div>
      <h2>{title}</h2>
      {body ? <p>{body}</p> : null}
      {action}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="state-block" role="alert">
      <div className="state-icon" aria-hidden="true">
        <AlertCircle size={20} />
      </div>
      <h2>Something went wrong</h2>
      <p>{userMessage(error)}</p>
      {onRetry ? <Button onClick={onRetry}>Retry</Button> : null}
    </div>
  );
}
