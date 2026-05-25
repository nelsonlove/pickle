use pickle::collection::PickleCollection;
use pickle::model::{CreateRequest, CreateResponse, RequestState};
use pickle::templates::{ACK_RESPONSE_TYPE, APPROVAL_RESPONSE_TYPE};
use serde_json::json;
use std::fs;
use tempfile::tempdir;

#[test]
fn approval_response_is_a_linked_mdbase_file_without_status_mutation() {
    let root = tempdir().unwrap();
    let collection = PickleCollection::new(root.path());

    let request = collection
        .create_request(CreateRequest {
            title: "Approve build".to_string(),
            source: "vitest".to_string(),
            kind: "approval".to_string(),
            message: "Approve the build?".to_string(),
            body: "The smoke build passed.".to_string(),
            priority: "normal".to_string(),
            response_type: APPROVAL_RESPONSE_TYPE.to_string(),
            tags: vec!["ci".to_string()],
            ..empty_request()
        })
        .unwrap();

    assert_eq!(request.state, RequestState::Pending);
    assert_eq!(request.response_count, 0);
    assert!(root.path().join(&request.path).exists());

    let answered = collection
        .respond(
            &request.id,
            CreateResponse {
                responder: "callum".to_string(),
                payload: json!({ "decision": "approve", "comment": "Ship it." }),
            },
        )
        .unwrap();

    assert_eq!(answered.state, RequestState::Answered);
    assert_eq!(answered.status, "answered");
    assert_eq!(answered.response_count, 1);
    assert_eq!(
        answered.response.as_ref().unwrap().payload,
        json!({ "decision": "approve", "comment": "Ship it." })
    );

    let request_file = fs::read_to_string(root.path().join(&request.path)).unwrap();
    assert!(!request_file.contains("status: answered"));

    let response_path = &answered.response.as_ref().unwrap().path;
    let response_file = fs::read_to_string(root.path().join(response_path)).unwrap();
    assert!(response_file.contains(&format!(
        "request: '[[{}]]'",
        request.path.trim_end_matches(".md")
    )));
}

#[test]
fn message_requests_default_to_ack_response_type() {
    let root = tempdir().unwrap();
    let collection = PickleCollection::new(root.path());

    let request = collection
        .create_request(CreateRequest {
            title: "Read deployment update".to_string(),
            source: "tickle".to_string(),
            kind: "message".to_string(),
            message: "Deployment finished cleanly.".to_string(),
            ..empty_request()
        })
        .unwrap();

    assert_eq!(request.response_type, ACK_RESPONSE_TYPE);
    assert_eq!(
        request.response_type_definition.unwrap().name,
        ACK_RESPONSE_TYPE
    );

    let answered = collection
        .respond(
            &request.id,
            CreateResponse {
                responder: "callum".to_string(),
                payload: json!({ "message": "Acknowledged." }),
            },
        )
        .unwrap();

    assert_eq!(answered.state, RequestState::Answered);
    assert_eq!(
        answered.response.unwrap().payload,
        json!({ "message": "Acknowledged." })
    );
}

#[test]
fn multiple_response_links_make_request_conflicted() {
    let root = tempdir().unwrap();
    let collection = PickleCollection::new(root.path());
    let request = collection
        .create_request(CreateRequest {
            title: "Choose deployment".to_string(),
            source: "vitest".to_string(),
            kind: "approval".to_string(),
            response_type: APPROVAL_RESPONSE_TYPE.to_string(),
            ..empty_request()
        })
        .unwrap();

    let link = request.path.trim_end_matches(".md");
    let responses = root.path().join("responses");
    fs::write(
        responses.join("one.md"),
        format!(
            "---\ntype: {APPROVAL_RESPONSE_TYPE}\nrequest: '[[{link}]]'\ndecision: approve\nresponded_at: \"2026-05-25T00:00:00Z\"\nresponder: one\n---\n"
        ),
    )
    .unwrap();
    fs::write(
        responses.join("two.md"),
        format!(
            "---\ntype: {APPROVAL_RESPONSE_TYPE}\nrequest: '[[{link}]]'\ndecision: reject\nresponded_at: \"2026-05-25T00:01:00Z\"\nresponder: two\n---\n"
        ),
    )
    .unwrap();

    let conflicted = collection.get_request_by_id(&request.id).unwrap();
    assert_eq!(conflicted.state, RequestState::Conflict);
    assert_eq!(conflicted.status, "conflict");
    assert_eq!(conflicted.response_count, 2);
    assert!(conflicted.response.is_none());
}

fn empty_request() -> CreateRequest {
    CreateRequest {
        source: String::new(),
        kind: String::new(),
        title: String::new(),
        message: String::new(),
        body: String::new(),
        priority: String::new(),
        response_type: String::new(),
        tags: Vec::new(),
        links: Vec::new(),
        attachments: Vec::new(),
        metadata: json!({}),
        context: json!({}),
        dedupe_key: String::new(),
    }
}
