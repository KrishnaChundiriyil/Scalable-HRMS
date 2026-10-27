import { createContext, useContext, useEffect, useId, useRef, useState } from 'react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { AlertCircle, ArrowRight, Check, CheckCircle2, ChevronLeft, ChevronRight, Inbox, LoaderCircle, X } from 'lucide-react';
import { initials, label } from '../lib/format';

export function Button({ children, className = '', variant = 'primary', busy, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; busy?: boolean }) {
  return <button {...props} disabled={props.disabled || busy} className={`button ${variant} ${className}`} aria-busy={busy || undefined}>{busy && <LoaderCircle size={17} className="spin" aria-hidden="true" />}{children}</button>;
}
export function Brand({ small = false }: { small?: boolean }) { return <span className={`brand ${small ? 'small' : ''}`}><img src="/alder.svg" alt="" /><span>Alder <em>&</em> Co.<small>PEOPLE WORKSPACE</small></span></span>; }
export function Avatar({ name, size = 'normal', index = 0 }: { name: string; size?: string; index?: number }) { return <span aria-hidden="true" className={`avatar ${size} tone-${index % 5}`}>{initials(name || '?')}</span>; }
export function Badge({ value, children }: { value: string; children?: ReactNode }) { return <span className={`badge badge-${value.toLowerCase()}`}><span className="status-dot" />{children || label(value)}</span>; }
export function Field({ label: text, hint, children, className = '' }: { label: string; hint?: string; children: ReactNode; className?: string }) { return <label className={`field ${className}`}><span>{text}</span>{children}{hint && <small>{hint}</small>}</label>; }
export function ErrorMessage({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  return <div className="error-message" role="alert"><AlertCircle size={18} aria-hidden="true" /><div>{error instanceof Error ? error.message : String(error)}{retry && <button onClick={retry} className="text-link">Try again</button>}</div></div>;
}
export function Empty({ title, detail, action, icon }: { title: string; detail?: string; action?: ReactNode; icon?: ReactNode }) { return <div className="empty"><span className="empty-icon">{icon || <Inbox size={25} />}</span><h3>{title}</h3>{detail && <p>{detail}</p>}{action}</div>; }
export function Loading({ rows = 4 }: { rows?: number }) { return <div className="skeleton-list" role="status" aria-label="Loading information">{Array.from({ length: rows }, (_, index) => <span className="skeleton" key={index} />)}</div>; }
export function PageHeader({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description?: string; action?: ReactNode }) { return <header className="page-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1>{description && <p>{description}</p>}</div>{action && <div className="page-actions">{action}</div>}</header>; }
export function CardHeader({ title, detail, action }: { title: string; detail?: string; action?: ReactNode }) { return <div className="card-heading"><div><h2>{title}</h2>{detail && <p>{detail}</p>}</div>{action}</div>; }
export function Sheet({ open, onClose, title, subtitle, children, footer, wide = false, busy = false, centered = false }: { open: boolean; onClose: () => void; title: string; subtitle?: string; children: ReactNode; footer?: ReactNode; wide?: boolean; busy?: boolean; centered?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null); const titleId = useId();
  useEffect(() => { const node = dialog.current; if (!node) return; if (open && !node.open) node.showModal(); if (!open && node.open) node.close(); }, [open]);
  return <dialog ref={dialog} className={`sheet ${wide ? 'wide' : ''} ${centered ? 'centered' : ''}`} aria-labelledby={titleId} onKeyDown={event => {
    if (event.key !== 'Tab') return;
    const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]')].filter(node => node.getClientRects().length);
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }} onClick={event => { if (event.target === dialog.current && !busy) onClose(); }}>
    <div className="sheet-content"><header className="sheet-heading"><div><p className="eyebrow">ALDER & CO.</p><h2 id={titleId}>{title}</h2>{subtitle && <p>{subtitle}</p>}</div><button type="button" className="icon-button" aria-label="Close dialog" disabled={busy} onClick={onClose}><X size={21} /></button></header><div className="sheet-body">{children}</div>{footer && <footer className="sheet-footer">{footer}</footer>}</div>
  </dialog>;
}
export function Pagination({ page, totalPages, total, onChange }: { page: number; totalPages: number; total: number; onChange: (page: number) => void }) { return <div className="pagination"><span>{total} {total === 1 ? 'result' : 'results'}<span className="hide-mobile"> · Page {page + 1} of {Math.max(1, totalPages)}</span></span><div><button className="icon-button" aria-label="Previous page" disabled={!page} onClick={() => onChange(page - 1)}><ChevronLeft size={18} /></button><button className="icon-button" aria-label="Next page" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}><ChevronRight size={18} /></button></div></div>; }
export function TextAction({ children, onClick }: { children: ReactNode; onClick: () => void }) { return <button className="text-action" onClick={onClick}>{children}<ArrowRight size={15} /></button>; }
export function Stat({ label: text, value, note, icon, tone = 'sage' }: { label: string; value: ReactNode; note?: string; icon: ReactNode; tone?: string }) { return <section className={`stat-card ${tone}`}><div className="stat-top"><span>{text}</span><span className="stat-icon">{icon}</span></div><strong>{value}</strong>{note && <small>{note}</small>}</section>; }

const ToastContext = createContext<(message: string, type?: 'success' | 'error') => void>(() => {});
export const useToast = () => useContext(ToastContext);
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string; type: string }[]>([]);
  const show = (message: string, type = 'success') => { const id = Date.now() + Math.random(); setToasts(old => [...old.slice(-2), { id, message, type }]); window.setTimeout(() => setToasts(old => old.filter(item => item.id !== id)), 6500); };
  return <ToastContext.Provider value={show}>{children}<div className="toast-stack" aria-live="polite">{toasts.map(item => <div className={`toast ${item.type}`} key={item.id}>{item.type === 'error' ? <AlertCircle size={20} /> : <CheckCircle2 size={20} />}<span>{item.message}</span><button className="icon-button" aria-label="Dismiss notification" onClick={() => setToasts(old => old.filter(value => value.id !== item.id))}><X size={17} /></button></div>)}</div></ToastContext.Provider>;
}
export function Success({ title, children }: { title: string; children?: ReactNode }) { return <div className="success-state"><span><Check size={30} /></span><h3>{title}</h3>{children}</div>; }
