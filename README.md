# BlossomCraft Item Database Mod

A client-side Fabric mod designed specifically for the **BlossomCraft SMP**. This mod connects directly to the server's database API to provide an in-game GUI for browsing, filtering, and previewing custom crates, miscellaneous items, tagged gear, and personal item collections.

**Note:** This mod is intended exclusively for players on the BlossomCraft server network. Its database endpoints and item indexing are built specifically for BlossomCraft's custom item ecosystem.

---

## Features

- **Item Collection Tracking:**
    - Mark items as owned or unowned directly from the card view or with a hotkey.
    - Dedicated **Collect** drawer tab to inspect your collection and track progress.
    - Visual status markers (`✔` / `•`) embedded alongside items in crate and misc listings.
- **Dynamic Thematic Gradients:**
    - Automatic color extraction and smooth gradient styling for Seasonal, Side, and Misc category headers.
    - Gradient-preserving truncation so long titles never lose their colors.
- **Direct API Synchronization:** Fetches live crate contents, miscellaneous items, tags, and group structures directly from the database backend.
- **Dynamic Texture Rendering:** Displays static PNGs and multi-frame animated GIFs centered and scaled for every custom item.
- **Explorer Drawer:** Expandable tree drawer organizing items into four distinct modes:
    - **Crates:** Seasonal, Side, and Misc crates.
    - **Misc:** Unsorted, Voucher, Event, and Furniture groupings.
    - **Tags:** Taxonomy explorer covering Tools, Weapons, Armor, Tool Features, and Misc Tags.
    - **Collect:** Filtered view showing your owned item collection.
- **Visual Feedback & Styling:** Items styled dynamically with custom server gradients, rarity colors, hover row highlights, and active selection pointers.
- **Instant Search:** Live `EditBox` filtering inside the drawer to quickly find items by name, tag, or database ID.
- **Offline Persistence:** Automatically saves downloaded textures to a local disk cache (`itemdatabase_cache/`) and maintains collection tracking locally to minimize network bandwidth.
- **Customizable Card View:** Freely drag, scale, and inspect item details, win chances, custom rarity tags, and lore.

---

## Controls & Keybinds

### Global Keybind
- **O** (Default): Open / Close the Item Database Screen.  
  *This is a registered Minecraft key mapping and can be rebound at any time via **Options → Controls → Key Binds**.*

### In-Screen Navigation Hotkeys
*(These controls are only active while the Item Database screen is currently open)*

| Key / Input | Action |
| :--- | :--- |
| **Space** | Toggle **Owned / Unowned** status for current item |
| **C** | Toggle the Explorer Drawer (Crates, Misc, Tags & Collect) |
| **Tab** | Quick-toggle Category between **Crates** and **Misc** |
| **A / D** *(or Left / Right Arrows)* | Navigate to Previous / Next item card |
| **Ctrl + Mouse Scroll** | Zoom / Scale the item card display |
| **Mouse Scroll** | Scroll through lore descriptions or drawer lists |
| **Left-Click + Drag** | Reposition / Pan the item card |
| **R** | Reset scale and card position to default |
| **Esc** | Unfocus search field / Exit screen |

---

## Requirements

- **Minecraft:** Fabric 26.1.2
- **Fabric Loader:** 0.19.5+
- **Fabric API**

---

## Installation

1. Download the latest `.jar` release from the [Releases](https://github.com/rolandgaymer013/ItemDatabaseMod/releases) tab (or from CurseForge).
2. Place the downloaded `.jar` into your Minecraft `.minecraft/mods` directory.
3. Launch Minecraft with your Fabric profile and press **O** in-game to open the database.

---

## 📚 Documentation
For complete guides, troubleshooting, and feature breakdowns, visit the **[Item Database Wiki](https://github.com/rolandgaymer013/ItemDatabaseMod/wiki)**:
* [Installation Guide](https://github.com/rolandgaymer013/ItemDatabaseMod/wiki/Installation-&-Requirements)
* [Controls & Shortcuts](https://github.com/rolandgaymer013/ItemDatabaseMod/wiki/Controls-&-Navigation)
* [Troubleshooting & Cache](https://github.com/rolandgaymer013/ItemDatabaseMod/wiki/Troubleshooting-&-Cache)
