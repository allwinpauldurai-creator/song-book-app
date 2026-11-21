# Design diagrams

Sources for the pictures in [`../DESIGN.md`](../DESIGN.md). Each `src/*.jsx` file draws one diagram as SVG (React
JSX, coordinates by hand). `render.mjs` turns them into `../images/<name>.svg` and a 2x `../images/<name>.png`.

To change a diagram, edit its `.jsx` file, then (Node 18+):

```powershell
cd docs/diagrams
npm install
npm run render
```

Commit the updated files in `docs/images/` together with the source. Colours are design tokens
(`var(--cds-...)`) mapped to hex values in `render.mjs`.
