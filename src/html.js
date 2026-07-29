// Small HTML helpers. The LMS markup we touch is simple and very regular,
// so a handful of regexes beats pulling in a parser dependency.

const ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', '#039': "'", '#39': "'",
};

export function decodeEntities(s) {
  return String(s).replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (whole, code) => {
    const key = code.toLowerCase();
    if (ENTITIES[key] !== undefined) return ENTITIES[key];
    if (key.startsWith('#x')) return String.fromCodePoint(parseInt(key.slice(2), 16));
    if (key.startsWith('#')) return String.fromCodePoint(parseInt(key.slice(1), 10));
    return whole;
  });
}

export function stripTags(html) {
  return decodeEntities(String(html).replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();
}

export function parseAttrs(tagInnards) {
  const attrs = {};
  const re = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*(?:=\s*("[^"]*"|'[^']*'|[^\s>]+))?/g;
  let m;
  while ((m = re.exec(tagInnards))) {
    let value = m[2] ?? '';
    if (value.startsWith('"') || value.startsWith("'")) value = value.slice(1, -1);
    attrs[m[1].toLowerCase()] = decodeEntities(value);
  }
  return attrs;
}

/** All <form>...</form> blocks, with their attributes and their input fields. */
export function parseForms(html) {
  const forms = [];
  const re = /<form\b([^>]*)>([\s\S]*?)<\/form>/gi;
  let m;
  while ((m = re.exec(html))) {
    forms.push({ attrs: parseAttrs(m[1]), inner: m[2], fields: parseFields(m[2]) });
  }
  return forms;
}

function parseFields(inner) {
  const fields = [];
  const re = /<(input|button|textarea|select)\b([^>]*)>/gi;
  let m;
  while ((m = re.exec(inner))) {
    const attrs = parseAttrs(m[2]);
    if (!attrs.name) continue;
    fields.push({
      tag: m[1].toLowerCase(),
      name: attrs.name,
      type: (attrs.type || (m[1].toLowerCase() === 'button' ? 'submit' : 'text')).toLowerCase(),
      value: attrs.value ?? '',
    });
  }
  return fields;
}

/**
 * The label/value tables on /classroom/{nid}/view render as
 *   <td class="col-1">Location</td><td class="mid">:</td><td class="col-2">Majlis</td>
 * Returns a plain object keyed by the label.
 */
export function parseLabelTable(html) {
  const out = {};
  const re = /<td[^>]*class="[^"]*col-1[^"]*"[^>]*>([\s\S]*?)<\/td>\s*<td[^>]*class="[^"]*mid[^"]*"[^>]*>[\s\S]*?<\/td>\s*<td[^>]*class="[^"]*col-2[^"]*"[^>]*>([\s\S]*?)<\/td>/gi;
  let m;
  while ((m = re.exec(html))) {
    const label = stripTags(m[1]);
    if (label) out[label] = stripTags(m[2]);
  }
  return out;
}
