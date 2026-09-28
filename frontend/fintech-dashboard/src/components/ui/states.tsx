import { userMessage } from '../../utils/format';
import { Button } from './Button';

export function Skeleton({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" aria-label={label}>
      <div className="skeleton" style={{ height: 18, marginBottom: 8 }} />
      <div className="skeleton" style={{ height: 18, width: '72%', marginBottom: 8 }} />
      <div className="skeleton" style={{ height: 18, width: '48%' }} />
      <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
        {label}…
      </span>
    </div>
  );
}

export function EmptyState({ title, body }: { title: string; body?: string }) {
  return (
    <div className="state-block" role="status">
      <h2>{title}</h2>
      {body ? <p>{body}</p> : null}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="state-block" role="alert">
      <h2>Something went wrong</h2>
      <p>{userMessage(error)}</p>
      {onRetry ? <Button onClick={onRetry}>Retry</Button> : null}
    </div>
  );
}
