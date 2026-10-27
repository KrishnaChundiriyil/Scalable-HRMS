import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, CheckCircle2 } from 'lucide-react';
import { useAuth } from '../lib/auth';
import { Button, Sheet } from './ui';

interface Approval { id: string; recipientId: string; leaveId: string; message: string; }
export function ApprovalPopup() {
  const { session } = useAuth(); const navigate = useNavigate();
  const [queue, setQueue] = useState<Approval[]>([]);
  useEffect(() => {
    setQueue([]);
    const receive = (event: Event) => {
      const approval = (event as CustomEvent<Approval>).detail;
      if (approval?.recipientId !== session?.employee.employeeId) return;
      setQueue(old => old.some(item => item.id === approval.id) ? old : [...old, approval]);
    };
    window.addEventListener('alder-leave-approved', receive);
    return () => window.removeEventListener('alder-leave-approved', receive);
  }, [session?.employee.employeeId]);
  const current = queue[0]; const close = () => setQueue(old => old.slice(1));
  return <Sheet centered open={!!current} onClose={close} title="Your leave is approved" subtitle="A live update from your workspace." footer={<><Button variant="secondary" onClick={() => { if (current) navigate('/leave?detail=' + encodeURIComponent(current.leaveId)); close(); }}>View request<ArrowRight size={16} /></Button><Button onClick={close}>Got it</Button></>}><div className="approval-message"><span><CheckCircle2 size={38} strokeWidth={1.5} /></span><h3>You’re all set.</h3><p>Your leave request has been approved and your balance has been updated.</p><small>This update is saved in your Inbox, so you can return to it later.</small></div></Sheet>;
}
