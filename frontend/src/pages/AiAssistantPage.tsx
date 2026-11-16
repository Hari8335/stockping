import { useState, type FormEvent } from 'react';
import { Alert, Box, Button, Chip, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material';
import { tokens } from '../theme';
import { api, ApiError, type AiQueryResponse, type Session } from '../api';

const sampleQueries = [
  'S25 Ultra 512 black available?',
  's25u blk 512 thiyenawada',
  'Need iphone 17 pro 256 black',
  'Do you have the silver 512 one?',
  'stop sending alerts',
];

function ConfidenceMeter({ value }: { value: number }) {
  const pct = Math.round(value * 100);
  const color = pct >= 80 ? tokens.teal : pct >= 60 ? tokens.amber : tokens.red;
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
      <Box sx={{ flex: 1, height: 6, bgcolor: tokens.parchment, borderRadius: 3, overflow: 'hidden' }}>
        <Box sx={{ width: `${pct}%`, height: '100%', bgcolor: color, borderRadius: 3 }} />
      </Box>
      <Typography sx={{ fontSize: '0.8rem', fontWeight: 700, color, minWidth: 36 }}>{pct}%</Typography>
    </Box>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ display: 'flex', gap: 1.5, py: 0.75, borderBottom: `1px solid ${tokens.divider}`, '&:last-child': { borderBottom: 0 } }}>
      <Typography sx={{ fontSize: '0.78rem', fontWeight: 600, color: tokens.inkLight, minWidth: 100, flexShrink: 0 }}>
        {label}
      </Typography>
      <Typography sx={{ fontSize: '0.8rem', color: tokens.ink, wordBreak: 'break-word' }}>
        {value || '—'}
      </Typography>
    </Box>
  );
}

export default function AiAssistantPage({ session, onExpired }: { session: Session; onExpired: () => void }) {
  const [query, setQuery] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<AiQueryResponse | null>(null);

  async function submitQuery(text: string) {
    const trimmed = text.trim();
    if (!trimmed) return;
    setPending(true); setError(''); setResult(null);
    try {
      const response = await api<AiQueryResponse>('/api/ai/query-preview', session.accessToken, {
        method: 'POST',
        body: JSON.stringify({ query: trimmed }),
      });
      setResult(response);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else setError(failure instanceof Error ? failure.message : 'Unable to analyze query.');
    } finally { setPending(false); }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void submitQuery(query);
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h1" variant="h5">AI Stock Query Assistant</Typography>
        <Typography variant="body2" color="text.secondary">
          Preview how natural-language customer messages are interpreted and matched against your catalog.
        </Typography>
      </Box>

      {/* Input panel */}
      <Paper variant="outlined" sx={{ p: 2.5, bgcolor: tokens.white }}>
        <Stack spacing={2} component="form" onSubmit={handleSubmit} aria-label="AI Query Form">
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1.5 }}>Sample customer inquiries</Typography>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: { xs: 1, sm: 1.25 }, alignItems: 'center' }}>
              {sampleQueries.map(sample => (
                <Chip
                  key={sample}
                  label={sample}
                  onClick={() => { setQuery(sample); void submitQuery(sample); }}
                  clickable
                  variant="outlined"
                  size="small"
                  sx={{ borderRadius: 1, fontSize: '0.78rem', borderColor: tokens.divider, '&:hover': { borderColor: tokens.teal, bgcolor: tokens.tealBg } }}
                />
              ))}
            </Box>
          </Box>
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1.5, alignItems: 'flex-end' }}>
            <TextField
              label="Customer WhatsApp message"
              placeholder="e.g. s25 ultra 512 black available?"
              value={query}
              onChange={e => setQuery(e.target.value)}
              disabled={pending}
              fullWidth
              required
              size="small"
            />
            <Button type="submit" variant="contained" disabled={pending || !query.trim()} sx={{ minWidth: 130, flexShrink: 0 }}>
              {pending ? 'Analyzing…' : 'Test inquiry'}
            </Button>
          </Box>
        </Stack>
      </Paper>

      {pending && (
        <Box role="status" aria-label="Analyzing customer inquiry">
          <LinearProgress />
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Analyzing inquiry with AI extractor…</Typography>
        </Box>
      )}

      {error && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => void submitQuery(query)}>Retry</Button>}>
          {error}
        </Alert>
      )}

      {/* Results */}
      {result && (
        <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
          {/* Header */}
          <Box sx={{ px: 2.5, py: 2, borderBottom: `1px solid ${tokens.divider}`, bgcolor: tokens.parchment }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
              <Box>
                  <Typography variant="caption" sx={{ display: 'block', mb: 0.25 }}>Preview result</Typography>
                  <Typography variant="h6" sx={{ fontSize: '0.95rem' }}>
                    Extraction &amp; Stock Resolution
                  </Typography>
              </Box>
              <Box sx={{ display: 'flex', gap: 0.75 }}>
                <Box sx={{ px: 0.75, py: 0.25, bgcolor: tokens.ink, color: '#fff', borderRadius: 0.5, fontSize: '0.7rem', fontWeight: 700, letterSpacing: '0.05em' }}>
                  {result.intent}
                </Box>
                <Box sx={{ px: 0.75, py: 0.25, bgcolor: result.catalogStatus === 'MATCHED' ? tokens.tealBg : tokens.amberBg, color: result.catalogStatus === 'MATCHED' ? tokens.teal : tokens.amber, borderRadius: 0.5, fontSize: '0.7rem', fontWeight: 700 }}>
                  Catalog: {result.catalogStatus}
                </Box>
                <Box sx={{ px: 0.75, py: 0.25, bgcolor: tokens.parchment, color: tokens.inkLight, borderRadius: 0.5, fontSize: '0.7rem', fontWeight: 600 }}>
                  {result.latencyMs}ms · {result.provider}
                </Box>
              </Box>
            </Box>
            <Box sx={{ mt: 1.5, maxWidth: 320 }}>
              <Typography sx={{ fontSize: '0.72rem', color: tokens.inkLight, mb: 0.5 }}>Confidence</Typography>
              <ConfidenceMeter value={result.confidence} />
            </Box>
          </Box>

          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, borderBottom: `1px solid ${tokens.divider}` }}>
            {/* Extracted entities */}
            <Box sx={{ flex: 1, p: 2.5, borderRight: { md: `1px solid ${tokens.divider}` }, borderBottom: { xs: `1px solid ${tokens.divider}`, md: 'none' } }}>
              <Typography variant="subtitle2" sx={{ mb: 1.5 }}>Extracted entities</Typography>
              <InfoRow label="Brand" value={result.extracted.brand} />
              <InfoRow label="Model" value={result.extracted.model} />
              <InfoRow label="Product phrase" value={result.extracted.productPhrase} />
              {Object.entries(result.extracted.attributes).map(([k, v]) => (
                <InfoRow key={k} label={k} value={v} />
              ))}
            </Box>
            {/* Catalog match */}
            <Box sx={{ flex: 1, p: 2.5 }}>
              <Typography variant="subtitle2" sx={{ mb: 1.5 }}>Catalog match</Typography>
              {result.matchedVariant ? (
                <>
                  <InfoRow label="Product" value={result.matchedVariant.productName} />
                  <InfoRow label="Brand" value={result.matchedVariant.brand} />
                  <InfoRow label="SKU" value={result.matchedVariant.sku} />
                  {Object.entries(result.matchedVariant.attributes).map(([k, v]) => (
                    <InfoRow key={k} label={k} value={v} />
                  ))}
                  <Box sx={{ mt: 1.5 }}>
                    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, px: 1.25, py: 0.5, bgcolor: result.matchedVariant.available ? tokens.tealBg : tokens.redBg, color: result.matchedVariant.available ? tokens.teal : tokens.red, borderRadius: 1, fontWeight: 700, fontSize: '0.8rem' }}>
                      <Box sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: 'currentColor' }} />
                      {result.matchedVariant.available ? `In Stock (${result.matchedVariant.quantity} units)` : 'Out of Stock (0 units)'}
                    </Box>
                  </Box>
                </>
              ) : (
                <Box sx={{ py: 2 }}>
                  <Typography variant="body2" color="text.secondary">No catalog match found for this inquiry.</Typography>
                </Box>
              )}
            </Box>
          </Box>

          {/* Clarification */}
          {result.needsClarification && result.clarificationPrompt && (
            <Box sx={{ px: 2.5, py: 2, borderBottom: `1px solid ${tokens.divider}`, bgcolor: tokens.amberBg }}>
              <Typography sx={{ fontSize: '0.78rem', fontWeight: 700, color: tokens.amber, mb: 0.5 }}>Clarification needed</Typography>
              <Typography sx={{ fontSize: '0.85rem', color: tokens.ink }}>{result.clarificationPrompt}</Typography>
            </Box>
          )}

          {/* Suggested reply */}
          <Box sx={{ px: 2.5, py: 2, borderBottom: `1px solid ${tokens.divider}` }}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>Suggested WhatsApp reply</Typography>
            <Box sx={{ p: 2, bgcolor: tokens.parchment, borderRadius: 1, borderLeft: `3px solid ${tokens.teal}` }}>
              <Typography sx={{ fontSize: '0.875rem', fontStyle: 'italic', color: tokens.ink, lineHeight: 1.6 }}>
                "{result.suggestedReply}"
              </Typography>
            </Box>
          </Box>

          {/* Disclaimer */}
          <Box sx={{ px: 2.5, py: 2, bgcolor: tokens.parchment }}>
            <Alert severity="info" sx={{ fontSize: '0.78rem' }}>{result.disclaimer}</Alert>
          </Box>
        </Paper>
      )}
    </Stack>
  );
}
