use anyhow::{Context, Result};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    #[serde(default)]
    pub default_collection: String,
    #[serde(default)]
    pub collections: BTreeMap<String, CollectionConfig>,
    #[serde(default)]
    pub api_url: String,
    #[serde(default)]
    pub token: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub collection_path: Option<PathBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_path: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CollectionConfig {
    pub path: PathBuf,
}

impl Default for Config {
    fn default() -> Self {
        let mut collections = BTreeMap::new();
        collections.insert(
            "default".to_string(),
            CollectionConfig {
                path: data_dir().join("collection"),
            },
        );
        Self {
            default_collection: "default".to_string(),
            collections,
            api_url: "http://127.0.0.1:8787".to_string(),
            token: String::new(),
            collection_path: None,
            data_path: None,
        }
    }
}

pub fn config_dir() -> PathBuf {
    if let Some(value) = std::env::var_os("PICKLE_CONFIG_HOME") {
        return PathBuf::from(value);
    }
    if let Some(value) = std::env::var_os("XDG_CONFIG_HOME") {
        return PathBuf::from(value).join("pickle");
    }
    home_dir()
        .map(|home| home.join(".config").join("pickle"))
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn data_dir() -> PathBuf {
    if let Some(value) = std::env::var_os("PICKLE_DATA_HOME") {
        return PathBuf::from(value);
    }
    if let Some(value) = std::env::var_os("XDG_DATA_HOME") {
        return PathBuf::from(value).join("pickle");
    }
    home_dir()
        .map(|home| home.join(".local").join("share").join("pickle"))
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn config_path() -> PathBuf {
    config_dir().join("config.json")
}

pub fn load() -> Result<Config> {
    let path = config_path();
    if !path.exists() {
        return Ok(Config::default());
    }
    let mut config: Config = serde_json::from_slice(
        &fs::read(&path).with_context(|| format!("read {}", path.display()))?,
    )
    .with_context(|| format!("parse {}", path.display()))?;
    apply_defaults(&mut config);
    Ok(config)
}

pub fn save(config: &Config) -> Result<()> {
    fs::create_dir_all(config_dir())?;
    for collection in config.collections.values() {
        if let Some(parent) = collection.path.parent() {
            fs::create_dir_all(parent)?;
        }
    }
    let data = serde_json::to_vec_pretty(config)?;
    fs::write(config_path(), [data, b"\n".to_vec()].concat())?;
    Ok(())
}

pub fn ensure() -> Result<(Config, bool)> {
    let mut config = load()?;
    let mut created = !config_path().exists();
    apply_defaults(&mut config);
    if config.token.is_empty() {
        config.token = random_token()?;
        created = true;
    }
    if created {
        save(&config)?;
    }
    Ok((config, created))
}

fn apply_defaults(config: &mut Config) {
    let default = Config::default();
    if config.default_collection.is_empty() {
        config.default_collection = default.default_collection;
    }
    if config.collections.is_empty() {
        let path = config
            .collection_path
            .clone()
            .unwrap_or_else(|| default.collections["default"].path.clone());
        config
            .collections
            .insert(config.default_collection.clone(), CollectionConfig { path });
    }
    if !config.collections.contains_key(&config.default_collection) {
        if let Some(first) = config.collections.keys().next().cloned() {
            config.default_collection = first;
        }
    }
    if config.api_url.is_empty() {
        config.api_url = default.api_url;
    }
}

pub fn collection_path(config: &Config, selector: Option<&str>) -> Result<(String, PathBuf)> {
    let name_or_path = selector.unwrap_or(&config.default_collection);
    if let Some(collection) = config.collections.get(name_or_path) {
        return Ok((
            name_or_path.to_string(),
            expand_tilde(collection.path.clone()),
        ));
    }
    let path = expand_tilde(PathBuf::from(name_or_path));
    if path.components().count() > 1 || path.is_absolute() {
        return Ok((path.display().to_string(), path));
    }
    anyhow::bail!("unknown Pickle collection: {name_or_path}");
}

pub fn upsert_collection(
    config: &mut Config,
    name: impl Into<String>,
    path: impl Into<PathBuf>,
    set_default: bool,
) {
    let name = name.into();
    config
        .collections
        .insert(name.clone(), CollectionConfig { path: path.into() });
    if set_default || config.default_collection.is_empty() {
        config.default_collection = name;
    }
}

pub fn expand_tilde(path: PathBuf) -> PathBuf {
    let Some(raw) = path.to_str() else {
        return path;
    };
    if raw == "~" {
        return home_dir().unwrap_or(path);
    }
    if let Some(rest) = raw.strip_prefix("~/") {
        if let Some(home) = home_dir() {
            return home.join(rest);
        }
    }
    path
}

fn home_dir() -> Option<PathBuf> {
    std::env::var_os("HOME").map(PathBuf::from)
}

fn random_token() -> Result<String> {
    let mut bytes = [0_u8; 32];
    getrandom(&mut bytes)?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes))
}

fn getrandom(bytes: &mut [u8]) -> Result<()> {
    let mut file = fs::File::open("/dev/urandom")?;
    use std::io::Read;
    file.read_exact(bytes)?;
    Ok(())
}
