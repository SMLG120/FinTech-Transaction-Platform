/** Every section is implemented; only the 404 page lives here now. */

import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="state-block" role="alert">
      <h2>Page not found</h2>
      <p>That path does not exist in this dashboard. Check the navigation or head back home.</p>
      <Link to="/" className="btn btn-primary">
        Back to dashboard
      </Link>
    </div>
  );
}
