# LastChat Antigravity — Todo List

## 🐛 Bug Fixes

### 1. Remove leftover Antigravity entry from AI Studio provider screen
The Antigravity provider now appears as its own standalone card, but the old entry
nested inside the AI Studio / Google section still exists. Need to find and remove it.

**Files to check:** `SettingProviderDetailPage.kt`, `ProviderConfigure.kt`

---

### 2. Add disclaimer warning on Antigravity provider screen
Show a visible warning card on the Antigravity setup screen, e.g.:

> ⚠️ This provider uses an internal Google API not intended for third-party use.
> Your Google account may be rate-limited or suspended. This app is not endorsed
> by or affiliated with Google. Proceed with caution.

**Files to check:** `ProviderConfigure.kt` → `ProviderConfigureAntigravity()` composable

---

### 3. Verify post-login UI shows useful account info
After a successful OAuth login, the provider card should show:
- Logged-in email address
- Google Cloud Project ID
- Token expiry info
- A "Sign out" / "Re-authenticate" button

**Files to check:** `ProviderConfigure.kt` → `ProviderConfigureAntigravity()` and `AntigravityOAuthStatus.Success` handling

---

## 🔀 Sync / Merge

### 4. Upstream sync — pull the massive upstream commit from original LastChat repo
The original `Cocolalilal/LastChat` `LastChat_dev` branch has received a large new commit.
Need to merge it into this fork without losing the Antigravity provider work.

```bash
git remote add upstream https://github.com/Cocolalilal/LastChat.git
git fetch upstream
git merge upstream/LastChat_dev --no-ff
# resolve conflicts (especially ProviderSetting.kt, ProviderConfigure.kt,
# ProviderPresets.kt, build.gradle.kts)
git push origin LastChat_dev
```

> High conflict risk in files we modified heavily. Do carefully.
