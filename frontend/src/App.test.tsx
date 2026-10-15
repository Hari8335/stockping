import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import App from './App';

test('identifies the running StockPing skeleton', () => {
  render(<App />);
  expect(screen.getByRole('main')).toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 1, name: 'StockPing' })).toBeVisible();
  expect(screen.getByText('Stage 0 · Project skeleton')).toBeVisible();
  expect(screen.getByText(/The frontend is running/)).toBeVisible();
});

