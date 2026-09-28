/** Every section is implemented; only the 404 page lives here now. */

export function NotFoundPage() {
  return (
    <div className="state-block" role="alert">
      <h2>Page not found</h2>
      <p>That path does not exist in this dashboard.</p>
    </div>
  );
}
