import { Navigate, Route, Routes } from "react-router-dom";
import ViewsPage from "./pages/ViewsPage.jsx";
import OidcCallbackPage from "./pages/OidcCallbackPage.jsx";
import TopologyPage from "./pages/TopologyPage.jsx";
import WallPage from "./pages/WallPage.jsx";
import EventsPage from "./pages/EventsPage.jsx";
import EventRulesPage from "./pages/EventRulesPage.jsx";
import AppLayout from "./auth/AppLayout.jsx";
import ProtectedRoute from "./auth/ProtectedRoute.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import MaintenancesPage from "./pages/MaintenancesPage.jsx";
import OnCallPage from "./pages/OnCallPage.jsx";
import StatusPagesPage from "./pages/StatusPagesPage.jsx";
import StatusPageViewPage from "./pages/StatusPageViewPage.jsx";
import NotificationsPage from "./pages/NotificationsPage.jsx";
import TokensPage from "./pages/TokensPage.jsx";
import TwoFactorPage from "./pages/TwoFactorPage.jsx";
import ReportSchedulesPage from "./pages/ReportSchedulesPage.jsx";
import ReportsPage from "./pages/ReportsPage.jsx";
import SecurityPage from "./pages/SecurityPage.jsx";
import RulesPage from "./pages/RulesPage.jsx";
import MetricsPage from "./pages/MetricsPage.jsx";
import SitesPage from "./pages/SitesPage.jsx";
import UsersPage from "./pages/UsersPage.jsx";
import AlertsPage from "./pages/AlertsPage.jsx";
import CollectionPage from "./pages/CollectionPage.jsx";
import ProbesPage from "./pages/ProbesPage.jsx";
import EquipmentPage from "./pages/EquipmentPage.jsx";
import TemplatesPage from "./pages/TemplatesPage.jsx";
import AuditPage from "./pages/AuditPage.jsx";
import ChannelsPage from "./pages/ChannelsPage.jsx";
import DiscoveryPage from "./pages/DiscoveryPage.jsx";
import ImportPage from "./pages/ImportPage.jsx";
import ChangePasswordPage from "./pages/ChangePasswordPage.jsx";

function App() {
  return (
    <Routes>
      <Route path="/connexion" element={<LoginPage />} />
      <Route path="/auth/callback" element={<OidcCallbackPage />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/changer-mot-de-passe" element={<ChangePasswordPage />} />
        <Route path="/mur" element={<WallPage />} />
        <Route element={<AppLayout />}>
          <Route path="/tableau-de-bord" element={<DashboardPage />} />
          <Route path="/vues" element={<ViewsPage />} />
          <Route path="/equipements" element={<EquipmentPage />} />
          <Route path="/equipements/:id/historique" element={<MetricsPage />} />
          <Route path="/equipements/:id/sondes" element={<ProbesPage />} />
          <Route path="/modeles" element={<TemplatesPage />} />
          <Route path="/sites" element={<SitesPage />} />
          <Route path="/topologie" element={<TopologyPage />} />
          <Route path="/alertes" element={<AlertsPage />} />
          <Route path="/maintenances" element={<MaintenancesPage />} />
          <Route path="/astreinte" element={<OnCallPage />} />
          <Route path="/pages-statut" element={<StatusPagesPage />} />
          <Route path="/pages-statut/:id" element={<StatusPageViewPage />} />
          <Route path="/rapports" element={<ReportsPage />} />
          <Route path="/regles" element={<RulesPage />} />
          <Route path="/evenements" element={<EventsPage />} />
          <Route path="/securite" element={<SecurityPage />} />
          <Route path="/regles-evenements" element={<EventRulesPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/jetons" element={<TokensPage />} />
          <Route path="/double-authentification" element={<TwoFactorPage />} />
          <Route element={<ProtectedRoute roles={["ADMIN", "NOC_OPERATOR"]} />}>
            <Route path="/import" element={<ImportPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={["ADMIN"]} />}>
            <Route path="/decouverte" element={<DiscoveryPage />} />
            <Route path="/utilisateurs" element={<UsersPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/canaux" element={<ChannelsPage />} />
            <Route path="/rapports-planifies" element={<ReportSchedulesPage />} />
            <Route
              path="/equipements/:id/collecte"
              element={<CollectionPage />}
            />
          </Route>
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/tableau-de-bord" replace />} />
      <Route path="*" element={<Navigate to="/tableau-de-bord" replace />} />
    </Routes>
  );
}

export default App;
