import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { DayBucket } from './stats';

const PIE_COLORS = ['#047857', '#b42318', '#5b6b7f', '#92400e'];

export interface Slice {
  name: string;
  value: number;
}

/** Dashboard charts, split into their own chunk — Recharts is the heaviest
 * dependency and only the dashboard needs it. Loaded via React.lazy. */
export default function Charts({ daily, slices }: { daily: DayBucket[]; slices: Slice[] }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
        gap: 16,
        marginBottom: 24,
      }}
    >
      <section className="card" aria-labelledby="txn-over-time">
        <h2 id="txn-over-time" style={{ fontSize: '0.9rem' }}>
          Transactions — last 14 days
        </h2>
        <div style={{ height: 260 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={daily} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#dfe4ec" />
              <XAxis dataKey="day" tick={{ fontSize: 11 }} />
              <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Bar dataKey="approved" name="Approved" stackId="a" fill="#047857" />
              <Bar dataKey="declined" name="Declined" stackId="a" fill="#b42318" />
              <Bar dataKey="other" name="Other" stackId="a" fill="#8595a9" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className="card" aria-labelledby="approval-mix">
        <h2 id="approval-mix" style={{ fontSize: '0.9rem' }}>
          Outcome mix
        </h2>
        <div style={{ height: 260 }}>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={slices} dataKey="value" nameKey="name" outerRadius={90} label>
                {slices.map((entry, index) => (
                  <Cell key={entry.name} fill={PIE_COLORS[index % PIE_COLORS.length]} />
                ))}
              </Pie>
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </section>
    </div>
  );
}
