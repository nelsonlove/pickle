use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RequestState {
    Pending,
    Answered,
    Conflict,
    Cancelled,
}

impl RequestState {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Answered => "answered",
            Self::Conflict => "conflict",
            Self::Cancelled => "cancelled",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Link {
    pub label: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Attachment {
    pub id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub request_id: String,
    pub path: String,
    pub filename: String,
    pub content_type: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub id: String,
    pub path: String,
    pub source: String,
    pub kind: String,
    pub title: String,
    pub message: String,
    pub body: String,
    pub status: String,
    pub state: RequestState,
    pub response_count: usize,
    pub priority: String,
    pub response_type: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub links: Vec<Link>,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
    #[serde(default)]
    pub metadata: Value,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub dedupe_key: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub answered_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response: Option<Response>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_type_definition: Option<TypeDefinition>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateRequest {
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub kind: String,
    pub title: String,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub priority: String,
    #[serde(default)]
    pub response_type: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub links: Vec<Link>,
    #[serde(default)]
    pub attachments: Vec<CreateAttachment>,
    #[serde(default)]
    pub metadata: Value,
    #[serde(default)]
    pub context: Value,
    #[serde(default)]
    pub dedupe_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateAttachment {
    pub filename: String,
    #[serde(default)]
    pub content_type: String,
    #[serde(default)]
    pub data_base64: String,
    #[serde(skip)]
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    pub request_id: String,
    pub path: String,
    pub response_type: String,
    pub responder: String,
    pub payload: Value,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateResponse {
    #[serde(default)]
    pub responder: String,
    #[serde(default)]
    pub payload: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub id: u64,
    #[serde(rename = "type")]
    pub event_type: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub request_id: String,
    pub payload: Value,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TypeDefinition {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name_key: Option<String>,
    #[serde(default)]
    pub fields: BTreeMap<String, FieldDefinition>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct FieldDefinition {
    #[serde(rename = "type")]
    pub field_type: String,
    #[serde(default, skip_serializing_if = "is_false")]
    pub required: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub generated: Option<Value>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub values: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub items: Option<Box<FieldDefinition>>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub fields: BTreeMap<String, FieldDefinition>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub validate_exists: Option<bool>,
}

fn is_false(value: &bool) -> bool {
    !*value
}
