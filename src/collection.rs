use crate::model::{
    Attachment, CreateAttachment, CreateRequest, CreateResponse, Event, Request, RequestState,
    Response, TypeDefinition,
};
use crate::templates::{
    ACK_RESPONSE_TYPE, APPROVAL_RESPONSE_TYPE, MDBASE_CONFIG, PICKLE_ACK_RESPONSE_TYPE,
    PICKLE_APPROVAL_RESPONSE_TYPE, PICKLE_REQUEST_TYPE, REQUEST_TYPE,
};
use anyhow::{anyhow, Context, Result};
use base64::Engine;
use chrono::{DateTime, Utc};
use mdbase::Collection as MdbaseCollection;
use serde_json::{json, Map, Value};
use serde_yaml::{Mapping as YamlMapping, Value as YamlValue};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use ulid::Ulid;

#[derive(Debug, Clone)]
pub struct PickleCollection {
    pub root: PathBuf,
}

impl PickleCollection {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn ensure(&self) -> Result<()> {
        fs::create_dir_all(self.root.join("_types"))?;
        fs::create_dir_all(self.root.join("requests"))?;
        fs::create_dir_all(self.root.join("responses"))?;
        fs::create_dir_all(self.root.join("attachments"))?;
        ensure_mdbase_config(&self.root.join("mdbase.yaml"))?;
        write_if_missing(
            &self.root.join("_types").join(format!("{REQUEST_TYPE}.md")),
            PICKLE_REQUEST_TYPE,
        )?;
        write_if_missing(
            &self
                .root
                .join("_types")
                .join(format!("{APPROVAL_RESPONSE_TYPE}.md")),
            PICKLE_APPROVAL_RESPONSE_TYPE,
        )?;
        write_if_missing(
            &self
                .root
                .join("_types")
                .join(format!("{ACK_RESPONSE_TYPE}.md")),
            PICKLE_ACK_RESPONSE_TYPE,
        )?;
        Ok(())
    }

    pub fn validate(&self) -> Result<Value> {
        self.ensure()?;
        let collection = self.open_mdbase()?;
        let result = collection.query(&json!({ "limit": 10_000 }));
        if let Some(error) = result.get("error") {
            return Err(anyhow!(error.to_string()));
        }
        Ok(json!({ "valid": true, "issues": [] }))
    }

    pub fn create_request(&self, mut input: CreateRequest) -> Result<Request> {
        self.ensure()?;
        if input.title.trim().is_empty() {
            return Err(anyhow!("title is required"));
        }
        if input.source.trim().is_empty() {
            input.source = "agent".to_string();
        }
        if input.kind.trim().is_empty() {
            input.kind = "approval".to_string();
        }
        if input.priority.trim().is_empty() {
            input.priority = "normal".to_string();
        }
        if input.response_type.trim().is_empty() {
            input.response_type = if input.kind == "message" {
                ACK_RESPONSE_TYPE.to_string()
            } else {
                APPROVAL_RESPONSE_TYPE.to_string()
            };
        }
        if input.message.trim().is_empty() {
            input.message = input.body.lines().next().unwrap_or("").trim().to_string();
        }
        if input.metadata.is_null() {
            input.metadata = json!({});
        }
        if input.context.is_null() {
            input.context = json!({});
        }

        if !input.dedupe_key.trim().is_empty() {
            if let Some(existing) = self.find_request_by_dedupe(&input.dedupe_key)? {
                return Ok(existing);
            }
        }

        let id = new_id();
        let attachment_paths = self.store_attachments(&id, &input.attachments)?;
        let now = Utc::now();
        let mut frontmatter = Map::new();
        frontmatter.insert("type".to_string(), json!(REQUEST_TYPE));
        frontmatter.insert("id".to_string(), json!(id));
        frontmatter.insert("title".to_string(), json!(input.title));
        frontmatter.insert("source".to_string(), json!(input.source));
        if !input.message.is_empty() {
            frontmatter.insert("message".to_string(), json!(input.message));
        }
        frontmatter.insert("kind".to_string(), json!(input.kind));
        frontmatter.insert("priority".to_string(), json!(input.priority));
        frontmatter.insert("response_type".to_string(), json!(input.response_type));
        frontmatter.insert("created_at".to_string(), json!(now.to_rfc3339()));
        if !input.tags.is_empty() {
            frontmatter.insert("tags".to_string(), json!(normalize_tags(input.tags)));
        }
        if !input.links.is_empty() {
            frontmatter.insert("links".to_string(), serde_json::to_value(input.links)?);
        }
        if !attachment_paths.is_empty() {
            frontmatter.insert("attachment_paths".to_string(), json!(attachment_paths));
        }
        if !input
            .metadata
            .as_object()
            .map(Map::is_empty)
            .unwrap_or(false)
        {
            frontmatter.insert("metadata".to_string(), input.metadata);
        }
        if !input
            .context
            .as_object()
            .map(Map::is_empty)
            .unwrap_or(false)
        {
            frontmatter.insert("context".to_string(), input.context);
        }
        if !input.dedupe_key.trim().is_empty() {
            frontmatter.insert("dedupe_key".to_string(), json!(input.dedupe_key));
        }

        let path = format!(
            "requests/{}-{}.md",
            id,
            slugify(&frontmatter_string(&frontmatter, "title"))
        );
        let collection = self.open_mdbase()?;
        let result = collection.create(&json!({
            "type": REQUEST_TYPE,
            "path": path,
            "frontmatter": Value::Object(frontmatter),
            "body": input.body,
        }));
        result_error(&result)?;

        self.get_request_by_id(&id)
    }

    pub fn list_requests(&self, status: &str, limit: usize) -> Result<Vec<Request>> {
        self.ensure()?;
        let collection = self.open_mdbase()?;
        let requested_limit = if limit == 0 { 100 } else { limit.min(500) };
        let query_limit = if status == "all" || status.is_empty() {
            requested_limit
        } else {
            10_000
        };
        let query = collection.query(&json!({
            "types": [REQUEST_TYPE],
            "include_body": true,
            "order_by": [{ "field": "created_at", "direction": "desc" }],
            "limit": query_limit,
        }));
        result_error(&query)?;
        let responses = self.response_rows(&collection)?;
        let mut requests = Vec::new();
        for row in query
            .get("results")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
        {
            let request = self.request_from_row(&row, &responses)?;
            if status == "all" || status.is_empty() || request.state.as_str() == status {
                requests.push(request);
                if requests.len() >= requested_limit {
                    break;
                }
            }
        }
        Ok(requests)
    }

    pub fn get_request_by_id(&self, id: &str) -> Result<Request> {
        self.ensure()?;
        let collection = self.open_mdbase()?;
        let responses = self.response_rows(&collection)?;
        for row in self.request_rows(&collection)? {
            let request_id = row
                .pointer("/frontmatter/id")
                .and_then(Value::as_str)
                .unwrap_or("");
            if request_id == id {
                return self.request_from_row(&row, &responses);
            }
        }
        Err(anyhow!("request {id} not found"))
    }

    pub fn respond(&self, request_id: &str, mut input: CreateResponse) -> Result<Request> {
        self.ensure()?;
        if input.responder.trim().is_empty() {
            input.responder = "callum".to_string();
        }
        if input.payload.is_null() {
            return Err(anyhow!("response payload is required"));
        }
        let request = self.get_request_by_id(request_id)?;
        let response_type = request.response_type.clone();
        let collection = self.open_mdbase()?;
        let linked = self.linked_response_rows(&collection, &request.path)?;
        if linked.len() > 1 {
            return Err(anyhow!(
                "request {request_id} has multiple linked responses; resolve the conflict in mdbase"
            ));
        }

        let now = Utc::now();
        let frontmatter = self.response_frontmatter(
            &response_type,
            &request.path,
            &input.responder,
            input.payload.clone(),
            now,
        )?;

        if let Some(existing) = linked.first() {
            let path = existing
                .get("path")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow!("linked response is missing path"))?;
            let result = collection.update(&json!({
                "path": path,
                "fields": Value::Object(frontmatter),
            }));
            result_error(&result)?;
        } else {
            let path = format!("responses/{}-{}.md", new_id(), slugify(&request.title));
            let result = collection.create(&json!({
                "type": response_type,
                "path": path,
                "frontmatter": Value::Object(frontmatter),
                "body": "",
            }));
            result_error(&result)?;
        }

        self.get_request_by_id(request_id)
    }

    pub fn response_payload(&self, request_id: &str) -> Result<Value> {
        let request = self.get_request_by_id(request_id)?;
        request
            .response
            .map(|response| response.payload)
            .ok_or_else(|| anyhow!("request {request_id} has no response"))
    }

    pub fn wait_for_response(
        &self,
        request_id: &str,
        timeout: Option<std::time::Duration>,
        poll: std::time::Duration,
    ) -> Result<Value> {
        let start = std::time::Instant::now();
        loop {
            if let Ok(payload) = self.response_payload(request_id) {
                return Ok(payload);
            }
            if timeout
                .map(|duration| start.elapsed() >= duration)
                .unwrap_or(false)
            {
                return Err(anyhow!("timed out waiting for response"));
            }
            std::thread::sleep(poll);
        }
    }

    pub fn get_attachment(
        &self,
        request_id: &str,
        attachment_id: &str,
    ) -> Result<(Attachment, PathBuf)> {
        let request = self.get_request_by_id(request_id)?;
        let attachment = request
            .attachments
            .into_iter()
            .find(|candidate| candidate.id == attachment_id || candidate.filename == attachment_id)
            .ok_or_else(|| anyhow!("attachment not found"))?;
        let path = self.root.join(&attachment.path);
        Ok((attachment, path))
    }

    pub fn events_after(&self, after: u64, limit: usize) -> Result<Vec<Event>> {
        let collection = self.open_mdbase()?;
        let responses = self.response_rows(&collection)?;
        let mut events = Vec::new();
        for row in self.request_rows(&collection)? {
            let request = self.request_from_row(&row, &responses)?;
            events.push(Event {
                id: 0,
                event_type: "request.created".to_string(),
                request_id: request.id.clone(),
                payload: json!({
                    "id": request.id,
                    "title": request.title,
                    "source": request.source,
                    "kind": request.kind,
                    "priority": request.priority,
                    "state": request.state,
                    "attachments": request.attachments,
                }),
                created_at: request.created_at,
            });
            if let Some(response) = &request.response {
                events.push(Event {
                    id: 0,
                    event_type: "request.answered".to_string(),
                    request_id: request.id.clone(),
                    payload: json!({
                        "id": request.id,
                        "responder": response.responder,
                        "response": response.payload,
                    }),
                    created_at: response.created_at,
                });
            }
        }
        events.sort_by(|a, b| {
            a.created_at
                .cmp(&b.created_at)
                .then_with(|| a.event_type.cmp(&b.event_type))
                .then_with(|| a.request_id.cmp(&b.request_id))
        });
        for (index, event) in events.iter_mut().enumerate() {
            event.id = (index + 1) as u64;
        }
        let limit = if limit == 0 { 100 } else { limit.min(1000) };
        Ok(events
            .into_iter()
            .filter(|event| event.id > after)
            .take(limit)
            .collect())
    }

    pub fn read_type_definition(&self, name: &str) -> Result<TypeDefinition> {
        self.ensure()?;
        let type_name = name.to_ascii_lowercase();
        let path = self.root.join("_types").join(format!("{type_name}.md"));
        let content = fs::read_to_string(&path)
            .with_context(|| format!("read type definition {}", path.display()))?;
        let frontmatter = parse_frontmatter(&content)?;
        serde_json::from_value(frontmatter).context("parse type definition")
    }

    pub fn list_type_definitions(&self) -> Result<Vec<TypeDefinition>> {
        self.ensure()?;
        let mut out = Vec::new();
        for entry in fs::read_dir(self.root.join("_types"))? {
            let entry = entry?;
            if entry.path().extension().and_then(|s| s.to_str()) != Some("md") {
                continue;
            }
            let name = entry
                .path()
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("")
                .to_string();
            out.push(self.read_type_definition(&name)?);
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(out)
    }

    fn find_request_by_dedupe(&self, dedupe_key: &str) -> Result<Option<Request>> {
        let requests = self.list_requests("all", 500)?;
        Ok(requests
            .into_iter()
            .find(|request| request.dedupe_key == dedupe_key))
    }

    fn open_mdbase(&self) -> Result<MdbaseCollection> {
        MdbaseCollection::open(&self.root)
            .map_err(|error| anyhow!("open mdbase collection: {}", error))
    }

    fn request_rows(&self, collection: &MdbaseCollection) -> Result<Vec<Value>> {
        let result = collection.query(&json!({
            "types": [REQUEST_TYPE],
            "include_body": true,
            "order_by": [{ "field": "created_at", "direction": "desc" }],
            "limit": 10_000,
        }));
        result_error(&result)?;
        Ok(result
            .get("results")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default())
    }

    fn response_rows(&self, collection: &MdbaseCollection) -> Result<Vec<Value>> {
        let result = collection.query(&json!({
            "include_body": true,
            "order_by": [{ "field": "responded_at", "direction": "desc" }],
            "limit": 10_000,
        }));
        result_error(&result)?;
        Ok(result
            .get("results")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .filter(|row| row.pointer("/frontmatter/request").is_some())
            .collect())
    }

    fn linked_response_rows(
        &self,
        collection: &MdbaseCollection,
        request_path: &str,
    ) -> Result<Vec<Value>> {
        Ok(self
            .response_rows(collection)?
            .into_iter()
            .filter(|row| {
                link_targets_request(
                    row.pointer("/frontmatter/request"),
                    request_path,
                    self.root.file_name().and_then(|s| s.to_str()),
                )
            })
            .collect())
    }

    fn request_from_row(&self, row: &Value, responses: &[Value]) -> Result<Request> {
        let path = row
            .get("path")
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow!("request row is missing path"))?
            .to_string();
        let frontmatter = row
            .get("frontmatter")
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow!("request row is missing frontmatter"))?;
        let linked_responses: Vec<&Value> = responses
            .iter()
            .filter(|response| {
                link_targets_request(
                    response.pointer("/frontmatter/request"),
                    &path,
                    self.root.file_name().and_then(|s| s.to_str()),
                )
            })
            .collect();
        let state = derive_state(frontmatter.get("status"), linked_responses.len());
        let response = if linked_responses.len() == 1 {
            Some(self.response_from_row(linked_responses[0], &path)?)
        } else {
            None
        };
        let response_type = string_field(frontmatter, "response_type", APPROVAL_RESPONSE_TYPE);
        let response_type_definition = self.read_type_definition(&response_type).ok();

        Ok(Request {
            id: string_field(frontmatter, "id", ""),
            path: path.clone(),
            source: string_field(frontmatter, "source", "agent"),
            kind: string_field(frontmatter, "kind", "approval"),
            title: string_field(frontmatter, "title", &path),
            message: string_field(frontmatter, "message", ""),
            body: row
                .get("body")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string(),
            status: state.as_str().to_string(),
            state,
            response_count: linked_responses.len(),
            priority: string_field(frontmatter, "priority", "normal"),
            response_type,
            tags: string_array_field(frontmatter, "tags"),
            links: serde_json::from_value(frontmatter.get("links").cloned().unwrap_or(json!([])))
                .unwrap_or_default(),
            attachments: self
                .attachments_for_request(frontmatter, &string_field(frontmatter, "id", ""))?,
            metadata: frontmatter
                .get("metadata")
                .cloned()
                .unwrap_or_else(|| json!({})),
            dedupe_key: string_field(frontmatter, "dedupe_key", ""),
            created_at: datetime_field(frontmatter, "created_at").unwrap_or_else(Utc::now),
            updated_at: row
                .pointer("/file/mtime")
                .and_then(Value::as_str)
                .and_then(parse_datetime)
                .unwrap_or_else(|| {
                    datetime_field(frontmatter, "created_at").unwrap_or_else(Utc::now)
                }),
            answered_at: response.as_ref().map(|response| response.created_at),
            response,
            response_type_definition,
        })
    }

    fn response_from_row(&self, row: &Value, request_path: &str) -> Result<Response> {
        let path = row
            .get("path")
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow!("response row is missing path"))?
            .to_string();
        let frontmatter = row
            .get("frontmatter")
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow!("response row is missing frontmatter"))?;
        let response_type = string_field(frontmatter, "type", "");
        let mut payload = Map::new();
        for (key, value) in frontmatter {
            if matches!(
                key.as_str(),
                "type"
                    | "types"
                    | "id"
                    | "request"
                    | "responded_at"
                    | "responder"
                    | "attachment_paths"
            ) {
                continue;
            }
            payload.insert(key.clone(), value.clone());
        }
        Ok(Response {
            request_id: request_path.to_string(),
            path,
            response_type,
            responder: string_field(frontmatter, "responder", "human"),
            payload: Value::Object(payload),
            created_at: datetime_field(frontmatter, "responded_at").unwrap_or_else(Utc::now),
        })
    }

    fn response_frontmatter(
        &self,
        response_type: &str,
        request_path: &str,
        responder: &str,
        payload: Value,
        now: DateTime<Utc>,
    ) -> Result<Map<String, Value>> {
        let mut frontmatter = match payload {
            Value::Object(map) => map,
            _ => return Err(anyhow!("response payload must be a JSON object")),
        };
        for key in [
            "type",
            "types",
            "id",
            "request",
            "responded_at",
            "responder",
            "attachment_paths",
        ] {
            frontmatter.remove(key);
        }
        frontmatter.insert("type".to_string(), json!(response_type));
        frontmatter.insert(
            "request".to_string(),
            json!(request_link_value(request_path)),
        );
        frontmatter.insert("responded_at".to_string(), json!(now.to_rfc3339()));
        frontmatter.insert("responder".to_string(), json!(responder));
        Ok(frontmatter)
    }

    fn attachments_for_request(
        &self,
        frontmatter: &Map<String, Value>,
        request_id: &str,
    ) -> Result<Vec<Attachment>> {
        let mut attachments = Vec::new();
        for path in string_array_field(frontmatter, "attachment_paths") {
            let full_path = self.root.join(&path);
            let metadata = match fs::metadata(&full_path) {
                Ok(metadata) => metadata,
                Err(_) => continue,
            };
            let data = fs::read(&full_path)?;
            let filename = full_path
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("attachment")
                .to_string();
            attachments.push(Attachment {
                id: filename.clone(),
                request_id: request_id.to_string(),
                path,
                content_type: mime_guess::from_path(&full_path)
                    .first_or_octet_stream()
                    .essence_str()
                    .to_string(),
                filename,
                size_bytes: metadata.len(),
                sha256: sha256_hex(&data),
                created_at: metadata
                    .created()
                    .ok()
                    .map(DateTime::<Utc>::from)
                    .unwrap_or_else(Utc::now),
            });
        }
        Ok(attachments)
    }

    fn store_attachments(
        &self,
        request_id: &str,
        attachments: &[CreateAttachment],
    ) -> Result<Vec<String>> {
        if attachments.is_empty() {
            return Ok(Vec::new());
        }
        let attachment_dir = self.root.join("attachments").join(request_id);
        fs::create_dir_all(&attachment_dir)?;
        let mut paths = Vec::new();
        for (index, attachment) in attachments.iter().enumerate() {
            let mut data = attachment.data.clone();
            if data.is_empty() && !attachment.data_base64.is_empty() {
                data = base64::engine::general_purpose::STANDARD
                    .decode(&attachment.data_base64)
                    .context("decode attachment data_base64")?;
            }
            let filename = safe_filename(&attachment.filename);
            let relative = format!("attachments/{request_id}/{}-{filename}", index + 1);
            fs::write(self.root.join(&relative), data)?;
            paths.push(relative);
        }
        Ok(paths)
    }
}

fn write_if_missing(path: &Path, content: &str) -> Result<()> {
    if path.exists() {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, content)?;
    Ok(())
}

fn ensure_mdbase_config(path: &Path) -> Result<()> {
    if !path.exists() {
        return write_if_missing(path, MDBASE_CONFIG);
    }
    let raw = fs::read_to_string(path)?;
    let mut yaml: YamlValue = serde_yaml::from_str(&raw)?;
    let Some(root) = yaml.as_mapping_mut() else {
        return Ok(());
    };
    let settings_key = YamlValue::String("settings".to_string());
    if !root.contains_key(&settings_key) {
        root.insert(settings_key.clone(), YamlValue::Mapping(YamlMapping::new()));
    }
    let Some(settings) = root
        .get_mut(&settings_key)
        .and_then(YamlValue::as_mapping_mut)
    else {
        return Ok(());
    };
    let exclude_key = YamlValue::String("exclude".to_string());
    if !settings.contains_key(&exclude_key) {
        settings.insert(exclude_key.clone(), YamlValue::Sequence(Vec::new()));
    }
    let Some(exclude) = settings
        .get_mut(&exclude_key)
        .and_then(YamlValue::as_sequence_mut)
    else {
        return Ok(());
    };
    let mut changed = false;
    for pattern in [".git", "node_modules", ".mdbase", "attachments/**"] {
        if !exclude.iter().any(|value| value.as_str() == Some(pattern)) {
            exclude.push(YamlValue::String(pattern.to_string()));
            changed = true;
        }
    }
    if changed {
        fs::write(path, serde_yaml::to_string(&yaml)?)?;
    }
    Ok(())
}

fn result_error(result: &Value) -> Result<()> {
    if let Some(error) = result.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or_else(|| error.as_str().unwrap_or("mdbase operation failed"));
        let detail = result
            .get("issues")
            .and_then(Value::as_array)
            .map(|issues| {
                issues
                    .iter()
                    .filter_map(|issue| issue.get("message").and_then(Value::as_str))
                    .collect::<Vec<_>>()
                    .join("; ")
            })
            .filter(|detail| !detail.is_empty());
        return Err(anyhow!(detail.unwrap_or_else(|| message.to_string())));
    }
    Ok(())
}

fn parse_frontmatter(content: &str) -> Result<Value> {
    let trimmed = content.strip_prefix("---\n").unwrap_or(content);
    let Some((frontmatter, _body)) = trimmed.split_once("\n---") else {
        return Err(anyhow!("missing YAML frontmatter"));
    };
    let yaml: serde_yaml::Value = serde_yaml::from_str(frontmatter)?;
    Ok(serde_json::to_value(yaml)?)
}

fn derive_state(raw_status: Option<&Value>, response_count: usize) -> RequestState {
    if raw_status.and_then(Value::as_str) == Some("cancelled") {
        return RequestState::Cancelled;
    }
    match response_count {
        0 => RequestState::Pending,
        1 => RequestState::Answered,
        _ => RequestState::Conflict,
    }
}

fn request_link_value(request_path: &str) -> String {
    format!("[[{}]]", strip_md_extension(request_path))
}

fn link_targets_request(
    raw_value: Option<&Value>,
    request_path: &str,
    collection_name: Option<&str>,
) -> bool {
    let Some(target) = raw_value
        .and_then(Value::as_str)
        .and_then(normalize_link_target)
    else {
        return false;
    };
    let request_without_extension = strip_md_extension(request_path);
    if target == request_without_extension {
        return true;
    }
    if let Some(collection_name) = collection_name {
        if target
            == format!(
                "{}/{}",
                strip_md_extension(collection_name),
                request_without_extension
            )
        {
            return true;
        }
    }
    if !target.contains('/') {
        return request_without_extension
            .rsplit('/')
            .next()
            .map(|name| name == target)
            .unwrap_or(false);
    }
    false
}

fn normalize_link_target(raw: &str) -> Option<String> {
    let mut value = raw.trim().to_string();
    if value.starts_with("[[") && value.ends_with("]]") {
        value = value[2..value.len().saturating_sub(2)].to_string();
    } else if value.starts_with('[') {
        if let Some(start) = value.find("](") {
            if value.ends_with(')') {
                value = value[start + 2..value.len().saturating_sub(1)].to_string();
            }
        }
    }
    if let Some((before, _)) = value.split_once('|') {
        value = before.to_string();
    }
    if let Some((before, _)) = value.split_once('#') {
        value = before.to_string();
    }
    value = value.replace('\\', "/");
    value = value
        .trim_start_matches('/')
        .trim_start_matches("./")
        .to_string();
    let value = strip_md_extension(&value);
    (!value.is_empty()).then_some(value)
}

fn strip_md_extension(path: &str) -> String {
    path.strip_suffix(".md").unwrap_or(path).to_string()
}

fn string_field(map: &Map<String, Value>, key: &str, fallback: &str) -> String {
    map.get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or(fallback)
        .to_string()
}

fn frontmatter_string(map: &Map<String, Value>, key: &str) -> String {
    string_field(map, key, "request")
}

fn string_array_field(map: &Map<String, Value>, key: &str) -> Vec<String> {
    map.get(key)
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

fn datetime_field(map: &Map<String, Value>, key: &str) -> Option<DateTime<Utc>> {
    map.get(key)
        .and_then(Value::as_str)
        .and_then(parse_datetime)
}

fn parse_datetime(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|datetime| datetime.with_timezone(&Utc))
}

fn normalize_tags(tags: Vec<String>) -> Vec<String> {
    let mut seen = HashMap::new();
    let mut out = Vec::new();
    for raw in tags {
        for part in raw.split(',') {
            let tag = part.trim().trim_start_matches('#');
            if tag.is_empty() || seen.contains_key(tag) {
                continue;
            }
            seen.insert(tag.to_string(), true);
            out.push(tag.to_string());
        }
    }
    out
}

fn new_id() -> String {
    Ulid::new().to_string().to_ascii_lowercase()
}

fn slugify(value: &str) -> String {
    let mut slug = String::new();
    let mut last_dash = false;
    for ch in value.chars().flat_map(char::to_lowercase) {
        if ch.is_ascii_alphanumeric() {
            slug.push(ch);
            last_dash = false;
        } else if !last_dash {
            slug.push('-');
            last_dash = true;
        }
    }
    let slug = slug.trim_matches('-').to_string();
    if slug.is_empty() {
        "request".to_string()
    } else {
        slug.chars().take(72).collect()
    }
}

fn safe_filename(value: &str) -> String {
    let fallback = "attachment";
    let name = Path::new(value)
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or(fallback);
    let cleaned: String = name
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '-'
            }
        })
        .collect();
    if cleaned.is_empty() {
        fallback.to_string()
    } else {
        cleaned
    }
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}
