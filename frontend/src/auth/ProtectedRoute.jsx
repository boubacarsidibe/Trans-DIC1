import { Navigate, Outlet } from 'react-router-dom'

function ProtectedRoute() {
  const authenticationRequired = import.meta.env.VITE_AUTH_REQUIRED === 'true'
  const token = globalThis.localStorage?.getItem('trans-dic1.access-token')

  if (authenticationRequired && !token) {
    return <Navigate to="/connexion" replace />
  }

  return <Outlet />
}

export default ProtectedRoute
