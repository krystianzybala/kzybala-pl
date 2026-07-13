# Design-token contract

Every lab MUST be styled exclusively from the tokens below (defined in
`assets/css/styles.css` under `:root` and `[data-theme="light"]`). No lab
may introduce a raw hex/rgb colour, a one-off radius, or a bespoke font
stack — new visual needs are met by adding a token here, not by hard-coding
a value in lab CSS.

## Colour

| Token | Purpose |
|---|---|
| `--bg`, `--bg-elevated` | Page background gradient stops. |
| `--surface`, `--surface-solid`, `--surface-2` | Panel/card backgrounds, in increasing opacity/solidity. |
| `--text` | Primary text. |
| `--muted` | Secondary text. |
| `--dim` | Tertiary/decorative text (e.g. arrows, timestamps). |
| `--line`, `--line-strong` | Hairline borders; `-strong` for focus/active/hover emphasis. |
| `--accent`, `--accent-strong`, `--accent-2` | Brand accent, its high-emphasis variant, and a secondary accent for gradients. |
| `--warning` | Non-fatal, needs-attention state. |
| `--danger` | Fatal/error state. |
| `--shadow` | Elevation shadow. |

Colour MUST NOT be the only carrier of meaning (see `docs/keyboard-rules.md`
and the accessibility baseline in `spec.md`) — pair every colour-coded state
with text or an icon.

## Shape and layout

| Token | Purpose |
|---|---|
| `--radius-sm`, `--radius`, `--radius-lg` | Corner radii, small to large. |
| `--max` | Max content width for header/main/footer. |
| `--reading` | Max width for prose blocks. |

## Type

| Token | Purpose |
|---|---|
| `--sans` | UI and prose font stack. |
| `--mono` | Code, metrics, labels, eyebrows. |

## Theming

Both colour-scheme blocks (`:root` for dark, `[data-theme="light"]` for
light) MUST define the same token set. `color-scheme` MUST be set on both so
native form controls and scrollbars follow the active theme. A lab MUST NOT
assume dark mode — verify visually in both themes before merging.
