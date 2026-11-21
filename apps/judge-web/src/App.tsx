import { Navigate, Route, Routes } from 'react-router-dom'
import { useSession } from './api/queries'
import { ApiError } from './api/client'
import { Shell } from './components/Shell'
import { Loading, Failure } from './components/Feedback'
import { Login } from './pages/Login'
import { Assignments } from './pages/Assignments'
import { AssignmentDetail } from './pages/AssignmentDetail'
import { Profile } from './pages/Profile'
import { Submissions } from './pages/Submissions'
import { SubmissionDetail } from './pages/SubmissionDetail'
import { AssignmentEditor } from './pages/AssignmentEditor'
import { AdminUsers, AdminUserDetail } from './pages/AdminUsers'
import { Leaderboard } from './pages/Leaderboard'

function Protected() {
  const session = useSession()
  if (session.isPending) return <Loading session />
  if (session.isError) return <Failure error={session.error} retry={() => void session.refetch()} />
  if (!session.data) return <Navigate to="/login?session=expired" replace />
  return <Shell user={session.data} />
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Protected />}>
        <Route path="/" element={<Navigate to="/assignments" replace />} />
        <Route path="/assignments" element={<Assignments />} />
        <Route path="/assignments/new" element={<AssignmentEditor />} />
        <Route path="/assignments/:id/manage" element={<AssignmentEditor />} />
        <Route path="/admin/users" element={<AdminUsers />} />
        <Route path="/admin/users/:id" element={<AdminUserDetail />} />
        <Route path="/assignments/:id" element={<AssignmentDetail />} />
        <Route path="/assignments/:id/submissions" element={<Submissions />} />
        <Route path="/assignments/:id/leaderboard" element={<Leaderboard />} />
        <Route path="/submissions" element={<Submissions />} />
        <Route path="/submissions/:id" element={<SubmissionDetail />} />
        <Route path="/profile" element={<Profile />} />
        <Route path="*" element={<Failure error={new ApiError(404)} />} />
      </Route>
    </Routes>
  )
}
