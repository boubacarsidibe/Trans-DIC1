-- ADR-0006 : l'agent envoie ses mesures à l'API avec un jeton secret propre à chaque agent.
-- Seule l'empreinte SHA-256 du jeton est stockée ; le jeton lui-même n'est montré qu'à l'enregistrement.
ALTER TABLE collection_agent ADD COLUMN token_hash VARCHAR(128);
CREATE UNIQUE INDEX uq_collection_agent_token_hash ON collection_agent(token_hash) WHERE token_hash IS NOT NULL;
