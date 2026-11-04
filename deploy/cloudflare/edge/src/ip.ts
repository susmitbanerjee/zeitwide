/** An IPv4 or IPv6 network, as bytes plus a prefix length. IPv4-mapped IPv6 is folded to IPv4. */
export interface Network {
  readonly bytes: Uint8Array;
  readonly bits: number;
}

/** Parses an IP address into 4 or 16 bytes, or null when it is not one. */
export function parseAddress(text: string): Uint8Array | null {
  const value = text.trim();
  return value.includes(":") ? parseIpv6(value) : parseIpv4(value);
}

/** Parses "a.b.c.d/n" or "x::/n"; null when malformed. */
export function parseNetwork(cidr: string): Network | null {
  const slash = cidr.indexOf("/");
  const bytes = parseAddress(slash < 0 ? cidr : cidr.slice(0, slash));
  if (bytes === null) {
    return null;
  }
  const max = bytes.length * 8;
  const suffix = slash < 0 ? String(max) : cidr.slice(slash + 1);
  if (!/^\d{1,3}$/.test(suffix)) {
    return null;
  }
  const bits = Number(suffix);
  return bits <= max ? { bytes, bits } : null;
}

export function contains(network: Network, address: Uint8Array): boolean {
  if (network.bytes.length !== address.length) {
    return false;
  }
  const whole = Math.floor(network.bits / 8);
  for (let i = 0; i < whole; i++) {
    if (network.bytes[i] !== address[i]) {
      return false;
    }
  }
  const rest = network.bits % 8;
  if (rest === 0) {
    return true;
  }
  const mask = (0xff << (8 - rest)) & 0xff;
  return ((network.bytes[whole] ?? 0) & mask) === ((address[whole] ?? 0) & mask);
}

function parseIpv4(text: string): Uint8Array | null {
  const parts = text.split(".");
  if (parts.length !== 4 || !parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)) {
    return null;
  }
  return Uint8Array.from(parts.map(Number));
}

function parseIpv6(text: string): Uint8Array | null {
  const zone = text.indexOf("%");
  let value = zone < 0 ? text : text.slice(0, zone);
  let tail: Uint8Array | null = null;
  const lastColon = value.lastIndexOf(":");
  if (value.slice(lastColon + 1).includes(".")) {
    tail = parseIpv4(value.slice(lastColon + 1));
    if (tail === null) {
      return null;
    }
    value = `${value.slice(0, lastColon + 1)}0:0`;
  }
  const halves = value.split("::");
  if (halves.length > 2) {
    return null;
  }
  const head = halves[0] === "" ? [] : (halves[0] ?? "").split(":");
  const back = halves.length === 2 && halves[1] !== "" ? (halves[1] ?? "").split(":") : [];
  const missing = 8 - head.length - back.length;
  if ((halves.length === 1 && missing !== 0) || (halves.length === 2 && missing < 1)) {
    return null;
  }
  const groups = [...head, ...Array<string>(halves.length === 2 ? missing : 0).fill("0"), ...back];
  if (!groups.every((group) => /^[0-9a-f]{1,4}$/i.test(group))) {
    return null;
  }
  const bytes = new Uint8Array(16);
  groups.forEach((group, i) => {
    const n = parseInt(group, 16);
    bytes[i * 2] = n >> 8;
    bytes[i * 2 + 1] = n & 0xff;
  });
  if (tail !== null) {
    bytes.set(tail, 12);
  }
  return isIpv4Mapped(bytes) ? bytes.slice(12) : bytes;
}

function isIpv4Mapped(bytes: Uint8Array): boolean {
  return bytes.slice(0, 10).every((b) => b === 0) && bytes[10] === 0xff && bytes[11] === 0xff;
}
