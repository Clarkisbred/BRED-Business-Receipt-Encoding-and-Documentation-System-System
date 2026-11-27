import { useState, type FormEvent } from 'react';
import { LockKeyhole } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';

export function Login() {
  const { signIn, message, configurationError, clearMessage, status } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    clearMessage();
    setSubmitting(true);
    try {
      await signIn(email, password);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">BRED</div>
        <p className="eyebrow">BUSINESS RECEIPT WORKSPACE</p>
        <h1 id="login-title">Sign in</h1>
        <p className="login-copy">Use your approved BRED account to view your workspace.</p>

        {configurationError && (
          <div className="notice notice-config" role="status">
            <strong>Connect the existing Supabase project</strong>
            <p>{configurationError}</p>
            <p>Use only the public anon/publishable key; never put a service-role key in browser configuration.</p>
          </div>
        )}

        {message && <p className="form-message" role="alert">{message}</p>}

        <form className="login-form" onSubmit={(event) => void handleSubmit(event)}>
          <label htmlFor="email">Email address</label>
          <input
            autoComplete="username"
            id="email"
            name="email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />

          <label htmlFor="password">Password</label>
          <input
            autoComplete="current-password"
            id="password"
            name="password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />

          <button className="button button-primary login-submit" type="submit" disabled={submitting || status === 'initializing'}>
            <LockKeyhole aria-hidden="true" size={17} />
            {submitting || status === 'initializing' ? 'Checking account…' : 'Sign in'}
          </button>
        </form>
        <p className="login-footnote">Pending, rejected, and inactive accounts cannot access BRED data.</p>
      </section>
    </main>
  );
}
