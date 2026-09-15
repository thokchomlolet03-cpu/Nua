// Reviews describe support for an objective, never predicted learning success.
export function reviewInput(raw) {
  if (!raw || typeof raw.objective !== 'string' || raw.objective.trim().length < 12 || raw.objective.length > 300 || typeof raw.level !== 'string' || raw.level.length > 200 || typeof raw.excerpt !== 'string' || raw.excerpt.length < 40 || raw.excerpt.length > 6000) throw Error('Provide an objective, learner level and a checked passage of 40–6000 characters.');
  return { objective: raw.objective, level: raw.level, excerpt: raw.excerpt };
}
export function parseReview(raw, input) {
  const r = JSON.parse(raw);
  if (!Array.isArray(r.findings) || !r.findings.length || r.findings.length > 8) throw Error('Review must contain 1–8 objective requirements.');
  const findings = r.findings.map(f => {
    for (const key of ['requirement', 'reason', 'remedy', 'search']) if (typeof f[key] !== 'string' || f[key].length < 3 || f[key].length > 800) throw Error('Incomplete review finding.');
    if (!['supported','partial','missing','verify'].includes(f.status) || typeof f.quote !== 'string' || f.quote.length > 600 || (f.quote && !input.excerpt.includes(f.quote)) || (['supported','partial'].includes(f.status) && !f.quote)) throw Error('Review evidence must quote the supplied passage exactly.');
    return Object.fromEntries(['requirement','status','quote','reason','remedy','search'].map(k => [k,f[k]]));
  });
  return { schema: 'material-review/1', input: structuredClone(input), findings, at: Date.now(), status: 'AI proposal — author review required' };
}
export function searchLink(query) { return 'https://www.google.com/search?q=' + encodeURIComponent(query); }
export function approveSupplement(pages, title, content, url) {
  if (!title.trim() || content.trim().length < 40 || content.length > 6000) throw Error('Give the supplement a title and 40–6000 characters of checked content.');
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password) throw Error('Use an HTTPS source URL without credentials.');
  if (pages.length >= 80 || pages.reduce((n,p) => n+p.text.length,0)+content.trim().length > 180000) throw Error('Learning pack exceeds material limits.');
  return { page: pages.length+1, text: content.trim(), supplement: { title: title.trim(), url: parsed.href, approvedAt: Date.now() } };
}
