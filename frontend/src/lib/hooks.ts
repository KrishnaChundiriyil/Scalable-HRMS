import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './api';
import { useAuth } from './auth';
import { useToast } from '../components/ui';
import type { Employee } from '../types';

export const usePeople = (enabled = true) => useQuery({ enabled, queryKey: ['people'], queryFn: () => api<Employee[]>('/api/employees') });
export function useDebounced<T>(value: T, wait = 300) { const [result, setResult] = useState(value); useEffect(() => { const timer = setTimeout(() => setResult(value), wait); return () => clearTimeout(timer); }, [JSON.stringify(value), wait]); return result; }
export function useLiveNotifications() {
  const { session } = useAuth(); const client = useQueryClient(); const toast = useToast();
  useEffect(() => {
    if (!session || session.employee.role === 'CANDIDATE') return;
    const controller = new AbortController(); let lastId = ''; const seen = new Set<string>(); let timer: ReturnType<typeof setTimeout>;
    const connect = async () => {
      try {
        const response = await fetch('/api/leaves/notifications/stream', { headers: { Authorization: `Bearer ${session.token}`, Accept: 'text/event-stream', ...(lastId ? { 'Last-Event-ID': lastId } : {}) }, signal: controller.signal });
        if (!response.ok || !response.body) throw new Error('Stream unavailable');
        const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
        while (!controller.signal.aborted) {
          const { done, value } = await reader.read(); if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          let end;
          while ((end = buffer.indexOf('\n\n')) !== -1) {
            const event = buffer.slice(0, end); buffer = buffer.slice(end + 2);
            const id = event.match(/^id:\s*(.+)$/m)?.[1]; if (id) lastId = id;
            if (event.includes('data:') && !event.includes('event:connected')) {
              if (event.includes('event:leave-update') && id && !seen.has(id)) {
                seen.add(id); if (seen.size > 200) seen.delete(seen.values().next().value!);
                try { const data = JSON.parse(event.match(/^data:\s*(.+)$/m)?.[1] || '{}'); if (data.message === 'Your leave request was approved') window.dispatchEvent(new CustomEvent('alder-leave-approved', { detail: data })); else if (typeof data.message === 'string') toast(data.message); } catch { /* Persisted Inbox remains the source of truth. */ }
              }
              client.invalidateQueries({ queryKey: ['notifications'] }); client.invalidateQueries({ queryKey: ['leave'] }); client.invalidateQueries({ queryKey: ['recent-activity'] });
            }
          }
        }
      } catch { /* Persisted notification queries remain available during reconnect. */ }
      if (!controller.signal.aborted) timer = setTimeout(connect, 10000);
    };
    connect();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [session?.token, client]);
}
