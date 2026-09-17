/**
 * Extract the Prometheus instance address from a service-instance display value.
 * Service instances may be rendered as `name (10.0.0.1:8080)`.
 */
export function normalizeInstanceIp (value: unknown): string {
  let text = String(value == null ? '' : value).trim();
  const parenthesized = text.match(/\(([^()]*)\)/);
  if (parenthesized) {
    text = parenthesized[1].trim();
  }
  if (!text || text.toLowerCase() === 'unknown') {
    return '';
  }
  if (text.startsWith('[')) {
    const close = text.indexOf(']');
    if (close > 0) {
      return text.slice(1, close).trim();
    }
  }
  const colonCount = (text.match(/:/g) || []).length;
  if (colonCount === 1) {
    const portSeparator = text.lastIndexOf(':');
    const port = text.slice(portSeparator + 1);
    if (/^\d{1,5}$/.test(port)) {
      return text.slice(0, portSeparator).trim();
    }
  }
  return text;
}

function isIpAddress (value: string): boolean {
  if (/^(?:[0-9]{1,3}[.]){3}[0-9]{1,3}$/.test(value)) {
    return value.split('.').every(part => Number(part) >= 0 && Number(part) <= 255);
  }
  return value.includes(':') && /^[0-9a-f:.]+$/i.test(value);
}

export function resolveInstanceIp (row: any): string {
  const serviceInstanceText = String(row?.serviceInstance == null ? '' : row.serviceInstance).trim();
  const serviceInstanceIp = normalizeInstanceIp(serviceInstanceText);
  if (serviceInstanceIp && (serviceInstanceText.includes('(') || isIpAddress(serviceInstanceIp))) {
    return serviceInstanceIp;
  }
  for (const value of [row?.hostIp, row?.hostName]) {
    const text = String(value == null ? '' : value).trim();
    if (text.includes('(') && text.includes(')')) {
      const instanceIp = normalizeInstanceIp(text);
      if (instanceIp) {
        return instanceIp;
      }
    }
  }
  return normalizeInstanceIp(row?.hostIp || row?.hostName);
}
