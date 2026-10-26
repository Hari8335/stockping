import { lazy, Suspense, useEffect, useState, type FormEvent } from 'react';
import { Alert, Box, Button, Container, Paper, Stack, TextField, Typography } from '@mui/material';
import { api, type Session } from './api';
const WaitlistScreen = lazy(() => import('./WaitlistScreen'));

export default function App() {
  // Memory only: reload and tab closure require login. No password/token persistence.
  const [session, setSession] = useState<Session | null>(null);
  const [notice, setNotice] = useState('');

  function expire(token: string) {
    setSession(current => current?.accessToken === token ? null : current);
    setNotice('Your session expired. Please sign in again.');
  }

  useEffect(() => {
    if (!session) return;
    const timeout = window.setTimeout(() => expire(session.accessToken), Math.max(0, Date.parse(session.expiresAt) - Date.now()));
    return () => window.clearTimeout(timeout);
  }, [session]);

  function logout() {
    if (session) void api('/api/auth/logout', session.accessToken, { method: 'POST' }).catch(() => undefined);
    setSession(null);
    setNotice('You have signed out.');
  }

  return (
    <Box component="main" sx={{ minHeight: '100vh', bgcolor: 'grey.50', py: { xs: 3, sm: 6 } }}>
      <Container maxWidth={session ? 'lg' : 'sm'}>
        <Stack spacing={3}>
          <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <Box>
              <Typography component="h1" variant="h4" sx={{ fontWeight: 700 }}>StockPing</Typography>
              <Typography color="text.secondary">Merchant waitlists</Typography>
            </Box>
            {session && <Button onClick={logout}>Log out</Button>}
          </Stack>
          {session ? <Suspense fallback={<Typography role="status">Loading merchant tools…</Typography>}>
            <WaitlistScreen key={session.accessToken} session={session} onExpired={() => expire(session.accessToken)} />
          </Suspense>
            : <Login notice={notice} onLogin={value => { setSession(value); setNotice(''); }} />}
        </Stack>
      </Container>
    </Box>
  );
}

function Login({ notice, onLogin }: { notice: string; onLogin: (session: Session) => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!email.trim() || !password) { setError('Enter your email and password.'); return; }
    setPending(true); setError('');
    try {
      const session = await api<Session>('/api/auth/login', undefined, {
        method: 'POST', body: JSON.stringify({ email: email.trim(), password }),
      });
      setPassword('');
      onLogin(session);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to sign in. Please try again.');
    } finally { setPending(false); }
  }
  return (
    <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
      <Stack component="form" spacing={3} onSubmit={submit} aria-label="Merchant login">
        <Typography component="h2" variant="h5">Sign in</Typography>
        <Typography color="text.secondary">Sign in to view customers waiting for your products.</Typography>
        {notice && <Alert severity="info">{notice}</Alert>}
        {error && <Alert severity="error">{error}</Alert>}
        <TextField label="Email" type="email" required autoComplete="username" value={email}
          onChange={event => setEmail(event.target.value)} disabled={pending} />
        <TextField label="Password" type="password" required autoComplete="current-password" value={password}
          onChange={event => setPassword(event.target.value)} disabled={pending} />
        <Button type="submit" variant="contained" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</Button>
      </Stack>
    </Paper>
  );
}

