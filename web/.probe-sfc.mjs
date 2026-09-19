import { readFileSync } from 'node:fs';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

const file = process.argv[2];
const src = readFileSync(file, 'utf8');
const { descriptor, errors } = parse(src, { filename: file });
if (errors.length) {
  console.log('PARSE ERRORS:');
  errors.forEach((e) => console.log(' -', e.message));
}
try {
  const s = compileScript(descriptor, { id: 'probe' });
  console.log('SCRIPT OK, bindings:', Object.keys(s.bindings || {}).length);
  console.log('  binding names:', Object.keys(s.bindings || {}).join(', '));
} catch (e) {
  console.log('SCRIPT COMPILE ERROR:', e.message);
}
try {
  const t = compileTemplate({ source: descriptor.template.content, filename: file, id: 'probe' });
  if (t.errors.length) {
    console.log('TEMPLATE ERRORS:');
    t.errors.forEach((e) => console.log(' -', e.message || e));
  } else console.log('TEMPLATE OK');
} catch (e) {
  console.log('TEMPLATE COMPILE ERROR:', e.message);
}
