import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { ArrowUpRight, BriefcaseBusiness, CalendarDays, Clock3, Users, Wallet } from 'lucide-react';
import { api } from '../lib/api';
import { useAuth, useRoles } from '../lib/auth';
import { CardHeader, Loading } from './ui';

interface Activity { id: string; title: string; href: string; source: string; createdAt: string; expiresAt: string; }
const icons = { People: Users, 'Time off': CalendarDays, Payroll: Wallet, Hiring: BriefcaseBusiness };
const endpoints = ['/api/employee-activity/me', '/api/leaves/me/activity', '/api/payroll/me/activity', '/api/recruitment-activity/me'];
function relativeTime(value: string, now: number) {
  const minutes = Math.max(0, Math.floor((now - Date.parse(value)) / 60000));
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} ${hours === 1 ? 'hour' : 'hours'} ago` : 'Yesterday';
}
export function RecentActivity() {
  const { session } = useAuth(); const { candidate } = useRoles();
  const [now, setNow] = useState(Date.now); const [expanded, setExpanded] = useState(false);
  const queries = useQueries({ queries: endpoints.filter((_, index) => !candidate || index === 0 || index === 3).map(endpoint => ({
    queryKey: ['recent-activity', session!.employee.employeeId, endpoint],
    queryFn: () => api<Activity[]>(endpoint), refetchInterval: 30000, refetchOnWindowFocus: true,
  })) });
  const items = queries.flatMap(query => query.data || []).filter(item => Date.parse(item.expiresAt) > now)
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  // Also expire rows while this page stays open; do not wait for MongoDB's TTL sweep.
  const nextExpiry = Math.min(...items.map(item => Date.parse(item.expiresAt)));
  useEffect(() => {
    const timer = setTimeout(() => setNow(Date.now()), Math.max(1, Math.min(60000, nextExpiry - Date.now() + 1)));
    return () => clearTimeout(timer);
  }, [now, nextExpiry]);
  const visible = expanded ? items.slice(0, 50) : items.slice(0, 6);
  return <section className="card recent-activity" aria-label="Your recent activity">
    <CardHeader title="Your recent activity" detail="A little catch-up on your workspace. Only visible to you." action={<span className="activity-retention"><Clock3 size={14} />Last 48 hours</span>} />
    {queries.some(query => query.isPending) && !items.length ? <Loading rows={2} /> : visible.length ? <ul className="activity-list">{visible.map(item => {
      const Icon = icons[item.source as keyof typeof icons] || Clock3;
      return <li key={item.source + ':' + item.id}><Link to={item.href}><span className="activity-icon"><Icon size={18} strokeWidth={1.65} /></span><span className="activity-copy"><strong>{item.title}</strong><small>{item.source}<span aria-hidden="true"> · </span><time dateTime={item.createdAt} title={new Date(item.createdAt).toLocaleString()}>{relativeTime(item.createdAt, now)}</time></small></span><ArrowUpRight size={17} aria-hidden="true" /></Link></li>;
    })}</ul> : <div className="activity-empty"><Clock3 size={22} /><p>You’re all caught up.</p><span>Your next profile update, request or application will appear here.</span></div>}
    {queries.some(query => query.isError) && <p className="activity-note" role="status">Some updates could not be loaded. We’ll try again shortly.</p>}
    <div className="activity-footer"><p>Updates stay here for 48 hours. Your records and Inbox messages remain in their usual sections.</p>{items.length > 6 && <button className="text-action" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? 'Show less' : 'Show more'}</button>}</div>
  </section>;
}
