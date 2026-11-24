// Gera os SVGs oficiais da logo T.A.S.K. (geometria pura no padrão T.A.L. / T.A.R.K.A.S.).
const fs = require('fs');
const path = require('path');

const OUT = __dirname;
const C = {
  black: '#080808',
  white: '#F2F2F0',
  ice: '#7EE3EA',
  teal: '#0D6C74',
};

function roundRectPath(x, y, w, h, r) {
  return [
    `M${x + r},${y}`,
    `L${x + w - r},${y}`,
    `Q${x + w},${y} ${x + w},${y + r}`,
    `L${x + w},${y + h - r}`,
    `Q${x + w},${y + h} ${x + w - r},${y + h}`,
    `L${x + r},${y + h}`,
    `Q${x},${y + h} ${x},${y + h - r}`,
    `L${x},${y + r}`,
    `Q${x},${y} ${x + r},${y}Z`,
  ].join('');
}

function circlePath(cx, cy, r) {
  const k = (r * 0.55228475).toFixed(2);
  const rk = r.toFixed(2);
  return [
    `M${cx},${(cy - r).toFixed(2)}`,
    `C${(cx + +k).toFixed(2)},${(cy - r).toFixed(2)} ${(cx + r).toFixed(2)},${(cy - +k).toFixed(2)} ${(cx + r).toFixed(2)},${cy}`,
    `C${(cx + r).toFixed(2)},${(cy + +k).toFixed(2)} ${(cx + +k).toFixed(2)},${(cy + r).toFixed(2)} ${cx},${(cy + r).toFixed(2)}`,
    `C${(cx - +k).toFixed(2)},${(cy + r).toFixed(2)} ${(cx - r).toFixed(2)},${(cy + +k).toFixed(2)} ${(cx - r).toFixed(2)},${cy}`,
    `C${(cx - r).toFixed(2)},${(cy - +k).toFixed(2)} ${(cx - +k).toFixed(2)},${(cy - r).toFixed(2)} ${cx},${(cy - r).toFixed(2)}Z`,
  ].join('');
}

function renderTaskLogo({ bg, fg, accent, monoCutout = false }) {
  // 108x108 viewport centered at (54, 54), bounding box [27..81] x [30..78] inside 66dp safe zone
  const capsFg = [
    roundRectPath(27, 30, 15, 13, 3),
    roundRectPath(66, 30, 15, 13, 3),
    roundRectPath(27, 47, 15, 13, 3),
    roundRectPath(46, 47, 16, 13, 3),
    roundRectPath(66, 47, 15, 13, 3),
    roundRectPath(33, 65, 42, 13, 3),
  ].join(' ');

  const targetOuter = roundRectPath(46, 30, 16, 13, 3);
  const dot = circlePath(54, 36.5, 2.1);
  const innerFrame = roundRectPath(49, 32.5, 10, 8, 1.5);

  const paths = [];
  if (bg) {
    paths.push(`<rect width="108" height="108" rx="24" fill="${bg}"/>`);
  }
  paths.push(`<path d="${capsFg}" fill="${fg}"/>`);
  if (monoCutout) {
    paths.push(`<path d="${targetOuter} ${innerFrame}" fill="${fg}" fill-rule="evenodd"/>`);
    paths.push(`<path d="${dot}" fill="${fg}"/>`);
  } else {
    paths.push(`<path d="${targetOuter}" fill="${accent}"/>`);
    paths.push(`<path d="${dot}" fill="${bg || C.black}"/>`);
  }

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">${paths.join('')}</svg>\n`;
}

const files = {
  'task-logo-negativo.svg': renderTaskLogo({ bg: C.black, fg: C.white, accent: C.ice }),
  'task-logo-positivo.svg': renderTaskLogo({ bg: C.white, fg: C.black, accent: C.teal }),
  'task-logo-mono.svg': renderTaskLogo({ bg: null, fg: C.white, accent: C.white, monoCutout: true }),
};

for (const [name, content] of Object.entries(files)) {
  fs.writeFileSync(path.join(OUT, name), content);
}
console.log(Object.keys(files).join('\n'));
