import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";
import { completeOidcLogin } from "../auth/oidc.js";
import photoLarge from "../assets/login/reseau-2000.webp";
import photoSmall from "../assets/login/reseau-900.webp";

/** Retour du fournisseur d'identité : termine la connexion unique puis ouvre l'application. */
function OidcCallbackPage() {
  const { adoptSession } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState("");
  const done = useRef(false);

  useEffect(() => {
    if (done.current) return;
    done.current = true;
    completeOidcLogin(globalThis.location.search)
      .then(({ session, returnTo }) => {
        adoptSession(session);
        navigate(returnTo, { replace: true });
      })
      .catch((e) => setError(e?.message || "La connexion unique a échoué."));
  }, [adoptSession, navigate]);

  return (
    <main className="login">
      <img
        className="login-photo"
        src={photoLarge}
        srcSet={`${photoSmall} 900w, ${photoLarge} 2000w`}
        sizes="100vw"
        alt=""
      />
      <div
        className="login-card"
        style={{
          gridColumn: "1 / -1",
          justifySelf: "center",
          alignSelf: "center",
        }}
      >
        <h1 className="login-title">Connexion</h1>
        {error ? (
          <>
            <p role="alert" className="login-error">
              {error}
            </p>
            <Link to="/connexion" className="login-assist">
              Revenir à la page de connexion
            </Link>
          </>
        ) : (
          <p className="login-help" role="status">
            Connexion avec votre compte EPT…
          </p>
        )}
      </div>
    </main>
  );
}

export default OidcCallbackPage;
