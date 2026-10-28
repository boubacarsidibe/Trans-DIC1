import axios from "axios";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { refreshSession } from "./refresh.js";
import { clearSession, readSession } from "./session.js";

vi.mock("axios", () => ({ default: { post: vi.fn() } }));

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("refreshSession", () => {
  it("envoie le cookie et garde le nouveau jeton en mémoire", async () => {
    axios.post.mockResolvedValue({
      data: { accessToken: "neuf", role: "NOC_OPERATOR", expiresIn: 900 },
    });

    const session = await refreshSession();

    expect(axios.post).toHaveBeenCalledWith(
      expect.stringMatching(/\/auth\/refresh$/),
      null,
      expect.objectContaining({ withCredentials: true }),
    );
    expect(session.role).toBe("NOC_OPERATOR");
    expect(readSession().accessToken).toBe("neuf");
  });

  it("n'envoie qu'une seule requête pour des appels simultanés", async () => {
    axios.post.mockResolvedValue({
      data: { accessToken: "neuf", role: "ADMIN", expiresIn: 900 },
    });

    await Promise.all([refreshSession(), refreshSession(), refreshSession()]);

    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it("permet un nouvel essai après un échec", async () => {
    axios.post.mockRejectedValueOnce(new Error("401"));
    await expect(refreshSession()).rejects.toThrow("401");
    expect(readSession()).toBeNull();

    axios.post.mockResolvedValue({
      data: { accessToken: "ok", role: "VIEWER", expiresIn: 900 },
    });
    await expect(refreshSession()).resolves.toMatchObject({ role: "VIEWER" });
  });
});
