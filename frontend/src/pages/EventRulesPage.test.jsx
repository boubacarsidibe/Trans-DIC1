import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { toEventRulePayload, validateEventRule } from "../api/events.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const rule = {
  id: "r1",
  name: "Interface coupée (linkDown)",
  source: "SNMP_TRAP",
  minSeverity: null,
  oidPrefix: "1.3.6.1.6.3.1.1.5.3",
  messageRegex: null,
  resolveOid: "1.3.6.1.6.3.1.1.5.4",
  resolveRegex: null,
  alertSeverity: "CRITICAL",
  dedupWindowSeconds: 300,
  active: false,
  siteIds: [],
  equipmentIds: [],
};

const pageOf = (content, totalPages = 1) => ({
  data: { content, page: { totalPages, number: 0, totalElements: content.length, size: 20 } },
});

function mockGets(rules = [rule]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/regles-evenements") return pageOf(rules);
    if (url === "/sites") return pageOf([{ id: "s1", name: "Campus" }]);
    if (url === "/equipements") return pageOf([{ id: "e1", name: "Routeur cœur" }]);
    return { data: [] };
  });
}

function renderPage(role) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/regles-evenements"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

const base = {
  name: "x",
  source: "",
  minSeverity: "",
  oidPrefix: "",
  messageRegex: "",
  resolveOid: "",
  resolveRegex: "",
  alertSeverity: "WARNING",
  dedupWindowSeconds: "300",
  active: true,
  siteIds: [],
  equipmentIds: [],
};

describe("validation d'une règle d'événement", () => {
  it("exige au moins un critère d'ouverture", () => {
    expect(validateEventRule(base).minSeverity).toMatch(/au moins un critère/);
    expect(validateEventRule({ ...base, minSeverity: "ERROR" })).toEqual({});
  });

  it("refuse un OID invalide, un OID sur une règle syslog et une fenêtre hors bornes", () => {
    expect(validateEventRule({ ...base, oidPrefix: "1.3.x" }).oidPrefix).toMatch(/OID invalide/);
    expect(validateEventRule({ ...base, source: "SYSLOG", oidPrefix: "1.3.6" }).oidPrefix).toMatch(
      /syslog/,
    );
    expect(
      validateEventRule({ ...base, minSeverity: "ERROR", dedupWindowSeconds: "-1" }).dedupWindowSeconds,
    ).toMatch(/86 400/);
  });

  it("convertit les champs vides en null", () => {
    expect(toEventRulePayload({ ...base, minSeverity: "ERROR" })).toEqual(
      expect.objectContaining({ source: null, oidPrefix: null, messageRegex: null, dedupWindowSeconds: 300 }),
    );
  });
});

describe("page des règles d'événements", () => {
  it("affiche les règles en lecture seule pour un lecteur", async () => {
    mockGets();
    renderPage("VIEWER");

    expect(await screen.findByText("Interface coupée (linkDown)")).toBeInTheDocument();
    expect(screen.getByText(/OID 1.3.6.1.6.3.1.1.5.4/)).toBeInTheDocument();
    expect(screen.getByText("Désactivée")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Ajouter une règle d'événement" }),
    ).not.toBeInTheDocument();
  });

  it("crée une règle syslog avec une expression", async () => {
    mockGets([]);
    apiClient.post.mockResolvedValue({ data: rule });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle d'événement" }));
    await userEvent.type(await screen.findByLabelText("Nom"), "Disque plein");
    await userEvent.selectOptions(screen.getByLabelText("Source"), "SYSLOG");
    await userEvent.type(
      screen.getByLabelText("Expression sur le texte (insensible à la casse)"),
      "disk full",
    );
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/regles-evenements",
        expect.objectContaining({ name: "Disque plein", source: "SYSLOG", messageRegex: "disk full" }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Règle d'événement enregistrée.");
  });

  it("affiche le refus du serveur pour une expression dangereuse", async () => {
    mockGets([]);
    apiClient.post.mockRejectedValue({
      response: { status: 400, data: { error: "Expression régulière trop coûteuse" } },
    });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle d'événement" }));
    await userEvent.type(await screen.findByLabelText("Nom"), "Lente");
    await userEvent.type(screen.getByLabelText("Expression sur le texte (insensible à la casse)"), "a+");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("trop coûteuse");
  });

  it("teste la règle sur un exemple sans l'enregistrer", async () => {
    mockGets();
    apiClient.post.mockResolvedValue({
      data: { opens: true, resolves: false, aborted: false, explanation: "Cet événement ouvrirait une alerte" },
    });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Modifier Interface coupée (linkDown)" }));
    await userEvent.type(await screen.findByLabelText("OID du trap (exemple)"), "1.3.6.1.6.3.1.1.5.3");
    await userEvent.click(screen.getByRole("button", { name: "Tester" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/regles-evenements/tester",
        expect.objectContaining({
          source: "SNMP_TRAP",
          severity: "ERROR",
          trapOid: "1.3.6.1.6.3.1.1.5.3",
          rule: expect.objectContaining({ oidPrefix: "1.3.6.1.6.3.1.1.5.3" }),
        }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("ouvrirait une alerte");
    expect(apiClient.put).not.toHaveBeenCalled();
  });

  it("demande confirmation avant de supprimer et explique un refus", async () => {
    mockGets();
    apiClient.delete.mockRejectedValue({ response: { status: 409 } });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Supprimer Interface coupée (linkDown)" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirmer la suppression" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/regles-evenements/r1"));
    expect(await screen.findByRole("alert")).toHaveTextContent("désactivez-la");
  });
});
