import { useEffect, useLayoutEffect, useRef, useState } from 'react';

interface MoneyInputProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  required?: boolean;
  min?: number;
  max?: number;
}

function normalizeMoneyInput(value: string): string {
  const cleaned = value.replace(/[,\s]/g, '').replace(/[^\d.]/g, '');
  if (!cleaned) return '';
  const decimalIndex = cleaned.indexOf('.');
  const integer = (decimalIndex < 0 ? cleaned : cleaned.slice(0, decimalIndex)) || '0';
  const fraction = decimalIndex < 0 ? '' : `.${cleaned.slice(decimalIndex + 1).replace(/\./g, '').slice(0, 2)}`;
  return `${integer}${fraction}`;
}

function formatMoneyInput(value: unknown): string {
  const normalized = String(value ?? '').replace(/[,\s]/g, '');
  if (!normalized) return '';
  const [whole = '', fraction] = normalized.split('.');
  const grouped = (whole || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return fraction === undefined ? grouped : `${grouped}.${fraction.slice(0, 2)}`;
}

export function MoneyInput({
  value,
  onChange,
  disabled = false,
  required = false,
  min,
  max,
}: MoneyInputProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const caretCharacters = useRef<number | null>(null);
  const [displayValue, setDisplayValue] = useState(() => formatMoneyInput(value));

  useEffect(() => {
    setDisplayValue(formatMoneyInput(value));
  }, [value]);

  useEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    if (value === '') {
      input.setCustomValidity('');
      return;
    }
    const numericValue = Number(value);
    if (!Number.isFinite(numericValue)) {
      input.setCustomValidity('Enter a valid amount.');
    } else if (min !== undefined && numericValue < min) {
      input.setCustomValidity(`Enter an amount of at least ${min}.`);
    } else if (max !== undefined && numericValue > max) {
      input.setCustomValidity(`Enter an amount no more than ${max}.`);
    } else {
      input.setCustomValidity('');
    }
  }, [max, min, value]);

  useLayoutEffect(() => {
    if (caretCharacters.current === null || !inputRef.current) return;
    let position = 0;
    let characters = 0;
    while (position < displayValue.length && characters < caretCharacters.current) {
      if (/\d|\./.test(displayValue[position]!)) characters += 1;
      position += 1;
    }
    inputRef.current.setSelectionRange(position, position);
    caretCharacters.current = null;
  }, [displayValue]);

  return (
    <input
      ref={inputRef}
      type="text"
      inputMode="decimal"
      placeholder="0.00"
      required={required}
      value={displayValue}
      onChange={(event) => {
        const raw = event.target.value;
        const cursor = event.target.selectionStart ?? raw.length;
        caretCharacters.current = (raw.slice(0, cursor).match(/\d|\./g) ?? []).length;
        const normalized = normalizeMoneyInput(raw);
        setDisplayValue(formatMoneyInput(normalized));
        onChange(normalized);
      }}
      disabled={disabled}
    />
  );
}
