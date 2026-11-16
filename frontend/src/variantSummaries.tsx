import { useEffect, useState } from 'react';
import { api, ApiError } from './api';

export interface VariantSummary {
  id: string;
  productName: string;
  sku: string;
  attributes: Record<string, string>;
  quantity: number | null;
}

export function useVariantSummaries(ids: string[], token: string, onExpired: () => void, refresh = 0) {
  const key = [...new Set(ids)].sort().join(',');
  const [items, setItems] = useState<Record<string, VariantSummary>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    if (!key) { setItems({}); setError(false); setLoading(false); return; }
    setLoading(true);
    setError(false);
    const ids = key.split(',');
    const batches = Array.from({ length: Math.ceil(ids.length / 50) }, (_, index) => ids.slice(index * 50, (index + 1) * 50));
    Promise.all(batches.map(batch => api<VariantSummary[]>(`/api/variants/lookup?ids=${encodeURIComponent(batch.join(','))}`, token, { signal: controller.signal })))
      .then(result => { if (!controller.signal.aborted) setItems(Object.fromEntries(result.flat().map(item => [item.id, item]))); })
      .catch(failure => {
        if (controller.signal.aborted) return;
        setItems({}); setError(true);
        if (failure instanceof ApiError && failure.status === 401) onExpired();
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [key, token, refresh]);

  return { items, loading, error };
}

export function variantLabel(item: VariantSummary | undefined, loading: boolean, error: boolean) {
  if (loading) return 'Loading product…';
  if (error) return 'Product details unavailable';
  if (!item) return 'Product no longer available';
  return item.productName;
}

export function variantDetails(item: VariantSummary | undefined) {
  if (!item) return '';
  const attributes = Object.entries(item.attributes).map(([key, value]) => `${key}: ${value}`);
  return [item.sku, ...attributes].join(' · ');
}
