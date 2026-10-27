# BlossomCraft Item Database Mod

A client-side Fabric mod designed specifically for the BlossomCraft SMP. This mod connects directly to the server's database API to provide an in-game GUI for browsing, filtering, and previewing custom crates, miscellaneous items, tagged gear, wishlist alerts, and personal item collections.

> **Note:** This mod is intended exclusively for players on the BlossomCraft server network. Its database endpoints and item indexing are built specifically for BlossomCraft's custom item ecosystem.

---

## ✨ Features

- **Item Collection & Wishlist Tracking:**
  - Mark items as owned (`Spacebar`) or wishlisted directly from the card view.
  - Dedicated **Collect** drawer tab to inspect your collection and track crate completion progress.
  - Visual status markers (`✔` / `•`) embedded alongside items in listings.
- **Wishlist Container Highlighting:**
  - Spot hunted items immediately inside chests, inventories, and `/ah`.
  - Highlights wishlisted slots with a pulsing golden backdrop, high-contrast double border, and a crisp corner diamond badge.
- **Live Crate Roll Alerts:**
  - Plays an audible chime and delivers a chat notification whenever another player rolls an item on your wishlist from crates.
  - Features interactive chat buttons to immediately view the item in your database or send a whisper to the winner.
- **Automatic Update Checker:**
  - Silently checks for new releases on server join and provides an in-chat alert with a one-click direct download link.
- **Dynamic Texture Rendering:**
  - Live texture streaming for static PNGs and multi-frame animated GIFs centered and scaled for custom items without frame drops.
- **Direct API Synchronization:**
  - Fetches live crate contents, miscellaneous items, tags, and category structures directly from the database backend.
- **Explorer Drawer (`C`):**
  - Expandable tree navigation covering **Crates** (Seasonal, Side, Misc), **Misc** (Unsorted, Voucher, Event, Furniture), **Tags** (Taxonomy explorer for Tools, Weapons, Armor, etc.), and **Collect**.
- **Thematic Gradients & Lore:**
  - Accurately renders raw JSON components, hex gradients, rarity colors, and hover pointers.
- **Instant Search:**
  - Live filtering inside the drawer to quickly find items by name, tag, or database ID.
- **Customizable Card View:**
  - Drag to reposition, scale dynamically (`Ctrl + Scroll`), and inspect win chances, lore, and custom rarity tags.
- **Offline Persistence:**
  - Caches textures locally (`itemdatabase_cache/`) and saves tracking data locally to minimize network bandwidth.

---

## 🎮 Controls & Commands

### Global Access
| Command / Key | Function |
| :--- | :--- |
| `/idb` | Open the Item Database screen via chat command |
| `O` *(Default)* | Toggle the Item Database screen *(Rebindable in Options → Controls → Key Binds)* |

### In-Screen Navigation Hotkeys
*(Active while the Item Database screen is open)*

| Key / Input | Action |
| :--- | :--- |
| `Space` | Toggle Owned / Unowned status for the current item |
| `C` | Toggle the Explorer Drawer (Crates, Misc, Tags & Collect) |
| `Tab` | Quick-toggle category between Crates and Misc |
| `A` / `D` *(or Arrow Keys)* | Navigate to Previous / Next item card |
| `Ctrl + Mouse Scroll` | Zoom / Scale the item card display |
| `Mouse Scroll` | Scroll through lore descriptions or drawer lists |
| `Left-Click + Drag` | Reposition / Pan the item card |
| `R` | Reset scale and card position to default |
| `Esc` | Unfocus search field / Exit screen |

---

## 📋 Requirements

- **Minecraft:** Fabric `26.1.2`
- **Fabric Loader:** `>= 0.19.5`
- **Fabric API**
- **Java:** `>= 25`

---

## 📥 Installation

1. Download the latest `.jar` release from the [Releases](https://github.com/rolandgaymer013/ItemDatabaseMod/releases) tab (or from CurseForge).
2. Place the downloaded `.jar` file into your `.minecraft/mods` directory.
3. Launch Minecraft with your Fabric profile and press `O` (or type `/idb`) in-game.

---

## 📚 Documentation

For complete guides, troubleshooting, and feature breakdowns, visit the [Item Database GitHub Wiki](https://github.com/rolandgaymer013/ItemDatabaseMod/wiki).