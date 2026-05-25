use anyhow::{anyhow, Context, Result};
use clap::{Parser, Subcommand};
use pickle::collection::PickleCollection;
use pickle::config;
use pickle::model::{CreateAttachment, CreateRequest, CreateResponse, Link};
use pickle::server;
use pickle::templates::{ACK_RESPONSE_TYPE, APPROVAL_RESPONSE_TYPE};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::io::{self, Write};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "pickle")]
#[command(about = "A local mdbase-backed inbox for typed agent requests")]
struct Cli {
    #[arg(long, global = true)]
    collection: Option<String>,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    Init {
        #[arg(long)]
        api_url: Option<String>,
        #[arg(long, default_value = "default")]
        collection_name: String,
        #[arg(long)]
        collection_path: Option<PathBuf>,
        #[arg(long)]
        set_default: bool,
        #[arg(long, hide = true)]
        data: Option<PathBuf>,
    },
    Collections {
        #[command(subcommand)]
        command: CollectionCommands,
    },
    MigrateSqlite {
        #[arg(long)]
        sqlite: PathBuf,
        #[arg(long)]
        collection_name: String,
        #[arg(long)]
        collection_path: PathBuf,
        #[arg(long)]
        set_default: bool,
    },
    Serve {
        #[arg(long, default_value = "127.0.0.1:8787")]
        listen: SocketAddr,
    },
    Ask {
        #[arg(long, default_value = "agent")]
        source: String,
        #[arg(long, default_value = "approval")]
        kind: String,
        #[arg(long)]
        title: String,
        #[arg(long, default_value = "")]
        message: String,
        #[arg(long, default_value = "")]
        body: String,
        #[arg(long)]
        body_file: Option<PathBuf>,
        #[arg(long)]
        response_type: Option<String>,
        #[arg(long, hide = true)]
        schema: Option<PathBuf>,
        #[arg(long, default_value = "normal")]
        priority: String,
        #[arg(long, default_value = "")]
        dedupe_key: String,
        #[arg(long)]
        metadata: Option<PathBuf>,
        #[arg(long)]
        context: Option<PathBuf>,
        #[arg(long = "link")]
        links: Vec<String>,
        #[arg(long = "tag")]
        tags: Vec<String>,
        #[arg(long = "attach")]
        attachments: Vec<PathBuf>,
        #[arg(long)]
        json: bool,
    },
    Message {
        #[arg(long)]
        title: String,
        #[arg(long, default_value = "")]
        message: String,
        #[arg(long, default_value = "")]
        body: String,
        #[arg(long)]
        body_file: Option<PathBuf>,
        #[arg(long = "tag")]
        tags: Vec<String>,
        #[arg(long = "attach")]
        attachments: Vec<PathBuf>,
        #[arg(long)]
        json: bool,
    },
    #[command(alias = "list")]
    Inbox {
        #[arg(long, default_value = "pending")]
        status: String,
        #[arg(long, default_value_t = 50)]
        limit: usize,
        #[arg(long)]
        json: bool,
    },
    Show {
        request_id: String,
        #[arg(long)]
        json: bool,
    },
    Respond {
        request_id: String,
        #[arg(long, default_value = "callum")]
        responder: String,
        #[arg(long = "json")]
        payload: Option<String>,
        #[arg(long = "json-file")]
        payload_file: Option<PathBuf>,
        #[arg(long)]
        out_json: bool,
    },
    Response {
        request_id: String,
    },
    Wait {
        request_id: String,
        #[arg(long)]
        timeout: Option<String>,
        #[arg(long, default_value = "1s")]
        poll: String,
    },
    Events {
        #[arg(long, default_value_t = 0)]
        after: u64,
        #[arg(long, default_value_t = 50)]
        limit: usize,
    },
    Watch,
    Token,
    Types {
        #[arg(long)]
        json: bool,
    },
}

#[derive(Subcommand)]
enum CollectionCommands {
    List {
        #[arg(long)]
        json: bool,
    },
    Add {
        name: String,
        path: PathBuf,
        #[arg(long)]
        set_default: bool,
    },
    Use {
        name: String,
    },
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("pickle: {error:#}");
        std::process::exit(1);
    }
}

async fn run() -> Result<()> {
    let cli = Cli::parse();
    let collection_selector = cli.collection.clone();
    let collection_selector_ref = collection_selector.as_deref();
    match cli.command {
        Commands::Init {
            api_url,
            collection_name,
            collection_path,
            set_default,
            data,
        } => cmd_init(api_url, collection_name, collection_path, set_default, data),
        Commands::Collections { command } => cmd_collections(command),
        Commands::MigrateSqlite {
            sqlite,
            collection_name,
            collection_path,
            set_default,
        } => cmd_migrate_sqlite(sqlite, collection_name, collection_path, set_default),
        Commands::Serve { listen } => cmd_serve(listen).await,
        Commands::Ask {
            source,
            kind,
            title,
            message,
            body,
            body_file,
            response_type,
            schema: _schema,
            priority,
            dedupe_key,
            metadata,
            context,
            links,
            tags,
            attachments,
            json,
        } => cmd_ask(AskArgs {
            collection_selector: collection_selector.clone(),
            source,
            kind,
            title,
            message,
            body,
            body_file,
            response_type,
            priority,
            dedupe_key,
            metadata,
            context,
            links,
            tags,
            attachments,
            json,
        }),
        Commands::Message {
            title,
            message,
            body,
            body_file,
            tags,
            attachments,
            json,
        } => cmd_message(
            collection_selector_ref,
            title,
            message,
            body,
            body_file,
            tags,
            attachments,
            json,
        ),
        Commands::Inbox {
            status,
            limit,
            json,
        } => cmd_inbox(collection_selector_ref, &status, limit, json),
        Commands::Show { request_id, json } => cmd_show(collection_selector_ref, &request_id, json),
        Commands::Respond {
            request_id,
            responder,
            payload,
            payload_file,
            out_json,
        } => cmd_respond(
            collection_selector_ref,
            &request_id,
            &responder,
            payload,
            payload_file,
            out_json,
        ),
        Commands::Response { request_id } => cmd_response(collection_selector_ref, &request_id),
        Commands::Wait {
            request_id,
            timeout,
            poll,
        } => cmd_wait(collection_selector_ref, &request_id, timeout, &poll),
        Commands::Events { after, limit } => cmd_events(collection_selector_ref, after, limit),
        Commands::Watch => cmd_watch(collection_selector_ref),
        Commands::Token => cmd_token(),
        Commands::Types { json } => cmd_types(collection_selector_ref, json),
    }
}

fn cmd_init(
    api_url: Option<String>,
    collection_name: String,
    collection_path: Option<PathBuf>,
    set_default: bool,
    data: Option<PathBuf>,
) -> Result<()> {
    let (mut cfg, created) = config::ensure()?;
    if let Some(api_url) = api_url {
        cfg.api_url = api_url;
    }
    if let Some(collection_path) = collection_path {
        config::upsert_collection(
            &mut cfg,
            collection_name.clone(),
            collection_path,
            set_default,
        );
    } else if set_default {
        if !cfg.collections.contains_key(&collection_name) {
            return Err(anyhow!("unknown Pickle collection: {collection_name}"));
        }
        cfg.default_collection = collection_name;
    }
    if let Some(data) = data {
        cfg.data_path = Some(data);
    }
    config::save(&cfg)?;
    let (_, path) = config::collection_path(&cfg, Some(&cfg.default_collection))?;
    PickleCollection::new(&path).ensure()?;
    if created {
        println!("created {}", config::config_path().display());
    } else {
        println!("updated {}", config::config_path().display());
    }
    println!("default_collection {}", cfg.default_collection);
    for (name, collection) in &cfg.collections {
        println!("collection {name} {}", collection.path.display());
    }
    println!("api {}", cfg.api_url);
    Ok(())
}

async fn cmd_serve(listen: SocketAddr) -> Result<()> {
    let (cfg, _) = config::ensure()?;
    let mut collections = HashMap::new();
    for (name, configured) in &cfg.collections {
        let collection = PickleCollection::new(config::expand_tilde(configured.path.clone()));
        collection.ensure()?;
        collections.insert(name.clone(), collection);
    }
    eprintln!("pickled: listening on {listen}");
    eprintln!("pickled: default collection is {}", cfg.default_collection);
    server::listen_and_serve(listen, collections, cfg.default_collection, cfg.token).await
}

fn cmd_collections(command: CollectionCommands) -> Result<()> {
    let (mut cfg, _) = config::ensure()?;
    match command {
        CollectionCommands::List { json } => {
            if json {
                print_json(&json!({
                    "default_collection": cfg.default_collection,
                    "collections": cfg.collections,
                }))
            } else {
                for (name, collection) in &cfg.collections {
                    let marker = if name == &cfg.default_collection {
                        "*"
                    } else {
                        " "
                    };
                    println!("{marker} {name} {}", collection.path.display());
                }
                Ok(())
            }
        }
        CollectionCommands::Add {
            name,
            path,
            set_default,
        } => {
            config::upsert_collection(&mut cfg, name.clone(), path, set_default);
            config::save(&cfg)?;
            let (_, selected_path) = config::collection_path(&cfg, Some(&name))?;
            PickleCollection::new(selected_path).ensure()?;
            println!("added {name}");
            Ok(())
        }
        CollectionCommands::Use { name } => {
            if !cfg.collections.contains_key(&name) {
                return Err(anyhow!("unknown Pickle collection: {name}"));
            }
            cfg.default_collection = name.clone();
            config::save(&cfg)?;
            println!("default_collection {name}");
            Ok(())
        }
    }
}

fn cmd_migrate_sqlite(
    sqlite: PathBuf,
    collection_name: String,
    collection_path: PathBuf,
    set_default: bool,
) -> Result<()> {
    let report = pickle::legacy_sqlite::migrate_sqlite(&sqlite, &collection_path)?;
    let (mut cfg, _) = config::ensure()?;
    config::upsert_collection(
        &mut cfg,
        collection_name.clone(),
        collection_path,
        set_default,
    );
    config::save(&cfg)?;
    println!(
        "migrated {requests} requests, {responses} responses, {attachments} attachments to {collection_name}",
        requests = report.requests,
        responses = report.responses,
        attachments = report.attachments
    );
    Ok(())
}

struct AskArgs {
    collection_selector: Option<String>,
    source: String,
    kind: String,
    title: String,
    message: String,
    body: String,
    body_file: Option<PathBuf>,
    response_type: Option<String>,
    priority: String,
    dedupe_key: String,
    metadata: Option<PathBuf>,
    context: Option<PathBuf>,
    links: Vec<String>,
    tags: Vec<String>,
    attachments: Vec<PathBuf>,
    json: bool,
}

fn cmd_ask(args: AskArgs) -> Result<()> {
    let collection = open_collection(args.collection_selector.as_deref())?;
    let body = read_body(args.body, args.body_file)?;
    let request = collection.create_request(CreateRequest {
        source: args.source,
        kind: args.kind,
        title: args.title,
        message: args.message,
        body,
        priority: args.priority,
        response_type: args
            .response_type
            .unwrap_or_else(|| APPROVAL_RESPONSE_TYPE.to_string()),
        tags: args.tags,
        links: parse_links(args.links),
        attachments: read_attachment_files(args.attachments)?,
        metadata: read_json_file(args.metadata)?.unwrap_or_else(|| json!({})),
        context: read_json_file(args.context)?.unwrap_or_else(|| json!({})),
        dedupe_key: args.dedupe_key,
    })?;
    if args.json {
        print_json(&request)
    } else {
        println!("{}", request.id);
        Ok(())
    }
}

fn cmd_message(
    collection_selector: Option<&str>,
    title: String,
    message: String,
    body: String,
    body_file: Option<PathBuf>,
    tags: Vec<String>,
    attachments: Vec<PathBuf>,
    json_out: bool,
) -> Result<()> {
    let collection = open_collection(collection_selector)?;
    let body = read_body(body, body_file)?;
    let request = collection.create_request(CreateRequest {
        source: "callum".to_string(),
        kind: "message".to_string(),
        title,
        message,
        body,
        priority: "normal".to_string(),
        response_type: ACK_RESPONSE_TYPE.to_string(),
        tags,
        links: Vec::new(),
        attachments: read_attachment_files(attachments)?,
        metadata: json!({}),
        context: json!({}),
        dedupe_key: String::new(),
    })?;
    if json_out {
        print_json(&request)
    } else {
        println!("{}", request.id);
        Ok(())
    }
}

fn cmd_inbox(
    collection_selector: Option<&str>,
    status: &str,
    limit: usize,
    json_out: bool,
) -> Result<()> {
    let requests = open_collection(collection_selector)?.list_requests(status, limit)?;
    if json_out {
        return print_json(&json!({ "requests": requests }));
    }
    for request in requests {
        println!(
            "{}  {:<8} {:<10} {}  {}",
            request.id,
            request.state.as_str(),
            request.source,
            request
                .created_at
                .with_timezone(&chrono::Local)
                .format("%Y-%m-%d %H:%M"),
            request.title
        );
    }
    Ok(())
}

fn cmd_show(collection_selector: Option<&str>, request_id: &str, json_out: bool) -> Result<()> {
    let request = open_collection(collection_selector)?.get_request_by_id(request_id)?;
    if json_out {
        return print_json(&request);
    }
    println!("{}\n{}\n", request.id, request.title);
    println!(
        "source: {}\nkind: {}\nstate: {}\npriority: {}\nresponse_type: {}\ncreated: {}",
        request.source,
        request.kind,
        request.state.as_str(),
        request.priority,
        request.response_type,
        request
            .created_at
            .with_timezone(&chrono::Local)
            .to_rfc3339()
    );
    if !request.message.is_empty() {
        println!("\n{}", request.message);
    }
    if !request.body.is_empty() && request.body != request.message {
        println!("\n{}", request.body);
    }
    if !request.attachments.is_empty() {
        println!("\nattachments:");
        for attachment in &request.attachments {
            println!(
                "- {}  {}  {} bytes  {}",
                attachment.id, attachment.filename, attachment.size_bytes, attachment.content_type
            );
        }
    }
    if let Some(response) = request.response {
        println!(
            "\nresponse by {} at {}:",
            response.responder,
            response
                .created_at
                .with_timezone(&chrono::Local)
                .to_rfc3339()
        );
        print_json(&response.payload)?;
    }
    Ok(())
}

fn cmd_respond(
    collection_selector: Option<&str>,
    request_id: &str,
    responder: &str,
    payload: Option<String>,
    payload_file: Option<PathBuf>,
    out_json: bool,
) -> Result<()> {
    let payload = read_response_payload(payload, payload_file)?;
    let request = open_collection(collection_selector)?.respond(
        request_id,
        CreateResponse {
            responder: responder.to_string(),
            payload,
        },
    )?;
    if out_json {
        print_json(&request)
    } else {
        println!("answered {}", request.id);
        Ok(())
    }
}

fn cmd_response(collection_selector: Option<&str>, request_id: &str) -> Result<()> {
    let payload = open_collection(collection_selector)?.response_payload(request_id)?;
    print_json(&payload)
}

fn cmd_wait(
    collection_selector: Option<&str>,
    request_id: &str,
    timeout: Option<String>,
    poll: &str,
) -> Result<()> {
    let timeout = timeout.as_deref().map(parse_duration).transpose()?;
    let poll = parse_duration(poll)?;
    let payload =
        open_collection(collection_selector)?.wait_for_response(request_id, timeout, poll)?;
    print_json(&payload)
}

fn cmd_events(collection_selector: Option<&str>, after: u64, limit: usize) -> Result<()> {
    let events = open_collection(collection_selector)?.events_after(after, limit)?;
    print_json(&json!({ "events": events }))
}

fn cmd_watch(collection_selector: Option<&str>) -> Result<()> {
    let collection = open_collection(collection_selector)?;
    let mut after = 0;
    loop {
        for event in collection.events_after(after, 100)? {
            after = after.max(event.id);
            print_json(&event)?;
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn cmd_token() -> Result<()> {
    let (cfg, _) = config::ensure()?;
    println!("{}", cfg.token);
    Ok(())
}

fn cmd_types(collection_selector: Option<&str>, json_out: bool) -> Result<()> {
    let types = open_collection(collection_selector)?.list_type_definitions()?;
    if json_out {
        print_json(&json!({ "types": types }))
    } else {
        for definition in types {
            println!("{}", definition.name);
        }
        Ok(())
    }
}

fn open_collection(selector: Option<&str>) -> Result<PickleCollection> {
    let (cfg, _) = config::ensure()?;
    let (_, path) = config::collection_path(&cfg, selector)?;
    let collection = PickleCollection::new(path);
    collection.ensure()?;
    Ok(collection)
}

fn read_body(body: String, body_file: Option<PathBuf>) -> Result<String> {
    if let Some(path) = body_file {
        return fs::read_to_string(path).context("read body file");
    }
    Ok(body)
}

fn read_json_file(path: Option<PathBuf>) -> Result<Option<Value>> {
    path.map(|path| {
        let data = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
        serde_json::from_str(&data).with_context(|| format!("parse {}", path.display()))
    })
    .transpose()
}

fn read_response_payload(payload: Option<String>, payload_file: Option<PathBuf>) -> Result<Value> {
    let raw = if let Some(path) = payload_file {
        fs::read_to_string(path)?
    } else {
        payload.ok_or_else(|| anyhow!("--json or --json-file is required"))?
    };
    serde_json::from_str(&raw).context("response payload is not valid JSON")
}

fn parse_links(values: Vec<String>) -> Vec<Link> {
    values
        .into_iter()
        .map(|raw| {
            let (label, target) = raw.split_once('=').unwrap_or((&raw, &raw));
            let target = target.trim();
            if target.starts_with("http://") || target.starts_with("https://") {
                Link {
                    label: label.trim().to_string(),
                    url: target.to_string(),
                    path: String::new(),
                }
            } else {
                Link {
                    label: label.trim().to_string(),
                    url: String::new(),
                    path: target.to_string(),
                }
            }
        })
        .collect()
}

fn read_attachment_files(paths: Vec<PathBuf>) -> Result<Vec<CreateAttachment>> {
    let mut attachments = Vec::new();
    for path in paths {
        let data = fs::read(&path).with_context(|| format!("read {}", path.display()))?;
        let filename = path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("attachment")
            .to_string();
        let content_type = mime_guess::from_path(&path)
            .first_or_octet_stream()
            .essence_str()
            .to_string();
        attachments.push(CreateAttachment {
            filename,
            content_type,
            data_base64: String::new(),
            data,
        });
    }
    Ok(attachments)
}

fn parse_duration(value: &str) -> Result<Duration> {
    let value = value.trim();
    let (number, multiplier) = if let Some(number) = value.strip_suffix("ms") {
        (number, 1_u64)
    } else if let Some(number) = value.strip_suffix('s') {
        (number, 1_000)
    } else if let Some(number) = value.strip_suffix('m') {
        (number, 60_000)
    } else if let Some(number) = value.strip_suffix('h') {
        (number, 3_600_000)
    } else {
        (value, 1_000)
    };
    let number = number
        .parse::<u64>()
        .with_context(|| format!("invalid duration: {value}"))?;
    Ok(Duration::from_millis(number.saturating_mul(multiplier)))
}

fn print_json(value: impl serde::Serialize) -> Result<()> {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    serde_json::to_writer_pretty(&mut lock, &value)?;
    writeln!(lock)?;
    Ok(())
}
