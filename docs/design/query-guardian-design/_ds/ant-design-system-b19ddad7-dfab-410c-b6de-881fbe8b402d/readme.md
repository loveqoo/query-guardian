# Ant Design — Design System

A faithful, self-contained recreation of **Ant Design (antd)** — the enterprise-class
React UI language and component library from the Ant Group / AntV ecosystem. This
package gives a design agent the tokens, icons, components and full-screen UI kits
needed to produce interfaces that look and feel like real antd products.

> **Source of truth:** [github.com/ant-design/ant-design](https://github.com/ant-design/ant-design)
> (the antd component library),
> [github.com/ant-design/ant-design-icons](https://github.com/ant-design/ant-design-icons)
> (the official outlined icon set, MIT-licensed), and
> [github.com/ant-design/x](https://github.com/ant-design/x)
> (**Ant Design X** — the RICH-paradigm component library for building AI/LLM
> interfaces on top of antd). Token values are lifted directly
> from antd v5's theme generator (`components/theme/themes/seed.ts`,
> `default/colors.ts`, `shared/genFontSizes.ts`, etc.); the X components mirror
> `@ant-design/x`'s bubble / sender / welcome / prompts / conversations styles.
> Explore those repos to go deeper than this package captures.

---

## What Ant Design is

Ant Design is a **design language for enterprise / data-dense web applications** —
dashboards, admin consoles, CRMs, internal tools. Its north-star values are
*Certainty, Meaningfulness, Growth and Naturalness*. The aesthetic is calm,
neutral, and information-first: white surfaces, a single confident blue, restrained
use of color, tight 4px-based spacing, and a 14px base type size tuned for reading
tables and forms all day. It does **not** chase visual flash — no big gradients, no
playful illustration in the core UI, no oversized hero type. Hierarchy comes from
weight, spacing and a small set of greys, not decoration.

The flagship surfaces this system recreates:
- **Pro dashboard / admin layout** — the classic navy sider + white content layout
  used by antd Pro (the most recognizable antd screen).
- **Ant Design X AI chat** — a RICH-paradigm conversational interface (sidebar of
  conversations, welcome hero, streaming message bubbles, suggestion prompts, and
  the rounded sender composer).

### Ant Design X (the AI layer)

**Ant Design X** extends antd into AI product interfaces. Same neutral, token-driven
foundation, but tuned for conversation: softer **12px-rounded** surfaces, a faint
tertiary shadow on the composer, brand-blue accents at low alpha (tinted slots,
`rgba(22,119,255,.15)` buttons), filled/outlined/shadow/borderless message bubbles,
three-dot "thinking" loaders and a blinking typing cursor for streaming. It follows
the **RICH** interaction paradigm (Role, Intention, Conversation, Hybrid-UI). The X
primitives here are `Welcome`, `Bubble`/`BubbleList`, `Prompts`, `Sender`,
`Conversations`, and **`A2UISurface`** — a renderer for the **A2UI**
(Agent-to-User Interface) protocol, which lets an agent stream declarative JSON
that is rendered into native design-system components (generative UI) with
path-based data binding, two-way controls, an extensible component catalog, and
action events back to the agent.

---

## CONTENT FUNDAMENTALS

How antd writes UI copy:

- **Tone:** plain, professional, neutral. Functional over friendly. It states what
  a thing is or does without marketing flourish. "Please select", "Save", "Are you
  sure you want to delete this task?".
- **Voice / person:** mostly impersonal and imperative. Buttons are bare verbs
  (`Submit`, `Cancel`, `Delete`, `Add`). System messages address the user as "you"
  sparingly ("Your trial ends in 3 days"). Avoids "we".
- **Casing:** **Title Case for buttons and short labels** is common in the docs
  ("Add Item", "Basic Usage"), while sentence case is used for help text, table
  cells and descriptions. Menu items and column headers are usually Title Case or
  single capitalized words. Be consistent within a surface.
- **Punctuation:** no terminal periods on buttons, labels, tags, menu items or
  table headers. Full sentences in alerts, tooltips and descriptions do take
  periods. Confirmation dialogs end with a question mark ("Delete this file?").
- **Length:** terse. Labels are 1–3 words. Placeholder text describes the action
  ("Please select", "Search"). Empty states are short ("No data").
- **Emoji:** **none.** Ant Design's enterprise UI does not use emoji. Status and
  meaning are carried by icons + color, never emoji.
- **Numbers:** tabular figures are on by default (`font-variant: tabular-nums`) so
  columns of numbers align. Counts over a cap render as "99+".
- **Examples:** `Please select`, `No data`, `Are you sure you want to delete this
  task?`, `Saved successfully`, `Your trial ends in 3 days`, `Add`, `Search`,
  `Operation`, `Submit`.

---

## VISUAL FOUNDATIONS

**Color.** One brand blue — **`#1677ff`** (antd v5's primary; v4 was `#1890ff`).
Color is used sparingly and semantically: blue = primary/active/link, green
(`#52c41a`) = success, gold (`#faad14`) = warning, red (`#ff4d4f`) = error/danger.
Beyond those, a set of **13 named preset palettes** (blue, purple, cyan, green,
magenta, red, orange, yellow, volcano, geekblue, gold, lime — each a 1→10 scale)
is available for tags, charts and categorical data, but the core UI stays neutral.
Text is **black at decreasing alpha** over white — 88% primary, 65% secondary,
45% tertiary, 25% disabled — rather than fixed greys, so it composites correctly on
tinted surfaces. Backgrounds are flat: `#ffffff` containers on a `#f5f5f5` layout.

**Type.** **No webfont** — antd deliberately uses the **native system font stack**
(`-apple-system, "Segoe UI", Roboto, …`) so text matches each OS. Base size is
**14px** with a 1.5714 line-height; the scale runs 12 / 14 / 16 / 20 and headings
38 / 30 / 24 / 20 / 16. Weights are sparse: 400 normal, 500 medium (buttons,
active nav, emphasized headings), 600 strong. Code uses a mono stack
(`SFMono-Regular, Consolas, Menlo, …`).

**Spacing.** A **4px unit** with an 8-step rhythm (4 / 8 / 12 / 16 / 24 / 32 / 48).
Default component padding is 16px; default control height is **32px** (small 24,
large 40). Density is moderate-to-high — antd is built for screens that show a lot.

**Radius.** Small and consistent — a **6px** base on buttons, inputs and cards;
4px on tags and inner elements; 8px on larger cards and popovers; 2px hairline.
Nothing is pill-shaped except explicit `shape="round"`/`circle` controls.

**Borders.** Hairline **1px solid `#d9d9d9`** for controls, `#f0f0f0` for the
softer separators between sections and table rows. Dashed borders appear on
"add"-style buttons and dropzones. Focus is a 1px colored border **plus a 2px outer
glow ring** (`box-shadow: 0 0 0 2px rgba(5,145,255,.1)`), never a thick outline.

**Elevation / shadows.** A subtle three-layer system. Resting cards use an almost
imperceptible *tertiary* shadow (or just a border); dropdowns, popovers and modals
use the heavier multi-layer `box-shadow` (`0 6px 16px rgba(0,0,0,.08)` + two more
layers). Shadows are soft and low-contrast — elevation is felt, not seen. No hard
or colored drop shadows.

**Cards.** White surface, `#f0f0f0` 1px border, 8px radius, optional 56px header
with a bottom divider, 24px body padding, optional equal-width action footer row.
Hoverable cards drop the border and gain the default shadow on hover. No colored
left-border accent cards.

**Backgrounds.** Flat color only — `#ffffff` and `#f5f5f5`. **No gradients**, no
photographic hero backgrounds, no textures or patterns in the core UI. The one dark
surface is the **navy `#001529` sider** in the classic Pro layout.

**Animation.** Quick and functional. Durations 0.1s (fast) / 0.2s (mid) / 0.3s
(slow). Signature easings are the antd cubic-beziers — `ease-out-circ`
`cubic-bezier(.08,.82,.17,1)` for entrances, plus standard ease-in-out. Modals
zoom-and-fade in from 92% scale; dropdowns slide-fade; switches and checkboxes
animate their handle/checkmark. Motion is short and never bouncy in the core UI
(a gentle `ease-out-back` exists but is reserved). Respect reduced-motion.

**Hover / press states.**
- *Primary button:* hover → **lighter** blue (`#4096ff`), active → **darker**
  (`#0958d9`).
- *Default button:* hover → border + text shift to primary blue; active → darker
  primary.
- *Text/link:* hover → subtle grey fill (text) or lighter blue (link).
- *Menu/list rows:* hover → `rgba(0,0,0,.04)` fill; selected → primary-tinted
  `#e6f4ff` background with primary text.
- Press generally deepens the color rather than scaling the element.

**Transparency & blur.** Used lightly. Modal mask is `rgba(0,0,0,.45)`; tooltips
are `rgba(0,0,0,.85)` solid-ish spotlight. Fills (`rgba(0,0,0,.04–.15)`) provide
hover/zebra backgrounds that composite over any surface. Backdrop blur is **not** a
core antd motif.

**Imagery.** The core component UI is essentially image-free — it's a system for
data, forms and tables. When product/marketing imagery appears it is clean and
bright, but the design system itself relies on icons, type and color, not photos.

---

## ICONOGRAPHY

- **System:** Ant Design ships its own first-party icon set,
  **`@ant-design/icons`** — ~800 glyphs, each available in **outlined** (default),
  **filled**, and **two-tone** themes, drawn on a **1024×1024** viewBox.
- **Style:** clean, geometric, **2-unit-ish stroke** outlined glyphs that read well
  at 14–16px. Outlined is the default and dominant style across the UI; filled and
  two-tone are used for emphasis (e.g. status icons, active states).
- **Format:** real SVG paths, rendered inline so they inherit `currentColor` and
  scale with font-size. This package copies the **actual outlined SVGs** from
  `ant-design-icons` into [`assets/icons/`](assets/icons/) (62 of the most common)
  and exposes them through the **`Icon`** component (`<Icon name="search" />`). The
  raw path data is also bundled in [`assets/icons.js`](assets/icons.js).
- **Sizing:** icons default to `1em` so they track the surrounding text; inside
  buttons they sit 8px from the label.
- **Emoji / unicode:** **never** used as iconography. All glyphs come from the
  antd icon set.
- To pull more icons, import from
  `ant-design/ant-design-icons` → `packages/icons-svg/svg/outlined/<name>.svg` and
  drop them in `assets/icons/` (or extend `assets/icons.js`).

---

## INDEX — what's in this package

**Foundations**
- [`styles.css`](styles.css) — the single entry point consumers link; only `@import`s.
- [`tokens/colors.css`](tokens/colors.css) — 13 preset palettes, neutral scale, semantic aliases.
- [`tokens/typography.css`](tokens/typography.css) — font stacks, sizes, line-heights, weights.
- [`tokens/spacing.css`](tokens/spacing.css) — spacing, radius, control heights, shadows, motion, z-index.
- [`tokens/base.css`](tokens/base.css) — element resets / baseline.
- [`guidelines/`](guidelines/) — foundation specimen cards (Colors / Type / Spacing / Brand).

**Components** (`window.AntDesignSystem_b19dda.*`)
- Core: `Icon`
- Forms: `Button`, `Input`, `Select`, `Switch`, `Checkbox`, `Radio` / `RadioGroup`
- Data: `Card`, `Tag`, `Badge`, `Avatar` / `AvatarGroup`
- Feedback: `Alert`, `Modal`
- Navigation: `Menu`, `Tabs`, `Breadcrumb`
- **Ant Design X (AI):** `Welcome`, `Bubble` / `BubbleList`, `Prompts`, `Sender`, `Conversations`, `A2UISurface`
- Each component directory has `<Name>.jsx` + `.d.ts` + `.prompt.md` and a
  `@dsCard` demo HTML.

**UI kits**
- [`ui_kits/admin/`](ui_kits/admin/) — **my-agents management console** (building
  blocks browser, agent CRUD, live session monitoring) — interactive `index.html`.
- [`ui_kits/agent-debug/`](ui_kits/agent-debug/) — **my-agents debug console**
  (agent selector, chat, per-turn prompt/memory/MCP/LangGraph inspector).
- [`ui_kits/chat/`](ui_kits/chat/) — **Ant Design X** AI chat (conversations
  sidebar, welcome hero, streaming message bubbles, starter prompts, sender
  composer) — interactive `index.html` with fake streaming.

**Other**
- [`assets/`](assets/) — outlined icon SVGs + `icons.js` path data.
- [`SKILL.md`](SKILL.md) — Agent Skill manifest for use in Claude Code.

---

## Usage

Link the stylesheet and the compiled bundle, then read components off the namespace:

```html
<link rel="stylesheet" href="styles.css" />
<script src="_ds_bundle.js"></script>
<script type="text/babel">
  const { Button, Card, Input } = window.AntDesignSystem_b19dda;
</script>
```

All colors, spacing and radii reference CSS custom properties from `styles.css`, so
overriding a token (e.g. `--color-primary`) re-themes everything downstream.

---

## CAVEATS / SUBSTITUTIONS

- **Fonts:** none substituted — antd intentionally uses the native system stack, so
  there are no webfont binaries to ship. This is faithful to the real product.
- **Icons:** 62 common outlined icons are bundled; the full set is ~800. Pull more
  from `ant-design/ant-design-icons` as needed.
- **Components** are simplified, mostly-cosmetic recreations of antd's API surface
  (the real library has far more props, sub-components and edge cases). They are
  built for prototyping fidelity, not production parity.
