use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::RwLock;
use once_cell::sync::Lazy;

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct ProxyConfig {
    pub rotation: RotationConfig,
    pub scoring: ScoringConfig,
    pub models: ModelsConfig,
    pub retry: RetryConfig,
    pub tokens: TokensConfig,
    pub quota: QuotaConfig,
    pub endpoints: EndpointsConfig,
    pub logging: LoggingConfig,
    pub features: FeaturesConfig,
    pub scheduling: SchedulingConfig,
    pub alerting: AlertingConfig,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct RotationConfig {
    pub strategy: String, // "hybrid" | "sticky" | "round-robin" | "random" | "least-used"
    pub cooldown: CooldownConfig,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct CooldownConfig {
    pub default_duration_ms: u64,
    pub max_duration_ms: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct ScoringConfig {
    pub health_range: HealthRangeConfig,
    pub penalties: PenaltiesConfig,
    pub rewards: RewardsConfig,
    pub weights: WeightsConfig,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct HealthRangeConfig {
    pub min: i32,
    pub max: i32,
    pub initial: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct PenaltiesConfig {
    pub api_error: i32,
    pub refresh_error: i32,
    pub fatal_error: i32,
    pub systemic_error: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct RewardsConfig {
    pub success: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct WeightsConfig {
    pub health: f64,
    pub lru: f64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct ModelsConfig {
    pub blacklist: Vec<String>,
    pub routing: RoutingConfig,
    pub timeouts: HashMap<String, u64>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct RoutingConfig {
    pub sandbox_keywords: Vec<String>,
    pub cli_keywords: Vec<String>,
    pub force_to_sandbox: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct RetryConfig {
    pub max_attempts: u32,
    pub transient_retry_threshold_seconds: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct TokensConfig {
    pub expiry_buffer_ms: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct QuotaConfig {
    pub refresh_interval_ms: u64,
    pub initial_delay_ms: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct EndpointsConfig {
    pub sandbox: Vec<String>,
    pub cli: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct LoggingConfig {
    pub max_buffer_size: usize,
    pub enable_console_capture: bool,
    pub disable_request_logging: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct FeaturesConfig {
    pub google_search_grounding: bool,
    pub expose_variants: bool,
    pub grounding_mode: String, // "auto" | "always"
    pub keep_thinking: bool,
    pub sanitize_tool_names: bool,
    pub pid_offset_enabled: bool,
    pub soft_quota_threshold_percent: u32,
    pub jitter_enabled: bool,
    pub jitter_min_ms: u64,
    pub jitter_max_ms: u64,
    pub default_project_id: Option<String>,
    pub sanitize_antigravity_prompts: bool,
    pub synthetic_search: bool,
    pub synthetic_search_model: String,
    pub intercept_search: bool,
    pub prioritize_search_over_tools: bool,
    pub obscure_models: bool,
    pub exact_request_caching: bool,
    pub prompt_caching: bool,
    pub fast_mode: bool,
    pub safeguard_empty_content: bool,
    pub safeguard_roles: bool,
    pub safeguard_schemas: bool,
    pub safeguard_context: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct SchedulingConfig {
    pub mode: String, // "cache_first" | "balance" | "performance_first"
    pub max_cache_first_wait_seconds: u64,
    pub max_rate_limit_wait_seconds: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct AlertingConfig {
    pub webhook_url: String,
    pub health_threshold: u32,
    pub notify_on_full_cooldown: bool,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        let mut timeouts = HashMap::new();
        timeouts.insert("default".to_string(), 30000);
        timeouts.insert("claude".to_string(), 60000);
        timeouts.insert("gemini-3-pro".to_string(), 45000);
        timeouts.insert("gemini-3.1-pro".to_string(), 45000);
        timeouts.insert("thinking".to_string(), 120000);

        ProxyConfig {
            rotation: RotationConfig {
                strategy: "hybrid".to_string(),
                cooldown: CooldownConfig {
                    default_duration_ms: 60000,
                    max_duration_ms: 3600000,
                },
            },
            scoring: ScoringConfig {
                health_range: HealthRangeConfig {
                    min: 0,
                    max: 100,
                    initial: 100,
                },
                penalties: PenaltiesConfig {
                    api_error: -10,
                    refresh_error: -20,
                    fatal_error: -50,
                    systemic_error: -5,
                },
                rewards: RewardsConfig { success: 1 },
                weights: WeightsConfig {
                    health: 2.0,
                    lru: 0.1,
                },
            },
            models: ModelsConfig {
                blacklist: Vec::new(),
                routing: RoutingConfig {
                    sandbox_keywords: vec!["gpt".to_string(), "antigravity".to_string(), "image".to_string()],
                    cli_keywords: vec!["claude".to_string(), "gemini-2.0".to_string(), "gemini-2.5".to_string(), "-preview".to_string()],
                    force_to_sandbox: vec!["gpt".to_string()],
                },
                timeouts,
            },
            retry: RetryConfig {
                max_attempts: 5,
                transient_retry_threshold_seconds: 5,
            },
            tokens: TokensConfig {
                expiry_buffer_ms: 60000,
            },
            quota: QuotaConfig {
                refresh_interval_ms: 300000,
                initial_delay_ms: 10000,
            },
            endpoints: EndpointsConfig {
                sandbox: vec![
                    "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse".to_string(),
                    "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse".to_string(),
                ],
                cli: vec![
                    "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse".to_string(),
                    "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse".to_string(),
                ],
            },
            logging: LoggingConfig {
                max_buffer_size: 200,
                enable_console_capture: true,
                disable_request_logging: false,
            },
            features: FeaturesConfig {
                google_search_grounding: true,
                expose_variants: false,
                grounding_mode: "auto".to_string(),
                keep_thinking: false,
                sanitize_tool_names: true,
                pid_offset_enabled: false,
                soft_quota_threshold_percent: 90,
                jitter_enabled: false,
                jitter_min_ms: 50,
                jitter_max_ms: 300,
                default_project_id: None,
                sanitize_antigravity_prompts: false,
                synthetic_search: false,
                synthetic_search_model: "gemini-2.5-flash".to_string(),
                intercept_search: false,
                prioritize_search_over_tools: false,
                obscure_models: false,
                exact_request_caching: false,
                prompt_caching: false,
                fast_mode: false,
                safeguard_empty_content: false,
                safeguard_roles: false,
                safeguard_schemas: false,
                safeguard_context: false,
            },
            scheduling: SchedulingConfig {
                mode: "balance".to_string(),
                max_cache_first_wait_seconds: 5,
                max_rate_limit_wait_seconds: 300,
            },
            alerting: AlertingConfig {
                webhook_url: "".to_string(),
                health_threshold: 30,
                notify_on_full_cooldown: true,
            },
        }
    }
}

// Implement defaults for all structs to support Partial / Default deserialization
impl Default for RotationConfig { fn default() -> Self { ProxyConfig::default().rotation } }
impl Default for CooldownConfig { fn default() -> Self { ProxyConfig::default().rotation.cooldown } }
impl Default for ScoringConfig { fn default() -> Self { ProxyConfig::default().scoring } }
impl Default for HealthRangeConfig { fn default() -> Self { ProxyConfig::default().scoring.health_range } }
impl Default for PenaltiesConfig { fn default() -> Self { ProxyConfig::default().scoring.penalties } }
impl Default for RewardsConfig { fn default() -> Self { ProxyConfig::default().scoring.rewards } }
impl Default for WeightsConfig { fn default() -> Self { ProxyConfig::default().scoring.weights } }
impl Default for ModelsConfig { fn default() -> Self { ProxyConfig::default().models } }
impl Default for RoutingConfig { fn default() -> Self { ProxyConfig::default().models.routing } }
impl Default for RetryConfig { fn default() -> Self { ProxyConfig::default().retry } }
impl Default for TokensConfig { fn default() -> Self { ProxyConfig::default().tokens } }
impl Default for QuotaConfig { fn default() -> Self { ProxyConfig::default().quota } }
impl Default for EndpointsConfig { fn default() -> Self { ProxyConfig::default().endpoints } }
impl Default for LoggingConfig { fn default() -> Self { ProxyConfig::default().logging } }
impl Default for FeaturesConfig { fn default() -> Self { ProxyConfig::default().features } }
impl Default for SchedulingConfig { fn default() -> Self { ProxyConfig::default().scheduling } }
impl Default for AlertingConfig { fn default() -> Self { ProxyConfig::default().alerting } }

static CONFIG: Lazy<RwLock<ProxyConfig>> = Lazy::new(|| RwLock::new(ProxyConfig::default()));

pub fn get_proxy_config() -> ProxyConfig {
    CONFIG.read().unwrap().clone()
}

pub fn get_effective_features() -> FeaturesConfig {
    let config = get_proxy_config();
    if config.features.fast_mode {
        FeaturesConfig {
            google_search_grounding: false,
            keep_thinking: false,
            synthetic_search: false,
            intercept_search: false,
            exact_request_caching: false,
            prompt_caching: false,
            safeguard_empty_content: false,
            safeguard_roles: false,
            safeguard_schemas: false,
            safeguard_context: false,
            sanitize_antigravity_prompts: true,
            ..config.features
        }
    } else {
        config.features
    }
}

pub fn load_proxy_config(path_str: &str) -> ProxyConfig {
    let path = Path::new(path_str);
    if path.exists() {
        if let Ok(content) = fs::read_to_string(path) {
            if let Ok(loaded) = serde_json::from_str::<ProxyConfig>(&content) {
                let mut config_write = CONFIG.write().unwrap();
                *config_write = loaded.clone();
                println!("[Config] Loaded configuration: strategy={}", config_write.rotation.strategy);
                return loaded;
            }
        }
    }
    
    // Save defaults if it doesn't exist or is invalid
    let default_config = ProxyConfig::default();
    let mut config_write = CONFIG.write().unwrap();
    *config_write = default_config.clone();
    let _ = save_proxy_config(path_str, &default_config);
    default_config
}

pub fn save_proxy_config(path_str: &str, config: &ProxyConfig) -> Result<(), std::io::Error> {
    let content = serde_json::to_string_pretty(config)?;
    fs::write(path_str, content)?;
    let mut config_write = CONFIG.write().unwrap();
    *config_write = config.clone();
    Ok(())
}

pub fn update_proxy_config(path_str: &str, updates: serde_json::Value) -> Result<ProxyConfig, String> {
    let current = serde_json::to_value(&get_proxy_config()).map_err(|e| e.to_string())?;
    let mut merged = current;
    merge_json(&mut merged, updates);
    let updated: ProxyConfig = serde_json::from_value(merged).map_err(|e| e.to_string())?;
    save_proxy_config(path_str, &updated).map_err(|e| e.to_string())?;
    Ok(updated)
}

fn merge_json(target: &mut serde_json::Value, source: serde_json::Value) {
    match (target, source) {
        (serde_json::Value::Object(target_map), serde_json::Value::Object(source_map)) => {
            for (key, val) in source_map {
                if val.is_null() {
                    target_map.remove(&key);
                } else {
                    merge_json(target_map.entry(key).or_insert(serde_json::Value::Null), val);
                }
            }
        }
        (target, source) => {
            *target = source;
        }
    }
}
