import { Navigate, Route, Routes } from "react-router-dom";
import AppLayout from "./auth/AppLayout.jsx";
import ProtectedRoute from "./auth/ProtectedRoute.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import MaintenancesPage from "./pages/MaintenancesPage.jsx";
import NotificationsPage from "./pages/NotificationsPage.jsx";
import RulesPage from "./pages/RulesPage.jsx";
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
          <Route path="/maintenances" element={<MaintenancesPage />} />
          <Route path="/regles" element={<RulesPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/tableau-de-bord" replace />} />
      <Route path="*" element={<Navigate to="/tableau-de-bord" replace />} />
    </Routes>
  );
}

export default App;
