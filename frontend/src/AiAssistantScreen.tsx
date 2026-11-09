import { useState, type FormEvent } from 'react';
import { Alert, Box, Button, Chip, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material';
import { api, ApiError, type AiQueryResponse, type Session } from './api';

const sampleQueries = [
  'S25 Ultra 512 black available?',
  's25u blk 512 thiyenawada',
  'Need iphone 17 pro 256 black',
  'Do you have the silver 512 one?',
  'stop sending alerts',
];

export default function AiAssistantScreen({ session, onExpired }: { session: Session; onExpired: () => void }) {
  const [query, setQuery] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<AiQueryResponse | null>(null);

  async function submitQuery(text: string) {
    const trimmed = text.trim();
    if (!trimmed) return;
    setPending(true);
    setError('');
    setResult(null);
    try {
      const response = await api<AiQueryResponse>('/api/ai/query-preview', session.accessToken, {
        method: 'POST',
        body: JSON.stringify({ query: trimmed }),
      });
      setResult(response);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        onExpired();
      } else {
        setError(failure instanceof Error ? failure.message : 'Unable to analyze query.');
      }
    } finally {
      setPending(false);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void submitQuery(query);
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h2" variant="h5">AI Stock Query Assistant</Typography>
        <Typography color="text.secondary">
          Simulate how natural-language WhatsApp messages are interpreted and mapped to catalog inventory.
        </Typography>
      </Box>

      <Paper variant="outlined" sx={{ p: 3 }}>
        <Stack spacing={2} component="form" onSubmit={handleSubmit} aria-label="AI Query Form">
          <Typography variant="subtitle2" color="text.secondary">Try a sample customer inquiry:</Typography>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
            {sampleQueries.map(sample => (
              <Chip
                key={sample}
                label={sample}
                onClick={() => { setQuery(sample); void submitQuery(sample); }}
                clickable
                variant="outlined"
                size="small"
              />
            ))}
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Customer WhatsApp message"
              placeholder="e.g. s25 ultra 512 black available?"
              value={query}
              onChange={event => setQuery(event.target.value)}
              disabled={pending}
              fullWidth
              required
            />
            <Button type="submit" variant="contained" disabled={pending || !query.trim()} sx={{ minWidth: 140 }}>
              {pending ? 'Analyzing…' : 'Test inquiry'}
            </Button>
          </Stack>
        </Stack>
      </Paper>

      {pending && (
        <Box role="status" aria-label="Analyzing customer inquiry">
          <LinearProgress />
          <Typography sx={{ mt: 1 }} color="text.secondary">Analyzing inquiry with AI extractor…</Typography>
        </Box>
      )}

      {error && (
        <Alert severity="error" action={<Button color="inherit" onClick={() => void submitQuery(query)}>Retry</Button>}>
          {error}
        </Alert>
      )}

      {result && (
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Stack spacing={2}>
            <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
              <Typography variant="h6">Extraction & Stock Resolution</Typography>
              <Stack direction="row" spacing={1}>
                <Chip label={result.intent} color="primary" size="small" />
                <Chip
                  label={`${Math.round(result.confidence * 100)}% confidence`}
                  color={result.confidence >= 0.70 ? 'success' : 'warning'}
                  size="small"
                  variant="outlined"
                />
                <Chip label={`Catalog: ${result.catalogStatus}`} size="small" variant="outlined" />
              </Stack>
            </Stack>

            <Box sx={{ bgcolor: 'grey.50', p: 2, borderRadius: 1 }}>
              <Typography variant="subtitle2" color="text.secondary">Extracted Entities:</Typography>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 0.5 }}>
                <Typography variant="body2"><strong>Brand:</strong> {result.extracted.brand || '—'}</Typography>
                <Typography variant="body2"><strong>Model:</strong> {result.extracted.model || '—'}</Typography>
                <Typography variant="body2"><strong>Product phrase:</strong> {result.extracted.productPhrase || '—'}</Typography>
                {Object.keys(result.extracted.attributes).length > 0 && (
                  <Typography variant="body2">
                    <strong>Attributes:</strong> {Object.entries(result.extracted.attributes).map(([k, v]) => `${k}: ${v}`).join(', ')}
                  </Typography>
                )}
              </Stack>
            </Box>

            {result.matchedVariant && (
              <Box sx={{ bgcolor: 'grey.50', p: 2, borderRadius: 1 }}>
                <Typography variant="subtitle2" color="text.secondary">Authoritative Catalog Match:</Typography>
                <Typography variant="body2">
                  <strong>{result.matchedVariant.productName}</strong> (SKU: {result.matchedVariant.sku})
                </Typography>
                <Typography variant="body2" color={result.matchedVariant.available ? 'success.main' : 'error.main'}>
                  Status: {result.matchedVariant.available ? `In Stock (${result.matchedVariant.quantity} units)` : 'Out of Stock (0 units)'}
                </Typography>
              </Box>
            )}

            <Box sx={{ p: 2, border: 1, borderColor: 'divider', borderRadius: 1 }}>
              <Typography variant="subtitle2" color="text.secondary">Suggested WhatsApp Response:</Typography>
              <Typography variant="body1" sx={{ mt: 0.5, fontStyle: 'italic' }}>
                "{result.suggestedReply}"
              </Typography>
            </Box>

            <Alert severity="info" sx={{ mt: 1 }}>
              {result.disclaimer}
            </Alert>
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}
