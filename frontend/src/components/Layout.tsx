import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Bell, BriefcaseBusiness, CalendarDays, ChevronDown, CircleHelp, Grid2X2, FileText, Leaf, LogOut, Menu, Search, Settings2, Users, Wallet, X } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth, useRoles } from '../lib/auth';
import { useLiveNotifications } from '../lib/hooks';
import { Avatar, Brand } from './ui';

export function Layout() {
  const { session, logout } = useAuth(); const { hr, admin, candidate } = useRoles(); const [open, setOpen] = useState(false); const [search, setSearch] = useState('');
  const navigate = useNavigate(); const location = useLocation(); useLiveNotifications();
  const [mobile, setMobile] = useState(() => matchMedia('(max-width:760px)').matches);
  const sidebar = useRef<HTMLElement>(null); const menu = useRef<HTMLButtonElement>(null);
  useEffect(() => { const media = matchMedia('(max-width:760px)'); const update = () => { setMobile(media.matches); setOpen(false); }; media.addEventListener('change', update); return () => media.removeEventListener('change', update); }, []);
  useEffect(() => {
    if (!open || !mobile) return;
    const previous = document.body.style.overflow; document.body.style.overflow = 'hidden';
    const elements = sidebar.current?.querySelectorAll<HTMLElement>('a[href],button:not(:disabled)'); elements?.[0]?.focus();
    const trap = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); menu.current?.focus(); } if (event.key === 'Tab' && elements?.length) { const first = elements[0], last = elements[elements.length - 1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); } } };
    document.addEventListener('keydown', trap); return () => { document.body.style.overflow = previous; document.removeEventListener('keydown', trap); };
  }, [open, mobile]);
  const unread = useQuery({ enabled: !candidate, queryKey: ['notifications', 'unread'], queryFn: () => api<{ unread: number }>('/api/leaves/notifications/unread-count'), refetchInterval: 30000 });
  const links = candidate ? [{ to: '/', title: 'Overview', icon: Grid2X2 }, { to: '/hiring', title: 'Opportunities', icon: BriefcaseBusiness }, { to: '/applications', title: 'My applications', icon: FileText }] : [
    { to: '/', title: 'Overview', icon: Grid2X2 }, { to: '/people', title: 'People', icon: Users },
    { to: '/leave', title: 'Time off', icon: CalendarDays }, { to: '/payroll', title: 'Payroll', icon: Wallet },
    { to: '/hiring', title: hr ? 'Hiring' : 'Opportunities', icon: BriefcaseBusiness }, { to: '/applications', title: 'My applications', icon: FileText }, { to: '/notifications', title: 'Inbox', icon: Bell },
  ];
  const section = [...links, { to: '/account', title: 'My account' }, { to: '/admin', title: 'Administration' }].find(item => item.to === '/' ? location.pathname === '/' : location.pathname.startsWith(item.to))?.title || 'Workspace';
  useEffect(() => { document.title = section + ' · Alder & Co.'; }, [section]);
  return <div className="app-shell"><a className="skip-link" href="#main">Skip to main content</a>
    <aside ref={sidebar} id="workspace-navigation" inert={mobile && !open} aria-hidden={mobile && !open || undefined} className={`sidebar ${open ? 'is-open' : ''}`} aria-label="Workspace navigation"><NavLink to="/" className="brand-link" onClick={() => setOpen(false)}><Brand /></NavLink><div className="workspace-label"><span className="workspace-square">A</span><span>Company workspace<small>Alder & Co.</small></span><ChevronDown size={14} aria-hidden="true" /></div><p className="nav-label">WORKSPACE</p><nav>{links.map(({ to, title, icon: Icon }) => <NavLink to={to} end={to === '/'} key={to} onClick={() => setOpen(false)} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}><Icon size={19} strokeWidth={1.65} /><span>{title}</span>{to === '/notifications' && !!unread.data?.unread && <span className="nav-count">{unread.data.unread}</span>}</NavLink>)}</nav>
      <div className="sidebar-bottom">{!candidate && <div className="quiet-note"><Leaf size={23} strokeWidth={1.4} /><strong>Connected by people.</strong><p>Meet the teams behind our work.</p><NavLink to="/people">Meet your colleagues <span>↗</span></NavLink></div>}{admin && <NavLink className="nav-item" to="/admin" onClick={() => setOpen(false)}><Settings2 size={19} /><span>Administration</span></NavLink>}<NavLink to="/account" className="sidebar-account" onClick={() => setOpen(false)}><Avatar name={session!.employee.name} /><span><strong>{session!.employee.name}</strong><small>{session!.employee.jobTitle || session!.employee.role}</small></span><ChevronDown size={15} /></NavLink></div>
    </aside>{open && <button className="nav-scrim" aria-label="Close navigation" onClick={() => setOpen(false)} />}
    <div className="workspace-main"><header className="topbar"><div className="breadcrumb"><button ref={menu} aria-expanded={open} aria-controls="workspace-navigation" className="icon-button mobile-menu" aria-label={open ? 'Close navigation' : 'Open navigation'} onClick={() => setOpen(!open)}>{open ? <X size={22} /> : <Menu size={22} />}</button><NavLink className="mobile-brand" to="/"><Brand small /></NavLink><span>Workspace</span><span className="breadcrumb-divider">/</span><strong>{section}</strong></div>{!candidate && <form className="global-search" onSubmit={event => { event.preventDefault(); navigate('/people?search=' + encodeURIComponent(search)); setSearch(''); }}><Search size={17} aria-hidden="true" /><input aria-label="Search people" placeholder="Search people…" value={search} onChange={event => setSearch(event.target.value)} /><kbd>↵</kbd></form>}<div className="topbar-actions"><NavLink className="icon-button" aria-label="Workspace help" to="/help"><CircleHelp size={20} strokeWidth={1.6} /></NavLink>{!candidate && <NavLink className="icon-button notification-bell" aria-label={`Inbox${unread.data?.unread ? `, ${unread.data.unread} unread` : ''}`} to="/notifications"><Bell size={20} strokeWidth={1.6} />{!!unread.data?.unread && <span />}</NavLink>}<span className="topbar-divider" /><button className="icon-button signout" aria-label="Sign out" onClick={logout}><LogOut size={19} /></button><NavLink to="/account"><span className="sr-only">My account</span><Avatar name={session!.employee.name} size="small" /></NavLink></div></header><main id="main" className="page-content" key={location.pathname}><Outlet /></main><footer className="app-footer"><span>Alder & Co. <span>·</span> People workspace</span><NavLink to="/help">Workspace help <span>↗</span></NavLink></footer></div>
  </div>;
}
