package sn.ept.transdic1.inventory.domain;

/** Famille d'hyperviseur interrogée par un connecteur (ADR 0055). */
public enum HypervisorKind {
  /** Proxmox VE : API REST, jeton en lecture seule (rôle PVEAuditor). */
  PROXMOX,
  /** VMware vSphere : interface prévue, connecteur pas encore écrit. */
  VSPHERE,
  /** Microsoft Hyper-V : interface prévue, connecteur pas encore écrit. */
  HYPERV
}
