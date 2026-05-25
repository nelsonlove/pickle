use crate::collection::PickleCollection;
use crate::model::{CreateRequest, CreateResponse};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::Duration;
use tokio::net::TcpListener;

#[derive(Clone)]
pub struct AppState {
    pub collections: HashMap<String, PickleCollection>,
    pub default_collection: String,
    pub token: String,
}

pub fn router(
    collections: HashMap<String, PickleCollection>,
    default_collection: String,
    token: String,
) -> Router {
    let state = AppState {
        collections,
        default_collection,
        token,
    };
    Router::new()
        .route("/health", get(health))
        .route("/api/v1/collections", get(list_collections))
        .route("/api/v1/inbox", get(inbox))
        .route("/api/v1/requests", post(create_request))
        .route("/api/v1/requests/{id}", get(get_request))
        .route("/api/v1/requests/{id}/responses", post(respond))
        .route(
            "/api/v1/requests/{id}/attachments/{attachment_id}",
            get(get_attachment),
        )
        .route("/api/v1/types", get(list_types))
        .route("/api/v1/types/{name}", get(get_type))
        .route("/api/v1/events", get(events))
        .route("/api/v1/stream", get(stream))
        .with_state(state)
}

pub async fn listen_and_serve(
    addr: SocketAddr,
    collections: HashMap<String, PickleCollection>,
    default_collection: String,
    token: String,
) -> anyhow::Result<()> {
    let listener = TcpListener::bind(addr).await?;
    axum::serve(listener, router(collections, default_collection, token)).await?;
    Ok(())
}

async fn health() -> Json<Value> {
    Json(json!({ "status": "ok" }))
}

async fn inbox(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let status = query.get("status").map(String::as_str).unwrap_or("pending");
    let limit = query
        .get("limit")
        .and_then(|raw| raw.parse::<usize>().ok())
        .unwrap_or(100);
    let requests = collection
        .list_requests(status, limit)
        .map_err(internal_error)?;
    Ok(Json(json!({ "requests": requests })))
}

async fn create_request(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Json(input): Json<CreateRequest>,
) -> Result<(StatusCode, Json<Value>), Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let request = collection.create_request(input).map_err(bad_request)?;
    Ok((StatusCode::CREATED, Json(json!(request))))
}

async fn get_request(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let request = collection
        .get_request_by_id(&id)
        .map_err(not_found_or_internal)?;
    Ok(Json(json!(request)))
}

async fn respond(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Path(id): Path<String>,
    Json(input): Json<CreateResponse>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let request = collection.respond(&id, input).map_err(bad_request)?;
    Ok(Json(json!(request)))
}

async fn get_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Path((id, attachment_id)): Path<(String, String)>,
) -> Result<Response, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let (attachment, path) = collection
        .get_attachment(&id, &attachment_id)
        .map_err(not_found_or_internal)?;
    let data = tokio::fs::read(&path).await.map_err(internal_error)?;
    let mut response = data.into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        attachment.content_type.parse().unwrap_or_else(|_| {
            "application/octet-stream"
                .parse()
                .expect("static content type is valid")
        }),
    );
    response.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        format!("inline; filename=\"{}\"", attachment.filename)
            .parse()
            .unwrap(),
    );
    Ok(response)
}

async fn list_types(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let types = collection.list_type_definitions().map_err(internal_error)?;
    Ok(Json(json!({ "types": types })))
}

async fn get_type(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Path(name): Path<String>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let definition = collection
        .read_type_definition(&name)
        .map_err(not_found_or_internal)?;
    Ok(Json(json!(definition)))
}

async fn events(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let collection = selected_collection(&state, &headers, &query)?;
    let after = query
        .get("after")
        .and_then(|raw| raw.parse::<u64>().ok())
        .unwrap_or(0);
    let limit = query
        .get("limit")
        .and_then(|raw| raw.parse::<usize>().ok())
        .unwrap_or(100);
    let events = collection
        .events_after(after, limit)
        .map_err(internal_error)?;
    Ok(Json(json!({ "events": events })))
}

async fn stream(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    ws: WebSocketUpgrade,
) -> Response {
    if let Err(response) = require_auth(&state, &headers, &query) {
        return response;
    }
    let collection = match selected_collection(&state, &headers, &query) {
        Ok(collection) => collection,
        Err(response) => return response,
    };
    ws.on_upgrade(move |socket| stream_events(socket, collection))
}

async fn stream_events(socket: WebSocket, collection: PickleCollection) {
    let (mut sender, _receiver) = socket.split();
    let _ = sender
        .send(Message::Text(
            json!({ "type": "stream.ready", "ts": chrono::Utc::now() })
                .to_string()
                .into(),
        ))
        .await;
    let mut after = 0_u64;
    loop {
        match collection.events_after(after, 100) {
            Ok(events) => {
                for event in events {
                    after = after.max(event.id);
                    let payload = match serde_json::to_string(&event) {
                        Ok(payload) => payload,
                        Err(_) => continue,
                    };
                    if sender.send(Message::Text(payload.into())).await.is_err() {
                        return;
                    }
                }
            }
            Err(error) => {
                let payload = json!({
                    "type": "stream.error",
                    "error": error.to_string(),
                    "ts": chrono::Utc::now(),
                });
                if sender
                    .send(Message::Text(payload.to_string().into()))
                    .await
                    .is_err()
                {
                    return;
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

async fn list_collections(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, Response> {
    require_auth(&state, &headers, &query)?;
    let mut collections = state
        .collections
        .iter()
        .map(|(name, collection)| {
            json!({
                "name": name,
                "path": collection.root,
                "default": name == &state.default_collection,
            })
        })
        .collect::<Vec<_>>();
    collections.sort_by(|a, b| {
        a.get("name")
            .and_then(Value::as_str)
            .cmp(&b.get("name").and_then(Value::as_str))
    });
    Ok(Json(json!({ "collections": collections })))
}

fn selected_collection(
    state: &AppState,
    headers: &HeaderMap,
    query: &HashMap<String, String>,
) -> Result<PickleCollection, Response> {
    let requested = headers
        .get("X-Pickle-Collection")
        .and_then(|value| value.to_str().ok())
        .or_else(|| query.get("collection").map(String::as_str))
        .unwrap_or(&state.default_collection);
    state
        .collections
        .get(requested)
        .cloned()
        .ok_or_else(|| error_response(StatusCode::NOT_FOUND, "unknown Pickle collection"))
}

fn require_auth(
    state: &AppState,
    headers: &HeaderMap,
    query: &HashMap<String, String>,
) -> Result<(), Response> {
    if state.token.is_empty() {
        return Ok(());
    }
    let authorization = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .strip_prefix("Bearer ")
        .unwrap_or("");
    let header_token = headers
        .get("X-Pickle-Token")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    let query_token = query.get("token").map(String::as_str).unwrap_or("");
    if authorization == state.token || header_token == state.token || query_token == state.token {
        Ok(())
    } else {
        Err(error_response(StatusCode::UNAUTHORIZED, "unauthorized"))
    }
}

fn bad_request(error: anyhow::Error) -> Response {
    error_response(StatusCode::BAD_REQUEST, &error.to_string())
}

fn internal_error(error: impl std::fmt::Display) -> Response {
    error_response(StatusCode::INTERNAL_SERVER_ERROR, &error.to_string())
}

fn not_found_or_internal(error: anyhow::Error) -> Response {
    let message = error.to_string();
    if message.contains("not found") {
        error_response(StatusCode::NOT_FOUND, &message)
    } else {
        error_response(StatusCode::INTERNAL_SERVER_ERROR, &message)
    }
}

fn error_response(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({ "error": message }))).into_response()
}
