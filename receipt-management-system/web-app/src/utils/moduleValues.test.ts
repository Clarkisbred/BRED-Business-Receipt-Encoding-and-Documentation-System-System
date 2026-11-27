import { describe, expect, it } from 'vitest';
import { displayValue, inputValue } from './moduleValues';

describe('module value formatting', () => {
  it('renders actual null and literal null values as blank', () => {
    expect(displayValue(null)).toBe('');
    expect(displayValue(undefined)).toBe('');
    expect(displayValue('null')).toBe('');
    expect(displayValue(' NULL ')).toBe('');
    expect(inputValue(null)).toBe('');
    expect(inputValue('null')).toBe('');
  });
});
