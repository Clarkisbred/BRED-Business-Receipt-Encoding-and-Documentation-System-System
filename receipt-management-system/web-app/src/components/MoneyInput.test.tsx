import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MoneyInput } from './MoneyInput';

describe('MoneyInput', () => {
  afterEach(cleanup);

  it('keeps the caret after the decimal point and allows fractional typing', () => {
    const onChange = vi.fn();
    render(<MoneyInput value="1234" onChange={onChange} />);
    const input = screen.getByRole<HTMLInputElement>('textbox');

    fireEvent.change(input, { target: { value: '1,234.', selectionStart: 6 } });

    expect(input).toHaveValue('1,234.');
    expect(input.selectionStart).toBe(6);
    expect(onChange).toHaveBeenLastCalledWith('1234.');

    fireEvent.change(input, { target: { value: '1,234.56', selectionStart: 8 } });

    expect(input).toHaveValue('1,234.56');
    expect(input.selectionStart).toBe(8);
    expect(onChange).toHaveBeenLastCalledWith('1234.56');
  });
});
