// Renders each src/*.jsx diagram to a standalone SVG + 2x PNG in docs/images.
// Usage (from docs/diagrams): npm install && npm run render
import { build } from 'esbuild';
import { readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import sharp from 'sharp';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = process.argv[2] ?? join(here, '..', 'images');
mkdirSync(outDir, { recursive: true });

// Light-theme colours for the design tokens used in the diagrams.
const tokens = {
  '--cds-chart-axis': '#9c9a92',
  '--cds-chart-grid': '#d8d6ce',
  '--cds-chart-categorical-1': '#2f6fdb',
  '--cds-chart-status-critical': '#d14343',
  '--cds-chart-reference-tint': '#efeee9',
  '--cds-text-primary': '#1f1e1b',
  '--cds-text-secondary': '#5f5d57',
};
const font = "-apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

const tmp = join(here, 'build');
for (const file of readdirSync(join(here, 'src')).filter(f => f.endsWith('.jsx')).sort()) {
  const name = basename(file, '.jsx');
  const outfile = join(tmp, `${name}.mjs`);
  await build({ entryPoints: [join(here, 'src', file)], outfile, bundle: true, format: 'esm', platform: 'node',
    jsx: 'transform', external: ['react'], logLevel: 'error' });
  const Diagram = (await import(pathToFileURL(outfile).href)).default;
  let svg = renderToStaticMarkup(React.createElement(Diagram));
  svg = svg.replace(/var\((--[\w-]+)\)/g, (m, t) => {
    if (!tokens[t]) throw new Error(`unknown token ${t} in ${name}`);
    return tokens[t];
  });
  const [, , w, h] = svg.match(/viewBox="([\d.\s]+)"/)[1].split(/\s+/).map(Number);
  svg = svg.replace('<svg ', `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" font-family="${font}" `)
    .replace(/(<svg[^>]*>)/, `$1<rect width="${w}" height="${h}" fill="#ffffff"/>`);
  writeFileSync(join(outDir, `${name}.svg`), svg + '\n');
  await sharp(Buffer.from(svg), { density: 192 }).png().toFile(join(outDir, `${name}.png`));
  console.log(`${name}: ${w}x${h}`);
}
