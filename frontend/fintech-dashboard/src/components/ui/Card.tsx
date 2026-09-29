import type { ReactNode } from 'react';

export function Card({
  children,
  labelledBy,
  flat,
}: {
  children: ReactNode;
  labelledBy?: string;
  flat?: boolean;
}) {
  return (
    <section className={flat ? 'card card-flat' : 'card'} aria-labelledby={labelledBy}>
      {children}
    </section>
  );
}

export function CardHeader({
  title,
  sub,
  actions,
  titleId,
}: {
  title: string;
  sub?: string;
  actions?: ReactNode;
  titleId: string;
}) {
  return (
    <div className="card-head">
      <div>
        <h2 id={titleId}>{title}</h2>
        {sub ? <p className="card-sub">{sub}</p> : null}
      </div>
      {actions ? <div className="card-actions">{actions}</div> : null}
    </div>
  );
}

type StatTone = 'default' | 'accent' | 'success' | 'warning' | 'danger';

export function StatCard({
  title,
  value,
  hint,
  icon,
  tone = 'default',
}: {
  title: string;
  value: string;
  hint?: string;
  icon?: ReactNode;
  tone?: StatTone;
}) {
  const titleId = `stat-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <section className="card stat-card" aria-labelledby={titleId}>
      <div className="stat-top">
        <p className="card-title" id={titleId} style={{ margin: 0 }}>
          {title}
        </p>
        {icon ? (
          <span className={tone === 'default' ? 'stat-icon' : `stat-icon ${tone}`} aria-hidden="true">
            {icon}
          </span>
        ) : null}
      </div>
      <p className="card-value">{value}</p>
      {hint ? <p className="stat-hint">{hint}</p> : null}
    </section>
  );
}
