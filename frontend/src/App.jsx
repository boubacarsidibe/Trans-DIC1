import { Navigate, Route, Routes } from 'react-router-dom'
import ProtectedRoute from './auth/ProtectedRoute.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import LoginPage from './pages/LoginPage.jsx'

function App() {
  return (
    <Routes>
      <Route path="/connexion" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/tableau-de-bord" element={<DashboardPage />} />
      </Route>
      <Route path="/" element={<Navigate to="/tableau-de-bord" replace />} />
      <Route path="*" element={<Navigate to="/tableau-de-bord" replace />} />
    </Routes>
  )
}

export default App
