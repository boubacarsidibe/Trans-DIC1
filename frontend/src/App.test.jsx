import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import App from "./App.jsx";

describe("application shell", () => {
  it("renders the Trans-DIC1 monitoring dashboard", () => {
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
      </MemoryRouter>,
    );

    expect(
      screen.getByRole("heading", { name: "Trans-DIC1" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Socle opérationnel")).toBeInTheDocument();
  });
});
