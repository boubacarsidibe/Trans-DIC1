import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import Modal from "./Modal.jsx";

function Page({ onClose = () => {} }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Ajouter
      </button>
      {open && (
        <Modal
          title="Ajouter un compte"
          description="Le compte changera son mot de passe."
          error="Nom déjà pris"
          onClose={() => {
            onClose();
            setOpen(false);
          }}
        >
          <form>
            <label>
              Nom
              <input />
            </label>
            <button type="submit">Créer</button>
          </form>
        </Modal>
      )}
    </>
  );
}

describe("Modal", () => {
  it("s'ouvre par-dessus la page, nommée, avec le focus dans le premier champ et l'erreur visible", async () => {
    const user = userEvent.setup();
    render(<Page />);
    await user.click(screen.getByRole("button", { name: "Ajouter" }));

    const dialog = screen.getByRole("dialog", { name: "Ajouter un compte" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("Le compte changera son mot de passe.");
    expect(screen.getByLabelText("Nom")).toHaveFocus();
    expect(screen.getByRole("alert")).toHaveTextContent("Nom déjà pris");
  });

  it("garde le focus dans la fenêtre : Tab et Maj+Tab bouclent", async () => {
    const user = userEvent.setup();
    render(<Page />);
    await user.click(screen.getByRole("button", { name: "Ajouter" }));

    const close = screen.getByRole("button", { name: "Fermer la fenêtre" });
    const submit = screen.getByRole("button", { name: "Créer" });
    submit.focus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(submit).toHaveFocus();
  });

  it("Échap ou la croix ferment et rendent le focus au bouton d'ouverture", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Page onClose={onClose} />);
    const opener = screen.getByRole("button", { name: "Ajouter" });

    await user.click(opener);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();

    await user.click(opener);
    await user.click(screen.getByRole("button", { name: "Fermer la fenêtre" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("sans titre, prend le nom donné et laisse son en-tête au panneau affiché", () => {
    render(
      <Modal label="Proxy de collecte du site Nord" onClose={() => {}}>
        <section>
          <h2>Proxy de collecte</h2>
          <button type="button">Fermer</button>
        </section>
      </Modal>,
    );
    expect(screen.getByRole("dialog", { name: "Proxy de collecte du site Nord" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Fermer la fenêtre" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Fermer" })).toHaveFocus();
  });
});
