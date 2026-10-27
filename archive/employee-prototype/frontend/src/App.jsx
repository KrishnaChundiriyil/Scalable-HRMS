import React, { useEffect, useState } from 'react'

const API = 'http://localhost:8080/api'
async function call(path, token, options = {}) {
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers || {}) }
  })
  const body = response.status === 204 ? null : await response.json().catch(() => null)
  if (!response.ok) throw new Error(body?.error || `Request failed (${response.status})`)
  return body
}
const blankEmployee = { fullName: '', department: 'Engineering', jobTitle: '', email: '', phone: '', location: '' }

export default function App() {
  const [token, setToken] = useState('')
  const [account, setAccount] = useState(null)
  const [mode, setMode] = useState('login')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [authForm, setAuthForm] = useState({ email: '', password: '', fullName: '', department: 'Engineering', jobTitle: 'Software Engineer' })
  const [employees, setEmployees] = useState([])
  const [search, setSearch] = useState('')
  const [suggestions, setSuggestions] = useState([])
  const [form, setForm] = useState(blankEmployee)
  const [editingId, setEditingId] = useState('')
  const [myProfile, setMyProfile] = useState(null)
  const [showProfile, setShowProfile] = useState(false)
  const [roleEmail, setRoleEmail] = useState('')
  const [roleValue, setRoleValue] = useState('HR')

  async function refresh(q = search) {
    if (!token) return
    try { setEmployees(await call(`/employees${q ? `?q=${encodeURIComponent(q)}` : ''}`, token)) }
    catch (e) { setMessage(e.message) }
  }
  async function loadProfile() {
    if (!account?.employeeId) return
    try { setMyProfile(await call(`/employees/${account.employeeId}`, token)); setShowProfile(true) }
    catch (e) { setMessage(e.message) }
  }
  useEffect(() => {
    if (token) {
      call('/auth/me', token).then(setAccount).catch(e => setMessage(e.message))
      refresh('')
    }
  }, [token])
  useEffect(() => {
    if (!token || search.trim().length < 1) { setSuggestions([]); return }
    const timer = setTimeout(() => call(`/employees/suggest?q=${encodeURIComponent(search)}`, token)
      .then(setSuggestions).catch(() => setSuggestions([])), 180)
    return () => clearTimeout(timer)
  }, [search, token])

  async function submitAuth(e) {
    e.preventDefault(); setBusy(true); setMessage('')
    try {
      const result = await call(`/auth/${mode === 'login' ? 'login' : 'register'}`, '', {
        method: 'POST', body: JSON.stringify(mode === 'login'
          ? { email: authForm.email, password: authForm.password }
          : authForm)
      })
      setToken(result.token); setAccount({ email: result.email, employeeId: result.employeeId, role: result.role })
      setMessage(`Welcome! Your role is ${result.role}; employee ID: ${result.employeeId}`)
    } catch (e) { setMessage(e.message) } finally { setBusy(false) }
  }
  async function saveEmployee(e) {
    e.preventDefault(); setMessage('')
    try {
      const saved = await call(editingId ? `/employees/${editingId}` : '/employees', token, {
        method: editingId ? 'PUT' : 'POST', body: JSON.stringify(form)
      })
      setMessage(`${editingId ? 'Updated' : 'Created'} ${saved.fullName} (${saved.employeeId})`)
      setForm(blankEmployee); setEditingId(''); await refresh('')
      if (saved.employeeId === account?.employeeId) setMyProfile(saved)
    } catch (e) { setMessage(e.message) }
  }
  function startEdit(employee) {
    setEditingId(employee.employeeId)
    setForm({ fullName: employee.fullName || '', department: employee.department || '', jobTitle: employee.jobTitle || '', email: employee.email || '', phone: employee.phone || '', location: employee.location || '' })
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
  }
  async function removeEmployee(employee) {
    if (!window.confirm(`Delete ${employee.fullName} (${employee.employeeId})?`)) return
    try { await call(`/employees/${employee.employeeId}`, token, { method: 'DELETE' }); setMessage(`Deleted ${employee.employeeId}`); await refresh('') }
    catch (e) { setMessage(e.message) }
  }
  async function updateRole(e) {
    e.preventDefault()
    try {
      const result = await call(`/admin/users/${encodeURIComponent(roleEmail)}/role`, token, { method: 'PUT', body: JSON.stringify({ role: roleValue }) })
      setMessage(`Role updated: ${result.email} → ${result.role}`)
    } catch (e) { setMessage(e.message) }
  }
  function logout() { setToken(''); setAccount(null); setEmployees([]); setSuggestions([]); setMyProfile(null); setShowProfile(false); setMessage('Logged out') }
  const canManage = account?.role === 'ADMIN' || account?.role === 'HR'

  if (!token) return <main className="auth-page"><section className="auth-card">
    <div className="eyebrow">SCALABLE HRMS · PERSON 1</div><h1>Employee & Identity</h1>
    <p className="muted">Secure employee accounts, profiles, directory search and role management.</p>
    <div className="tabs"><button className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>Login</button><button className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>Register</button></div>
    <form onSubmit={submitAuth} className="stack">
      {mode === 'register' && <><label>Full name<input required value={authForm.fullName} onChange={e => setAuthForm({ ...authForm, fullName: e.target.value })} /></label><div className="two"><label>Department<input required value={authForm.department} onChange={e => setAuthForm({ ...authForm, department: e.target.value })} /></label><label>Job title<input required value={authForm.jobTitle} onChange={e => setAuthForm({ ...authForm, jobTitle: e.target.value })} /></label></div></>}
      <label>Email<input type="email" required value={authForm.email} onChange={e => setAuthForm({ ...authForm, email: e.target.value })} /></label>
      <label>Password<input type="password" minLength="8" required value={authForm.password} onChange={e => setAuthForm({ ...authForm, password: e.target.value })} /><small>At least 8 characters</small></label>
      <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Log in' : 'Create account'}</button>
    </form>
    {message && <p className="message">{message}</p>}
    <p className="note">The first registered account becomes ADMIN; later public registrations become EMPLOYEE. An ADMIN can promote an account to HR.</p>
  </section></main>

  return <main className="app-page">
    <header className="topbar"><div><div className="eyebrow">SCALABLE HRMS</div><h1>Employee Directory</h1><p className="muted">Identity, profiles, search and access control</p></div><div className="account"><div><strong>{account?.email}</strong><span>{account?.role} · {account?.employeeId}</span></div><button onClick={loadProfile}>My profile</button><button onClick={logout}>Log out</button></div></header>
    {message && <div className="banner" role="status">{message}<button onClick={() => setMessage('')}>×</button></div>}
    <section className="stats"><div><span>Employees shown</span><strong>{employees.length}</strong></div><div><span>Signed-in role</span><strong>{account?.role}</strong></div><div><span>Your employee ID</span><strong>{account?.employeeId}</strong></div></section>

    {showProfile && myProfile && <section className="panel"><div className="panel-heading"><div><h2>My profile</h2><p>View and edit your employee information.</p></div><button onClick={() => setShowProfile(false)}>Close</button></div><div className="profile-grid"><div><span>Employee ID</span><strong>{myProfile.employeeId}</strong></div><div><span>Full name</span><strong>{myProfile.fullName}</strong></div><div><span>Department</span><strong>{myProfile.department}</strong></div><div><span>Job title</span><strong>{myProfile.jobTitle}</strong></div><div><span>Email</span><strong>{myProfile.email}</strong></div><div><span>Phone</span><strong>{myProfile.phone || '—'}</strong></div><div><span>Location</span><strong>{myProfile.location || '—'}</strong></div></div><button className="primary" onClick={() => startEdit(myProfile)}>Edit my profile</button></section>}

    <section className="panel"><div className="panel-heading"><div><h2>Employee directory</h2><p>Search by employee name, department or job title. Suggestions are generated by a Trie and cached in Redis.</p></div></div>
      <div className="search-wrap"><input placeholder="Search name, department or job title…" value={search} onChange={e => setSearch(e.target.value)} onKeyDown={e => e.key === 'Enter' && refresh(search)} /><button onClick={() => refresh(search)}>Search</button><button onClick={() => { setSearch(''); refresh('') }}>Clear</button>
      {suggestions.length > 0 && <div className="suggestions">{suggestions.map(s => <button key={s} onClick={() => { setSearch(s); refresh(s); setSuggestions([]) }}>{s}</button>)}</div>}</div>
      <div className="table-wrap"><table><thead><tr><th>Employee ID</th><th>Name</th><th>Department</th><th>Job title</th><th>Email</th><th>Phone</th><th>Location</th><th>Actions</th></tr></thead><tbody>{employees.map(e => <tr key={e.employeeId}><td><code>{e.employeeId}</code></td><td>{e.fullName}</td><td>{e.department}</td><td>{e.jobTitle}</td><td>{e.email}</td><td>{e.phone || '—'}</td><td>{e.location || '—'}</td><td className="actions"><button onClick={() => { setMyProfile(e); setShowProfile(true); window.scrollTo({ top: 0, behavior: 'smooth' }) }}>View</button>{(canManage || e.employeeId === account?.employeeId) && <button onClick={() => startEdit(e)}>Edit</button>}{canManage && <button className="danger" onClick={() => removeEmployee(e)}>Delete</button>}</td></tr>)}</tbody></table>{employees.length === 0 && <p className="empty">No employees found. Create one or change your search.</p>}</div>
    </section>

    {(canManage || editingId) && <section className="panel"><div className="panel-heading"><div><h2>{editingId ? `Edit ${editingId}` : 'Add employee'}</h2><p>Employee IDs are generated using Redis atomic counters.</p></div>{editingId && <button onClick={() => { setEditingId(''); setForm(blankEmployee) }}>Cancel edit</button>}</div>
      <form className="employee-form" onSubmit={saveEmployee}>
        <label>Full name<input required value={form.fullName} onChange={e => setForm({ ...form, fullName: e.target.value })} /></label><label>Department<input required value={form.department} onChange={e => setForm({ ...form, department: e.target.value })} /></label><label>Job title<input required value={form.jobTitle} onChange={e => setForm({ ...form, jobTitle: e.target.value })} /></label><label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label><label>Phone<input value={form.phone} onChange={e => setForm({ ...form, phone: e.target.value })} /></label><label>Location<input value={form.location} onChange={e => setForm({ ...form, location: e.target.value })} /></label><button className="primary">{editingId ? 'Save changes' : 'Create employee'}</button>
      </form>
    </section>}

    {account?.role === 'ADMIN' && <section className="panel"><div className="panel-heading"><div><h2>Role management</h2><p>Promote registered accounts to HR or change account roles. ADMIN only.</p></div></div><form className="role-form" onSubmit={updateRole}><label>Registered account email<input required type="email" placeholder="employee@example.com" value={roleEmail} onChange={e => setRoleEmail(e.target.value)} /></label><label>Assign role<select value={roleValue} onChange={e => setRoleValue(e.target.value)}><option value="HR">HR</option><option value="EMPLOYEE">EMPLOYEE</option><option value="ADMIN">ADMIN</option></select></label><button className="primary">Update role</button></form></section>}
    <footer>Employee & Identity module · Spring Boot · MongoDB · Redis counters/cache/session metadata · JWT · RBAC · Trie autocomplete</footer>
  </main>
}
