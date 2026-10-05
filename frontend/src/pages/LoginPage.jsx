function LoginPage() {
  return (
    <main className="app-shell grid min-h-screen place-items-center px-6">
      <section className="status-card w-full max-w-md rounded-2xl p-8">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-teal-300">Trans-DIC1</p>
        <h1 className="mt-3 text-3xl font-semibold text-white">Connexion</h1>
        <p className="mt-2 text-sm text-slate-400">L'authentification sera activée dans la user story US04.</p>
        <button className="mt-8 w-full rounded-lg bg-teal-400 px-4 py-3 font-semibold text-slate-950" type="button" disabled>
          Bientôt disponible
        </button>
      </section>
    </main>
  )
}

export default LoginPage
