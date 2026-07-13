use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashSet, HashMap};
use std::sync::RwLock;
use once_cell::sync::Lazy;
use chrono::{DateTime, Utc};
use crate::config::get_proxy_config;
use crate::auth::{
    AntigravityAccount, QuotaEntry, get_accounts, save_accounts_config,
    refresh_access_token, get_impersonation_headers_builder, generate_fingerprint_for_email, ensure_fingerprint
};

pub static SUPPORTED_MODELS_CACHE: Lazy<RwLock<HashSet<String>>> = Lazy::new(|| RwLock::new(HashSet::new()));

pub async fn fetch_quota(account: &AntigravityAccount, retry_enabled: bool) -> Result<Option<Vec<QuotaEntry>>, String> {
    let project_id = match &account.project_id {
        Some(p) => p,
        None => return Ok(None),
    };

    let config = get_proxy_config();
    let sandbox_endpoints = config.endpoints.sandbox;
    let url_str = sandbox_endpoints.first()
        .map(|s| s.as_str())
        .unwrap_or("https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:streamGenerateContent");

    let mut base_url = "https://daily-cloudcode-pa.sandbox.googleapis.com".to_string();
    if let Ok(parsed) = reqwest::Url::parse(url_str) {
        if let Some(host) = parsed.host_str() {
            let scheme = parsed.scheme();
            let port = parsed.port().map(|p| format!(":{}", p)).unwrap_or_default();
            base_url = format!("{}://{}{}", scheme, host, port);
        }
    }

    let fetch_url = format!("{}/v1internal:fetchAvailableModels", base_url);

    let fp = match &account.fingerprint {
        Some(f) => f.clone(),
        None => {
            let mut acc_mut = account.clone();
            ensure_fingerprint(&mut acc_mut);
            acc_mut.fingerprint.unwrap()
        }
    };

    let client = reqwest::Client::new();
    let mut current_access_token = account.access_token.clone().unwrap_or_default();
    let mut current_refresh_token = account.refresh_token.clone();
    
    let max_attempts = if retry_enabled { 2 } else { 1 };
    let mut attempts = 0;
    
    while attempts < max_attempts {
        attempts += 1;
        
        let headers = get_impersonation_headers_builder(&current_access_token, &fp, None);
        let payload = serde_json::json!({
            "project": project_id
        });

        let res = match client.post(&fetch_url)
            .headers(headers)
            .header("User-Agent", "antigravity")
            .json(&payload)
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => return Err(e.to_string()),
        };

        if res.status().as_u16() == 401 && attempts < max_attempts && !current_refresh_token.is_empty() {
            println!("Quota fetch 401 for {}, refreshing token...", account.email);
            match refresh_access_token(&current_refresh_token).await {
                Ok(tokens) => {
                    let now = Utc::now().timestamp_millis() as u64;
                    if let Some(rt) = tokens.refresh_token.clone() {
                        current_refresh_token = rt;
                    }
                    current_access_token = tokens.access_token.clone();
                    
                    // Update in storage
                    let accounts = get_accounts();
                    if let Some(acc_idx) = accounts.iter().position(|a| a.email == account.email) {
                        let mut acc_copy = accounts[acc_idx].clone();
                        if let Some(rt) = tokens.refresh_token {
                            acc_copy.refresh_token = rt;
                        }
                        acc_copy.access_token = Some(tokens.access_token);
                        acc_copy.expires_at = Some(now + (tokens.expires_in * 1000));
                        add_account_updated(acc_copy).await;
                    }
                    continue; // retry
                }
                Err(e) => {
                    return Err(format!("Token refresh failed during quota fetch retry: {}", e));
                }
            }
        }

        if !res.status().is_success() {
            return Err(format!("Quota fetch failed for {}: {}", account.email, res.status()));
        }

        let data = res.json::<Value>().await.map_err(|e| e.to_string())?;
        return Ok(parse_quota_response(&data));
    }
    
    Err("Exhausted quota fetch attempts".to_string())
}

// Helper to avoid circular dependency issues when updating manager from quota module
async fn add_account_updated(account: AntigravityAccount) {
    crate::auth::add_account(account).await;
}

fn get_pacific_offset(utc: DateTime<Utc>) -> chrono::FixedOffset {
    use chrono::{Datelike, TimeZone};
    let year = utc.year();
    
    // DST in US begins on the second Sunday of March.
    // Start checking from March 8th. The second Sunday will fall between March 8 and March 14.
    let mut march_second_sunday = 8;
    while march_second_sunday <= 14 {
        if let Some(date) = Utc.with_ymd_and_hms(year, 3, march_second_sunday, 12, 0, 0).single() {
            if date.weekday() == chrono::Weekday::Sun {
                break;
            }
        }
        march_second_sunday += 1;
    }
    
    // DST in US ends on the first Sunday of November.
    // Start checking from November 1st. The first Sunday will fall between November 1 and November 7.
    let mut nov_first_sunday = 1;
    while nov_first_sunday <= 7 {
        if let Some(date) = Utc.with_ymd_and_hms(year, 11, nov_first_sunday, 12, 0, 0).single() {
            if date.weekday() == chrono::Weekday::Sun {
                break;
            }
        }
        nov_first_sunday += 1;
    }

    let dst_start = Utc.with_ymd_and_hms(year, 3, march_second_sunday, 10, 0, 0).unwrap(); // 2:00 AM PST = 10:00 AM UTC
    let dst_end = Utc.with_ymd_and_hms(year, 11, nov_first_sunday, 9, 0, 0).unwrap(); // 2:00 AM PDT = 9:00 AM UTC

    if utc >= dst_start && utc < dst_end {
        chrono::FixedOffset::west_opt(7 * 3600).unwrap()
    } else {
        chrono::FixedOffset::west_opt(8 * 3600).unwrap()
    }
}

fn get_next_midnight_pt() -> String {
    use chrono::TimeZone;
    let now_utc = Utc::now();
    let offset = get_pacific_offset(now_utc);
    let now_pt = now_utc.with_timezone(&offset);
    
    // Set to 00:00:00 of next day
    let tomorrow_pt = (now_pt + chrono::Duration::days(1))
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .unwrap();
    
    // Get the tentative UTC time of tomorrow's midnight PT, then find the correct offset at that time
    let tentative_utc = offset.from_local_datetime(&tomorrow_pt).unwrap().with_timezone(&Utc);
    let correct_offset = get_pacific_offset(tentative_utc);
    let tomorrow_midnight_utc = correct_offset.from_local_datetime(&tomorrow_pt).unwrap().with_timezone(&Utc);
    
    tomorrow_midnight_utc.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}


fn parse_quota_response(data: &Value) -> Option<Vec<QuotaEntry>> {
    let raw_models = data.get("availableModels")
        .or_else(|| data.get("models"))
        .unwrap_or(&Value::Null);

    let mut entries = Vec::new();
    if let Some(arr) = raw_models.as_array() {
        for m in arr {
            let name = m.get("model").and_then(|model| model.get("name"))
                .or_else(|| m.get("displayName"))
                .or_else(|| m.get("displayMetadata").and_then(|dm| dm.get("label")))
                .and_then(|v| v.as_str())
                .unwrap_or("Unknown");
            
            entries.push((name.to_string(), m.clone()));
        }
    } else if let Some(obj) = raw_models.as_object() {
        for (key, val) in obj {
            entries.push((key.clone(), val.clone()));
        }
    }

    let mut groups: HashMap<String, QuotaGroupTemp> = HashMap::new();
    let allowed_patterns = vec!["Claude", "Anthropic", "GPT", "Gemini", "chat", "tab_flash", "MODEL_PLACEHOLDER"];

    for (key, m) in entries {
        let quota_info = match m.get("quotaInfo") {
            Some(q) => q,
            None => continue,
        };

        let label = m.get("displayMetadata").and_then(|dm| dm.get("label"))
            .or_else(|| m.get("displayName"))
            .or_else(|| m.get("model").and_then(|model| model.get("name")))
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown");

        let lower_label = label.to_lowercase();
        if label == "Unknown" || lower_label == "unknown" {
            continue;
        }

        // Cache model ID
        let model_id = key.replace("models/", "");
        if !model_id.is_empty() && model_id != "Unknown" && !model_id.contains(' ') {
            SUPPORTED_MODELS_CACHE.write().unwrap().insert(model_id);
        } else if label != "Unknown" {
            SUPPORTED_MODELS_CACHE.write().unwrap().insert(label.to_string());
        }

        let is_allowed = allowed_patterns.iter().any(|pattern| label.contains(pattern));
        if !is_allowed {
            continue;
        }

        let remaining_fraction = quota_info.get("remainingFraction").and_then(|v| v.as_f64()).unwrap_or(0.0);
        let limit_name = quota_info.get("limitName").and_then(|v| v.as_str()).unwrap_or(label).to_string();

        if let Some(group) = groups.get_mut(&limit_name) {
            if !group.labels.contains(&label.to_string()) {
                group.labels.push(label.to_string());
                group.labels.sort();
                group.group_name = group.labels.join(" / ");
            }
        } else {
            let mut reset_time = quota_info.get("quotaResetTime")
                .or_else(|| m.get("quotaResetTime"))
                .or_else(|| quota_info.get("resetTime"))
                .or_else(|| m.get("resetTime"))
                .or_else(|| quota_info.get("nextResetTime"))
                .or_else(|| m.get("nextResetTime"))
                .or_else(|| quota_info.get("quota_reset_time"))
                .or_else(|| m.get("quota_reset_time"))
                .cloned();

            let mut reset_time_str = None;

            if let Some(rt) = &reset_time {
                if let Some(n) = rt.as_i64() {
                    let mut millis = n;
                    if millis < 10000000000 {
                        millis *= 1000;
                    }
                    if let Some(dt) = DateTime::from_timestamp_millis(millis) {
                        reset_time_str = Some(dt.to_rfc3339_opts(chrono::SecondsFormat::Millis, true));
                    }
                } else if let Some(s) = rt.as_str() {
                    if let Some(sec_str) = s.strip_suffix('s') {
                        if s.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
                            if let Ok(sec) = sec_str.parse::<i64>() {
                                if let Some(dt) = DateTime::from_timestamp_millis(Utc::now().timestamp_millis() + sec * 1000) {
                                    reset_time_str = Some(dt.to_rfc3339_opts(chrono::SecondsFormat::Millis, true));
                                }
                            }
                        }
                    } else {
                        // try to parse ISO format or fallback
                        if let Ok(parsed) = DateTime::parse_from_rfc3339(s) {
                            reset_time_str = Some(parsed.with_timezone(&Utc).to_rfc3339_opts(chrono::SecondsFormat::Millis, true));
                        }
                    }
                }
            }

            if reset_time_str.is_none() {
                reset_time_str = Some(get_next_midnight_pt());
            }

            let r_str = reset_time_str.unwrap();
            let mut diff_ms = 0i64;
            if let Ok(parsed_dt) = DateTime::parse_from_rfc3339(&r_str) {
                diff_ms = std::cmp::max(0, parsed_dt.timestamp_millis() - Utc::now().timestamp_millis());
            }
            let hours = diff_ms / (1000 * 60 * 60);
            let minutes = (diff_ms % (1000 * 60 * 60)) / (1000 * 60);
            let reset_in = format!("{}h {}m", hours, minutes);

            let pct = (remaining_fraction * 100.0).round() as i32;
            let quota_left = format!("{}%", pct);

            groups.insert(limit_name.clone(), QuotaGroupTemp {
                group_name: label.to_string(),
                labels: vec![label.to_string()],
                limit: quota_info.get("quotaLimit").and_then(|v| v.as_str()).unwrap_or("Unknown").to_string(),
                usage: quota_info.get("quotaUsage").and_then(|v| v.as_str()).unwrap_or("Unknown").to_string(),
                limit_name,
                remaining_fraction,
                reset_time: Some(r_str),
                quota_left,
                reset_in
            });
        }
    }

    let mut results: Vec<QuotaEntry> = groups.into_values().map(|g| QuotaEntry {
        group_name: g.group_name,
        limit: g.limit,
        usage: g.usage,
        limit_name: g.limit_name,
        remaining_fraction: g.remaining_fraction,
        quota_left: g.quota_left,
        reset_in: g.reset_in,
        reset_time: g.reset_time,
    }).collect();

    results.sort_by(|a, b| a.group_name.cmp(&b.group_name));

    if results.is_empty() { None } else { Some(results) }
}

struct QuotaGroupTemp {
    group_name: String,
    labels: Vec<String>,
    limit: String,
    usage: String,
    limit_name: String,
    remaining_fraction: f64,
    reset_time: Option<String>,
    quota_left: String,
    reset_in: String,
}

pub async fn refresh_all_quotas() {
    let accounts = get_accounts();
    for acc in accounts {
        if acc.project_id.is_some() {
            match fetch_quota(&acc, true).await {
                Ok(Some(quota)) => {
                    // Update account with new quota
                    let accounts_current = get_accounts();
                    if let Some(acc_idx) = accounts_current.iter().position(|a| a.email == acc.email) {
                        let mut acc_copy = accounts_current[acc_idx].clone();
                        acc_copy.quota = Some(quota);
                        let _ = add_account_updated(acc_copy).await;
                    }
                }
                Ok(None) => {}
                Err(e) => {
                    eprintln!("Error refreshing quota for {}: {}", acc.email, e);
                }
            }
        }
    }
}
