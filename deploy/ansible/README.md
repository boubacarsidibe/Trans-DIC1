# Déploiement de l'agent Trans-DIC1 par Ansible

Rôle `trans_dic1_agent` (Linux par SSH, Windows par WinRM) et playbook de déploiement par vagues.
Mode d'emploi complet, gestion des secrets, retour arrière et dépannage :
[docs/devops/deploiement-a-grande-echelle.md](../../docs/devops/deploiement-a-grande-echelle.md).

```sh
ansible-galaxy collection install -r requirements.yml -p collections
ansible-playbook -i inventory/exemple.yml playbooks/deploy-agent.yml --syntax-check
TRANS_DIC1_ENROLL_TOKEN=tdic1e_simulation ansible-playbook -i tests/inventaire-local.yml \
  playbooks/deploy-agent.yml --check -e trans_dic1_agent_systemd=false
```

L'inventaire d'exemple n'utilise que des adresses de documentation (RFC 5737). Aucun secret dans ce
dossier : le jeton d'inscription vient de la variable masquée `TRANS_DIC1_ENROLL_TOKEN` du pipeline
ou d'Ansible Vault.
