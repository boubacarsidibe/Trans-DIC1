package sn.ept.transdic1.inventory.domain;

/** Ce qu'un connecteur d'hyperviseur voit : un hôte physique, une VM ou un conteneur. */
public enum HypervisorResourceKind {
  HOST,
  VM,
  CONTAINER
}
