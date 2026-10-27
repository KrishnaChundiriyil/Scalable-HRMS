import type { Session } from '../types';

let token = '';
export function setToken(value: string) { token = value; }
export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) { super(message); this.status = status; }
}
export async function api<T>(path: string, options: { method?: string; body?: unknown; public?: boolean; headers?: Record<string, string>; signal?: AbortSignal } = {}): Promise<T> {
  const headers: Record<string, string> = { ...options.headers };
  if (token && !options.public) headers.Authorization = `Bearer ${token}`;
  const multipart = options.body instanceof FormData;
  if (options.body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  let response: Response;
  try {
    response = await fetch(path, { method: options.method || 'GET', headers, body: options.body === undefined ? undefined : multipart ? options.body as FormData : JSON.stringify(options.body), signal: options.signal || AbortSignal.timeout(15000) });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError('We couldn’t connect. Please check your connection and try again.', 0);
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 && token && !options.public) window.dispatchEvent(new Event('alder-session-expired'));
    const message = typeof data.error === 'string' ? data.error : typeof data.message === 'string' ? data.message : `This request could not be completed (${response.status}).`;
    throw new ApiError(response.status === 503 ? 'This service is temporarily unavailable. Your information is safe. Please try again shortly.' : message, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}
export function loadSession(): Session | null {
  try {
    const session = JSON.parse(sessionStorage.getItem('alder.session') || 'null');
    if (!session?.token || !session?.employee?.employeeId) return null;
    const encoded = session.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    if (JSON.parse(atob(encoded)).exp * 1000 <= Date.now()) return null;
    return session;
  } catch { return null; }
}
export const qs = (values: Record<string, string | number | undefined>) => {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)); });
  return params.toString();
};
export async function downloadFile(path: string, name: string) {
  const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new ApiError('The file could not be downloaded. Please try again.', response.status);
  const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
