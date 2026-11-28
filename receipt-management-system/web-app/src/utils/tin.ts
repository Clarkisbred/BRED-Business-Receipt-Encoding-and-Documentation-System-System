export function formatPhilippineTin(value: string): string {
  const digits = value.replace(/\D/g, '').slice(0, 15);
  const groups = [
    digits.slice(0, 3),
    digits.slice(3, 6),
    digits.slice(6, 9),
    digits.slice(9, 15),
  ].filter(Boolean);
  return groups.join('-');
}
