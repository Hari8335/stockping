import { useState, type FormEvent } from 'react';
import { useNavigate, Link as RouterLink } from 'react-router-dom';
import { Alert, Box, Button, Container, Paper, Stack, TextField, Typography, Link } from '@mui/material';
import { tokens } from '../theme';
import { StockPingWordmark } from '../components/Logo';
import { api, ApiError, type Session } from '../api';
import { useAuth } from '../contexts/AuthContext';

export default function LoginPage() {
  const { login, notice, clearNotice } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError('Enter your email and password.');
      return;
    }
    setPending(true);
    setError('');
    clearNotice();
    try {
      const session = await api<Session>('/api/auth/login', undefined, {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), password }),
      });
      setPassword('');
      login(session);
      navigate('/app');
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Unable to sign in. Please try again.');
    } finally {
      setPending(false);
    }
  }

  return (
    <Box
      sx={{
        minHeight: '100vh',
        bgcolor: tokens.offWhite,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Top bar */}
      <Box
        sx={{
          bgcolor: tokens.white,
          borderBottom: `1px solid ${tokens.divider}`,
          px: { xs: 2.5, sm: 4 },
          py: 1.75,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <Link component={RouterLink} to="/" underline="none" sx={{ display: 'flex', alignItems: 'center' }}>
          <StockPingWordmark height={28} color={tokens.ink} accentColor={tokens.teal} />
        </Link>
        <Link
          component={RouterLink}
          to="/"
          underline="none"
          sx={{
            fontSize: '0.82rem',
            fontWeight: 600,
            color: tokens.inkLight,
            '&:hover': { color: tokens.ink },
          }}
        >
          ← Back to overview
        </Link>
      </Box>

      <Box sx={{ flex: 1, display: 'flex', alignItems: 'center', py: { xs: 5, sm: 8 } }}>
        <Container maxWidth="xs">
          <Paper
            variant="outlined"
            sx={{
              p: { xs: 3.5, sm: 4.5 },
              bgcolor: tokens.white,
              borderColor: tokens.divider,
              borderRadius: 2.5,
              boxShadow: '0 8px 30px rgba(15, 23, 42, 0.04)',
            }}
          >
            <Stack component="form" spacing={2.75} onSubmit={submit} aria-label="Merchant login">
              <Box>
                <Box
                  sx={{
                    display: 'inline-block',
                    px: 1,
                    py: 0.3,
                    bgcolor: tokens.parchment,
                    borderRadius: 1,
                    mb: 1.25,
                  }}
                >
                  <Typography
                    sx={{
                      fontSize: '0.68rem',
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: tokens.inkMid,
                    }}
                  >
                    MERCHANT PORTAL
                  </Typography>
                </Box>
                <Typography component="h1" variant="h5" sx={{ mb: 0.5, fontWeight: 750, letterSpacing: '-0.02em' }}>
                  Sign in
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Access waitlists, demand signals, and restock notifications.
                </Typography>
              </Box>

              {notice && <Alert severity="info">{notice}</Alert>}
              {error && <Alert severity="error">{error}</Alert>}

              <TextField
                label="Email"
                type="email"
                required
                autoComplete="username"
                autoFocus
                value={email}
                onChange={e => setEmail(e.target.value)}
                disabled={pending}
                fullWidth
              />
              <TextField
                label="Password"
                type="password"
                required
                autoComplete="current-password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                disabled={pending}
                fullWidth
              />
              <Button
                type="submit"
                variant="contained"
                disabled={pending}
                size="large"
                fullWidth
                sx={{ py: 1.25, fontSize: '0.92rem' }}
              >
                {pending ? 'Signing in…' : 'Sign in to Merchant Portal'}
              </Button>

              <Box sx={{ pt: 1, borderTop: `1px solid ${tokens.divider}`, textAlign: 'center' }}>
                <Typography sx={{ fontSize: '0.74rem', color: tokens.inkLight }}>
                  Authorized store operator credentials required
                </Typography>
              </Box>
            </Stack>
          </Paper>
        </Container>
      </Box>
    </Box>
  );
}
