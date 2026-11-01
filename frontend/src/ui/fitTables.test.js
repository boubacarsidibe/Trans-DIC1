import { afterEach, describe, expect, it } from "vitest";
import { fitTable, labelCells } from "./fitTables.js";

function mount() {
  document.body.innerHTML = `
    <div id="cadre">
      <table class="data-table">
        <thead><tr>
          <th>Équipement</th><th>État</th><th><span class="sr-only">Actions</span></th>
        </tr></thead>
        <tbody>
          <tr><td>Routeur</td><td>En service</td><td><button>Historique</button></td></tr>
          <tr><td colspan="3">Aucun équipement.</td></tr>
        </tbody>
      </table>
    </div>`;
  return document.querySelector("table");
}

function sizes(table, tableWidth, frameWidth) {
  Object.defineProperty(table, "scrollWidth", { configurable: true, get: () => tableWidth });
  Object.defineProperty(table.parentElement, "clientWidth", { configurable: true, get: () => frameWidth });
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("tableaux sans défilement horizontal", () => {
  it("recopie le titre de chaque colonne sur ses cellules et garde les rôles du tableau", () => {
    const table = mount();
    labelCells(table);

    const [name, state, actions] = table.tBodies[0].rows[0].cells;
    expect(name.dataset.label).toBe("Équipement");
    expect(state.dataset.label).toBe("État");
    // Colonne d'actions au titre masqué et ligne vide sur toute la largeur : pas de libellé.
    expect(actions.dataset.label).toBeUndefined();
    expect(table.tBodies[0].rows[1].cells[0].dataset.label).toBeUndefined();
    expect(table.getAttribute("role")).toBe("table");
    expect(name.getAttribute("role")).toBe("cell");
  });

  it("passe en fiches seulement quand le tableau déborde de son cadre", () => {
    const table = mount();

    sizes(table, 1400, 900);
    fitTable(table);
    expect(table.classList.contains("is-stacked")).toBe(true);

    sizes(table, 800, 900);
    fitTable(table);
    expect(table.classList.contains("is-stacked")).toBe(false);
  });
});
