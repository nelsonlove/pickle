use crate::collection::PickleCollection;
use crate::templates::{ACK_RESPONSE_TYPE, APPROVAL_RESPONSE_TYPE, REQUEST_TYPE};
use anyhow::{anyhow, Context, Result};
use mdbase::Collection as MdbaseCollection;
use rusqlite::Connection;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

const LEGACY_RESPONSE_TYPE: &str = "pickle_response_legacy";

#[derive(Debug, Clone)]
pub struct MigrationReport {
    pub requests: usize,
    pub responses: usize,
    pub attachments: usize,
}

#[derive(Debug)]
struct LegacyRequest {
    id: String,
    source: String,
    kind: String,
    title: String,
    body: String,
    schema_json: String,
    status: String,
    priority: String,
    tags_json: String,
    links_json: String,
    metadata_json: String,
    dedupe_key: Option<String>,
    created_at: String,
    updated_at: String,
    answered_at: Option<String>,
}

#[derive(Debug, Clone)]
struct LegacyResponse {
    request_id: String,
    responder: String,
    payload_json: String,
    created_at: String,
}

#[derive(Debug, Clone)]
struct LegacyAttachment {
    id: String,
    request_id: String,
    filename: String,
    storage_path: String,
}

pub fn migrate_sqlite(sqlite_path: &Path, collection_path: &Path) -> Result<MigrationReport> {
    let collection = PickleCollection::new(collection_path);
    collection.ensure()?;
    ensure_legacy_response_type(collection_path)?;

    let db = Connection::open(sqlite_path)
        .with_context(|| format!("open SQLite database {}", sqlite_path.display()))?;
    let responses = load_responses(&db)?;
    let attachments = load_attachments(&db)?;
    let attachments_by_request = group_attachments(&attachments);
    let responses_by_request = responses
        .iter()
        .map(|response| (response.request_id.clone(), response.clone()))
        .collect::<HashMap<_, _>>();
    let attachment_root = sqlite_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join("attachments");

    let mut report = MigrationReport {
        requests: 0,
        responses: 0,
        attachments: 0,
    };

    let mdbase = MdbaseCollection::open(collection_path)
        .map_err(|error| anyhow!("open migrated collection: {error}"))?;
    for request in load_requests(&db)? {
        let request_attachments = attachments_by_request
            .get(&request.id)
            .cloned()
            .unwrap_or_default();
        let attachment_paths = copy_attachments(
            &attachment_root,
            collection_path,
            &request.id,
            &request_attachments,
        )?;
        report.attachments += attachment_paths.len();

        let response_type = response_type_for(&request, responses_by_request.get(&request.id));
        let request_path = format!("requests/{}-{}.md", request.id, slugify(&request.title));
        let request_frontmatter = request_frontmatter(&request, &response_type, &attachment_paths)?;
        upsert_file(
            &mdbase,
            REQUEST_TYPE,
            &request_path,
            Value::Object(request_frontmatter),
            &request.body,
        )?;
        report.requests += 1;

        if let Some(response) = responses_by_request.get(&request.id) {
            let response_path = format!("responses/{}-response.md", request.id);
            let response_frontmatter =
                response_frontmatter(response, &response_type, &request_path)?;
            upsert_file(
                &mdbase,
                &response_type,
                &response_path,
                Value::Object(response_frontmatter),
                "",
            )?;
            report.responses += 1;
        }
    }

    Ok(report)
}

fn load_requests(db: &Connection) -> Result<Vec<LegacyRequest>> {
    let mut stmt = db.prepare(
        "select id, source, kind, title, body, schema_json, status, priority, tags_json, links_json, metadata_json, dedupe_key, created_at, updated_at, answered_at from requests order by created_at asc",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(LegacyRequest {
            id: row.get(0)?,
            source: row.get(1)?,
            kind: row.get(2)?,
            title: row.get(3)?,
            body: row.get(4)?,
            schema_json: row.get(5)?,
            status: row.get(6)?,
            priority: row.get(7)?,
            tags_json: row.get(8)?,
            links_json: row.get(9)?,
            metadata_json: row.get(10)?,
            dedupe_key: row.get(11)?,
            created_at: row.get(12)?,
            updated_at: row.get(13)?,
            answered_at: row.get(14)?,
        })
    })?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .context("load legacy requests")
}

fn load_responses(db: &Connection) -> Result<Vec<LegacyResponse>> {
    let mut stmt =
        db.prepare("select request_id, responder, payload_json, created_at from responses")?;
    let rows = stmt.query_map([], |row| {
        Ok(LegacyResponse {
            request_id: row.get(0)?,
            responder: row.get(1)?,
            payload_json: row.get(2)?,
            created_at: row.get(3)?,
        })
    })?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .context("load legacy responses")
}

fn load_attachments(db: &Connection) -> Result<Vec<LegacyAttachment>> {
    let mut stmt = db.prepare("select id, request_id, filename, storage_path from attachments")?;
    let rows = stmt.query_map([], |row| {
        Ok(LegacyAttachment {
            id: row.get(0)?,
            request_id: row.get(1)?,
            filename: row.get(2)?,
            storage_path: row.get(3)?,
        })
    })?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .context("load legacy attachments")
}

fn group_attachments(attachments: &[LegacyAttachment]) -> HashMap<String, Vec<LegacyAttachment>> {
    let mut grouped: HashMap<String, Vec<LegacyAttachment>> = HashMap::new();
    for attachment in attachments {
        grouped
            .entry(attachment.request_id.clone())
            .or_default()
            .push(attachment.clone());
    }
    grouped
}

fn request_frontmatter(
    request: &LegacyRequest,
    response_type: &str,
    attachment_paths: &[String],
) -> Result<Map<String, Value>> {
    let mut frontmatter = Map::new();
    frontmatter.insert("type".to_string(), json!(REQUEST_TYPE));
    frontmatter.insert("id".to_string(), json!(request.id));
    frontmatter.insert("title".to_string(), json!(request.title));
    frontmatter.insert("source".to_string(), json!(request.source));
    let message = request.body.lines().next().unwrap_or("").trim();
    if !message.is_empty() {
        frontmatter.insert("message".to_string(), json!(message));
    }
    frontmatter.insert("kind".to_string(), json!(request.kind));
    if request.status == "cancelled" {
        frontmatter.insert("status".to_string(), json!("cancelled"));
    }
    frontmatter.insert("priority".to_string(), json!(request.priority));
    frontmatter.insert("response_type".to_string(), json!(response_type));
    frontmatter.insert("created_at".to_string(), json!(request.created_at));
    if let Some(dedupe_key) = request
        .dedupe_key
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        frontmatter.insert("dedupe_key".to_string(), json!(dedupe_key));
    }
    let tags = parse_json(&request.tags_json).unwrap_or_else(|| json!([]));
    if tags
        .as_array()
        .map(|items| !items.is_empty())
        .unwrap_or(false)
    {
        frontmatter.insert("tags".to_string(), tags);
    }
    let links = parse_json(&request.links_json).unwrap_or_else(|| json!([]));
    if links
        .as_array()
        .map(|items| !items.is_empty())
        .unwrap_or(false)
    {
        frontmatter.insert("links".to_string(), links);
    }
    if !attachment_paths.is_empty() {
        frontmatter.insert("attachment_paths".to_string(), json!(attachment_paths));
    }
    let mut metadata = parse_json(&request.metadata_json)
        .and_then(|value| value.as_object().cloned())
        .unwrap_or_default();
    metadata.insert("legacy_state".to_string(), json!(request.status));
    metadata.insert("legacy_updated_at".to_string(), json!(request.updated_at));
    if let Some(answered_at) = &request.answered_at {
        metadata.insert("legacy_answered_at".to_string(), json!(answered_at));
    }
    if let Some(schema) = parse_json(&request.schema_json) {
        metadata.insert("legacy_schema".to_string(), schema);
    }
    if !metadata.is_empty() {
        frontmatter.insert("metadata".to_string(), Value::Object(metadata));
    }
    Ok(frontmatter)
}

fn response_frontmatter(
    response: &LegacyResponse,
    response_type: &str,
    request_path: &str,
) -> Result<Map<String, Value>> {
    let mut frontmatter = parse_json(&response.payload_json)
        .and_then(|value| value.as_object().cloned())
        .unwrap_or_else(|| {
            let mut map = Map::new();
            map.insert(
                "payload".to_string(),
                parse_json(&response.payload_json)
                    .unwrap_or(Value::String(response.payload_json.clone())),
            );
            map
        });
    frontmatter.insert("type".to_string(), json!(response_type));
    frontmatter.insert(
        "request".to_string(),
        json!(format!("[[{}]]", request_path.trim_end_matches(".md"))),
    );
    frontmatter.insert("responded_at".to_string(), json!(response.created_at));
    frontmatter.insert("responder".to_string(), json!(response.responder));
    Ok(frontmatter)
}

fn response_type_for(request: &LegacyRequest, response: Option<&LegacyResponse>) -> String {
    if request.kind == "message" {
        return ACK_RESPONSE_TYPE.to_string();
    }
    if response
        .and_then(|response| parse_json(&response.payload_json))
        .and_then(|payload| {
            payload
                .get("decision")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .map(|decision| matches!(decision.as_str(), "approve" | "reject" | "revise"))
        .unwrap_or(true)
    {
        APPROVAL_RESPONSE_TYPE.to_string()
    } else {
        LEGACY_RESPONSE_TYPE.to_string()
    }
}

fn ensure_legacy_response_type(collection_path: &Path) -> Result<()> {
    let path = collection_path
        .join("_types")
        .join(format!("{LEGACY_RESPONSE_TYPE}.md"));
    if path.exists() {
        return Ok(());
    }
    fs::write(
        path,
        format!(
            "---\nname: {LEGACY_RESPONSE_TYPE}\ndescription: Migrated response from the SQLite Pickle store.\ndisplay_name_key: decision\nfields:\n  request:\n    type: link\n    target: pickle_request\n    validate_exists: true\n    required: true\n  responded_at:\n    type: datetime\n    generated: now\n  responder:\n    type: string\n---\n"
        ),
    )?;
    Ok(())
}

fn upsert_file(
    collection: &MdbaseCollection,
    type_name: &str,
    path: &str,
    frontmatter: Value,
    body: &str,
) -> Result<()> {
    let result = if collection.root.join(path).exists() {
        collection.update(&json!({
            "path": path,
            "fields": frontmatter,
            "body": body,
        }))
    } else {
        collection.create(&json!({
            "type": type_name,
            "path": path,
            "frontmatter": frontmatter,
            "body": body,
        }))
    };
    if let Some(error) = result.get("error") {
        return Err(anyhow!("mdbase import failed for {path}: {error}"));
    }
    Ok(())
}

fn copy_attachments(
    legacy_attachment_root: &Path,
    collection_path: &Path,
    request_id: &str,
    attachments: &[LegacyAttachment],
) -> Result<Vec<String>> {
    let mut paths = Vec::new();
    if attachments.is_empty() {
        return Ok(paths);
    }
    let target_dir = collection_path.join("attachments").join(request_id);
    fs::create_dir_all(&target_dir)?;
    for attachment in attachments {
        let source = legacy_attachment_root.join(&attachment.storage_path);
        if !source.exists() {
            continue;
        }
        let target_name = format!("{}-{}", attachment.id, safe_filename(&attachment.filename));
        let relative = format!("attachments/{request_id}/{target_name}");
        fs::copy(&source, collection_path.join(&relative)).with_context(|| {
            format!(
                "copy attachment {} to {}",
                source.display(),
                collection_path.join(&relative).display()
            )
        })?;
        paths.push(relative);
    }
    Ok(paths)
}

fn parse_json(raw: &str) -> Option<Value> {
    serde_json::from_str(raw).ok()
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
    let name = PathBuf::from(value)
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or(fallback)
        .to_string();
    let cleaned = name
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '-'
            }
        })
        .collect::<String>();
    if cleaned.is_empty() {
        fallback.to_string()
    } else {
        cleaned
    }
}
