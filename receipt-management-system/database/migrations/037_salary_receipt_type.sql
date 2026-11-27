-- RMS 037: allow salary documents without changing existing receipt types.
alter type public.receipt_type add value if not exists 'salary';
