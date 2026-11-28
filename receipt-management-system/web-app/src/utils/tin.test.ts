import { describe, expect, it } from 'vitest';
import { formatPhilippineTin } from './tin';

describe('formatPhilippineTin', () => {
  it('formats the Philippine TIN groups and limits input to 15 digits', () => {
    expect(formatPhilippineTin('123456789123456789')).toBe('123-456-789-123456');
    expect(formatPhilippineTin('12-345')).toBe('123-45');
  });
});
