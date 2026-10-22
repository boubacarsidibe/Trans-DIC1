import { useState } from "react";
import {
  EQUIPMENT_STATUSES,
  EQUIPMENT_TYPES,
  validateEquipment,
} from "../api/equipment.js";

const EMPTY = {
  inventoryCode: "",
  name: "",
  managementAddress: "",
  type: "SERVER",
  status: "UNKNOWN",
  siteId: "",
};

const inputClass = "mt-1 w-full rounded-lg bg-slate-900 p-2 text-white";

function Field({ label, error, children }) {
  return (
    <label className="block text-sm text-slate-300">
      {label}
      {children}
      {error && (
        <span role="alert" className="mt-1 block text-red-300">
          {error}
        </span>
      )}
    </label>
  );
}

function EquipmentForm({ initial, sites, onSubmit, onCancel, submitting }) {
  const [values, setValues] = useState({
    ...EMPTY,
    siteId: sites[0]?.id ?? "",
    ...initial,
  });
  const [errors, setErrors] = useState({});

  const bind = (field) => ({
    value: values[field],
    onChange: (e) => setValues({ ...values, [field]: e.target.value }),
  });

  function submit(event) {
    event.preventDefault();
    const found = validateEquipment(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onSubmit({
      ...values,
      inventoryCode: values.inventoryCode.trim(),
      name: values.name.trim(),
      managementAddress: values.managementAddress.trim(),
    });
  }

  return (
    <form
      noValidate
      onSubmit={submit}
      aria-label={initial ? "Modifier l'équipement" : "Ajouter un équipement"}
      className="status-card mt-6 grid gap-4 rounded-xl p-5 sm:grid-cols-2"
    >
      <Field label="Code d'inventaire" error={errors.inventoryCode}>
        <input {...bind("inventoryCode")} className={inputClass} />
      </Field>
      <Field label="Nom" error={errors.name}>
        <input {...bind("name")} className={inputClass} />
      </Field>
      <Field label="Adresse de gestion" error={errors.managementAddress}>
        <input {...bind("managementAddress")} className={inputClass} />
      </Field>
      <Field label="Site" error={errors.siteId}>
        <select {...bind("siteId")} className={inputClass}>
          <option value="">— Choisir —</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Type" error={errors.type}>
        <select {...bind("type")} className={inputClass}>
          {EQUIPMENT_TYPES.map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </Field>
      <Field label="Statut" error={errors.status}>
        <select {...bind("status")} className={inputClass}>
          {EQUIPMENT_STATUSES.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
      </Field>
      <div className="flex gap-3 sm:col-span-2">
        <button
          type="submit"
          disabled={submitting}
          className="rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950"
        >
          {submitting ? "Enregistrement…" : "Enregistrer"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-slate-600 px-4 py-2 text-slate-200"
        >
          Annuler
        </button>
      </div>
    </form>
  );
}

export default EquipmentForm;
