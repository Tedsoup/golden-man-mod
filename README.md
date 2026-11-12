# golden-man-mod

_A new way to experience Hypixel SkyBlock._

A client-side Fabric mod for Hypixel SkyBlock (Minecraft 26.2) that enforces the **Golden Man** challenge.

## Rules
- **Bazaar:** insta-sell only. No buy orders, no sell offers (those buttons are replaced with glass panes).
- **Bazaar buys:** only Booster Cookies, instant buy only.
- **Auction House:** completely off-limits.
- **Trading:** only with your co-op members.
- NPC shops and the Dark Auction are fine.

## Using it
- Press **G** (or type `/goldenman`) to open the Golden Man menu. Starting a run registers you for verification automatically; co-op trade partners are managed in the same menu.
- Golden Man must be started on a fresh profile below SkyBlock level 7.
- Turn on all API settings in SkyBlock (SkyBlock Menu → Settings → API Settings) before registering.

## Name colors (tab list and chat)
- **Pink:** tedsoup, the creator of the mod
- **Yellow:** verified Golden Man players (*italic* while verification is pending)
- ~~Strikethrough~~: players who failed verification

## Verification
`verification/verify.py` runs on GitHub Actions in the public list repo. It reads each registered profile from the Hypixel API and checks:
API settings are on, the profile was registered below level 7, the Auction House was never used, and every co-op member is registered.
Results are published as `players.json`, which the mod downloads to color names.

## Building
Open in IntelliJ IDEA with a JDK 25+ and run **Gradle → build → build**. The jar is written to `build/libs/`.
