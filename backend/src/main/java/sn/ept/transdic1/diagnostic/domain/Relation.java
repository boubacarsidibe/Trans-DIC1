package sn.ept.transdic1.diagnostic.domain;

/** Lien entre l'alerte diagnostiquée et une alerte ou un événement voisin. */
public enum Relation {
  MEME_EQUIPEMENT,
  AMONT,
  AVAL,
  MEME_SITE
}
