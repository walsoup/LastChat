use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::{Arc, RwLock};
use once_cell::sync::Lazy;
use rand::seq::SliceRandom;
use rand::Rng;
use sha2::{Sha256, Digest};
use base64::prelude::*;
use crate::config::{get_proxy_config, get_effective_features};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct DeviceFingerprint {
    #[serde(rename = "userAgent")]
    pub user_agent: String,
    #[serde(rename = "quotaUser")]
    pub quota_user: String,
    #[serde(rename = "deviceId")]
    pub device_id: String,
    pub platform: String,
    #[serde(rename = "apiClient")]
    pub api_client: String,
    #[serde(rename = "ideType")]
    pub ide_type: String,
    #[serde(rename = "platformName")]
    pub platform_name: String,
    #[serde(rename = "sessionToken")]
    pub session_token: String,
    #[serde(rename = "cliUserAgent")]
    pub cli_user_agent: String,
    #[serde(rename = "cliApiClient")]
    pub cli_api_client: String,
    #[serde(rename = "clientMetadata")]
    pub client_metadata: Option<ClientMetadata>,
    #[serde(rename = "createdAt")]
    pub created_at: Option<u64>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ClientMetadata {
    #[serde(rename = "ideType")]
    pub ide_type: String,
    pub platform: String,
    #[serde(rename = "pluginType")]
    pub plugin_type: String,
    #[serde(rename = "osVersion")]
    pub os_version: Option<String>,
    pub arch: Option<String>,
    #[serde(rename = "sqmId")]
    pub sqm_id: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct HistoryEntry {
    pub timestamp: u64,
    pub status: String, // "success" | "error"
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ChallengeEntry {
    #[serde(rename = "type")]
    pub challenge_type: String,
    pub url: String,
    #[serde(rename = "detectedAt")]
    pub detected_at: u64,
    pub reason: Option<String>,
    pub message: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct QuotaEntry {
    #[serde(rename = "groupName")]
    pub group_name: String,
    pub limit: String,
    pub usage: String,
    #[serde(rename = "limitName")]
    pub limit_name: String,
    #[serde(rename = "remainingFraction")]
    pub remaining_fraction: f64,
    #[serde(rename = "quotaLeft")]
    pub quota_left: String,
    #[serde(rename = "resetIn")]
    pub reset_in: String,
    #[serde(rename = "resetTime")]
    pub reset_time: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct AntigravityAccount {
    pub email: String,
    #[serde(rename = "refreshToken")]
    pub refresh_token: String,
    #[serde(rename = "accessToken")]
    pub access_token: Option<String>,
    #[serde(rename = "expiresAt")]
    pub expires_at: Option<u64>,
    #[serde(rename = "projectId")]
    pub project_id: Option<String>,
    #[serde(rename = "managedProjectId")]
    pub managed_project_id: Option<String>,

    #[serde(rename = "healthScore")]
    pub health_score: i32,
    #[serde(rename = "modelScores")]
    pub model_scores: Option<HashMap<String, i32>>,
    #[serde(rename = "lastUsed")]
    pub last_used: u64,
    #[serde(rename = "tokenUsage")]
    pub token_usage: u64,
    #[serde(rename = "consecutiveFailures")]
    pub consecutive_failures: Option<u32>,
    pub cooldowns: Option<HashMap<String, u64>>,
    pub history: Option<Vec<HistoryEntry>>,
    pub fingerprint: Option<DeviceFingerprint>,
    pub challenge: Option<ChallengeEntry>,
    pub capabilities: Option<HashMap<String, bool>>,
    pub quota: Option<Vec<QuotaEntry>>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct GoogleTokenResponse {
    pub access_token: String,
    pub expires_in: u64,
    pub scope: String,
    pub token_type: String,
    pub refresh_token: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct StorageFormat {
    accounts: Vec<AntigravityAccount>,
    strategy: Option<String>,
}

pub struct AuthState {
    pub accounts: Vec<AntigravityAccount>,
    pub strategy: String,
    pub last_account_index: isize,
    pub client_sticky_map: HashMap<String, String>,
    pub cooldown_map: HashMap<String, u64>,
}

static AUTH_STATE: Lazy<RwLock<AuthState>> = Lazy::new(|| RwLock::new(AuthState {
    accounts: Vec::new(),
    strategy: "hybrid".to_string(),
    last_account_index: -1,
    client_sticky_map: HashMap::new(),
    cooldown_map: HashMap::new(),
}));

static REFRESH_LOCKS: Lazy<tokio::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> =
    Lazy::new(|| tokio::sync::Mutex::new(HashMap::new()));

pub static EVENT_SENDER: Lazy<RwLock<Option<tokio::sync::broadcast::Sender<ManagerEvent>>>> =
    Lazy::new(|| RwLock::new(None));

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum ManagerEvent {
    #[serde(rename = "update")]
    Update {
        accounts: Vec<AntigravityAccount>,
        strategy: String,
    },
    #[serde(rename = "cooldown")]
    Cooldown {
        cooldowns: HashMap<String, u64>,
    },
    #[serde(rename = "flash")]
    Flash {
        email: String,
        status: String,
    },
    #[serde(rename = "log")]
    Log {
        message: String,
    },
}

pub fn emit_event(event: ManagerEvent) {
    if let Some(sender) = &*EVENT_SENDER.read().unwrap() {
        let _ = sender.send(event);
    }
}

pub fn emit_log(msg: String) {
    emit_event(ManagerEvent::Log { message: msg });
}

// Path to accounts file
fn get_accounts_file() -> String {
    std::env::var("ACCOUNTS_FILE")
        .unwrap_or_else(|_| {
            let pwd = std::env::current_dir().unwrap_or_default();
            pwd.join("antigravity-accounts.json").to_string_lossy().to_string()
        })
}

pub fn load_accounts_config() -> Result<(), String> {
    let file_path = get_accounts_file();
    let path = Path::new(&file_path);
    
    let mut state = AUTH_STATE.write().unwrap();
    
    if path.exists() {
        let content = fs::read_to_string(path).map_err(|e| e.to_string())?;
        // Try storage format first
        if let Ok(storage) = serde_json::from_str::<StorageFormat>(&content) {
            state.accounts = storage.accounts;
            state.strategy = storage.strategy.unwrap_or_else(|| "hybrid".to_string());
        } else if let Ok(accounts) = serde_json::from_str::<Vec<AntigravityAccount>>(&content) {
            // Old format
            state.accounts = accounts;
            state.strategy = "hybrid".to_string();
        } else {
            return Err("Failed to parse accounts JSON".to_string());
        }
    } else {
        state.accounts = Vec::new();
        state.strategy = "hybrid".to_string();
    }
    
    println!("[Manager] Loaded {} accounts from storage.", state.accounts.length_or_count());
    Ok(())
}

trait LengthOrCount {
    fn length_or_count(&self) -> usize;
}
impl<T> LengthOrCount for Vec<T> {
    fn length_or_count(&self) -> usize { self.len() }
}

pub fn save_accounts_config() -> Result<(), String> {
    let file_path = get_accounts_file();
    let state = AUTH_STATE.read().unwrap();
    let storage = StorageFormat {
        accounts: state.accounts.clone(),
        strategy: Some(state.strategy.clone()),
    };
    let content = serde_json::to_string_pretty(&storage).map_err(|e| e.to_string())?;
    fs::write(file_path, content).map_err(|e| e.to_string())?;
    
    // Emit update event
    emit_event(ManagerEvent::Update {
        accounts: state.accounts.clone(),
        strategy: state.strategy.clone(),
    });
    
    Ok(())
}

pub fn get_accounts() -> Vec<AntigravityAccount> {
    AUTH_STATE.read().unwrap().accounts.clone()
}

pub fn get_strategy() -> String {
    AUTH_STATE.read().unwrap().strategy.clone()
}

pub fn set_strategy(strategy: &str) {
    {
        let mut state = AUTH_STATE.write().unwrap();
        state.strategy = strategy.to_string();
    }
    let _ = save_accounts_config();
}

pub fn get_cooldowns() -> HashMap<String, u64> {
    AUTH_STATE.read().unwrap().cooldown_map.clone()
}

pub fn mark_cooldown(email: &str, pool: &str, model_family: &str, reset_time_str: Option<&str>) {
    let config = get_proxy_config();
    let mut base_duration = config.rotation.cooldown.default_duration_ms;

    if let Some(reset_str) = reset_time_str {
        if reset_str == "0s" {
            return;
        }
        base_duration = parse_duration(reset_str);
    }

    let now = chrono::Utc::now().timestamp_millis() as u64;
    let key = format!("{}|{}|{}", email, pool, model_family);

    let mut state = AUTH_STATE.write().unwrap();
    
    let consecutive = if let Some(acc) = state.accounts.iter().find(|a| a.email == email) {
        acc.consecutive_failures.unwrap_or(0)
    } else {
        0
    };

    let backoff_multiplier = 2u64.pow(std::cmp::min(consecutive as u32, 5));
    let expiry = now + std::cmp::min(base_duration * backoff_multiplier, config.rotation.cooldown.max_duration_ms);

    state.cooldown_map.insert(key.clone(), expiry);

    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        let mut cooldowns = acc.cooldowns.clone().unwrap_or_default();
        cooldowns.insert(format!("{}|{}", pool, model_family), expiry);
        acc.cooldowns = Some(cooldowns);
        acc.consecutive_failures = Some(consecutive + 1);
    }
    drop(state);

    let _ = save_accounts_config();
    emit_event(ManagerEvent::Cooldown {
        cooldowns: get_cooldowns(),
    });
}

pub fn clear_cooldown(email: &str, pool: &str, model_family: &str) {
    let key = format!("{}|{}|{}", email, pool, model_family);
    let mut state = AUTH_STATE.write().unwrap();
    if state.cooldown_map.remove(&key).is_some() {
        if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
            if let Some(cooldowns) = &mut acc.cooldowns {
                cooldowns.remove(&format!("{}|{}", pool, model_family));
            }
            acc.consecutive_failures = Some(0);
        }
        drop(state);
        let _ = save_accounts_config();
        emit_event(ManagerEvent::Cooldown {
            cooldowns: get_cooldowns(),
        });
    }
}

pub fn reset_all_cooldowns() {
    let mut state = AUTH_STATE.write().unwrap();
    state.cooldown_map.clear();
    for acc in &mut state.accounts {
        acc.cooldowns = Some(HashMap::new());
        acc.consecutive_failures = Some(0);
    }
    drop(state);
    let _ = save_accounts_config();
}

pub fn flag_account_challenge(email: &str, pool: &str, model_family: &str, challenge: serde_json::Value) {
    let now = chrono::Utc::now().timestamp_millis() as u64;
    let expiry = now + 3600000;
    let key = format!("{}|{}|{}", email, pool, model_family);

    let mut state = AUTH_STATE.write().unwrap();
    state.cooldown_map.insert(key, expiry);

    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        let mut cooldowns = acc.cooldowns.clone().unwrap_or_default();
        cooldowns.insert(format!("{}|{}", pool, model_family), expiry);
        acc.cooldowns = Some(cooldowns);

        let mut challenge_entry = ChallengeEntry {
            challenge_type: challenge.get("type").and_then(|v| v.as_str()).unwrap_or("CAPTCHA").to_string(),
            url: challenge.get("url").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            detected_at: now,
            reason: challenge.get("reason").and_then(|v| v.as_str()).map(|s| s.to_string()),
            message: challenge.get("message").and_then(|v| v.as_str()).map(|s| s.to_string()),
        };
        acc.challenge = Some(challenge_entry);
    }
    drop(state);

    let _ = save_accounts_config();
    emit_event(ManagerEvent::Cooldown {
        cooldowns: get_cooldowns(),
    });
}

pub fn flag_model_unsupported(email: &str, model: &str) {
    let mut state = AUTH_STATE.write().unwrap();
    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        let mut capabilities = acc.capabilities.clone().unwrap_or_default();
        capabilities.insert(model.to_string(), false);
        acc.capabilities = Some(capabilities);
        drop(state);
        let _ = save_accounts_config();
    }
}

pub fn clear_all_capabilities() {
    let mut state = AUTH_STATE.write().unwrap();
    for acc in &mut state.accounts {
        acc.capabilities = None;
    }
    drop(state);
    let _ = save_accounts_config();
    println!("[Manager] Cleared capabilities cache for all accounts.");
}

pub fn purge_system_state() {
    let mut state = AUTH_STATE.write().unwrap();
    state.client_sticky_map.clear();
    state.cooldown_map.clear();
    for acc in &mut state.accounts {
        acc.cooldowns = Some(HashMap::new());
        acc.model_scores = Some(HashMap::new());
        acc.consecutive_failures = Some(0);
        acc.token_usage = 0;
        acc.history = Some(Vec::new());
        acc.health_score = 100;
        acc.capabilities = None;
    }
    drop(state);
    let _ = save_accounts_config();
}

pub fn reset_account(email: &str) {
    let mut state = AUTH_STATE.write().unwrap();
    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        acc.health_score = 100;
        acc.consecutive_failures = Some(0);
        acc.cooldowns = Some(HashMap::new());
        acc.model_scores = Some(HashMap::new());
        acc.history = Some(Vec::new());
        acc.challenge = None;
    }
    // Remove from cooldown map keys starting with email + "|"
    let prefix = format!("{}|", email);
    state.cooldown_map.retain(|key, _| !key.starts_with(&prefix));
    drop(state);
    let _ = save_accounts_config();
}

pub fn update_account_project(email: &str, project_id: &str) {
    let mut state = AUTH_STATE.write().unwrap();
    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        acc.project_id = Some(project_id.to_string());
        acc.managed_project_id = Some(project_id.to_string());
        drop(state);
        let _ = save_accounts_config();
    }
}

pub async fn add_account(account: AntigravityAccount) {
    {
        let mut state = AUTH_STATE.write().unwrap();
        if let Some(existing) = state.accounts.iter_mut().find(|a| a.email == account.email) {
            *existing = account;
        } else {
            state.accounts.push(account);
        }
    }
    let _ = save_accounts_config();
}

pub async fn remove_account(email: &str) {
    {
        let mut state = AUTH_STATE.write().unwrap();
        state.accounts.retain(|a| a.email != email);
    }
    let _ = save_accounts_config();
}

pub fn emit_account_flash(email: &str, status: &str) {
    emit_event(ManagerEvent::Flash {
        email: email.to_string(),
        status: status.to_string(),
    });
}

pub fn get_family_name(model_name: &str) -> String {
    let n = model_name.to_lowercase();
    if n.contains("gemini") && n.contains("flash") && !n.contains("2.5") {
        "Gemini 3 Flash".to_string()
    } else if n.contains("gemini") && (n.contains("pro") || n.contains("image")) && !n.contains("2.5") {
        "Gemini 3 Pro".to_string()
    } else if n.contains("2.5") {
        "Gemini 2.5".to_string()
    } else if n.contains("claude") || n.contains("gpt") {
        "Claude/GPT".to_string()
    } else {
        "Other".to_string()
    }
}

fn is_account_quota_exhausted(account: &AntigravityAccount, model: Option<&str>) -> bool {
    let config = get_proxy_config();
    let threshold = config.features.soft_quota_threshold_percent;
    if threshold >= 100 {
        return false;
    }
    let quota = match &account.quota {
        Some(q) => q,
        None => return false,
    };
    if quota.is_empty() {
        return false;
    }

    let family = model.map(get_family_name);
    let relevant_quotas: Vec<&QuotaEntry> = if let Some(fam) = &family {
        quota.iter().filter(|q| {
            let q_lower = q.group_name.to_lowercase();
            let f_lower = fam.to_lowercase();
            q_lower.contains(&f_lower) || f_lower.contains(&q_lower) ||
            (f_lower.contains("claude") && q_lower.contains("claude")) ||
            (f_lower.contains("gemini") && q_lower.contains("gemini"))
        }).collect()
    } else {
        quota.iter().collect()
    };

    if relevant_quotas.is_empty() {
        return false;
    }

    let mut worst_used_percent = 0.0;
    for q in relevant_quotas {
        let used = (1.0 - q.remaining_fraction) * 100.0;
        if used > worst_used_percent {
            worst_used_percent = used;
        }
    }

    if worst_used_percent >= threshold as f64 {
        println!("[SoftQuota] Skipping {} for {}: {:.1}% used (threshold: {}%)",
            account.email, family.unwrap_or_else(|| "unknown".to_string()), worst_used_percent, threshold);
        return true;
    }
    false
}

fn get_pid_offset() -> usize {
    let config = get_proxy_config();
    if !config.features.pid_offset_enabled {
        return 0;
    }
    let pid = std::process::id() as usize;
    let count = get_accounts().len();
    if count == 0 {
        return 0;
    }
    pid % count
}

pub fn get_earliest_reset(pool: &str) -> Option<String> {
    let accounts = get_accounts();
    let usable: Vec<&AntigravityAccount> = accounts.iter().filter(|a| a.quota.is_some()).collect();
    if usable.is_empty() {
        return None;
    }
    let mut reset_times = Vec::new();
    for acc in usable {
        if let Some(quota) = &acc.quota {
            for q in quota {
                if let Some(reset_time) = &q.reset_time {
                    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(reset_time) {
                        reset_times.push(dt.timestamp_millis());
                    }
                }
            }
        }
    }
    if reset_times.is_empty() {
        return None;
    }
    let min_reset = *reset_times.iter().min().unwrap();
    let now = chrono::Utc::now().timestamp_millis();
    let diff_ms = std::cmp::max(0, min_reset - now);
    let hours = diff_ms / 3600000;
    let minutes = (diff_ms % 3600000) / 60000;
    Some(format!("{}h {}m", hours, minutes))
}

pub fn parse_duration(val: &str) -> u64 {
    let val = val.trim();
    if val.is_empty() {
        return 60000;
    }
    let unit = &val[val.len() - 1..];
    let amount_str = &val[0..val.len() - 1];
    if amount_str.chars().all(|c| c.is_ascii_digit()) {
        if let Ok(amount) = amount_str.parse::<u64>() {
            return match unit {
                "s" => amount * 1000,
                "m" => amount * 60000,
                "h" => amount * 3600000,
                _ => 60000,
            };
        }
    }
    60000
}

pub async fn update_account_usage(email: &str, success: bool, model: Option<&str>, pool: Option<&str>, client_id: Option<&str>, status: Option<u16>) {
    let mut state = AUTH_STATE.write().unwrap();
    if success {
        if let Some(cid) = client_id {
            state.client_sticky_map.insert(cid.to_string(), email.to_string());
        }
        if let (Some(p), Some(m)) = (pool, model) {
            let family = get_family_name(m);
            let key = format!("{}|{}|{}", email, p, family);
            state.cooldown_map.remove(&key);
        }
    }

    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        if success {
            if let (Some(p), Some(m)) = (pool, model) {
                let family = get_family_name(m);
                if let Some(cooldowns) = &mut acc.cooldowns {
                    cooldowns.remove(&format!("{}|{}", p, family));
                }
                acc.consecutive_failures = Some(0);
            }
        }
        acc.last_used = chrono::Utc::now().timestamp_millis() as u64;
        let delta = if success {
            2
        } else if status == Some(403) {
            -50
        } else {
            -10
        };
        acc.health_score = std::cmp::max(0, std::cmp::min(100, acc.health_score + delta));
        if let (Some(m), Some(p)) = (model, pool) {
            let mut scores = acc.model_scores.clone().unwrap_or_default();
            let key = format!("{}|{}", m, p);
            let current_score = scores.get(&key).copied().unwrap_or(100);
            scores.insert(key, std::cmp::max(0, std::cmp::min(100, current_score + delta)));
            acc.model_scores = Some(scores);
        }
    }
    drop(state);
    let _ = save_accounts_config();
}

fn calculate_priority(account: &AntigravityAccount, now: u64, model: Option<&str>, pool: Option<&str>) -> f64 {
    let config = get_proxy_config();
    let seconds_since_used = (now.saturating_sub(account.last_used)) as f64 / 1000.0;
    let mut health = account.health_score as f64;

    if let (Some(m), Some(p)) = (model, pool) {
        let family = get_family_name(m);
        let key = format!("{}|{}", m, p);
        let mut score = account.model_scores.as_ref().and_then(|s| s.get(&key)).copied().map(|v| v as f64);
        
        if score.is_none() {
            if let Some(scores) = &account.model_scores {
                let mut sum = 0.0;
                let mut count = 0;
                for (k, v) in scores {
                    let parts: Vec<&str> = k.split('|').collect();
                    if parts.len() >= 2 && parts[1] == p && get_family_name(parts[0]) == family {
                        sum += *v as f64;
                        count += 1;
                    }
                }
                if count > 0 {
                    score = Some(sum / count as f64);
                }
            }
        }
        if let Some(s) = score {
            health = s;
        }
    }

    (health * config.scoring.weights.health) + (seconds_since_used * config.scoring.weights.lru)
}

pub async fn get_best_account(
    pool: Option<&str>,
    model: Option<&str>,
    client_id: Option<&str>,
    exclude_emails: &[String],
    skip_rescue: bool,
) -> Option<AntigravityAccount> {
    let accounts = get_accounts();
    if accounts.is_empty() {
        return None;
    }
    let now = chrono::Utc::now().timestamp_millis() as u64;
    
    // Filter to usable accounts: having refresh token, no captcha challenge, and not excluded
    let usable: Vec<AntigravityAccount> = accounts.into_iter().filter(|a| {
        !a.refresh_token.is_empty() && a.challenge.is_none() && !exclude_emails.contains(&a.email)
    }).collect();

    if let Some(p) = pool {
        let family = model.map(get_family_name).unwrap_or_else(|| "Other".to_string());
        
        // Primary candidates: not blacklisted/unsupported model, not quota exhausted, and not on cooldown
        let mut candidates: Vec<AntigravityAccount> = usable.iter().filter(|a| {
            if let Some(m) = model {
                if let Some(caps) = &a.capabilities {
                    if caps.get(m) == Some(&false) {
                        return false;
                    }
                }
            }
            if is_account_quota_exhausted(a, model) {
                return false;
            }
            let key = format!("{}|{}|{}", a.email, p, family);
            let cooldown_map = get_cooldowns();
            if let Some(&expiry) = cooldown_map.get(&key) {
                if expiry > now {
                    return false;
                }
            }
            true
        }).cloned().collect();

        let scheduling_mode = get_proxy_config().scheduling.mode;
        let max_wait_sec = get_proxy_config().scheduling.max_cache_first_wait_seconds;
        
        if candidates.is_empty() && scheduling_mode == "cache_first" && client_id.is_some() {
            let cid = client_id.unwrap();
            let sticky_email = {
                let state = AUTH_STATE.read().unwrap();
                state.client_sticky_map.get(cid).cloned()
            };
            if let Some(email) = sticky_email {
                if !exclude_emails.contains(&email) {
                    if let Some(sticky_account) = usable.iter().find(|a| a.email == email) {
                        let key = format!("{}|{}|{}", email, p, family);
                        let cooldown_map = get_cooldowns();
                        if let Some(&expiry) = cooldown_map.get(&key) {
                            if expiry > now {
                                let wait_ms = expiry - now;
                                let max_wait_ms = max_wait_sec * 1000;
                                if wait_ms <= max_wait_ms {
                                    println!("[CacheFirst] Waiting {}s for {} to preserve prompt cache...", (wait_ms as f64 / 1000.0).ceil(), email);
                                    tokio::time::sleep(tokio::time::Duration::from_millis(wait_ms)).await;
                                    return ensure_account_ready(sticky_account.clone()).await;
                                }
                                println!("[CacheFirst] {} cooldown ({}s) exceeds max wait, switching account.", email, (wait_ms as f64 / 1000.0).ceil());
                            }
                        }
                    }
                }
            }
        }

        // Rescue mode: allow accounts whose cooldowns expire within next 5 minutes, sorted by earliest cooldown
        if candidates.is_empty() && !skip_rescue {
            candidates = usable.iter().filter(|a| {
                if let Some(m) = model {
                    if let Some(caps) = &a.capabilities {
                        if caps.get(m) == Some(&false) {
                            return false;
                        }
                    }
                }
                let key = format!("{}|{}|{}", a.email, p, family);
                let cooldown_map = get_cooldowns();
                if let Some(&expiry) = cooldown_map.get(&key) {
                    expiry <= now + 300000
                } else {
                    true
                }
            }).cloned().collect();

            candidates.sort_by(|a, b| {
                let key_a = format!("{}|{}|{}", a.email, p, family);
                let key_b = format!("{}|{}|{}", b.email, p, family);
                let cooldown_map = get_cooldowns();
                let exp_a = cooldown_map.get(&key_a).copied().unwrap_or(0);
                let exp_b = cooldown_map.get(&key_b).copied().unwrap_or(0);
                exp_a.cmp(&exp_b)
            });
        }

        if candidates.is_empty() {
            return None;
        }

        // If client_id matches a sticky account, use it
        if let Some(cid) = client_id {
            if exclude_emails.is_empty() {
                let sticky_email = {
                    let state = AUTH_STATE.read().unwrap();
                    state.client_sticky_map.get(cid).cloned()
                };
                if let Some(email) = sticky_email {
                    let key = format!("{}|{}|{}", email, p, family);
                    let cooldown_map = get_cooldowns();
                    let has_cooldown = cooldown_map.get(&key).map(|&exp| exp > now).unwrap_or(false);
                    if !has_cooldown {
                        if let Some(sticky) = candidates.iter().find(|a| a.email == email) {
                            return ensure_account_ready(sticky.clone()).await;
                        }
                    }
                }
            }
        }

        // Sort candidates based on priority
        candidates.sort_by(|a, b| {
            let prio_b = calculate_priority(b, now, model, Some(p));
            let prio_a = calculate_priority(a, now, model, Some(p));
            if (prio_a - prio_b).abs() < 0.1 {
                a.last_used.cmp(&b.last_used)
            } else {
                prio_b.partial_cmp(&prio_a).unwrap_or(std::cmp::Ordering::Equal)
            }
        });

        // Implement PID-based offset for account rotation
        let offset = get_pid_offset();
        let selected_index = offset % candidates.len();
        
        return ensure_account_ready(candidates[selected_index].clone()).await;
    }
    None
}

async fn ensure_account_ready(mut account: AntigravityAccount) -> Option<AntigravityAccount> {
    let now = chrono::Utc::now().timestamp_millis() as u64;
    let config = get_proxy_config();
    
    let needs_refresh = account.access_token.is_none() || 
        account.expires_at.map(|exp| exp < now + config.tokens.expiry_buffer_ms).unwrap_or(true);

    if needs_refresh {
        // Create or get lock for this email to avoid double-refreshing
        let mut registry = REFRESH_LOCKS.lock().await;
        let lock = registry.entry(account.email.clone())
            .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
            .clone();
        drop(registry);

        let _guard = lock.lock().await;

        // Double check after acquiring the lock
        let accounts = get_accounts();
        if let Some(current_acc) = accounts.iter().find(|a| a.email == account.email) {
            let still_needs_refresh = current_acc.access_token.is_none() || 
                current_acc.expires_at.map(|exp| exp < now + config.tokens.expiry_buffer_ms).unwrap_or(true);
            
            if !still_needs_refresh {
                return Some(current_acc.clone());
            }
        }

        // Perform refresh
        println!("[Manager] Refreshing access token for {}", account.email);
        match refresh_access_token(&account.refresh_token).await {
            Ok(tokens) => {
                let now = chrono::Utc::now().timestamp_millis() as u64;
                if let Some(rt) = tokens.refresh_token {
                    account.refresh_token = rt;
                }
                account.access_token = Some(tokens.access_token.clone());
                account.expires_at = Some(now + (tokens.expires_in * 1000));
                
                // Get Project ID if missing
                if account.project_id.is_none() {
                    match get_project_id(&tokens.access_token).await {
                        Ok(pid) => {
                            if !pid.is_empty() {
                                account.project_id = Some(pid);
                            }
                        }
                        Err(e) => {
                            eprintln!("[Manager] Project ID discovery failed for {}: {}", account.email, e);
                        }
                    }
                }

                if account.project_id.is_none() {
                    if let Some(default_pid) = &config.features.default_project_id {
                        account.project_id = Some(default_pid.clone());
                    }
                }

                if account.project_id.is_none() {
                    eprintln!("[Manager] No Google Cloud Project ID found for {}. Please configure features.defaultProjectId in config.json.", account.email);
                    // Fail refresh if no project ID
                    {
                        let mut state = AUTH_STATE.write().unwrap();
                        if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == account.email) {
                            acc.health_score = std::cmp::max(0, acc.health_score - 20);
                        }
                    }
                    let _ = save_accounts_config();
                    return None;
                }

                // Save updated account back to manager state
                {
                    let mut state = AUTH_STATE.write().unwrap();
                    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == account.email) {
                        acc.refresh_token = account.refresh_token.clone();
                        acc.access_token = account.access_token.clone();
                        acc.expires_at = account.expires_at;
                        acc.project_id = account.project_id.clone();
                    }
                }
                let _ = save_accounts_config();
                return Some(account);
            }
            Err(e) => {
                eprintln!("[Manager] Token refresh failed for {}: {}", account.email, e);
                {
                    let mut state = AUTH_STATE.write().unwrap();
                    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == account.email) {
                        acc.health_score = std::cmp::max(0, acc.health_score - 20);
                    }
                }
                let _ = save_accounts_config();
                return None;
            }
        }
    }

    Some(account)
}

pub fn ensure_fingerprint(account: &mut AntigravityAccount) -> bool {
    if account.fingerprint.is_none() || account.fingerprint.as_ref().unwrap().client_metadata.as_ref().and_then(|m| m.sqm_id.as_ref()).is_none() {
        account.fingerprint = Some(generate_fingerprint_for_email(Some(&account.email)));
        let email = account.email.clone();
        let fp = account.fingerprint.clone();
        let mut state = AUTH_STATE.write().unwrap();
        if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
            acc.fingerprint = fp;
            drop(state);
            let _ = save_accounts_config();
            return true;
        }
    }
    false
}

pub fn regenerate_fingerprint(email: &str) {
    let mut state = AUTH_STATE.write().unwrap();
    if let Some(acc) = state.accounts.iter_mut().find(|a| a.email == email) {
        acc.fingerprint = Some(generate_fingerprint_for_email(Some(email)));
        drop(state);
        let _ = save_accounts_config();
    }
}

// OAuth Client settings
pub struct OAuthConfig {
    pub client_id: String,
    pub client_secret: String,
    pub auth_uri: &'static str,
    pub token_uri: &'static str,
    pub scopes: &'static [&'static str],
    pub redirect_uri: &'static str,
}

pub static OAUTH_SETTINGS: Lazy<OAuthConfig> = Lazy::new(|| {
    let client_id = std::env::var("ANTIGRAVITY_CLIENT_ID").unwrap_or_default();
    let client_secret = std::env::var("ANTIGRAVITY_CLIENT_SECRET").unwrap_or_default();
    if client_id.is_empty() {
        eprintln!("[Auth Error] ANTIGRAVITY_CLIENT_ID environment variable is required");
        std::process::exit(1);
    }
    if client_secret.is_empty() {
        eprintln!("[Auth Error] ANTIGRAVITY_CLIENT_SECRET environment variable is required");
        std::process::exit(1);
    }

    OAuthConfig {
        client_id,
        client_secret,
        auth_uri: "https://accounts.google.com/o/oauth2/v2/auth",
        token_uri: "https://oauth2.googleapis.com/token",
        scopes: &[
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        ],
        redirect_uri: "http://localhost:3000/oauth-callback",
    }
});

pub fn generate_verifier() -> String {
    let mut rng = rand::thread_rng();
    let bytes: Vec<u8> = (0..32).map(|_| rng.r#gen::<u8>()).collect();
    hex::encode(bytes)
}

pub fn generate_auth_url(verifier: &str, dynamic_redirect_uri: Option<&str>) -> String {
    let mut hasher = Sha256::new();
    hasher.update(verifier.as_bytes());
    let hash = hasher.finalize();
    let challenge = BASE64_URL_SAFE_NO_PAD.encode(hash);

    let redirect = dynamic_redirect_uri.unwrap_or(OAUTH_SETTINGS.redirect_uri);
    let scopes_str = OAUTH_SETTINGS.scopes.join(" ");

    let params = vec![
        ("client_id", OAUTH_SETTINGS.client_id.as_str()),
        ("redirect_uri", redirect),
        ("response_type", "code"),
        ("scope", &scopes_str),
        ("access_type", "offline"),
        ("prompt", "consent"),
        ("code_challenge", &challenge),
        ("code_challenge_method", "S256"),
    ];

    let query = params
        .iter()
        .map(|(k, v)| format!("{}={}", k, urlencoding::encode(v)))
        .collect::<Vec<String>>()
        .join("&");

    format!("{}?{}", OAUTH_SETTINGS.auth_uri, query)
}

pub async fn exchange_code(code: &str, verifier: &str, dynamic_redirect_uri: Option<&str>) -> Result<GoogleTokenResponse, String> {
    let client = reqwest::Client::new();
    let redirect = dynamic_redirect_uri.unwrap_or(OAUTH_SETTINGS.redirect_uri);

    let params = [
        ("client_id", OAUTH_SETTINGS.client_id.as_str()),
        ("client_secret", OAUTH_SETTINGS.client_secret.as_str()),
        ("redirect_uri", redirect),
        ("grant_type", "authorization_code"),
        ("code", code),
        ("code_verifier", verifier),
    ];

    let res = client
        .post(OAUTH_SETTINGS.token_uri)
        .form(&params)
        .send()
        .await
        .map_err(|e| e.to_string())?;

    if !res.status().is_success() {
        let err_text = res.text().await.unwrap_or_default();
        return Err(format!("Token exchange failed: {}", err_text));
    }

    res.json::<GoogleTokenResponse>().await.map_err(|e| e.to_string())
}

pub async fn refresh_access_token(refresh_token: &str) -> Result<GoogleTokenResponse, String> {
    let client = reqwest::Client::new();
    let params = [
        ("client_id", OAUTH_SETTINGS.client_id.as_str()),
        ("client_secret", OAUTH_SETTINGS.client_secret.as_str()),
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
    ];

    let res = client
        .post(OAUTH_SETTINGS.token_uri)
        .form(&params)
        .send()
        .await
        .map_err(|e| e.to_string())?;

    if !res.status().is_success() {
        let err_text = res.text().await.unwrap_or_default();
        return Err(format!("Token refresh failed: {}", err_text));
    }

    res.json::<GoogleTokenResponse>().await.map_err(|e| e.to_string())
}

pub async fn get_project_id(access_token: &str) -> Result<String, String> {
    let client = reqwest::Client::new();
    let ide_types = vec!["VSCODE", "JETBRAINS", "CLOUD_SHELL", "IDE_UNSPECIFIED"];

    for ide_type in ide_types {
        let fp = generate_fingerprint_for_email(None);
        let headers = get_impersonation_headers_builder(access_token, &fp, None);
        
        let payload = serde_json::json!({
            "metadata": {
                "ideType": ide_type,
                "platform": "PLATFORM_UNSPECIFIED",
                "pluginType": "GEMINI"
            }
        });

        match client
            .post("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist")
            .headers(headers)
            .json(&payload)
            .send()
            .await
        {
            Ok(res) => {
                if res.status().is_success() {
                    if let Ok(data) = res.json::<serde_json::Value>().await {
                        if let Some(project) = data.get("cloudaicompanionProject") {
                            let pid = if let Some(pid_str) = project.as_str() {
                                pid_str.to_string()
                            } else if let Some(id) = project.get("id").and_then(|v| v.as_str()) {
                                id.to_string()
                            } else {
                                "".to_string()
                            };

                            if !pid.is_empty() {
                                println!("[OAuth] Discovered Project ID using {}: {}", ide_type, pid);
                                return Ok(pid);
                            }
                        }
                    }
                }
            }
            Err(e) => {
                eprintln!("[OAuth] loadCodeAssist error using {}: {}", ide_type, e);
            }
        }
    }

    Err("No Project ID found during loadCodeAssist calls".to_string())
}

pub async fn get_user_email(access_token: &str) -> Result<String, String> {
    let client = reqwest::Client::new();
    let res = client
        .get("https://www.googleapis.com/oauth2/v2/userinfo")
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|e| e.to_string())?;

    if !res.status().is_success() {
        return Err("Failed to fetch user info".to_string());
    }

    let data: serde_json::Value = res.json().await.map_err(|e| e.to_string())?;
    let email = data.get("email").and_then(|v| v.as_str()).ok_or("Email field not found")?;
    Ok(email.to_string())
}

// Fingerprint generation matching TS version
pub fn generate_fingerprint_for_email(email: Option<&str>) -> DeviceFingerprint {
    let platforms = vec!["darwin/x64", "darwin/arm64"];
    let archs = vec!["x64", "arm64"];
    let sdk_clients = vec![
        "google-cloud-sdk vscode/1.96.0",
        "google-cloud-sdk vscode/1.95.0",
    ];
    let gemini_cli_user_agents = vec![
        "google-api-nodejs-client/9.15.1",
        "google-api-nodejs-client/9.14.0",
        "google-api-nodejs-client/9.13.0",
        "google-api-nodejs-client/10.3.0",
    ];
    let gemini_cli_api_clients = vec![
        "gl-node/22.17.0",
        "gl-node/22.12.0",
        "gl-node/20.18.0",
        "gl-node/21.7.0",
        "gl-node/22.18.0",
    ];
    let os_versions = vec!["14.5", "15.0", "15.1", "15.2"];

    let mut rng = rand::thread_rng();
    let platform = platforms.choose(&mut rng).unwrap().to_string();
    let arch = if platform.contains("arm64") { "arm64" } else { "x64" }.to_string();
    let api_client = sdk_clients.choose(&mut rng).unwrap().to_string();
    let cli_user_agent = gemini_cli_user_agents.choose(&mut rng).unwrap().to_string();
    let cli_api_client = gemini_cli_api_clients.choose(&mut rng).unwrap().to_string();
    let os_version = os_versions.choose(&mut rng).unwrap().to_string();

    let quota_user = match email {
        Some(e) => {
            let mut hasher = Sha256::new();
            hasher.update(e.as_bytes());
            let hash = hex::encode(hasher.finalize());
            format!("device-{}", &hash[0..16])
        }
        None => {
            let rand_bytes: Vec<u8> = (0..8).map(|_| rng.r#gen::<u8>()).collect();
            format!("device-{}", hex::encode(rand_bytes))
        }
    };

    let device_id = quota_user.replace("device-", "");
    let session_token = {
        let rand_bytes: Vec<u8> = (0..16).map(|_| rng.r#gen::<u8>()).collect();
        hex::encode(rand_bytes)
    };

    DeviceFingerprint {
        user_agent: format!("antigravity/2.2.1 {}", platform),
        quota_user,
        device_id,
        platform,
        api_client,
        ide_type: "VSCODE".to_string(),
        platform_name: "MACOS".to_string(),
        session_token,
        cli_user_agent,
        cli_api_client,
        client_metadata: Some(ClientMetadata {
            ide_type: "VSCODE".to_string(),
            platform: "MACOS".to_string(),
            plugin_type: "GEMINI".to_string(),
            os_version: Some(os_version),
            arch: Some(arch),
            sqm_id: Some(crate::utils::generate_uuid_v4()),
        }),
        created_at: Some(chrono::Utc::now().timestamp_millis() as u64),
    }
}

// Building request headers matching the original TS getImpersonationHeaders
pub fn get_impersonation_headers_builder(
    access_token: &str,
    fingerprint: &DeviceFingerprint,
    model: Option<&str>,
) -> reqwest::header::HeaderMap {
    let mut headers = reqwest::header::HeaderMap::new();
    
    headers.insert(
        reqwest::header::AUTHORIZATION,
        reqwest::header::HeaderValue::from_str(&format!("Bearer {}", access_token)).unwrap(),
    );
    headers.insert(
        reqwest::header::CONTENT_TYPE,
        reqwest::header::HeaderValue::from_static("application/json"),
    );
    headers.insert(
        reqwest::header::USER_AGENT,
        reqwest::header::HeaderValue::from_static(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.2.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36"
        ),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-goog-api-client"),
        reqwest::header::HeaderValue::from_str(&fingerprint.api_client).unwrap(),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-goog-quotauser"),
        reqwest::header::HeaderValue::from_str(&fingerprint.quota_user).unwrap(),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-client-device-id"),
        reqwest::header::HeaderValue::from_str(&fingerprint.device_id).unwrap(),
    );

    let client_metadata_str = if let Some(m) = &fingerprint.client_metadata {
        serde_json::json!({
            "ideType": m.ide_type,
            "platform": m.platform,
            "pluginType": m.plugin_type,
            "osVersion": m.os_version,
            "arch": m.arch
        }).to_string()
    } else {
        r#"{"ideType":"VSCODE","platform":"MACOS","pluginType":"GEMINI","osVersion":"15.1","arch":"arm64"}"#.to_string()
    };

    headers.insert(
        reqwest::header::HeaderName::from_static("client-metadata"),
        reqwest::header::HeaderValue::from_str(&client_metadata_str).unwrap(),
    );

    if let Some(m) = model {
        let m_lower = m.to_lowercase();
        if m_lower.contains("claude") || m_lower.contains("anthropic") {
            headers.insert(
                reqwest::header::HeaderName::from_static("anthropic-beta"),
                reqwest::header::HeaderValue::from_static("interleaved-thinking-2025-05-14"),
            );
        }
    }

    headers
}

// Building request headers matching the original TS getGeminiCliHeaders
pub fn get_gemini_cli_headers_builder(
    access_token: &str,
    fingerprint: &DeviceFingerprint,
) -> reqwest::header::HeaderMap {
    let mut headers = reqwest::header::HeaderMap::new();

    headers.insert(
        reqwest::header::AUTHORIZATION,
        reqwest::header::HeaderValue::from_str(&format!("Bearer {}", access_token)).unwrap(),
    );
    headers.insert(
        reqwest::header::USER_AGENT,
        reqwest::header::HeaderValue::from_str(&fingerprint.cli_user_agent).unwrap(),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-goog-api-client"),
        reqwest::header::HeaderValue::from_str(&fingerprint.cli_api_client).unwrap(),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-goog-quotauser"),
        reqwest::header::HeaderValue::from_str(&fingerprint.quota_user).unwrap(),
    );
    headers.insert(
        reqwest::header::HeaderName::from_static("x-client-device-id"),
        reqwest::header::HeaderValue::from_str(&fingerprint.device_id).unwrap(),
    );
    headers.insert(
        reqwest::header::CONTENT_TYPE,
        reqwest::header::HeaderValue::from_static("application/json; charset=utf-8"),
    );

    let client_metadata_str = if let Some(m) = &fingerprint.client_metadata {
        let mut parts = Vec::new();
        parts.push(format!("ideType={}", m.ide_type));
        parts.push(format!("platform={}", m.platform));
        parts.push(format!("pluginType={}", m.plugin_type));
        if let Some(os) = &m.os_version {
            parts.push(format!("osVersion={}", os));
        }
        if let Some(a) = &m.arch {
            parts.push(format!("arch={}", a));
        }
        parts.join(",")
    } else {
        "ideType=VSCODE,platform=MACOS,pluginType=GEMINI,osVersion=14.5,arch=arm64".to_string()
    };

    headers.insert(
        reqwest::header::HeaderName::from_static("client-metadata"),
        reqwest::header::HeaderValue::from_str(&client_metadata_str).unwrap(),
    );

    headers
}
