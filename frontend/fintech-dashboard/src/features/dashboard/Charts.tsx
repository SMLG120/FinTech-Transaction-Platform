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
import { CardHeader, Card } from '../../components/ui/Card';
import type { DayBucket } from './stats';

const PIE_COLORS = ['#046c4e', '#b42318', '#175cd3', '#8a4a08'];

export interface Slice {
  name: string;
  value: number;
}

/** Dashboard charts, split into their own chunk — Recharts is the heaviest
 * dependency and only the dashboard needs it. Loaded via React.lazy. */
export default function Charts({ daily, slices }: { daily: DayBucket[]; slices: Slice[] }) {
  return (
    <div className="grid-2" role="group" aria-label="Transaction charts">
      <Card labelledBy="txn-over-time">
        <CardHeader
          titleId="txn-over-time"
          title="Transactions — last 14 days"
          sub="Daily approvals, declines, and other outcomes"
        />
        <div style={{ height: 260 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={daily} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e3e8ef" vertical={false} />
              <XAxis dataKey="day" tick={{ fontSize: 11, fill: '#55677d' }} tickLine={false} axisLine={{ stroke: '#dfe4ec' }} />
              <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: '#55677d' }} tickLine={false} axisLine={false} />
              <Tooltip
                contentStyle={{ borderRadius: 8, border: '1px solid #dfe4ec', fontSize: 12 }}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} iconType="circle" iconSize={8} />
              <Bar dataKey="approved" name="Approved" stackId="a" fill="#046c4e" radius={[0, 0, 0, 0]} />
              <Bar dataKey="declined" name="Declined" stackId="a" fill="#b42318" />
              <Bar dataKey="other" name="Other" stackId="a" fill="#8fa1ba" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </Card>

      <Card labelledBy="approval-mix">
        <CardHeader
          titleId="approval-mix"
          title="Outcome mix"
          sub="Share of decided vs. pending payments"
        />
        <div style={{ height: 260 }}>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={slices}
                dataKey="value"
                nameKey="name"
                outerRadius={88}
                innerRadius={52}
                paddingAngle={2}
                label={{ fontSize: 11 }}
              >
                {slices.map((entry, index) => (
                  <Cell
                    key={entry.name}
                    fill={PIE_COLORS[index % PIE_COLORS.length]}
                    stroke="#fff"
                    strokeWidth={2}
                  />
                ))}
              </Pie>
              <Tooltip contentStyle={{ borderRadius: 8, border: '1px solid #dfe4ec', fontSize: 12 }} />
              <Legend wrapperStyle={{ fontSize: 12 }} iconType="circle" iconSize={8} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </Card>
    </div>
  );
}
