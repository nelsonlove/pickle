# User Service

Wickle can run as a normal user service. Install the binary somewhere stable,
then create `~/.config/systemd/user/wickle.service`:

```ini
[Unit]
Description=Wickle agent inbox daemon

[Service]
ExecStart=%h/.local/bin/wickle serve --listen 0.0.0.0:8787
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Enable it:

```bash
systemctl --user daemon-reload
systemctl --user enable --now wickle.service
systemctl --user status wickle.service
```

Use `wickle token` to copy the Android bearer token. Keep the listener on
Tailscale or another private network.
