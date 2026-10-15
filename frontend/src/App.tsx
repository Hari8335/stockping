import { Box, Chip, Container, Paper, Stack, Typography } from '@mui/material';

export default function App() {
  return (
    <Box component="main" sx={{ minHeight: '100vh', bgcolor: 'grey.50', py: { xs: 5, sm: 10 } }}>
      <Container maxWidth="sm">
        <Paper variant="outlined" sx={{ p: { xs: 3, sm: 5 } }}>
          <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
            <Typography component="h1" variant="h3" sx={{ fontWeight: 700 }}>StockPing</Typography>
            <Typography variant="h6" component="p">Retail demand and restock notifications through WhatsApp.</Typography>
            <Chip label="Stage 0 · Project skeleton" color="primary" variant="outlined" />
            <Typography color="text.secondary">The frontend is running. Merchant tools will arrive in later implementation stages.</Typography>
          </Stack>
        </Paper>
      </Container>
    </Box>
  );
}

