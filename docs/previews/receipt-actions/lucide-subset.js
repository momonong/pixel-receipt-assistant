/* Lucide 1.8.0 official icon nodes; see LUCIDE-LICENSE.txt.
 * Only the seven icons used by this preview are included.
 * The small createIcons adapter below replaces local data-lucide placeholders.
 */
(() => {
  const nodes = {
    inbox: [
      ['polyline', { points: '22 12 16 12 14 15 10 15 8 12 2 12' }],
      ['path', { d: 'M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z' }]
    ],
    'book-open': [
      ['path', { d: 'M12 7v14' }],
      ['path', { d: 'M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z' }]
    ],
    'circle-help': [
      ['circle', { cx: '12', cy: '12', r: '10' }],
      ['path', { d: 'M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3' }],
      ['path', { d: 'M12 17h.01' }]
    ],
    'ellipsis-vertical': [
      ['circle', { cx: '12', cy: '12', r: '1' }],
      ['circle', { cx: '12', cy: '5', r: '1' }],
      ['circle', { cx: '12', cy: '19', r: '1' }]
    ],
    'arrow-left': [
      ['path', { d: 'm12 19-7-7 7-7' }],
      ['path', { d: 'M19 12H5' }]
    ],
    'trash-2': [
      ['path', { d: 'M10 11v6' }],
      ['path', { d: 'M14 11v6' }],
      ['path', { d: 'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6' }],
      ['path', { d: 'M3 6h18' }],
      ['path', { d: 'M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2' }]
    ],
    'circle-slash': [
      ['circle', { cx: '12', cy: '12', r: '10' }],
      ['line', { x1: '9', x2: '15', y1: '15', y2: '9' }]
    ]
  };
  const namespace = 'http://www.w3.org/2000/svg';
  const createIcons = ({ attrs = {} } = {}) => {
    document.querySelectorAll('i[data-lucide]').forEach(placeholder => {
      const name = placeholder.dataset.lucide;
      if (!nodes[name]) throw new Error(`Preview icon is not bundled: ${name}`);
      const svg = document.createElementNS(namespace, 'svg');
      const attributes = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', ...attrs };
      for (const attribute of placeholder.attributes) {
        if (attribute.name !== 'data-lucide') svg.setAttribute(attribute.name, attribute.value);
      }
      for (const [key, value] of Object.entries(attributes)) svg.setAttribute(key, value);
      for (const [tag, values] of nodes[name]) {
        const element = document.createElementNS(namespace, tag);
        for (const [key, value] of Object.entries(values)) element.setAttribute(key, value);
        svg.append(element);
      }
      placeholder.replaceWith(svg);
    });
  };
  globalThis.lucide = { createIcons };
})();
