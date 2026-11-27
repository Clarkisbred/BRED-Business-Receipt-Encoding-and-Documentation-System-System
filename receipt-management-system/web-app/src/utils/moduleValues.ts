export function displayValue(value: unknown): string {
  if (value == null || value === '') return '';
  if (typeof value === 'string') {
    return value.trim().toLocaleLowerCase() === 'null' ? '' : value;
  }
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(displayValue).filter(Boolean).join(', ');
  return JSON.stringify(value);
}

export function inputValue(value: unknown): string {
  const formatted = displayValue(value);
  return formatted === 'Yes' || formatted === 'No' ? String(value) : formatted;
}

export function booleanValue(value: unknown): boolean {
  return value === true || (typeof value === 'string' && value.toLocaleLowerCase() === 'true');
}
