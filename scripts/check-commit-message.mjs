// Vérifie qu'un message de commit suit Conventional Commits.
// Usage : node scripts/check-commit-message.mjs <fichier-du-message>
import { readFileSync } from "node:fs";

const TYPES = ["feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert"];
const HEADER = /^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([a-z0-9][a-z0-9._/-]*\))?!?: \S.*$/;
const MAX_HEADER = 100;

export function validate(message) {
  const lines = message.split(/\r?\n/).filter((line) => !line.startsWith("#"));
  const header = (lines[0] ?? "").trimEnd();
  // Les commits de fusion et d'annulation produits par Git sont acceptés tels quels.
  if (/^(Merge |Revert ")/.test(header) || /^(fixup|squash)! /.test(header)) return [];
  const errors = [];
  if (!HEADER.test(header)) {
    errors.push(`L'en-tête doit suivre « type(portée): description » avec un type parmi : ${TYPES.join(", ")}.`);
  }
  if (header.length > MAX_HEADER) errors.push(`L'en-tête dépasse ${MAX_HEADER} caractères (${header.length}).`);
  if (lines.length > 1 && lines[1].trim() !== "") errors.push("Laissez une ligne vide entre l'en-tête et le corps.");
  return errors;
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replaceAll("\\", "/").split("/").pop())) {
  const file = process.argv[2];
  if (!file) {
    console.error("Usage : node scripts/check-commit-message.mjs <fichier>");
    process.exit(2);
  }
  const errors = validate(readFileSync(file, "utf8"));
  if (errors.length > 0) {
    console.error("Message de commit refusé :");
    errors.forEach((error) => console.error(`  - ${error}`));
    console.error("Exemple : feat(alerts): ajouter l'acquittement des incidents");
    process.exit(1);
  }
}
