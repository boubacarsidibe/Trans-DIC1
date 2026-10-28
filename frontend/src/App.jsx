import { Navigate, Route, Routes } from "react-router-dom";
import AppLayout from "./auth/AppLayout.jsx";
import ProtectedRoute from "./auth/ProtectedRoute.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import AlertsPage from "./pages/AlertsPage.jsx";
import EquipmentPage from "./pages/EquipmentPage.jsx";

function App() {
  return (
    <Routes>
      <Route path="/connexion" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/tableau-de-bord" element={<DashboardPage />} />
          <Route path="/equipements" element={<EquipmentPage />} />
          <Route path="/alertes" element={<AlertsPage />} />
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/tableau-de-bord" replace />} />
      <Route path="*" element={<Navigate to="/tableau-de-bord" replace />} />
    </Routes>
  );
}

export default App;
