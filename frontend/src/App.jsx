import { Navigate, Route, Routes } from "react-router-dom";
import AppLayout from "./auth/AppLayout.jsx";
import ProtectedRoute from "./auth/ProtectedRoute.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import MaintenancesPage from "./pages/MaintenancesPage.jsx";
import NotificationsPage from "./pages/NotificationsPage.jsx";
import ReportsPage from "./pages/ReportsPage.jsx";
import RulesPage from "./pages/RulesPage.jsx";
import MetricsPage from "./pages/MetricsPage.jsx";
import SitesPage from "./pages/SitesPage.jsx";
import UsersPage from "./pages/UsersPage.jsx";
import AlertsPage from "./pages/AlertsPage.jsx";
import CollectionPage from "./pages/CollectionPage.jsx";
import EquipmentPage from "./pages/EquipmentPage.jsx";
import AuditPage from "./pages/AuditPage.jsx";
import ChannelsPage from "./pages/ChannelsPage.jsx";

function App() {
  return (
    <Routes>
      <Route path="/connexion" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/tableau-de-bord" element={<DashboardPage />} />
          <Route path="/equipements" element={<EquipmentPage />} />
          <Route path="/equipements/:id/historique" element={<MetricsPage />} />
          <Route path="/sites" element={<SitesPage />} />
          <Route path="/alertes" element={<AlertsPage />} />
          <Route path="/maintenances" element={<MaintenancesPage />} />
          <Route path="/rapports" element={<ReportsPage />} />
          <Route path="/regles" element={<RulesPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route element={<ProtectedRoute roles={["ADMIN"]} />}>
            <Route path="/utilisateurs" element={<UsersPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/canaux" element={<ChannelsPage />} />
            <Route path="/equipements/:id/collecte" element={<CollectionPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/tableau-de-bord" replace />} />
      <Route path="*" element={<Navigate to="/tableau-de-bord" replace />} />
    </Routes>
  );
}

export default App;
