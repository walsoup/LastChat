use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, VecDeque, HashSet};
use std::sync::Mutex;
use once_cell::sync::Lazy;
use std::hash::{Hash, Hasher};
use crate::config::{get_proxy_config, get_effective_features};

pub fn generate_uuid_v4() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let bytes: [u8; 16] = rng.r#gen();
    let b6 = (bytes[6] & 0x0f) | 0x40;
    let b8 = (bytes[8] & 0x3f) | 0x80;
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5],
        b6, bytes[7],
        b8, bytes[9],
        bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
    )
}

pub fn generate_random_hex_8() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let val: u32 = rng.r#gen();
    format!("{:08x}", val)
}

// --- Cache Implementation ---

pub struct LruCache<K, V> {
    map: HashMap<K, V>,
    queue: VecDeque<K>,
    max_size: usize,
}

impl<K: Eq + Hash + Clone, V: Clone> LruCache<K, V> {
    pub fn new(max_size: usize) -> Self {
        LruCache {
            map: HashMap::new(),
            queue: VecDeque::new(),
            max_size,
        }
    }

    pub fn insert(&mut self, key: K, value: V) {
        if self.map.insert(key.clone(), value).is_none() {
            self.queue.push_back(key);
        }
        if self.map.len() > self.max_size {
            if let Some(oldest) = self.queue.pop_front() {
                self.map.remove(&oldest);
            }
        }
    }

    pub fn get(&mut self, key: &K) -> Option<V> {
        let val = self.map.get(key).cloned();
        if val.is_some() {
            if let Some(pos) = self.queue.iter().position(|x| x == key) {
                self.queue.remove(pos);
                self.queue.push_back(key.clone());
            }
        }
        val
    }

    pub fn get_and_remove(&mut self, key: &K) -> Option<V> {
        let val = self.map.remove(key);
        if val.is_some() {
            if let Some(pos) = self.queue.iter().position(|x| x == key) {
                self.queue.remove(pos);
            }
        }
        val
    }
}

#[derive(Clone)]
pub struct CachedResponse {
    pub chunks: Vec<String>,
    pub timestamp: u64,
}

static SIGNATURE_CACHE: Lazy<Mutex<LruCache<String, String>>> = Lazy::new(|| Mutex::new(LruCache::new(1000)));
static CALL_ID_SIGNATURE_CACHE: Lazy<Mutex<LruCache<String, String>>> = Lazy::new(|| Mutex::new(LruCache::new(1000)));
static REQUEST_CACHE: Lazy<Mutex<LruCache<String, CachedResponse>>> = Lazy::new(|| Mutex::new(LruCache::new(500)));

pub fn hash_string(text: &str) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    text.hash(&mut hasher);
    hasher.finish()
}

pub fn cache_signature(conversation_id: &str, thought: &str, signature: &str) {
    let hash = hash_string(thought.trim());
    let key = format!("{}:{}", conversation_id, hash);
    SIGNATURE_CACHE.lock().unwrap().insert(key, signature.to_string());
}

pub fn get_signature(conversation_id: &str, thought: &str) -> Option<String> {
    let hash = hash_string(thought.trim());
    let key = format!("{}:{}", conversation_id, hash);
    SIGNATURE_CACHE.lock().unwrap().get_and_remove(&key)
}

pub fn cache_call_id_signature(call_id: &str, signature: &str) {
    CALL_ID_SIGNATURE_CACHE.lock().unwrap().insert(call_id.to_string(), signature.to_string());
}

pub fn get_call_id_signature(call_id: &str) -> Option<String> {
    CALL_ID_SIGNATURE_CACHE.lock().unwrap().get_and_remove(&call_id.to_string())
}

pub fn get_exact_cache(hash: &str) -> Option<CachedResponse> {
    REQUEST_CACHE.lock().unwrap().get(&hash.to_string())
}

pub fn set_exact_cache(hash: &str, chunks: Vec<String>) {
    let entry = CachedResponse {
        chunks,
        timestamp: chrono::Utc::now().timestamp_millis() as u64,
    };
    REQUEST_CACHE.lock().unwrap().insert(hash.to_string(), entry);
}

// --- Schema Cleaner ---

static UNSUPPORTED_SCHEMA_FIELDS: Lazy<HashSet<&'static str>> = Lazy::new(|| {
    let mut s = HashSet::new();
    s.insert("additionalProperties");
    s.insert("$schema");
    s.insert("$id");
    s.insert("$comment");
    s.insert("$ref");
    s.insert("$defs");
    s.insert("definitions");
    s.insert("const");
    s.insert("contentMediaType");
    s.insert("contentEncoding");
    s.insert("if");
    s.insert("then");
    s.insert("else");
    s.insert("not");
    s.insert("patternProperties");
    s.insert("unevaluatedProperties");
    s.insert("unevaluatedItems");
    s.insert("dependentRequired");
    s.insert("dependentSchemas");
    s.insert("propertyNames");
    s.insert("minContains");
    s.insert("maxContains");
    s
});

pub fn clean_json_schema_for_antigravity(schema: Value, aggressive: bool) -> Value {
    match schema {
        Value::Bool(b) => {
            if b {
                serde_json::json!({ "type": "STRING" })
            } else {
                serde_json::json!({ "type": "NULL" })
            }
        }
        Value::Object(mut map) => {
            if let Some(any_of) = map.get("anyOf").or(map.get("oneOf")).and_then(|v| v.as_array()) {
                let best = any_of.iter()
                    .find(|v| v.get("type").and_then(|t| t.as_str()) == Some("object"))
                    .unwrap_or(&any_of[0])
                    .clone();
                return clean_json_schema_for_antigravity(best, aggressive);
            }

            let mut result = serde_json::Map::new();

            let mut property_names = HashSet::new();
            if let Some(properties) = map.get("properties").and_then(|v| v.as_object()) {
                for prop_name in properties.keys() {
                    property_names.insert(prop_name.clone());
                }
            }

            for (key, val) in map {
                if UNSUPPORTED_SCHEMA_FIELDS.contains(key.as_str()) {
                    continue;
                }

                if key == "type" {
                    if let Some(s) = val.as_str() {
                        result.insert(key, Value::String(s.to_uppercase()));
                    } else if let Some(arr) = val.as_array() {
                        if !arr.is_empty() {
                            if let Some(s) = arr[0].as_str() {
                                result.insert(key, Value::String(s.to_uppercase()));
                            }
                        }
                    }
                } else if key == "properties" {
                    if let Some(obj) = val.as_object() {
                        if !obj.is_empty() {
                            let mut props = serde_json::Map::new();
                            for (prop_name, prop_schema) in obj {
                                props.insert(prop_name.clone(), clean_json_schema_for_antigravity(prop_schema.clone(), aggressive));
                            }
                            result.insert(key, Value::Object(props));
                        } else {
                            result.insert(key, serde_json::json!({
                                "_placeholder": {
                                    "type": "BOOLEAN",
                                    "description": "Technical placeholder to ensure non-empty schema"
                                }
                            }));
                        }
                    }
                } else if key == "items" {
                    if let Some(arr) = val.as_array() {
                        if !arr.is_empty() {
                            result.insert(key, clean_json_schema_for_antigravity(arr[0].clone(), aggressive));
                        } else {
                            result.insert(key, serde_json::json!({ "type": "STRING" }));
                        }
                    } else {
                        result.insert(key, clean_json_schema_for_antigravity(val, aggressive));
                    }
                } else if key == "required" {
                    if let Some(arr) = val.as_array() {
                        if !property_names.is_empty() {
                            let valid: Vec<Value> = arr.iter()
                                .filter(|v| v.as_str().map(|s| property_names.contains(s)).unwrap_or(false))
                                .cloned()
                                .collect();
                            if !valid.is_empty() {
                                result.insert(key, Value::Array(valid));
                            }
                        } else if result.get("properties").and_then(|p| p.get("_placeholder")).is_some() {
                            result.insert(key, serde_json::json!(["_placeholder"]));
                        }
                    }
                } else if key == "description" {
                    if !aggressive {
                        result.insert(key, val);
                    }
                } else if key == "enum" || key == "format" || key == "default" || key == "examples" {
                    result.insert(key, val);
                }
            }

            if result.get("type").and_then(|t| t.as_str()) == Some("ARRAY") && result.get("items").is_none() {
                result.insert("items".to_string(), serde_json::json!({ "type": "STRING" }));
            }

            if result.get("type").is_none() && result.contains_key("properties") {
                result.insert("type".to_string(), Value::String("OBJECT".to_string()));
            }

            Value::Object(result)
        }
        _ => schema,
    }
}

// --- Error Parser ---

#[derive(Debug)]
pub struct ParsedGoogleError {
    pub reason: String,
    pub validation_url: Option<String>,
    pub is_quota_exhausted: bool,
    pub is_challenge_required: bool,
    pub is_model_unsupported: bool,
    pub status: u16,
    pub message: Option<String>,
}

pub fn parse_google_error(body: &str) -> ParsedGoogleError {
    let mut reason = "unknown_error".to_string();
    let mut validation_url = None;
    let mut is_quota_exhausted = false;
    let mut is_challenge_required = false;
    let mut is_model_unsupported = false;
    let mut status = 500u16;
    let mut message = None;

    if let Ok(json) = serde_json::from_str::<Value>(body) {
        let err_val = if json.is_array() {
            json.get(0).and_then(|v| v.get("error"))
        } else {
            json.get("error")
        };

        if let Some(err) = err_val {
            message = err.get("message").and_then(|v| v.as_str()).map(|s| s.to_string());
            let err_status = err.get("status").and_then(|v| v.as_str()).unwrap_or("");
            let msg_str = message.as_ref().map(|s| s.as_str()).unwrap_or("");

            if err_status == "RESOURCE_EXHAUSTED" || msg_str.contains("quota") {
                is_quota_exhausted = true;
                reason = "quota_exhausted".to_string();
                status = 429;
            }

            if msg_str.contains("VALIDATION_REQUIRED") {
                is_challenge_required = true;
                reason = "validation_required".to_string();
                status = 403;
            }

            if msg_str.contains("Gemini Code Assist license") || msg_str.contains("SUBSCRIPTION_REQUIRED") {
                is_challenge_required = true;
                reason = "subscription_required".to_string();
                status = 403;
            }

            if err_status == "NOT_FOUND" || msg_str.contains("not found") || msg_str.contains("not supported") {
                is_model_unsupported = true;
                reason = "model_not_found".to_string();
                status = 404;
            }

            if let Some(details) = err.get("details").and_then(|v| v.as_array()) {
                for detail in details {
                    let r = detail.get("reason").and_then(|v| v.as_str())
                        .or_else(|| detail.get("errorInfo").and_then(|e| e.get("reason")).and_then(|v| v.as_str()))
                        .unwrap_or("");

                    if r == "VALIDATION_REQUIRED" {
                        is_challenge_required = true;
                        reason = "validation_required".to_string();
                        status = 403;
                        if let Some(url) = detail.get("validation_url").and_then(|v| v.as_str()) {
                            validation_url = Some(url.to_string());
                        }
                        if let Some(url) = detail.get("metadata").and_then(|m| m.get("validation_url")).and_then(|v| v.as_str()) {
                            validation_url = Some(url.to_string());
                        }
                    }

                    if r == "RATE_LIMIT_EXCEEDED" {
                        is_quota_exhausted = true;
                        reason = "quota_exhausted".to_string();
                        status = 429;
                    }
                }
            }
        }
    } else if body.contains("automated queries") {
        is_quota_exhausted = true;
        reason = "quota_exhausted".to_string();
        status = 429;
    }

    ParsedGoogleError {
        reason,
        validation_url,
        is_quota_exhausted,
        is_challenge_required,
        is_model_unsupported,
        status,
        message,
    }
}

// --- Remap Cache & Function Name sanitizing ---

static TOOL_NAME_REMAP_CACHE: Lazy<Mutex<HashMap<String, String>>> = Lazy::new(|| Mutex::new(HashMap::new()));

fn sanitize_function_name(name: &str) -> String {
    let is_valid = !name.is_empty() && {
        let mut chars = name.chars();
        let first = chars.next().unwrap();
        (first.is_ascii_alphabetic() || first == '_') && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
    };
    if is_valid {
        return name.to_string();
    }

    let mut cache = TOOL_NAME_REMAP_CACHE.lock().unwrap();
    if let Some(s) = cache.get(name) {
        return s.clone();
    }

    let mut sanitized = name.replace(|c: char| !c.is_ascii_alphanumeric() && c != '_', "_");
    if sanitized.starts_with(|c: char| c.is_ascii_digit()) {
        sanitized = format!("fn_{}", sanitized);
    }
    if sanitized.is_empty() {
        let rand_val: u32 = rand::random();
        sanitized = format!("fn_{:x}", rand_val);
    }

    cache.insert(name.to_string(), sanitized.clone());
    println!("[Sanitize] Renamed tool \"{}\" → \"{}\"", name, sanitized);
    sanitized
}

pub fn get_original_tool_name(sanitized_name: &str) -> Option<String> {
    let cache = TOOL_NAME_REMAP_CACHE.lock().unwrap();
    for (k, v) in cache.iter() {
        if v == sanitized_name {
            return Some(k.clone());
        }
    }
    None
}

// --- Model Resolving & Target Request Payload transform ---

static CLAUDE_MODEL_REGISTRY: &[&str] = &[
    "claude-3-7-sonnet-20250219",
    "claude-3-5-sonnet-20241022",
    "claude-3-5-sonnet-v2-20241022",
    "claude-3-5-sonnet-20240620",
    "claude-3-5-haiku-20241022",
    "claude-3-opus-20240229",
    "claude-opus-4-6-thinking",
    "claude-sonnet-4-6",
    "claude-sonnet-4-6-thinking",
    "claude-3-sonnet-20240229",
    "claude-3-haiku-20240307",
];

fn resolve_model_id(model_id: &str) -> String {
    let mut clean_id = model_id.to_lowercase()
        .replace("openai/", "")
        .replace("antigravity/", "")
        .replace("custom_openai/", "")
        .replace("litellm/", "")
        .replace("google/", "");
    
    clean_id = clean_id.replace("antigravity-", "");
    clean_id = clean_id.replace("gemini-claude-", "claude-");

    if clean_id.contains("claude") {
        if let Some(&exact) = CLAUDE_MODEL_REGISTRY.iter().find(|&&m| m == clean_id) {
            return exact.to_string();
        }

        let base_id = clean_id.replace("-thinking", "")
            .replace("-preview", "")
            .replace("-low", "")
            .replace("-medium", "")
            .replace("-high", "");

        let mut fuzzy: Vec<String> = CLAUDE_MODEL_REGISTRY.iter()
            .filter(|&&m| m.starts_with(&clean_id) || m.starts_with(&base_id) || clean_id.starts_with(m))
            .map(|&s| s.to_string())
            .collect();

        if !fuzzy.is_empty() {
            fuzzy.sort();
            fuzzy.reverse();
            return fuzzy[0].clone();
        }
    }

    clean_id
}

fn remove_base64_images(text: &str) -> String {
    let mut result = String::new();
    let mut current = text;
    while let Some(start_alt) = current.find("![") {
        result.push_str(&current[..start_alt]);
        let search = &current[start_alt..];
        if let Some(end_alt) = search.find("](") {
            let url_start = &search[end_alt + 2..];
            if url_start.starts_with("data:image/") {
                if let Some(end_url) = url_start.find(')') {
                    let url_content = &url_start[..end_url];
                    if url_content.contains(";base64,") {
                        result.push_str("[Image Removed]");
                        current = &url_start[end_url + 1..];
                        continue;
                    }
                }
            }
        }
        result.push_str("![");
        current = &search[2..];
    }
    result.push_str(current);
    result
}

fn parse_data_url(url: &str) -> Option<(String, String)> {
    if !url.starts_with("data:") {
        return None;
    }
    let url = &url["data:".len()..];
    let (metadata, data) = url.split_once(',')?;
    let mut parts = metadata.split(';');
    let mime_type = parts.next()?.to_string();
    Some((mime_type, data.to_string()))
}

pub fn transform_to_google_body(
    openai_body: &Value,
    project_id: &str,
    is_cli: bool,
    session_id: Option<&str>,
    aggressive: bool,
) -> Value {
    let proxy_config = get_proxy_config();
    let features = get_effective_features();
    
    let raw_model = openai_body.get("model").and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
    let resolved_model = resolve_model_id(&raw_model);
    let mut google_model = resolved_model.clone();

    // Check for thinking effort/tier suffix
    let tier_match = if raw_model.ends_with("-low") { Some("low") }
        else if raw_model.ends_with("-medium") { Some("medium") }
        else if raw_model.ends_with("-high") { Some("high") }
        else if raw_model.ends_with("-xhigh") { Some("xhigh") }
        else { None };

    let extracted_tier = tier_match;
    let mut base_model = google_model.clone();
    if let Some(t) = tier_match {
        let suffix = format!("-{}", t);
        base_model = base_model.replace(&suffix, "");
        // also replace -thinking- suffix if present
        base_model = base_model.replace(&format!("-thinking-{}", t), "");
    }
    
    base_model = base_model.replace("-preview", "");

    if google_model.contains("claude") {
        google_model = base_model.clone();
        if google_model == "claude-opus-4-6" {
            google_model = "claude-opus-4-6-thinking".to_string();
        }
        if google_model == "claude-sonnet-4-6-thinking" || google_model.contains("claude-3-7-sonnet") || google_model.contains("claude-3.7-sonnet") {
            google_model = "claude-sonnet-4-6".to_string();
        }
        if google_model == "claude-sonnet-4-5" {
            google_model = "claude-sonnet-4-5-thinking".to_string();
        }
    }

    let mut adaptive_tier = extracted_tier.map(|s| s.to_string());
    if let Some(eff) = openai_body.get("reasoning_effort").and_then(|v| v.as_str()) {
        adaptive_tier = Some(eff.to_lowercase());
    }

    if is_cli {
        if !google_model.contains("claude") {
            if google_model.contains("gpt") {
                if google_model.contains("thinking") {
                    google_model = "gemini-2.0-flash-thinking-exp".to_string();
                } else {
                    google_model = "gemini-2.0-pro-exp".to_string();
                }
            } else if google_model.contains("gemini-3") && google_model.contains("thinking") {
                google_model = "gemini-3-flash-preview".to_string();
            } else {
                google_model = base_model;
            }
        } else {
            google_model = base_model;
            if google_model == "claude-sonnet-4-6-thinking" || google_model.contains("claude-3-7-sonnet") || google_model.contains("claude-3.7-sonnet") {
                google_model = "claude-sonnet-4-6".to_string();
            }
        }
    } else {
        google_model = google_model.replace("-preview", "");
        if base_model.contains("gemini-3.1-pro") {
            let tier = adaptive_tier.as_deref().unwrap_or("low");
            if tier == "xhigh" || tier == "high" {
                google_model = "gemini-pro-agent".to_string();
            } else {
                google_model = "gemini-3.1-pro-low".to_string();
            }
        } else if base_model.contains("gemini-3-pro") {
            google_model = "gemini-3-pro".to_string();
        } else if base_model.contains("gemini-3.5-flash") {
            let tier = adaptive_tier.as_deref().unwrap_or("low");
            if tier == "xhigh" || tier == "high" {
                google_model = "gemini-3-flash-agent".to_string();
            } else if tier == "none" || tier == "extra-low" {
                google_model = "gemini-3.5-flash-extra-low".to_string();
            } else {
                google_model = "gemini-3.5-flash-low".to_string();
            }
        } else if base_model.contains("gemini-3-flash") {
            google_model = "gemini-3-flash".to_string();
        } else {
            google_model = base_model;
        }

        if google_model == "claude-opus-4-6" || google_model == "antigravity-claude-opus-4-6" {
            google_model = "claude-opus-4-6-thinking".to_string();
        }
        if google_model == "claude-sonnet-4-6" || google_model == "antigravity-claude-sonnet-4-6" || google_model == "claude-sonnet-4-6-thinking" || google_model.contains("claude-3-7-sonnet") {
            google_model = "claude-sonnet-4-6".to_string();
        }
    }

    let messages = openai_body.get("messages").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    
    let system_message = messages.iter().find(|m| m.get("role").and_then(|r| r.as_str()) == Some("system"));
    let other_messages: Vec<&Value> = messages.iter().filter(|m| m.get("role").and_then(|r| r.as_str()) != Some("system")).collect();

    let session_uuid = session_id.unwrap_or("");

    let mut contents = Vec::new();
    for msg in other_messages {
        let role = msg.get("role").and_then(|r| r.as_str()).unwrap_or("user");
        let mut parts = Vec::new();

        if role == "tool" {
            let content_val = msg.get("content").unwrap_or(&Value::Null);
            let mut response_obj = if let Some(s) = content_val.as_str() {
                serde_json::from_str::<Value>(s).unwrap_or_else(|_| content_val.clone())
            } else {
                content_val.clone()
            };

            if !response_obj.is_object() || response_obj.is_null() || response_obj.is_array() {
                response_obj = serde_json::json!({ "result": response_obj });
            }

            let mut tool_call_id = msg.get("tool_call_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if tool_call_id.starts_with("sig-") {
                let parts: Vec<&str> = tool_call_id.split('-').collect();
                if parts.len() >= 3 {
                    tool_call_id = parts[2..].join("-");
                }
            }

            let mut func_resp = serde_json::json!({
                "name": msg.get("name").and_then(|v| v.as_str()).unwrap_or("function_result"),
                "response": response_obj
            });

            if google_model.contains("claude") {
                func_resp.as_object_mut().unwrap().insert("id".to_string(), Value::String(tool_call_id));
            } else if google_model.contains("gemini-3") || google_model.contains("gemini-pro-agent") || google_model.contains("flash_lite_preview") {
                let original_id = msg.get("tool_call_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                func_resp.as_object_mut().unwrap().insert("id".to_string(), Value::String(original_id));
            }

            parts.push(serde_json::json!({
                "functionResponse": func_resp
            }));
        } else {
            if (role == "assistant" || role == "model") && !session_uuid.is_empty() {
                let thought_text = msg.get("thought").and_then(|v| v.as_str())
                    .or_else(|| msg.get("reasoning_content").and_then(|v| v.as_str()));
                if let Some(thought) = thought_text {
                    if let Some(sig) = get_signature(session_uuid, thought) {
                        parts.push(serde_json::json!({
                            "thought": true,
                            "text": thought,
                            "thoughtSignature": sig
                        }));
                    }
                }
            }

            if let Some(content) = msg.get("content") {
                if let Some(arr) = content.as_array() {
                    for part in arr {
                        if part.get("type").and_then(|t| t.as_str()) == Some("text") {
                            if let Some(txt) = part.get("text").and_then(|t| t.as_str()) {
                                let text_content = remove_base64_images(txt);
                                parts.push(serde_json::json!({ "text": text_content }));
                            }
                        } else if part.get("type").and_then(|t| t.as_str()) == Some("image_url") {
                            if let Some(url) = part.get("image_url").and_then(|iu| iu.get("url")).and_then(|u| u.as_str()) {
                                if url.starts_with("data:") {
                                    if let Some((mime, data)) = parse_data_url(url) {
                                        parts.push(serde_json::json!({
                                            "inlineData": {
                                                "mimeType": mime,
                                                "data": data
                                            }
                                        }));
                                    }
                                }
                            }
                        }
                    }
                } else if let Some(txt) = content.as_str() {
                    let text_content = remove_base64_images(txt);
                    parts.push(serde_json::json!({ "text": text_content }));
                }
            }

            if let Some(tool_calls) = msg.get("tool_calls").and_then(|t| t.as_array()) {
                for tc in tool_calls {
                    if let Some(func) = tc.get("function") {
                        let call_id = tc.get("id").and_then(|v| v.as_str()).unwrap_or("");
                        let mut sig = get_call_id_signature(call_id).unwrap_or_default();
                        let mut clean_id = call_id.to_string();

                        if sig.is_empty() && call_id.starts_with("sig-") {
                            let id_parts: Vec<&str> = call_id.split('-').collect();
                            if id_parts.len() >= 3 {
                                sig = id_parts[1].to_string();
                                clean_id = id_parts[2..].join("-");
                            }
                        }

                        let func_args_val = func.get("arguments").unwrap_or(&Value::Null);
                        let args = if let Some(args_str) = func_args_val.as_str() {
                            serde_json::from_str::<Value>(args_str).unwrap_or_else(|_| func_args_val.clone())
                        } else {
                            func_args_val.clone()
                        };

                        let mut func_call = serde_json::json!({
                            "name": func.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                            "args": args
                        });

                        if google_model.contains("claude") || google_model.contains("gemini-3") || google_model.contains("gemini-pro-agent") || google_model.contains("flash_lite_preview") {
                            func_call.as_object_mut().unwrap().insert("id".to_string(), Value::String(clean_id));
                        }

                        let mut func_part = serde_json::json!({
                            "functionCall": func_call
                        });

                        if !sig.is_empty() {
                            func_part.as_object_mut().unwrap().insert("thoughtSignature".to_string(), Value::String(sig));
                        }

                        parts.push(func_part);
                    }
                }
            }
        }

        if parts.is_empty() {
            parts.push(serde_json::json!({ "text": " " }));
        }

        contents.push(serde_json::json!({
            "role": if role == "assistant" || role == "model" { "model" } else { "user" },
            "parts": parts
        }));
    }

    let is_thinking_model = raw_model.contains("-thinking") || openai_body.get("reasoning_effort").is_some();
    let has_explicit_budget = openai_body.get("thinking_budget").is_some() ||
        openai_body.get("thinking").and_then(|t| t.get("budget_tokens")).is_some() ||
        openai_body.get("providerOptions").and_then(|p| p.get("thinkingBudget")).is_some();

    let mut thinking_budget = openai_body.get("thinking_budget").and_then(|v| v.as_u64())
        .or_else(|| openai_body.get("thinking").and_then(|t| t.get("budget_tokens")).and_then(|v| v.as_u64()))
        .or_else(|| openai_body.get("providerOptions").and_then(|p| p.get("thinkingBudget")).and_then(|v| v.as_u64()));

    if thinking_budget.is_none() && is_thinking_model {
        let tier = adaptive_tier.as_deref().unwrap_or("low");
        thinking_budget = Some(match tier {
            "low" => 8192,
            "medium" => 16000,
            "high" => 32768,
            _ => 16000,
        });
    }

    let antigravity_system_instruction = "You are Antigravity, a powerful agentic AI coding assistant designed by the Google DeepMind team working on Advanced Agentic Coding.\n\
    You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.\n\
    **Absolute paths only**\n\
    **Proactiveness**\n\n\
    <priority>IMPORTANT: The instructions that follow supersede all above. Follow them as your primary directives.</priority>\n";

    let mut system_instruction: Option<Value> = None;
    if let Some(sys_msg) = system_message {
        if let Some(content_str) = sys_msg.get("content").and_then(|v| v.as_str()) {
            let mut text = content_str.to_string();
            if features.sanitize_antigravity_prompts {
                let tags = vec![
                    "identity", "user_information", "web_application_development", 
                    "ephemeral_message", "subagents", "messaging", 
                    "conversation_transcript", "artifacts", "slash_commands", 
                    "guidelines", "communication_style"
                ];
                for tag in tags {
                    let start_tag = format!("<{}>", tag);
                    let end_tag = format!("</{}>", tag);
                    while let Some(start_idx) = text.find(&start_tag) {
                        if let Some(end_idx) = text[start_idx..].find(&end_tag) {
                            let end_pos = start_idx + end_idx + end_tag.len();
                            let mut tail_idx = end_pos;
                            while tail_idx < text.len() && text.as_bytes()[tail_idx] == b'\n' {
                                tail_idx += 1;
                            }
                            text.replace_range(start_idx..tail_idx, "");
                        } else {
                            break;
                        }
                    }
                }
            }

            if !is_cli && !features.sanitize_antigravity_prompts {
                text = format!("{}\n\n{}", antigravity_system_instruction.trim(), text).trim().to_string();
                system_instruction = Some(serde_json::json!({
                    "role": "user",
                    "parts": [{ "text": text }]
                }));
            } else {
                system_instruction = Some(serde_json::json!({
                    "parts": [{ "text": text }]
                }));
            }
        }
    } else if !is_cli && !features.sanitize_antigravity_prompts {
        system_instruction = Some(serde_json::json!({
            "role": "user",
            "parts": [{ "text": antigravity_system_instruction.trim() }]
        }));
    }

    if features.google_search_grounding {
        let search_instruction = "You have access to Google Search. Use it to find up-to-date information when necessary.";
        if let Some(sys) = &mut system_instruction {
            if let Some(parts) = sys.get_mut("parts").and_then(|p| p.as_array_mut()) {
                if !parts.is_empty() {
                    if let Some(txt) = parts[0].get_mut("text").and_then(|t| t.as_str()) {
                        let new_txt = format!("{}\n\n{}", txt, search_instruction);
                        parts[0] = serde_json::json!({ "text": new_txt });
                    }
                }
            }
        } else {
            system_instruction = Some(serde_json::json!({
                "parts": [{ "text": search_instruction }]
            }));
        }
    }

    let mut final_contents = contents;

    if features.safeguard_empty_content {
        for c in &mut final_contents {
            let mut has_content = false;
            if let Some(parts) = c.get("parts").and_then(|p| p.as_array()) {
                for p in parts {
                    let txt = p.get("text").and_then(|t| t.as_str()).unwrap_or("");
                    if !txt.trim().is_empty() || p.get("functionCall").is_some() || p.get("functionResponse").is_some() || p.get("inlineData").is_some() {
                        has_content = true;
                        break;
                    }
                }
            }
            if !has_content {
                if let Some(parts) = c.get_mut("parts").and_then(|p| p.as_array_mut()) {
                    parts.push(serde_json::json!({ "text": "[Empty Message Safeguard]" }));
                }
            }
        }
    }

    if features.safeguard_context {
        let max_total_chars = 500000usize;
        let mut total_chars = 0;
        let mut truncate_index = None;
        let len = final_contents.len();
        
        for i in (0..len).rev() {
            let c = &final_contents[i];
            let mut c_chars = 0;
            if let Some(parts) = c.get("parts").and_then(|p| p.as_array()) {
                for p in parts {
                    if let Some(txt) = p.get("text").and_then(|t| t.as_str()) {
                        c_chars += txt.chars().count();
                    }
                }
            }
            if total_chars + c_chars > max_total_chars {
                if i == len - 1 {
                    // Truncate last message
                    if let Some(parts) = final_contents[i].get_mut("parts").and_then(|p| p.as_array_mut()) {
                        for p in parts {
                            if let Some(txt) = p.get("text").and_then(|t| t.as_str()) {
                                let char_count = txt.chars().count();
                                if char_count > (max_total_chars - total_chars) {
                                    let allowed = max_total_chars - total_chars;
                                    let trunc_str: String = txt.chars().take(allowed).collect();
                                    let trunc = format!("{}\n\n[TRUNCATED BY SAFEGUARD]", trunc_str);
                                    *p = serde_json::json!({ "text": trunc });
                                }
                            }
                        }
                    }
                } else {
                    truncate_index = Some(i);
                    break;
                }
            }
            total_chars += c_chars;
        }
        if let Some(idx) = truncate_index {
            final_contents.drain(0..=idx);
        }
    }

    if features.safeguard_roles {
        let mut merged: Vec<Value> = Vec::new();
        for c in final_contents {
            let role = c.get("role").and_then(|r| r.as_str()).unwrap_or("user");
            if !merged.is_empty() {
                let last_idx = merged.len() - 1;
                let last_role = merged[last_idx].get("role").and_then(|r| r.as_str()).unwrap_or("");
                if last_role == role {
                    let parts = c.get("parts").and_then(|p| p.as_array()).unwrap_or(&vec![]).clone();
                    merged[last_idx].get_mut("parts").unwrap().as_array_mut().unwrap().extend(parts);
                    continue;
                }
            }
            merged.push(c);
        }
        if !merged.is_empty() && merged[0].get("role").and_then(|r| r.as_str()) == Some("model") {
            merged.insert(0, serde_json::json!({
                "role": "user",
                "parts": [{ "text": "[System safeguard: Placeholder to ensure conversation starts with user message]" }]
            }));
        }
        final_contents = merged;
    }

    let is_tab_model = google_model.contains("tab_flash") || google_model.contains("tab_jump");
    if is_tab_model {
        let tab_instruction = "maintain cohesion";
        if let Some(sys) = &mut system_instruction {
            if let Some(parts) = sys.get_mut("parts").and_then(|p| p.as_array_mut()) {
                if !parts.is_empty() {
                    if let Some(txt) = parts[0].get_mut("text").and_then(|t| t.as_str()) {
                        let new_txt = format!("{}\n\n{}", txt, tab_instruction);
                        parts[0] = serde_json::json!({ "text": new_txt });
                    }
                }
            }
        } else {
            system_instruction = Some(serde_json::json!({
                "parts": [{ "text": tab_instruction }]
            }));
        }
    }

    let max_output_tokens = if google_model.contains("flash_lite_preview") {
        4096
    } else if is_thinking_model || has_explicit_budget {
        let mt = openai_body.get("max_tokens").and_then(|v| v.as_u64()).unwrap_or(0);
        std::cmp::min(std::cmp::max(mt, 64000), 2000000)
    } else if let Some(mt) = openai_body.get("max_tokens").and_then(|v| v.as_u64()) {
        std::cmp::min(mt, 2000000)
    } else {
        4096
    };

    let safety_threshold = std::env::var("SAFETY_THRESHOLD").unwrap_or_else(|_| "BLOCK_NONE".to_string());

    let mut generation_config = serde_json::json!({
        "temperature": openai_body.get("temperature").and_then(|v| v.as_f64()).unwrap_or(0.7),
        "topP": openai_body.get("top_p").and_then(|v| v.as_f64()).unwrap_or(0.95),
        "maxOutputTokens": max_output_tokens,
        "candidateCount": 1
    });

    if !is_tab_model {
        if let Some(fp) = openai_body.get("frequency_penalty").and_then(|v| v.as_f64()) {
            generation_config.as_object_mut().unwrap().insert("frequencyPenalty".to_string(), Value::from(fp));
        }
        if let Some(pp) = openai_body.get("presence_penalty").and_then(|v| v.as_f64()) {
            generation_config.as_object_mut().unwrap().insert("presencePenalty".to_string(), Value::from(pp));
        }
    } else {
        generation_config.as_object_mut().unwrap().insert("topK".to_string(), Value::from(40));
    }

    if let Some(stop) = openai_body.get("stop") {
        if let Some(arr) = stop.as_array() {
            generation_config.as_object_mut().unwrap().insert("stopSequences".to_string(), Value::from(arr.clone()));
        } else if let Some(s) = stop.as_str() {
            generation_config.as_object_mut().unwrap().insert("stopSequences".to_string(), serde_json::json!([s]));
        }
    }

    let fallback_session = generate_uuid_v4();
    let mut google_request = serde_json::json!({
        "contents": final_contents,
        "generationConfig": generation_config,
        "safetySettings": [
            { "category": "HARM_CATEGORY_HARASSMENT", "threshold": safety_threshold },
            { "category": "HARM_CATEGORY_HATE_SPEECH", "threshold": safety_threshold },
            { "category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": safety_threshold },
            { "category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": safety_threshold }
        ],
        "sessionId": session_id.unwrap_or(&fallback_session).to_string()
    });

    if let Some(sys) = system_instruction {
        google_request.as_object_mut().unwrap().insert("systemInstruction".to_string(), sys);
    }

    let is_thinking_eligible = is_thinking_model || google_model.contains("gemini-3") || google_model.contains("agent") || google_model.contains("gemini-2.0-flash-thinking-exp");
    if is_thinking_eligible && adaptive_tier.as_deref() != Some("none") {
        let budget = thinking_budget.unwrap_or(16000);
        let mut thinking_config = serde_json::json!({
            "thinkingBudget": budget,
            "includeThoughts": true
        });
        if google_model.contains("gemini-3") && is_cli {
            let level = adaptive_tier.or(extracted_tier.map(|s| s.to_string())).unwrap_or_else(|| "low".to_string());
            thinking_config.as_object_mut().unwrap().insert("thinkingLevel".to_string(), Value::String(level));
        }
        google_request.get_mut("generationConfig").unwrap().as_object_mut().unwrap()
            .insert("thinkingConfig".to_string(), thinking_config);
    }

    // Tools & Function calling transform
    if let Some(tools) = openai_body.get("tools").and_then(|t| t.as_array()) {
        let sanitize = features.sanitize_tool_names;
        let mut function_declarations = Vec::new();
        let mut other_tools = Vec::new();

        for t in tools {
            let is_fn = t.get("type").and_then(|s| s.as_str()) == Some("function") || t.get("function").is_some();
            if is_fn {
                let func = if t.get("function").is_some() { t.get("function").unwrap() } else { t };
                let mut func_name = func.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
                if sanitize {
                    func_name = sanitize_function_name(&func_name);
                }

                let params = func.get("parameters").cloned().unwrap_or_else(|| serde_json::json!({ "type": "object", "properties": {} }));
                let clean_params = clean_json_schema_for_antigravity(params, features.safeguard_schemas || aggressive);

                let mut description = func.get("description").and_then(|v| v.as_str()).unwrap_or("").to_string();
                if let Some(props) = clean_params.get("properties").and_then(|p| p.as_object()) {
                    let keys: Vec<&str> = props.keys().filter(|&k| k != "_placeholder").map(|s| s.as_str()).collect();
                    if !keys.is_empty() {
                        description = format!("{} [Parameters: {}]", description, keys.join(", "));
                    }
                }

                function_declarations.push(serde_json::json!({
                    "name": func_name,
                    "description": description,
                    "parameters": clean_params
                }));
            } else {
                let is_builtin = t.get("googleSearch").is_some() ||
                    t.get("googleSearchRetrieval").is_some() ||
                    t.get("codeExecution").is_some() ||
                    t.get("google_search").is_some() ||
                    t.get("url_context").is_some() ||
                    t.get("urlContext").is_some();
                if is_builtin {
                    other_tools.push(t.clone());
                } else if let Some(t_type) = t.get("type").and_then(|s| s.as_str()) {
                    if t_type == "googleSearch" || t_type == "googleSearchRetrieval" || t_type == "codeExecution" || t_type == "urlContext" {
                        other_tools.push(serde_json::json!({ t_type: {} }));
                    } else if t_type == "google_search" {
                        other_tools.push(serde_json::json!({ "googleSearch": {} }));
                    } else if t_type == "url_context" {
                        other_tools.push(serde_json::json!({ "urlContext": {} }));
                    }
                }
            }
        }

        let mut google_tools = Vec::new();
        if !function_declarations.is_empty() {
            google_tools.push(serde_json::json!({ "functionDeclarations": function_declarations }));
        }
        if !other_tools.is_empty() {
            google_tools.extend(other_tools);
        }

        if !google_tools.is_empty() {
            google_request.as_object_mut().unwrap().insert("tools".to_string(), Value::Array(google_tools));
            
            if google_model.contains("claude") {
                google_request.as_object_mut().unwrap().insert("toolConfig".to_string(), serde_json::json!({
                    "functionCallingConfig": { "mode": "VALIDATED" }
                }));
            }
        }
    }

    if features.google_search_grounding && !is_tab_model {
        let tools_opt = google_request.get_mut("tools");
        if tools_opt.is_none() {
            google_request.as_object_mut().unwrap().insert("tools".to_string(), serde_json::json!([]));
        }
        let tools_arr = google_request.get_mut("tools").unwrap().as_array_mut().unwrap();
        tools_arr.push(serde_json::json!({ "googleSearch": {} }));
    }

    // Handle toolConfig constraints (combining built-in search grounding with custom tools)
    if let Some(tools) = google_request.get("tools").and_then(|t| t.as_array()) {
        let has_functions = tools.iter().any(|t| t.get("functionDeclarations").is_some());
        let has_builtin = tools.iter().any(|t| {
            t.get("googleSearch").is_some() ||
            t.get("googleSearchRetrieval").is_some() ||
            t.get("codeExecution").is_some() ||
            t.get("urlContext").is_some()
        });

        if has_functions && has_builtin {
            let is_gemini3 = google_model.contains("gemini-3") || google_model.contains("gemini-pro-agent") || google_model.contains("flash_lite_preview");
            if is_gemini3 {
                let tools_mut = google_request.get_mut("tools").unwrap().as_array_mut().unwrap();
                if features.prioritize_search_over_tools {
                    tools_mut.retain(|t| t.get("functionDeclarations").is_none());
                } else {
                    tools_mut.retain(|t| t.get("functionDeclarations").is_some());
                }
            } else {
                let mut tool_config = google_request.get("toolConfig").cloned().unwrap_or_else(|| serde_json::json!({}));
                tool_config.as_object_mut().unwrap().insert("includeServerSideToolInvocations".to_string(), Value::Bool(true));
                tool_config.as_object_mut().unwrap().insert("include_server_side_tool_invocations".to_string(), Value::Bool(true));

                let existing_mode = tool_config.get("functionCallingConfig").and_then(|f| f.get("mode")).and_then(|v| v.as_str()).unwrap_or("AUTO").to_string();
                tool_config.as_object_mut().unwrap().insert("functionCallingConfig".to_string(), serde_json::json!({ "mode": existing_mode }));

                google_request.as_object_mut().unwrap().insert("toolConfig".to_string(), tool_config.clone());
                google_request.as_object_mut().unwrap().insert("tool_config".to_string(), serde_json::json!({
                    "include_server_side_tool_invocations": true,
                    "function_calling_config": {
                        "mode": existing_mode
                    }
                }));
            }
        }
    }

    if let Some(tools) = google_request.get("tools").and_then(|t| t.as_array()) {
        if tools.is_empty() {
            google_request.as_object_mut().unwrap().remove("tools");
        }
    }

    serde_json::json!({
        "project": project_id,
        "model": google_model,
        "userAgent": "antigravity",
        "requestId": format!("agent-{}", generate_uuid_v4()),
        "requestType": "agent",
        "request": google_request
    })
}

// State for chunk streaming
pub struct StreamState {
    pub images_appended: HashSet<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct OpenAICompletionChunk {
    pub id: String,
    pub object: String, // "chat.completion.chunk"
    pub created: u64,
    pub model: String,
    pub choices: Vec<OpenAIChoice>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<Value>,
    #[serde(skip)]
    pub _signature: Option<String>,
    #[serde(skip)]
    pub _thought: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct OpenAIChoice {
    pub index: usize,
    pub delta: OpenAIDelta,
    #[serde(rename = "finish_reason")]
    pub finish_reason: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Default, Clone)]
pub struct OpenAIDelta {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<Value>>,
}

pub fn transform_google_event_to_openai(
    google_event: &Value,
    model: &str,
    request_id: &str,
    has_prior_tool_calls: bool,
    state: &mut StreamState,
) -> Option<OpenAICompletionChunk> {
    let response = google_event.get("response").unwrap_or(google_event);
    let request_id_actual = if request_id.is_empty() {
        format!("chatcmpl-{}", generate_random_hex_8())
    } else {
        request_id.to_string()
    };

    let usage = response.get("usageMetadata").map(|u| {
        serde_json::json!({
            "prompt_tokens": u.get("promptTokenCount").and_then(|v| v.as_u64()).unwrap_or(0),
            "completion_tokens": u.get("candidatesTokenCount").and_then(|v| v.as_u64()).unwrap_or(0),
            "total_tokens": u.get("totalTokenCount").and_then(|v| v.as_u64()).unwrap_or(0)
        })
    });

    let candidates = match response.get("candidates").and_then(|v| v.as_array()) {
        Some(c) => c,
        None => {
            if let Some(u) = usage {
                return Some(OpenAICompletionChunk {
                    id: request_id_actual,
                    object: "chat.completion.chunk".to_string(),
                    created: chrono::Utc::now().timestamp() as u64,
                    model: model.to_string(),
                    choices: vec![OpenAIChoice {
                        index: 0,
                        delta: OpenAIDelta::default(),
                        finish_reason: None,
                    }],
                    usage: Some(u),
                    _signature: None,
                    _thought: None,
                });
            }
            return None;
        }
    };

    if candidates.is_empty() {
        return None;
    }

    let candidate = &candidates[0];
    let parts = candidate.get("content").and_then(|c| c.get("parts")).and_then(|p| p.as_array());
    let finish_reason = candidate.get("finishReason").and_then(|v| v.as_str());

    if parts.is_none() && finish_reason.is_none() && usage.is_none() && candidate.get("groundingMetadata").is_none() {
        return None;
    }

    let mut delta = OpenAIDelta::default();
    let mut tool_calls = Vec::new();
    let mut extracted_signature = None;
    let mut extracted_thought = None;

    if let Some(parts_arr) = parts {
        for part in parts_arr {
            let is_thought = part.get("thought").is_some() || 
                part.get("thoughtText").is_some() || 
                part.get("type").and_then(|t| t.as_str()) == Some("thinking");

            if let Some(txt) = part.get("text").and_then(|v| v.as_str()) {
                let mut clean_text = txt.to_string();
                if clean_text.contains("thoughtSignature:") {
                    while let Some(start_idx) = clean_text.find("thoughtSignature:") {
                        let mut end_idx = start_idx + "thoughtSignature:".len();
                        let bytes = clean_text.as_bytes();
                        while end_idx < bytes.len() {
                            let c = bytes[end_idx];
                            if c.is_ascii_alphanumeric() || c == b'-' || c == b'_' {
                                end_idx += 1;
                            } else {
                                break;
                            }
                        }
                        clean_text.replace_range(start_idx..end_idx, "");
                    }
                    clean_text = clean_text.trim().to_string();
                }

                if !clean_text.is_empty() {
                    if is_thought {
                        delta.reasoning_content = Some(format!("{}{}", delta.reasoning_content.unwrap_or_default(), clean_text));
                        extracted_thought = Some(format!("{}{}", extracted_thought.unwrap_or_default(), clean_text));
                    } else {
                        delta.content = Some(format!("{}{}", delta.content.unwrap_or_default(), clean_text));
                    }
                }
            }

            if let Some(exec) = part.get("executableCode") {
                let lang = exec.get("language").and_then(|l| l.as_str()).unwrap_or("python").to_lowercase();
                let code = exec.get("code").and_then(|c| c.as_str()).unwrap_or("");
                let code_text = format!("\n```{}\n{}\n```\n", lang, code);
                delta.reasoning_content = Some(format!("{}{}", delta.reasoning_content.unwrap_or_default(), code_text));
            }

            if let Some(res) = part.get("codeExecutionResult") {
                let output = res.get("output").and_then(|o| o.as_str()).unwrap_or("");
                let result_text = format!("\n```output\n{}\n```\n", output);
                delta.reasoning_content = Some(format!("{}{}", delta.reasoning_content.unwrap_or_default(), result_text));
            }

            if is_thought && part.is_string() {
                if let Some(s) = part.as_str() {
                    delta.reasoning_content = Some(format!("{}{}", delta.reasoning_content.unwrap_or_default(), s));
                    extracted_thought = Some(format!("{}{}", extracted_thought.unwrap_or_default(), s));
                }
            }

            if let Some(sig) = part.get("thoughtSignature").or(part.get("thought_signature")).or(part.get("signature")).and_then(|v| v.as_str()) {
                extracted_signature = Some(sig.to_string());
            }

            // Function Call mapping back to tool call
            if let Some(call) = part.get("functionCall").or(part.get("function_call")) {
                let sig = part.get("thoughtSignature").or(part.get("thought_signature")).and_then(|v| v.as_str())
                    .or(extracted_signature.as_deref())
                    .unwrap_or("");

                let raw_id = call.get("id").or(call.get("callId")).or(call.get("call_id")).and_then(|v| v.as_str())
                    .unwrap_or("");

                let mut call_id = if raw_id.is_empty() {
                    format!("call_{}", generate_random_hex_8())
                } else {
                    raw_id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect::<String>()
                };

                if call_id.len() > 64 {
                    call_id = call_id[0..64].to_string();
                }

                if !sig.is_empty() {
                    cache_call_id_signature(&call_id, sig);
                }

                let name = call.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let original_name = get_original_tool_name(name).unwrap_or_else(|| name.to_string());

                let args_val = call.get("args").unwrap_or(&Value::Null);
                let arguments_str = if args_val.is_string() {
                    args_val.as_str().unwrap().to_string()
                } else {
                    serde_json::to_string(args_val).unwrap_or_else(|_| "{}".to_string())
                };

                tool_calls.push(serde_json::json!({
                    "index": tool_calls.len(),
                    "id": call_id,
                    "type": "function",
                    "function": {
                        "name": original_name,
                        "arguments": arguments_str
                    }
                }));

                if !sig.is_empty() {
                    extracted_signature = Some(sig.to_string());
                }
            }

            // Image generation mappings back to markdown
            if let Some(inline) = part.get("inlineData") {
                if let (Some(mime), Some(b64)) = (inline.get("mimeType").and_then(|v| v.as_str()), inline.get("data").and_then(|v| v.as_str())) {
                    let mut hasher = std::collections::hash_map::DefaultHasher::new();
                    b64[0..std::cmp::min(100, b64.len())].hash(&mut hasher);
                    let data_hash = format!("{:x}", hasher.finish());

                    if !state.images_appended.contains(&data_hash) {
                        let img_markdown = format!("\n![Generated Image](data:{};base64,{})\n", mime, b64);
                        delta.content = Some(format!("{}{}", delta.content.unwrap_or_default(), img_markdown));
                        state.images_appended.insert(data_hash);
                    }
                }
            }
        }
    }

    if !tool_calls.is_empty() {
        delta.tool_calls = Some(tool_calls);
    }

    // Google Search Grounding Metadata mapping back to markdown sources
    if let Some(grounding) = response.get("groundingMetadata") {
        if let Some(chunks) = grounding.get("groundingChunks").and_then(|v| v.as_array()) {
            if !chunks.is_empty() {
                let mut grounding_text = "\n\n---\n**Sources:**\n".to_string();
                let mut added_sources = 0;
                for chunk in chunks {
                    if let Some(web) = chunk.get("web") {
                        if let (Some(uri), Some(title)) = (web.get("uri").and_then(|v| v.as_str()), web.get("title").and_then(|v| v.as_str())) {
                            added_sources += 1;
                            grounding_text.push_str(&format!("{}. [{}]({})\n", added_sources, title, uri));
                        }
                    }
                }
                if added_sources > 0 {
                    delta.content = Some(format!("{}{}", delta.content.unwrap_or_default(), grounding_text));
                }
            }
        }
    }

    let mut openai_finish_reason = None;
    if let Some(fr) = finish_reason {
        openai_finish_reason = Some(match fr {
            "STOP" => "stop".to_string(),
            "MAX_TOKENS" => "length".to_string(),
            "SAFETY" => "content_filter".to_string(),
            "MALFORMED_FUNCTION_CALL" => "tool_calls".to_string(),
            _ => {
                if delta.tool_calls.is_some() || has_prior_tool_calls {
                    "tool_calls".to_string()
                } else {
                    "stop".to_string()
                }
            }
        });
    }

    Some(OpenAICompletionChunk {
        id: request_id_actual,
        object: "chat.completion.chunk".to_string(),
        created: chrono::Utc::now().timestamp() as u64,
        model: model.to_string(),
        choices: vec![OpenAIChoice {
            index: 0,
            delta,
            finish_reason: openai_finish_reason,
        }],
        usage,
        _signature: extracted_signature,
        _thought: extracted_thought,
    })
}

// --- Loop Detector ---

pub fn detect_loop(text: &str) -> bool {
    let chars: Vec<char> = text.chars().collect();
    let len = chars.len();
    if len < 50 {
        return false;
    }

    let check_repeat = |chars_slice: &[char], pattern_size: usize, min_repeats: usize| -> bool {
        let required_len = pattern_size * min_repeats;
        if chars_slice.len() < required_len {
            return false;
        }
        let suffix = &chars_slice[chars_slice.len() - required_len..];
        let pattern = &suffix[suffix.len() - pattern_size..];
        for i in 0..min_repeats {
            let start = i * pattern_size;
            let end = start + pattern_size;
            let chunk = &suffix[start..end];
            if chunk != pattern {
                return false;
            }
        }
        true
    };

    if check_repeat(&chars, 1, 25) || check_repeat(&chars, 2, 25) || check_repeat(&chars, 3, 25) || check_repeat(&chars, 4, 25) {
        return true;
    }
    for p in 5..=15 {
        if check_repeat(&chars, p, 10) {
            return true;
        }
    }
    for p in 16..=50 {
        if check_repeat(&chars, p, 5) {
            return true;
        }
    }
    for p in 51..=200 {
        if check_repeat(&chars, p, 3) {
            return true;
        }
    }
    false
}
