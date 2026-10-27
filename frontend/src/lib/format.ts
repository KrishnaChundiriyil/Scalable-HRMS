export const label = (value: string) => value?.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()) || '—';
export const initials = (name: string) => name.trim().split(/\s+/).slice(0, 2).map(part => part[0]).join('').toUpperCase();
export const date = (value: string, options: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' }) => new Date(value.length === 10 ? value + 'T12:00:00' : value).toLocaleDateString('en-IN', options);
export const money = (value: number) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(value);
export const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
export const isoDate = (value: Date) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
export function monthRange(month: string) { const [y, m] = month.split('-').map(Number); return { from: `${month}-01`, to: isoDate(new Date(y, m, 0)) }; }
export const fullName = (name: string) => name.split(' ')[0];
export function exportCsv(name: string, rows: (string | number)[][]) {
  const csv = rows.map(row => row.map(value => { const text = String(value); const safe = /^[=+@\-\t\r]/.test(text) ? "'" + text : text; return `"${safe.replace(/"/g, '""')}"`; }).join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' }));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click(); URL.revokeObjectURL(url);
}
