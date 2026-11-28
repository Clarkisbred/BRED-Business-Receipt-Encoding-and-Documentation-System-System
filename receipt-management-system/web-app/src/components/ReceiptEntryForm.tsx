import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Plus, X } from 'lucide-react';
import Decimal from 'decimal.js';
import { loadModulePage, saveModuleRecord, type ModulePageData, type ModuleRecord } from '../api/modules';
import { formatPhilippineTin } from '../utils/tin';

interface ReceiptEntryFormProps {
  data: ModulePageData;
  saving: boolean;
  receipt?: ModuleRecord;
  onSavingChange: (saving: boolean) => void;
  onCancel: () => void;
  onSaved: (data: ModulePageData) => void;
}

function valueText(value: unknown, fallback = ''): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : fallback;
}

function receiptItems(receipt?: ModuleRecord): ReceiptLine[] {
  const source = receipt?.values.items;
  if (!Array.isArray(source)) return [{ id: 1, description: '', quantity: '1', unit: 'pc', unitPrice: '' }];
  const items = source.flatMap((value, index) => {
    if (!value || typeof value !== 'object') return [];
    const item = value as Record<string, unknown>;
    return [{
      id: index + 1,
      description: valueText(item.description),
      quantity: valueText(item.quantity, '1'),
      unit: valueText(item.unit, 'pc'),
      unitPrice: valueText(item.unitPrice),
    }];
  });
  return items.length > 0 ? items : [{ id: 1, description: '', quantity: '1', unit: 'pc', unitPrice: '' }];
}

function receiptAttachments(receipt?: ModuleRecord): ExistingAttachment[] {
  const source = receipt?.values.attachments;
  if (!Array.isArray(source)) return [];
  return source.flatMap((value) => {
    if (!value || typeof value !== 'object') return [];
    const attachment = value as Record<string, unknown>;
    const fileName = valueText(attachment.fileName);
    const storagePath = valueText(attachment.storagePath);
    return fileName && storagePath ? [{ fileName, storagePath }] : [];
  });
}

interface ReceiptLine {
  id: number;
  description: string;
  quantity: string;
  unit: string;
  unitPrice: string;
}

interface NewSupplier {
  businessName: string;
  tin: string;
  address: string;
  contactNumber: string;
}

interface ExistingAttachment {
  fileName: string;
  storagePath: string;
}

const units = ['pc', 'quarter', 'kg', 'g', 'lb', 'L', 'mL', 'm', 'cm', 'box', 'bag', 'sack', 'set', 'roll'];
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const MAX_TOTAL_ATTACHMENT_BYTES = 10 * 1024 * 1024;

function todayValue(): string {
  const today = new Date();
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
}

function amount(value: string): Decimal {
  return new Decimal(value || 0);
}

function pesos(value: Decimal): string {
  return `₱${value.toNumber().toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function fileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error(`Could not read ${file.name}. Please choose it again.`));
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        reject(new Error(`Could not read ${file.name}. Please choose it again.`));
        return;
      }
      resolve(reader.result.slice(reader.result.indexOf(',') + 1));
    };
    reader.readAsDataURL(file);
  });
}

export function ReceiptEntryForm({ data, saving, receipt, onSavingChange, onCancel, onSaved }: ReceiptEntryFormProps) {
  const [page, setPage] = useState(data);
  const [receiptNo, setReceiptNo] = useState(() => valueText(receipt?.values.receiptNo));
  const [date, setDate] = useState(() => valueText(receipt?.values.date, todayValue()));
  const [type, setType] = useState(() => valueText(receipt?.values.typeValue, 'official_receipt'));
  const [supplierId, setSupplierId] = useState(() => valueText(receipt?.values.supplierId));
  const [projectId, setProjectId] = useState(() => valueText(receipt?.values.projectId));
  const [classification, setClassification] = useState(() => valueText(receipt?.values.classification));
  const [paymentMethod, setPaymentMethod] = useState(() => valueText(receipt?.values.paymentMethod, 'Cash'));
  const [notes, setNotes] = useState(() => valueText(receipt?.values.notes));
  const [discount, setDiscount] = useState(() => valueText(receipt?.values.discountValue, '0'));
  const [vat, setVat] = useState(() => valueText(receipt?.values.vatValue, '0'));
  const [vatAuto, setVatAuto] = useState(!receipt);
  const [additionalCharges, setAdditionalCharges] = useState(() => valueText(receipt?.values.additionalChargesValue, '0'));
  const [ewtRate, setEwtRate] = useState(() => valueText(receipt?.values.ewtRate, '0'));
  const [items, setItems] = useState<ReceiptLine[]>(() => receiptItems(receipt));
  const [existingAttachments, setExistingAttachments] = useState<ExistingAttachment[]>(() => receiptAttachments(receipt));
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [supplierDialog, setSupplierDialog] = useState(false);
  const [newSupplier, setNewSupplier] = useState<NewSupplier>({
    businessName: '',
    tin: '',
    address: '',
    contactNumber: '',
  });
  const [addingSupplier, setAddingSupplier] = useState(false);

  const supplierOptions = page.fields.find((field) => field.key === 'supplierId')?.options ?? [];
  const projectOptions = page.fields.find((field) => field.key === 'projectId')?.options ?? [];
  const typeOptions = page.fields.find((field) => field.key === 'type')?.options ?? [];
  const classificationOptions = [...new Map(data.rows
    .map((row) => valueText(row.values.classification).trim())
    .filter((value) => value && value.toLocaleUpperCase() !== 'UNCLASSIFIED')
    .map((value) => [value.toLocaleLowerCase(), value] as const)).values()]
    .sort((left, right) => left.localeCompare(right));
  const supplier = supplierOptions.find((option) => option.value === supplierId);
  const subtotal = useMemo(() => items.reduce(
    (sum, item) => sum.plus(amount(item.quantity).mul(amount(item.unitPrice))
      .toDecimalPlaces(2, Decimal.ROUND_HALF_UP)),
    new Decimal(0),
  ).toDecimalPlaces(2, Decimal.ROUND_HALF_UP), [items]);
  const calculatedVat = Decimal.max(subtotal.minus(amount(discount)), 0).mul('0.12')
    .toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
  const ewtPercent = amount(ewtRate);
  const grandTotal = subtotal.minus(amount(discount)).plus(amount(vat)).plus(amount(additionalCharges))
    .toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
  const ewtAmount = grandTotal.div('1.12').mul(ewtPercent).div(100)
    .toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
  const netTotal = grandTotal.minus(ewtAmount).toDecimalPlaces(2, Decimal.ROUND_HALF_UP);

  useEffect(() => {
    if (vatAuto) setVat(calculatedVat.toFixed(2));
  }, [calculatedVat, vatAuto]);

  function updateItem(id: number, patch: Partial<ReceiptLine>) {
    setItems((current) => current.map((item) => item.id === id ? { ...item, ...patch } : item));
  }

  function addItem() {
    setItems((current) => [...current, {
      id: Math.max(0, ...current.map((item) => item.id)) + 1,
      description: '',
      quantity: '1',
      unit: 'pc',
      unitPrice: '',
    }]);
  }

  function addFiles(fileList: FileList | null) {
    if (!fileList) return;
    setError(null);
    const accepted = [...fileList];
    for (const file of accepted) {
      if (!/\.(png|jpe?g|pdf)$/i.test(file.name)) {
        setError('Attachments must be PNG, JPG, or PDF files.');
        return;
      }
      if (file.size > MAX_ATTACHMENT_BYTES) {
        setError(`${file.name} is over 10 MB. Please choose a smaller file.`);
        return;
      }
    }
    const next = [...files, ...accepted];
    if (next.reduce((sum, file) => sum + file.size, 0) > MAX_TOTAL_ATTACHMENT_BYTES) {
      setError('Receipt attachments must total 10 MB or less.');
      return;
    }
    setFiles(next);
  }

  async function createSupplier(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAddingSupplier(true);
    setError(null);
    try {
      const savedSuppliers = await saveModuleRecord('SUPPLIERS', 'save', {
        businessName: newSupplier.businessName,
        tin: newSupplier.tin,
        address: newSupplier.address,
        contactNumber: newSupplier.contactNumber,
      });
      const match = savedSuppliers.rows.find((row) => (
        row.values.businessName === newSupplier.businessName.trim()
        && row.values.tin === newSupplier.tin.trim()
      ));
      if (!match) throw new Error('Supplier was saved, but BRED could not select it. Refresh the supplier list.');
      setPage(await loadModulePage('RECEIPTS'));
      setSupplierId(match.id);
      setSupplierDialog(false);
      setNewSupplier({ businessName: '', tin: '', address: '', contactNumber: '' });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this supplier.');
    } finally {
      setAddingSupplier(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    if (!receiptNo.trim()) {
      setError('Receipt number is required.');
      return;
    }
    if (!supplierId) {
      setError('Choose a supplier.');
      return;
    }
    if (!classification.trim()) {
      setError('Enter a category.');
      return;
    }
    const enteredItems = items.filter((item) => (
      item.description.trim() || item.unitPrice.trim() || item.quantity !== '1' || item.unit !== 'pc'
    ));
    if (enteredItems.length === 0 || enteredItems.some((item) => (
      !item.description.trim()
      || !amount(item.quantity).greaterThan(0)
      || item.unitPrice.trim() === ''
      || amount(item.unitPrice).lessThan(0)
    ))) {
      setError('Complete each item with a description, positive quantity, and non-negative unit price.');
      return;
    }
    if (ewtPercent.lessThan(0) || ewtPercent.greaterThan(100)) {
      setError('EWT percentage must be from 0 to 100.');
      return;
    }
    onSavingChange(true);
    try {
      const attachments = await Promise.all(files.map(async (file) => ({
        name: file.name,
        data: await fileAsBase64(file),
      })));
      const updated = await saveModuleRecord('RECEIPTS', 'save', {
        receiptNo,
        date,
        type,
        supplierId,
        projectId,
        classification: classification.trim().toLocaleUpperCase(),
        paymentMethod,
        notes,
        ewtRate: String(ewtPercent),
        discount: discount || '0',
        vat: vat || '0',
        additionalCharges: additionalCharges || '0',
        items: enteredItems.map((item) => ({
          description: item.description.trim(),
          quantity: item.quantity,
          unit: item.unit.trim() || 'pc',
          unitPrice: item.unitPrice || '0',
        })),
        attachments,
        existingAttachmentPaths: existingAttachments.map((attachment) => attachment.storagePath),
      }, receipt?.id);
      onSaved(updated);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this receipt.');
    } finally {
      onSavingChange(false);
    }
  }

  return (
    <section className="panel module-form-panel receipt-entry-panel" aria-labelledby="receipt-entry-title">
      <div className="panel-heading">
        <div>
          <h3 id="receipt-entry-title">{receipt ? 'Edit receipt' : 'New receipt'}</h3>
          <p>Enter the receipt details, line items, and supporting files.</p>
        </div>
        <button className="icon-button" type="button" aria-label="Close form" onClick={onCancel} disabled={saving}>
          <X aria-hidden="true" size={18} />
        </button>
      </div>
      <form className="receipt-entry-form" onSubmit={(event) => void submit(event)}>
        <div className="receipt-entry-grid">
          <label className="module-field">
            <span>Receipt number <strong aria-hidden="true">*</strong></span>
            <input required value={receiptNo} onChange={(event) => setReceiptNo(event.target.value)} disabled={saving} />
          </label>
          <label className="module-field">
            <span>Category <strong aria-hidden="true">*</strong></span>
            <input
              required
              list="receipt-classifications"
              value={classification}
              onChange={(event) => setClassification(event.target.value.toLocaleUpperCase())}
              disabled={saving}
            />
            <datalist id="receipt-classifications">
              {classificationOptions.map((option) => <option value={option} key={option} />)}
            </datalist>
          </label>
          <label className="module-field">
            <span>Date <strong aria-hidden="true">*</strong></span>
            <input aria-label="Receipt date" type="date" required value={date} onChange={(event) => setDate(event.target.value)} disabled={saving} />
          </label>
          <label className="module-field">
            <span>Document type <strong aria-hidden="true">*</strong></span>
            <select required value={type} onChange={(event) => setType(event.target.value)} disabled={saving}>
              {typeOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label className="module-field">
            <span>Project</span>
            <select value={projectId} onChange={(event) => setProjectId(event.target.value)} disabled={saving}>
              <option value="">— None —</option>
              {projectOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label className="module-field receipt-entry-supplier">
            <span>Supplier <strong aria-hidden="true">*</strong></span>
            <select required value={supplierId} onChange={(event) => setSupplierId(event.target.value)} disabled={saving}>
              <option value="">Select a supplier…</option>
              {supplierOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select>
          </label>
          <button className="button button-secondary receipt-new-supplier" type="button" onClick={() => setSupplierDialog(true)} disabled={saving}>
            <Plus aria-hidden="true" size={16} /> New supplier
          </button>
          {supplier && (
            <div className="receipt-supplier-details">
              <span>{formatPhilippineTin(supplier.description?.match(/TIN ([^·]+)/)?.[1]?.trim() ?? '')}</span>
              {supplier.description?.includes(' · ') && <span>{supplier.description.split(' · ').slice(1).join(' · ')}</span>}
            </div>
          )}
          <label className="module-field">
            <span>Account</span>
            <select value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value)} disabled={saving}>
              <option>Cash</option><option>Card</option><option>E-wallet</option>
            </select>
          </label>
        </div>

        <section className="receipt-items-section" aria-label="Receipt line items">
          <div className="receipt-items-heading">
            <div><h4>Items</h4><p>Enter the quantity, SI unit, and unit price for each line.</p></div>
            <button className="button button-secondary" type="button" onClick={addItem} disabled={saving}>
              <Plus aria-hidden="true" size={16} /> Add item
            </button>
          </div>
          <div className="table-scroll receipt-items-scroll">
            <table className="receipt-items-table">
              <thead><tr><th>Description</th><th>Quantity</th><th>Unit</th><th>Unit price (₱)</th><th>Subtotal (₱)</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td><input aria-label={`Item ${item.id} description`} value={item.description} onChange={(event) => updateItem(item.id, { description: event.target.value })} disabled={saving} /></td>
                    <td><input aria-label={`Item ${item.id} quantity`} type="number" min="0.001" step="0.001" value={item.quantity} onChange={(event) => updateItem(item.id, { quantity: event.target.value })} disabled={saving} /></td>
                    <td><input aria-label={`Item ${item.id} unit`} list="receipt-units" value={item.unit} onChange={(event) => updateItem(item.id, { unit: event.target.value })} disabled={saving} /><datalist id="receipt-units">{units.map((unit) => <option value={unit} key={unit} />)}</datalist></td>
                    <td><input aria-label={`Item ${item.id} unit price`} type="number" min="0" step="0.01" value={item.unitPrice} onChange={(event) => updateItem(item.id, { unitPrice: event.target.value })} disabled={saving} /></td>
                    <td className="is-numeric">{pesos(amount(item.quantity).mul(amount(item.unitPrice)).toDecimalPlaces(2, Decimal.ROUND_HALF_UP))}</td>
                    <td><button className="icon-button" type="button" aria-label={`Remove item ${item.id}`} onClick={() => setItems((current) => current.filter((entry) => entry.id !== item.id))} disabled={saving || items.length === 1}><X aria-hidden="true" size={16} /></button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <div className="receipt-entry-bottom">
          <section className="receipt-attachments-section" aria-label="Receipt attachments">
            <div className="receipt-items-heading">
              <div><h4>Attachments</h4><p>PNG, JPG, or PDF; 10 MB total.</p></div>
              <label className="button button-secondary receipt-file-button">
                <Plus aria-hidden="true" size={16} /> Add attachments
                <input aria-label="Add attachments" type="file" accept=".png,.jpg,.jpeg,.pdf" multiple onChange={(event) => { addFiles(event.target.files); event.currentTarget.value = ''; }} disabled={saving} />
              </label>
            </div>
            {files.length === 0 && existingAttachments.length === 0 ? <p className="receipt-attachments-empty">No attachments added.</p> : (
              <ul className="receipt-attachment-list">
                {existingAttachments.map((attachment) => (
                  <li key={attachment.storagePath}>
                    <span>{attachment.fileName} <small>(saved)</small></span>
                    <button className="text-button text-button-danger" type="button" onClick={() => setExistingAttachments((current) => current.filter((entry) => entry.storagePath !== attachment.storagePath))} disabled={saving}>Remove</button>
                  </li>
                ))}
                {files.map((file, index) => (
                  <li key={`${file.name}-${file.lastModified}-${index}`}>
                    <span>{file.name} <small>({(file.size / 1024).toFixed(0)} KB)</small></span>
                    <button className="text-button text-button-danger" type="button" onClick={() => setFiles((current) => current.filter((_, fileIndex) => fileIndex !== index))} disabled={saving}>Remove</button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="receipt-totals" aria-label="Receipt totals">
            <label className="module-field"><span>Discount (₱)</span><input type="number" min="0" step="0.01" value={discount} onChange={(event) => setDiscount(event.target.value)} disabled={saving} /></label>
            <label className="module-field"><span>VAT (₱)</span><input type="number" min="0" step="0.01" value={vat} onChange={(event) => { setVatAuto(false); setVat(event.target.value); }} disabled={saving} /></label>
            <button className="button button-secondary receipt-vat-recompute" type="button" onClick={() => setVatAuto(true)} disabled={saving}>Recompute 12%</button>
            <label className="module-field"><span>Additional charges (₱)</span><input type="number" min="0" step="0.01" value={additionalCharges} onChange={(event) => setAdditionalCharges(event.target.value)} disabled={saving} /></label>
            <div className="receipt-ewt-control">
              <span>EWT</span>
              {[0, 1, 2, 5, 10].map((rate) => (
                <button className={ewtPercent.equals(rate) ? 'is-selected' : undefined} type="button" key={rate} onClick={() => setEwtRate(String(rate))} disabled={saving}>
                  {rate === 0 ? 'Off' : `${rate}%`}
                </button>
              ))}
              <label className="module-field"><span>Custom %</span><input aria-label="Custom EWT percentage" type="number" min="0" max="100" step="0.01" value={ewtRate} onChange={(event) => setEwtRate(event.target.value)} disabled={saving} /></label>
            </div>
            <dl className="receipt-total-lines">
              <div><dt>Subtotal</dt><dd>{pesos(subtotal)}</dd></div>
              <div><dt>EWT{ewtPercent.greaterThan(0) ? ` (${ewtPercent}%)` : ''}</dt><dd>{ewtPercent.greaterThan(0) ? `−${pesos(ewtAmount)}` : '—'}</dd></div>
              <div><dt>Receipt total</dt><dd>{pesos(grandTotal)}</dd></div>
              <div className="receipt-net-total"><dt>Net payable</dt><dd>{pesos(netTotal)}</dd></div>
            </dl>
          </section>
        </div>

        <label className="module-field module-field-wide">
          <span>Personal notes</span>
          <textarea rows={3} maxLength={2000} value={notes} onChange={(event) => setNotes(event.target.value)} disabled={saving} />
        </label>
        {error && <p className="form-message receipt-entry-error" role="alert">{error}</p>}
        <div className="module-form-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={saving}>Cancel</button>
          <button className="button button-primary" type="submit" disabled={saving}>{saving ? 'Saving…' : receipt ? 'Save changes' : 'Save receipt'}</button>
        </div>
      </form>

      {supplierDialog && (
        <div className="receipt-dialog-backdrop">
          <section className="panel receipt-supplier-dialog" role="dialog" aria-modal="true" aria-labelledby="new-supplier-title">
            <div className="panel-heading">
              <div><h3 id="new-supplier-title">New supplier</h3><p>Add supplier details for this receipt.</p></div>
              <button className="icon-button" type="button" aria-label="Close new supplier" onClick={() => setSupplierDialog(false)} disabled={addingSupplier}><X aria-hidden="true" size={18} /></button>
            </div>
            <form className="module-form" onSubmit={(event) => void createSupplier(event)}>
              <label className="module-field module-field-wide"><span>Business name <strong aria-hidden="true">*</strong></span><input required value={newSupplier.businessName} onChange={(event) => setNewSupplier((current) => ({ ...current, businessName: event.target.value }))} disabled={addingSupplier} /></label>
              <label className="module-field module-field-wide"><span>TIN <strong aria-hidden="true">*</strong></span><input required inputMode="numeric" autoComplete="off" maxLength={17} placeholder="###-###-###-######" value={newSupplier.tin} onChange={(event) => setNewSupplier((current) => ({ ...current, tin: formatPhilippineTin(event.target.value) }))} disabled={addingSupplier} /></label>
              <label className="module-field module-field-wide"><span>Address</span><input value={newSupplier.address} onChange={(event) => setNewSupplier((current) => ({ ...current, address: event.target.value }))} disabled={addingSupplier} /></label>
              <label className="module-field module-field-wide"><span>Contact number</span><input value={newSupplier.contactNumber} onChange={(event) => setNewSupplier((current) => ({ ...current, contactNumber: event.target.value }))} disabled={addingSupplier} /></label>
              <div className="module-form-actions">
                <button className="button button-secondary" type="button" onClick={() => setSupplierDialog(false)} disabled={addingSupplier}>Cancel</button>
                <button className="button button-primary" type="submit" disabled={addingSupplier}>{addingSupplier ? 'Saving…' : 'Save supplier'}</button>
              </div>
            </form>
          </section>
        </div>
      )}
    </section>
  );
}
