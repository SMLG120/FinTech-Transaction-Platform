import type { ReactNode } from 'react';

export function Card({ children, labelledBy }: { children: ReactNode; labelledBy?: string }) {
  return (
    <section className="card" aria-labelledby={labelledBy}>
      {children}
    </section>
  );
}

export function StatCard({
  title,
  value,
  hint,
}: {
  title: string;
  value: string;
  hint?: string;
}) {
  const titleId = `stat-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <section className="card" aria-labelledby={titleId}>
      <p className="card-title" id={titleId}>
        {title}
      </p>
      <p className="card-value">{value}</p>
      {hint ? <p className="field-hint">{hint}</p> : null}
    </section>
  );
}
