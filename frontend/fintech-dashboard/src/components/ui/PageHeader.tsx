import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/** Consistent page heading: eyebrow + title + description + optional actions. */
export function PageHeader({
  eyebrow,
  title,
  sub,
  actions,
}: {
  eyebrow?: string;
  title: string;
  sub?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-head">
      <div>
        {eyebrow ? <p className="page-eyebrow">{eyebrow}</p> : null}
        <h1 className="page-title">{title}</h1>
        {sub ? <p className="page-sub">{sub}</p> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  );
}

/** Breadcrumb trail for detail views. */
export function Breadcrumbs({ trail }: { trail: { label: string; to?: string }[] }) {
  return (
    <nav aria-label="Breadcrumb" style={{ marginBottom: 12 }}>
      <ol
        style={{
          display: 'flex',
          gap: 8,
          alignItems: 'center',
          listStyle: 'none',
          margin: 0,
          padding: 0,
          fontSize: '0.83rem',
          color: 'var(--ink-muted)',
          flexWrap: 'wrap',
        }}
      >
        {trail.map((item, i) => (
          <li key={item.label} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {i > 0 ? <span aria-hidden="true">/</span> : null}
            {item.to ? <Link to={item.to}>{item.label}</Link> : <span aria-current="page">{item.label}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}
