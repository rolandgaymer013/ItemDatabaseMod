\# BlossomCraft Item Database Mod



A client-side Fabric mod designed specifically for the \*\*BlossomCraft SMP\*\*. This mod connects directly to the server's database API to provide an in-game GUI for browsing, filtering, and previewing custom crates and miscellaneous items.



> \*\*Note:\*\* This mod is intended exclusively for players on the BlossomCraft server network. Its database endpoints and item indexing are built specifically for BlossomCraft's custom item ecosystem.



\---



\## Features



\- \*\*Direct API Synchronization:\*\* Fetches live crate contents, miscellaneous items, and group structures directly from the database backend.

\- \*\*Dynamic Texture Rendering:\*\* Displays static PNGs and multi-frame animated GIFs centered and scaled for every custom item.

\- \*\*Explorer Drawer:\*\* Expandable tree drawer organizing items into tabs:

&#x20; - \*\*Crates:\*\* Seasonal, Side, and Misc crates.

&#x20; - \*\*Misc Items:\*\* Unsorted, Voucher, Event, and Furniture groupings.

\- \*\*Instant Search:\*\* Live `EditBox` filtering inside the drawer to quickly find items by name or database ID.

\- \*\*Offline Persistence:\*\* Automatically saves downloaded textures to a local disk cache (`itemdatabase\_cache/`) to minimize network bandwidth and prevent stutter.

\- \*\*Customizable Card View:\*\* Freely drag, scale, and inspect item details, win chances, custom rarity tags, and lore.



\---



\## Controls \& Keybinds



\### Global Keybind

\- \*\*`O`\*\* (Default): Open / Close the Item Database Screen.  

&#x20; \*This is a registered Minecraft key mapping and can be rebound at any time via \*\*Options → Controls → Key Binds\*\*.\*



\### In-Screen Navigation Hotkeys

\*(These controls are only active while the Item Database screen is currently open)\*



| Key / Input | Action |

| :--- | :--- |

| \*\*`C`\*\* | Toggle the Explorer Drawer (Crates \& Misc groups) |

| \*\*`Tab`\*\* | Switch Category between \*\*Crates\*\* and \*\*Misc Items\*\* |

| \*\*`A` / `D`\*\* \*(or Left/Right Arrows)\* | Navigate to Previous / Next item card |

| \*\*`Ctrl + Mouse Scroll`\*\* | Zoom / Scale the item card display |

| \*\*`Mouse Scroll`\*\* | Scroll through lore descriptions or drawer lists |

| \*\*`Left-Click + Drag`\*\* | Reposition / Pan the item card |

| \*\*`R`\*\* | Reset scale and card position to default |

| \*\*`Esc`\*\* | Unfocus search field / Exit screen |



\---



\## Requirements



\- \*\*Minecraft:\*\* Fabric 26.1.2

\- \*\*Fabric Loader:\*\* 0.19.5+

\- \*\*Fabric API\*\*



\---



\## Installation



1\. Download the latest `.jar` release from the \[Releases](https://github.com/rolandgaymer013/ItemDatabaseMod/releases) tab (or from Modrinth).

2\. Place the downloaded `.jar` into your Minecraft `.minecraft/mods` directory.

3\. Launch Minecraft with your Fabric profile and press \*\*`O`\*\* in-game to open the database.

