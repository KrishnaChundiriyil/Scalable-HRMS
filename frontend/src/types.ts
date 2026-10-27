export type Role = 'EMPLOYEE' | 'MANAGER' | 'HR' | 'ADMIN';
export interface Employee { employeeId: string; name: string; email: string; department: string; role: Role; jobTitle: string; phone: string; }
export interface Session { token: string; employee: Employee; }
export interface EmployeeSettings { employeeId: string; name: string; department: string; managerId: string | null; active: boolean; }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number; }
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export interface Leave { id: string; employeeId: string; type: string; startDate: string; endDate: string; days: number; paid: boolean; reason: string; status: LeaveStatus; createdAt: string; decidedAt: string | null; reviewedBy: string | null; rejectionReason: string | null; cancellationReason: string | null; eligibilityNote: string | null; session: 'FULL_DAY' | 'MORNING' | 'AFTERNOON'; }
export interface LeavePolicy { yearlyAllowance: number; maxPerRequest: number; paid: boolean; counting: string; eligibilityReviewRequired: boolean; }
export interface BalanceItem { entitled: number | null; used: number; remaining: number | null; maxPerRequest: number; paid: boolean; counting: string; limitScope: string; }
export type Balance = { employeeId: string; year: number } & Record<string, BalanceItem>;
export interface Preview { chargedDays: number; excludedCalendarDays: number; canSubmit: boolean; conflictingRequestIds: string[]; yearlyAllocation: Record<string, { days: number; availableBefore: number | null; availableAfterApproval: number | null; shortfall: number }>; }
export interface Notification { id: string; recipientId: string; leaveId: string; message: string; createdAt: string; readAt: string | null; }
export interface Audit { sequence: number; action: string; actorId: string; occurredAt: string; leaveId: string; details: string; }
export interface Absence { employeeId: string; employeeName: string; startDate: string; endDate: string; session: string; }
export interface Report { from: string; to: string; requestCount: number; pendingRequests: number; paidDays: number; unpaidDays: number; approvedDaysByType: Record<string, number>; monthlyApprovedDaysByType: Record<string, Record<string, number>>; requestsByStatus: Record<string, number>; averageDecisionSeconds: number | null; dayTotalsComplete: boolean; }
export interface Payslip { id: string; employeeId: string; year: number; month: number; basicSalary: number; allowances: number; deductions: number; grossSalary: number; netSalary: number; createdAt: string; }
export interface Job { id: string; title: string; company: string; location: string; description: string; shortCode: string; shortUrl: string; clicks: number; }
export interface Candidate { id: string; name: string; email: string; phone: string; }
export interface Application { id: string; jobId: string; candidateId: string; candidateName: string; status: string; appliedAt: string; resumeFileName: string; storageNode: string; }
export interface StorageNodes { nodes: string[]; virtualNodesPerNode: number; resumesPerNode: Record<string, number>; }
