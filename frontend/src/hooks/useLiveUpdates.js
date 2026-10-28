import { useEffect, useRef, useState } from "react";
import { subscribe } from "../api/live.js";

const DEBOUNCE_MS = 400;

/**
 * Appelle `onChange` quand le serveur signale un changement d'alerte (plusieurs signaux rapprochés
 * n'en font qu'un). Retourne `true` tant que le flux en direct est connecté.
 */
export function useLiveUpdates(onChange) {
  const handler = useRef(onChange);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    handler.current = onChange;
  }, [onChange]);

  useEffect(() => {
    let timer = null;
    const stop = subscribe(
      ({ name }) => {
        if (name !== "alerts") return;
        clearTimeout(timer);
        timer = setTimeout(() => handler.current(), DEBOUNCE_MS);
      },
      { onStatus: setConnected },
    );
    return () => {
      clearTimeout(timer);
      stop();
    };
  }, []);

  return connected;
}
